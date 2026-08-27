package org.jahia.modules.revisionhistory;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.RenderService;
import org.jahia.services.render.Resource;
import org.jahia.services.render.filter.AbstractFilter;
import org.jahia.services.render.filter.RenderChain;
import org.jahia.services.render.filter.RenderFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures a Markdown snapshot of a page the first time it is rendered in live after being
 * published.
 *
 * <p>Why a render filter rather than a publication listener: an Action cannot be registered on
 * this platform (Jahia 8.2.3.2 populates {@code TemplatePackageRegistry.getActions()} only from
 * module Spring contexts, and runtime-installed bundles do not receive one), and Jahia ships no
 * mock {@code HttpServletRequest}, so {@code RenderContext} cannot be built off-request. A
 * render filter is <em>handed</em> a real {@code RenderContext} by Jahia, which removes the
 * problem entirely.
 *
 * <p>Consequences, all acceptable:
 * <ul>
 *   <li>Publication latency is untouched -- nothing runs on the publish thread.</li>
 *   <li>The first live render is precisely the first moment the change is publicly visible,
 *       so no visible state can go unrecorded.</li>
 *   <li>A page nobody ever views gets no snapshot, which records nothing that was never
 *       public. The next view captures it.</li>
 * </ul>
 *
 * <p>Runs late and never changes the output: it returns {@code previousOut} untouched.
 */
@Component(service = RenderFilter.class, immediate = true)
public class SnapshotCaptureFilter extends AbstractFilter {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotCaptureFilter.class);

    private static final String MIXIN = "jmix:publiclyRevisioned";
    private static final String MARKDOWN_TEMPLATE_TYPE = "markdown";
    /** Late: let the page finish rendering before we do side work. */
    private static final float PRIORITY = 999f;

    private final RevisionSnapshotService snapshotService = new RevisionSnapshotService();

    @Activate
    public void activate() {
        setPriority(PRIORITY);
        setApplyOnNodeTypes(MIXIN);
        setApplyOnConfigurations(Resource.CONFIGURATION_PAGE);
        setApplyOnModes(Constants.LIVE_WORKSPACE);
        setDescription("Captures a Markdown revision snapshot of publicly revisioned pages");
    }

    @Override
    public String execute(String previousOut, RenderContext renderContext, Resource resource,
                          RenderChain chain) {
        try {
            if (shouldSkip(renderContext, resource)) {
                return previousOut;
            }
            capture(renderContext, resource);
        } catch (Exception e) {
            // Never let snapshot capture break page delivery -- but never swallow it silently.
            logger.error("Failed to capture revision snapshot for {}",
                    safePath(resource), e);
        }
        return previousOut;
    }

    /**
     * Guards, in order of cheapness. The template-type check is the recursion guard: rendering
     * the markdown variant re-enters the chain, and without it we would capture forever.
     */
    private boolean shouldSkip(RenderContext renderContext, Resource resource) {
        if (MARKDOWN_TEMPLATE_TYPE.equals(resource.getTemplateType())) {
            return true;
        }
        // Only the page being requested, not every aggregated fragment inside it.
        return !resource.getNode().equals(renderContext.getMainResource().getNode());
    }

    private void capture(RenderContext renderContext, Resource resource) throws Exception {
        JCRNodeWrapper page = resource.getNode();
        String language = resource.getLocale().toString();
        String siteKey = page.getResolveSite().getSiteKey();

        Resource markdownResource = new Resource(page, MARKDOWN_TEMPLATE_TYPE, null,
                Resource.CONFIGURATION_PAGE);
        String raw = RenderService.getInstance().render(markdownResource, renderContext);
        String markdown = MarkdownNormalizer.normalize(raw);

        if (markdown.isEmpty()) {
            // An empty snapshot would be silent content loss in an authoritative record.
            logger.warn("Markdown render for {} [{}] produced no content; snapshot not stored",
                    page.getPath(), language);
            return;
        }

        boolean created = snapshotService.captureIfChanged(siteKey, page.getIdentifier(),
                language, markdown, null);
        if (created) {
            logger.info("Captured revision snapshot for {} [{}]", page.getPath(), language);
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
