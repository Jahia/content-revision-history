import {addNode, deleteNode, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * The `.markdown` URL must not be an HTML document. This is a security regression test.
 *
 * GHSA-4hvq-2x8x-49w2, stored XSS, fixed in 1.4.7. The markdown views print node content with no
 * escaping, and that is deliberate: `bigText.jsp` emits the rich-text `text` property verbatim so
 * `MarkdownNormalizer` can convert the HTML to Markdown in one testable place. What made it a
 * vulnerability was the response HEADER. Jahia's Render servlet falls back to
 * `getDefaultContentType(templateType)` for a type absent from its injected map -- which holds only
 * csv, ics, json, html, rss, text, vcf, xml, js -- so `markdown` fell through to
 * `text/html; charset=UTF-8`, and every `.markdown` URL was an unescaped HTML document reachable
 * anonymously.
 *
 * Measured on 8.2.3.2 against 1.4.6: an anonymous GET answered 200 with `Content-Type: text/html`
 * and `<img src=x onerror=...>` placed in a page title came back byte-for-byte intact.
 *
 * Why the tests assert the HEADER and not the absence of the payload: escaping the payload would be
 * the wrong fix. The snapshot is a legal record of what the page said, so the markup must survive
 * INTO it as text -- and escaping `bigText`'s rich text would archive `<p>Hello</p>` as its own
 * source instead of converting it to `Hello`. The bytes are supposed to contain markup. What must
 * never happen is a browser being told to parse them as a document.
 */
describe('the markdown template type is served as plain text, not HTML', () => {
    const siteKey = 'digitall';
    const language = 'en';
    const revisionedPagePath = `/sites/${siteKey}/home/crh-mdtype`;
    const plainPagePath = `/sites/${siteKey}/home/crh-mdtype-plain`;

    /** The classic probe. Harmless as text, executable if the response is ever HTML again. */
    const payload = '<img src=x onerror=window.__crhxss=1>';
    const marker = 'MDTYPE-PROBE-4c81';

    /** Anonymous on purpose: the vulnerable URL was reachable with no session at all. */
    const fetchMarkdown = (path: string) =>
        cy.request<string>({
            url: `/cms/render/live/${language}${path}.markdown`,
            qs: {v: `${Date.now()}`},
            failOnStatusCode: false
        });

    const page = (name: string, path: string, mixins: string[]) => {
        deleteNode(path).then(null, () => undefined);
        addNode({
            parentPathOrId: `/sites/${siteKey}/home`,
            name,
            primaryNodeType: 'jnt:page',
            mixins,
            properties: [
                // The payload goes in the TITLE, which is the sink the advisory was filed against
                // and the one an editor reaches most easily.
                {name: 'jcr:title', value: `${marker} ${payload}`, language},
                {name: 'j:templateName', value: 'simple'}
            ],
            children: [{name: 'area-main', primaryNodeType: 'jnt:contentList'}]
        });
        publishAndWaitJobEnding(path, [language]);
    };

    before(() => {
        cy.login();
        page('crh-mdtype', revisionedPagePath, ['jmix:publiclyRevisioned']);
        // No mixin, no revision history, nothing opted in. The markdown views are registered for
        // jnt:page / jnt:content / jnt:bigText -- CORE types -- so deploying this module adds the
        // render surface to every page in the installation. A site that never uses the feature was
        // affected too, which is the part of the advisory most easily missed.
        page('crh-mdtype-plain', plainPagePath, []);
        cy.logout();
    });

    after(() => {
        cy.login();
        deleteNode(revisionedPagePath).then(null, () => undefined);
        deleteNode(revisionedPagePath, 'LIVE').then(null, () => undefined);
        deleteNode(plainPagePath).then(null, () => undefined);
        deleteNode(plainPagePath, 'LIVE').then(null, () => undefined);
    });

    it('declares text/plain, so a stored payload is displayed and never parsed', () => {
        fetchMarkdown(revisionedPagePath).then(response => {
            expect(response.status).to.eq(200);

            const contentType = String(response.headers['content-type'] ?? '');

            expect(
                contentType.toLowerCase(),
                'the exact regression: markdown fell through Jahia\'s content-type map to ' +
                    'text/html, which turned every unescaped property into markup a browser ' +
                    'would execute. Actual: ' + contentType
            ).to.contain('text/plain');
            expect(contentType.toLowerCase(), 'must not be served as a document').to.not.contain(
                'text/html'
            );
        });
    });

    it('refuses content-type sniffing, because the body really does contain markup', () => {
        // Declaring text/plain is necessary but not sufficient on its own. bigText emits rich text
        // as-is for the normalizer, so a response can legitimately begin with markup, and a client
        // that guesses from content rather than trusting the header would undo the fix.
        fetchMarkdown(revisionedPagePath).then(response => {
            expect(
                String(response.headers['x-content-type-options'] ?? '').toLowerCase(),
                'nosniff is what stops a guessing client re-opening the hole'
            ).to.eq('nosniff');
        });
    });

    it('still returns the content verbatim, because the record must stay faithful', () => {
        // The fix must change how the bytes are LABELLED, never what they are. If a future change
        // "fixes" this by escaping instead, this test fails -- and it should: escaping corrupts the
        // archive, which is the one thing this module exists to keep.
        fetchMarkdown(revisionedPagePath).then(response => {
            expect(response.body, 'the title text must survive').to.contain(marker);
            expect(
                response.body,
                'the markup must reach the snapshot as text; escaping it here would archive rich ' +
                    'text as its own source rather than converting it'
            ).to.contain(payload);
        });
    });

    it('applies to a page that never opted into revision history', () => {
        // The views are registered for core node types, so the render surface exists site-wide.
        // Scoping the fix to revisioned pages only would leave every other page exposed.
        fetchMarkdown(plainPagePath).then(response => {
            expect(response.status).to.eq(200);
            expect(
                String(response.headers['content-type'] ?? '').toLowerCase(),
                'an ordinary page is reachable at .markdown too, and was equally exposed'
            ).to.contain('text/plain');
        });
    });
});
