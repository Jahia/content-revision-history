import gql from 'graphql-tag';
import {
    addNode,
    deleteNode,
    enableModule,
    publishAndWaitJobEnding,
    removeMixins,
    setNodeProperty
} from '@jahia/cypress';

/**
 * Two kinds of coverage that were missing before this file:
 *
 * 1. Rendered-output assertions for `crh_revisionHistory`/`crh_revisionEntry` branches that were
 *    never exercised by any test -- the exact blind spot that let a fully blank Compare button
 *    ship twice with every other check green (see the `renders Compare buttons...` test in
 *    01-publicationSnapshotCapture.cy.ts, whose pattern this file follows: create nodes with
 *    `addNode`, render via `cy.request` against `/cms/render/default/<lang><pagePath>.html` in
 *    the EDIT workspace, assert on the raw HTML). None of these tests publish anything, so they
 *    have no effect on snapshot/capture state and no ordering dependency on any other spec.
 *
 * 2. Publication-mapping correctness: that publishing a nested non-page node attributes the
 *    capture to the owning page's uuid, and that removing `jmix:publiclyRevisioned` really stops
 *    further captures. These use their own dedicated scratch page, never the shared page from
 *    01-publicationSnapshotCapture.cy.ts, so this file cannot corrupt that spec's state.
 */
describe('Rendered output correctness (crh_revisionHistory / crh_revisionEntry views)', () => {
    const siteKey = 'digitall';
    const language = 'en';
    // The same long-lived demo page 01-publicationSnapshotCapture.cy.ts uses. Safe to reuse here:
    // every test in this block only ever touches EDIT-workspace children under its own uniquely
    // named container and never publishes, so it can never affect that spec's capture/snapshot
    // assertions (which all depend on publication) regardless of run order.
    const pagePath = `/sites/${siteKey}/home/demo-roles-and-users`;
    const areaPath = `${pagePath}/area-main`;

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface AddNodeQueryData {
        jcr: {
            addNode: {
                uuid: string
            }
        }
    }

    const renderPage = (): Cypress.Chainable<string> =>
        cy.request<string>({url: `/cms/render/default/${language}${pagePath}.html`}).then(response => response.body);

    /** Escapes a string for safe use inside a `new RegExp(...)` pattern. */
    const escapeForRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);
    });

    beforeEach(() => {
        cy.login();
    });

    it('renders the resource-bundle fallback title, never an empty <h2>, when historyTitle is empty', () => {
        const containerName = `crh-render-check-empty-title-${Date.now()}`;

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName,
            properties: [{name: 'historyTitle', value: '', language}]
        }).then((container: ApolloResult<AddNodeQueryData>) => {
            expect(container.errors, 'the revision history container must be creatable').to.be.undefined;
            const containerUuid = container.data?.jcr.addNode.uuid as string;

            expect(containerUuid, 'the container must yield a uuid to locate its own heading').to.be.a('string').and
                .not.be.empty;

            return renderPage().then(html => {
                const headingMatch = html.match(
                    new RegExp(`<h2 id="crh-history-heading-${containerUuid}">([\\s\\S]*?)</h2>`)
                );

                expect(headingMatch, 'the container\'s own <h2> heading must be present in the rendered page').to.not
                    .be.null;
                const headingText = (headingMatch?.[1] ?? '').trim();

                expect(
                    headingText,
                    'an empty historyTitle must fall back to the resource-bundle default title, never render an empty heading'
                ).to.equal('Revision history');

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
        });
    });

    it('renders a non-empty historyTitle escaped, never as live markup', () => {
        const containerName = `crh-render-check-xss-title-${Date.now()}`;
        const xssTitle = '<img src=y onerror=alert(2)>';

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName,
            properties: [{name: 'historyTitle', value: xssTitle, language}]
        }).then((container: ApolloResult<AddNodeQueryData>) => {
            expect(container.errors, 'the revision history container must be creatable').to.be.undefined;
            const containerUuid = container.data?.jcr.addNode.uuid as string;

            return renderPage().then(html => {
                const headingMatch = html.match(
                    new RegExp(`<h2 id="crh-history-heading-${containerUuid}">([\\s\\S]*?)</h2>`)
                );

                expect(headingMatch, 'the container\'s own <h2> heading must be present in the rendered page').to.not
                    .be.null;
                const headingText = (headingMatch?.[1] ?? '').trim();

                expect(headingText, 'historyTitle is a second XSS-adjacent field and must arrive escaped').to.equal(
                    '&lt;img src=y onerror=alert(2)&gt;'
                );
                expect(html, 'the payload must NOT render as a live tag').to.not.contain(xssTitle);

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
        });
    });

    it('renders crh:revisionEntry.summary HTML as escaped text, never sanitised or raw', () => {
        const containerName = `crh-render-check-summary-${Date.now()}`;
        const marker = Date.now();
        const rawSummary = `<strong>bold-marker-${marker}</strong>`;

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName
        })
            .then((container: ApolloResult<AddNodeQueryData>) => {
                expect(container.errors, 'the revision history container must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-summary-check',
                    properties: [
                        {name: 'revisionLabel', value: `Summary check ${marker}`},
                        {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                        {name: 'summary', value: rawSummary, language}
                    ]
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the revisionEntry must be creatable with an HTML summary').to.be.undefined;

                return renderPage();
            })
            .then(html => {
                expect(
                    html,
                    'the module does not sanitise summary yet (documented, deliberate): it must render as ESCAPED text'
                ).to.contain(`&lt;strong&gt;bold-marker-${marker}&lt;/strong&gt;`);
                expect(html, 'the summary HTML must never render as live markup').to.not.contain(rawSummary);

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
    });

    it('renders the oldest (only) entry with the non-interactive message and no Compare button', () => {
        const containerName = `crh-render-check-oldest-${Date.now()}`;
        const marker = Date.now();
        const onlyLabel = `Only version ${marker}`;

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName
        })
            .then((container: ApolloResult<AddNodeQueryData>) => {
                expect(container.errors, 'the revision history container must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-only',
                    properties: [
                        {name: 'revisionLabel', value: onlyLabel},
                        {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                        {name: 'summary', value: 'the only entry', language}
                    ]
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the revisionEntry must be creatable').to.be.undefined;

                return renderPage();
            })
            .then(html => {
                expect(
                    html,
                    'the sole/oldest entry must render the explained non-interactive state'
                ).to.contain(`${onlyLabel} is the earliest recorded revision - there is no earlier version to compare.`);
                expect(
                    html,
                    'an entry with no previous sibling must never render a Compare button'
                ).to.not.contain('crh-compare-btn');

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
    });

    it('renders the changeType fallback label, never an unresolved ??? bundle key, when changeType is unset', () => {
        const containerName = `crh-render-check-changetype-${Date.now()}`;
        const marker = Date.now();
        const label = `Changetype check ${marker}`;

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName
        })
            .then((container: ApolloResult<AddNodeQueryData>) => {
                expect(container.errors, 'the revision history container must be creatable').to.be.undefined;

                // ChangeType deliberately omitted.
                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-no-changetype',
                    properties: [
                        {name: 'revisionLabel', value: label},
                        {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                        {name: 'summary', value: 'no change type set', language}
                    ]
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the revisionEntry must be creatable without changeType').to.be.undefined;

                return renderPage();
            })
            .then(html => {
                expect(html, 'an unset changeType must resolve to the substantive fallback label').to.contain(
                    'Substantive (meaning changed)'
                );
                expect(html, 'no unresolved resource-bundle key may reach the page').to.not.contain('???');

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
    });

    it('walks actual sibling order for a 3-entry history: the middle entry\'s previous is its real next sibling', () => {
        const containerName = `crh-render-check-siblings-${Date.now()}`;
        const marker = Date.now();
        const newestLabel = `v3-${marker}`;
        const middleLabel = `v2-${marker}`;
        const oldestLabel = `v1-${marker}`;

        const entryProps = (label: string, daysAgo: number) => [
            {name: 'revisionLabel', value: label},
            {name: 'revisionDate', value: new Date(Date.now() - (daysAgo * 86400000)).toISOString(), type: 'DATE'},
            {name: 'summary', value: `summary for ${label}`, language}
        ];

        addNode({
            parentPathOrId: areaPath,
            primaryNodeType: 'crh:revisionHistory',
            name: containerName
        })
            .then((container: ApolloResult<AddNodeQueryData>) => {
                expect(container.errors, 'the revision history container must be creatable').to.be.undefined;

                // Added newest-first, matching the editorial order revisionEntry.jsp assumes.
                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-newest',
                    properties: entryProps(newestLabel, 0)
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the newest entry must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-middle',
                    properties: entryProps(middleLabel, 1)
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the middle entry must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: `${areaPath}/${containerName}`,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'entry-oldest',
                    properties: entryProps(oldestLabel, 2)
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the oldest entry must be creatable').to.be.undefined;

                return renderPage();
            })
            .then(html => {
                const correctPattern = new RegExp(
                    `Compare ${escapeForRegExp(middleLabel)} \\([^)]*\\) with the previous revision, ${escapeForRegExp(oldestLabel)} \\(`
                );
                const wrongPreviousPattern = new RegExp(
                    `Compare ${escapeForRegExp(middleLabel)} \\([^)]*\\) with the previous revision, ${escapeForRegExp(newestLabel)} \\(`
                );
                const selfReferencePattern = new RegExp(
                    `Compare ${escapeForRegExp(middleLabel)} \\([^)]*\\) with the previous revision, ${escapeForRegExp(middleLabel)} \\(`
                );

                expect(
                    html,
                    'the middle entry\'s previous must be its actual next sibling (the oldest entry), proving real sibling-walking rather than a hardcoded reference -- a 2-entry fixture cannot tell these apart'
                ).to.match(correctPattern);
                expect(html, 'the middle entry must not point at the newest entry as its previous').to.not.match(
                    wrongPreviousPattern
                );
                expect(html, 'the middle entry must not point at itself as its previous').to.not.match(
                    selfReferencePattern
                );

                deleteNode(`${areaPath}/${containerName}`).then(null, () => undefined);
            });
    });
});

describe('Publication mapping correctness', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-mapping`;
    const areaPath = `${pagePath}/area-main`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    const revisionedMixin = 'jmix:publiclyRevisioned';

    const captureTimeoutMs = 30000;
    const pollIntervalMs = 1000;
    const captureRateLimitGraceMs = 1200;
    const noOpSettleChecks = 6;
    const noOpSettleIntervalMs = 500;

    interface JcrPropertyValue {
        name: string
        value: string | null
    }

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface NodeQueryData {
        jcr: {
            nodeByPath: {
                uuid: string
            } | null
        } | null
    }

    interface FolderQueryData {
        jcr: {
            nodeByPath: {
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

    interface FolderStatus {
        lastCaptureStatus: string
        lastCaptureDate: string
    }

    const emptyFolderStatus: FolderStatus = {lastCaptureStatus: '', lastCaptureDate: ''};

    const nodeUuidQuery = gql`
        query node($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    uuid
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

    const folderPropertyNames = ['crh:lastCaptureStatus', 'crh:lastCaptureDate'];

    const propertyMap = (properties: JcrPropertyValue[]): Record<string, string> => {
        const map: Record<string, string> = {};

        for (const property of properties) {
            if (property.value !== null && property.value !== undefined) {
                map[property.name] = property.value;
            }
        }

        return map;
    };

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy
            .apollo({query: nodeUuidQuery, variables: {path}})
            .then((result: ApolloResult<NodeQueryData>) => {
                const uuid = result.data?.jcr?.nodeByPath?.uuid;

                expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;

                return uuid as string;
            });

    const getFolderStatus = (folderPath: string): Cypress.Chainable<FolderStatus> =>
        cy
            .apollo({query: folderQuery, variables: {path: folderPath, names: folderPropertyNames}})
            .then((result: ApolloResult<FolderQueryData>) => {
                const node = result.data?.jcr?.nodeByPath;

                if (!node) {
                    return emptyFolderStatus;
                }

                const props = propertyMap(node.properties ?? []);

                return {
                    lastCaptureStatus: props['crh:lastCaptureStatus'] ?? '',
                    lastCaptureDate: props['crh:lastCaptureDate'] ?? ''
                };
            });

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

    const assertFolderStatusStaysAt = (
        folderPath: string,
        expectedLastCaptureDate: string,
        assertionMessage: string
    ): Cypress.Chainable<boolean> => {
        let stableChecks = 0;

        return cy.waitUntil<boolean>(
            () =>
                getFolderStatus(folderPath).then(status => {
                    expect(status.lastCaptureDate, assertionMessage).to.equal(expectedLastCaptureDate);
                    stableChecks += 1;
                    return stableChecks >= noOpSettleChecks;
                }),
            {
                timeout: (noOpSettleChecks * noOpSettleIntervalMs) + pollIntervalMs,
                interval: noOpSettleIntervalMs,
                errorMsg: 'could not confirm the folder status stayed stable',
                verbose: true
            }
        );
    };

    let pageUuid = '';
    let lastPublishAt = 0;

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;

    /** Paces publishes of the scratch page past the module's per-page-and-language rate limiter. */
    const publishPathTriggeringCapture = (path: string): Cypress.Chainable<boolean> => {
        const waitMs = Math.max(0, captureRateLimitGraceMs - (Date.now() - lastPublishAt));

        return cy
            .waitUntil<boolean>(() => Date.now() - lastPublishAt >= captureRateLimitGraceMs, {
                timeout: waitMs + 5000,
                interval: 100,
                errorMsg: 'could not pace this publish past the module\'s capture rate limiter'
            })
            .then(() => {
                publishAndWaitJobEnding(path, [language]);

                return cy.wrap(true, {log: false}).then(published => {
                    lastPublishAt = Date.now();
                    return published;
                });
            });
    };

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-mapping',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - publication mapping', language},
                {name: 'j:templateName', value: 'default'}
            ],
            mixins: [revisionedMixin]
        })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the scratch page must be creatable').to.be.undefined;

                return addNode({parentPathOrId: pagePath, primaryNodeType: 'jnt:contentList', name: 'area-main'});
            })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the area container must be creatable').to.be.undefined;

                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;

                // Baseline publish/capture, so the "nested node" test below has a real previous
                // lastCaptureDate to prove changed.
                return publishPathTriggeringCapture(pagePath);
            })
            .then(() =>
                pollUntil(
                    () => getFolderStatus(folderPath()),
                    status => status.lastCaptureDate !== '',
                    `expected a baseline capture attempt to be recorded for ${pagePath} within ${captureTimeoutMs}ms`
                )
            );
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

    it('attributes a deeply-nested non-page node\'s publication to the owning page\'s uuid', () => {
        const nestedContainerName = 'crh-mapping-nested-container';
        const nestedContainerPath = `${areaPath}/${nestedContainerName}`;
        const nestedEntryPath = `${nestedContainerPath}/nested-entry`;
        let previousLastCaptureDate = '';

        getFolderStatus(folderPath())
            .then(statusBefore => {
                expect(
                    statusBefore.lastCaptureDate,
                    'precondition: the page must already have a recorded capture attempt from the baseline publish'
                ).to.not.equal('');
                previousLastCaptureDate = statusBefore.lastCaptureDate;

                // Page > area-main > container > entry: three levels below the page, so only
                // the outermost node of a whole-page publish is itself a jnt:page -- this one
                // never is.
                return addNode({
                    parentPathOrId: areaPath,
                    primaryNodeType: 'crh:revisionHistory',
                    name: nestedContainerName
                });
            })
            .then((container: ApolloResult<AddNodeQueryData>) => {
                expect(container.errors, 'the nested container must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: nestedContainerPath,
                    primaryNodeType: 'crh:revisionEntry',
                    name: 'nested-entry',
                    properties: [
                        {name: 'revisionLabel', value: 'nested mapping check'},
                        {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                        {name: 'summary', value: 'proves attribution walks up to the owning page', language}
                    ]
                });
            })
            .then((entry: ApolloResult<AddNodeQueryData>) => {
                expect(entry.errors, 'the nested entry must be creatable').to.be.undefined;

                // Publish ONLY the deeply-nested entry's own path, never the page path: the
                // publication event's node identifier is the entry's own, not the page's.
                return publishPathTriggeringCapture(nestedEntryPath);
            })
            .then(() =>
                waitForFolderStatusChange(
                    folderPath(),
                    previousLastCaptureDate,
                    `expected publishing the nested entry to schedule a new capture attempt attributed to page ${pageUuid} within ${captureTimeoutMs}ms -- if attribution walked to the wrong page (or no page), this specific folder would never update`
                )
            )
            .then(status => {
                expect(
                    ['STORED', 'UNCHANGED'],
                    'the capture attributed to the owning page must complete normally, not error out for an unrelated reason'
                ).to.include(status.lastCaptureStatus);

                deleteNode(nestedContainerPath).then(null, () => undefined);
                deleteNode(nestedContainerPath, 'LIVE').then(null, () => undefined);
            });
    });

    it('produces no further captures once jmix:publiclyRevisioned is removed from the page', () => {
        let previousLastCaptureDate = '';

        getFolderStatus(folderPath())
            .then(statusBefore => {
                expect(
                    statusBefore.lastCaptureDate,
                    'precondition: the page must already have a recorded capture attempt to compare against'
                ).to.not.equal('');
                previousLastCaptureDate = statusBefore.lastCaptureDate;

                // A real content change alongside the mixin removal, so a "no capture" result
                // cannot be a vacuous pass caused by there being nothing to publish at all (see
                // 01-publicationSnapshotCapture.cy.ts's note on no-op republishes).
                setNodeProperty(pagePath, 'jcr:title', 'CRH e2e - publication mapping (opted out)', language);
                removeMixins(pagePath, [revisionedMixin]);

                return publishPathTriggeringCapture(pagePath);
            })
            .then(() =>
                assertFolderStatusStaysAt(
                    folderPath(),
                    previousLastCaptureDate,
                    'removing jmix:publiclyRevisioned must stop further captures -- the folder must record nothing new even though real content was published'
                )
            );
    });
});
