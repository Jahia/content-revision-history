import gql from 'graphql-tag';
import {
    addNode,
    deleteNode,
    enableModule,
    publishAndWaitJobEnding,
    removeMixins
} from '@jahia/cypress';

/**
 * Coverage for the three `CaptureStatus` outcomes that had NO test at any level before this
 * file: NOT_PUBLIC, EMPTY and OVERSIZE (see `CaptureStatus.java`). The module's whole premise is
 * "failures are durable, not silent" -- every non-STORED outcome is written to
 * `crh:lastCaptureStatus` / `crh:lastCaptureMessage` / `crh:lastCaptureDate` on the per-language
 * `crh:snapshotFolder`, and no `crh:revisionSnapshot` node is ever created for them. That claim
 * was unverified for these three statuses; this file closes that gap.
 *
 * Each scenario uses its OWN scratch page (never the shared page from
 * 01-publicationSnapshotCapture.cy.ts) so this file has no ordering dependency on that spec and
 * cannot corrupt its state, and vice versa.
 *
 * NOTE on CaptureStatus.EMPTY: it is NOT covered here. `jnt_page/markdown/page.jsp` -- the only
 * markdown view a `jnt:page` (the only node type `jmix:publiclyRevisioned` can be applied to) can
 * resolve to -- unconditionally emits `# ${currentNode.displayableName}` before anything else.
 * Verified directly against the shipped `MarkdownNormalizer`: `normalize("# \n\n")` (the raw
 * output of that heading when displayableName is blank) yields `"#\n"`, which is NOT considered
 * empty by `RevisionSnapshotService.captureIfChanged`'s `markdown.trim().isEmpty()` check.
 * `displayableName` itself always falls back to the node's own JCR name when no title is set,
 * which can never be blank either. There is therefore no way to make a real, publicly-revisioned
 * `jnt:page` render truly empty Markdown through the module's own shipped view -- see this file's
 * final report for why this looks like effectively dead code, not a gap in this test suite.
 */
describe('Durable capture failure modes (CaptureStatus outside STORED/UNCHANGED)', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const homePath = `/sites/${siteKey}/home`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    const revisionedMixin = 'jmix:publiclyRevisioned';

    const captureTimeoutMs = 30000;
    const pollIntervalMs = 1000;

    // ------------------------------------------------------------ GraphQL response shapes

    interface JcrPropertyValue {
        name: string
        value: string | null
    }

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface FolderQueryNode {
        properties: JcrPropertyValue[]
    }

    interface FolderQueryData {
        jcr: {
            nodeByPath: FolderQueryNode | null
        } | null
    }

    interface NodeQueryData {
        jcr: {
            nodeByPath: {
                uuid: string
            } | null
        } | null
    }

    interface SnapshotCountQueryData {
        jcr: {
            nodeByPath: {
                descendants: {
                    nodes: Array<{name: string}>
                } | null
            } | null
        } | null
    }

    interface AclEntry {
        aclEntryType: string
        role: {name: string} | null
        principal: {name: string; principalType: string} | null
    }

    interface AclQueryData {
        jcr: {
            nodeByPath: {
                acl: {
                    aclEntries: AclEntry[]
                } | null
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

    // ------------------------------------------------------------------------ domain shapes

    interface FolderStatus {
        lastCaptureStatus: string
        lastCaptureMessage: string
        lastCaptureDate: string
    }

    const emptyFolderStatus: FolderStatus = {lastCaptureStatus: '', lastCaptureMessage: '', lastCaptureDate: ''};

    // -------------------------------------------------------------------- GraphQL documents

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

    const snapshotCountQuery = gql`
        query snapshotCount($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    descendants(typesFilter: {types: ["crh:revisionSnapshot"]}) {
                        nodes {
                            name
                        }
                    }
                }
            }
        }
    `;

    const aclQuery = gql`
        query pageAcl($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    acl {
                        aclEntries(inclInherited: true) {
                            aclEntryType
                            role {
                                name
                            }
                            principal {
                                name
                                principalType
                            }
                        }
                    }
                }
            }
        }
    `;

    const revokeGuestRolesMutation = gql`
        mutation revokeGuestRoles($path: String!, $roles: [String]!) {
            jcr(workspace: EDIT) {
                mutateNode(pathOrId: $path) {
                    revokeRoles(principalName: "guest", principalType: USER, roleNames: $roles)
                }
            }
        }
    `;

    const folderPropertyNames = ['crh:lastCaptureStatus', 'crh:lastCaptureMessage', 'crh:lastCaptureDate'];

    // ------------------------------------------------------------------------------ helpers

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
                    // Not created yet -- the async capture job may not have run at all so far.
                    return emptyFolderStatus;
                }

                const props = propertyMap(node.properties ?? []);

                return {
                    lastCaptureStatus: props['crh:lastCaptureStatus'] ?? '',
                    lastCaptureMessage: props['crh:lastCaptureMessage'] ?? '',
                    lastCaptureDate: props['crh:lastCaptureDate'] ?? ''
                };
            });

    const countSnapshots = (folderPath: string): Cypress.Chainable<number> =>
        cy
            .apollo({query: snapshotCountQuery, variables: {path: folderPath}})
            .then((result: ApolloResult<SnapshotCountQueryData>) => result.data?.jcr?.nodeByPath?.descendants?.nodes?.length ?? 0);

    /**
     * Polls `fetch` until `predicate` accepts the result, then yields that result. See
     * 01-publicationSnapshotCapture.cy.ts for the rationale: capture is asynchronous, so every
     * assertion that depends on it must poll for the expected state rather than assume it is
     * done by the time the publish mutation returns.
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

    /**
     * Waits for the FIRST-EVER capture attempt on a brand-new page's folder to be recorded.
     * This is the precondition that makes "and creates no snapshot node" a non-vacuous
     * assertion afterwards: it proves a capture attempt genuinely ran and was durably recorded,
     * rather than merely that nothing happened yet.
     */
    const waitForFirstCaptureOutcome = (folderPath: string, errorMsg: string): Cypress.Chainable<FolderStatus> =>
        pollUntil(() => getFolderStatus(folderPath), status => status.lastCaptureDate !== '', errorMsg);

    const cleanupPage = (pagePath: string, pageUuid: string | null): void => {
        deleteNode(pagePath).then(null, () => undefined);
        deleteNode(pagePath, 'LIVE').then(null, () => undefined);
        if (pageUuid) {
            deleteNode(`${historyRoot}/${pageUuid}`).then(null, () => undefined);
        }
    };

    before(() => {
        cy.login();
        // Enable the module HERE, not in provisioning.yml -- see 01-publicationSnapshotCapture.cy.ts
        // for why. Safe to call again even if another spec already enabled it on this site.
        enableModule('content-revision-history', siteKey);
    });

    beforeEach(() => {
        cy.login();
    });

    it('records NOT_PUBLIC durably and stores no snapshot when the guest render cannot read the page', () => {
        const pagePath = `${homePath}/crh-e2e-not-public`;
        let pageUuid: string | null = null;

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-not-public',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - not public', language},
                {name: 'j:templateName', value: 'default'}
            ],
            mixins: [revisionedMixin]
        })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the scratch page must be creatable').to.be.undefined;

                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;

                // Discover whatever role(s) currently let guest read this page (normally
                // inherited from the site root) instead of guessing a role name: the assertion
                // this test makes only holds if guest really could read the page beforehand.
                return cy.apollo({query: aclQuery, variables: {path: pagePath}});
            })
            .then((result: ApolloResult<AclQueryData>) => {
                const entries = result.data?.jcr?.nodeByPath?.acl?.aclEntries ?? [];
                const guestGrantedRoles = Array.from(
                    new Set(
                        entries
                            .filter(entry => entry.aclEntryType === 'GRANT' && entry.principal?.name === 'guest')
                            .map(entry => entry.role?.name)
                            .filter((name): name is string => Boolean(name))
                    )
                );

                expect(
                    guestGrantedRoles.length,
                    'precondition: guest must be able to read this page before revoking access can prove anything'
                ).to.be.greaterThan(0);

                return cy.apollo({
                    mutation: revokeGuestRolesMutation,
                    variables: {path: pagePath, roles: guestGrantedRoles}
                });
            })
            .then((result: ApolloResult<unknown>) => {
                expect(result.errors, 'revoking guest\'s read role must succeed').to.be.undefined;

                // The mixin was already set at creation time, so this first-ever publish is
                // also the first-ever capture attempt -- no baseline STORED snapshot is needed.
                publishAndWaitJobEnding(pagePath, [language]);

                return waitForFirstCaptureOutcome(
                    `${historyRoot}/${pageUuid}/${language}`,
                    `expected a first capture attempt to be durably recorded for ${pagePath} within ${captureTimeoutMs}ms`
                );
            })
            .then(status => {
                expect(status.lastCaptureStatus, 'a guest-unreadable page must be recorded as NOT_PUBLIC').to.equal(
                    'NOT_PUBLIC'
                );
                expect(
                    status.lastCaptureMessage,
                    'the durable record must explain why, not just that something failed'
                ).to.not.equal('');

                return countSnapshots(`${historyRoot}/${pageUuid}/${language}`);
            })
            .then(count => {
                expect(count, 'NOT_PUBLIC must never produce a crh:revisionSnapshot node').to.equal(0);
            })
            .then(() => {
                // Best-effort restore before deleting, so a failed assertion above still leaves
                // guest access sane for whatever inherits from this subtree while cypress
                // retries/investigates.
                removeMixins(pagePath, [revisionedMixin]).then(null, () => undefined);
                cleanupPage(pagePath, pageUuid);
            });
    });

    it('records OVERSIZE durably and stores no snapshot when the rendered markdown exceeds the 1MB cap', () => {
        const pagePath = `${homePath}/crh-e2e-oversize`;
        // Comfortably over RevisionHistoryConstants.MAX_MARKDOWN_BYTES (1,048,576): a single
        // huge bigText property is the cheapest possible fixture -- far cheaper than creating
        // enough small nodes to add up to the same size.
        const oversizedText = 'x'.repeat(1_200_000);
        let pageUuid: string | null = null;

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-oversize',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - oversize', language},
                {name: 'j:templateName', value: 'default'}
            ],
            mixins: [revisionedMixin]
        })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the scratch page must be creatable').to.be.undefined;

                // A page created via addNode has no template-provisioned areas; create the
                // droppable container a real template would normally provide, exactly like one
                // would exist on any editorially-created page.
                return addNode({
                    parentPathOrId: pagePath,
                    primaryNodeType: 'jnt:contentList',
                    name: 'area-main'
                });
            })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the area container must be creatable').to.be.undefined;

                return addNode({
                    parentPathOrId: `${pagePath}/area-main`,
                    primaryNodeType: 'jnt:bigText',
                    name: 'bigText',
                    properties: [{name: 'text', value: oversizedText, language}]
                });
            })
            .then((result: ApolloResult<AddNodeQueryData>) => {
                expect(result.errors, 'the oversized bigText node must be creatable').to.be.undefined;

                return getUuid(pagePath);
            })
            .then(uuid => {
                pageUuid = uuid;

                // Mixin was set at creation time and the oversized content is already in place,
                // so this first-ever publish is also the first-ever (and doomed) capture
                // attempt -- no baseline STORED snapshot is needed.
                publishAndWaitJobEnding(pagePath, [language]);

                return waitForFirstCaptureOutcome(
                    `${historyRoot}/${pageUuid}/${language}`,
                    `expected a first capture attempt to be durably recorded for ${pagePath} within ${captureTimeoutMs}ms`
                );
            })
            .then(status => {
                expect(status.lastCaptureStatus, 'markdown over the 1MB cap must be recorded as OVERSIZE').to.equal(
                    'OVERSIZE'
                );
                expect(
                    status.lastCaptureMessage,
                    'the durable record must explain why, not just that something failed'
                ).to.not.equal('');

                return countSnapshots(`${historyRoot}/${pageUuid}/${language}`);
            })
            .then(count => {
                expect(count, 'OVERSIZE must never produce a crh:revisionSnapshot node').to.equal(0);
            })
            .then(() => {
                cleanupPage(pagePath, pageUuid);
            });
    });
});
