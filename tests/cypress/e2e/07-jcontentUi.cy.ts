import gql from 'graphql-tag';
import {addNode, deleteNode, enableModule, publishAndWaitJobEnding, removeMixins} from '@jahia/cypress';

/**
 * Does an EDITOR actually see the snapshot store in jContent?
 *
 * Every other spec in this suite answers that with a GraphQL query, and twice that was not good
 * enough. A GraphQL assertion proves what the AUTHOR believed jContent asks for, and the belief was
 * wrong: the filter it used, includeTypes [jmix:droppableContent, jnt:page, jnt:file], belongs to
 * the content PICKER. The content BROWSER filters flat views on
 * [jmix:editorialContent, jmix:queryContent] and recurses the tree only into
 * [jnt:page, jnt:contentFolder, jnt:folder, ...]. So the suite stayed green through two releases
 * while the tree was empty on screen.
 *
 * This file loads jContent in the browser instead, and asserts against jContent's own test hooks
 * (data-cm-role), so it cannot be satisfied by a filter the author invented.
 *
 * Mutation-checked: reverting crh:snapshotFolder to `> jnt:content, jmix:droppableContent` (so the
 * tree cannot recurse into it) or crh:revisionSnapshot to `> jnt:content, jmix:droppableContent`
 * (so the flat view lists nothing) fails the tests below.
 */
describe('jContent shows the snapshot store to an editor', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedMixin = 'jmix:publiclyRevisioned';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-ui`;
    const areaPath = `${pagePath}/area-main`;
    const historyRoot = `/sites/${siteKey}/contents/revision-history`;

    const captureTimeoutMs = 60000;
    const uiTimeoutMs = 90000;

    /** The jContent test hooks. Stable across releases in a way CSS classes are not. */
    const TREE = '[data-cm-role="navtree-holder"]';
    const TABLE = '[data-cm-role="table-content-list"]';
    const ROW = '[data-cm-role="table-content-list-row"]';
    const NAME_CELL = '[data-cm-role="table-content-list-cell-name"]';

    interface ApolloResult<T> { data?: T; errors?: Array<{message: string}> }
    interface AddNodeQueryData { jcr: { addNode: { uuid: string } } }
    interface ChildrenData {
        jcr: { nodeByPath?: { children: { nodes: Array<{name: string}> } } | null }
    }

    const nodeUuidQuery = gql`query($path: String!) { jcr { nodeByPath(path: $path) { uuid } } }`;
    const childrenQuery = gql`
        query($path: String!) {
            jcr(workspace: EDIT) { nodeByPath(path: $path) { children { nodes { name } } } }
        }
    `;

    let pageUuid = '';
    let snapshotName = '';

    /** The jContent route: /jahia/jcontent/<site>/<lang>/<mode>/<path relative to the site>. */
    const jcontent = (relativePath: string) =>
        `/jahia/jcontent/${siteKey}/${language}/content-folders/${relativePath}`;

    const getUuid = (path: string): Cypress.Chainable<string> =>
        cy.apollo({query: nodeUuidQuery, variables: {path}}).then((r: ApolloResult<{jcr: {nodeByPath?: {uuid: string}}}>) => {
            const uuid = r.data?.jcr?.nodeByPath?.uuid;
            expect(uuid, `${path} must resolve to a uuid`).to.be.a('string').and.not.be.empty;
            return uuid as string;
        });

    const snapshotNames = (): Cypress.Chainable<string[]> =>
        cy.apollo({query: childrenQuery, variables: {path: `${historyRoot}/${pageUuid}/${language}`}})
            .then((r: ApolloResult<ChildrenData>) =>
                (r.data?.jcr?.nodeByPath?.children.nodes ?? [])
                    .map(n => n.name)
                    .filter(n => !n.startsWith('j:')));

    /** Waits for jContent's table to finish loading before anything is asserted about it. */
    const openInJContent = (relativePath: string) => {
        cy.visit(jcontent(relativePath));
        cy.get(TABLE, {timeout: uiTimeoutMs}).should('exist');
        return cy.get(ROW, {timeout: uiTimeoutMs}).should('have.length.greaterThan', 0);
    };

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-ui',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - jContent UI', language},
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
                    properties: [{name: 'text', value: '<p>Wording an editor will look for.</p>', language}]
                })
            )
            .then(() => getUuid(pagePath))
            .then(uuid => {
                pageUuid = uuid;
                publishAndWaitJobEnding(pagePath, [language]);
                return cy.waitUntil<string[] | false>(
                    () => snapshotNames().then(names => (names.length > 0 ? names : false)),
                    {timeout: captureTimeoutMs, interval: 1000, errorMsg: 'a snapshot must be captured', verbose: true}
                );
            })
            .then(names => {
                snapshotName = (names as string[])[0];
                expect(snapshotName, 'a snapshot name is needed to look for it on screen').to.not.be.empty;
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

    it('lists the revision-history folder in the Content section', () => {
        // Fails when crh:snapshotFolder is not something the content view lists.
        openInJContent('contents');
        cy.get(NAME_CELL).should('contain.text', 'revision-history');
    });

    it('shows revision-history in the navigation tree, so it can be opened', () => {
        // Separate from the row above on purpose: the table lists it, the TREE is what lets an
        // editor descend into it, and they are driven by different filters. A folder that lists
        // but cannot be opened is still a dead end.
        cy.visit(jcontent('contents'));
        cy.get(TREE, {timeout: uiTimeoutMs}).should('exist');
        cy.get(TREE).should('contain.text', 'revision-history');
    });

    it('lists the captured snapshots when the language folder is opened', () => {
        // THE assertion. The snapshots are what an editor has to read before describing a
        // revision, and this is what was empty on screen through two releases.
        openInJContent(`contents/revision-history/${pageUuid}/${language}`);
        cy.get(NAME_CELL).should('contain.text', snapshotName);
    });

    it('opens a snapshot without an error', () => {
        // The preview is the point of making the store browsable: an editor has to read a
        // snapshot before writing the revision entry that describes it.
        cy.visit(`/cms/render/default/${language}${historyRoot}/${pageUuid}/${language}/${snapshotName}.html`);
        cy.get('body', {timeout: uiTimeoutMs}).should('contain.text', 'Captured');
        cy.get('body').should('not.contain.text', 'HTTP Status 404');
    });

    // --- readable by someone other than root ----------------------------------------------
    //
    // Up to 1.3.x the history root had its ACL inheritance broken with nothing granted. The code
    // said that left access to "server administrators, who bypass ACLs", which is untrue: in Jahia
    // a server administrator is a ROLE, and a role is delivered through ACL entries -- exactly what
    // breaking inheritance removes. Measured on 8.2.x, every non-root account was denied, including
    // site and server administrators. Only root could read a snapshot, because root is the JCR
    // system user and bypasses the access manager rather than holding a role.
    //
    // That made the feature unusable: an editor has to read a snapshot to describe it, and the
    // picker offers exactly those snapshots. Every check before this one ran as root, which is
    // precisely why nobody noticed.

    it('shows the snapshot store to an editor, not only to root', () => {
        cy.login('mathias', 'password');
        cy.visit(jcontent('contents'));
        cy.get(TABLE, {timeout: uiTimeoutMs}).should('exist');
        cy.get(NAME_CELL).should('contain.text', 'revision-history');
    });

    it('shows an editor the snapshots themselves', () => {
        // The folder being listed is not enough: the snapshots inside it are what has to be read
        // before a revision entry can be written for one.
        cy.login('mathias', 'password');
        openInJContent(`contents/revision-history/${pageUuid}/${language}`);
        cy.get(NAME_CELL).should('contain.text', snapshotName);
    });
});
