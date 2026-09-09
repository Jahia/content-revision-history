import {addNode, deleteNode, enableModule} from '@jahia/cypress';

/**
 * The revision comparison must check EACH chosen entry against the caller's own rights, not merely
 * that the entry belongs to the history (GHSA-q67w-prc3-ch5h #4).
 *
 * Why the advisory could not close this from source alone. `RevisionDiffService` resolves both
 * caller-supplied identifiers inside a SYSTEM session, and until 1.4.11 the only test applied to
 * them was membership of the history's entry list -- a list also built on that system session, so
 * it contains entries the caller cannot read. Containment is a good check for what it was written
 * for (it stops an arbitrary UUID reaching a repository read) but every entry of the history passes
 * it whatever its own ACL. The reviewers marked it UNVERIFIED in both directions because the case
 * needs two entries with DIVERGENT per-entry ACLs, and none exist on a default install: entries are
 * editor-placed content that normally inherits from the history. This spec builds that case.
 *
 * HOW THE CONTROL WORKS, because without one this passes for the wrong reason. No snapshots are
 * created, so a comparison the caller IS entitled to make reports `noSnapshot`. A comparison
 * involving an entry the caller may not read reports `notFound`. Those are different strings, and
 * the per-entry gate runs BEFORE the snapshot lookup (RevisionDiffService:182 vs :195), so:
 *
 *   - gate working  -> restricted caller sees `notFound`, entitled caller sees `noSnapshot`
 *   - gate deleted  -> BOTH see `noSnapshot`, and the first assertion below fails
 *
 * That is what makes this a test of the gate rather than a test that two identifiers can be posted.
 * Asserting only "the label did not leak" would pass with the gate deleted too, because the view
 * prints labels solely on the `available` branch.
 */
describe('Per-entry access control on the revision comparison', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const pagePath = `/sites/${siteKey}/home/crh-e2e-entryacl`;
    const areaPath = `${pagePath}/area-main`;
    const containerName = 'history';
    const historyPath = `${areaPath}/${containerName}`;

    /** An editor: holds roles as an individual user on digitall, and can read ordinary content. */
    const editor = 'mathias';
    const editorPassword = 'password';

    /** Straight from the resource bundle, so a reworded message fails loudly instead of silently. */
    const NOT_FOUND = 'One of the selected revisions is not part of this history.';
    const NO_SNAPSHOT = 'No snapshot of the page was recorded for this revision';

    interface ApolloResult<T> {
        data?: T
        errors?: Array<{message: string}>
    }

    interface AddNodeQueryData {
        jcr: { addNode: { uuid: string } }
    }

    let restrictedUuid = '';
    let readableUuid = '';

    /**
     * The page rendered in the EDIT workspace, which is where an entry's own ACL is what decides.
     * `crhFrom`/`crhTo` are the parameters the comparison form posts, so this is the real path a
     * caller takes and not a service call dressed up as one.
     */
    const compareAs = (user: string, password: string) => {
        cy.login(user, password);
        return cy
            .request<string>({
                url: `/cms/render/default/${language}${pagePath}.html`,
                qs: {crhFrom: restrictedUuid, crhTo: readableUuid},
                failOnStatusCode: false
            })
            .then(response => response.body);
    };

    const entry = (name: string, label: string) =>
        addNode({
            parentPathOrId: historyPath,
            primaryNodeType: 'crh:revisionEntry',
            name,
            properties: [
                {name: 'revisionLabel', value: label},
                {name: 'revisionDate', value: new Date().toISOString(), type: 'DATE'},
                {name: 'summary', value: `summary of ${label}`, language}
            ]
        });

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);
        deleteNode(pagePath).then(null, () => undefined);
        addNode({
            parentPathOrId: `/sites/${siteKey}/home`,
            name: 'crh-e2e-entryacl',
            primaryNodeType: 'jnt:page',
            properties: [
                {name: 'jcr:title', value: 'Entry ACL probe', language},
                {name: 'j:templateName', value: 'simple'}
            ],
            children: [{name: 'area-main', primaryNodeType: 'jnt:contentList'}]
        })
            .then(() =>
                addNode({
                    parentPathOrId: areaPath,
                    primaryNodeType: 'crh:revisionHistory',
                    name: containerName
                })
            )
            .then(() => entry('entry-restricted', '2.0'))
            .then((created: ApolloResult<AddNodeQueryData>) => {
                expect(created.errors, 'the entry to be restricted must be creatable').to.be.undefined;
                restrictedUuid = created.data?.jcr.addNode.uuid as string;
                return entry('entry-readable', '1.0');
            })
            .then((created: ApolloResult<AddNodeQueryData>) => {
                expect(created.errors, 'the readable entry must be creatable').to.be.undefined;
                readableUuid = created.data?.jcr.addNode.uuid as string;
                expect(restrictedUuid, 'both uuids are needed to compose a comparison').to.be.a('string')
                    .and.not.be.empty;
                expect(readableUuid).to.be.a('string').and.not.be.empty;
            });
    });

    after(() => {
        cy.login();
        deleteNode(pagePath).then(null, () => undefined);
    });

    it('the control: with both entries readable, the editor gets as far as the snapshot lookup', () => {
        // Establishes that this editor can read the page, the history AND both entries, so the
        // refusal asserted in the next test can only have come from the ACL applied between them.
        // Runs first deliberately: once inheritance is broken it cannot be re-established here
        // without asserting on the repair rather than on the fix.
        compareAs(editor, editorPassword).then(html => {
            expect(html, 'the editor must reach the comparison at all').to.contain('crh-diff-panel');
            expect(
                html,
                'with no snapshots the only obstacle should be the snapshot lookup, which sits AFTER' +
                    ' the per-entry gate -- so seeing notFound here would mean the gate is refusing' +
                    ' an entry the editor may in fact read'
            ).to.contain(NO_SNAPSHOT);
            expect(html, 'nothing should be refused as absent yet').to.not.contain(NOT_FOUND);
        });
    });

    it('refuses a comparison naming an entry the caller cannot read, though it IS in the history', () => {
        // Break inheritance on one entry and grant nothing, so it becomes unreadable to everyone
        // but the system while remaining a child of the history -- which is precisely the state
        // containment cannot distinguish. Done through the provisioning API because Jahia's GraphQL
        // exposes ACL reads but no ACL write.
        const groovy = [
            'import org.jahia.services.content.JCRTemplate',
            'JCRTemplate.instance.doExecuteWithSystemSession(null, \'default\', { session ->',
            `    def node = session.getNodeByIdentifier('${restrictedUuid}')`,
            '    node.setAclInheritanceBreak(true)',
            '    session.save()',
            '    return null',
            '} as org.jahia.services.content.JCRCallback)'
        ].join('\n');

        cy.login();
        cy.runProvisioningScript({
            script: {fileContent: '[{"executeScript":"entry-acl.groovy"}]', type: 'application/json'},
            files: [{fileContent: groovy, fileName: 'entry-acl.groovy', type: 'text/plain'}]
        }).then(result => {
            expect(JSON.stringify(result), 'the ACL break must have been applied').to.not.contain('.failed');
        });

        compareAs(editor, editorPassword).then(html => {
            expect(
                html,
                'an entry whose own ACL the caller does not satisfy must be answered as absent,' +
                    ' not read on a system session because the history happens to contain it'
            ).to.contain(NOT_FOUND);
            // The refusal has to be indistinguishable from a history that never had that entry,
            // or the page becomes an oracle for which identifiers are real.
            expect(
                html,
                'and it must not fall through to the snapshot lookup, which would prove the entry' +
                    ' was read anyway'
            ).to.not.contain(NO_SNAPSHOT);
            expect(html, 'the restricted revision label must not appear').to.not.contain('2.0');
        });
    });

    it('and root, who can read it, still gets the entitled answer -- so it is rights, not breakage', () => {
        // The other half of the control. If breaking inheritance had made the entry unresolvable
        // outright, the previous test would report notFound for everyone and would be measuring a
        // broken fixture rather than an access decision.
        compareAs('root', 'root1234').then(html => {
            expect(
                html,
                'a caller who may read the entry must still reach the snapshot lookup'
            ).to.contain(NO_SNAPSHOT);
            expect(html, 'root must not be refused').to.not.contain(NOT_FOUND);
        });
    });
});
