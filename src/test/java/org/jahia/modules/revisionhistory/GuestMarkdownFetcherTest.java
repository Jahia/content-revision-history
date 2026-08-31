package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.management.AttributeNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the two static, package-visible pieces of {@link GuestMarkdownFetcher} that decide (a)
 * where a container's plain-HTTP connector lives -- {@link GuestMarkdownFetcher#probeConnectors}
 * -- and (b) the operator-facing message when none can be found, plus {@code buildUrl}, whose
 * visibility was widened from {@code private} to package-private specifically so it could be
 * exercised here without standing up an HTTP server.
 */
class GuestMarkdownFetcherTest {

    // ------------------------------------------------------------ probeConnectors

    @Test
    @DisplayName("a plain-HTTP connector yields its port")
    void httpConnectorYieldsItsPort() throws Exception {
        // Arrange
        MBeanServer server = mock(MBeanServer.class);
        ObjectName httpConnector = new ObjectName("Catalina:type=Connector,port=8080");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(httpConnector));
        when(server.getAttribute(httpConnector, "scheme")).thenReturn("http");
        when(server.getAttribute(httpConnector, "port")).thenReturn(8080);

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertEquals(Integer.valueOf(8080), probe.httpPort);
        assertNull(probe.failure);
    }

    @Test
    @DisplayName("one unreadable connector does not hide the real HTTP connector behind it")
    void oneUnreadableConnectorDoesNotHideTheRealOne() throws Exception {
        // Arrange -- queryNames returns a Set, so enumeration order is unspecified. A container
        // can expose a connector whose attributes cannot be read (an AJP or a custom one), and
        // it may come first. Aborting the whole sweep on it fell back to the default port and
        // made every capture on that container fail, with a perfectly usable connector present.
        MBeanServer server = mock(MBeanServer.class);
        ObjectName broken = new ObjectName("Catalina:type=Connector,port=8009");
        ObjectName real = new ObjectName("Catalina:type=Connector,port=9090");
        LinkedHashSet<ObjectName> ordered = new LinkedHashSet<>();
        ordered.add(broken);
        ordered.add(real);
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(ordered);
        when(server.getAttribute(broken, "scheme"))
                .thenThrow(new AttributeNotFoundException("scheme"));
        when(server.getAttribute(real, "scheme")).thenReturn("http");
        when(server.getAttribute(real, "port")).thenReturn(9090);

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertEquals(Integer.valueOf(9090), probe.httpPort,
                "the sweep must continue past a connector it cannot read");
        assertNull(probe.failure, "finding a usable connector is a success, whatever preceded it");
    }

    @Test
    @DisplayName("a sweep that finds nothing still reports the last unreadable connector")
    void unreadableConnectorIsReportedWhenNothingUsableIsFound() throws Exception {
        // Arrange -- continuing past a failure must not mean swallowing it: with no usable
        // connector, the reason the sweep came up empty is the diagnosis.
        MBeanServer server = mock(MBeanServer.class);
        ObjectName broken = new ObjectName("Catalina:type=Connector,port=8009");
        when(server.queryNames(any(ObjectName.class), any()))
                .thenReturn(Collections.singleton(broken));
        when(server.getAttribute(broken, "scheme"))
                .thenThrow(new AttributeNotFoundException("scheme"));

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertNull(probe.httpPort);
        assertNotNull(probe.failure, "an empty result with no reason is not a diagnosis");
    }

    // ------------------------------------------------------- statusForHttp

    @Test
    @DisplayName("401, 403, 404 and a redirect are permission facts: NOT_PUBLIC")
    void permissionRelatedCodesAreNotPublic() {
        // Jahia answers 404 rather than 403 for content guest may not see, so 404 belongs here.
        assertEquals(CaptureStatus.NOT_PUBLIC, GuestMarkdownFetcher.statusForHttp(401));
        assertEquals(CaptureStatus.NOT_PUBLIC, GuestMarkdownFetcher.statusForHttp(403));
        assertEquals(CaptureStatus.NOT_PUBLIC, GuestMarkdownFetcher.statusForHttp(404));
        assertEquals(CaptureStatus.NOT_PUBLIC, GuestMarkdownFetcher.statusForHttp(302),
                "a redirect to login means the same thing as a 403");
    }

    @Test
    @DisplayName("a 5xx is a broken render, not a permission fact: FAILED")
    void serverErrorsAreFailedNotNotPublic() {
        // Reporting NOT_PUBLIC for a 500 sent operators to inspect ACLs on a page whose ACLs
        // were never the problem, while the real cause was an exception in a markdown view.
        assertEquals(CaptureStatus.FAILED, GuestMarkdownFetcher.statusForHttp(500));
        assertEquals(CaptureStatus.FAILED, GuestMarkdownFetcher.statusForHttp(502));
        assertEquals(CaptureStatus.FAILED, GuestMarkdownFetcher.statusForHttp(503));
    }

    @Test
    @DisplayName("a request we malformed is ours, not the page's: FAILED")
    void badRequestIsOurFault() {
        assertEquals(CaptureStatus.FAILED, GuestMarkdownFetcher.statusForHttp(400));
    }

    @Test
    @DisplayName("an https-only instance yields no port and reports the https scheme")
    void httpsOnlyYieldsNoPortAndReportsScheme() throws Exception {
        // Arrange
        MBeanServer server = mock(MBeanServer.class);
        ObjectName httpsConnector = new ObjectName("Catalina:type=Connector,port=8443");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(httpsConnector));
        when(server.getAttribute(httpsConnector, "scheme")).thenReturn("https");
        when(server.getAttribute(httpsConnector, "port")).thenReturn(8443);

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertNull(probe.httpPort, "an https connector must never be mistaken for a usable http one");
        assertTrue(probe.otherSchemes.contains("https"));
        assertNull(probe.failure);
    }

    @Test
    @DisplayName("a connector with an unusable (zero) port is not treated as a usable http listener")
    void zeroPortHttpConnectorIsNotUsable() throws Exception {
        // Arrange -- guards the "usablePort" check: port must be a positive Integer
        MBeanServer server = mock(MBeanServer.class);
        ObjectName disabledConnector = new ObjectName("Catalina:type=Connector,port=0");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(disabledConnector));
        when(server.getAttribute(disabledConnector, "scheme")).thenReturn("http");
        when(server.getAttribute(disabledConnector, "port")).thenReturn(0);

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertNull(probe.httpPort, "port 0 means the connector is disabled, not listening");
    }

    @Test
    @DisplayName("a throwing queryNames is captured as a probe failure, not propagated")
    void throwingQueryNamesIsCapturedAsFailure() throws Exception {
        // Arrange
        MBeanServer server = mock(MBeanServer.class);
        RuntimeException boom = new IllegalStateException("JMX sweep exploded");
        when(server.queryNames(any(ObjectName.class), any())).thenThrow(boom);

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertNull(probe.httpPort);
        assertNotNull(probe.failure, "a failed sweep must be visible as a failure, not silently empty");
        assertEquals(boom, probe.failure);
    }

    @Test
    @DisplayName("no connector MBeans at all yields no port and no schemes")
    void noConnectorsYieldsEmptyProbe() throws Exception {
        // Arrange
        MBeanServer server = mock(MBeanServer.class);
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.emptySet());

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertNull(probe.httpPort);
        assertTrue(probe.otherSchemes.isEmpty());
        assertNull(probe.failure);
    }

    // ------------------------------------------------------------ misconfigurationMessage

    @Test
    @DisplayName("message names the capture.baseUrl configuration key")
    void messageNamesTheOverrideProperty() {
        // Arrange
        GuestMarkdownFetcher.ConnectorProbe probe =
                new GuestMarkdownFetcher.ConnectorProbe(null, Collections.emptySet(), null);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert -- this is the one property an operator must be told about to fix the outage
        assertTrue(message.contains(CaptureEndpoint.PROP_BASE_URL),
                "the message must name the exact system property that fixes the misconfiguration");
    }

    @Test
    @DisplayName("message does not mention https when no https connector was seen")
    void messageOmitsHttpsWhenNotPresent() {
        // Arrange -- no connectors at all
        GuestMarkdownFetcher.ConnectorProbe probe =
                new GuestMarkdownFetcher.ConnectorProbe(null, Collections.emptySet(), null);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert
        assertTrue(message.contains("exposes no connector MBean at all"));
        assertFalse(message.contains("HTTPS-only"),
                "must not claim an HTTPS-only deployment when there is no evidence of one");
    }

    @Test
    @DisplayName("message reports an other, non-https scheme without claiming HTTPS-only")
    void messageReportsNonHttpsSchemeWithoutHttpsClaim() {
        // Arrange -- an AJP-only deployment: a real "other scheme" that is not https
        Set<String> schemes = new LinkedHashSet<>();
        schemes.add("ajp");
        GuestMarkdownFetcher.ConnectorProbe probe = new GuestMarkdownFetcher.ConnectorProbe(null, schemes, null);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert
        assertTrue(message.contains("ajp"));
        assertFalse(message.contains("HTTPS-only"), "https must only be mentioned when it is actually present");
    }

    @Test
    @DisplayName("message mentions https only when an https connector was actually found")
    void messageMentionsHttpsOnlyWhenPresent() {
        // Arrange
        Set<String> schemes = new LinkedHashSet<>();
        schemes.add("https");
        GuestMarkdownFetcher.ConnectorProbe probe = new GuestMarkdownFetcher.ConnectorProbe(null, schemes, null);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert
        assertTrue(message.contains("https"));
        assertTrue(message.contains("HTTPS-only"),
                "an https-only deployment is exactly the case operators most need called out");
    }

    @Test
    @DisplayName("message reports the sweep failure cause when the probe itself failed")
    void messageReportsSweepFailureCause() {
        // Arrange
        Exception failure = new SecurityException("no JMX permission");
        GuestMarkdownFetcher.ConnectorProbe probe =
                new GuestMarkdownFetcher.ConnectorProbe(null, Collections.emptySet(), failure);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert
        assertTrue(message.contains("could not be read"));
        assertTrue(message.contains("no JMX permission"));
    }

    // ------------------------------------------------------------ buildUrl

    @Test
    @DisplayName("builds a well-formed URL with the language, encoded path and template suffix")
    void buildsWellFormedUrl() throws IOException {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act
        String url = fetcher.buildUrl("/sites/mySite/home", "en", 42L);

        // Assert
        assertEquals("http://127.0.0.1:1234/cms/render/live/en/sites/mySite/home.markdown?crhCapture=42", url);
    }

    @Test
    @DisplayName("the cache-buster appears as the documented crhCapture query parameter")
    void cacheBusterAppearsAsDocumentedQueryParameter() throws IOException {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act
        String url = fetcher.buildUrl("/sites/mySite/home", "en", 123456789L);

        // Assert
        assertTrue(url.endsWith("?crhCapture=123456789"),
                "the cache-buster must be carried by the documented crhCapture parameter");
    }

    @Test
    @DisplayName("a path segment with a space is percent-encoded per segment")
    void encodesSpacesPerSegment() throws IOException {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act
        String url = fetcher.buildUrl("/sites/mySite/my page", "en", 1L);

        // Assert -- URLEncoder would otherwise emit "+" for a space; the code explicitly
        // replaces it with the URL-path-correct "%20"
        assertTrue(url.contains("/my%20page."), url);
        assertFalse(url.contains("+"), "a literal '+' would be misread as a space by some servers, not this one");
    }

    @Test
    @DisplayName("a path segment with unicode characters is percent-encoded per segment")
    void encodesUnicodePerSegment() throws IOException {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act
        String url = fetcher.buildUrl("/sites/mySite/café", "en", 1L);

        // Assert -- UTF-8 percent-encoding of 'é' (U+00E9) is %C3%A9; slashes between segments
        // must survive untouched (each segment is encoded independently, not the whole path)
        assertTrue(url.contains("/sites/mySite/caf%C3%A9."), url);
    }

    @Test
    @DisplayName("rejects a path that is not under /sites/")
    void rejectsNonSitePath() {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.buildUrl("/not-a-site-path", "en", 1L));
    }

    @Test
    @DisplayName("rejects a path containing a '.' segment")
    void rejectsDotSegment() {
        // Arrange
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.buildUrl("/sites/mySite/./home", "en", 1L));
    }

    @Test
    @DisplayName("rejects a path containing a '..' segment")
    void rejectsDotDotSegment() {
        // Arrange -- the concrete path-traversal shape this guard exists to stop
        GuestMarkdownFetcher fetcher = new GuestMarkdownFetcher("http://127.0.0.1:1234");

        // Act / Assert
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.buildUrl("/sites/mySite/../../etc/passwd", "en", 1L));
    }

    // --- the capture base URL must reach Jahia directly -----------------------------------
    //
    // capture.baseUrl is an escape hatch for exotic setups, and it accepts anything. A
    // deployment that points it at the site's PUBLIC address gets a flat 404 on every capture:
    // a public host has SEO URL rewriting (urlRewriteSeoRulesEnabled, urlRewriteRemoveCmsPrefix)
    // and usually a reverse proxy, and those rewrite or refuse the /cms/render/... paths this
    // fetcher asks for. It reports FAILED and says nothing about why, which cost a real
    // investigation several rounds on the backfill script before the address was questioned.

    @Test
    @DisplayName("a loopback capture base URL is accepted without complaint")
    void loopbackBaseUrlsAreFine() {
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("http://127.0.0.1:8080"));
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("http://localhost:8080"));
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("https://127.0.0.1:8443"));
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("http://127.0.0.1:8080/context"));
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("http://[::1]:8080"));
    }

    @Test
    @DisplayName("a public hostname is flagged, because every render will 404")
    void publicBaseUrlsAreFlagged() {
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("https://academypp.jahia.com"));
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("http://www.example.com:8080"));
    }

    @Test
    @DisplayName("a host that merely begins with localhost is not loopback")
    void lookalikeHostnamesAreFlagged() {
        // localhost.example.com resolves wherever its DNS says, which is not this machine.
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("https://localhost.example.com"));
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("http://127.0.0.1.example.com"));
    }

    @Test
    @DisplayName("nothing configured is treated as fine, because the default is derived")
    void absentBaseUrlIsNotFlagged() {
        // resolveBaseUrl only consults this when an override is set; the derived default is always
        // loopback, so a null must not produce a warning on every start.
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly(null));
        assertTrue(GuestMarkdownFetcher.reachesJahiaDirectly("   "));
    }

    // --- AJP reports scheme "http" too -----------------------------------------------------
    //
    // Reported from a real instance: capture asked http://127.0.0.1:8009/... and got
    // "SocketException: Unexpected end of file from server", which is what speaking HTTP to an AJP
    // port sounds like. Tomcat's AJP connector reports scheme="http" -- the attribute describes how
    // requests APPEAR to the application, not the wire protocol -- so a sweep matching on scheme
    // alone picks it whenever it is enumerated first.

    @Test
    @DisplayName("an AJP connector is not mistaken for the HTTP one, though it reports scheme http")
    void ajpConnectorIsNotMistakenForHttp() throws Exception {
        // Arrange -- AJP first, so a sweep matching on scheme alone would take it
        MBeanServer server = mock(MBeanServer.class);
        ObjectName ajp = new ObjectName("Catalina:type=Connector,port=8009");
        ObjectName http = new ObjectName("Catalina:type=Connector,port=8080");
        LinkedHashSet<ObjectName> ordered = new LinkedHashSet<>();
        ordered.add(ajp);
        ordered.add(http);
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(ordered);
        when(server.getAttribute(ajp, "scheme")).thenReturn("http");
        when(server.getAttribute(ajp, "port")).thenReturn(8009);
        when(server.getAttribute(ajp, "protocol")).thenReturn("AJP/1.3");
        when(server.getAttribute(http, "scheme")).thenReturn("http");
        when(server.getAttribute(http, "port")).thenReturn(8080);
        when(server.getAttribute(http, "protocol")).thenReturn("HTTP/1.1");

        // Act
        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        // Assert
        assertEquals(Integer.valueOf(8080), probe.httpPort, "the HTTP connector must win");
    }

    @Test
    @DisplayName("the AJP protocol class name is recognised as well as AJP/1.3")
    void ajpProtocolClassNameIsRecognised() throws Exception {
        // A container may report the implementation class rather than the protocol string.
        MBeanServer server = mock(MBeanServer.class);
        ObjectName ajp = new ObjectName("Catalina:type=Connector,port=8009");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(ajp));
        when(server.getAttribute(ajp, "scheme")).thenReturn("http");
        when(server.getAttribute(ajp, "port")).thenReturn(8009);
        when(server.getAttribute(ajp, "protocol"))
                .thenReturn("org.apache.coyote.ajp.AjpNioProtocol");

        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        assertNull(probe.httpPort, "an AJP-only container has no usable HTTP connector");
    }

    @Test
    @DisplayName("a connector whose protocol cannot be read is still accepted")
    void unreadableProtocolIsNotTreatedAsAjp() throws Exception {
        // Rejecting on a missing attribute would break containers that do not expose it, which is
        // a worse failure than the one being fixed: it would take out working deployments.
        MBeanServer server = mock(MBeanServer.class);
        ObjectName http = new ObjectName("Catalina:type=Connector,port=8080");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(http));
        when(server.getAttribute(http, "scheme")).thenReturn("http");
        when(server.getAttribute(http, "port")).thenReturn(8080);
        when(server.getAttribute(http, "protocol"))
                .thenThrow(new AttributeNotFoundException("protocol"));

        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);

        assertEquals(Integer.valueOf(8080), probe.httpPort);
    }

    @Test
    @DisplayName("an AJP-only container is reported as such, not as having no connector")
    void ajpOnlyContainerIsDiagnosed() throws Exception {
        MBeanServer server = mock(MBeanServer.class);
        ObjectName ajp = new ObjectName("Catalina:type=Connector,port=8009");
        when(server.queryNames(any(ObjectName.class), any())).thenReturn(Collections.singleton(ajp));
        when(server.getAttribute(ajp, "scheme")).thenReturn("http");
        when(server.getAttribute(ajp, "port")).thenReturn(8009);
        when(server.getAttribute(ajp, "protocol")).thenReturn("AJP/1.3");

        GuestMarkdownFetcher.ConnectorProbe probe = GuestMarkdownFetcher.probeConnectors(server);
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        assertTrue(message.contains("ajp"), message);
        assertTrue(message.contains(RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL), message);
    }
}
