package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.service.cm.ConfigurationException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        // per-site file keeps using the module-wide account exactly as before. Asserted positively:
        // the previous version asserted null, which only proved that no test before it had left a
        // global credential behind -- and one did, whenever CaptureIdentityTest ran first (#35).
        CaptureIdentity global = new CaptureIdentity();
        try {
            java.util.Map<String, Object> config = new java.util.HashMap<>();
            config.put(CaptureIdentity.PROP_USER, "crh-global");
            config.put(CaptureIdentity.PROP_SECRET, "s3cr3t");
            global.configure(config);
            registry.deactivate();

            assertEquals(CaptureIdentity.authorization(), GuestMarkdownFetcher.authorizationFor("academy"),
                    "a site with no file of its own captures as the module-wide account");
            assertNotNull(GuestMarkdownFetcher.authorizationFor("academy"));
        } finally {
            global.configure(null);
        }
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

    // ------------------------------------------------------------------ writing the file

    /**
     * Every field the panel can edit has to survive save-then-reload.
     *
     * <p>Written after capture.baseUrl did not. The resolver accepted it, withChanges carried it and
     * the mutation returned it, so a test of any one of those passed; save() simply never wrote the
     * key, and the value came back null on the next read. Only a round trip through the file catches
     * a field that is missing from the writer, which is why this asserts on a reloaded registry
     * rather than on the object it just saved.
     */
    @Test
    @DisplayName("every edited field survives save and reload, baseUrl included")
    void saveRoundTripsEveryField(@TempDir Path etc) throws Exception {
        String previousEtc = System.getProperty("karaf.etc");
        System.setProperty("karaf.etc", etc.toString());
        try {
            SiteCaptureSettings edited = SiteCaptureSettings.DEFAULTS
                    .withChanges("academy", false, 7, "crh-academy", "http://127.0.0.1:8080");
            registry.save(edited);

            Path written = registry.configFile("academy");
            assertTrue(Files.exists(written), "save must produce the site's file");

            // Reload through the same path Felix FileInstall uses, so the assertion covers the
            // writer and the reader together.
            java.util.Properties reloaded = new java.util.Properties();
            try (java.io.InputStream in = Files.newInputStream(written)) {
                reloaded.load(in);
            }
            Hashtable<String, Object> asProps = new Hashtable<>();
            reloaded.stringPropertyNames().forEach(k -> asProps.put(k, reloaded.getProperty(k)));

            SiteSettingsRegistry reader = new SiteSettingsRegistry();
            reader.updated("pid-reload", asProps);
            SiteCaptureSettings back = reader.forSite("academy");

            assertEquals("http://127.0.0.1:8080", back.getBaseUrl(),
                    "baseUrl must be written, not just accepted");
            assertEquals(7, back.getMaxSnapshots());
            assertEquals("crh-academy", back.getCaptureUser());
            assertFalse(back.isCaptureEnabled());
        } finally {
            if (previousEtc == null) {
                System.clearProperty("karaf.etc");
            } else {
                System.setProperty("karaf.etc", previousEtc);
            }
        }
    }

    /** An unset baseUrl must stay absent, so the node default keeps applying. */
    @Test
    @DisplayName("an unset baseUrl is omitted from the file rather than written empty")
    void unsetBaseUrlIsOmitted(@TempDir Path etc) throws Exception {
        String previousEtc = System.getProperty("karaf.etc");
        System.setProperty("karaf.etc", etc.toString());
        try {
            registry.save(SiteCaptureSettings.DEFAULTS
                    .withChanges("academy", true, 10, null, null));

            String body = new String(Files.readAllBytes(registry.configFile("academy")),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(body.contains(SiteSettingsRegistry.PROP_BASE_URL + " ="),
                    "an absent key means the node default applies; an empty value would override it");
        } finally {
            if (previousEtc == null) {
                System.clearProperty("karaf.etc");
            } else {
                System.setProperty("karaf.etc", previousEtc);
            }
        }
    }

    // ------------------------------------------------------------------ #3: excluded properties

    @Test
    @DisplayName("#3: capture.excludedProperties parses into a set, split on commas and whitespace")
    void excludedPropertiesAreParsed() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_EXCLUDED_PROPERTIES, "internalNote, secret  note"));

        assertEquals(
                new java.util.HashSet<>(java.util.Arrays.asList("internalNote", "secret", "note")),
                new java.util.HashSet<>(registry.forSite("academy").getExcludedProperties()));
        assertTrue(registry.forSite("digitall").getExcludedProperties().isEmpty(),
                "a site with no list of its own excludes nothing -- the default publishes everything");
    }

    @Test
    @DisplayName("#3: a hand-added capture.excludedProperties survives a panel save, like capture.secret")
    void excludedPropertiesArePreservedAcrossSave(@TempDir Path etc) throws Exception {
        String previousEtc = System.getProperty("karaf.etc");
        System.setProperty("karaf.etc", etc.toString());
        try {
            // An administrator hand-adds the advanced setting to the site's file.
            Path file = registry.configFile("academy");
            Files.write(file, ("siteKey = academy\ncapture.enabled = true\n"
                    + "capture.excludedProperties = internalNote\n")
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

            // A panel save edits an unrelated field. Because the key is NOT managed, it must survive
            // -- the same guarantee capture.secret has, and for the same reason (GHSA-q67w-prc3-ch5h #3).
            registry.save(SiteCaptureSettings.DEFAULTS.withChanges("academy", false, 9, null, null));

            String body = new String(Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("capture.excludedProperties = internalNote"),
                    "the advanced setting must be preserved verbatim across a save, not dropped: " + body);
        } finally {
            if (previousEtc == null) {
                System.clearProperty("karaf.etc");
            } else {
                System.setProperty("karaf.etc", previousEtc);
            }
        }
    }

    // ------------------------------------------------------------------ component lifecycle

    @Test
    @DisplayName("a stopping instance does not clear a newer one that already activated")
    void deactivateDoesNotClearALiveReplacement() {
        // During a bundle refresh the replacement activates before the old instance stops. An
        // unconditional null would then clear the LIVE instance, and settingsFor would fall back to
        // the module defaults for every site until the next activation -- silently.
        SiteSettingsRegistry old = new SiteSettingsRegistry();
        SiteSettingsRegistry replacement = new SiteSettingsRegistry();
        old.activate();
        replacement.activate();

        old.deactivate();

        assertSame(replacement, SiteSettingsRegistry.active(),
                "the instance that is actually running must survive the other one stopping");
        replacement.deactivate();
        assertNull(SiteSettingsRegistry.active(), "and the last one out does clear it");
    }

    @Test
    @DisplayName("the capture principal of record names the per-site account when one authenticated")
    void principalOfRecordNamesThePerSiteAccount() throws Exception {
        SiteSettingsRegistry running = new SiteSettingsRegistry();
        running.activate();
        try {
            running.updated("pid-1", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                    SiteSettingsRegistry.PROP_USER, "crh-academy",
                    SiteSettingsRegistry.PROP_SECRET, "s3cret"));

            SiteCaptureSettings site = SiteSettingsRegistry.settingsFor("academy");
            assertNotNull(site.getAuthorization(), "the fixture must actually resolve a credential");
            assertEquals("crh-academy",
                    GuestMarkdownFetcher.principalFor(site, site.getAuthorization()),
                    "the render authenticated as this account, so the snapshot must say so");
        } finally {
            running.deactivate();
        }
    }

    @Test
    @DisplayName("and records guest when the configured account had no credential to send")
    void principalOfRecordSaysGuestWhenNothingResolved() throws Exception {
        // The regression this guards: a configured capture.user whose secret does not resolve makes
        // the fetch ANONYMOUS, because authorizationFor returns null and no header is sent. Recording
        // the account name then told an editor a plain guest render had privileged provenance --
        // and the inverse case told them restricted content was safe to publish.
        SiteSettingsRegistry running = new SiteSettingsRegistry();
        running.activate();
        try {
            running.updated("pid-1", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                    SiteSettingsRegistry.PROP_USER, "crh-academy"));

            SiteCaptureSettings site = SiteSettingsRegistry.settingsFor("academy");
            assertNull(site.getAuthorization(),
                    "a user without a secret must resolve no credential at all");
            // No header goes out, so the render is anonymous whatever the configuration says.
            assertEquals(RevisionHistoryConstants.CAPTURE_PRINCIPAL,
                    GuestMarkdownFetcher.principalFor(site, null),
                    "no credential resolved, so the render really was anonymous");
        } finally {
            running.deactivate();
        }
    }

    // ------------------------------------------------------------------ writing safely

    /** Runs a body with karaf.etc pointed at a temp directory, restoring it afterwards. */
    private void withEtc(Path etc, ThrowingRunnable body) throws Exception {
        String previous = System.getProperty("karaf.etc");
        System.setProperty("karaf.etc", etc.toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty("karaf.etc");
            } else {
                System.setProperty("karaf.etc", previous);
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("a newline in a written value is refused, because it would forge further settings")
    void newlineInAValueIsRefused(@TempDir Path etc) throws Exception {
        // The file is a properties file, so a value carrying a newline stops being a value: the
        // rest is parsed as more keys. These values come from GraphQL, from a site administrator,
        // so without this one could inject capture.secretFile and have the module read a server
        // file and send its first line to a host they also control, or re-key the file onto a site
        // they administer nothing on.
        withEtc(etc, () -> {
            SiteCaptureSettings injected = SiteCaptureSettings.DEFAULTS.withChanges(
                    "academy", true, 10, "x\ncapture.secretFile = /etc/shadow", null);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> registry.save(injected));
            assertTrue(refused.getMessage().contains("line break"), refused.getMessage());
            assertFalse(Files.exists(registry.configFile("academy")),
                    "nothing may be written when a value is refused");
        });
    }

    @Test
    @DisplayName("a carriage return is refused too, not just a newline")
    void carriageReturnIsRefused(@TempDir Path etc) throws Exception {
        // Injected through capture.user with NO baseUrl. The previous version put the \r in a
        // non-loopback baseUrl, so the exception it caught came from the SSRF guard that runs
        // first in save(), and the \r check itself was never exercised (#34).
        withEtc(etc, () -> {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> registry.save(SiteCaptureSettings.DEFAULTS
                            .withChanges("academy", true, 10, "x\rcapture.secretFile = /etc/shadow", null)));
            assertTrue(refused.getMessage().contains("line break"), refused.getMessage());
            assertFalse(Files.exists(registry.configFile("academy")));
        });
    }

    @Test
    @DisplayName("a backslash is refused: it is the properties escape character")
    void backslashInAValueIsRefused(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> registry.save(SiteCaptureSettings.DEFAULTS
                            .withChanges("academy", true, 10, "DOMAIN\\svc_capture", null)));
            assertTrue(refused.getMessage().contains("backslash"), refused.getMessage());
            assertFalse(Files.exists(registry.configFile("academy")));
        });
    }

    // ------------------------------------------------------------------ review-pass fixes

    @Test
    @DisplayName("#21: save writes back to the file that delivered the site, not to a second file")
    void saveWritesBackToTheDeliveringFile(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            // An operator-named file, with a hand-added secret the module does not manage.
            Path prod = etc.resolve(SiteSettingsRegistry.FACTORY_PID + "-corp-prod.cfg");
            Files.write(prod, java.util.Arrays.asList(
                    "siteKey = corp", "capture.user = svc", "capture.secret = hunter2"));
            registry.updated("pid-prod", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "corp",
                    SiteSettingsRegistry.PROP_USER, "svc",
                    SiteSettingsRegistry.PROP_SECRET, "hunter2",
                    SiteSettingsRegistry.PROP_FILEINSTALL_FILENAME, prod.toUri().toString()));

            registry.save(registry.forSite("corp").withChanges("corp", false, 10, "svc", null));

            Path canonical = etc.resolve(SiteSettingsRegistry.FACTORY_PID + "-corp.cfg");
            assertFalse(Files.exists(canonical), "a second file would compete with the first across restarts");
            String rewritten = new String(Files.readAllBytes(prod), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(rewritten.contains("capture.enabled = false"), rewritten);
            assertTrue(rewritten.contains("capture.secret = hunter2"), "the secret must be carried: " + rewritten);

            registry.delete("corp");
            assertFalse(Files.exists(prod), "Use defaults must remove the file that configured the site");
        });
    }

    @Test
    @DisplayName("#21: a fileinstall name that is not a .cfg leaves the site on the conventional name")
    void unusableDeliveringNameFallsBackToTheConvention(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            registry.updated("pid-1", props(
                    SiteSettingsRegistry.PROP_SITE_KEY, "corp",
                    SiteSettingsRegistry.PROP_FILEINSTALL_FILENAME, "not a file"));

            assertEquals(etc.resolve(SiteSettingsRegistry.FACTORY_PID + "-corp.cfg"), registry.configFile("corp"));
            assertNull(SiteSettingsRegistry.fileInstallPath(null));
            assertNull(SiteSettingsRegistry.fileInstallPath("file:/x/y.properties"));
        });
    }

    @Test
    @DisplayName("#28: delete clears the in-memory settings immediately")
    void deleteClearsTheInMemorySettings(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            registry.save(SiteCaptureSettings.DEFAULTS.withChanges("academy", false, 5, null, null));
            assertTrue(registry.isConfigured("academy"));

            registry.delete("academy");

            assertFalse(registry.isConfigured("academy"),
                    "the panel refetches at once and must not see the deleted settings");
            assertSame(SiteCaptureSettings.DEFAULTS, registry.forSite("academy"));
        });
    }

    @Test
    @DisplayName("#29: a site key with a leading underscore or hyphen is accepted, as capture accepts it")
    void siteKeysCaptureAcceptsCanBeConfigured(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            registry.updated("pid-1", props(SiteSettingsRegistry.PROP_SITE_KEY, "_intranet"));
            registry.save(registry.forSite("_intranet").withChanges("_intranet", false, 5, null, null));
            assertTrue(Files.exists(registry.configFile("_intranet")));
            assertTrue(Files.exists(etc.resolve(SiteSettingsRegistry.FACTORY_PID + "-_intranet.cfg")));
            registry.save(SiteCaptureSettings.DEFAULTS.withChanges("-legacy", false, 5, null, null));
            assertTrue(Files.exists(registry.configFile("-legacy")));
            // Still one safe path segment: no dots, no separators.
            assertThrows(IllegalArgumentException.class, () -> registry.configFile("a.b"));
            assertThrows(IllegalArgumentException.class, () -> registry.configFile("a/b"));
        });
    }

    @Test
    @DisplayName("#31: the preserved-lines banner is written once, however many times the file is saved")
    void bannerIsNotDuplicatedAcrossSaves(@TempDir Path etc) throws Exception {
        withEtc(etc, () -> {
            Path file = registry.configFile("academy");
            Files.write(file, java.util.Arrays.asList("siteKey = academy", "capture.secret = s"));
            for (int i = 0; i < 4; i++) {
                registry.save(SiteCaptureSettings.DEFAULTS.withChanges("academy", true, 10 + i, null, null));
            }

            java.util.List<String> lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.ISO_8859_1);
            long banners = lines.stream().filter(l -> l.trim().equals(SiteSettingsRegistry.PRESERVED_BANNER)).count();
            assertEquals(1, banners, String.join("\n", lines));
            assertEquals(1, lines.stream().filter(l -> l.startsWith("capture.secret")).count());
        });
    }

    @Test
    @DisplayName("#32: a retention below the floor in the file is raised to the floor")
    void retentionBelowTheFloorIsRaised() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "academy",
                SiteSettingsRegistry.PROP_MAX_SNAPSHOTS, "1"));

        assertEquals(SiteSettingsRegistry.MIN_MAX_SNAPSHOTS, registry.forSite("academy").getMaxSnapshots(),
                "1 cannot be honoured: prune never deletes the newest snapshot");
    }

    @Test
    @DisplayName("An operator's comment on a preserved key is preserved with it")
    void saveKeepsCommentsOnUnmanagedKeys(@TempDir Path etc) throws Exception {
        // The secret survived a save; the note explaining it did not. What that costs is the
        // provenance of a live credential -- who owns it, when it was rotated -- silently, with
        // nothing recording that anything was dropped. A comment run travels with the key it sits
        // above, and a run above a key this module rewrites is discarded rather than left pointing
        // at a value it no longer describes.
        withEtc(etc, () -> {
            Path file = registry.configFile("academy");
            Files.write(file, ("siteKey = academy\n"
                    + "# owner: SecOps, rotate quarterly\n"
                    + "# last rotated 2026-01-05\n"
                    + "capture.secretFile = /opt/jahia/etc/crh.secret\n"
                    + "# this one describes a key the module rewrites\n"
                    + "capture.enabled = true\n").getBytes(StandardCharsets.UTF_8));

            registry.save(SiteCaptureSettings.DEFAULTS
                    .withChanges("academy", false, 25, null, null));

            String rewritten = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("# owner: SecOps, rotate quarterly"),
                    "the note explaining a hand-added credential must survive: " + rewritten);
            assertTrue(rewritten.contains("# last rotated 2026-01-05"),
                    "a whole comment run travels with its key: " + rewritten);
            assertTrue(rewritten.contains("capture.secretFile = /opt/jahia/etc/crh.secret"),
                    "and the key itself, obviously: " + rewritten);
            assertFalse(rewritten.contains("# this one describes a key the module rewrites"),
                    "a comment above a managed key would end up above a rewritten value it no"
                    + " longer matches: " + rewritten);
        });
    }

    @Test
    @DisplayName("saving keeps the credential an administrator added by hand")
    void saveKeepsUnmanagedKeys(@TempDir Path etc) throws Exception {
        // The bug this replaces: SiteCaptureSettings carries only the RESOLVED authorization
        // header, never the secret or its path, so a save rebuilt from it dropped both. An
        // administrator who hand-added a credential so a restricted site could be captured, then
        // toggled anything at all in the panel, lost it -- and capture fell back to anonymous with
        // no error, so restricted pages simply stopped being recorded.
        withEtc(etc, () -> {
            Path file = registry.configFile("academy");
            Files.write(file, ("siteKey = academy\n"
                    + "capture.enabled = true\n"
                    + "capture.user = crh-academy\n"
                    + "capture.secretFile = /opt/jahia/etc/crh.secret\n"
                    + "some.future.key = keep me\n").getBytes(StandardCharsets.UTF_8));

            registry.save(SiteCaptureSettings.DEFAULTS
                    .withChanges("academy", false, 25, "crh-academy", null));

            String rewritten = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("capture.secretFile = /opt/jahia/etc/crh.secret"),
                    "the hand-added secret path must survive a save: " + rewritten);
            assertTrue(rewritten.contains("some.future.key = keep me"),
                    "any key this module does not manage must survive too");
            // And the managed keys are the NEW values, not the old ones.
            assertTrue(rewritten.contains("capture.enabled = false"), rewritten);
            assertTrue(rewritten.contains("retention.maxSnapshots = 25"), rewritten);
            assertEquals(1, rewritten.split("capture.user", -1).length - 1,
                    "a managed key must appear once, not duplicated by preservation");
        });
    }

    @Test
    @DisplayName("re-pointing a file at another site releases the site it used to name")
    void changingTheSiteKeyReleasesThePreviousSite() throws Exception {
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "siteA",
                SiteSettingsRegistry.PROP_ENABLED, "false"));
        assertTrue(registry.isConfigured("siteA"));

        // The same file, edited to name a different site. It keeps its pid.
        registry.updated("pid-1", props(
                SiteSettingsRegistry.PROP_SITE_KEY, "siteB",
                SiteSettingsRegistry.PROP_ENABLED, "false"));

        // deleted() is never called here, so without an explicit release siteA kept the old
        // settings for the life of the process, from a file that no longer named it at all.
        assertFalse(registry.isConfigured("siteA"),
                "the site the file used to name must fall back to the module defaults");
        assertTrue(registry.isConfigured("siteB"));
        assertTrue(registry.forSite("siteA").isCaptureEnabled(),
                "and it must get the DEFAULTS, not the settings it used to have");
    }

    @Test
    @DisplayName("another file naming the same site keeps it configured")
    void aSecondFileKeepsTheSiteConfigured() throws Exception {
        registry.updated("pid-1", props(SiteSettingsRegistry.PROP_SITE_KEY, "shared"));
        registry.updated("pid-2", props(SiteSettingsRegistry.PROP_SITE_KEY, "shared"));

        registry.updated("pid-1", props(SiteSettingsRegistry.PROP_SITE_KEY, "moved"));

        // Releasing on any re-point would have dropped a site another file still names.
        assertTrue(registry.isConfigured("shared"),
                "pid-2 still names it, so it stays configured");
    }

    @Test
    @DisplayName("a site key the capture path would refuse is refused here too")
    void siteKeyRulesAgree() {
        // These two halves used to disagree: this class accepted a dot, the capture path rejected
        // it, so settings could be saved for a site whose every capture then failed validation.
        assertThrows(ConfigurationException.class, () -> registry.updated("pid-1",
                props(SiteSettingsRegistry.PROP_SITE_KEY, "example.com")));
        assertFalse(RevisionSnapshotService.isValidSiteKey("example.com"));
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
