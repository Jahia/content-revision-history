package org.jahia.modules.revisionhistory;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.RenderService;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Renders the current node as Markdown using the {@code markdown} template type.
 *
 * <p>Implemented as a Jahia {@link Action} rather than a servlet on purpose: an Action is
 * handed a fully-built {@link RenderContext} by the render chain. Jahia ships no mock
 * HttpServletRequest, and {@code RenderContext} has no no-request constructor, so this is
 * the only way to drive {@link RenderService} without hand-stubbing the servlet API.
 */
public class MarkdownSnapshotAction extends Action {

    /** Template type name. Must match the {@code <nodetype>/markdown/} view directories. */
    public static final String TEMPLATE_TYPE = "markdown";

    public MarkdownSnapshotAction() {
        setName("crhMarkdown");
    }

    @Override
    public ActionResult doExecute(HttpServletRequest request, RenderContext renderContext,
                                  Resource resource, JCRSessionWrapper session,
                                  Map<String, List<String>> parameters, URLResolver urlResolver)
            throws Exception {
        JCRNodeWrapper node = resource.getNode();
        Resource markdownResource =
                new Resource(node, TEMPLATE_TYPE, null, Resource.CONFIGURATION_PAGE);
        String markdown = RenderService.getInstance().render(markdownResource, renderContext);

        JSONObject json = new JSONObject();
        json.put("path", node.getPath());
        json.put("markdown", markdown);
        return new ActionResult(HttpServletResponse.SC_OK, null, json);
    }
}
