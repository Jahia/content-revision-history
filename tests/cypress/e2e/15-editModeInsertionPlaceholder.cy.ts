import {addNode, deleteNode, enableModule} from '@jahia/cypress';

/**
 * Can an editor still ADD a revision entry in Page Builder?
 *
 * Since jContent 3.7.0 the hover "add" button and the right-click Create action are derived from
 * the RENDERED page: jContent fetches the edit-mode HTML and reads the wildcard placeholder
 * (path="*") a view emits, rather than the nodetypes attribute of the enclosing module as 3.6.x
 * did. A view that renders its children with a bare <template:module> loop and no placeholder
 * therefore loses both affordances, silently, with no error (Jahia/jcontent#2771).
 *
 * Every other spec in this suite creates crh:revisionEntry nodes through the GraphQL addNode
 * mutation, which never touches Page Builder, so none of them could see this regression.
 *
 * This file asserts the CONTRACT jContent reads -- the edit-mode HTML -- rather than driving the
 * Page Builder UI: the placeholder is what jContent keys on, the HTML is deterministic, and a UI
 * assertion would depend on jContent's own test hooks, which move between releases.
 *
 * The first test fails if the editMode placeholder is removed from revisionHistory.jsp; the second
 * fails if it is emitted outside edit mode.
 */
describe('The revision list offers an insertion placeholder to an editor', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const homePath = `/sites/${siteKey}/home`;
    const pagePath = `${homePath}/crh-e2e-placeholder`;
    const areaPath = `${pagePath}/area-main`;
    const historyPath = `${areaPath}/history`;

    const editUrl = `/cms/edit/default/${language}${pagePath}.html`;
    const previewUrl = `/cms/render/default/${language}${pagePath}.html`;

    // The wildcard placeholder as the edit-mode renderer writes it. Matched loosely on purpose:
    // attribute order and quoting are the renderer's business, only the presence of a
    // path="*" element that names the entry type is the contract.
    const placeholder = /<[^>]*\bpath="\*"[^>]*>/;
    const entryType = /crh:revisionEntry/;

    before(() => {
        cy.login();
        enableModule('content-revision-history', siteKey);

        addNode({
            parentPathOrId: homePath,
            primaryNodeType: 'jnt:page',
            name: 'crh-e2e-placeholder',
            properties: [
                {name: 'jcr:title', value: 'CRH e2e - edit-mode placeholder', language},
                {name: 'j:templateName', value: 'home'}
            ]
        })
            .then(() => addNode({parentPathOrId: pagePath, primaryNodeType: 'jnt:contentList', name: 'area-main'}))
            .then(() => addNode({parentPathOrId: areaPath, primaryNodeType: 'crh:revisionHistory', name: 'history'}))
            // One existing entry: the case that broke is an insertion point for an EXISTING
            // child, so an empty list would not reproduce it.
            .then(() => addNode({
                parentPathOrId: historyPath,
                primaryNodeType: 'crh:revisionEntry',
                name: 'entry-1',
                properties: [{name: 'jcr:title', value: 'First revision', language}]
            }));
    });

    beforeEach(() => {
        cy.login();
    });

    after(() => {
        cy.login();
        deleteNode(pagePath).then(null, () => undefined);
    });

    it('emits a wildcard placeholder naming the entry type in edit mode', () => {
        // THE assertion. Without it jContent 3.7.x concludes nothing can be added to the list.
        cy.request(editUrl).then(response => {
            expect(response.status).to.eq(200);
            const html = response.body as string;
            const tags = html.match(new RegExp(placeholder.source, 'g')) ?? [];
            expect(tags, 'the edit-mode page must carry a path="*" placeholder').to.not.be.empty;
            expect(tags.join('\n'), 'the placeholder must name crh:revisionEntry').to.match(entryType);
        });
    });

    it('keeps the placeholder out of the public rendering', () => {
        // The list is a semantic <ol>; an editing affordance must not leak into what visitors get.
        cy.request(previewUrl).then(response => {
            expect(response.status).to.eq(200);
            expect(response.body as string).to.not.match(placeholder);
        });
    });
});
