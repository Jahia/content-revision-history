import gql from 'graphql-tag';
import {
    addMixins,
    deleteNode,
    enableModule,
    publishAndWaitJobEnding,
    setNodeProperty
} from '@jahia/cypress';

/**
 * Capture behaviour of the Content Revision History module.
 *
 * The module snapshots a page as Markdown on the first live render after publication and
 * stores it, deduped on a content hash, under
 * /sites/<site>/contents/revision-history/<pageUuid>/<lang>/<timestamp> in the DEFAULT
 * workspace (snapshots are never published).
 */
describe('Revision snapshot capture', () => {
    const siteKey = 'digitall';
    const pagePath = `/sites/${siteKey}/home/demo-roles-and-users`;
    const bigTextPath = `${pagePath}/area-main/bigText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    // Query the site's contents folder, never revision-history itself: the root does not
    // exist until the first capture, and nodeByPath on a missing path is a GraphQL error.
    const contentsPath = `/sites/${siteKey}/contents`;

    const snapshotsQuery = gql`
        query snapshots($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    descendants(typesFilter: {types: ["crh:revisionSnapshot"]}) {
                        nodes {
                            name
                            properties {
                                name
                                value
                            }
                        }
                    }
                }
            }
        }
    `;

    interface Snapshot {
        name: string;
        markdown: string;
        contentHash: string;
    }

    const listSnapshots = (): Cypress.Chainable<Snapshot[]> =>
        cy
            .apollo({query: snapshotsQuery, variables: {path: contentsPath}})
            .then((result: any) => {
                const nodes = result?.data?.jcr?.nodeByPath?.descendants?.nodes ?? [];
                return nodes
                    .map((node: any) => {
                        const props: Record<string, string> = {};
                        for (const p of node.properties ?? []) {
                            if (p.value) {
                                props[p.name] = p.value;
                            }
                        }
                        return {
                            name: node.name,
                            markdown: props.markdown ?? '',
                            contentHash: props.contentHash ?? ''
                        };
                    })
                    .sort((a: Snapshot, b: Snapshot) => a.name.localeCompare(b.name));
            });

    /**
     * Renders the page in live with a unique query string.
     *
     * The cache-buster is required, not cosmetic: Jahia's HTML output cache short-circuits the
     * render chain, and the capture filter runs late in that chain, so a cached page produces
     * no capture at all. Without a forced miss this suite would pass vacuously.
     */
    const renderLive = () =>
        cy.request({
            url: `/cms/render/live/en/sites/${siteKey}/home/demo-roles-and-users.html`,
            qs: {cb: String(Date.now()) + String(Math.random()).slice(2)},
            failOnStatusCode: true
        });

    before(() => {
        cy.login();
        // Enable the module on the site HERE, not in provisioning.yml: the harness installs
        // the module after the manifest runs, so a provisioning `enable` step silently
        // no-ops. A module's views and render filters only apply to sites where it is
        // enabled -- without this the module looks healthy and every assertion fails.
        enableModule('content-revision-history', siteKey);
        // Start from a known-empty history so counts are meaningful.
        deleteNode(historyRoot).then(null, () => undefined);
        addMixins(pagePath, ['jmix:publiclyRevisioned']);
        publishAndWaitJobEnding(pagePath, ['en']);
    });

    beforeEach(() => {
        cy.login();
    });

    it('captures a snapshot on the first live render after publication', () => {
        // Act
        renderLive();

        // Assert
        listSnapshots().then(snapshots => {
            expect(snapshots.length, 'a snapshot should have been stored').to.be.greaterThan(0);
            expect(snapshots[snapshots.length - 1].markdown).to.contain('Demo Roles and Users');
        });
    });

    it('stores the snapshot under revision-history/<pageUuid>/<lang>/<timestamp>', () => {
        cy.apollo({
            query: gql`
                query historyTree($path: String!) {
                    jcr(workspace: EDIT) {
                        nodeByPath(path: $path) {
                            children {
                                nodes {
                                    name
                                    primaryNodeType {
                                        name
                                    }
                                    children {
                                        nodes {
                                            name
                                            primaryNodeType {
                                                name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            `,
            variables: {path: historyRoot}
        }).then((result: any) => {
            const perPage = result?.data?.jcr?.nodeByPath?.children?.nodes ?? [];
            expect(perPage.length, 'one folder per versioned page').to.be.greaterThan(0);
            expect(perPage[0].primaryNodeType.name).to.equal('crh:snapshotFolder');
            // Keyed on UUID so the history survives a page rename or move.
            expect(perPage[0].name).to.match(/^[0-9a-f-]{36}$/);

            const perLanguage = perPage[0].children.nodes;
            expect(perLanguage.map((n: any) => n.name)).to.include('en');
        });
    });

    it('does not store a second snapshot when the content has not changed', () => {
        listSnapshots().then(before => {
            // Guard: 0 === 0 would pass vacuously if capture were broken entirely.
            expect(before.length, 'precondition: at least one snapshot exists').to.be.greaterThan(
                0
            );

            // Act -- a fresh render, but identical content
            renderLive();

            // Assert -- deduped on content hash
            listSnapshots().then(after => {
                expect(after.length, 'identical content must not be stored twice').to.equal(
                    before.length
                );
            });
        });
    });

    it('stores a new snapshot when the page content changes', () => {
        const marker = `revision-history e2e marker ${Date.now()}`;

        listSnapshots().then(before => {
            // Arrange
            setNodeProperty(bigTextPath, 'text', `<p>${marker}</p>`, 'en');
            publishAndWaitJobEnding(pagePath, ['en']);

            // Act
            renderLive();

            // Assert
            listSnapshots().then(after => {
                expect(after.length, 'a content change must produce a snapshot').to.equal(
                    before.length + 1
                );
                const newest = after[after.length - 1];
                expect(newest.markdown).to.contain(marker);
                expect(newest.contentHash).to.not.equal(
                    before.length > 0 ? before[before.length - 1].contentHash : ''
                );
            });
        });
    });

    it('produces Markdown with the heading on its own line', () => {
        // Regression: the page H1 used to fuse onto the first child's text
        // ("# Demo Roles and UsersSUPPORT-666 ..."), which corrupts every diff.
        listSnapshots().then(snapshots => {
            const markdown = snapshots[snapshots.length - 1].markdown;

            expect(markdown, 'starts with an H1').to.match(/^# \S/);
            const firstLine = markdown.split('\n')[0];
            expect(firstLine, 'heading line holds only the title').to.equal(
                '# Demo Roles and Users'
            );
        });
    });

    it('records a content hash and a generator version on every snapshot', () => {
        cy.apollo({query: snapshotsQuery, variables: {path: contentsPath}}).then((result: any) => {
            const nodes = result?.data?.jcr?.nodeByPath?.descendants?.nodes ?? [];
            expect(nodes.length).to.be.greaterThan(0);

            for (const node of nodes) {
                const names = (node.properties ?? []).map((p: any) => p.name);
                // generatorVersion lets the diff viewer flag a formatting change instead of
                // reporting spurious content churn; contentHash is the dedupe key.
                expect(names, `${node.name} properties`).to.include('contentHash');
                expect(names, `${node.name} properties`).to.include('generatorVersion');
                expect(names, `${node.name} properties`).to.include('language');
            }
        });
    });
});
