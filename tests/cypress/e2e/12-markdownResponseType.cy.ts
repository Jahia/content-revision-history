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

    /**
     * Anonymous on purpose: the vulnerable URL was reachable with no session at all.
     *
     * No cache-busting query string, deliberately. For an anonymous request the fragment-cache
     * key does not include the query string, so `?v=` never varied anything -- and the request a
     * browser actually sends has no such parameter. The URL must be byte-identical across calls so
     * the second call really is a cache hit.
     */
    const markdownUrl = (path: string) => `/cms/render/live/${language}${path}.markdown`;

    const fetchMarkdown = (path: string) =>
        cy.request<string>({url: markdownUrl(path), failOnStatusCode: false});

    /**
     * The properties under test, on ONE response. Used for every request in a sequence, because the
     * regression this file guards is per-request: 1.4.7 passed on the first request (cache miss)
     * and failed on every one that followed (cache hit).
     */
    const expectPlainText = (response: Cypress.Response<string>, which: string) => {
        expect(response.status, which).to.eq(200);
        const contentType = String(response.headers['content-type'] ?? '').toLowerCase();
        expect(contentType, `${which}: served as a document. Actual: ${contentType}`).to.contain(
            'text/plain'
        );
        expect(contentType, `${which}: must not be text/html`).to.not.contain('text/html');
        expect(
            String(response.headers['x-content-type-options'] ?? '').toLowerCase(),
            `${which}: nosniff missing`
        ).to.eq('nosniff');
    };

    /** Same URL, N times, strictly sequential, asserting every response, not just the first. */
    const fetchMarkdownRepeatedly = (path: string, times: number, index = 1): Cypress.Chainable<unknown> =>
        fetchMarkdown(path).then(response => {
            expectPlainText(response, `request ${index} of ${times}`);
            if (index < times) {
                return fetchMarkdownRepeatedly(path, times, index + 1);
            }

            return undefined;
        });

    /**
     * Publication flushes the fragment cache asynchronously (a rule background action), so a request
     * fired immediately after `publishAndWaitJobEnding` may find the cache emptied between two calls
     * and every call a miss -- which is how the first version of this spec passed against a filter
     * that only worked on a miss. Let the flush settle before measuring.
     */
    const cacheFlushSettleMs = 3000;

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

    it('keeps the header on a WARM fragment cache, not only on the first request', () => {
        // The regression 1.4.7 shipped. Jahia's CacheFilter (priority 16.5) returns the cached body
        // from prepare() and the render chain stops at the first non-null prepare(), so a filter
        // numbered above it runs on the cache miss only. Measured on 8.2.3.2 with 1.4.7: request 1
        // text/plain + nosniff, requests 2 and 3 text/html with no nosniff. The four single fetches
        // in this file were not positioned to see it -- so this one asserts every response of a
        // sequence, on a URL identical byte-for-byte to what a browser sends.
        // There is no observable signal for "the asynchronous flush has run" that an anonymous
        // client can read, so this is the one wait in the suite that cannot be replaced by a
        // condition. Without it the test can pass for the wrong reason (every request a miss).
        // eslint-disable-next-line cypress/no-unnecessary-waiting -- see cacheFlushSettleMs
        cy.wait(cacheFlushSettleMs);
        fetchMarkdownRepeatedly(revisionedPagePath, 3);
    });

    it('applies to a page that never opted into revision history', () => {
        // The views are registered for core node types, so the render surface exists site-wide.
        // Scoping the fix to revisioned pages only would leave every other page exposed.
        // Repeated for the same reason as above: an ordinary page is cached like any other.
        // eslint-disable-next-line cypress/no-unnecessary-waiting -- see cacheFlushSettleMs
        cy.wait(cacheFlushSettleMs);
        fetchMarkdownRepeatedly(plainPagePath, 2);
    });
});
