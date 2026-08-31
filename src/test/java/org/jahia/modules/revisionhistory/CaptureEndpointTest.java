package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where capture renders are addressed, as OSGi configuration rather than a system property.
 *
 * <p>A system property is set on the JVM command line: it needs a restart to change, it is visible
 * to anyone who can list processes, and it cannot be managed per environment the way a configuration
 * file can. It was also the only setting in this module that lived outside the module's own
 * configuration, which is a surprise for whoever goes looking.
 */
class CaptureEndpointTest {

    private final CaptureEndpoint endpoint = new CaptureEndpoint();

    @AfterEach
    void reset() {
        endpoint.clear();
    }

    private static Map<String, Object> config(String baseUrl) {
        Map<String, Object> properties = new HashMap<>();
        if (baseUrl != null) {
            properties.put(CaptureEndpoint.PROP_BASE_URL, baseUrl);
        }
        return properties;
    }

    @Test
    @DisplayName("nothing configured leaves the port to be detected")
    void unconfiguredYieldsNull() {
        endpoint.configure(Collections.emptyMap());

        assertNull(CaptureEndpoint.baseUrl(), "the detected loopback connector must be used");
    }

    @Test
    @DisplayName("a configured base URL is used, with any trailing slash removed")
    void configuredBaseUrlIsUsed() {
        endpoint.configure(config("http://127.0.0.1:8080/"));

        assertEquals("http://127.0.0.1:8080", CaptureEndpoint.baseUrl());
    }

    @Test
    @DisplayName("a blank value is the same as not configuring it")
    void blankIsTreatedAsAbsent() {
        // An operator who comments the value out leaves `capture.baseUrl =` behind, and that must
        // mean "detect it" rather than "address the empty string".
        endpoint.configure(config("   "));

        assertNull(CaptureEndpoint.baseUrl());
    }

    @Test
    @DisplayName("a configuration change takes effect without a restart")
    void configureCanBeCalledAgain() {
        // This is the point of the move: @Modified re-invokes configure on a live component, so a
        // corrected port applies in seconds. A system property could not do this at all.
        endpoint.configure(config("http://127.0.0.1:8009"));
        assertEquals("http://127.0.0.1:8009", CaptureEndpoint.baseUrl());

        endpoint.configure(config("http://127.0.0.1:8080"));
        assertEquals("http://127.0.0.1:8080", CaptureEndpoint.baseUrl());
    }

    @Test
    @DisplayName("deactivation stops the configured endpoint being used")
    void clearForgetsTheEndpoint() {
        // A capture still in flight must not address configuration the platform has withdrawn.
        endpoint.configure(config("http://127.0.0.1:8080"));
        endpoint.clear();

        assertNull(CaptureEndpoint.baseUrl());
    }

    @Test
    @DisplayName("a non-loopback endpoint is still accepted, having been warned about")
    void nonLoopbackIsAcceptedNotRefused() {
        // Refusing it would take out an exotic-but-working setup -- a container whose HTTP
        // connector genuinely is not on loopback. It warns instead, at configuration time as well
        // as at fetch time, and the value is still used.
        endpoint.configure(config("https://academypp.jahia.com"));

        assertEquals("https://academypp.jahia.com", CaptureEndpoint.baseUrl());
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("https://academypp.jahia.com"),
                "and that is the predicate the warning is driven by");
    }
}
