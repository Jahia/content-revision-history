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
import java.util.Set;

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
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

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

    private String buildUrl(String pagePath, String language, long cacheBuster) throws IOException {
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
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Set<ObjectName> connectors = server.queryNames(new ObjectName("*:type=Connector,*"), null);
            for (ObjectName connector : connectors) {
                Object scheme = server.getAttribute(connector, "scheme");
                Object port = server.getAttribute(connector, "port");
                if ("http".equals(scheme) && port instanceof Integer && (Integer) port > 0) {
                    return (Integer) port;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not read the HTTP connector port from JMX, falling back to {}."
                    + " Set -D{} if that is wrong.", DEFAULT_BASE_URL,
                    RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL, e);
        }
        return 8080;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String getBaseUrl() {
        return baseUrl;
    }
}
