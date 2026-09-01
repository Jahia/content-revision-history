import {addNode, deleteNode, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * Does an unspecialised content type reach the snapshot at all?
 *
 * The markdown template type ships views for jnt:page, jnt:bigText and a generic jnt:content
 * fallback. That fallback emitted jcr:title and then recursed into children, and nothing else, so a
 * node holding its text in any OTHER property rendered COMPLETELY empty.
 *
 * Measured on a real advisory page before this was fixed: a leaf carrying 388 characters of stored
 * text produced nothing, every instant of a backfill composed to the page heading alone (27 chars,
 * identical across five publication moments), and the run stored ONE snapshot for a page that had
 * changed five times. Live capture had the same hole, so the snapshots were near-empty too.
 *
 * jnt:press is the probe because it is a real Jahia type whose prose lives in `body`, which is
 * exactly the shape that was lost: not jcr:title, and not the jnt:bigText `text` the specialised
 * view knows about.
 *
 * Mutation-checked: removing the crh:textProperties loop from jnt_content/markdown/content.jsp
 * fails the first test below.
 */
describe('the generic markdown fallback emits content, not just titles', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const pagePath = `/sites/${siteKey}/home/crh-fallback`;
    const marker = 'PRESS-BODY-MARKER-9f3a';
    const titleMarker = 'Fallback probe heading';

    const renderMarkdown = () => cy.request<string>({
        url: `/cms/render/default/${language}${pagePath}.markdown`,
        failOnStatusCode: false
    });

    before(() => {
        cy.login();
        deleteNode(pagePath).then(null, () => undefined);
        addNode({
            parentPathOrId: `/sites/${siteKey}/home`,
            name: 'crh-fallback',
            primaryNodeType: 'jnt:page',
            mixins: ['jmix:publiclyRevisioned'],
            properties: [
                {name: 'jcr:title', value: 'Fallback probe', language},
                {name: 'j:templateName', value: 'simple'}
            ],
            children: [{name: 'area-main', primaryNodeType: 'jnt:contentList'}]
        });
        // A type with no markdown view of its own, whose text is NOT in jcr:title.
        addNode({
            parentPathOrId: `${pagePath}/area-main`,
            name: 'release',
            primaryNodeType: 'jnt:press',
            properties: [
                {name: 'jcr:title', value: titleMarker, language},
                {name: 'body', value: `<p>${marker}</p>`, language}
            ]
        });
        publishAndWaitJobEnding(pagePath, [language]);
        // Wait for the render to actually carry the content rather than for a fixed interval:
        // capture is asynchronous, and a fixed wait is both slower than it needs to be and
        // unreliable under load.
        cy.waitUntil(
            () => cy.request({
                url: `/cms/render/default/${language}${pagePath}.markdown`,
                failOnStatusCode: false
            }).then(r => r.status === 200 && String(r.body).includes(marker)),
            {timeout: 30000, interval: 1000, errorMsg: 'the page never rendered its content'}
        );
    });

    after(() => {
        cy.login();
        deleteNode(pagePath).then(null, () => undefined);
    });

    it('emits a text property that is not jcr:title', () => {
        cy.login();
        renderMarkdown().then(response => {
            expect(response.status).to.equal(200);
            // The whole defect in one assertion: this text is the node's content and it was absent.
            expect(response.body, 'the node body must reach the markdown').to.contain(marker);
        });
    });

    it('still emits the title as a heading, and only once', () => {
        cy.login();
        renderMarkdown().then(response => {
            expect(response.body).to.contain(`## ${titleMarker}`);
            // Jcr:title is excluded from textProperties precisely so it is not emitted twice; a
            // doubled title would corrupt every heading in every snapshot.
            const occurrences = response.body.split(titleMarker).length - 1;
            expect(occurrences, 'the title appears once, not twice').to.equal(1);
        });
    });

    it('renders more than the page heading alone', () => {
        cy.login();
        renderMarkdown().then(response => {
            // The signature of the production failure was a render of ~27 characters: the page
            // heading and nothing else. Any real page composes to far more than that.
            expect(response.body.trim().length,
                'a page whose leaf holds content must compose to more than its heading')
                .to.be.greaterThan(60);
        });
    });
});
