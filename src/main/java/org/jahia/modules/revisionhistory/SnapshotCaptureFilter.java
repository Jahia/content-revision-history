package org.jahia.modules.revisionhistory;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.RenderService;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

/**
 * Captures a Markdown snapshot of a page the first time it is rendered in live after
 * publication.
 *
 * <p>Why a render filter rather than a publication listener: Jahia ships no mock
 * {@code HttpServletRequest} and {@code RenderContext} has no off-request constructor, so
 * {@link RenderService} cannot be driven outside a request. A render filter is <em>handed</em>
 * a real {@code RenderContext}, which removes that problem. It also keeps publication latency
 * untouched, and the first live render is precisely the first moment a change becomes publicly
 * visible, so nothing visible can go unrecorded.
 *
 * <p>Two non-obvious constraints, both learned the hard way:
 * <ul>
 *   <li>Priority must be below {@code TemplateScriptFilter} (99.0), which is <em>final</em> --
 *       later filters are never chained at all.</li>
 *   <li>The page is the render context's <em>main resource</em>, never the filtered
 *       {@code Resource}: the outermost resource in a page render is the template node
 *       ({@code /modules/<set>/templates/base}). Guarding on
 *       {@code resource.getNode() == mainResource} therefore never matches.</li>
 * </ul>
 *
 * <p>Never changes the output: returns {@code previousOut} untouched.
 */
public class SnapshotCaptureFilter extends AbstractFilter {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotCaptureFilter.class);

    static final String MIXIN = "jmix:publiclyRevisioned";
    static final String MARKDOWN_TEMPLATE_TYPE = "markdown";
    /** Once-per-request guard: the chain calls this filter many times per page. */
    private static final String DONE_ATTRIBUTE = SnapshotCaptureFilter.class.getName() + ".done";
    /**
     * Value of {@code AggregateFilter.SKIP_AGGREGATION} / {@code AggregateCacheFilter
     * .SKIP_AGGREGATION}. Inlined deliberately: both holder classes are deprecated and marked
     * for removal in 8.2.3.2, and there is no non-deprecated constant to reference.
     */
    private static final String SKIP_AGGREGATION = "aggregateFilter.skip";

    private final RevisionSnapshotService snapshotService = new RevisionSnapshotService();

    @Override
    public String execute(String previousOut, RenderContext renderContext, Resource resource,
                          RenderChain chain) {
        try {
            JCRNodeWrapper page = resolvePageToCapture(renderContext, resource);
            if (page != null) {
                capture(page, renderContext, resource);
            }
        } catch (Exception e) {
            // Never let snapshot capture break page delivery -- but never swallow it either.
            logger.error("Failed to capture revision snapshot for {}", safePath(resource), e);
        }
        return previousOut;
    }

    /** @return the page to snapshot, or null when this invocation should be ignored */
    private JCRNodeWrapper resolvePageToCapture(RenderContext renderContext, Resource resource)
            throws Exception {
        // Recursion guard: rendering the markdown variant re-enters the chain.
        if (MARKDOWN_TEMPLATE_TYPE.equals(resource.getTemplateType())) {
            return null;
        }
        if (!Constants.LIVE_WORKSPACE.equals(resource.getWorkspace())) {
            return null;
        }
        Resource main = renderContext.getMainResource();
        if (main == null) {
            return null;
        }
        JCRNodeWrapper page = main.getNode();
        if (!page.isNodeType(MIXIN)) {
            return null;
        }
        if (renderContext.getRequest() == null
                || renderContext.getRequest().getAttribute(DONE_ATTRIBUTE) != null) {
            return null;
        }
        renderContext.getRequest().setAttribute(DONE_ATTRIBUTE, Boolean.TRUE);
        return page;
    }

    private void capture(JCRNodeWrapper page, RenderContext renderContext, Resource resource)
            throws Exception {
        String language = renderContext.getMainResource().getLocale().toString();
        String siteKey = page.getResolveSite().getSiteKey();

        // CONFIGURATION_MODULE, not CONFIGURATION_PAGE: PAGE makes Jahia resolve a page
        // *template* in the markdown template type (there is none, giving
        // "Unable to resolve script: default"), whereas MODULE resolves the node's *view* --
        // which is what jnt_page/markdown/page.jsp is.
        Resource markdownResource = new Resource(page, MARKDOWN_TEMPLATE_TYPE, null,
                Resource.CONFIGURATION_MODULE);
        String raw = renderSkippingAggregation(markdownResource, renderContext);
        String markdown = MarkdownNormalizer.normalize(raw);

        if (markdown.trim().isEmpty()) {
            // An empty snapshot would be silent content loss in an authoritative record.
            logger.warn("Markdown render for {} [{}] produced no content; snapshot not stored",
                    page.getPath(), language);
            return;
        }

        boolean created = snapshotService.captureIfChanged(siteKey, page.getIdentifier(),
                language, markdown, null);
        logger.info("Revision snapshot for {} [{}]: {}", page.getPath(), language,
                created ? "stored" : "unchanged, deduped");
    }

    /**
     * Renders with aggregation bypassed.
     *
     * <p>Without this, a render nested inside an in-flight render chain returns an
     * {@code <!-- cache:include ... -->} placeholder rather than content -- the aggregation
     * filter defers sub-renders and substitutes markers that are resolved later. The snapshot
     * would then normalise to an empty string. The attribute is restored so the outer page
     * render is unaffected.
     */
    private String renderSkippingAggregation(Resource resource, RenderContext renderContext)
            throws Exception {
        HttpServletRequest request = renderContext.getRequest();
        Object previous = request.getAttribute(SKIP_AGGREGATION);
        request.setAttribute(SKIP_AGGREGATION, Boolean.TRUE);
        try {
            return RenderService.getInstance().render(resource, renderContext);
        } finally {
            if (previous == null) {
                request.removeAttribute(SKIP_AGGREGATION);
            } else {
                request.setAttribute(SKIP_AGGREGATION, previous);
            }
        }
    }

    private String safePath(Resource resource) {
        try {
            return resource.getNode().getPath();
        } catch (Exception e) {
            return "<unknown>";
        }
    }
}
