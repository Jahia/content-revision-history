import gql from 'graphql-tag';
import {
    addNode,
    createUser,
    deleteNode,
    deleteUser,
    enableModule,
    grantRoles,
    publishAndWaitJobEnding
} from '@jahia/cypress';

/**
 * The capture principal: giving a revision history to pages the public cannot read.
 *
 * Capture renders anonymously by default, so a page `guest` may not read answers 403 and is
 * recorded NOT_PUBLIC with no snapshot -- not partially, at all. Configuring `capture.user` is
 * what makes such a page capturable, and until this file nothing exercised that path end to end:
 * the unit tests cover the credential logic and the runtime check covers the component
 * activating, but nothing joined restricted page -> authenticated capture -> stored snapshot.
 *
 * Three things are asserted here, and the third matters as much as the second:
 *
 *   1. the default really does refuse (the regression guard for every existing installation),
 *   2. a configured principal that may read the page really does capture it,
 *   3. `crh:capturedBy` names THAT principal, not `guest`.
 *
 * (3) is provenance. A snapshot is one artifact with one visibility, and `crh:capturedBy` is what
 * tells a later reader whose view of the page the text represents -- and therefore who may safely
 * be shown it. A record that says `guest` while holding content only a privileged account could
 * see is worse than one that says nothing, because it invites exactly the wrong conclusion.
 *
 * The configuration is applied through the provisioning API's `editConfiguration` operation,
 * which writes the module's `.cfg` in `karaf/etc`; Felix FileInstall then delivers it to the DS
 * component's `@Modified`. That round trip is asynchronous, so every assertion here polls for the
 * EFFECT rather than sleeping for a guessed interval.
 */
describe('Capture principal (restricted pages)', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const homePath = `/sites/${siteKey}/home`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const configPid = 'org.jahia.modules.revisionhistory';

    const pageName = 'crh-e2e-restricted';
    const pagePath = `${homePath}/${pageName}`;
    const captureUser = 'crh-e2e-capture';
    // Test fixture only. A real deployment puts this in a file that `capture.secretFile` names,
    // so its permissions decide who can read it.
    const captureSecret = 'crh-e2e-capture-secret';

    const captureTimeoutMs = 60000;
    const pollIntervalMs = 1000;
    // Comfortably past MIN_CAPTURE_INTERVAL_MILLIS, so a publish is never refused as RATE_LIMITED.
    const rateLimitPaceMs = 2000;

    let pageUuid = '';
    let lastPublishAt = 0;
    let guestRoles: string[] = [];

    // ------------------------------------------------------------------ response shapes

    interface JcrPropertyValue { name: string; value: string | null }
    interface ApolloResult<T> { data?: T; errors?: Array<{message: string}> }

    interface NodeQueryData { jcr: {nodeByPath: {uuid: string} | null} }
    interface FolderQueryData { jcr: {nodeByPath: {properties: JcrPropertyValue[]} | null} }
    interface SnapshotsQueryData {
        jcr: {
            nodeByPath: {
                descendants: {nodes: Array<{name: string; properties: JcrPropertyValue[]}>}
            } | null
        }
    }
    interface AclQueryData {
        jcr: {
            nodeByPath: {
                acl: {
                    aclEntries: Array<{
                        aclEntryType: string
                        role: {name: string} | null
                        principal: {name: string} | null
                    }>
                }
            } | null
        }
    }

    // ------------------------------------------------------------------ queries

    const nodeUuidQuery = gql`
        query node($path: String!) {
            jcr(workspace: EDIT) { nodeByPath(path: $path) { uuid } }
        }
    `;

    const folderQuery = gql`
        query folder($path: String!, $names: [String]) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) { properties(names: $names) { name value } }
            }
        }
    `;

    const snapshotsQuery = gql`
        query snapshots($path: String!, $names: [String]) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    descendants(typesFilter: {types: ["crh:revisionSnapshot"]}) {
                        nodes { name properties(names: $names) { name value } }
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
                            role { name }
                            principal { name }
                        }
                    }
                }
            }
        }
    `;

    const revokeGuestMutation = gql`
        mutation revokeGuest($path: String!, $roles: [String]!) {
            jcr(workspace: EDIT) {
                mutateNode(pathOrId: $path) {
                    revokeRoles(principalName: "guest", principalType: USER, roleNames: $roles)
                }
            }
        }
    `;

    const setTextMutation = gql`
        mutation setText($path: String!, $value: String!, $language: String!) {
            jcr(workspace: EDIT) {
                mutateNode(pathOrId: $path) {
                    mutateProperty(name: "jcr:title") { setValue(value: $value, language: $language) }
                }
            }
        }
    `;

    // ------------------------------------------------------------------ helpers

    const propertyMap = (properties: JcrPropertyValue[]): Record<string, string> => {
        const map: Record<string, string> = {};
        for (const property of properties) {
            if (property.value !== null && property.value !== undefined) {
                map[property.name] = property.value;
            }
        }

        return map;
    };

    const languageFolder = () => `${historyRoot}/${pageUuid}/${language}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((result: ApolloResult<NodeQueryData>) => {
            const uuid = result.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    const captureStatus = (): Cypress.Chainable<string> =>
        cy
            .apollo({query: folderQuery, variables: {path: languageFolder(), names: ['crh:lastCaptureStatus']}})
            .then((result: ApolloResult<FolderQueryData>) => {
                const node = result.data?.jcr?.nodeByPath;
                // Absent folder means the job has not run yet, which is not a status.
                return node ? propertyMap(node.properties ?? [])['crh:lastCaptureStatus'] ?? '' : '';
            });

    const snapshots = (): Cypress.Chainable<Array<Record<string, string>>> =>
        cy
            .apollo({query: snapshotsQuery, variables: {path: languageFolder(), names: ['crh:capturedBy', 'crh:snapshotDate']}})
            .then((result: ApolloResult<SnapshotsQueryData>) =>
                (result.data?.jcr?.nodeByPath?.descendants?.nodes ?? []).map(n => ({
                    name: n.name,
                    ...propertyMap(n.properties ?? [])
                }))
            );

    /**
     * Polls until `predicate` accepts, then yields the value. Every wait in this file goes
     * through here: the capture job, the provisioning write and FileInstall's delivery are all
     * asynchronous, and a fixed sleep would be either flaky or needlessly slow.
     */
    const pollUntil = <T>(fetch: () => Cypress.Chainable<T>, predicate: (value: T) => boolean, errorMsg: string): Cypress.Chainable<T> =>
        cy
            .waitUntil<T | false>(() => fetch().then(value => (predicate(value) ? value : false)), {
                timeout: captureTimeoutMs, interval: pollIntervalMs, errorMsg, verbose: true
            })
            // `as Chainable<T>`: waitUntil's signature admits `false` because that is what the
            // predicate returns while polling, but it THROWS on timeout rather than resolving
            // false, so no caller can observe it.
            .then(value => value as T) as Cypress.Chainable<T>;

    /** Writes the module's OSGi configuration through the provisioning API. */
    const setCaptureConfig = (content: string) =>
        cy.request({
            method: 'POST',
            url: '/modules/api/provisioning',
            headers: {'Content-Type': 'application/json'},
            // The provisioning API does not accept the browser session established by
            // cy.login(); it answers 401. Authenticate the request itself, from the same
            // credential source cy.login() uses rather than a second hardcoded copy.
            auth: {user: 'root', pass: Cypress.env('SUPER_USER_PASSWORD')},
            body: [{editConfiguration: configPid, format: 'cfg', content}]
        }).then(response => {
            expect(response.status, 'the configuration must be accepted').to.equal(200);
        });

    const configureCapturePrincipal = () =>
        setCaptureConfig(`capture.user = ${captureUser}\ncapture.secret = ${captureSecret}\n`);

    const clearCapturePrincipal = () => setCaptureConfig('capture.user =\ncapture.secret =\n');

    /**
     * Changes the page and publishes, so a capture is actually attempted (never a dedupe).
     *
     * Paces on the ELAPSED time since the last publish rather than sleeping a fixed interval:
     * it waits only as long as the rate limiter actually requires, and it is a condition rather
     * than a guess.
     */
    const changeAndPublish = (title: string): Cypress.Chainable<unknown> =>
        cy
            .waitUntil<boolean>(() => Date.now() - lastPublishAt >= rateLimitPaceMs, {
                timeout: rateLimitPaceMs + 5000,
                interval: 100,
                errorMsg: 'could not pace this publish past the capture rate limiter'
            })
            .then(() => cy.apollo({mutation: setTextMutation, variables: {path: pagePath, value: title, language}}))
            .then(() => {
                lastPublishAt = Date.now();
                return publishAndWaitJobEnding(pagePath, [language]);
            });

    /**
     * Publishes repeatedly until the capture status reaches `wanted`.
     *
     * A configuration change reaches the module through provisioning -> karaf/etc -> FileInstall
     * -> @Modified, which is asynchronous, so the first publish after writing the config may
     * still be handled by the old identity. Rather than sleeping for a guessed delivery time,
     * keep publishing until the effect appears.
     */
    const publishUntilStatus = (wanted: string, errorMsg: string): Cypress.Chainable<string> =>
        pollUntil(
            () => changeAndPublish(`CRH e2e - ${wanted} ${Date.now()}`).then(() => captureStatus()),
            status => status === wanted,
            errorMsg
        );

    // ------------------------------------------------------------------ lifecycle

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: pageName,
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - restricted', language},
                {name: 'j:templateName', value: 'default'}
            ],
            mixins: [revisionedMixin]
        });

        getUuid(pagePath).then(uuid => {
            pageUuid = uuid;
        });

        // Discover the roles that actually let guest read this page rather than guessing a name:
        // the whole test rests on guest having been able to read it before we revoke.
        cy.apollo({query: aclQuery, variables: {path: pagePath}}).then((result: ApolloResult<AclQueryData>) => {
            const entries = result.data?.jcr?.nodeByPath?.acl?.aclEntries ?? [];
            guestRoles = Array.from(new Set(
                entries
                    .filter(e => e.aclEntryType === 'GRANT' && e.principal?.name === 'guest')
                    .map(e => e.role?.name)
                    .filter((name): name is string => Boolean(name))
            ));

            expect(guestRoles.length, 'precondition: guest must be able to read the page before revoking proves anything').to.be.greaterThan(0);

            cy.apollo({mutation: revokeGuestMutation, variables: {path: pagePath, roles: guestRoles}});
        });

        // The technical user gets exactly the roles guest lost, and nothing else: the point is an
        // account that can read THIS page, not a privileged one.
        createUser(captureUser, captureSecret);
        cy.then(() => grantRoles(pagePath, guestRoles, captureUser, 'USER'));

        publishAndWaitJobEnding(pagePath, [language]);
    });

    beforeEach(() => {
        cy.login();
    });

    after(() => {
        cy.login();
        // Order matters: clear the credential before removing the account it names, so no window
        // exists in which the module is configured to authenticate as a user that is gone.
        clearCapturePrincipal();
        deleteUser(captureUser);
        deleteNode(pagePath).then(null, () => undefined);
        deleteNode(pagePath, 'LIVE').then(null, () => undefined);
        if (pageUuid) {
            deleteNode(`${historyRoot}/${pageUuid}`).then(null, () => undefined);
        }
    });

    // ------------------------------------------------------------------ tests

    it('refuses a page the public cannot read while capture is anonymous, and stores nothing', () => {
        // The default every existing installation runs with. NOT_PUBLIC is the correct answer
        // here, not a failure: there is no principal, so there is nothing the module may record.
        pollUntil(captureStatus, status => status === 'NOT_PUBLIC', 'capture must record NOT_PUBLIC for a page guest cannot read')
            .then(() => snapshots())
            .then(list => {
                expect(list, 'a refused capture must store no snapshot at all').to.have.length(0);
            });
    });

    it('captures that same page once a capture principal that may read it is configured', () => {
        configureCapturePrincipal()
            .then(() => publishUntilStatus('STORED', 'a configured capture principal that may read the page must capture it'))
            .then(() => snapshots())
            .then(list => {
                expect(list.length, 'the restricted page must now have a snapshot').to.be.greaterThan(0);
            });
    });

    it('records the configured principal in crh:capturedBy, not guest', () => {
        // Provenance. crh:capturedBy is what tells a later reader whose view of the page the
        // stored text represents, and therefore who may safely be shown it. Recording `guest` for
        // content only a privileged account could fetch invites precisely the wrong conclusion.
        snapshots().then(list => {
            expect(list.length, 'precondition: the previous test must have captured a snapshot').to.be.greaterThan(0);

            const principals = Array.from(new Set(list.map(s => s['crh:capturedBy'])));

            expect(principals, 'every snapshot of a restricted page must name the account that fetched it').to.deep.equal([captureUser]);
            expect(principals, 'and must never claim guest, which could not have read this page').to.not.include('guest');
        });
    });

    it('returns to refusing the page when the principal is cleared', () => {
        // Proves the @Modified path in both directions: a credential can be withdrawn without a
        // restart, and withdrawing it restores the safe default rather than leaving the module
        // authenticated with configuration the platform no longer holds.
        clearCapturePrincipal()
            .then(() => publishUntilStatus('NOT_PUBLIC', 'clearing the capture principal must return the page to NOT_PUBLIC'));
    });
});
