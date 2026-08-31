package org.jahia.modules.revisionhistory;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-site capture configuration, one file per site.
 *
 * <h2>Why a ManagedServiceFactory over a file, and not ConfigurationAdmin</h2>
 * A factory configuration created through {@code ConfigurationAdmin.createFactoryConfiguration} is
 * bundle-scoped: it lives under {@code bundleNN/data/config} and is orphaned or dropped when the
 * bundle is reinstalled, or when a default {@code .cfg} later ships for the same factory pid. That
 * loses a site's configuration on a restart, silently. A file under {@code karaf/etc} is delivered
 * by Felix FileInstall to {@link #updated}, survives reinstalls, and is what an administrator can
 * back up, diff and put in configuration management.
 *
 * <p>Each instance is {@code <karaf.etc>/org.jahia.modules.revisionhistory.site-<siteKey>.cfg},
 * keyed by the {@code siteKey} property inside it rather than by the file name: the name is a
 * convention for humans, the property is what this trusts.
 *
 * <h2>Fallback</h2>
 * A site with no file of its own gets {@link SiteCaptureSettings#DEFAULTS}, which reproduces exactly
 * what the module did before per-site settings existed, with the global capture principal from
 * {@link CaptureIdentity}. An upgrade must not change what gets captured.
 */
@Component(
        service = {SiteSettingsRegistry.class, ManagedServiceFactory.class},
        property = "service.pid=" + SiteSettingsRegistry.FACTORY_PID,
        immediate = true)
public class SiteSettingsRegistry implements ManagedServiceFactory {

    static final String FACTORY_PID = "org.jahia.modules.revisionhistory.site";

    private static final Logger logger = LoggerFactory.getLogger(SiteSettingsRegistry.class);

    static final String PROP_SITE_KEY = "siteKey";
    static final String PROP_ENABLED = "capture.enabled";
    static final String PROP_MAX_SNAPSHOTS = "retention.maxSnapshots";
    static final String PROP_USER = "capture.user";
    static final String PROP_SECRET = "capture.secret";
    static final String PROP_SECRET_FILE = "capture.secretFile";
    static final String PROP_BASE_URL = "capture.baseUrl";

    /**
     * A site key must be one safe path segment, because it is interpolated into a file name.
     * Jahia site keys are already restricted to this, so nothing legitimate is refused.
     */
    private static final Pattern SAFE_SITE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /** siteKey -> settings. Written on a configuration-admin thread, read on Quartz ones. */
    private final Map<String, SiteCaptureSettings> bySite = new ConcurrentHashMap<>();
    /** OSGi pid -> siteKey, so {@link #deleted} knows which site a pid was for. */
    private final Map<String, String> siteByPid = new ConcurrentHashMap<>();

    /**
     * The active component, for callers that cannot be injected.
     *
     * <p>The consumers of this are a Quartz job and a service instantiated reflectively by the
     * backfill script, neither of which DS can inject into. {@link CaptureIdentity} solves the same
     * problem the same way, and doing it differently here would leave two patterns for one need.
     */
    private static volatile SiteSettingsRegistry instance;

    @Activate
    void activate() {
        instance = this;
    }

    @Deactivate
    void deactivate() {
        instance = null;
    }

    /**
     * @return the settings for a site, or the module defaults when the component is not running.
     *         Never null: capture must keep working while configuration is being replaced.
     */
    /** @return the running component, or null when the module is not active on this node */
    public static SiteSettingsRegistry active() {
        return instance;
    }

    public static SiteCaptureSettings settingsFor(String siteKey) {
        SiteSettingsRegistry current = instance;
        return current == null ? SiteCaptureSettings.DEFAULTS : current.forSite(siteKey);
    }

    @Override
    public String getName() {
        return "Content Revision History per-site settings";
    }

    @Override
    public void updated(String pid, Dictionary<String, ?> properties) throws ConfigurationException {
        String siteKey = string(properties, PROP_SITE_KEY);
        if (siteKey == null) {
            throw new ConfigurationException(PROP_SITE_KEY,
                    "required: it is what says which site this file configures");
        }
        if (!SAFE_SITE_KEY.matcher(siteKey).matches()) {
            throw new ConfigurationException(PROP_SITE_KEY,
                    "'" + siteKey + "' is not a valid site key");
        }
        SiteCaptureSettings settings = new SiteCaptureSettings(
                siteKey,
                bool(properties, PROP_ENABLED, SiteCaptureSettings.DEFAULTS.isCaptureEnabled()),
                positiveInt(properties, PROP_MAX_SNAPSHOTS,
                        SiteCaptureSettings.DEFAULTS.getMaxSnapshots()),
                string(properties, PROP_USER),
                CaptureIdentity.authorizationFrom(
                        string(properties, PROP_USER),
                        string(properties, PROP_SECRET_FILE),
                        string(properties, PROP_SECRET)),
                endpoint(string(properties, PROP_BASE_URL), siteKey));
        bySite.put(siteKey, settings);
        siteByPid.put(pid, siteKey);
        logger.info("Applied per-site revision capture settings: {}", settings);
    }

    @Override
    public void deleted(String pid) {
        String siteKey = siteByPid.remove(pid);
        if (siteKey != null) {
            bySite.remove(siteKey);
            logger.info("Removed per-site revision capture settings for {}; it falls back to the"
                    + " module defaults.", siteKey);
        }
    }

    /**
     * @return this site's settings, or the defaults when it has none of its own. Never null: a
     *         caller must not have to decide what an absent configuration means.
     */
    public SiteCaptureSettings forSite(String siteKey) {
        if (siteKey == null) {
            return SiteCaptureSettings.DEFAULTS;
        }
        SiteCaptureSettings configured = bySite.get(siteKey);
        return configured != null ? configured : SiteCaptureSettings.DEFAULTS;
    }

    /** @return true when this site has a configuration file of its own */
    public boolean isConfigured(String siteKey) {
        return siteKey != null && bySite.containsKey(siteKey);
    }

    /**
     * A site's own capture endpoint, warned about on the same terms as the node-level one.
     *
     * <p>Accepted rather than refused, because an exotic-but-real setup may need it. Warned about
     * because the value people reach for -- the site's public address -- is the one that cannot
     * work: a public host rewrites or refuses the /cms/render/... paths capture asks for, and every
     * capture then reports FAILED on a flat 404.
     */
    private static String endpoint(String configured, String siteKey) {
        if (configured == null) {
            return null;
        }
        String trimmed = configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1) : configured;
        if (!GuestMarkdownFetcher.reachesJahiaDirectly(trimmed)) {
            logger.warn("{} for site {} is {}, which is not this node's own connector. Capture asks"
                    + " for /cms/render/... paths, and a public host rewrites or refuses those (SEO"
                    + " rewriting, a reverse proxy), so every capture for this site will report"
                    + " FAILED on a flat HTTP 404 whatever the page. Fetching over loopback does not"
                    + " put 127.0.0.1 into a snapshot -- site-relative links stay relative -- so the"
                    + " public address buys nothing here.", PROP_BASE_URL, siteKey, trimmed);
        }
        return trimmed;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes a site's configuration file, atomically.
     *
     * <p>Temp file then {@code ATOMIC_MOVE}, so FileInstall never sees a half-written file and
     * delivers a configuration missing half its properties. The move target is derived from the
     * validated site key, never from caller-supplied text.
     */
    public void save(SiteCaptureSettings settings) throws IOException {
        String siteKey = settings.getSiteKey();
        Path target = configFile(siteKey);
        StringBuilder body = new StringBuilder()
                .append("# Content Revision History -- settings for site ").append(siteKey)
                .append("\n# Written by the module. Safe to edit by hand; changes apply without a restart.\n\n")
                .append(PROP_SITE_KEY).append(" = ").append(siteKey).append('\n')
                .append(PROP_ENABLED).append(" = ").append(settings.isCaptureEnabled()).append('\n')
                .append(PROP_MAX_SNAPSHOTS).append(" = ").append(settings.getMaxSnapshots()).append('\n');
        if (settings.getCaptureUser() != null) {
            body.append(PROP_USER).append(" = ").append(settings.getCaptureUser()).append('\n');
        }
        // Omitted rather than written empty when unset: an empty value would read back as an empty
        // string and override the node default, whereas an absent key means "use the default".
        if (settings.getBaseUrl() != null) {
            body.append(PROP_BASE_URL).append(" = ").append(settings.getBaseUrl()).append('\n');
        }
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        Files.write(temp, body.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            // Some filesystems refuse ATOMIC_MOVE across the same directory only in theory, but a
            // failed save must not leave the temp file behind either way.
            Files.deleteIfExists(temp);
            throw atomicUnsupported;
        }
        logger.info("Wrote per-site revision capture settings for {} to {}", siteKey, target);
    }

    /** Removes a site's configuration file, after which the site falls back to the defaults. */
    public void delete(String siteKey) throws IOException {
        Path target = configFile(siteKey);
        if (Files.deleteIfExists(target)) {
            logger.info("Deleted per-site revision capture settings for {}", siteKey);
        }
    }

    /**
     * The file one site's configuration lives in.
     *
     * <p>The site key is validated before it reaches the file name. A key carrying a separator or a
     * {@code ..} would otherwise let a caller name a path outside the configuration directory.
     */
    Path configFile(String siteKey) {
        if (siteKey == null || !SAFE_SITE_KEY.matcher(siteKey).matches()) {
            throw new IllegalArgumentException("Not a valid site key: " + siteKey);
        }
        return etcDirectory().resolve(FACTORY_PID + '-' + siteKey + ".cfg");
    }

    private static Path etcDirectory() {
        String etc = System.getProperty("karaf.etc");
        if (etc != null && !etc.trim().isEmpty()) {
            return Paths.get(etc.trim());
        }
        String home = System.getProperty("karaf.home");
        if (home != null && !home.trim().isEmpty()) {
            return Paths.get(home.trim(), "etc");
        }
        throw new IllegalStateException("Neither karaf.etc nor karaf.home is set,"
                + " so there is nowhere to write a configuration file");
    }

    // ------------------------------------------------------------------ property reading

    private static String string(Dictionary<String, ?> properties, String key) {
        Object raw = properties == null ? null : properties.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean bool(Dictionary<String, ?> properties, String key, boolean fallback) {
        String value = string(properties, key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /**
     * A non-positive or unparseable retention would either prune everything or throw on every
     * capture, so it falls back and says so rather than being honoured.
     */
    private static int positiveInt(Dictionary<String, ?> properties, String key, int fallback) {
        String value = string(properties, key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
            logger.warn("{} = {} is not positive; using {}", key, parsed, fallback);
        } catch (NumberFormatException notANumber) {
            logger.warn("{} = '{}' is not a number; using {}", key, value, fallback);
        }
        return fallback;
    }

    /** Exposed for tests: enumerating a Dictionary is awkward enough to be worth one helper. */
    static Dictionary<String, Object> keys(Dictionary<String, Object> properties) {
        Enumeration<String> names = properties.keys();
        while (names.hasMoreElements()) {
            names.nextElement();
        }
        return properties;
    }
}
