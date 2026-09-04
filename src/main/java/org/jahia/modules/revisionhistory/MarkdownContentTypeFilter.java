package org.jahia.modules.revisionhistory;

import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;

/**
 * Serves the {@code markdown} template type as plain text, because that is what it is.
 *
 * <p><b>This closes a stored cross-site-scripting hole, and it is the only thing that does.</b>
 * The markdown views print node content with no escaping, deliberately -- {@code bigText.jsp}
 * emits the rich-text {@code text} property verbatim so that {@link MarkdownNormalizer}, which
 * parses the fetched body with jsoup, can convert the HTML to Markdown in one testable place. What
 * made that a vulnerability was not the printing but the <em>response header</em>: Jahia's
 * {@code Render} servlet sets the content type from {@code RenderContext.getContentType()} and
 * falls back to {@code getDefaultContentType(templateType)}, whose injected map holds only
 * {@code csv, ics, json, html, rss, text, vcf, xml, js}. {@code markdown} is absent, so the
 * fallback returned {@code text/html; charset=UTF-8} and every {@code .markdown} URL was an
 * unescaped HTML document. Measured on 8.2.3.2 before this filter existed: an anonymous
 * {@code GET .../<page>.markdown} answered {@code 200} with {@code Content-Type: text/html}, and a
 * payload placed in a page title came back byte-for-byte intact.
 *
 * <p><b>Why not escape the four print sites instead.</b> Because it would break the feature and
 * still leave the class of bug open. Escaping {@code bigText}'s rich text means
 * {@code <p>Hello</p>} is archived as the literal characters {@code <p>Hello</p>} rather than
 * converted to {@code Hello}, corrupting the record this module exists to keep; the same applies to
 * any {@code crh:textProperties} value holding markup. And escaping fixes four print sites, not the
 * surface: the fourth sink arrived precisely because someone added a raw print to a markdown view
 * later. Declaring the type fixes the surface once, for every view that will ever be added.
 *
 * <p><b>Why a render filter and not a view property.</b> There is no view-level content-type
 * setting in 8.2.3.2 -- the only inputs to the header are the render context and that Spring map,
 * neither of which a module can reach declaratively. {@code RenderContext.setContentType} is
 * public API for exactly this, and the ordering in {@code Render} makes it work: the rendered
 * output is produced first, then {@code getContentType()} is read, then
 * {@code response.setContentType} is called and only then is the body written. A filter running
 * inside the chain therefore still gets to decide. Verified in the 8.2.3.2 bytecode rather than
 * assumed, because a fix that silently does nothing is worse here than no fix -- it closes the
 * advisory without closing the hole.
 *
 * <p><b>Priority must be below the fragment cache, not merely below the final filter.</b>
 * {@code RenderChain.doFilter} stops at the <em>first</em> filter whose {@code prepare} returns
 * non-null, and {@code CacheFilter} (priority 16.5; the legacy {@code AggregateCacheFilter} sits at
 * 16.0) returns the cached body from {@code prepare} on a hit. It applies to this template type: its
 * only skip is {@code json}, and it is on for {@code live}. Version 1.4.7 shipped this filter at 98
 * -- below {@code TemplateScriptFilter}'s 99 but above the cache -- and so it ran only on a cache
 * miss. Measured on 8.2.3.2: the first anonymous request answered {@code text/plain} with
 * {@code nosniff}; the second and third, served from the cache, answered {@code text/html} with the
 * unescaped body intact. The advisory was closed for exactly one request per cache lifetime.
 *
 * <p>Hence {@link #PRIORITY} is below every filter that can end the chain early --
 * {@code MarkedForDeletionFilter} at 10 included, so a deleted page's short response is typed
 * correctly too. Nothing lower ({@code EditModeFilter}, {@code SourceFormatterFilter},
 * {@code ExternalizeHtmlFilter}, {@code StaticAssetsFilter}) returns content from {@code prepare}
 * for a live markdown render.
 *
 * <p>The capture path is unaffected, which was checked rather than hoped: {@code
 * GuestMarkdownFetcher} already sends {@code Accept: text/html, text/plain}, reads the body as raw
 * UTF-8 bytes and never inspects the response type, and jsoup parses markup regardless of the
 * header it arrived under.
 */
@Component(service = RenderFilter.class, immediate = true)
public class MarkdownContentTypeFilter extends AbstractFilter {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownContentTypeFilter.class);

    /**
     * What the markdown views actually emit. Plain text is not parsed as markup by any browser, so
     * a payload stored in content is displayed rather than executed.
     */
    static final String MARKDOWN_CONTENT_TYPE = "text/plain; charset=UTF-8";

    /**
     * Belt and braces against content-type sniffing. A browser that ignores {@code text/plain} and
     * guesses from the bytes would undo this fix, and the bytes can legitimately start with markup
     * because {@code bigText} emits rich text verbatim.
     */
    static final String NOSNIFF_HEADER = "X-Content-Type-Options";
    static final String NOSNIFF_VALUE = "nosniff";

    /**
     * Must stay under the cache filters (16.0 / 16.5), which end the chain on a hit, and under
     * MarkedForDeletionFilter (10), which ends it for a deleted node. See the class comment.
     */
    private static final float PRIORITY = 5f;

    @Activate
    public void start() {
        setPriority(PRIORITY);
        setApplyOnTemplateTypes(RevisionHistoryConstants.MARKDOWN_TEMPLATE_TYPE);
        setDescription("Serves the markdown template type as text/plain, so an unescaped"
                + " node property cannot become script in a visitor's browser");
        logger.debug("Markdown responses will be served as {}", MARKDOWN_CONTENT_TYPE);
    }

    /**
     * Set before the chain runs, not after.
     *
     * <p>{@code prepare} rather than {@code execute} so the decision is already recorded if a view
     * further down throws: an error response for a {@code .markdown} URL must not fall back to
     * being HTML either.
     */
    @Override
    public String prepare(RenderContext renderContext, Resource resource, RenderChain chain) {
        renderContext.setContentType(MARKDOWN_CONTENT_TYPE);
        HttpServletResponse response = renderContext.getResponse();
        if (response != null) {
            // Set on the response too. RenderContext is what Render consults for the header, but
            // the header below is ours to add and has no other route.
            response.setHeader(NOSNIFF_HEADER, NOSNIFF_VALUE);
        }
        return null;
    }
}
