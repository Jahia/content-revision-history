package org.jahia.modules.revisionhistory;

import org.jahia.bin.Jahia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders a page's Markdown view <em>as the public sees it</em>, by fetching the live page
 * over loopback HTTP with no credentials at all.
 *
 * <p>Why HTTP and not {@code RenderService} directly: Jahia ships no mock
 * {@code HttpServletRequest} and {@code RenderContext} has no off-request constructor, so a
 * view cannot be rendered from a background thread in-process -- JSP views are executed by
 * {@code RequestDispatcherScript}, which needs a container-issued request to obtain a
 * {@code RequestDispatcher}. A loopback request gives us a real one, from the servlet
 * container, for free.
 *
 * <p>The security property this buys is the point of the class, not a side effect. The
 * connection carries no session cookie, no Authorization header and no JWT, so Jahia serves it
 * as {@code guest}. Whatever comes back is by construction what an anonymous visitor would
 * see, which is exactly the contract a public revision history has to honour. Capturing with
 * the triggering user's session -- the previous design -- meant an editor's privileges decided
 * the contents of a public record.
 *
 * <p>Bounded on every axis that an unbounded one would hurt: fixed loopback host (no SSRF
 * surface: the caller cannot influence the host), connect and read timeouts, no redirect
 * following, and a hard byte cap on the response body.
 */
final class GuestMarkdownFetcher {

    private static final Logger logger = LoggerFactory.getLogger(GuestMarkdownFetcher.class);

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:" + DEFAULT_PORT;

    /** Guards the misconfiguration ERROR so it is emitted once per JVM, not once per capture. */
    private static final AtomicBoolean FALLBACK_REPORTED = new AtomicBoolean(false);

    /** Result of one fetch: either a body, or a reason there is none. */
    static final class Fetched {
        final String body;
        final CaptureStatus status;
        final String message;
        final String sourceUrl;

        private Fetched(String body, CaptureStatus status, String message, String sourceUrl) {
            this.body = body;
            this.status = status;
            this.message = message;
            this.sourceUrl = sourceUrl;
        }

        static Fetched ok(String body, String sourceUrl) {
            return new Fetched(body, CaptureStatus.STORED, null, sourceUrl);
        }

        static Fetched problem(CaptureStatus status, String message, String sourceUrl) {
            return new Fetched(null, status, message, sourceUrl);
        }

        boolean isOk() {
            return body != null;
        }
    }

    private final String baseUrl;

    GuestMarkdownFetcher() {
        this(resolveBaseUrl());
    }

    GuestMarkdownFetcher(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * @param pagePath   JCR path of the page, e.g. {@code /sites/digitall/home/foo}
     * @param language   language code of the render
     * @param cacheBuster value making the URL unique per publication, so the live HTML cache
     *                    cannot hand back the pre-publication rendering. Deterministic per
     *                    publication rather than random, so a retry reuses one cache entry
     *                    instead of creating another.
     */
    Fetched fetch(String pagePath, String language, long cacheBuster) {
        String url;
        try {
            url = buildUrl(pagePath, language, cacheBuster);
        } catch (IllegalArgumentException | IOException e) {
            return Fetched.problem(CaptureStatus.FAILED, "Cannot build capture URL: " + e.getMessage(), null);
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            // Deliberately no cookie, no Authorization: the render must resolve to guest.
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept", "text/html, text/plain");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // 401/403/302-to-login all mean the same thing here: the public cannot read
                // this page, so there is nothing a public revision history may record.
                return Fetched.problem(CaptureStatus.NOT_PUBLIC,
                        "Guest render returned HTTP " + code, url);
            }
            String body = readBounded(connection.getInputStream());
            if (body == null) {
                return Fetched.problem(CaptureStatus.OVERSIZE,
                        "Guest render exceeded " + RevisionHistoryConstants.MAX_MARKDOWN_BYTES + " bytes", url);
            }
            return Fetched.ok(body, url);
        } catch (IOException e) {
            logger.debug("Guest capture render failed for {}", url, e);
            return Fetched.problem(CaptureStatus.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), url);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // Package-private (was private) so RevisionSnapshotServiceTest-sibling tests in this package
    // can exercise the URL-building/validation logic directly, without standing up an HTTP
    // server. This is the ONE approved production visibility change for the test-coverage
    // remediation task; behavior is unchanged.
    String buildUrl(String pagePath, String language, long cacheBuster) throws IOException {
        if (pagePath == null || !pagePath.startsWith("/sites/")) {
            throw new IllegalArgumentException("not a site path: " + pagePath);
        }
        StringBuilder encoded = new StringBuilder();
        for (String segment : pagePath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("illegal path segment in " + pagePath);
            }
            encoded.append('/').append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
        }
        return baseUrl + "/cms/render/live/" + URLEncoder.encode(language, "UTF-8")
                + encoded + '.' + RevisionHistoryConstants.MARKDOWN_TEMPLATE_TYPE
                + "?crhCapture=" + cacheBuster;
    }

    /** @return the decoded body, or null when the cap was exceeded */
    private String readBounded(InputStream in) throws IOException {
        int cap = RevisionHistoryConstants.MAX_MARKDOWN_BYTES;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(cap, 64 * 1024));
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > cap) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Loopback base URL of this very node.
     *
     * <p>The port is read from the servlet container's own MBean rather than configured, so a
     * non-default port needs no extra configuration and cannot silently drift out of sync. The
     * system property remains as an escape hatch for exotic setups (a container whose HTTP
     * connector is not reachable on loopback, for instance).
     */
    private static String resolveBaseUrl() {
        String override = System.getProperty(RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL);
        if (override != null && !override.trim().isEmpty()) {
            return stripTrailingSlash(override.trim());
        }
        int port = detectHttpPort();
        String contextPath = safeContextPath();
        return "http://127.0.0.1:" + port + contextPath;
    }

    private static String safeContextPath() {
        try {
            String contextPath = Jahia.getContextPath();
            return contextPath == null || "/".equals(contextPath) ? "" : contextPath;
        } catch (RuntimeException e) {
            logger.debug("No servlet context path available, assuming root", e);
            return "";
        }
    }

    private static int detectHttpPort() {
        ConnectorProbe probe = probeConnectors(ManagementFactory.getPlatformMBeanServer());
        if (probe.httpPort != null) {
            return probe.httpPort;
        }
        // Once per JVM, not once per capture: the condition is a deployment-wide
        // misconfiguration, and repeating it per page would bury it in its own noise.
        if (FALLBACK_REPORTED.compareAndSet(false, true)) {
            logger.error(misconfigurationMessage(probe), probe.failure);
        }
        return DEFAULT_PORT;
    }

    /** What a JMX connector sweep found, or why it found nothing usable. */
    static final class ConnectorProbe {
        /** Port of the first usable plain-HTTP connector, or null when there is none. */
        final Integer httpPort;
        /** Schemes of the connectors that were present but not plain HTTP, e.g. {@code https}. */
        final Set<String> otherSchemes;
        /** Non-null only when the sweep itself failed. */
        final Exception failure;

        ConnectorProbe(Integer httpPort, Set<String> otherSchemes, Exception failure) {
            this.httpPort = httpPort;
            this.otherSchemes = otherSchemes;
            this.failure = failure;
        }
    }

    /**
     * Sweeps the container's connector MBeans for a plain-HTTP listener.
     *
     * <p>Also collects the schemes it rejected, because "there is an https connector and no http
     * one" is a diagnosis, while "the port could not be read" is only a symptom.
     */
    static ConnectorProbe probeConnectors(MBeanServer server) {
        Set<String> otherSchemes = new LinkedHashSet<>();
        try {
            Set<ObjectName> connectors = server.queryNames(new ObjectName("*:type=Connector,*"), null);
            for (ObjectName connector : connectors) {
                Object scheme = server.getAttribute(connector, "scheme");
                Object port = server.getAttribute(connector, "port");
                boolean usablePort = port instanceof Integer && (Integer) port > 0;
                if ("http".equals(scheme) && usablePort) {
                    return new ConnectorProbe((Integer) port, otherSchemes, null);
                }
                if (scheme != null) {
                    otherSchemes.add(String.valueOf(scheme));
                }
            }
        } catch (Exception e) {
            return new ConnectorProbe(null, otherSchemes, e);
        }
        return new ConnectorProbe(null, otherSchemes, null);
    }

    /**
     * The one message an operator gets for this condition, so it has to be self-contained:
     * what broke, what it costs, and the exact property that fixes it.
     */
    static String misconfigurationMessage(ConnectorProbe probe) {
        String cause;
        if (probe.failure != null) {
            cause = "the connector MBeans could not be read (" + probe.failure + ")";
        } else if (probe.otherSchemes.isEmpty()) {
            cause = "this node exposes no connector MBean at all";
        } else {
            cause = "this node exposes only " + probe.otherSchemes + " connector(s)"
                    + (probe.otherSchemes.contains("https")
                    ? " -- it looks like an HTTPS-only deployment" : "");
        }
        return "CONTENT REVISION HISTORY IS MISCONFIGURED: no plain-HTTP connector was found, "
                + cause + ". Guest capture renders will be attempted against " + DEFAULT_BASE_URL
                + ", which almost certainly listens to nothing, so EVERY snapshot capture for "
                + "EVERY page will be recorded FAILED until this is corrected. Set -D"
                + RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL
                + "=<base URL of this node, reachable from this node itself> (for example -D"
                + RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL
                + "=https://127.0.0.1:8443) and restart.";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String getBaseUrl() {
        return baseUrl;
    }
}
