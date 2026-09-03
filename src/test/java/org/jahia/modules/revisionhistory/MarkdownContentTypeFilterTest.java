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
     * TemplateScriptFilter's priority. It is final: nothing ordered after it is ever chained, so a
     * filter at or above this number silently never executes.
     */
    private static final float TEMPLATE_SCRIPT_FILTER_PRIORITY = 99f;

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
    @DisplayName("It runs BELOW the filter that terminates the chain")
    void priorityIsBelowTheFinalFilter() {
        // Not a style preference. TemplateScriptFilter sits at 99.0 and is final, so a filter
        // numbered at or above it is registered, is reported as active, and never executes -- and
        // the symptom is simply that the header stays text/html. This repository has already been
        // caught by exactly that: an earlier filter used priority 999 and its execute() was never
        // called.
        assertTrue(filter.getPriority() < TEMPLATE_SCRIPT_FILTER_PRIORITY,
                "a filter at or above 99.0 never runs, so the fix would silently not apply;"
                        + " actual priority: " + filter.getPriority());
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
