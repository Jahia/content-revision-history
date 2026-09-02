import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Does the shipped backfill script actually run?
 *
 * Nothing exercised it before this file. Four other specs mention backfill, but they only
 * simulate backfilled STATE -- entries pinned to historical snapshots by hand -- so the Groovy
 * script itself had no coverage at all, on either the composition or the "does it even complete"
 * axis. That is how the defect below survived: it is not subtle, it aborts every run, and it was
 * found by reading rather than by any test.
 *
 * The defect: `crh:revisionHistory` sat in the script's SELF_RENDERING set, so the script FETCHED
 * its markdown view. That view renders empty on purpose (a page's changelog must never land inside
 * the record it describes), and fetchMarkdown refuses an empty 200 body, because splicing '' into a
 * reconstruction is how a page that changed gets stored as one that did not. So the run threw on
 * any page carrying the component -- which is every page with captured history, and therefore
 * exactly the page README tells you to backfill FIRST to prove the composition is faithful.
 *
 * What this asserts is the completion, not the report: the provisioning API returns only
 * `.installed` or `.failed`, and `.failed` is precisely what the defect produced. Composition
 * fidelity is checked by the script's own byte-for-byte gate, which compares each reconstructed
 * instant against the real captured snapshot for that instant and aborts on any unexplained
 * difference -- so a `.installed` on a page that HAS captured history means the gate passed too.
 * That is why this spec publishes twice before running anything.
 *
 * DRY_RUN stays true. The point is that the script completes and validates, not that it writes;
 * writing is the operator's decision and would make this spec's assertions depend on its own
 * side effects.
 */
describe('The shipped backfill script runs to completion', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const pageName = 'crh-backfill-spec';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/${pageName}`;
    const areaPath = `${pagePath}/pagecontent`;
    // Staged into fixtures by ci.build.sh, because the test image is built from tests/ only and
    // has no ../src. If this is missing, ci.build.sh was not run after checkout.
    const scriptFixture = 'backfill-revision-snapshots.groovy';

    const historyRoot = `/sites/${siteKey}/contents/revision-history`;
    let pageUuid = '';

    const snapshotCount = (): Cypress.Chainable<number> =>
        cy
            .apollo({
                fetchPolicy: 'no-cache',
                query: gql`
                    query snapshots($path: String!) {
                        jcr {
                            nodeByPath(path: $path) {
                                children { nodes { name } }
                            }
                        }
                    }
                `,
                variables: {path: `${historyRoot}/${pageUuid}/${language}`}
            })
            .then(result => {
                // The folder does not exist until the first capture lands, and "not yet" is a
                // legitimate answer while the Quartz job is still queued -- not an error.
                const nodes = (result as {data?: {jcr?: {nodeByPath?: {children?: {nodes?: unknown[]}}}}})
                    ?.data?.jcr?.nodeByPath?.children?.nodes;
                return nodes ? nodes.length : 0;
            });

    const waitForSnapshots = (min: number): Cypress.Chainable<number> =>
        cy
            .waitUntil<number | false>(
                () => snapshotCount().then(count => (count >= min ? count : false)),
                {
                    timeout: 60000,
                    interval: 2000,
                    errorMsg: `capture never produced ${min} snapshot(s); the script's validation ` +
                        'gate needs real captured snapshots to compare against',
                    verbose: true
                }
            )
            .then(count => count as number);

    /**
     * The shipped script with only its SETTINGS block filled in.
     *
     * Read from the staged copy of the real file rather than a copy committed here: ci.build.sh
     * stages it at build time, so this spec always runs the script that ships. The substitutions assert their own hit count, so a rename in
     * the settings block fails the spec loudly instead of silently running with an empty PAGE_PATH.
     */
    const configuredScript = (): Cypress.Chainable<string> =>
        cy.fixture(scriptFixture).then((source: string) => {
            const settings: Array<[string, string]> = [
                ['String PAGE_PATH = \'\'', `String PAGE_PATH = '${pagePath}'`],
                ['String RENDER_USER   = \'\'', 'String RENDER_USER   = \'root\''],
                ['String RENDER_SECRET = \'\'', `String RENDER_SECRET = '${Cypress.env('SUPER_USER_PASSWORD') || 'root1234'}'`]
            ];
            let configured = source;
            settings.forEach(([from, to]) => {
                expect(
                    configured.split(from).length - 1,
                    `the backfill settings block must still contain exactly one "${from}"`
                ).to.equal(1);
                configured = configured.replace(from, to);
            });
            // Reconstruction renders the DEFAULT workspace over this node's own connector.
            expect(configured, 'BASE_URL must stay the loopback connector, or the credential is withheld')
                .to.contain('String BASE_URL = \'http://127.0.0.1:8080\'');
            expect(configured, 'this spec must not write anything').to.contain('boolean DRY_RUN  = true');
            return configured;
        });

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            name: pageName,
            primaryNodeType: 'jnt:page',
            mixins: ['jmix:publiclyRevisioned'],
            properties: [
                {name: 'jcr:title', value: 'Backfill spec', language},
                {name: 'j:templateName', value: 'simple'}
            ]
        }).then((page: {data?: {jcr?: {addNode?: {uuid?: string}}}}) => {
            pageUuid = page.data?.jcr?.addNode?.uuid as string;
            expect(pageUuid, 'the page must yield a uuid: snapshots are keyed on it').to.be.a('string').and.not.be
                .empty;
        });

        addNode({parentPathOrId: pagePath, name: 'pagecontent', primaryNodeType: 'jnt:contentList'});
        addNode({
            parentPathOrId: areaPath,
            name: 'probe-text',
            primaryNodeType: 'jnt:bigText',
            properties: [{name: 'text', value: '<p>First published wording.</p>', language}]
        });
        // The component that used to abort the run. Without it this spec cannot fail on the defect.
        addNode({parentPathOrId: areaPath, name: 'probe-history', primaryNodeType: 'crh:revisionHistory'});

        publishAndWaitJobEnding(pagePath, [language]);
        waitForSnapshots(1);

        // A second, materially different version, so the page has real history for the gate to
        // validate against rather than a single snapshot.
        cy.apollo({
            mutation: gql`
                mutation setText($path: String!, $value: String!, $language: String!) {
                    jcr {
                        mutateNode(pathOrId: $path) {
                            mutateProperty(name: "text") {
                                setValue(value: $value, language: $language)
                            }
                        }
                    }
                }
            `,
            variables: {
                path: `${areaPath}/probe-text`,
                value: '<p>Second published wording, materially different.</p>',
                language
            }
        });
        publishAndWaitJobEnding(pagePath, [language]);
        waitForSnapshots(2);
    });

    after(() => {
        cy.login();
        deleteNode(pagePath).then(null, () => undefined);
    });

    it('completes on a page carrying a revision history component', () => {
        cy.login();
        configuredScript().then(script => {
            cy.runProvisioningScript({
                script: {fileContent: '[{"executeScript":"backfill-spec.groovy"}]', type: 'application/json'},
                files: [{fileContent: script, fileName: 'backfill-spec.groovy', type: 'text/plain'}]
            }).then(result => {
                const outcome = JSON.stringify(result);
                // `.failed` is exactly what the empty-body refusal produced on any page carrying a
                // revision history, and it is the whole reason this spec exists.
                expect(
                    outcome,
                    'the backfill must not abort on a page that carries a revision history component'
                ).to.not.contain('.failed');
                expect(outcome, 'the script must report a completed run').to.contain('.installed');
            });
        });
    });
});
