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

/**
 * TEMPORARY diagnostic. No setApplyOn* restrictions at all, so if Jahia wires DS-provided
 * RenderFilters from this bundle it MUST log on every render. Distinguishes "my filter config
 * is too narrow" from "runtime-installed bundles never get their code extension points wired".
 * Delete once the wiring question is settled.
 */
@Component(service = RenderFilter.class, immediate = true)
public class WiringProbeFilter extends AbstractFilter {

    private static final Logger logger = LoggerFactory.getLogger(WiringProbeFilter.class);

    @Activate
    public void activate() {
        setPriority(998f);
        setDescription("CRH wiring probe");
        logger.error("CRH-PROBE: WiringProbeFilter ACTIVATED");
    }

    @Override
    public String execute(String previousOut, RenderContext renderContext, Resource resource,
                          RenderChain chain) {
        logger.error("CRH-PROBE: execute on {} templateType={}",
                resource.getNodePath(), resource.getTemplateType());
        return previousOut;
    }
}
