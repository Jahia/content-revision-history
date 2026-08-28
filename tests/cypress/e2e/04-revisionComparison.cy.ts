import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins, setNodeProperty} from '@jahia/cypress';

/**
 * Coverage for the two halves of the module actually being joined up, and for the comparison
 * that join makes possible.
 *
 * Before this file the snapshot store and the editorial revision list were unconnected: nothing
 * ever wrote the entry/snapshot link, so every "Compare" control was a dead end. Nothing failed,
 * because nothing asserted it -- which is the same blind spot that previously let a blank Compare
 * button ship twice with the suite green. These tests assert the join itself, not just its parts.
 *
 * Order matters within this file: each test builds on the state the previous one published. That
 * is deliberate -- binding is defined by what was already bound when a capture ran, so it can
 * only be tested as a sequence. The file uses its own scratch page and never touches the pages
 * the other specs rely on.
 */
describe('Revision comparison (entry binding + diff viewer)', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-compare`;
    const areaPath = `${pagePath}/area-main`;
    const historyPath = `${areaPath}/history`;
    const textPath = `${areaPath}/policyText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;

    const captureTimeoutMs = 60000;
    const pollIntervalMs = 1000;
    /** Comfortably past MIN_CAPTURE_INTERVAL_MILLIS, so a publish is never RATE_LIMITED. */
    const captureRateLimitGraceMs = 2500;

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface AddNodeQueryData {
        jcr: { addNode: { uuid: string } }
    }

    interface SnapshotsQueryData {
        jcr: {
            nodeByPath?: {
                children: {
                    nodes: Array<{
                        name: string
                        entryRefs?: { values: string[] } | null
                    }>
                }
            } | null
        }
    }

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;

    const reorderMutation = gql`
        mutation($path: String!, $names: [String]!) {
            jcr { mutateNode(pathOrId: $path) { reorderChildren(names: $names) } }
        }
    `;

    const snapshotsQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children {
                        nodes {
                            name
                            entryRefs: property(name: "crh:entryRefs") { values }
                        }
                    }
                }
            }
        }
    `;

    let pageUuid = '';
    let firstEntryUuid = '';
    let secondEntryUuid = '';
    let lastPublishAt = 0;

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((result: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
            const uuid = result.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    /** Entry uuid -> the snapshot node bound to it. */
    const bindings = (): Cypress.Chainable<Record<string, string>> =>
        cy.apollo({query: snapshotsQuery, variables: {path: folderPath()}}).then((result: ApolloResult<SnapshotsQueryData>) => {
            const map: Record<string, string> = {};
            for (const node of result.data?.jcr?.nodeByPath?.children.nodes ?? []) {
                for (const entryUuid of node.entryRefs?.values ?? []) {
                    map[entryUuid] = node.name;
                }
            }

            return map;
        });

    const pollUntil = <T>(fetch: () => Cypress.Chainable<T>, predicate: (value: T) => boolean, errorMsg: string): Cypress.Chainable<T> =>
        cy
            .waitUntil<T | false>(() => fetch().then(value => (predicate(value) ? value : false)), {
                timeout: captureTimeoutMs, interval: pollIntervalMs, errorMsg, verbose: true
            })
            .then(value => value as T);

    /** Paces publishes past the module's per-page-and-language rate limiter. */
    const publishTriggeringCapture = (): Cypress.Chainable<boolean> => {
        const waitMs = Math.max(0, captureRateLimitGraceMs - (Date.now() - lastPublishAt));

        return cy
            .waitUntil<boolean>(() => Date.now() - lastPublishAt >= captureRateLimitGraceMs, {
                timeout: waitMs + 5000, interval: 100,
                errorMsg: 'could not pace this publish past the module\'s capture rate limiter'
            })
            .then(() => {
                publishAndWaitJobEnding(pagePath, [language]);
                return cy.wrap(true, {log: false}).then(published => {
                    lastPublishAt = Date.now();
                    return published;
                });
            });
    };

    /** Renders the LIVE page -- the public surface the comparison is actually served from. */
    const renderLive = (query = ''): Cypress.Chainable<string> =>
        cy.request<string>({url: `/cms/render/live/${language}${pagePath}.html${query}`}).then(r => r.body);

    /**
     * Puts the history in the newest-first order both views document and depend on.
     *
     * Entries are authored oldest-first over time, so this is the real editorial gesture, not
     * test scaffolding -- and it doubles as the regression test for `orderable` on
     * crh:revisionHistory. Extending jmix:list does NOT make a type orderable; while the CND
     * lacked the keyword, Jackrabbit rejected this outright with "child node ordering not
     * supported", which meant editors could not honour the convention at all.
     */
    const reorderNewestFirst = (names: string[]) =>
        cy
            .apollo({mutation: reorderMutation, variables: {path: historyPath, names}})
            .then((result: ApolloResult<unknown>) => {
                expect(result.errors, 'crh:revisionHistory must accept child reordering').to.be.undefined;
            });

    const addEntry = (name: string, label: string, summary: string) =>
        addNode({
            parentPathOrId: historyPath,
            primaryNodeType: 'crh:revisionEntry',
            name,
            properties: [
                {name: 'revisionLabel', value: label},
                {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                {name: 'changeType', value: 'substantive'},
                {name: 'summary', value: summary, language}
            ]
        });

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-compare',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - comparison', language},
                // 'home', not 'default'. The digitall template set has no template called
                // 'default', and an unresolvable template makes the LIVE .html render return
                // 404 -- while the .markdown render (which uses this module's own views and no
                // page template) still succeeds. Every assertion in this file that reads the
                // rendered page depends on this value being a template the site actually has.
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
                    properties: [{name: 'text', value: '<p>Support lasts twelve months after release.</p>', language}]
                })
            )
            .then(() => addNode({parentPathOrId: areaPath, primaryNodeType: 'crh:revisionHistory', name: 'history'}))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the revision history container must be creatable').to.be.undefined;
                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;
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

    // ---------------------------------------------------------------- binding

    it('binds a revision entry to the snapshot captured for the publication that carried it', () => {
        // The core of what was missing: the editorial half and the captured half joined.
        addEntry('rev-a', '1.0', '<p>Initial publication.</p>')
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the first entry must be creatable').to.be.undefined;
                firstEntryUuid = result.data?.jcr.addNode.uuid as string;

                return publishTriggeringCapture();
            })
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[firstEntryUuid]),
                    `expected the entry to be bound to a snapshot within ${captureTimeoutMs}ms`
                )
            )
            .then(map => {
                expect(map[firstEntryUuid], 'the entry must name the snapshot it describes').to.be.a('string').and.not
                    .be.empty;
            });
    });

    it('leaves an already-bound entry on its own snapshot when a later capture stores a new one', () => {
        // Binding must be append-only. Rebinding would silently rewrite what an existing public
        // revision claims the page said -- the one thing an evidentiary record may never do.
        let originalSnapshot = '';

        bindings()
            .then(map => {
                originalSnapshot = map[firstEntryUuid];
                expect(originalSnapshot, 'the previous test must have bound the first entry').to.not.be.empty;

                // A real content change, so capture stores a genuinely new snapshot.
                return setNodeProperty(textPath, 'text', '<p>Support lasts eighteen months after release.</p>', language);
            })
            .then(() => addEntry('rev-b', '1.1', '<p>Extended the support window.</p>'))
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the second entry must be creatable').to.be.undefined;
                secondEntryUuid = result.data?.jcr.addNode.uuid as string;

                // Newest first. Without this the list reads oldest-first and "previous" resolves
                // backwards: 1.0 would be compared against 1.1, and 1.1 -- as the last child --
                // would report itself as the earliest revision. Exactly inverted, and silently so.
                return reorderNewestFirst(['rev-b', 'rev-a']);
            })
            .then(() => publishTriggeringCapture())
            .then(() =>
                pollUntil(
                    bindings,
                    map => Boolean(map[secondEntryUuid]),
                    `expected the second entry to be bound within ${captureTimeoutMs}ms`
                )
            )
            .then(map => {
                expect(map[secondEntryUuid], 'the new entry must bind to the new snapshot').to.not.equal(
                    originalSnapshot
                );
                expect(map[firstEntryUuid], 'an already-bound entry must never be rebound').to.equal(originalSnapshot);
            });
    });

    // ---------------------------------------------------------------- diff viewer

    it('renders a word-level comparison between the two revisions', () => {
        renderLive(`?crhDiff=${secondEntryUuid}`).then(html => {
            // The id is qualified with the history node's identifier: the component is droppable,
            // so a page may carry more than one and a fixed id would be emitted twice.
            expect(html, 'the comparison panel must render').to.contain('id="crh-diff-panel-');
            // The change was one word, so exactly one word must be highlighted on each side.
            // Whole-line highlighting here would mean the word diff silently degraded.
            expect(html, 'the removed word must be marked').to.contain('<mark>twelve</mark>');
            expect(html, 'the added word must be marked').to.contain('<mark>eighteen</mark>');
            // Semantics, not colour (SC 1.4.1), plus text labels for assistive technology.
            expect(html, 'removals must use <del>').to.contain('<del>');
            expect(html, 'additions must use <ins>').to.contain('<ins>');
            expect(html, 'a text alternative to the +/- markers must be present').to.contain('Removed line:');
            // The active link is marked so a visitor returning to the list can tell which of N
            // identically-shaped links produced the panel. This also guards a bug that failed
            // silently: the entry view is a nested <template:module> render, and ${param.crhDiff}
            // does not survive into it, so reading the parameter there yielded an empty value and
            // simply omitted the attribute with no error anywhere.
            expect(
                (html.match(/aria-current="true"/g) ?? []).length,
                'exactly one Compare link must be marked as current'
            ).to.equal(1);
        });
    });

    it('renders no comparison panel at all when no revision was asked for', () => {
        renderLive().then(html => {
            expect(html, 'the revision list must still render').to.contain('crh-revision-list');
            expect(html, 'the panel must appear only on request').to.not.contain('id="crh-diff-panel-');
            expect(html, 'no link may claim to be current when nothing was requested').to.not.contain('aria-current');
        });
    });

    it('refuses an identifier that is not an entry of this history', () => {
        // The containment check is the access control: the service reads with a SYSTEM session,
        // so without it a crafted identifier would render an arbitrary node's content on a
        // public page. The page's own uuid is a real, readable node -- and not an entry here.
        renderLive(`?crhDiff=${pageUuid}`).then(html => {
            expect(html, 'the panel must report the refusal rather than render nothing').to.contain(
                'That revision could not be found in this history.'
            );
            expect(html, 'no comparison may be produced for a foreign node').to.not.contain('<mark>');
        });
    });

    it('refuses a malformed identifier without erroring the page', () => {
        renderLive('?crhDiff=not-a-uuid%27%20or%201%3D1').then(html => {
            expect(html, 'a malformed identifier must be refused like any other').to.contain(
                'That revision could not be found in this history.'
            );
            expect(html, 'the rest of the page must still render').to.contain('crh-revision-list');
        });
    });

    it('explains, rather than compares, when the requested revision is the earliest one', () => {
        renderLive(`?crhDiff=${firstEntryUuid}`).then(html => {
            expect(html, 'the earliest revision must be explained, not silently blank').to.contain(
                'This is the earliest recorded revision'
            );
            expect(html, 'nothing may be compared against a revision that has no predecessor').to.not.contain(
                '<mark>'
            );
        });
    });

    // ---------------------------------------------------------------- snapshot hygiene

    it('keeps the revision list itself out of the snapshots it describes', () => {
        // Without a dedicated markdown view, crh:revisionHistory falls through to the generic
        // jnt:content fallback and the list is captured into the record it describes: publishing
        // a revision entry would then change the page, and every comparison would show the
        // changelog instead of the change.
        cy.request<string>({url: `/cms/render/live/${language}${pagePath}.markdown`}).then(response => {
            expect(response.body, 'the version labels must not appear in the captured Markdown').to.not.contain('1.1');
            expect(response.body, 'entry summaries must not appear in the captured Markdown').to.not.contain(
                'Extended the support window'
            );
            expect(response.body, 'the page content itself must still be captured').to.contain('eighteen months');
        });
    });
});
