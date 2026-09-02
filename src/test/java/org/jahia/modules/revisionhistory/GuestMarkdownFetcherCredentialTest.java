package org.jahia.modules.revisionhistory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the capture credential leaves this JVM only for this node's own connector, by observing
 * what an HTTP server actually receives.
 *
 * <p>Why not a unit test of the predicate. {@code reachesJahiaDirectly} is already pinned by
 * {@link GuestMarkdownFetcherTest}, and that proves nothing about this: the guard could be deleted
 * from {@code doFetch}, or inverted, and every one of those assertions would still pass. The
 * property that matters is about the request on the wire, so the test has to be about the request
 * on the wire. {@code capture.baseUrl} is writable by a site administrator, so the thing being
 * prevented is a site-scoped role receiving the operator's capture password.
 *
 * <p>How a non-loopback endpoint is reached without leaving the machine: the whole {@code 127/8}
 * range is loopback to the kernel, but {@link GuestMarkdownFetcher#reachesJahiaDirectly} matches
 * the host literally and anchored, so {@code 127.0.0.2} is a real address this test can bind and
 * talk to while being, correctly, "not this node's own connector" as far as the guard is concerned.
 * That gives both branches a live server and keeps the test hermetic.
 */
class GuestMarkdownFetcherCredentialTest {

    private static final String PAGE = "/sites/digitall/home/policy";

    private final CaptureIdentity identity = new CaptureIdentity();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        // Static, so it would otherwise be read by whatever test runs next.
        identity.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    /** @return the Authorization header the server saw, holding null when none arrived */
    private AtomicReference<String> serverOn(String host) throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "# Policy".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return seen;
    }

    private String baseUrl(String host) {
        return "http://" + host + ":" + server.getAddress().getPort();
    }

    private void configureCredential() {
        Map<String, Object> config = new HashMap<>();
        config.put(CaptureIdentity.PROP_USER, "crh-capture");
        config.put(CaptureIdentity.PROP_SECRET, "not-a-real-secret");
        identity.configure(config);
        assertNotNull(CaptureIdentity.authorization(),
                "the test is meaningless unless a credential actually resolved");
    }

    @Test
    @DisplayName("The credential IS sent to this node's own loopback connector")
    void sendsCredentialToLoopback() throws Exception {
        // Arrange
        AtomicReference<String> seen = serverOn("127.0.0.1");
        configureCredential();
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher(baseUrl("127.0.0.1"));

        // Act
        GuestMarkdownFetcher.Fetched fetched = fetcher.fetch(PAGE, "en", 1L, null);

        // Assert
        assertTrue(fetched.isOk(), "the stub server answered 200, so the fetch should have worked");
        assertNotNull(seen.get(), "a configured credential must reach this node's own connector,"
                + " or restricted pages could never be captured at all");
        assertEquals("crh-capture", fetched.principal,
                "the snapshot records who the render actually ran as");
    }

    @Test
    @DisplayName("The credential is NOT sent to an endpoint that is not this node's connector")
    void withholdsCredentialFromAnyOtherHost() throws Exception {
        // Arrange
        AtomicReference<String> seen = serverOn("127.0.0.2");
        configureCredential();
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher(baseUrl("127.0.0.2"));

        // Act
        GuestMarkdownFetcher.Fetched fetched = fetcher.fetch(PAGE, "en", 1L, null);

        // Assert
        assertNull(seen.get(), "the capture password must not reach a host a site administrator"
                + " chose; this is the exfiltration channel the guard exists to close");
        assertEquals(RevisionHistoryConstants.CAPTURE_PRINCIPAL, fetched.principal,
                "no credential went out, so the record must say the render was anonymous rather"
                + " than naming an account that did not authenticate it");
    }

    @Test
    @DisplayName("A refusal after the credential was withheld says so in the durable record")
    void refusalNamesTheWithheldCredential() throws Exception {
        // Arrange: a server that refuses, standing in for a page guest may not read.
        AtomicReference<String> seen = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        configureCredential();
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher(baseUrl("127.0.0.2"));

        // Act
        GuestMarkdownFetcher.Fetched fetched = fetcher.fetch(PAGE, "en", 1L, null);

        // Assert: this is the whole point of keying status on "configured" and not "sent".
        // Reported as indistinguishable from a genuinely non-public page, which sent an operator
        // to inspect ACLs that were never the problem while the fixable cause -- their own
        // capture.baseUrl -- appeared nowhere in the record the module keeps precisely because
        // logs roll.
        assertNull(seen.get(), "the credential must still have been withheld");
        assertEquals(CaptureStatus.FAILED, fetched.status,
                "an operator configured an account so restricted pages could be captured, and it"
                + " was not even tried: that is a misconfiguration, not a policy outcome");
        assertTrue(fetched.message.contains(CaptureEndpoint.PROP_BASE_URL),
                "the durable message has to name the setting that has to change; it said only"
                + " 'Guest render returned HTTP 403'. Actual: " + fetched.message);
    }
}
