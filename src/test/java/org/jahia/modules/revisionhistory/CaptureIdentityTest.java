package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The capture principal decides what ends up in a permanent, publicly-served record, so the two
 * behaviours that matter here are: it is off unless deliberately configured, and a half-finished
 * configuration does not quietly look finished.
 */
class CaptureIdentityTest {

    private final CaptureIdentity identity = new CaptureIdentity();

    /**
     * The credential is static state shared by the whole surefire fork. Without this the last test
     * to run left it set, and SiteSettingsRegistryTest's fallback assertion held only when that class
     * happened to run first (issue #35).
     */
    @AfterEach
    void resetTheSharedCredential() {
        identity.configure(null);
    }

    private static Map<String, Object> config(String... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String decode(String header) {
        return new String(Base64.getDecoder().decode(header.substring("Basic ".length())),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("With no configuration at all, capture stays anonymous")
    void anonymousByDefault() {
        // The shipped default. Every existing installation must keep rendering as guest without
        // touching anything, because that is what its stored snapshots were captured as.
        identity.configure(config());
        assertNull(CaptureIdentity.authorization());

        identity.configure(null);
        assertNull(CaptureIdentity.authorization(), "a null property map must not throw either");
    }

    @Test
    @DisplayName("A user with a secret produces a Basic credential for exactly that account")
    void userAndSecretProduceACredential() {
        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture",
                CaptureIdentity.PROP_SECRET, "s3cr3t"));

        String header = CaptureIdentity.authorization();
        assertNotNull(header);
        assertEquals("crh-capture:s3cr3t", decode(header));
    }

    @Test
    @DisplayName("A user with NO secret stays anonymous rather than half-configured")
    void userWithoutASecretIsRefused() {
        // Arrange -- the dangerous outcome would be authenticating as the user with an empty
        // secret, or appearing configured while silently rendering as guest. Both leave the
        // operator believing restricted pages are being captured when they are not.
        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture"));

        assertNull(CaptureIdentity.authorization(),
                "an incomplete principal must not become a credential");
    }

    @Test
    @DisplayName("The secret can come from a file, so it need not sit in the configuration")
    void secretIsReadFromAFile(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("crh.secret");
        Files.write(secret, "\n\n  from-a-file  \n".getBytes(StandardCharsets.UTF_8));

        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture",
                CaptureIdentity.PROP_SECRET_FILE, secret.toString()));

        assertEquals("crh-capture:from-a-file", decode(CaptureIdentity.authorization()),
                "leading blank lines and surrounding whitespace must not become part of it");
    }

    @Test
    @DisplayName("An unreadable secret file stays anonymous instead of falling back silently")
    void unreadableSecretFileIsRefused(@TempDir Path dir) {
        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture",
                CaptureIdentity.PROP_SECRET_FILE, dir.resolve("absent").toString()));

        assertNull(CaptureIdentity.authorization());
    }

    @Test
    @DisplayName("An empty secret file does not become an empty password")
    void emptySecretFileIsRefused(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("blank.secret");
        Files.write(secret, "\n   \n".getBytes(StandardCharsets.UTF_8));

        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture",
                CaptureIdentity.PROP_SECRET_FILE, secret.toString()));

        assertNull(CaptureIdentity.authorization());
    }

    @Test
    @DisplayName("The recorded principal follows the credential, so provenance cannot drift from it")
    void principalTracksTheCredential() {
        // Anonymous by default: nothing to name.
        identity.configure(config());
        assertNull(CaptureIdentity.principal());

        // Configured and usable: the record must name the account that actually fetched.
        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture",
                CaptureIdentity.PROP_SECRET, "s3cr3t"));
        assertEquals("crh-capture", CaptureIdentity.principal());
    }

    @Test
    @DisplayName("A half-configured principal is not claimed, because capture stayed anonymous")
    void principalIsNotClaimedWithoutAUsableCredential() {
        // The dangerous case: a user is named but has no secret, so capture falls back to
        // anonymous. Naming that user on the snapshot would assert the record was built from a
        // view it was never built from -- the exact provenance error crh:capturedBy exists to
        // prevent.
        identity.configure(config(CaptureIdentity.PROP_USER, "crh-capture"));

        assertNull(CaptureIdentity.authorization());
        assertNull(CaptureIdentity.principal(),
                "a principal that never authenticated must not be recorded as one that did");
    }

    @Test
    @DisplayName("Deactivating clears the principal along with the credential")
    void deactivationClearsThePrincipal() {
        identity.configure(config(CaptureIdentity.PROP_USER, "u", CaptureIdentity.PROP_SECRET, "p"));
        assertEquals("u", CaptureIdentity.principal());

        identity.clear();

        assertNull(CaptureIdentity.principal());
    }

    @Test
    @DisplayName("Deactivating clears the credential")
    void deactivationClearsTheCredential() {
        identity.configure(config(CaptureIdentity.PROP_USER, "u", CaptureIdentity.PROP_SECRET, "p"));
        assertNotNull(CaptureIdentity.authorization());

        identity.clear();

        assertNull(CaptureIdentity.authorization(),
                "a capture still in flight must not authenticate with withdrawn configuration");
    }

    @Test
    @DisplayName("Reconfiguration replaces the credential, so a rotation needs no restart")
    void reconfigurationReplacesTheCredential() {
        identity.configure(config(CaptureIdentity.PROP_USER, "u", CaptureIdentity.PROP_SECRET, "old"));
        identity.configure(config(CaptureIdentity.PROP_USER, "u", CaptureIdentity.PROP_SECRET, "new"));

        assertEquals("u:new", decode(CaptureIdentity.authorization()));
    }
}
