import gql from 'graphql-tag';
import {
    addMixins,
    addNode,
    deleteNode,
    enableModule,
    publishAndWaitJobEnding,
    removeMixins,
    setNodeProperty
} from '@jahia/cypress';

/**
 * Capture behaviour of the Content Revision History module, post capture-architecture
 * redesign.
 *
 * Capture is triggered by publication, not by rendering. `PublicationSnapshotListener` maps
 * every published node up to the nearest `jmix:publiclyRevisioned` page and enqueues a
 * `SnapshotCaptureJob`, which fetches the page over HTTP loopback as `guest` and stores the
 * result under
 * /sites/<site>/contents/revision-history/<pageUuid>/<lang>/<yyyyMMdd'T'HHmmssSSS'Z'-hash8>
 * in the DEFAULT workspace (snapshots are never published). Capture is asynchronous, so every
 * assertion that depends on it polls for the expected state instead of assuming it is done by
 * the time the publish mutation returns.
 */
describe('Publication-triggered revision snapshot capture', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const pagePath = `/sites/${siteKey}/home/demo-roles-and-users`;
    const bigTextPath = `${pagePath}/area-main/bigText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    // Query the site's contents folder, never revision-history itself: the root does not
    // exist until the first capture, and nodeByPath on a missing path is a GraphQL error.
    const contentsPath = `/sites/${siteKey}/contents`;
    const scratchContainerName = 'crh-e2e-revision-history-scratch';
    const scratchContainerPath = `${contentsPath}/${scratchContainerName}`;
    const revisionedMixin = 'jmix:publiclyRevisioned';

    const captureTimeoutMs = 30000;
    const pollIntervalMs = 1000;
    const noOpSettleChecks = 6;
    const noOpSettleIntervalMs = 500;
    /**
     * The module rate-limits capture attempts to a minimum of 1s between captures per page
     * and language (confirmed against the running module: a publish landing ~960ms after the
     * previous one was refused as RATE_LIMITED and produced no snapshot at all). This is
     * headroom above that limit, not a completion signal -- see publishTriggeringCapture.
     */
    const captureRateLimitGraceMs = 1200;

    const snapshotNamePattern = /^\d{8}T\d{9}Z-[0-9a-f]{8}$/;
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

    // ---------------------------------------------------------------- GraphQL response shapes

    interface JcrPropertyValue {
        name: string
        value: string | null
    }

    interface BinarySize {
        size: number | null
    }

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{ message: string }>
    }

    interface SnapshotGqlNode {
        name: string
        properties: JcrPropertyValue[]
        markdown: BinarySize | null
    }

    interface SnapshotsQueryData {
        jcr: {
            nodeByPath: {
                descendants: {
                    nodes: SnapshotGqlNode[]
                } | null
            } | null
        } | null
    }

    interface FolderQueryNode {
        properties: JcrPropertyValue[]
    }

    interface FolderQueryData {
        jcr: {
            nodeByPath: FolderQueryNode | null
        } | null
    }

    interface NamedTypedNode {
        name: string
        primaryNodeType: { name: string }
    }

    interface LanguageFolderGqlNode extends NamedTypedNode {
        children: { nodes: NamedTypedNode[] }
    }

    interface PageFolderGqlNode extends NamedTypedNode {
        children: { nodes: LanguageFolderGqlNode[] }
    }

    interface HistoryTreeQueryData {
        jcr: {
            nodeByPath: {
                children: { nodes: PageFolderGqlNode[] }
            } | null
        } | null
    }

    interface NodeQueryData {
        jcr: {
            nodeByPath: {
                uuid: string
                properties: JcrPropertyValue[]
            } | null
        } | null
    }

    interface AddNodeQueryData {
        jcr: {
            addNode: {
                uuid: string
            }
        }
    }

    // ---------------------------------------------------------------- domain shapes

    interface Snapshot {
        name: string
        contentHash: string
        language: string
        generatorVersion: string
        capturedBy: string
        markdownSize: number
    }

    interface FolderStatus {
        latestHash: string
        snapshotCount: number
        lastCaptureStatus: string
        lastCaptureMessage: string
        lastCaptureDate: string
    }

    const emptyFolderStatus: FolderStatus = {
        latestHash: '',
        snapshotCount: 0,
        lastCaptureStatus: '',
        lastCaptureMessage: '',
        lastCaptureDate: ''
    };

    // ---------------------------------------------------------------- GraphQL documents

    const nodeQuery = gql`
        query node($path: String!, $names: [String], $language: String) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    uuid
                    properties(names: $names, language: $language) {
                        name
                        value
                    }
                }
            }
        }
    `;

    const snapshotsQuery = gql`
        query snapshots($path: String!, $names: [String]) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    descendants(typesFilter: { types: ["crh:revisionSnapshot"] }) {
                        nodes {
                            name
                            properties(names: $names) {
                                name
                                value
                            }
                            markdown: property(name: "crh:markdown") {
                                size
                            }
                        }
                    }
                }
            }
        }
    `;

    const folderQuery = gql`
        query folder($path: String!, $names: [String]) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    properties(names: $names) {
                        name
                        value
                    }
                }
            }
        }
    `;

    const historyTreeQuery = gql`
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
            }
        }
    `;

    const snapshotPropertyNames = [
        'crh:contentHash',
        'crh:language',
        'crh:generatorVersion',
        'crh:capturedBy',
        'crh:snapshotDate'
    ];

    const folderPropertyNames = [
        'crh:latestHash',
        'crh:snapshotCount',
        'crh:lastCaptureStatus',
        'crh:lastCaptureMessage',
        'crh:lastCaptureDate'
    ];

    // ---------------------------------------------------------------- helpers

    const propertyMap = (properties: JcrPropertyValue[]): Record<string, string> => {
        const map: Record<string, string> = {};

        for (const property of properties) {
            if (property.value !== null && property.value !== undefined) {
                map[property.name] = property.value;
            }
        }

        return map;
    };

    const getNode = (path: string, names: string[], nodeLanguage: string | null = null) =>
        cy
            .apollo({query: nodeQuery, variables: {path, names, language: nodeLanguage}})
            .then((result: ApolloResult<NodeQueryData>) => result.data?.jcr?.nodeByPath ?? null);

    const listSnapshots = (): Cypress.Chainable<Snapshot[]> =>
        cy
            .apollo({query: snapshotsQuery, variables: {path: contentsPath, names: snapshotPropertyNames}})
            .then((result: ApolloResult<SnapshotsQueryData>) => {
                const nodes = result.data?.jcr?.nodeByPath?.descendants?.nodes ?? [];

                return nodes
                    .map(node => {
                        const props = propertyMap(node.properties ?? []);

                        return {
                            name: node.name,
                            contentHash: props['crh:contentHash'] ?? '',
                            language: props['crh:language'] ?? '',
                            generatorVersion: props['crh:generatorVersion'] ?? '',
                            capturedBy: props['crh:capturedBy'] ?? '',
                            markdownSize: node.markdown?.size ?? 0
                        };
                    })
                    .sort((a, b) => a.name.localeCompare(b.name));
            });

    const getFolderStatus = (folderPath: string): Cypress.Chainable<FolderStatus> =>
        cy
            .apollo({query: folderQuery, variables: {path: folderPath, names: folderPropertyNames}})
            .then((result: ApolloResult<FolderQueryData>) => {
                const node = result.data?.jcr?.nodeByPath;

                if (!node) {
                    // Not created yet -- the async capture job may not have run at all so far.
                    return emptyFolderStatus;
                }

                const props = propertyMap(node.properties ?? []);

                return {
                    latestHash: props['crh:latestHash'] ?? '',
                    snapshotCount: Number(props['crh:snapshotCount'] ?? '0'),
                    lastCaptureStatus: props['crh:lastCaptureStatus'] ?? '',
                    lastCaptureMessage: props['crh:lastCaptureMessage'] ?? '',
                    lastCaptureDate: props['crh:lastCaptureDate'] ?? ''
                };
            });

    /**
     * Polls `fetch` until `predicate` accepts the result, then yields that result.
     *
     * Capture is asynchronous by design, so every positive assertion in this suite goes
     * through here instead of a fixed sleep: the job may run in a millisecond or take a few
     * seconds under load, and a fixed wait would either be flaky or needlessly slow.
     */
    const pollUntil = <T>(
        fetch: () => Cypress.Chainable<T>,
        predicate: (value: T) => boolean,
        errorMsg: string,
        timeout: number = captureTimeoutMs
    ): Cypress.Chainable<T> =>
            cy
                .waitUntil<T | false>(() => fetch().then(value => (predicate(value) ? value : false)), {
                    timeout,
                    interval: pollIntervalMs,
                    errorMsg,
                    verbose: true
                })
                .then(value => value as T);

    const waitForSnapshotCount = (min: number, errorMsg: string): Cypress.Chainable<Snapshot[]> =>
        pollUntil(listSnapshots, snapshots => snapshots.length >= min, errorMsg);

    const waitForFolderStatusChange = (
        folderPath: string,
        previousLastCaptureDate: string,
        errorMsg: string
    ): Cypress.Chainable<FolderStatus> =>
        pollUntil(
            () => getFolderStatus(folderPath),
            status => status.lastCaptureDate !== '' && status.lastCaptureDate !== previousLastCaptureDate,
            errorMsg
        );

    /**
     * Confirms `getCount` stays at `expectedCount` for a bounded number of consecutive
     * checks, failing immediately the moment it does not.
     *
     * This is the only place in the suite where a time budget stands in for a completion
     * signal, because there is no event to poll for when proving something did NOT happen.
     * The budget (a few seconds, well above the rate limiter's 1s minimum interval and the
     * near-instant async dispatch observed in the rest of this suite) exists only to bound
     * this one negative assertion -- it is not how the suite synchronises on real capture
     * completion elsewhere.
     */
    const assertCountStaysAt = (
        getCount: () => Cypress.Chainable<number>,
        expectedCount: number,
        assertionMessage: string
    ): Cypress.Chainable<boolean> => {
        let stableChecks = 0;

        return cy.waitUntil<boolean>(
            () =>
                getCount().then(count => {
                    expect(count, assertionMessage).to.equal(expectedCount);
                    stableChecks += 1;
                    return stableChecks >= noOpSettleChecks;
                }),
            {
                timeout: (noOpSettleChecks * noOpSettleIntervalMs) + pollIntervalMs,
                interval: noOpSettleIntervalMs,
                errorMsg: 'could not confirm the snapshot count stayed stable',
                verbose: true
            }
        );
    };

    let pageUuid: string;
    let originalBigText: string;
    let lastCaptureTriggeringPublishAt = 0;

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    /**
     * Publishes the page, first pacing past the module's per-page-and-language capture rate
     * limiter so the publish is not legitimately refused as RATE_LIMITED.
     *
     * A real editor never publishes the same page twice inside one second; this suite does
     * unless it paces itself, so every publish that can trigger a capture goes through here.
     * This paces two distinct, deliberate actions apart -- it is not a stand-in for polling
     * job completion, which every assertion in this suite still does separately via
     * waitForSnapshotCount / waitForFolderStatusChange.
     */
    const publishTriggeringCapture = (): Cypress.Chainable<boolean> => {
        const waitMs = Math.max(0, captureRateLimitGraceMs - (Date.now() - lastCaptureTriggeringPublishAt));

        return cy
            .waitUntil<boolean>(() => Date.now() - lastCaptureTriggeringPublishAt >= captureRateLimitGraceMs, {
                timeout: waitMs + 5000,
                interval: 100,
                errorMsg: 'could not pace this publish past the module\'s capture rate limiter'
            })
            .then(() => {
                publishAndWaitJobEnding(pagePath, [language]);

                // Return a CHAINABLE, never a plain value: this callback has already enqueued
                // Cypress commands, and returning a sync value from such a callback makes
                // Cypress throw "you are mixing up async and sync code" -- which fails the
                // whole before() hook and skips every test in the suite.
                return cy.wrap(true, {log: false}).then(published => {
                    lastCaptureTriggeringPublishAt = Date.now();

                    return published;
                });
            });
    };

    before(() => {
        cy.login();
        // Enable the module on the site HERE, not in provisioning.yml: the harness installs
        // the module after the manifest runs, so a provisioning `enable` step silently
        // no-ops. A module's views and listeners only apply to sites where it is enabled --
        // without this the module looks healthy and every assertion fails.
        enableModule('content-revision-history', siteKey);

        // Remember the page's current content so the suite can restore it afterwards.
        getNode(bigTextPath, ['text'], language).then(node => {
            originalBigText = propertyMap(node?.properties ?? []).text ?? '';
        });

        // Start from a known-empty history so counts are meaningful.
        deleteNode(historyRoot).then(null, () => undefined);
        deleteNode(scratchContainerPath).then(null, () => undefined);

        addMixins(pagePath, [revisionedMixin]);
        publishTriggeringCapture();

        getNode(pagePath, []).then(node => {
            pageUuid = node?.uuid ?? '';
            expect(pageUuid, 'the page must resolve to a uuid before any test can proceed').to.match(uuidPattern);
        });
    });

    beforeEach(() => {
        cy.login();
    });

    after(() => {
        cy.login();

        // Restore the page to its pre-suite content and opt-out state so the run does not
        // permanently alter the digitall site.
        setNodeProperty(bigTextPath, 'text', originalBigText, language);
        removeMixins(pagePath, [revisionedMixin]);
        publishAndWaitJobEnding(pagePath, [language]);

        deleteNode(historyRoot).then(null, () => undefined);
        deleteNode(scratchContainerPath).then(null, () => undefined);
    });

    it('captures a snapshot from publication alone, without anyone loading the page', () => {
        // The mixin-adding publish in before() is the only trigger exercised so far in this
        // suite -- no cy.request to the page has been made. If capture still depended on a
        // render, this would time out.
        waitForSnapshotCount(
            1,
            `expected at least one revision snapshot for ${pagePath} [${language}] within ` +
                `${captureTimeoutMs}ms of publishing, with no page ever rendered`
        ).then(snapshots => {
            const forLanguage = snapshots.filter(snapshot => snapshot.language === language);

            expect(forLanguage.length, 'a snapshot must have been stored by the async capture job').to.be.greaterThan(0);
        });
    });

    it('does not store a new snapshot when re-publishing with unchanged content', () => {
        let snapshotCountBefore = 0;
        let lastCaptureDateBefore = '';

        listSnapshots()
            .then(before => {
                expect(
                    before.length,
                    'precondition: at least one snapshot must already exist before the no-op republish'
                ).to.be.greaterThan(0);
                snapshotCountBefore = before.length;

                return getFolderStatus(folderPath());
            })
            .then(statusBefore => {
                lastCaptureDateBefore = statusBefore.lastCaptureDate;

                // Re-set bigText's own property to its current (unchanged) value. Republishing
                // a page with literally nothing modified is not a no-op capture -- Jahia has
                // nothing to publish, so no publication event fires and the capture job never
                // runs at all (confirmed against the running module: a bare republish produced
                // no "Scheduled revision snapshot capture" log line). Re-setting a property to
                // its own value still marks the node dirty for publish, while the *rendered*
                // markdown -- and so its content hash -- stays byte-identical, which is what
                // dedupe actually needs to exercise.
                setNodeProperty(bigTextPath, 'text', originalBigText, language);

                return publishTriggeringCapture();
            })
            .then(() =>
                waitForFolderStatusChange(
                    folderPath(),
                    lastCaptureDateBefore,
                    'expected the capture job to record an outcome for the no-op republish'
                )
            )
            .then(statusAfter => {
                expect(
                    statusAfter.lastCaptureStatus,
                    'unchanged content must be recorded as UNCHANGED, not silently dropped'
                ).to.equal('UNCHANGED');

                return listSnapshots();
            })
            .then(after => {
                expect(after.length, 'identical content must not produce a new snapshot node').to.equal(
                    snapshotCountBefore
                );
            });
    });

    it('stores exactly one new snapshot, containing the new text, when the page content changes', () => {
        const marker = `revision-history e2e marker ${Date.now()}`;
        let snapshotCountBefore = 0;
        let previousHash = '';

        listSnapshots()
            .then(before => {
                snapshotCountBefore = before.length;
                previousHash = before.length > 0 ? before[before.length - 1].contentHash : '';

                setNodeProperty(bigTextPath, 'text', `<p>${marker}</p>`, language);

                return publishTriggeringCapture();
            })
            .then(() =>
                waitForSnapshotCount(
                    snapshotCountBefore + 1,
                    'expected exactly one new snapshot after a content change'
                )
            )
            .then(after => {
                expect(after.length, 'a content change must produce exactly one new snapshot').to.equal(
                    snapshotCountBefore + 1
                );

                const newest = after[after.length - 1];

                expect(newest.contentHash, 'the new snapshot must carry a different content hash').to.not.equal(
                    previousHash
                );
                expect(newest.markdownSize, 'crh:markdown must not be empty').to.be.greaterThan(0);

                // Cross-check that the live markdown really does contain the new text.
                // NOTE: deliberately no byte-length equality against the stored snapshot --
                // the stored copy is NORMALIZED markdown while this endpoint returns the
                // pre-normalization render output, so their lengths differ legitimately
                // (whitespace collapsing). Asserting equality encodes a false equivalence.
                // A fresh query string only avoids a stale cached response for this
                // verification request; it plays no part in triggering capture, which is
                // publication-driven now.
                return cy
                    .request<string>({
                        url: `/cms/render/live/${language}${pagePath}.markdown`,
                        qs: {v: `${Date.now()}`}
                    })
                    .then(response => {
                        expect(response.body, 'the live markdown must contain the new text').to.contain(marker);
                    });
            });
    });

    it('always records crh:capturedBy as guest, never the acting user\'s identity', () => {
        listSnapshots().then(snapshots => {
            expect(snapshots.length, 'precondition: snapshots must exist to check').to.be.greaterThan(0);

            for (const snapshot of snapshots) {
                expect(
                    snapshot.capturedBy,
                    `snapshot ${snapshot.name} must be captured as guest, not the publishing user -- ` +
                        'otherwise an editor\'s privileges would decide the contents of a public record'
                ).to.equal('guest');
            }
        });
    });

    it('records complete metadata (contentHash, language, generatorVersion) on every snapshot', () => {
        listSnapshots().then(snapshots => {
            expect(snapshots.length, 'precondition: snapshots must exist to check').to.be.greaterThan(0);

            for (const snapshot of snapshots) {
                expect(snapshot.contentHash, `${snapshot.name} contentHash`).to.not.equal('');
                expect(snapshot.language, `${snapshot.name} language`).to.equal(language);
                expect(snapshot.generatorVersion, `${snapshot.name} generatorVersion`).to.not.equal('');
                expect(snapshot.markdownSize, `${snapshot.name} markdown size`).to.be.greaterThan(0);
            }
        });
    });

    it('produces zero new snapshots from anonymous requests with random cache-busting query strings', () => {
        listSnapshots().then(before => {
            cy.clearCookies();

            // This is the exact shape of the attack the old render-triggered capture allowed:
            // an unauthenticated request with a random cache-buster forcing a full render.
            // Rendering no longer captures anything, so none of this should have any effect.
            for (let i = 0; i < 5; i++) {
                cy.request({
                    url: `/cms/render/live/${language}${pagePath}.html`,
                    qs: {cb: `${Date.now()}${Math.random().toString(36).slice(2)}`},
                    failOnStatusCode: false
                });
            }

            cy.login();

            assertCountStaysAt(
                () => listSnapshots().then(snapshots => snapshots.length),
                before.length,
                'anonymous renders must never create a snapshot -- that was the DoS vector this redesign closes'
            );
        });
    });

    it('stores snapshots under revision-history/<pageUuid>/<lang>/<UTC-name>, keyed on the page uuid', () => {
        cy.apollo({query: historyTreeQuery, variables: {path: historyRoot}}).then(
            (result: ApolloResult<HistoryTreeQueryData>) => {
                const perPage = result.data?.jcr?.nodeByPath?.children?.nodes ?? [];

                expect(perPage.length, 'one folder per revisioned page must exist').to.be.greaterThan(0);

                const pageFolder = perPage.find(folder => folder.name === pageUuid);

                expect(pageFolder, `a folder keyed on the page's own uuid (${pageUuid}) must exist`).to.exist;
                expect(pageFolder?.primaryNodeType.name).to.equal('crh:snapshotFolder');

                const perLanguage = pageFolder?.children.nodes ?? [];
                const languageFolder = perLanguage.find(folder => folder.name === language);

                expect(languageFolder, `a per-language folder for "${language}" must exist`).to.exist;
                expect(languageFolder?.primaryNodeType.name).to.equal('crh:snapshotFolder');

                const snapshotNodes = languageFolder?.children.nodes ?? [];

                expect(snapshotNodes.length, 'at least one snapshot must be stored').to.be.greaterThan(0);

                for (const snapshot of snapshotNodes) {
                    expect(
                        snapshot.name,
                        `${snapshot.name} must be a UTC "yyyyMMdd'T'HHmmssSSS'Z'-<hash8>" name`
                    ).to.match(snapshotNamePattern);
                }
            }
        );
    });

    it('renders Compare buttons with real, distinct, escaped labels', () => {
        // Two defects meet in this markup and NEITHER was covered by any test:
        //   1. <fmt:message> writes its output unescaped, so an editor-authored revisionLabel
        //      is a stored-XSS sink.
        //   2. Escaping it with <c:set> placed ABOVE the assignments silently produced "" for
        //      every value -- JSTL has no hoisting -- blanking every button to
        //      "Compare  () with the previous revision,  ()". Unit and e2e stayed green.
        // Assert on the rendered page so both regressions are caught in future.
        const renderContainer = `${pagePath}/area-main/crh-render-check`;
        const xssLabel = '<img src=x onerror=alert(1)>';
        const plainLabel = 'Version 9.9 render check';

        addNode({
            parentPathOrId: `${pagePath}/area-main`,
            primaryNodeType: 'crh:revisionHistory',
            name: 'crh-render-check'
        }).then((container: ApolloResult<AddNodeQueryData>) => {
            expect(container.errors, 'the revision history container must be creatable on the page').to.be.undefined;

            return addNode({
                parentPathOrId: renderContainer,
                primaryNodeType: 'crh:revisionEntry',
                name: 'entry-newer',
                properties: [
                    {name: 'revisionLabel', value: xssLabel},
                    {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                    {name: 'summary', value: 'newer entry', language}
                ]
            });
        })
            .then(() =>
                addNode({
                    parentPathOrId: renderContainer,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-older',
                    properties: [
                        {name: 'revisionLabel', value: plainLabel},
                        {name: 'revisionDate', value: new Date(Date.now() - 86400000).toISOString(), type: 'DATE'},
                        {name: 'summary', value: 'older entry', language}
                    ]
                })
            )
            .then(() =>
                // Render in the authoring workspace: no publish needed, so this cannot perturb
                // the snapshot counts the other tests assert on.
                cy.request<string>({url: `/cms/render/default/${language}${pagePath}.html`})
            )
            .then(response => {
                const html = response.body;

                // The label must actually reach the button -- not the blanked "Compare  ()" form.
                expect(html, 'the older entry label must appear in the rendered page').to.contain(plainLabel);
                // Two spaces: that is exactly what an empty {0} produces in
                // "Compare {0} ({1}) with ...". Do not relax this to one space.
                expect(html, 'the Compare button must not be blank').to.not.contain('Compare  () with');
                expect(html, 'no unresolved resource-bundle key may reach the page').to.not.contain('???');

                // ...and the XSS payload must arrive escaped, never as live markup.
                expect(html, 'the payload must be escaped').to.contain('&lt;img src=x onerror=alert(1)&gt;');
                expect(html, 'the payload must NOT render as a live tag').to.not.contain(
                    '<img src=x onerror=alert(1)>'
                );

                deleteNode(renderContainer).then(null, () => undefined);
            });
    });

    it('allows creating a crh:revisionEntry without a snapshotRef', () => {
        // SnapshotRef used to be an editor-visible mandatory string holding an internally
        // generated key no editor could know -- a revisionEntry could not be saved at all.
        // It is now system-set, hidden and optional; this proves an entry is creatable
        // without ever touching it.
        addNode({
            parentPathOrId: contentsPath,
            primaryNodeType: 'crh:revisionHistory',
            name: scratchContainerName
        }).then((containerResult: ApolloResult<AddNodeQueryData>) => {
            expect(containerResult.errors, 'creating the crh:revisionHistory container must not fail').to.be.undefined;

            addNode({
                parentPathOrId: scratchContainerPath,
                primaryNodeType: 'crh:revisionEntry',
                name: 'entry-without-snapshot-ref',
                properties: [
                    {name: 'revisionLabel', value: 'e2e regression'},
                    {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                    {name: 'summary', value: 'Created without a snapshotRef on purpose', language}
                ]
            }).then((entryResult: ApolloResult<AddNodeQueryData>) => {
                expect(entryResult.errors, 'a revisionEntry must be creatable without snapshotRef').to.be.undefined;
                expect(entryResult.data?.jcr?.addNode.uuid, 'the entry must actually be created').to.be.a('string').and
                    .not.be.empty;

                deleteNode(scratchContainerPath).then(null, () => undefined);
            });
        });
    });
});
