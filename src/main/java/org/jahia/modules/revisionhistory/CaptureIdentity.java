package org.jahia.modules.revisionhistory;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;

/**
 * Who capture renders as.
 *
 * <p>By default: nobody. Capture stays anonymous, which is correct for a site whose revisioned
 * pages are public, and is what every existing installation keeps without touching anything.
 *
 * <p>Configuring a principal is what gives <b>restricted</b> pages a revision history at all. An
 * anonymous render of a page guest may not read returns 403, so such a page can never be
 * captured; there is no partial answer, just no history.
 *
 * <p><b>What configuring one costs.</b> A snapshot is flattened to text by whoever captured it,
 * so its content is whatever that account could see, and it has one visibility for everyone who
 * later reads it. That is why {@code RevisionDiffService} checks the viewer's own JCR rights
 * before serving a comparison, and why component-level ACLs inside a revisioned page cannot be
 * reflected per viewer. Give the account the narrowest access that covers the pages being
 * revisioned.
 *
 * <p><b>Why a component and not a system property.</b> A {@code -D} value is readable by anyone
 * who can list processes or take a thread dump, and it cannot be changed without a restart. An
 * OSGi configuration is a file whose permissions an administrator controls, and
 * {@link Modified} means a credential rotation takes effect without one.
 */
@Component(
        service = CaptureIdentity.class,
        immediate = true,
        configurationPid = CaptureIdentity.PID,
        configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class CaptureIdentity {

    static final String PID = "org.jahia.modules.revisionhistory";

    private static final Logger logger = LoggerFactory.getLogger(CaptureIdentity.class);

    static final String PROP_USER = "capture.user";
    static final String PROP_SECRET_FILE = "capture.secretFile";
    static final String PROP_SECRET = "capture.secret";

    /**
     * The {@code Authorization} header value, or null to render anonymously.
     *
     * <p>Static because the fetcher is a plain object created once and held statically, and
     * volatile because {@link Modified} can replace it on a configuration-admin thread while a
     * capture job reads it on a Quartz one.
     */
    private static volatile String authorization;

    @Activate
    @Modified
    public void configure(Map<String, Object> properties) {
        String user = trimmed(properties, PROP_USER);
        String header = resolve(properties);
        authorization = header;
        // Only claim the principal when the credential actually resolved. A user configured
        // without a usable secret leaves capture anonymous, and the record must say so.
        principal = header == null ? null : user;
    }

    @Deactivate
    public void clear() {
        principal = null;
        // Leaving a credential resolved after the component goes away would let a capture that
        // is still in flight authenticate with configuration the platform considers withdrawn.
        authorization = null;
    }

    /** @return the header for capture renders, or null when capture should stay anonymous */
    static String authorization() {
        return authorization;
    }

    /**
     * Who capture actually renders as, for the record written onto each snapshot.
     *
     * <p>Kept beside {@link #authorization} and set in the same place, because a snapshot that
     * names a different principal than the one that fetched it is worse than one that names
     * none: the whole point of the field is provenance.
     */
    private static volatile String principal;

    /** @return the configured capture user, or {@code null} when capture is anonymous */
    static String principal() {
        return principal;
    }

    private static String resolve(Map<String, Object> properties) {
        return authorizationFrom(trimmed(properties, PROP_USER),
                trimmed(properties, PROP_SECRET_FILE),
                properties == null || properties.get(PROP_SECRET) == null
                        ? null : String.valueOf(properties.get(PROP_SECRET)));
    }

    /**
     * The credential rules, in one place, for the global configuration and the per-site one alike.
     *
     * <p>Extracted rather than duplicated because the important rule is easy to get subtly wrong:
     * a user configured WITHOUT a usable secret must leave capture anonymous AND must not be
     * claimed as the principal, or a snapshot would name an account that never fetched it. Two
     * implementations of that would eventually disagree.
     *
     * @return the Authorization header, or {@code null} when capture should stay anonymous
     */
    static String authorizationFrom(String user, String secretFile, String secret) {
        if (user == null) {
            logger.info("No {} configured: capture renders stay anonymous. Pages the public"
                    + " cannot read will report NOT_PUBLIC and have no revision history.",
                    PROP_USER);
            return null;
        }
        String resolved = secretFrom(secretFile, secret);
        if (resolved == null) {
            // Falling back to anonymous here would be worse than refusing: the operator asked
            // for a principal precisely so restricted pages could be captured, and anonymous
            // capture cannot do that. It would look configured and quietly not be.
            logger.error("{} is set to '{}' but no secret was found. Set {} (a file whose first"
                    + " non-blank line is the secret, preferred) or {}. Capture stays anonymous"
                    + " until this is corrected, so restricted pages will keep reporting"
                    + " NOT_PUBLIC.", PROP_USER, user, PROP_SECRET_FILE, PROP_SECRET);
            return null;
        }
        logger.info("Capture renders will authenticate as '{}'. Snapshots will contain whatever"
                + " that account can read, so the revision history component should be shown"
                + " only to an audience entitled to see it.", user);
        return basicAuthorization(user, resolved);
    }

    /** File first: a path can be permission-restricted, a configuration value cannot. */
    private static String secretFrom(String file, String direct) {
        if (file != null) {
            try {
                for (String line : Files.readAllLines(Paths.get(file), StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) {
                        return line.trim();
                    }
                }
                logger.error("{} points at {}, which contains no non-blank line",
                        PROP_SECRET_FILE, file);
            } catch (IOException | RuntimeException unreadable) {
                logger.error("Could not read the capture secret from {}", file, unreadable);
            }
            return null;
        }
        return direct == null || direct.isEmpty() ? null : direct;
    }

    private static String trimmed(Map<String, Object> properties, String key) {
        Object raw = properties == null ? null : properties.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    /** Pure, so the encoding is testable without a container. */
    static String basicAuthorization(String user, String secret) {
        String pair = user + ':' + (secret == null ? "" : secret);
        return "Basic " + Base64.getEncoder()
                .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }
}
