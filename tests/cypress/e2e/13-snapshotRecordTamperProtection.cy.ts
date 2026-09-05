import gql from 'graphql-tag';
import {
    addNode,
    createUser,
    deleteNode,
    deleteUser,
    enableModule,
    grantRoles,
    publishAndWaitJobEnding,
    removeMixins
} from '@jahia/cypress';

/**
 * GHSA-q67w-prc3-ch5h #2: the public revision record must not be rewritable by its own subjects.
 *
 * Every crh:revisionSnapshot property is declared `hidden`, which suppresses it from the edit form
 * but does NOT refuse a write -- only `protected` does that, and a protected property cannot be
 * written even by the system session that captures it. So the ONLY thing standing between a
 * contributor and the record is the ACL on the history root, and an ACL is exactly the kind of
 * thing a unit test cannot prove: RevisionSnapshotServiceTest asserts that
 * enforceCuratorReadOnly calls setAclInheritanceBreak(true) and grants `reader` to
 * `g:privileged`, which is a statement about two method calls on a mock, not about what a real
 * repository then permits.
 *
 * <p><b>This file is the confirmation of the principal.</b> `g:privileged` is a constant in
 * RevisionSnapshotService, chosen because Jahia's back-office users -- editors, site
 * administrators and server administrators -- are all members of it. If that is wrong, the fix
 * fails in one of two opposite ways and both are silent:
 *
 * <ul>
 *   <li>too narrow, and no curator can read a snapshot -- which is precisely the 1.3.x failure
 *       that made the feature unusable (it broke inheritance and granted nothing, so only the
 *       {@code root} system user could read the store nobody could then curate);</li>
 *   <li>too broad, or not applied at all, and a contributor can still rewrite what a public
 *       revision claims the page said.</li>
 * </ul>
 *
 * So the structural assertions below are paired with behavioural ones driven as a real,
 * non-root user, and the write test carries a CONTROL write to ordinary site content. Without
 * that control, a refused snapshot write proves nothing: it passes just as well for a user who
 * has no rights anywhere, which is the easiest way for this file to go green while testing
 * nothing.
 */
describe('The snapshot record is tamper-protected, and curators can still read it', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-tamper`;
    const areaPath = `${pagePath}/area-main`;
    const textPath = `${areaPath}/policyText`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;

    /**
     * The principal and role RevisionSnapshotService.enforceCuratorReadOnly grants. Written here as
     * literals rather than imported, deliberately: if someone changes the constant in the module,
     * this file must FAIL and make them come and justify the new principal, not silently follow it.
     */
    const CURATOR_PRINCIPAL = 'privileged';
    const READER_ROLE = 'reader';

    /**
     * Roles that confer write on content. None of these may be granted on the history root, by any
     * route -- local or inherited. Listing them by name rather than asking "is anything but reader
     * granted" keeps the assertion readable when Jahia adds a role this module has never heard of.
     */
    const WRITE_CONFERRING_ROLES = ['editor', 'editor-in-chief', 'contributor', 'owner', 'reviewer'];

    // A real back-office user, created for this file. Not root: root is the JCR system user and
    // bypasses the access manager entirely, so every assertion here would pass against a tree with
    // no ACL at all.
    const contributor = 'crh-e2e-contributor';
    const contributorPassword = 'crh-e2e-contributor-pw';

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
    // against this schema in 02-captureFailureModes, and the difference between the two is the
    // whole point -- local entries say what this module granted, the full set says what the node
    // effectively carries once inheritance is accounted for.
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

    const setPropertyMutation = gql`
        mutation setProp($path: String!, $property: String!, $value: String!) {
            jcr(workspace: EDIT) {
                mutateNode(pathOrId: $path) {
                    mutateProperty(name: $property) { setValue(value: $value) }
                }
            }
        }
    `;

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

    const entriesOf = (
        query: typeof localAclQuery, path: string
    ): Cypress.Chainable<AclEntry[]> =>
        cy.apollo({query, variables: {path}, errorPolicy: 'all'})
            .then((r: ApolloResult<AclData>) => r.data?.jcr?.nodeByPath?.acl?.aclEntries ?? []);

    const readHashAs = (
        user: string | null, password?: string
    ): Cypress.Chainable<ApolloResult<HashData>> => {
        if (user === null) {
            cy.login();
        } else {
            cy.login(user, password);
        }

        return cy.apollo({
            query: snapshotHashQuery, variables: {path: snapshotPath()}, errorPolicy: 'all'
        }) as unknown as Cypress.Chainable<ApolloResult<HashData>>;
    };

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        createUser(contributor, contributorPassword);

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
            // The attacker profile from the advisory, made real: a user who genuinely CAN write in
            // this site's content tree. Granted on the site so it covers /contents, which is where
            // the snapshot store lives -- that is the whole reason restoring inheritance was a leak.
            .then(() => grantRoles(`/sites/${siteKey}`, ['editor'], contributor, 'USER'))
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

        deleteUser(contributor);
    });

    // ---------------------------------------------------------------- the principal, structurally

    it('grants the curator group READ on the history root -- the principal the fix names', () => {
        // The direct confirmation that RevisionSnapshotService.CURATOR_PRINCIPAL landed as a real
        // ACE on a real repository, rather than as two verified calls on a Mockito mock.
        entriesOf(localAclQuery, historyRoot).then(entries => {
            const readerGrants = entries.filter(e =>
                e.aclEntryType === 'GRANT' &&
                e.role?.name === READER_ROLE &&
                e.principal?.name === CURATOR_PRINCIPAL);

            expect(
                readerGrants,
                `the history root must grant '${READER_ROLE}' to '${CURATOR_PRINCIPAL}'. Local ACL was: ` +
                JSON.stringify(entries)
            ).to.have.length.greaterThan(0);

            expect(readerGrants[0].principal?.principalType, 'it must be granted to a GROUP')
                .to.equal('GROUP');
        });
    });

    it('grants nothing writable on the history root, by any route', () => {
        // Inheritance is broken, so the site's editor/contributor grants must not reach this tree.
        // Asserted on the EFFECTIVE acl, not the local one: a local set with only `reader` in it
        // would look identical whether inheritance was broken or not, so the local assertion above
        // cannot see this failure and this one cannot be satisfied by the fix forgetting to break.
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

    // ---------------------------------------------------------------- the principal, behaviourally

    it('lets the contributor write ordinary site content -- the control for the test below', () => {
        // Without this, the refusal asserted next proves nothing: it would pass identically for a
        // user with no rights anywhere. This is what makes the refusal attributable to the
        // snapshot ACL rather than to the fixture.
        cy.login(contributor, contributorPassword);

        cy.apollo({
            mutation: setPropertyMutation,
            variables: {path: textPath, property: 'text', value: '<p>Edited by the contributor.</p>'},
            errorPolicy: 'all'
        }).then((result: ApolloResult<unknown>) => {
            expect(
                result.errors,
                'the fixture user must genuinely be able to write this site\'s content, or the ' +
                'refusal below is meaningless: ' + JSON.stringify(result.errors)
            ).to.be.undefined;
        });
    });

    it('refuses a snapshot rewrite by that same contributor', () => {
        // The vulnerability, driven end to end. crh:contentHash is the honest thing to tamper with:
        // the advisory notes it cannot constrain an attacker because it is stored on the node it
        // describes, so rewriting the text and then the hash to match is one edit away -- unless
        // the repository refuses the write outright.
        readHashAs(null).then(before => {
            const original = before.data?.jcr?.nodeByPath?.hash?.value;
            expect(original, 'the snapshot must carry a content hash to begin with').to.be.a('string').and.not.be.empty;

            cy.login(contributor, contributorPassword);

            return cy.apollo({
                mutation: setPropertyMutation,
                variables: {path: snapshotPath(), property: 'crh:contentHash', value: 'tampered-by-a-contributor'},
                errorPolicy: 'all'
            }).then((attempt: ApolloResult<unknown>) => {
                expect(
                    attempt.errors,
                    'a contributor must NOT be able to rewrite the public revision record'
                ).to.not.be.undefined;

                // Belt and braces: an accepted-but-ineffective mutation would leave errors
                // undefined and the value changed, and a refusal that still wrote would be worse
                // than no refusal at all. Re-read as root, which bypasses the ACL.
                return readHashAs(null);
            }).then(after => {
                expect(
                    after.data?.jcr?.nodeByPath?.hash?.value,
                    'the stored hash must be byte-for-byte what capture wrote'
                ).to.equal(original);
            });
        });
    });

    it('still lets that contributor READ the snapshot, which 1.3.x did not', () => {
        // The other half of the principal, and the regression this pairing exists to catch. 1.3.x
        // broke inheritance and granted nothing: every non-root account was denied, an editor could
        // not read the snapshot they had to describe, and the picker offered a store nobody could
        // open. A fix that only stops the write and also stops the read is not a fix, it is the
        // 1.3.x bug with better commit messages.
        readHashAs(contributor, contributorPassword).then(result => {
            expect(
                result.errors,
                'a back-office user must still be able to read a snapshot: they have to, to curate it'
            ).to.be.undefined;
            expect(
                result.data?.jcr?.nodeByPath?.hash?.value,
                `reading returned nothing, so '${CURATOR_PRINCIPAL}' does not in fact deliver ` +
                `'${READER_ROLE}' to this user -- the principal is wrong`
            ).to.be.a('string').and.not.be.empty;
        });
    });
});
