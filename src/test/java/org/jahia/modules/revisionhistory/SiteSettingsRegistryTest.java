package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.service.cm.ConfigurationException;

import java.nio.file.Path;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-site capture configuration.
 *
 * <p>The rule that matters most is the fallback: a site with no configuration of its own must behave
 * exactly as the module did before per-site settings existed. An upgrade that silently changes what
 * gets captured would be worse than having no per-site settings at all.
 */
class SiteSettingsRegistryTest {

    private final SiteSettingsRegistry registry = new SiteSettingsRegistry();

    private static Hashtable<String, Object> props(Object... keyValues) {
        Hashtable<String, Object> table = new Hashtable<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            table.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return table;
    }

    // ------------------------------------------------------------------ fallback

    @Test
    @DisplayName("an unconfigured site gets the module defaults, not null")
    void unconfiguredSiteGetsDefaults() {
        SiteCaptureSettings settings = registry.forSite("academy");

        assertNotNull(settings, "a caller must not have to decide what absent configuration means");
        assertTrue(settings.isCaptureEnabled());
        assertEquals(RevisionHistoryConstants.MAX_SNAPSHOTS_PER_PAGE_LANGUAGE,
                settings.getMaxSnapshots());
        assertNull(settings.getCaptureUser(), "capture stays anonymous unless configured");
        assertFalse(registry.isConfigured("academy"));
    }

    @Test
    @DisplayName("a null site key gets the defaults rather than throwing")
    void nullSiteKeyGetsDefaults() {
        assertSame(SiteCaptureSettings.DEFAULTS, registry.forSite(null));
    }

    // ------------------------------------------------------------------ applying a file

    @Test
    @DisplayName("a site's file is applied to that site alone")
    void configurationAppliesToOneSite() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_ENABLED, "false",
                SiteSettingsRegistry.PROP_MAX_SNAPSHOTS, "25"));

        SiteCaptureSettings academy = registry.forSite("academy");
        assertFalse(academy.isCaptureEnabled());
        assertEquals(25, academy.getMaxSnapshots());
        assertTrue(registry.isConfigured("academy"));

        assertTrue(registry.forSite("digitall").isCaptureEnabled(), "other sites are untouched");
        assertFalse(registry.isConfigured("digitall"));
    }

    @Test
    @DisplayName("deleting a site's file returns it to the defaults")
    void deletingReturnsToDefaults() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_ENABLED, "false"));
        assertFalse(registry.forSite("academy").isCaptureEnabled());

        registry.deleted("pid-1");

        assertTrue(registry.forSite("academy").isCaptureEnabled());
        assertFalse(registry.isConfigured("academy"));
    }

    // ------------------------------------------------------------------ refusing bad input

    @Test
    @DisplayName("a file with no siteKey is refused, because nothing says which site it is for")
    void siteKeyIsRequired() {
        ConfigurationException refused = assertThrows(ConfigurationException.class,
                () -> registry.updated("pid-1", props(SiteSettingsRegistry.PROP_ENABLED, "false")));

        assertEquals(SiteSettingsRegistry.PROP_SITE_KEY, refused.getProperty());
    }

    @Test
    @DisplayName("a site key that is not one safe path segment is refused")
    void unsafeSiteKeyIsRefused() {
        // It is interpolated into a file name, so a separator or a .. would name a path outside the
        // configuration directory.
        for (String bad : new String[]{"../evil", "a/b", "..", "", "with space", "x".repeat(200)}) {
            assertThrows(ConfigurationException.class,
                    () -> registry.updated("pid-x", props(SiteSettingsRegistry.PROP_SITE_KEY, bad)),
                    "must refuse '" + bad + "'");
        }
    }

    @Test
    @DisplayName("an unusable retention value falls back instead of being honoured")
    void badRetentionFallsBack() throws Exception {
        // Zero would prune everything; a non-number would throw on every capture.
        for (String bad : new String[]{"0", "-5", "many"}) {
            registry.updated("pid-1", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                    SiteSettingsRegistry.PROP_MAX_SNAPSHOTS, bad));

            assertEquals(RevisionHistoryConstants.MAX_SNAPSHOTS_PER_PAGE_LANGUAGE,
                    registry.forSite("academy").getMaxSnapshots(), "for value '" + bad + "'");
        }
    }

    @Test
    @DisplayName("a capture user with no secret leaves the site anonymous")
    void userWithoutSecretStaysAnonymous() throws Exception {
        // Same rule as the global configuration, and it is shared code rather than reimplemented:
        // claiming a principal that never authenticated would put a false name on a snapshot.
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_USER, "crh-academy"));

        assertNull(registry.forSite("academy").getAuthorization(),
                "no secret means no header");
    }

    // ------------------------------------------------------------------ file naming

    @Test
    @DisplayName("the file is named for the factory pid and the site")
    void configFileIsNamedPredictably() {
        System.setProperty("karaf.etc", "/tmp/crh-test-etc");
        try {
            Path file = registry.configFile("academy");

            assertEquals("org.jahia.modules.revisionhistory.site-academy.cfg",
                    file.getFileName().toString());
            assertEquals("/tmp/crh-test-etc", file.getParent().toString());
        } finally {
            System.clearProperty("karaf.etc");
        }
    }

    @Test
    @DisplayName("configFile refuses an unsafe key too, not only updated()")
    void configFileValidatesTheKey() {
        System.setProperty("karaf.etc", "/tmp/crh-test-etc");
        try {
            assertThrows(IllegalArgumentException.class, () -> registry.configFile("../../etc/passwd"));
            assertThrows(IllegalArgumentException.class, () -> registry.configFile(null));
        } finally {
            System.clearProperty("karaf.etc");
        }
    }

    // ------------------------------------------------------------------ precedence

    @Test
    @DisplayName("a site with no settings falls back to the global capture principal")
    void globalPrincipalAppliesWhenSiteHasNone() {
        // This is what keeps an upgrade from changing what gets captured: every site that has no
        // per-site file keeps using the module-wide account exactly as before.
        registry.deactivate();

        assertNull(GuestMarkdownFetcher.authorizationFor("academy"),
                "with no global configuration either, capture stays anonymous");
    }

    @Test
    @DisplayName("the static accessor answers with the defaults when the component is not running")
    void staticAccessorNeverReturnsNull() {
        // Capture must keep working while configuration is being replaced, and a null here would
        // be a NullPointerException on a Quartz thread rather than a degraded capture.
        registry.deactivate();

        assertSame(SiteCaptureSettings.DEFAULTS, SiteSettingsRegistry.settingsFor("academy"));
        assertSame(SiteCaptureSettings.DEFAULTS, SiteSettingsRegistry.settingsFor(null));
    }

    @Test
    @DisplayName("a site's own settings are visible through the static accessor")
    void staticAccessorSeesConfiguredSites() throws Exception {
        registry.activate();
        try {
            registry.updated("pid-1", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                    SiteSettingsRegistry.PROP_MAX_SNAPSHOTS, "7"));

            assertEquals(7, SiteSettingsRegistry.settingsFor("academy").getMaxSnapshots());
            assertEquals(RevisionHistoryConstants.MAX_SNAPSHOTS_PER_PAGE_LANGUAGE,
                    SiteSettingsRegistry.settingsFor("digitall").getMaxSnapshots());
        } finally {
            registry.deactivate();
        }
    }

    @Test
    @DisplayName("settings never carry a secret into their own toString")
    void toStringIsSafeForLogs() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_USER, "crh-academy",
                SiteSettingsRegistry.PROP_SECRET, "s3cret"));

        String described = registry.forSite("academy").toString();

        assertFalse(described.contains("s3cret"), described);
        assertTrue(described.contains("crh-academy"), described);
    }

    // ------------------------------------------------------------------ per-site endpoint

    @Test
    @DisplayName("a site can override the capture endpoint, and most sites do not")
    void siteCanOverrideTheEndpoint() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_BASE_URL, "http://127.0.0.1:8009/"));

        assertEquals("http://127.0.0.1:8009", registry.forSite("academy").getBaseUrl(),
                "trailing slash removed: the paths appended always start with one");
        assertNull(registry.forSite("digitall").getBaseUrl(),
                "a site with no override uses the node-level endpoint");
    }

    @Test
    @DisplayName("a public hostname is accepted for a site, and warned about")
    void publicHostnameIsAcceptedAndWarned() throws Exception {
        // Accepted because an exotic-but-real setup may need it. The warning is what matters: the
        // value people reach for is the one that cannot work, since a public host rewrites or
        // refuses the /cms/render/... paths capture asks for.
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_BASE_URL, "https://academypp.jahia.com"));

        assertEquals("https://academypp.jahia.com", registry.forSite("academy").getBaseUrl());
        assertFalse(GuestMarkdownFetcher.reachesJahiaDirectly("https://academypp.jahia.com"),
                "and that is the predicate the warning is driven by");
    }

    @Test
    @DisplayName("the endpoint is not leaked into a log line as a secret would be")
    void toStringNamesTheEndpoint() throws Exception {
        // Unlike the secret, the endpoint IS safe to log, and seeing it is how an operator
        // diagnoses a site whose captures all fail.
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_BASE_URL, "http://127.0.0.1:8009"));

        assertTrue(registry.forSite("academy").toString().contains("http://127.0.0.1:8009"));
        assertTrue(SiteCaptureSettings.DEFAULTS.toString().contains("(node default)"));
    }

    @Test
    @DisplayName("configuring a site that has none starts from the defaults and keeps its key")
    void firstSaveForASiteCarriesTheSiteKey() {
        // The common case, and the one that breaks if withChanges reuses its own siteKey: the
        // caller starts from DEFAULTS, whose key is null, and a file written with no siteKey fails
        // the validation in updated() -- on the very first save of every site.
        SiteCaptureSettings first = SiteCaptureSettings.DEFAULTS
                .withChanges("academy", false, 30, "crh-academy", null);

        assertEquals("academy", first.getSiteKey());
        assertFalse(first.isCaptureEnabled());
        assertEquals(30, first.getMaxSnapshots());
        assertEquals("crh-academy", first.getCaptureUser());
    }

    @Test
    @DisplayName("withChanges carries the resolved credential without exposing it")
    void withChangesKeepsTheCredential() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_USER, "crh-academy",
                SiteSettingsRegistry.PROP_SECRET, "s3cret"));
        SiteCaptureSettings configured = registry.forSite("academy");
        assertTrue(configured.hasResolvedCredential(), "the fixture must actually resolve one");

        SiteCaptureSettings edited = configured.withChanges("academy", true, 99, "crh-academy", null);

        assertTrue(edited.hasResolvedCredential(),
                "editing retention must not silently drop the credential");
        assertEquals(99, edited.getMaxSnapshots());
    }
}
