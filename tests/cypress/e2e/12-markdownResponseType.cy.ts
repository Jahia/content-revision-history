import {addNode, deleteNode, publishAndWaitJobEnding} from '@jahia/cypress';

/**
 * The `.markdown` URL must not be an HTML document. This is a security regression test.
 *
 * GHSA-4hvq-2x8x-49w2, stored XSS, fixed in 1.4.7. The markdown views print RICH TEXT with no
 * escaping, and that is deliberate: `bigText.jsp` emits the rich-text `text` property verbatim so
 * `MarkdownNormalizer` can convert the HTML to Markdown in one testable place. (Plain strings and
 * titles are escaped since issue #18 -- they are text, and a literal `<style>` in one swallowed the
 * rest of the page -- but that is a fidelity rule, not the security fix.) What made it a
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
 *
 * WHAT CHANGED IN 1.4.13, and why half of this file now logs in. GHSA-q67w-prc3-ch5h #3: opting a
 * page in was enough to hand an ANONYMOUS caller every text-bearing string property beneath it,
 * including ones no template displays. The endpoint is now gated to callers entitled to that dump
 * -- the module's own capture, which presents an in-memory token, or a human holding
 * `siteAdminContentRevisionHistory` on the site. So there is no longer an anonymous `.markdown`
 * response to type, and the first test below asserts the stronger property: nothing comes back at
 * all. The content-type and nosniff assertions moved to an ENTITLED caller, which is where they
 * still bite -- an operator inspecting a capture in a browser is exactly who could still be handed
 * markup to parse, and the 1.4.7 warm-cache regression is still reachable on that path.
 *
 * These two paths cannot mask each other: the fragment cache keys per user, so an operator's warm
 * 200 is a different entry from an anonymous caller's 404.
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
     * The same URL, fetched by a caller the gate admits. `cy.login()` is root, which holds every
     * permission including `siteAdminContentRevisionHistory`, so this is the operator path.
     *
     * Login is done per test rather than once in `before`, so each test states the identity it
     * depends on instead of inheriting one -- the mistake that made an earlier permission spec in
     * this suite pass while secretly running as root.
     */
    const fetchMarkdownAsOperator = (path: string) => {
        cy.login();
        return fetchMarkdown(path);
    };

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

    it('#3: an anonymous caller is not served at all, however many times it asks', () => {
        // The measured exposure, and the reason the rest of this file changed. An opted-in page
        // handed an unauthenticated GET every text-bearing string property beneath it, including
        // ones no template displays -- an independent review pulled internal jmix:orderedList
        // ordering fields out of a 1.4.12 response, byte-identical to 1.4.10's.
        //
        // Asserted THREE TIMES on a byte-identical URL for the reason the warm-cache test below
        // exists: the gate runs at priority 5, before CacheFilter (16.5) can answer from prepare().
        // A gate above the cache would refuse the miss and then serve every hit -- exactly how
        // 1.4.7's content-type fix held for one request per cache lifetime.
        //
        // And the body is checked, not only the status: a 404 that still carries the render would
        // be a leak with a misleading status line.
        cy.clearCookies();
        for (let i = 1; i <= 3; i++) {
            fetchMarkdown(revisionedPagePath).then(response => {
                expect(response.status, `anonymous request ${i} of 3 must be refused`).to.eq(404);
                expect(String(response.body ?? ''), `request ${i}: the refusal must carry no content`)
                    .to.not.contain(marker);
                expect(String(response.body ?? ''), `request ${i}: nor the payload`)
                    .to.not.contain(payload);
            });
        }
    });

    it('declares text/plain to an entitled caller, so a stored payload is displayed not parsed', () => {
        fetchMarkdownAsOperator(revisionedPagePath).then(response => {
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
        fetchMarkdownAsOperator(revisionedPagePath).then(response => {
            expect(
                String(response.headers['x-content-type-options'] ?? '').toLowerCase(),
                'nosniff is what stops a guessing client re-opening the hole'
            ).to.eq('nosniff');
        });
    });

    it('still returns the content faithfully, because the record must say what the page said', () => {
        // The header fix changes how the bytes are LABELLED, never what they mean. The payload sits
        // in the page TITLE, which is plain text, so the view emits it HTML-escaped (issue #18): the
        // parser reads it back as the literal text `<img ...>` and that is what the snapshot holds.
        // Rich text (bigText) is still emitted raw so it can be converted rather than archived as
        // its own source -- that path is covered by the capture specs.
        const escapedPayload = payload.replace(/</g, '&lt;').replace(/>/g, '&gt;');
        fetchMarkdownAsOperator(revisionedPagePath).then(response => {
            expect(response.body, 'the title text must survive').to.contain(marker);
            expect(response.body, 'a plain-text title is escaped, not parsed').to.contain(escapedPayload);
            expect(response.body, 'the raw tag must not appear: it would be parsed as markup').to.not.contain(
                payload
            );
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
        cy.login();
        fetchMarkdownRepeatedly(revisionedPagePath, 3);
    });

    it('#46: a page that never opted in is not served at all -- 404, no property leak', () => {
        // Anonymous, so since 1.4.13 this is refused for TWO independent reasons: the page never
        // opted in (#46) and the caller is not entitled to a dump (#3). Kept anonymous rather than
        // logged in so it kills the #46 gate specifically -- an operator would pass the caller gate
        // and still have to be refused by the mixin gate, which is what the assertion after this
        // one covers.
        // The markdown views are registered on the core jnt:page/jnt:content types, so without the
        // opted-in gate the .markdown URL exists for EVERY page and hands an anonymous visitor every
        // text-bearing property -- including ones the HTML view never shows. crh-mdtype-plain has the
        // payload in its title and did NOT opt in, so its .markdown must 404 and its body must carry
        // nothing. Checked twice: the gate runs before the cache, so a warm request 404s too.
        fetchMarkdown(plainPagePath).then(first => {
            expect(first.status, 'a non-opted-in page must not serve .markdown').to.eq(404);
            expect(first.body ?? '', 'the refusal must not leak the title or its payload')
                .to.not.contain(marker);
        });
        fetchMarkdown(plainPagePath).then(second => {
            expect(second.status, 'and still 404 on the cached second request').to.eq(404);
        });
    });

    it('the two gates are independent: an entitled caller still cannot dump a non-opted-in page', () => {
        // Otherwise "opted in" and "entitled" would be one gate with two names, and revoking one
        // would quietly revoke the other. An operator passes the caller gate (#3) and must still be
        // refused by the mixin gate (#46), because the module's promise is that installing it
        // changes nothing for a page until that page opts in -- and the markdown views are
        // registered on the CORE jnt:page / jnt:content types, so the surface otherwise exists for
        // every page of every site.
        fetchMarkdownAsOperator(plainPagePath).then(response => {
            expect(response.status, 'the mixin gate must refuse an entitled caller too').to.eq(404);
            expect(String(response.body ?? ''), 'and leak nothing while doing it').to.not.contain(marker);
        });
    });
});
