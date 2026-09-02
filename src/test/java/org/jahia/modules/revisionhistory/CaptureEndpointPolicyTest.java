package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that decide what capture may address and whose credential it may use.
 *
 * <p>Both were security defects rather than preferences. {@code capture.baseUrl} is writable by a
 * site administrator, so "which host does this node issue a GET to" is a site-scoped decision; and
 * the credential precedence decides what a permanent, publicly-served record ends up containing.
 */
class CaptureEndpointPolicyTest {

    private final CaptureIdentity identity = new CaptureIdentity();

    @AfterEach
    void tearDown() {
        identity.clear();
    }

    private static SiteCaptureSettings site(String captureUser, String authorization) {
        return new SiteCaptureSettings("digitall", true, 10, captureUser, authorization, null);
    }

    private void configureModuleWideCredential() {
        Map<String, Object> config = new HashMap<>();
        config.put(CaptureIdentity.PROP_USER, "crh-module-wide");
        config.put(CaptureIdentity.PROP_SECRET, "not-a-real-secret");
        identity.configure(config);
        assertNotNull(CaptureIdentity.authorization(), "precondition");
    }

    // ------------------------------------------------------------ which host capture may address

    @Test
    @DisplayName("save() refuses an endpoint that does not address this node")
    void refusesAnOutwardFacingEndpoint() {
        // A site administrator setting this to a host they control would otherwise get an
        // arbitrary outbound GET from inside the network AND that host's response stored as the
        // site's public revision history.
        SiteSettingsRegistry registry = new SiteSettingsRegistry();
        SiteCaptureSettings outward = new SiteCaptureSettings(
                "digitall", true, 10, null, null, "http://169.254.169.254");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.save(outward));

        assertTrue(refused.getMessage().contains(SiteSettingsRegistry.PROP_BASE_URL),
                "the refusal has to name the setting: " + refused.getMessage());
    }

    @Test
    @DisplayName("save() refuses a retention cap the mechanism cannot honour")
    void refusesACapBelowTheAchievableFloor() {
        // prune never deletes a page's newest snapshot, so a cap of 1 settles at 2 forever.
        // Accepting 1 advertised a retention level that could not be delivered.
        SiteSettingsRegistry registry = new SiteSettingsRegistry();
        SiteCaptureSettings tooLow = new SiteCaptureSettings("digitall", true, 1, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> registry.save(tooLow));
    }

    @Test
    @DisplayName("A loopback endpoint is accepted whichever literal spelling it uses")
    void acceptsEveryLoopbackSpelling() {
        for (String accepted : new String[]{
                "http://127.0.0.1:8080", "http://localhost:8080", "http://[::1]:8080",
                "https://127.0.0.1:8443", "http://127.0.0.1:8080/jahia"}) {
            assertTrue(SiteSettingsRegistry.addressesThisNode(accepted), accepted);
        }
        for (String refused : new String[]{
                "http://169.254.169.254", "https://www.example.com",
                // Resolves wherever its DNS says, which is not this machine.
                "http://localhost.example.com:8080",
                // The whole 127/8 range is loopback to the kernel, but only this node's own
                // connector is the thing being authorised.
                "http://127.0.0.2:8080"}) {
            assertFalse(SiteSettingsRegistry.addressesThisNode(refused), refused);
        }
    }

    // ------------------------------------------------------------------ whose credential is used

    @Test
    @DisplayName("A site naming its own account does NOT fall back to the module-wide credential")
    void aSiteWithItsOwnAccountNeverInheritsTheGlobalOne() {
        // The rule the README states: a user configured without a usable secret leaves capture
        // anonymous. The fallback inverted it, so a site deliberately scoped to a narrow account
        // was captured with the broad one the moment its own secret stopped resolving -- and every
        // snapshot then permanently held content that account was kept away from.
        configureModuleWideCredential();

        SiteCaptureSettings scopedButUnresolved = site("crh-site-only", null);

        assertNull(GuestMarkdownFetcher.authorizationFor(scopedButUnresolved),
                "naming an account whose secret did not resolve must mean anonymous, not 'use the"
                + " module-wide account instead'");
        assertNull(scopedButUnresolved.getEffectiveCaptureUser(),
                "and the record must not name an account that never authenticated anything");
    }

    @Test
    @DisplayName("A site with no account of its own inherits the module-wide credential, and says so")
    void aSiteWithNoAccountInheritsAndReportsIt() {
        // The other half, and the reason the panel had to change: reporting this site as
        // "anonymous" told a site administrator that its snapshots hold only public text.
        configureModuleWideCredential();

        SiteCaptureSettings inheriting = site(null, null);

        assertNotNull(GuestMarkdownFetcher.authorizationFor(inheriting));
        assertTrue(inheriting.hasEffectiveCredential(),
                "the panel must not call this site anonymous when its captures authenticate");
        assertEquals("crh-module-wide", inheriting.getEffectiveCaptureUser());
    }

    @Test
    @DisplayName("A site's own resolved credential wins over the module-wide one")
    void aSiteOwnCredentialWins() {
        configureModuleWideCredential();

        SiteCaptureSettings own = site("crh-site-only", "Basic site-header");

        assertEquals("Basic site-header", GuestMarkdownFetcher.authorizationFor(own));
        assertEquals("crh-site-only", own.getEffectiveCaptureUser());
    }

    // -------------------------------------------------------- a refused credential is not policy

    @Test
    @DisplayName("A configured account that is refused is a misconfiguration, not a private page")
    void aRefusedConfiguredAccountIsNotReportedAsNotPublic() {
        // NOT_PUBLIC reads as "working as intended, this page is private". With an account
        // configured precisely so restricted pages COULD be captured, a refusal is the opposite of
        // working as intended, and filing it under NOT_PUBLIC left the history empty for exactly
        // the pages the setting was added to cover.
        for (int refusal : new int[]{401, 403, 404}) {
            assertEquals(CaptureStatus.FAILED,
                    GuestMarkdownFetcher.statusForHttp(refusal, true),
                    "HTTP " + refusal + " with a capture principal configured");
            assertEquals(CaptureStatus.NOT_PUBLIC,
                    GuestMarkdownFetcher.statusForHttp(refusal, false),
                    "HTTP " + refusal + " rendering anonymously");
        }
        assertTrue(GuestMarkdownFetcher.renderFailureMessage(403, true)
                        .contains(CaptureIdentity.PROP_USER),
                "the message has to name the setting an operator would change");
    }
}
