package org.jahia.modules.revisionhistory;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

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
 * convention for humans, the property is what this trusts. Because the name is only a convention,
 * {@link #save} writes back to <em>the file that delivered the site</em> (FileInstall names it in
 * {@code felix.fileinstall.filename}), and uses the conventional name only for a site that has no
 * file yet. It used to derive the target from the site key alone, so a site configured from
 * {@code ...site-corp-prod.cfg} got a second {@code ...site-corp.cfg} on the first panel save, the
 * hand-added {@code capture.secret} was not carried into it, two files then competed for the site
 * across restarts, and "Use defaults" deleted only the new one (issue #21).
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

    /**
     * The lowest cap retention can actually deliver.
     *
     * <p>Not 1, though the previous message promised it was. {@code prune} never deletes a page's
     * newest snapshot, and it runs before the incoming one is written, so a cap of 1 leaves the
     * existing snapshot in place and the folder settles at 2 forever. Accepting 1 advertised a
     * retention level the mechanism cannot reach.
     */
    public static final int MIN_MAX_SNAPSHOTS = 2;

    /**
     * @return whether a capture endpoint addresses this node itself, which is the only thing
     *         {@link #save} accepts
     *
     * <p>Exists so the GraphQL layer can refuse the value with a validation error naming the field,
     * rather than letting {@code save} throw and surface as an opaque internal error. The rule
     * itself stays in {@code GuestMarkdownFetcher}, which is the class that has to live by it.
     */
    public static boolean addressesThisNode(String baseUrl) {
        return GuestMarkdownFetcher.reachesJahiaDirectly(baseUrl);
    }
    static final String PROP_USER = "capture.user";
    static final String PROP_SECRET = "capture.secret";
    static final String PROP_SECRET_FILE = "capture.secretFile";
    static final String PROP_BASE_URL = "capture.baseUrl";

    /**
     * The property Felix FileInstall adds to every configuration it delivers: the URI of the file
     * it came from. Absent for a configuration created any other way.
     */
    static final String PROP_FILEINSTALL_FILENAME = "felix.fileinstall.filename";

    /** The banner {@link #save} writes above preserved lines; skipped on re-read so it is written once. */
    static final String PRESERVED_BANNER = "# Kept from the previous file: settings this module does not manage.";

    /*
     * One site-key rule for the whole module: RevisionSnapshotService.isValidSiteKey, the capture
     * path's own. This class used to apply a second, stricter pattern to the file name, which
     * refused a leading '_' or '-' -- both legal in a Jahia site key (validSiteKeyCharacters =
     * A-Za-z_0-9-, no position rule). A site such as _intranet captured normally but could not save
     * settings, and the panel showed "Internal Server Error(s) while executing query" (issue #29).
     * The shared rule is still one safe path segment: no separator, no dot, so no traversal.
     */

    /** siteKey -> settings. Written on a configuration-admin thread, read on Quartz ones. */
    private final Map<String, SiteCaptureSettings> bySite = new ConcurrentHashMap<>();
    /** OSGi pid -> siteKey, so {@link #deleted} knows which site a pid was for. */
    private final Map<String, String> siteByPid = new ConcurrentHashMap<>();
    /** siteKey -> the file FileInstall delivered it from, when known; see the class comment. */
    private final Map<String, Path> fileBySite = new ConcurrentHashMap<>();

    /**
     * The active component, for callers that cannot be injected.
     *
     * <p>The consumers of this are a Quartz job and a service instantiated reflectively by the
     * backfill script, neither of which DS can inject into. {@link CaptureIdentity} solves the same
     * problem the same way, and doing it differently here would leave two patterns for one need.
     */
    // AtomicReference rather than a volatile field. volatile publishes the REFERENCE safely but
    // says nothing about the object behind it, which is why Sonar's java:S3077 flags the idiom; the
    // instance here happens to keep only ConcurrentHashMaps, so the volatile version was in fact
    // sound, but a reader has to verify that to know it. A thread-safe holder needs no such
    // reasoning, and compareAndSet buys a real improvement below.
    private static final AtomicReference<SiteSettingsRegistry> INSTANCE = new AtomicReference<>();

    @Activate
    void activate() {
        INSTANCE.set(this);
    }

    @Deactivate
    void deactivate() {
        // compareAndSet, not set(null): during a bundle refresh the new instance can activate
        // before the old one deactivates, and an unconditional null would then wipe the LIVE
        // instance and leave every caller falling back to the module defaults until the next
        // activation. Only clear the holder if it still points at us.
        INSTANCE.compareAndSet(this, null);
    }

    /** @return the running component, or null when the module is not active on this node */
    public static SiteSettingsRegistry active() {
        return INSTANCE.get();
    }

    /**
     * @return the settings for a site, or the module defaults when the component is not running.
     *         Never null: capture must keep working while configuration is being replaced.
     */
    public static SiteCaptureSettings settingsFor(String siteKey) {
        SiteSettingsRegistry current = INSTANCE.get();
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
        // The capture path's own rule, which is also what keeps the key a safe single path segment
        // for the .cfg file name -- so this module cannot accept and persist settings for a site
        // whose every capture would then be refused, nor name a file outside the directory.
        if (!RevisionSnapshotService.isValidSiteKey(siteKey)) {
            throw new ConfigurationException(PROP_SITE_KEY,
                    "'" + siteKey + "' is not a valid site key");
        }
        SiteCaptureSettings settings = new SiteCaptureSettings(
                siteKey,
                bool(properties, PROP_ENABLED, SiteCaptureSettings.DEFAULTS.isCaptureEnabled()),
                retention(properties),
                string(properties, PROP_USER),
                CaptureIdentity.authorizationFrom(
                        string(properties, PROP_USER),
                        string(properties, PROP_SECRET_FILE),
                        string(properties, PROP_SECRET)),
                endpoint(string(properties, PROP_BASE_URL), siteKey));
        // A file keeps its pid when it is edited, so changing the siteKey INSIDE it re-points the
        // same pid at another site. Without this, the site it used to name kept the old settings
        // for the life of the process: deleted() only ever clears what siteByPid currently maps,
        // so nothing would remove it, and forSite() went on reporting settings from a file that no
        // longer named that site at all. Only drop it if no other file still names it.
        String previous = siteByPid.put(pid, siteKey);
        if (previous != null && !previous.equals(siteKey) && !siteByPid.containsValue(previous)) {
            bySite.remove(previous);
            fileBySite.remove(previous);
            logger.info("Configuration {} now names site {}; {} falls back to the module defaults.",
                    pid, siteKey, previous);
        }
        bySite.put(siteKey, settings);
        rememberDeliveringFile(siteKey, string(properties, PROP_FILEINSTALL_FILENAME));
        logger.info("Applied per-site revision capture settings: {}", settings);
    }

    /**
     * Records which file configures a site, so a later {@link #save} rewrites that file rather than
     * creating a second one under the conventional name. Only a {@code .cfg} FileInstall itself
     * named is remembered; anything else leaves the site on the conventional name.
     */
    private void rememberDeliveringFile(String siteKey, String fileInstallName) {
        Path delivered = fileInstallPath(fileInstallName);
        if (delivered == null) {
            fileBySite.remove(siteKey);
            return;
        }
        fileBySite.put(siteKey, delivered);
    }

    /** @return the path named by a FileInstall filename property (a file: URI or a plain path), or null */
    static Path fileInstallPath(String fileInstallName) {
        if (fileInstallName == null || !fileInstallName.endsWith(".cfg")) {
            return null;
        }
        try {
            return fileInstallName.startsWith("file:")
                    ? Paths.get(URI.create(fileInstallName)) : Paths.get(fileInstallName);
        } catch (RuntimeException unusable) {
            logger.debug("Ignoring unusable {} value {}", PROP_FILEINSTALL_FILENAME, fileInstallName, unusable);
            return null;
        }
    }

    @Override
    public void deleted(String pid) {
        String siteKey = siteByPid.remove(pid);
        if (siteKey != null) {
            bySite.remove(siteKey);
            fileBySite.remove(siteKey);
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
    /**
     * A value this module writes into a {@code .cfg} line, rejected if it could forge more lines.
     *
     * <p>The file is a Java properties file, so a value carrying a newline does not stay a value:
     * everything after it is parsed as further keys. These values arrive from GraphQL, from a site
     * administrator, so without this a site administrator of one site could write
     * {@code capture.user = x\ncapture.secretFile = /some/server/path} and have the module read
     * that file's first line and send it as an Authorization header to a host they also control, or
     * {@code x\nsiteKey = otherSite} and re-key the whole file onto a site they administer nothing
     * on. A carriage return alone does the same on the platforms that accept it.
     */
    private static String singleLine(String property, String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(property + " may not contain a line break: the file"
                    + " is a properties file, so a line break in a value forges further settings");
        }
        // A backslash is the properties escape character, and a trailing one continues the value
        // onto the next line -- so it swallows whatever setting follows rather than forging a new
        // one. Neither a username nor a URL needs one, so all of them are refused.
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(property + " may not contain a backslash: it is the"
                    + " properties escape character, and a trailing one continues onto the next"
                    + " line, consuming the setting written after it");
        }
        return value;
    }

    /**
     * The keys this module owns and rewrites. Everything else in the file is preserved verbatim.
     *
     * <p>{@code capture.secret} and {@code capture.secretFile} are the reason this exists.
     * SiteCaptureSettings carries only the already-resolved Authorization header, never the secret
     * or its path, so a save built purely from that object silently dropped them. An administrator
     * who hand-added a credential so a restricted site could be captured, then toggled anything at
     * all in the settings panel, lost it: FileInstall reloaded a file with a user and no secret,
     * capture fell back to anonymous, and every restricted page quietly stopped being recorded.
     */
    private static final Set<String> MANAGED_KEYS = new HashSet<>(Arrays.asList(
            PROP_SITE_KEY, PROP_ENABLED, PROP_MAX_SNAPSHOTS, PROP_USER, PROP_BASE_URL));

    /** Lines of the existing file that this module does not own, so a rewrite keeps them. */
    private List<String> preservedLines(Path target) {
        List<String> kept = new ArrayList<>();
        // exists(), NOT isReadable(). isReadable answers false for BOTH "no file yet" and "a file
        // that exists but this process cannot read", and treating the second as the first is exactly
        // the failure this method exists to prevent: the save would proceed and write a fresh file
        // with no capture.secret in it. An existing file now falls through to readAllLines, whose
        // IOException is turned into a refusal below.
        if (!Files.exists(target)) {
            return kept;
        }
        // A run of comment lines is held until the next real key decides its fate, and travels
        // with that key when the key is kept. Dropping comments outright cost real information: an
        // operator annotating a hand-added capture.secret ("rotated 2026-01-05, owner SecOps")
        // found the secret preserved across a panel save and the note explaining it gone, so the
        // provenance of a live credential was lost with nothing recording that it had been.
        // A run with no key after it annotated nothing and is not carried.
        List<String> pendingComments = new ArrayList<>();
        try {
            // An if/else chain rather than early continues: three of them tripped java:S135, and
            // the nesting is shallow enough that the guard-clause form bought nothing here.
            for (String line : Files.readAllLines(target, StandardCharsets.ISO_8859_1)) {
                String trimmed = line.trim();
                if (trimmed.equals(PRESERVED_BANNER)) {
                    // Our own banner from the previous save. Re-reading it as an operator comment
                    // re-emitted it under a fresh banner, so the file grew by one line per save
                    // (issue #31).
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    pendingComments.add(line);
                } else if (!trimmed.isEmpty()) {
                    int separator = indexOfSeparator(trimmed);
                    String key = separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
                    if (MANAGED_KEYS.contains(key)) {
                        // These lines are rewritten from the settings, so a comment describing one
                        // would end up above a value it no longer matches.
                        pendingComments.clear();
                    } else {
                        kept.addAll(pendingComments);
                        pendingComments.clear();
                        kept.add(line);
                    }
                }
            }
        } catch (IOException unreadable) {
            // Better to refuse than to rewrite a file whose other settings could not be read: a
            // silent drop here is exactly the credential loss this method exists to prevent.
            throw new IllegalStateException("Refusing to rewrite " + target
                    + ": its current contents could not be read, and saving would discard any"
                    + " settings this module does not manage, including a capture secret.",
                    unreadable);
        }
        return kept;
    }

    private static int indexOfSeparator(String line) {
        // '=', ':' AND whitespace are all legal key/value separators in a properties file. Reading
        // only the first two classed `capture.enabled false` as an unmanaged key, so a rewrite
        // re-appended it BELOW the module's own line -- and the last occurrence is the one that
        // wins, so the file said the opposite of what the panel reported.
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                return i;
            }
        }
        return -1;
    }

    public void save(SiteCaptureSettings settings) throws IOException {
        String siteKey = settings.getSiteKey();
        // Also checked here, not only in the GraphQL mutation. This method is reachable from the
        // reflectively instantiated backfill service as well, and a value written below 1 would sit
        // in the file as invalid until FileInstall next reloaded it and silently substituted the
        // default. Refusing at every entry point is what makes the file trustworthy.
        if (settings.getMaxSnapshots() < MIN_MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("maxSnapshots must be at least " + MIN_MAX_SNAPSHOTS
                    + ", not " + settings.getMaxSnapshots() + ": retention never deletes a page's"
                    + " newest snapshot, so a cap of 1 cannot be honoured and the effective floor"
                    + " is " + MIN_MAX_SNAPSHOTS);
        }
        // Refused, not merely warned about. This is the path the settings panel and the GraphQL
        // mutation write through, and it is reachable by a SITE administrator -- a role scoped to
        // one site, not to the server. capture.baseUrl decides which host this node issues an HTTP
        // GET to, and whatever answers is normalised and stored as that site's public revision
        // snapshot: an arbitrary outbound GET from inside the network (a metadata endpoint, an
        // internal service) plus a forged record of what the page said. GuestMarkdownFetcher's own
        // Javadoc claimed "no SSRF surface: the caller cannot influence the host", and that claim
        // was only true before this value became site-configurable.
        //
        // updated() deliberately still ACCEPTS a non-loopback value from the file itself and only
        // warns: a server administrator who owns karaf/etc is the one the escape hatch exists for
        // (a container whose connector is not reachable on loopback), and they already have every
        // privilege this would grant.
        if (settings.getBaseUrl() != null
                && !GuestMarkdownFetcher.reachesJahiaDirectly(settings.getBaseUrl())) {
            throw new IllegalArgumentException(PROP_BASE_URL + " must equal this node's own"
                    + " loopback connector, not " + settings.getBaseUrl() + ". Capture fetches"
                    + " /cms/render/... from this node itself; a different port, a path, a query or"
                    + " a fragment would send the capture credential elsewhere or store another"
                    + " response as this site's revision history. Use exactly http://127.0.0.1:<port>"
                    + " with no trailing path, or clear the field to let the connector be detected.");
        }
        Path target = configFile(siteKey);
        String captureUser = singleLine(PROP_USER, settings.getCaptureUser());
        String baseUrl = singleLine(PROP_BASE_URL, settings.getBaseUrl());
        List<String> preserved = preservedLines(target);
        StringBuilder body = new StringBuilder()
                .append("# Content Revision History -- settings for site ").append(siteKey)
                .append("\n# Written by the module. Safe to edit by hand; changes apply without a restart.\n\n")
                .append(PROP_SITE_KEY).append(" = ").append(siteKey).append('\n')
                .append(PROP_ENABLED).append(" = ").append(settings.isCaptureEnabled()).append('\n')
                .append(PROP_MAX_SNAPSHOTS).append(" = ").append(settings.getMaxSnapshots()).append('\n');
        if (captureUser != null) {
            body.append(PROP_USER).append(" = ").append(captureUser).append('\n');
        }
        // Omitted rather than written empty when unset: an empty value would read back as an empty
        // string and override the node default, whereas an absent key means "use the default".
        if (baseUrl != null) {
            body.append(PROP_BASE_URL).append(" = ").append(baseUrl).append('\n');
        }
        if (!preserved.isEmpty()) {
            body.append('\n').append(PRESERVED_BANNER).append('\n');
            for (String line : preserved) {
                body.append(line).append('\n');
            }
        }
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        // ISO-8859-1 to match how a properties file is read, here and by FileInstall.
        Files.write(temp, body.toString().getBytes(StandardCharsets.ISO_8859_1));
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            // Some filesystems refuse ATOMIC_MOVE across the same directory only in theory, but a
            // failed save must not leave the temp file behind either way.
            Files.deleteIfExists(temp);
            throw atomicUnsupported;
        }
        // Applied to the in-memory map now, rather than waiting for FileInstall to notice the
        // file and call updated(). The write is already durable at this point, so the map would
        // have converged on this value anyway; the only thing waiting achieved was a window in
        // which the module still answered with the OLD settings. Two consequences, both reported:
        // a panel that refetches immediately after Save snapped back to the previous values and
        // flipped its banner to "this site has no settings of its own", so an administrator saved
        // again believing the write had failed; and a publication landing inside that window was
        // captured under settings the operator had just turned off.
        //
        // updated() remains the authority: when FileInstall does deliver the file it overwrites
        // this with whatever was actually parsed, so a value this method got wrong cannot persist.
        bySite.put(siteKey, settings);
        logger.info("Wrote per-site revision capture settings for {} to {}", siteKey, target);
    }

    /** Removes a site's configuration file, after which the site falls back to the defaults. */
    public void delete(String siteKey) throws IOException {
        Path target = configFile(siteKey);
        if (Files.deleteIfExists(target)) {
            logger.info("Deleted per-site revision capture settings for {}", siteKey);
        }
        // Cleared now, for the same reason save() applies its write now: FileInstall will call
        // deleted(pid) when it notices, but until then the panel's refetch still showed the deleted
        // settings and the "own settings" banner, and a publication landing in that window was
        // captured under values the administrator had just removed (issue #28).
        bySite.remove(siteKey);
        fileBySite.remove(siteKey);
        siteByPid.values().removeIf(siteKey::equals);
    }

    /**
     * The file one site's configuration lives in: the one FileInstall delivered it from when that
     * is known, else the conventional name.
     *
     * <p>The site key is validated before it reaches the file name. A key carrying a separator or a
     * {@code ..} would otherwise let a caller name a path outside the configuration directory.
     */
    Path configFile(String siteKey) {
        if (!RevisionSnapshotService.isValidSiteKey(siteKey)) {
            throw new IllegalArgumentException("Not a valid site key: " + siteKey);
        }
        Path delivered = fileBySite.get(siteKey);
        return delivered != null ? delivered : etcDirectory().resolve(FACTORY_PID + '-' + siteKey + ".cfg");
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
     * The retention cap from the file, floored at {@link #MIN_MAX_SNAPSHOTS}.
     *
     * <p>One minimum, applied everywhere: the file, {@link #save}, the mutation and the panel. The
     * file used to accept 1 while save() refused it, so a site hand-configured with 1 could not be
     * edited in the panel until an unrelated field was raised first (issue #32). 1 cannot be
     * honoured anyway -- prune never deletes the newest snapshot -- so the floor is the honest value.
     */
    private static int retention(Dictionary<String, ?> properties) {
        int configured = positiveInt(properties, PROP_MAX_SNAPSHOTS, SiteCaptureSettings.DEFAULTS.getMaxSnapshots());
        if (configured < MIN_MAX_SNAPSHOTS) {
            logger.warn("{} = {} is below the floor retention can deliver; using {}", PROP_MAX_SNAPSHOTS,
                    configured, MIN_MAX_SNAPSHOTS);
            return MIN_MAX_SNAPSHOTS;
        }
        return configured;
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
