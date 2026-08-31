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
import java.util.Locale;
import java.util.regex.Pattern;
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

    /**
     * Non-null only when a test pinned one. Production leaves this null and resolves per call, so an
     * OSGi configuration change reaches the NEXT capture: this object is a static singleton, and
     * anything captured in its constructor would be fixed for the life of the JVM -- which is the
     * restart requirement the move away from a system property was meant to remove.
     */
    private final String pinnedBaseUrl;

    /**
     * The site being captured, so {@link #getBaseUrl()} can prefer that site's endpoint.
     *
     * <p>Set per fetch on a single object because the fetcher is a static singleton. Captures run on
     * Quartz worker threads, so this is a ThreadLocal rather than a field: two sites publishing at
     * once must not read each other's endpoint.
     */
    private final ThreadLocal<String> siteKeyForFetch = new ThreadLocal<>();

    GuestMarkdownFetcher() {
        this.pinnedBaseUrl = null;
    }

    GuestMarkdownFetcher(String baseUrl) {
        this.pinnedBaseUrl = baseUrl;
    }

    /**
     * @param pagePath   JCR path of the page, e.g. {@code /sites/digitall/home/foo}
     * @param language   language code of the render
     * @param cacheBuster value making the URL unique per publication, so the live HTML cache
     *                    cannot hand back the pre-publication rendering. Deterministic per
     *                    publication rather than random, so a retry reuses one cache entry
     *                    instead of creating another.
     */
    /** Kept for the existing tests, which do not care which site a page belongs to. */
    Fetched fetch(String pagePath, String language, long cacheBuster) {
        return fetch(pagePath, language, cacheBuster, null);
    }

    Fetched fetch(String pagePath, String language, long cacheBuster, String siteKey) {
        siteKeyForFetch.set(siteKey);
        try {
            return doFetch(pagePath, language, cacheBuster);
        } finally {
            // Quartz reuses worker threads, so a value left behind would be read by the next
            // capture on this thread -- a different site's endpoint.
            siteKeyForFetch.remove();
        }
    }

    private Fetched doFetch(String pagePath, String language, long cacheBuster) {
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
            // No cookie, ever: a session would make the render resolve to whoever last used
            // it. The identity comes from configuration and nowhere else.
            String authorization = authorizationFor(siteKeyForFetch.get());
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization);
            }
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept", "text/html, text/plain");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return Fetched.problem(statusForHttp(code, authorization != null),
                        renderFailureMessage(code, authorization != null), url);
            }
            String body = readBounded(connection.getInputStream());
            if (body == null) {
                return Fetched.problem(CaptureStatus.OVERSIZE,
                        "Guest render exceeded " + RevisionHistoryConstants.MAX_MARKDOWN_BYTES + " bytes", url);
            }
            return Fetched.ok(body, url);
        } catch (IOException e) {
            // Was DEBUG, which is off in production, while the durable record carries only the
            // exception class and message. A systemic failure -- the connector throttling, TLS
            // resets under load -- then had no stack trace anywhere, for any page.
            logger.warn("Guest capture render failed for {}", url, e);
            return Fetched.problem(CaptureStatus.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), url);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Maps a non-200 guest render to the outcome an operator should act on.
     *
     * <p>Everything used to be {@link CaptureStatus#NOT_PUBLIC}, which reads as a statement about
     * permissions. For 401, 403, 404 and a redirect to login that is exactly right -- Jahia
     * answers 404 rather than 403 for content guest may not see, so that one belongs here too.
     *
     * <p>A 5xx is not a permission fact. It means the render itself broke, most likely in a
     * {@code markdown} view. Recording NOT_PUBLIC for it sent operators to inspect ACLs on a page
     * whose ACLs were never the problem, while the stack trace they needed sat in the server log
     * under a different page's name. A 4xx we caused (a malformed URL, say) is likewise ours.
     */
    static CaptureStatus statusForHttp(int code) {
        return statusForHttp(code, false);
    }

    /**
     * @param authenticated whether the request carried a configured capture principal
     *
     * <p>NOT_PUBLIC is a statement about policy: the principal this module renders as may not
     * read the page, so there is nothing to record. Rendering anonymously that is a normal,
     * expected outcome for a page the public cannot see.
     *
     * <p>With a capture principal configured it is not an outcome at all, it is a
     * misconfiguration: an operator named an account precisely so restricted pages COULD be
     * captured, and the account was refused. Filing that under NOT_PUBLIC would report it as
     * working as intended and leave the history empty for exactly the pages the setting was
     * added to cover.
     */
    static CaptureStatus statusForHttp(int code, boolean authenticated) {
        boolean redirectedAway = code >= 300 && code < 400;
        boolean refused = code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN
                || code == HttpURLConnection.HTTP_NOT_FOUND;
        if (authenticated && refused) {
            return CaptureStatus.FAILED;
        }
        return redirectedAway || refused ? CaptureStatus.NOT_PUBLIC : CaptureStatus.FAILED;
    }

    /** A message that names the thing an operator has to change. */
    static String renderFailureMessage(int code, boolean authenticated) {
        String who = authenticated ? "Capture render" : "Guest render";
        boolean refused = code == HttpURLConnection.HTTP_UNAUTHORIZED
                || code == HttpURLConnection.HTTP_FORBIDDEN
                || code == HttpURLConnection.HTTP_NOT_FOUND;
        if (authenticated && refused) {
            return who + " returned HTTP " + code + " for the configured capture user: check "
                    + CaptureIdentity.PROP_USER + " and its secret, and that the account may"
                    + " read this page";
        }
        return who + " returned HTTP " + code;
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
        return getBaseUrl() + "/cms/render/live/" + URLEncoder.encode(language, "UTF-8")
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
    /**
     * Resolved per call rather than once, so an OSGi configuration change applies to the next
     * capture instead of the next restart. The detected fallback is still computed once: it sweeps
     * JMX, and the answer cannot change while the JVM runs.
     */
    private static String resolveBaseUrl() {
        String configured = CaptureEndpoint.baseUrl();
        if (configured != null) {
            if (!reachesJahiaDirectly(configured)) {
                logger.warn("{} is set to {}, which is not this node's own connector."
                        + " Capture asks for /cms/render/... paths, and a public host rewrites or"
                        + " refuses those (SEO rewriting, a reverse proxy), so EVERY capture will"
                        + " report FAILED on a flat HTTP 404 whatever the page. Point it at the"
                        + " loopback connector, e.g. http://127.0.0.1:8080, or remove the setting"
                        + " and let the port be detected.",
                        CaptureEndpoint.PROP_BASE_URL, configured);
            }
            return configured;
        }
        return DetectedEndpoint.VALUE;
    }

    /** The detected loopback endpoint, computed once: a JMX sweep whose answer cannot change. */
    private static final class DetectedEndpoint {
        static final String VALUE = "http://127.0.0.1:" + detectHttpPort() + safeContextPath();
    }

    /**
     * Does this base URL address Jahia directly, rather than through whatever serves the public site?
     *
     * <p>Only a loopback host does. A public hostname reaches the site through SEO URL rewriting
     * (and usually a reverse proxy), which rewrites or refuses {@code /cms/render/...} — so every
     * capture gets a flat 404 and reports {@code FAILED}, with nothing pointing at the address.
     *
     * <p>A blank value is accepted: {@code resolveBaseUrl} only consults this when an override is
     * set, and the derived default is always loopback, so a null must not warn on every start.
     *
     * <p>Matched on the host alone, and anchored. {@code localhost.example.com} resolves wherever
     * its DNS says, which is not this machine, so a prefix match would be wrong.
     *
     * <p>Package-private so the rule is pinned by a test rather than left to whoever edits it next.
     */
    static boolean reachesJahiaDirectly(String baseUrl) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return true;
        }
        return LOOPBACK_BASE_URL.matcher(baseUrl.trim()).matches();
    }

    /** Host must be exactly a loopback literal; a port and a context path may follow. */
    private static final Pattern LOOPBACK_BASE_URL = Pattern.compile(
            "(?i)https?://(127\\.0\\.0\\.1|localhost|\\[::1])(:\\d+)?(/.*)?");

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
        Set<ObjectName> connectors;
        try {
            connectors = server.queryNames(new ObjectName("*:type=Connector,*"), null);
        } catch (Exception sweepFailed) {
            // The query itself failed, so there is nothing to iterate.
            return new ConnectorProbe(null, otherSchemes, sweepFailed);
        }

        Exception unreadableConnector = null;
        for (ObjectName connector : connectors) {
            try {
                Object scheme = server.getAttribute(connector, "scheme");
                Object port = server.getAttribute(connector, "port");
                boolean usablePort = port instanceof Integer && (Integer) port > 0;
                boolean ajp = speaksAjp(server, connector);
                if ("http".equals(scheme) && usablePort && !ajp) {
                    return new ConnectorProbe((Integer) port, otherSchemes, null);
                }
                if (ajp) {
                    // Recorded as "ajp" rather than as its scheme, so the operator message can say
                    // what was actually found instead of claiming there was an http connector that
                    // somehow did not qualify.
                    otherSchemes.add("ajp");
                } else if (scheme != null) {
                    otherSchemes.add(String.valueOf(scheme));
                }
            } catch (Exception unreadable) {
                // Keep going. queryNames returns a Set, so enumeration order is unspecified:
                // one connector whose attributes cannot be read used to abort the sweep before
                // the real plain-HTTP connector was ever inspected, which silently fell back to
                // the default port and made every capture on that container fail.
                unreadableConnector = unreadable;
            }
        }
        return new ConnectorProbe(null, otherSchemes, unreadableConnector);
    }

    /**
     * Does this connector speak AJP rather than HTTP?
     *
     * <p>Tomcat's AJP connector reports {@code scheme="http"} -- that attribute describes how a
     * request APPEARS to the application, not the wire protocol -- so matching on the scheme alone
     * picks it whenever JMX enumerates it first. Reported from a real instance: capture asked
     * {@code http://127.0.0.1:8009/...} and got {@code SocketException: Unexpected end of file from
     * server}, which is what speaking HTTP at an AJP port sounds like.
     *
     * <p>{@code protocol} is matched loosely because containers report it either as a protocol
     * string ({@code AJP/1.3}) or as an implementation class
     * ({@code org.apache.coyote.ajp.AjpNioProtocol}).
     *
     * <p>An unreadable or absent {@code protocol} counts as NOT AJP. Rejecting on a missing
     * attribute would break containers that do not expose it, which is a worse failure than the one
     * this fixes: it would take out deployments that work today.
     */
    private static boolean speaksAjp(MBeanServer server, ObjectName connector) {
        try {
            Object protocol = server.getAttribute(connector, "protocol");
            return protocol != null
                    && String.valueOf(protocol).toLowerCase(Locale.ROOT).contains("ajp");
        } catch (Exception protocolUnreadable) {
            return false;
        }
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
                + CaptureEndpoint.PROP_BASE_URL
                + "=<base URL of this node, reachable from this node itself> (for example -D"
                + CaptureEndpoint.PROP_BASE_URL
                + " = https://127.0.0.1:8443) in the module configuration; it applies without a restart.";
    }

    /**
     * Which principal captures this site.
     *
     * <p>A site's own capture user wins; otherwise the module-wide one applies. Falling back rather
     * than refusing matters for an upgrade: every site that had no per-site configuration keeps
     * capturing exactly as it did, with the global account.
     *
     * <p>Package-private so the precedence is pinned by a test rather than inferred from two call
     * sites.
     */
    static String authorizationFor(String siteKey) {
        String perSite = SiteSettingsRegistry.settingsFor(siteKey).getAuthorization();
        return perSite != null ? perSite : CaptureIdentity.authorization();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String getBaseUrl() {
        if (pinnedBaseUrl != null) {
            return pinnedBaseUrl;
        }
        String perSite = SiteSettingsRegistry.settingsFor(siteKeyForFetch.get()).getBaseUrl();
        return perSite != null ? perSite : resolveBaseUrl();
    }
}
