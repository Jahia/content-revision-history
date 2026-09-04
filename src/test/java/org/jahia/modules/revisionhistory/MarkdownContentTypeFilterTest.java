package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
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
 * The response type for the {@code markdown} template type, and the gate that decides whether the
 * endpoint answers at all.
 *
 * <p>This is a security fix on two counts, and both fail by <em>not running</em>. The markdown
 * views print node content unescaped on purpose -- {@code bigText} emits rich text verbatim for
 * {@code MarkdownNormalizer} to convert -- so the {@code Content-Type} header is the only thing
 * between a payload stored in a page title and script in a visitor's browser; and the views are
 * registered on the core {@code jnt:page}/{@code jnt:content} types, so without a gate the
 * {@code .markdown} URL exists for every page and dumps text-bearing properties to anyone. The
 * assertions below are about <em>registration and reach</em> as much as behaviour.
 *
 * <p>End-to-end proof lives in the Cypress suite, which asserts the header and the 404 on real
 * responses; these tests pin the wiring they depend on.
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

    // ------------------------------------------------------------------ fixtures

    /** A node at the given path with the given mixin/page flags, as RevisionedAncestor.of reads it. */
    private static JCRNodeWrapper node(String path, boolean revisioned, boolean page)
            throws RepositoryException {
        JCRNodeWrapper n = mock(JCRNodeWrapper.class);
        when(n.getPath()).thenReturn(path);
        when(n.isNodeType(RevisionHistoryConstants.REVISIONED_MIXIN)).thenReturn(revisioned);
        when(n.isNodeType(RevisionHistoryConstants.PAGE_TYPE)).thenReturn(page);
        return n;
    }

    /** A render context whose main resource is the given node (or absent when null). */
    private static RenderContext contextWithMain(JCRNodeWrapper mainNode) throws RepositoryException {
        RenderContext rc = mock(RenderContext.class);
        if (mainNode != null) {
            Resource main = mock(Resource.class);
            when(main.getNode()).thenReturn(mainNode);
            when(rc.getMainResource()).thenReturn(main);
        }
        return rc;
    }

    /** The opted-in case: main resource is a revisioned page. */
    private static RenderContext revisionedContext() throws RepositoryException {
        return contextWithMain(node("/sites/x/home/p", true, true));
    }

    // ------------------------------------------------------------------ the typed response (opted-in)

    @Test
    @DisplayName("An opted-in page's response is declared as plain text, not HTML")
    void declaresPlainText() throws RepositoryException {
        RenderContext renderContext = revisionedContext();

        filter.prepare(renderContext, null, null);

        // text/plain is the whole fix: a browser renders it as text, so an unescaped property is
        // displayed rather than parsed as markup. Measured on 8.2.3.2 -- before this filter, an
        // anonymous GET of a .markdown URL answered 200 text/html with a payload in a page title
        // intact.
        verify(renderContext).setContentType("text/plain; charset=UTF-8");
    }

    @Test
    @DisplayName("Sniffing is refused, because the body legitimately contains markup")
    void refusesContentTypeSniffing() throws RepositoryException {
        RenderContext renderContext = revisionedContext();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(renderContext.getResponse()).thenReturn(response);

        filter.prepare(renderContext, null, null);

        // Declaring text/plain is not enough on its own: the bytes can start with real markup,
        // because bigText emits the rich-text property as-is for the normalizer to convert. A
        // client that guesses from content instead of trusting the header would undo the fix.
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    @DisplayName("A missing response does not break the render of an opted-in page")
    void toleratesNoResponse() throws RepositoryException {
        // getResponse() can be null outside a real request, and this filter must not be the reason
        // a capture render fails.
        RenderContext renderContext = revisionedContext();
        when(renderContext.getResponse()).thenReturn(null);

        assertDoesNotThrow(() -> filter.prepare(renderContext, null, null));
        verify(renderContext).setContentType(anyString());
    }

    @Test
    @DisplayName("The filter does not touch the rendered body of an opted-in page")
    void leavesTheBodyAlone() throws RepositoryException {
        // The record must stay faithful: the payload remains IN the snapshot as text, and the
        // display paths escape it. This fix changes how the bytes are labelled, never what they are.
        RenderContext renderContext = revisionedContext();

        String result = filter.prepare(renderContext, null, null);

        assertEquals(null, result, "returning content from prepare() would replace the render");
        verify(renderContext, never()).setRedirect(anyString());
    }

    @Test
    @DisplayName("A revisioned content node (not a page) is served too")
    void servesARevisionedContentNode() throws RepositoryException {
        // The 1.4.3 feature: a jmix:publiclyRevisioned block outside a page owns its own history and
        // its own .markdown, so the gate must pass it even though it is not a jnt:page.
        RenderContext renderContext = contextWithMain(node("/sites/x/contents/block", true, false));

        String result = filter.prepare(renderContext, null, null);

        assertEquals(null, result, "an opted-in content node must serve, not 404");
        verify(renderContext).setContentType("text/plain; charset=UTF-8");
    }

    // ------------------------------------------------------------------ the gate (#46)

    @Test
    @DisplayName("#46: a page that did not opt in gets 404, and no view runs")
    void refusesAPageThatDidNotOptIn() throws RepositoryException {
        // A plain page: not revisioned, so RevisionedAncestor.of stops at the page and returns null.
        RenderContext renderContext = contextWithMain(node("/sites/x/home/plain", false, true));
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(renderContext.getResponse()).thenReturn(response);

        String result = filter.prepare(renderContext, null, null);

        assertEquals("", result, "a non-null return ends the chain before any view emits a property");
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        // The content type is never set: the request does not resolve to a markdown document at all.
        verify(renderContext, never()).setContentType(anyString());
    }

    @Test
    @DisplayName("#46: an absent main resource is refused, not served")
    void refusesWhenTheMainResourceIsAbsent() throws RepositoryException {
        RenderContext renderContext = contextWithMain(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(renderContext.getResponse()).thenReturn(response);

        String result = filter.prepare(renderContext, null, null);

        assertEquals("", result);
        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("#46: the refusal does not throw when there is no response to mark")
    void refusalToleratesNoResponse() throws RepositoryException {
        RenderContext renderContext = contextWithMain(node("/sites/x/home/plain", false, true));
        when(renderContext.getResponse()).thenReturn(null);

        assertDoesNotThrow(() -> assertEquals("", filter.prepare(renderContext, null, null)));
    }

    // ------------------------------------------------------------------ registration

    @Test
    @DisplayName("It runs BEFORE the fragment cache, so a cache hit is typed too")
    void priorityIsBelowTheCacheFilters() {
        // Not a style preference, and not the bound this test used to pin. The chain stops at the
        // first prepare() that returns non-null; on a warm cache that is CacheFilter at 16.5 (or the
        // legacy AggregateCacheFilter at 16.0). A filter above them runs on the cache miss only, and
        // every following anonymous request is served as text/html with the unescaped body -- 1.4.7.
        assertTrue(filter.getPriority() < LEGACY_CACHE_FILTER_PRIORITY,
                "a filter at or above 16.0 is skipped on a fragment-cache hit; actual priority: "
                        + filter.getPriority());
    }

    @Test
    @DisplayName("It runs before the marked-for-deletion short-circuit as well")
    void priorityIsBelowTheMarkedForDeletionFilter() {
        assertTrue(filter.getPriority() < MARKED_FOR_DELETION_FILTER_PRIORITY,
                "actual priority: " + filter.getPriority());
    }

    @Test
    @DisplayName("It is scoped to the markdown template type")
    void appliesToTheMarkdownTemplateType() {
        assertEquals("markdown", RevisionHistoryConstants.MARKDOWN_TEMPLATE_TYPE,
                "the filter is registered for this constant, so it is what confines the change"
                        + " to .markdown responses");
    }
}
