package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("message names the jahia.crh.captureBaseUrl override property")
    void messageNamesTheOverrideProperty() {
        // Arrange
        GuestMarkdownFetcher.ConnectorProbe probe =
                new GuestMarkdownFetcher.ConnectorProbe(null, Collections.emptySet(), null);

        // Act
        String message = GuestMarkdownFetcher.misconfigurationMessage(probe);

        // Assert -- this is the one property an operator must be told about to fix the outage
        assertTrue(message.contains(RevisionHistoryConstants.SYSPROP_CAPTURE_BASE_URL),
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
}
