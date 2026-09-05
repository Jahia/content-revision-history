import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins} from '@jahia/cypress';

/**
 * GHSA-q67w-prc3-ch5h #2: the public revision record must not be rewritable by its own subjects.
 *
 * Every crh:revisionSnapshot property is declared `hidden`, which suppresses it from the edit form
 * but does NOT refuse a write -- only `protected` does that, and a protected property cannot be
 * written even by the system session that captures it. So the ONLY thing standing between a
 * contributor and the record is the ACL on the history root, and an ACL is exactly the kind of
 * thing a unit test cannot prove: RevisionSnapshotServiceTest asserts which methods
 * enforceCuratorReadOnly calls on a mock, not what a real repository then permits.
 *
 * <p><b>This file is why the fix is not what it first was.</b> The obvious implementation grants
 * `reader` to a curator GROUP, and the first version of the fix named `g:privileged`. It looked
 * right, the unit test passed, and it was wrong: measured here, the effective ACL of
 * /sites/digitall/contents grants roles mostly to INDIVIDUAL USERS (u:mathias=editor,
 * u:jane=contributor, u:irina=reviewer), and the global g:privileged group carries a DENY. So the
 * grant reached nobody, every editor lost sight of the store, and 07-jcontentUi caught it -- the
 * 1.3.x failure, reintroduced by a security fix. The fix now COPIES the principals that could
 * already read instead of naming a group, and these tests pin that.
 *
 * <p>The behavioural tests are driven as `mathias`, a real digitall editor with write on the site's
 * content tree, because `root` is the JCR system user and bypasses the access manager entirely --
 * every assertion here would pass against a tree with no ACL at all. The write test carries a
 * CONTROL write to ordinary site content: without it, a refused snapshot write proves nothing,
 * since it passes just as well for a user with no rights anywhere.
 */
describe('The snapshot record is tamper-protected, and curators can still read it', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-tamper`;
    const areaPath = `${pagePath}/area-main`;
    const textPath = `${areaPath}/policyText`;
    const contentsPath = `/sites/${siteKey}/contents`;
    const historyRoot = `${contentsPath}/revision-history`;

    /**
     * The role enforceCuratorReadOnly re-grants, and the only one it may grant.
     *
     * NOT 'reader'. Jahia scopes roles to a WORKSPACE and the built-in reader role is
     * jcr:read_live alone, while this tree is jmix:nolive and exists only in default -- so granting
     * reader grants read of a workspace the record is never in. That was the second wrong version
     * of this fix: the ACL looked perfect (GRANT reader USER:mathias, inheritance broken) and the
     * editor still got PathNotFoundException. 'privileged' is jcr:read_default with no write and no
     * publish, which is exactly "may look at it in the back office".
     */
    const CURATOR_ROLE = 'privileged';

    /**
     * Roles that confer write. None may be granted on the history root, by any route -- local or
     * inherited. Named rather than "anything but reader" so the assertion stays readable when
     * Jahia adds a role this module has never heard of.
     */
    const WRITE_CONFERRING_ROLES = ['editor', 'editor-in-chief', 'contributor', 'owner'];

    // A real back-office editor shipped with digitall, and the same user 07-jcontentUi drives.
    const editor = 'mathias';
    const editorPassword = 'password';

    const captureTimeoutMs = 60000;
    const pollIntervalMs = 1000;

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface AclEntry {
        aclEntryType: string
        role: {name: string} | null
        principal: {name: string, principalType: string} | null
    }

    interface AclData {
        jcr: { nodeByPath?: { acl: { aclEntries: AclEntry[] } | null } | null }
    }

    interface ChildrenData {
        jcr: { nodeByPath?: { children: { nodes: Array<{name: string}> } } | null }
    }

    interface HashData {
        jcr: { nodeByPath?: { hash?: {value: string} | null } | null }
    }

    let pageUuid = '';
    let snapshotName = '';

    const folderPath = () => `${historyRoot}/${pageUuid}/${language}`;
    const snapshotPath = () => `${folderPath()}/${snapshotName}`;

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;

    const childrenQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) { nodeByPath(path: $path) { children { nodes { name } } } }
        }
    `;

    // Two queries rather than one with a variable: `inclInherited: true` is the form already proven
    // against this schema in 02-captureFailureModes, and the difference between them is the point --
    // the local set says what this module granted, the full set says what the node effectively
    // carries once inheritance is accounted for.
    const localAclQuery = gql`
        query localAcl($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    acl {
                        aclEntries(inclInherited: false) {
                            aclEntryType
                            role { name }
                            principal { name principalType }
                        }
                    }
                }
            }
        }
    `;

    const effectiveAclQuery = gql`
        query effectiveAcl($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    acl {
                        aclEntries(inclInherited: true) {
                            aclEntryType
                            role { name }
                            principal { name principalType }
                        }
                    }
                }
            }
        }
    `;

    const snapshotHashQuery = gql`
        query snapshotHash($path: String!) {
            jcr(workspace: EDIT) {
                nodeByPath(path: $path) {
                    hash: property(name: "crh:contentHash") { value }
                }
            }
        }
    `;

    /** Jahia's ACL principal key: what grantRoles takes, and what the fix stores. */
    const principalKey = (entry: AclEntry): string =>
        `${entry.principal?.principalType === 'GROUP' ? 'g' : 'u'}:${entry.principal?.name}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}})
            .then((result: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
                const uuid = result.data?.jcr?.nodeByPath?.uuid;
                expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
                return uuid as string;
            });

    const snapshotNames = (): Cypress.Chainable<string[]> =>
        cy.apollo({query: childrenQuery, variables: {path: folderPath()}, errorPolicy: 'all'})
            .then((r: ApolloResult<ChildrenData>) =>
                (r.data?.jcr?.nodeByPath?.children.nodes ?? [])
                    .map(n => n.name)
                    .filter(n => !n.startsWith('j:')));

    const pollUntil = <T>(
        fetch: () => Cypress.Chainable<T>,
        predicate: (value: T) => boolean,
        errorMsg: string
    ): Cypress.Chainable<T> =>
        cy
            .waitUntil<T | false>(() => fetch().then(value => (predicate(value) ? value : false)), {
                timeout: captureTimeoutMs, interval: pollIntervalMs, errorMsg, verbose: true
            })
            .then(value => value as T) as Cypress.Chainable<T>;

    const entriesOf = (query: typeof localAclQuery, path: string): Cypress.Chainable<AclEntry[]> =>
        cy.apollo({query, variables: {path}, errorPolicy: 'all'})
            .then((r: ApolloResult<AclData>) => r.data?.jcr?.nodeByPath?.acl?.aclEntries ?? []);

    /**
     * A GraphQL call made AS a given user.
     *
     * <p><b>Not cy.apollo.</b> The harness's apollo client authenticates with its own configured
     * administrator credentials and ignores the browser session, so every cy.apollo in this file
     * runs as root no matter what cy.login was called with -- and root is the JCR system user, which
     * bypasses the access manager entirely. The first version of these tests used cy.apollo after
     * cy.login(mathias) and therefore asserted nothing: the "editor may still read" test passed
     * because ROOT could read, and the "editor may not write" test failed because ROOT could write.
     * A permission test that authenticates as somebody else is not a permission test.
     *
     * <p>cy.request carries basic auth of its own. The Origin header is required: Jahia's graphql
     * scope is auto_apply origin=hosted, so a request without a same-origin header is refused
     * whatever the credentials.
     */
    const graphqlAs = (user: string, password: string, query: string, variables: object) =>
        cy.request({
            method: 'POST',
            url: '/modules/graphql',
            auth: {user, pass: password},
            headers: {'Content-Type': 'application/json', Origin: Cypress.config('baseUrl') as string},
            body: {query, variables},
            failOnStatusCode: false
        });

    const READ_HASH = `query snapshotHash($path: String!) {
        jcr(workspace: EDIT) { nodeByPath(path: $path) { hash: property(name: "crh:contentHash") { value } } }
    }`;

    const WRITE_PLAIN = `mutation setProp($path: String!, $property: String!, $value: String!) {
        jcr(workspace: EDIT) { mutateNode(pathOrId: $path) { mutateProperty(name: $property) { setValue(value: $value) } } }
    }`;

    const WRITE_TRANSLATED = `mutation setTranslated($path: String!, $property: String!, $value: String!, $language: String!) {
        jcr(workspace: EDIT) { mutateNode(pathOrId: $path) { mutateProperty(name: $property) { setValue(value: $value, language: $language) } } }
    }`;

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-tamper',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - tamper protection', language},
                {name: 'j:templateName', value: 'home'}
            ],
            mixins: [revisionedMixin]
        })
            .then(() =>
                addNode({parentPathOrId: pagePath, primaryNodeType: 'jnt:contentList', name: 'area-main'}))
            .then(() =>
                addNode({
                    parentPathOrId: areaPath,
                    primaryNodeType: 'jnt:bigText',
                    name: 'policyText',
                    properties: [{name: 'text', value: '<p>The wording of record.</p>', language}]
                }))
            .then(() => getUuid(pagePath))
            .then(uuid => {
                pageUuid = uuid;
                publishAndWaitJobEnding(pagePath, [language]);
                return pollUntil(snapshotNames, names => names.length >= 1, 'a snapshot must be captured before anything about its protection can be asserted');
            })
            .then(names => {
                snapshotName = names[0];
                expect(snapshotName, 'the captured snapshot must have a name').to.be.a('string').and.not.be.empty;
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

    // ---------------------------------------------------------------- the reader set, structurally

    it('re-grants READ to every principal that could already read the site content', () => {
        // The fix copies the readers rather than naming a group, so this asserts the copy against
        // the source. Naming a group is what broke it: g:privileged carries a DENY here and the
        // editors hold their roles as individual users, so a group grant reached nobody at all.
        entriesOf(effectiveAclQuery, contentsPath).then(parentEntries => {
            const denied = new Set(parentEntries
                .filter(e => e.aclEntryType === 'DENY')
                .map(principalKey));
            const expectedReaders = new Set(parentEntries
                .filter(e => e.aclEntryType === 'GRANT')
                .map(principalKey)
                .filter(p => !denied.has(p)));

            expect(expectedReaders.size,
                'the fixture is meaningless unless the site content grants somebody something')
                .to.be.greaterThan(0);

            return entriesOf(localAclQuery, historyRoot).then(rootEntries => {
                const granted = new Set(rootEntries
                    .filter(e => e.aclEntryType === 'GRANT' && e.role?.name === CURATOR_ROLE)
                    .map(principalKey));

                const missing = [...expectedReaders].filter(p => !granted.has(p));
                expect(
                    missing,
                    'every principal that could read the site content must still be able to read ' +
                    'the record. Missing: ' + JSON.stringify(missing) +
                    '; history root granted: ' + JSON.stringify([...granted])
                ).to.have.length(0);

                // A named editor, so the failure reads as "mathias lost the store" rather than as a
                // set-difference. This is the exact user 07-jcontentUi found locked out.
                expect(granted, `${editor} must be among them`).to.include(`u:${editor}`);
            });
        });
    });

    it('grants nothing writable on the history root, by any route', () => {
        // Inheritance is broken, so the site's editor/contributor grants must not reach this tree.
        // Asserted on the EFFECTIVE acl, not the local one: a local set holding only reader grants
        // would look identical whether inheritance was broken or not, so the assertion above cannot
        // see this failure and this one cannot be satisfied by the fix forgetting to break.
        entriesOf(effectiveAclQuery, historyRoot).then(entries => {
            const writable = entries.filter(e =>
                e.aclEntryType === 'GRANT' &&
                e.role?.name !== null &&
                WRITE_CONFERRING_ROLES.includes(e.role?.name as string));

            expect(
                writable,
                'no write-conferring role may reach the history root; the record would then be ' +
                'rewritable by its own subjects. Effective ACL was: ' + JSON.stringify(entries)
            ).to.have.length(0);
        });
    });

    // -------------------------------------------------------------- the reader set, behaviourally

    it('lets the editor write ordinary site content -- the control for the test below', () => {
        // Without this, the refusal asserted next proves nothing: it would pass identically for a
        // user with no rights anywhere, or for one whose credentials never reached the server. This
        // is what makes the refusal attributable to the snapshot ACL rather than to the fixture.
        graphqlAs(editor, editorPassword, WRITE_TRANSLATED, {
            path: textPath, property: 'text', value: '<p>Edited by the editor.</p>', language
        }).then(response => {
            expect(response.body.errors,
                'the fixture user must genuinely be able to write this site\'s content, or the ' +
                'refusal below is meaningless: ' + JSON.stringify(response.body.errors)).to.be.undefined;
        });
    });

    it('refuses a snapshot rewrite by that same editor', () => {
        // The vulnerability, driven end to end as a real editor. crh:contentHash is the honest thing
        // to tamper with: the advisory notes it cannot constrain an attacker because it is stored on
        // the node it describes, so rewriting the text and then the hash to match is one edit away --
        // unless the repository refuses the write outright.
        cy.apollo({query: snapshotHashQuery, variables: {path: snapshotPath()}, errorPolicy: 'all'})
            .then((before: ApolloResult<HashData>) => {
                const original = before.data?.jcr?.nodeByPath?.hash?.value;
                expect(original, 'the snapshot must carry a content hash to begin with')
                    .to.be.a('string').and.not.be.empty;

                return graphqlAs(editor, editorPassword, WRITE_PLAIN, {
                    path: snapshotPath(),
                    property: 'crh:contentHash',
                    value: 'tampered-by-an-editor'
                }).then(response => {
                    expect(response.body.errors,
                        'an editor must NOT be able to rewrite the public revision record')
                        .to.not.be.undefined;
                    expect(JSON.stringify(response.body.errors),
                        'and it must be refused as an ACCESS decision, not by some incidental error')
                        .to.contain('AccessDeniedException');

                    // Belt and braces: a refusal that still wrote would be worse than no refusal at
                    // all. Re-read with the harness client (root), which bypasses the ACL.
                    return cy.apollo({
                        query: snapshotHashQuery, variables: {path: snapshotPath()}, errorPolicy: 'all'
                    });
                }).then((after: ApolloResult<HashData>) => {
                    expect(after.data?.jcr?.nodeByPath?.hash?.value,
                        'the stored hash must be byte-for-byte what capture wrote').to.equal(original);
                });
            });
    });

    it('still lets that editor READ the snapshot, which 1.3.x did not', () => {
        // The other half, and the regression this pairing exists to catch. 1.3.x broke inheritance
        // and granted nothing: every non-root account was denied, an editor could not read the
        // snapshot they had to describe, and the picker offered a store nobody could open. A fix
        // that stops the write and also stops the read is not a fix, it is the 1.3.x bug with better
        // commit messages -- which is exactly what granting the `reader` role shipped, because
        // reader is jcr:read_live and this tree is jmix:nolive.
        graphqlAs(editor, editorPassword, READ_HASH, {path: snapshotPath()}).then(response => {
            expect(response.body.errors,
                'a back-office user must still be able to read a snapshot: they have to, to curate it')
                .to.be.undefined;
            expect(response.body.data?.jcr?.nodeByPath?.hash?.value,
                `reading returned nothing, so '${CURATOR_ROLE}' does not in fact deliver read in the ` +
                'default workspace to ' + editor + ' -- the record is locked away from its curators')
                .to.be.a('string').and.not.be.empty;
        });
    });
});
