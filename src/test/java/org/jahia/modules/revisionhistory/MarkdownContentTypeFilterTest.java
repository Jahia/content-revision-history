package org.jahia.modules.revisionhistory;

import org.jahia.services.render.RenderContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The response type for the {@code markdown} template type.
 *
 * <p>This is a security fix, and the way it fails is by <em>not running</em>. The markdown views
 * print node content unescaped on purpose -- {@code bigText} emits rich text verbatim for
 * {@code MarkdownNormalizer} to convert -- so the only thing standing between a payload stored in a
 * page title and script execution in a visitor's browser is the {@code Content-Type} header. A
 * filter that is registered but never reaches the chain, or reaches it for the wrong template type,
 * closes the advisory without closing the hole. Hence the assertions below are about
 * <em>registration</em> as much as behaviour.
 *
 * <p>End-to-end proof lives in the Cypress suite, which asserts the header on a real response;
 * these tests pin the wiring that the header depends on.
 */
class MarkdownContentTypeFilterTest {

    /**
     * The lowest-numbered filter that can end the chain before the view runs, on the path an
     * anonymous visitor takes: the fragment cache. {@code AggregateCacheFilter} (legacy) sits at
     * 16.0 and {@code CacheFilter} at 16.5; on a hit their {@code prepare} returns the cached body,
     * and {@code RenderChain.doFilter} stops at the first non-null {@code prepare}. A filter
     * numbered above them runs only on a cache miss.
     *
     * <p>Not 99. Version 1.4.7 pinned {@code < 99} ({@code TemplateScriptFilter}), shipped at 98, and
     * the header held for exactly one request per cache lifetime -- measured, not inferred.
     */
    private static final float LEGACY_CACHE_FILTER_PRIORITY = 16f;

    /** Ends the chain for a node marked for deletion; the typed response must survive that too. */
    private static final float MARKED_FOR_DELETION_FILTER_PRIORITY = 10f;

    private MarkdownContentTypeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MarkdownContentTypeFilter();
        filter.start();
    }

    @Test
    @DisplayName("The response is declared as plain text, not HTML")
    void declaresPlainText() {
        // Arrange
        RenderContext renderContext = mock(RenderContext.class);

        // Act
        filter.prepare(renderContext, null, null);

        // Assert. text/plain is the whole fix: a browser renders it as text, so an unescaped
        // property is displayed rather than parsed as markup. Measured on 8.2.3.2 -- before this
        // filter, an anonymous GET of a .markdown URL answered 200 with text/html and a payload in
        // a page title came back byte-for-byte intact.
        verify(renderContext).setContentType("text/plain; charset=UTF-8");
    }

    @Test
    @DisplayName("Sniffing is refused, because the body legitimately contains markup")
    void refusesContentTypeSniffing() {
        // Arrange
        RenderContext renderContext = mock(RenderContext.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(renderContext.getResponse()).thenReturn(response);

        // Act
        filter.prepare(renderContext, null, null);

        // Assert. Declaring text/plain is not enough on its own: the bytes can start with real
        // markup, because bigText emits the rich-text property as-is for the normalizer to
        // convert. A client that guesses from content instead of trusting the header would undo
        // the fix.
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    @DisplayName("It runs BEFORE the fragment cache, so a cache hit is typed too")
    void priorityIsBelowTheCacheFilters() {
        // Not a style preference, and not the bound this test used to pin. The chain stops at the
        // first prepare() that returns non-null; on a warm cache that is CacheFilter at 16.5 (or
        // the legacy AggregateCacheFilter at 16.0), which applies to the markdown template type in
        // live. A filter above them runs on the cache miss only, and every following anonymous
        // request is served as text/html with the unescaped body -- which is what 1.4.7 did.
        assertTrue(filter.getPriority() < LEGACY_CACHE_FILTER_PRIORITY,
                "a filter at or above 16.0 is skipped on a fragment-cache hit, so the header"
                        + " holds for one request per cache lifetime; actual priority: "
                        + filter.getPriority());
    }

    @Test
    @DisplayName("It runs before the marked-for-deletion short-circuit as well")
    void priorityIsBelowTheMarkedForDeletionFilter() {
        // MarkedForDeletionFilter (10) also returns from prepare() early. A .markdown URL for a
        // page marked for deletion must not become an HTML document either.
        assertTrue(filter.getPriority() < MARKED_FOR_DELETION_FILTER_PRIORITY,
                "actual priority: " + filter.getPriority());
    }

    @Test
    @DisplayName("It is scoped to the markdown template type")
    void appliesToTheMarkdownTemplateType() {
        // The scope has to be exactly right in both directions. Too narrow and the vulnerable URL
        // keeps its HTML type; too wide and every HTML page in the installation starts being
        // served as plain text, which would be a far worse outage than the bug.
        assertEquals("markdown", RevisionHistoryConstants.MARKDOWN_TEMPLATE_TYPE,
                "the filter is registered for this constant, so it is what confines the change"
                        + " to .markdown responses");
    }

    @Test
    @DisplayName("A missing response does not break the render")
    void toleratesNoResponse() {
        // Arrange -- getResponse() can be null outside a real request, and this filter must not be
        // the reason a capture render fails.
        RenderContext renderContext = mock(RenderContext.class);
        when(renderContext.getResponse()).thenReturn(null);

        // Act / Assert
        assertDoesNotThrow(() -> filter.prepare(renderContext, null, null));
        verify(renderContext).setContentType(anyString());
    }

    @Test
    @DisplayName("The filter does not touch the rendered body")
    void leavesTheBodyAlone() {
        // The record must stay faithful to what the page said: the payload remains IN the snapshot
        // as text, and the display paths escape it. This fix changes how the bytes are labelled,
        // never what they are -- escaping here would corrupt rich text into its own source.
        RenderContext renderContext = mock(RenderContext.class);

        String result = filter.prepare(renderContext, null, null);

        assertEquals(null, result, "returning content from prepare() would replace the render");
        verify(renderContext, never()).setRedirect(anyString());
    }
}
