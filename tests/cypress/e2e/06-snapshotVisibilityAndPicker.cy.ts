import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins, setNodeProperty} from '@jahia/cypress';

/**
 * Coverage for the two things that make backfilled history usable, both of which shipped broken.
 *
 * 1.3.0 dropped jmix:hiddenType from the snapshot types so the store could be browsed, and the
 * FOLDER was verified against jContent's browse filter. The snapshots inside it were not. That
 * filter is includeTypes [jmix:droppableContent, jnt:page, jnt:file] and crh:revisionSnapshot was
 * deliberately not droppable, so the release shipped with every snapshot present and none shown --
 * folders that open onto an empty list. Nothing failed, because nothing asserted it.
 *
 * The picker had the same shape of gap: it was verified through its own API and never through the
 * call jContent actually makes, so "the dropdown works" rested on an assumption about which query
 * the editor issues. These tests use forms.fieldConstraints, which IS that call.
 *
 * Order matters within this file: binding is defined by what was already bound when a capture ran,
 * so the pin tests can only be written as a sequence. The file uses its own scratch page.
 */
describe('Snapshot visibility and the revision-entry snapshot picker', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-picker`;
    const areaPath = `${pagePath}/area-main`;
    const historyPath = `${areaPath}/history`;
    const textPath = `${areaPath}/policyText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;

    const captureTimeoutMs = 60000;
    const pollIntervalMs = 1000;
    const captureRateLimitGraceMs = 2500;

    /**
     * The filters jContent's CONTENT BROWSER uses, measured on 8.2.3.2.
     *
     * An earlier version of this file used ['jmix:droppableContent', 'jnt:page', 'jnt:file'], which
     * is the content PICKER's filter, not the browser's. These tests therefore passed while the
     * tree was empty on screen, through two releases. 07-jcontentUi.cy.ts is the authority now --
     * it loads jContent in a browser and cannot be satisfied by a filter invented here. These two
     * stay because they localise such a failure to the node types instead of to the UI.
     */
    const JCONTENT_FLAT_CONTENT = ['jmix:editorialContent', 'jmix:queryContent'];
    const JCONTENT_TREE_RECURSION = ['jnt:page', 'jnt:contentFolder', 'jnt:folder'];

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }
    interface AddNodeQueryData { jcr: { addNode: { uuid: string } } }
    interface ChildrenData {
        jcr: { nodeByPath?: { children: { nodes: Array<{name: string}> } } | null }
    }
    interface ConstraintsData {
        forms: { fieldConstraints: Array<{displayValue: string, value: {string: string}}> }
    }
    interface SnapshotsData {
        jcr: {
            nodeByPath?: {
                children: { nodes: Array<{name: string, entryRefs?: {values: string[]} | null}> }
            } | null
        }
    }

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;

    const allChildrenQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) { nodeByPath(path: $path) { children { nodes { name } } } }
        }
    `;

    const filteredChildrenQuery = gql`
        query($path: String!, $types: [String]!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children(typesFilter: {types: $types, multi: ANY}) { nodes { name } }
                }
            }
        }
    `;

    /**
     * The call jContent makes to populate a choice list. `context: []` is required: omitting it
     * makes EditorFormServiceImpl throw NullPointerException rather than default it.
     */
    const constraintsQuery = gql`
        query($parent: String!) {
            forms {
                fieldConstraints(
                    parentNodeUuidOrPath: $parent,
                    primaryNodeType: "crh:revisionEntry",
                    fieldNodeType: "crh:revisionEntry",
                    fieldName: "crh:snapshotRef",
                    context: [],
                    uiLocale: "en",
                    locale: "en"
                ) { displayValue value { string } }
            }
        }
    `;

    const snapshotsQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    children {
                        nodes { name entryRefs: property(name: "crh:entryRefs") { values } }
                    }
                }
            }
        }
    `;

    let pageUuid = '';
    let firstEntryUuid = '';
    let oldestSnapshot = '';
    let newestSnapshot = '';
    let lastPublishAt = 0;

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((result: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
            const uuid = result.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    const childNames = (path: string): Cypress.Chainable<string[]> =>
        cy.apollo({query: allChildrenQuery, variables: {path}}).then((r: ApolloResult<ChildrenData>) =>
            (r.data?.jcr?.nodeByPath?.children.nodes ?? []).map(n => n.name));

    const childNamesMatching = (path: string, types: string[]): Cypress.Chainable<string[]> =>
        cy.apollo({query: filteredChildrenQuery, variables: {path, types}})
            .then((r: ApolloResult<ChildrenData>) =>
                (r.data?.jcr?.nodeByPath?.children.nodes ?? []).map(n => n.name));

    const snapshotNames = (): Cypress.Chainable<string[]> =>
        childNames(folderPath()).then(names => names.filter(n => !n.startsWith('j:')));

    /** Entry uuid -> the snapshot node bound to it. */
    const bindings = (): Cypress.Chainable<Record<string, string>> =>
        cy.apollo({query: snapshotsQuery, variables: {path: folderPath()}}).then((r: ApolloResult<SnapshotsData>) => {
            const map: Record<string, string> = {};
            for (const node of r.data?.jcr?.nodeByPath?.children.nodes ?? []) {
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
            .then(value => value as T) as Cypress.Chainable<T>;

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

    const pinTo = (entryName: string, snapshotName: string) =>
        setNodeProperty(`${historyPath}/${entryName}`, 'crh:snapshotRef', snapshotName, language);

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-picker',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - picker', language},
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
                    properties: [{name: 'text', value: '<p>First wording of the policy.</p>', language}]
                })
            )
            .then(() => addNode({parentPathOrId: areaPath, primaryNodeType: 'crh:revisionHistory', name: 'history'}))
            .then(() => getUuid(pagePath))
            .then(uuid => {
                pageUuid = uuid;
                // Two captures, so there is an older snapshot to pin to that is NOT the latest.
                // Without that distinction every pin assertion would pass on the default path.
                return publishTriggeringCapture();
            })
            .then(() => pollUntil(snapshotNames, names => names.length >= 1, 'the first snapshot must be captured'))
            .then(() => setNodeProperty(textPath, 'text', '<p>Second wording of the policy.</p>', language))
            .then(() => publishTriggeringCapture())
            .then(() => pollUntil(snapshotNames, names => names.length >= 2, 'a second, different snapshot must be captured'))
            .then(names => {
                const sorted = [...names].sort();
                oldestSnapshot = sorted[0];
                newestSnapshot = sorted[sorted.length - 1];
                expect(oldestSnapshot, 'the two snapshots must differ').to.not.equal(newestSnapshot);
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

    // ---------------------------------------------------------------- visibility

    it('lists every snapshot in the default content view', () => {
        // 1.3.0 shipped with these two numbers reading 28 and 0. Set equality rather than a count:
        // "greater than zero" would pass at 1 of 28.
        snapshotNames().then(present => {
            expect(present.length, 'the scratch page must have snapshots to show').to.be.greaterThan(0);
            childNamesMatching(folderPath(), JCONTENT_FLAT_CONTENT).then(listed => {
                expect(listed.sort(), 'every stored snapshot must be listed in the content view')
                    .to.deep.equal(present.sort());
            });
        });
    });

    it('lets the tree recurse into every folder on the path to a snapshot', () => {
        // Listing a folder is not the same as being able to OPEN it: the table and the tree are
        // driven by different filters, and a folder that lists but cannot be opened is a dead end.
        // This is the half that was broken, and 07-jcontentUi.cy.ts proves it on screen.
        childNamesMatching(`/sites/${siteKey}/contents`, JCONTENT_TREE_RECURSION).then(names => {
            expect(names, 'the revision-history root must be openable').to.include('revision-history');
        });
        childNamesMatching(historyRoot, JCONTENT_TREE_RECURSION).then(names => {
            expect(names, 'the per-page folder must be openable').to.include(pageUuid);
        });
        childNamesMatching(`${historyRoot}/${pageUuid}`, JCONTENT_TREE_RECURSION).then(names => {
            expect(names, 'the per-language folder must be openable').to.include(language);
        });
    });

    // ---------------------------------------------------------------- the picker

    it('offers every snapshot of the page as a choice, through the query jContent issues', () => {
        cy.apollo({query: constraintsQuery, variables: {parent: historyPath}})
            .then((result: ApolloResult<ConstraintsData>) => {
                expect(result.errors, 'the choice list must resolve').to.be.undefined;
                const choices = result.data?.forms.fieldConstraints ?? [];
                expect(choices.length, 'the picker must offer the page snapshots').to.be.greaterThan(0);

                const values = choices.map(c => c.value.string);
                snapshotNames().then(present => {
                    expect(values.sort(), 'every snapshot must be offered').to.deep.equal(present.sort());
                });
            });
    });

    it('labels each choice with a capture instant to the second', () => {
        // To the second, not the minute: captures within one publication land milliseconds apart,
        // and minute precision printed several options identically.
        cy.apollo({query: constraintsQuery, variables: {parent: historyPath}})
            .then((result: ApolloResult<ConstraintsData>) => {
                for (const choice of result.data?.forms.fieldConstraints ?? []) {
                    expect(choice.displayValue, 'a choice must lead with its capture instant')
                        .to.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
                }
            });
    });

    it('does not label every choice with the page title they all share', () => {
        // The page view emits "# <page title>" first, so using the first line made every option
        // read identically -- worse than showing no excerpt, because it looks like information.
        cy.apollo({query: constraintsQuery, variables: {parent: historyPath}})
            .then((result: ApolloResult<ConstraintsData>) => {
                const labels = (result.data?.forms.fieldConstraints ?? []).map(c => c.displayValue);
                expect(labels.length, 'more than one choice is needed to tell them apart').to.be.greaterThan(1);
                const excerpts = labels.map(l => l.replace(/^\S+ \S+\s*/, ''));
                expect(new Set(excerpts).size, `choices must be distinguishable, got ${JSON.stringify(labels)}`)
                    .to.be.greaterThan(1);
            });
    });

    // ---------------------------------------------------------------- pinning

    it('binds a pinned entry to the snapshot it names, not to the newest', () => {
        // Without this, history assembled after a backfill attaches entirely to the latest
        // snapshot and every comparison reports that nothing changed.
        addEntry('rev-pinned', '1.0', '<p>Describes the first wording.</p>')
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the entry must be creatable').to.be.undefined;
                firstEntryUuid = result.data?.jcr.addNode.uuid as string;
                return pinTo('rev-pinned', oldestSnapshot);
            })
            .then(() => publishTriggeringCapture())
            .then(() =>
                pollUntil(bindings, map => Boolean(map[firstEntryUuid]), 'the pinned entry must bind')
            )
            .then(map => {
                expect(map[firstEntryUuid], 'the entry must bind to the snapshot it names')
                    .to.equal(oldestSnapshot);
            });
    });

    it('moves a bound entry when the editor changes which snapshot it names', () => {
        // Binding is append-only against a later CAPTURE. An editor correcting their own choice is
        // the opposite, and without it a wrong choice made once could never be fixed.
        pinTo('rev-pinned', newestSnapshot)
            .then(() => publishTriggeringCapture())
            .then(() =>
                pollUntil(bindings, map => map[firstEntryUuid] === newestSnapshot, 'the entry must move to the snapshot it now names')
            )
            .then(map => {
                expect(map[firstEntryUuid], 'the entry must have moved').to.equal(newestSnapshot);
            });
    });

    it('leaves an entry unbound when it names a snapshot that is not there', () => {
        // It must NOT fall back to the current snapshot: that would attach a revision to content
        // it does not describe, silently, which is the one failure this module exists to prevent.
        addEntry('rev-missing', '2.0', '<p>Names a snapshot that does not exist.</p>')
            .then((result: ApolloResult<AddNodeQueryData>) => {
                const uuid = result.data?.jcr.addNode.uuid as string;
                return pinTo('rev-missing', 'does-not-exist-20990101T000000000Z-deadbeef')
                    .then(() => publishTriggeringCapture())
                    .then(() => bindings())
                    .then(map => {
                        expect(map[uuid], 'an entry naming a missing snapshot must stay unbound')
                            .to.be.undefined;
                        expect(map[firstEntryUuid], 'the entry already bound must be left alone')
                            .to.equal(newestSnapshot);
                    });
            });
    });
});
