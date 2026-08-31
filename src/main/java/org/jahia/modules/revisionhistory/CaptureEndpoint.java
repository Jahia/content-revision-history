package org.jahia.modules.revisionhistory;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Where capture addresses this node, when the port cannot be detected.
 *
 * <p>The port is normally read from the container's own connector MBean, so nothing needs
 * configuring. This exists for the cases where that is not enough: a container whose HTTP
 * connector is not reachable on loopback, or one whose MBeans cannot be read.
 *
 * <p>It replaces a system property. A system property is set on the JVM command line, so it needs a
 * restart to change, it is visible to anyone who can list processes, and it cannot be managed per
 * environment the way a configuration file can. It was also the only setting in this module that
 * lived outside the module's own configuration, which is a surprise for whoever goes looking for it.
 * {@link Modified} means a corrected value applies in seconds instead.
 *
 * <p>Shares {@link CaptureIdentity#PID} deliberately: an operator configuring capture edits one
 * file. Declarative Services delivers the same configuration to every component that names the pid.
 *
 * <p>Static and volatile for the same reason as {@link CaptureIdentity}: the fetcher is a plain
 * object held statically, and configuration admin can replace this on its own thread while a capture
 * job reads it on a Quartz one.
 */
@Component(
        service = CaptureEndpoint.class,
        immediate = true,
        configurationPid = CaptureIdentity.PID,
        configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class CaptureEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(CaptureEndpoint.class);

    static final String PROP_BASE_URL = "capture.baseUrl";

    private static volatile String baseUrl;

    @Activate
    @Modified
    public void configure(Map<String, Object> properties) {
        String configured = trimmedOrNull(properties);
        baseUrl = configured;
        if (configured == null) {
            return;
        }
        logger.info("Capture renders will be addressed to {} ({}), rather than to the"
                + " container's detected HTTP connector.", configured, PROP_BASE_URL);
        // Warned here as well as at fetch time. At fetch time it arrives with the first FAILED
        // capture, which may be a publication away; here it arrives while the operator is still
        // looking at the file they just saved.
        if (!GuestMarkdownFetcher.reachesJahiaDirectly(configured)) {
            logger.warn("{} is {}, which is not this node's own connector. Capture asks for"
                    + " /cms/render/... paths, and a public host rewrites or refuses those (SEO"
                    + " rewriting, a reverse proxy), so every capture will report FAILED on a flat"
                    + " HTTP 404 whatever the page. Use the loopback connector, e.g."
                    + " http://127.0.0.1:8080, or remove the setting and let the port be detected.",
                    PROP_BASE_URL, configured);
        }
    }

    @Deactivate
    public void clear() {
        // A capture still in flight must not address configuration the platform has withdrawn.
        baseUrl = null;
    }

    /**
     * @return the configured base URL, or {@code null} to detect the connector instead
     */
    static String baseUrl() {
        return baseUrl;
    }

    /**
     * <p>A blank value means "detect it". An operator who comments the value out leaves
     * {@code capture.baseUrl =} behind, and that has to mean the same as never setting it.
     *
     * <p>A trailing slash is removed here rather than at every use: the paths appended to this
     * always start with one, and {@code //cms/render} is a different URL.
     */
    private static String trimmedOrNull(Map<String, Object> properties) {
        Object raw = properties == null ? null : properties.get(PROP_BASE_URL);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
