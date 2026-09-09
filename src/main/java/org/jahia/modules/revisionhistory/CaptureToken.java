package org.jahia.modules.revisionhistory;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Proves that a {@code .markdown} request is this module's own capture fetch and not a caller from
 * outside the JVM.
 *
 * <p><b>Why this exists.</b> The {@code .markdown} URL is not a visitor-facing feature; it is the
 * mechanism by which a snapshot is made. {@link GuestMarkdownFetcher} asks this node, over its own
 * loopback connector, for the markdown render of a page and stores the answer. Nothing else in the
 * module reads it, and the public feature -- the revision list and the comparison -- is rendered by
 * ordinary HTML views that never touch it.
 *
 * <p>Being reachable from the internet was therefore an accident of implementing capture as an HTTP
 * render, and it was a reportable one (GHSA-q67w-prc3-ch5h #3): the generic fallback deliberately
 * emits EVERY text-bearing string property of every node beneath the page, including properties no
 * template displays, and an anonymous {@code GET} returned the lot. An independent review measured
 * internal ordering fields of {@code jmix:orderedList} coming back to an unauthenticated caller.
 *
 * <p><b>Why not narrow what is emitted instead.</b> That was tried, in 1.4.11, as a per-site
 * exclusion list, and it did not close the finding: the list is empty by default, so the same review
 * measured the 1.4.12 response as byte-identical to 1.4.10's. Narrowing at the source is also the
 * wrong trade for this module. The breadth is load-bearing -- a per-type list of "which properties
 * hold prose" can never be complete, because modules ship their own types, and a snapshot that emits
 * nothing for an unrecognised type is silent content loss, which
 * {@link RevisionHistoryFunctions#textProperties} documents as the worst failure this module has.
 * Closing the endpoint keeps every stored snapshot byte-identical, needs no per-site configuration
 * to be safe, and covers types nobody has written yet.
 *
 * <p><b>Why a token and not the caller's address.</b> A loopback-only rule looks equivalent and is
 * not: Jahia is commonly fronted by Apache or HAProxy on the same host, so a request from the public
 * internet arrives at Tomcat from {@code 127.0.0.1} and would pass. The token cannot be arrived at
 * by topology.
 *
 * <p><b>Why a token and not authentication.</b> The capture render must stay ANONYMOUS: rendering as
 * guest is how the module establishes what the public can actually see, and a page that guest cannot
 * read is supposed to be recorded as {@code NOT_PUBLIC} rather than captured with privileges. So the
 * token authenticates the CALLER without touching the identity the render runs as -- which is the
 * property no credential-based gate can provide.
 *
 * <p><b>Lifetime.</b> Generated once per classload, i.e. per bundle start, and held only in memory.
 * It is never written to configuration, never logged, and never leaves the JVM: the two legitimate
 * callers both run inside it -- capture directly, and the backfill script by loading this class out
 * of the module's own OSGi bundle, which it already does for {@code MarkdownNormalizer}. There is
 * consequently nothing to rotate and nothing to leak, and a restart invalidates it harmlessly.
 */
public final class CaptureToken {

    /**
     * Carried as a header rather than a query parameter on purpose. A query string is written to
     * every access log in the chain and shows up in {@code Referer}; a request header is not.
     */
    public static final String HEADER = "X-CRH-Capture";

    /**
     * 256 bits from {@link SecureRandom}. Long enough that guessing is not a threat model, so the
     * gate needs no rate limiting of its own.
     */
    private static final String VALUE = generate();

    private CaptureToken() {
    }

    private static String generate() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * @return the value {@link #HEADER} must carry for a markdown render to be served
     *
     * <p>Public because the backfill script is a second in-process caller and reaches it through
     * {@code bundle.loadClass}, exactly as it already reaches {@code MarkdownNormalizer}; making it
     * package-private would only force that script into {@code setAccessible} reflection, which is
     * more fragile and no more private. The trust boundary is the JVM, not the package: anything
     * running in this process can already read the module's configuration and the repository.
     *
     * <p>What must NOT happen is exposing it through GraphQL, a servlet, a log line or any other
     * remote surface. The moment it can be fetched over HTTP it becomes a credential to be stolen
     * rather than a fact about being inside the process, and the gate is worth nothing.
     */
    public static String value() {
        return VALUE;
    }

    /**
     * @return whether this request carries the capture token
     *
     * <p>Compared with {@link MessageDigest#isEqual}, which is the JDK's constant-time array
     * comparison. {@code String.equals} short-circuits on the first differing byte, and while the
     * remote timing signal on a 256-bit value is not a practical attack, using the constant-time
     * primitive costs nothing and removes the question. It also handles a null argument, so a
     * request with no header takes the same path as one with a wrong header.
     */
    static boolean presentIn(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                VALUE.getBytes(StandardCharsets.UTF_8));
    }
}
