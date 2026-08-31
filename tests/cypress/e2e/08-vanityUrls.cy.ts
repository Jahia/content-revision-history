import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins} from '@jahia/cypress';

/**
 * A revisioned page that also has vanity URLs.
 *
 * This combination broke the backfill twice and had no coverage either time. Adding
 * jmix:vanityUrlMapped gives the page a `vanityUrlMapping` child whose name does NOT start with
 * `j:`, so the name-based filter the walk used to rely on stepped straight into it; its children are
 * named after the URL and contain SPACES, which produced a request Jahia rejects; and both types are
 * mix:versionable, so their checkpoints joined the candidate instants -- moments when a URL changed
 * and no page content did.
 *
 * jnt:vanityUrls and jnt:vanityUrl extend nt:base, NOT jnt:content, so nothing in the markdown
 * template type can render them. The backfill now filters on that rather than on the node name.
 *
 * These assertions are structural on purpose. The backfill script itself runs in the Groovy console
 * and is out of reach from here, so what is pinned is the shape it has to cope with: if a Jahia
 * upgrade changes how a vanity URL is stored, this fails and the script gets revisited.
 */
describe('A revisioned page that also has vanity URLs', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-vanity`;
    const areaPath = `${pagePath}/area-main`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    /** A slash becomes a space in the node name, which is what made the URL unformable. */
    const vanityUrl = '/crh-e2e/vanity path';
    const vanityNodeName = 'crh-e2e vanity path';

    const captureTimeoutMs = 60000;

    interface ApolloResult<T> { data?: T; errors?: Array<{message: string}> }
    interface AddNodeQueryData { jcr: { addNode: { uuid: string } } }
    interface ChildrenData {
        jcr: {
            nodeByPath?: {
                children: {
                    nodes: Array<{
                        name: string
                        primaryNodeType: {name: string}
                        isVanity: boolean
                        isContent: boolean
                        children: {nodes: Array<{name: string, primaryNodeType: {name: string}, isVanity: boolean}>}
                    }>
                }
            } | null
        }
    }
    interface SnapshotsData {
        jcr: {
            nodeByPath?: {
                children: {nodes: Array<{name: string, markdown?: {value: string} | null}>}
            } | null
        }
    }

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;

    const addVanityMutation = gql`
        mutation($path: String!, $url: String!, $language: String!) {
            jcr {
                mutateNode(pathOrId: $path) {
                    addVanityUrl(vanityUrlInputList: [
                        {language: $language, url: $url, active: true, defaultMapping: true}
                    ]) { uuid }
                }
            }
        }
    `;

    /** Asks the repository the same questions the backfill has to ask about each child. */
    const pageChildrenQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children {
                        nodes {
                            name
                            primaryNodeType { name }
                            isVanity: isNodeType(type: {types: ["jnt:vanityUrls"], multi: ANY})
                            isContent: isNodeType(type: {types: ["jnt:content"], multi: ANY})
                            children {
                                nodes {
                                    name
                                    primaryNodeType { name }
                                    isVanity: isNodeType(type: {types: ["jnt:vanityUrl"], multi: ANY})
                                }
                            }
                        }
                    }
                }
            }
        }
    `;

    const snapshotsQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children { nodes { name markdown: property(name: "crh:markdown") { value } } }
                }
            }
        }
    `;

    let pageUuid = '';

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((r: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
            const uuid = r.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    const snapshots = (): Cypress.Chainable<Array<{name: string, markdown: string}>> =>
        cy.apollo({query: snapshotsQuery, variables: {path: folderPath()}}).then((r: ApolloResult<SnapshotsData>) =>
            (r.data?.jcr?.nodeByPath?.children.nodes ?? [])
                .filter(n => !n.name.startsWith('j:'))
                .map(n => ({name: n.name, markdown: n.markdown?.value ?? ''})));

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-vanity',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - vanity', language},
                {name: 'j:templateName', value: 'home'}
            ],
            mixins: [revisionedMixin]
        })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the scratch page must be creatable').to.be.undefined;
                return addNode({parentPathOrId: pagePath, primaryNodeType: 'jnt:contentList', name: 'area-main'});
            })
            .then(() =>
                addNode({
                    parentPathOrId: areaPath,
                    primaryNodeType: 'jnt:bigText',
                    name: 'policyText',
                    properties: [{name: 'text', value: '<p>Wording that must reach the snapshot.</p>', language}]
                })
            )
            .then(() => cy.apollo({mutation: addVanityMutation, variables: {path: pagePath, url: vanityUrl, language}}))
            .then((result: ApolloResult<unknown>) => {
                expect(result.errors, 'the vanity URL must be creatable').to.be.undefined;
                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;
                publishAndWaitJobEnding(pagePath, [language]);
                return cy.waitUntil<Array<{name: string, markdown: string}> | false>(
                    () => snapshots().then(s => (s.length > 0 ? s : false)),
                    {
                        timeout: captureTimeoutMs,
                        interval: 1000,
                        errorMsg: 'a page with vanity URLs must still be captured',
                        verbose: true
                    }
                );
            });
    });

    beforeEach(() => {
        cy.login();
    });

    after(() => {
        cy.login();
        removeMixins(pagePath, [revisionedMixin]).then(null, () => undefined);
        deleteNode(pagePath).then(null, () => undefined);
        deleteNode(pagePath, 'LIVE').then(null, () => undefined);
        if (pageUuid) {
            deleteNode(`${historyRoot}/${pageUuid}`).then(null, () => undefined);
        }
    });

    it('still captures a snapshot for a page that has vanity URLs', () => {
        snapshots().then(s => {
            expect(s.length, 'the page must have been captured').to.be.greaterThan(0);
        });
    });

    it('captures the page content and not the vanity URL', () => {
        // The vanity node is not page content. If it ever reaches a snapshot, the record claims the
        // page said something it never displayed.
        snapshots().then(s => {
            const md = s.map(x => x.markdown).join('\n');
            expect(md, 'the page content must be in the snapshot').to.contain('Wording that must reach the snapshot.');
            expect(md, 'the vanity URL must not be').to.not.contain('vanity path');
            expect(md, 'nor the mapping node').to.not.contain('vanityUrlMapping');
        });
    });

    it('stores the mapping in a child whose name does not start with j:', () => {
        // This is why filtering the walk on the node name was not enough: `vanityUrlMapping` passes
        // that test, so the walk descended into it and asked Jahia to render a vanity URL.
        cy.apollo({query: pageChildrenQuery, variables: {path: pagePath}})
            .then((r: ApolloResult<ChildrenData>) => {
                const nodes = r.data?.jcr?.nodeByPath?.children.nodes ?? [];
                const mapping = nodes.find(n => n.isVanity);
                expect(mapping, 'the page must have a vanity mapping child').to.not.be.undefined;
                expect(mapping!.name, 'its name must not start with j:').to.not.match(/^j:/);
                expect(mapping!.primaryNodeType.name).to.equal('jnt:vanityUrls');
            });
    });

    it('is not jnt:content, which is what the backfill filters on instead', () => {
        // Both vanity types extend nt:base. Nothing in the markdown template type can
        // render one, and asking answers 401 rather than anything a reader could interpret.
        cy.apollo({query: pageChildrenQuery, variables: {path: pagePath}})
            .then((r: ApolloResult<ChildrenData>) => {
                const mapping = (r.data?.jcr?.nodeByPath?.children.nodes ?? []).find(n => n.isVanity);
                expect(mapping!.isContent, 'a vanity mapping must not be jnt:content').to.be.false;
            });
    });

    it('names the vanity node after the URL, spaces and all', () => {
        // A slash becomes a space. Interpolating that into a render URL unencoded produces a request
        // curl cannot even form -- it returns 000 -- which is why path segments are percent-encoded.
        cy.apollo({query: pageChildrenQuery, variables: {path: pagePath}})
            .then((r: ApolloResult<ChildrenData>) => {
                const mapping = (r.data?.jcr?.nodeByPath?.children.nodes ?? []).find(n => n.isVanity);
                const urls = mapping!.children.nodes.filter(c => c.isVanity);
                expect(urls.length, 'the mapping must hold the vanity URL').to.be.greaterThan(0);
                expect(urls.map(u => u.name), 'the node name carries the spaces').to.include(vanityNodeName);
                expect(urls[0].primaryNodeType.name).to.equal('jnt:vanityUrl');
            });
    });
});
