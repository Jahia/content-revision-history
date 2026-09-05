package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.RepositoryException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RevisionSnapshotService#withConcurrencyRetry} is the module's entire concurrent-write
 * correctness guarantee: telling a benign same-content name collision ({@link ItemExistsException})
 * apart from a genuinely lost write ({@link InvalidItemStateException}) is the one thing standing
 * between "nothing changed" and "a real revision silently vanished". It is driven here directly
 * with lambdas; no JCR session is needed since the method only depends on the two functional
 * interfaces it is given.
 *
 * <p>The pure helpers (the siteKey/pageUuid/language validation regexes and {@code truncate}) are
 * private, so they are reached through reflection rather than by widening visibility -- no
 * production change is authorized or needed for this class.
 */
class RevisionSnapshotServiceTest {

    // ------------------------------------------------------------ withConcurrencyRetry

    @Test
    @DisplayName("a first-try success returns its result and never resets the session")
    void firstTrySuccessNeverResets() throws RepositoryException {
        // Arrange
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                () -> CaptureStatus.STORED,
                () -> resetCalls.add("reset"),
                () -> { throw new AssertionError("the collision predicate must not be consulted"
                        + " when the attempt succeeds"); },
                failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.STORED, result);
        assertTrue(resetCalls.isEmpty(), "a clean first attempt must never touch the session");
        assertTrue(failuresRecorded.isEmpty());
    }

    @Test
    @DisplayName("ItemExistsException once returns UNCHANGED, resets exactly once, records no failure")
    void itemExistsExceptionIsBenignAndUnchanged() throws RepositoryException {
        // Arrange -- same deterministic name means same content for the same publication: this
        // is the "nothing was lost" branch, and must never be confused with a real conflict.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            throw new ItemExistsException("node already exists under the same name");
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> true, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.UNCHANGED, result,
                "a same-name collision proves identical content, never a lost write");
        assertEquals(1, resetCalls.size(), "the transient session state must be discarded exactly once");
        assertTrue(failuresRecorded.isEmpty(), "a benign collision is not a failure");
    }

    @Test
    @DisplayName("ItemExistsException with no snapshot under the expected name is FAILED, never UNCHANGED")
    void structuralCollisionIsNotTreatedAsBenign() throws RepositoryException {
        // Arrange -- an ItemExistsException can come from anywhere in the attempt, and the first
        // thing an attempt does is create the shared <pageUuid> folder. Two languages of a
        // never-before-captured page race there, so the loser's collision says nothing about
        // snapshot content. A node under the deterministic name (its suffix IS the content hash)
        // is the only proof that the winner stored what this thread meant to store.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            throw new ItemExistsException("/sites/x/contents/revision-history/<uuid> already exists");
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> false, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.FAILED, result,
                "reporting UNCHANGED here would erase a real revision while asserting nothing changed");
        assertEquals(1, resetCalls.size(), "the transient session state must still be discarded");
        assertEquals(1, failuresRecorded.size(), "the loss must be recorded durably, not swallowed");
        assertTrue(failuresRecorded.get(0).contains("ItemExistsException"),
                "the durable record must name the exception so an operator can diagnose it");
    }

    @Test
    @DisplayName("A non-benign collision on the RETRY is FAILED too, not UNCHANGED")
    void structuralCollisionOnRetryIsAlsoFailed() throws RepositoryException {
        // Arrange -- the retry path has its own ItemExistsException catch, and it must apply the
        // same proof or the narrowing is only half done.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        boolean[] first = {true};
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            if (first[0]) {
                first[0] = false;
                throw new InvalidItemStateException("stale item state");
            }
            throw new ItemExistsException("structural collision on retry");
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> false, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.FAILED, result);
        assertEquals(1, failuresRecorded.size());
    }

    @Test
    @DisplayName("InvalidItemStateException twice returns FAILED, resets twice, records a failure naming the exception")
    void invalidItemStateExceptionTwiceIsARealFailure() throws RepositoryException {
        // Arrange -- the generic optimistic-concurrency failure; if it recurs after one retry
        // against a refreshed session, the write was genuinely lost and must never be silently
        // reported as UNCHANGED.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            throw new InvalidItemStateException("stale item state");
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> true, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.FAILED, result);
        assertEquals(2, resetCalls.size(), "both the initial and the retried session must be reset");
        assertEquals(1, failuresRecorded.size());
        assertTrue(failuresRecorded.get(0).contains("InvalidItemStateException"),
                "the durable failure record must name the exception that caused it, for operators");
    }

    @Test
    @DisplayName("InvalidItemStateException once, then success on retry, resets once and returns the retry's outcome")
    void invalidItemStateExceptionThenSuccessRetriesOnce() throws RepositoryException {
        // Arrange -- the common real-world case: the refreshed session's second attempt succeeds
        // once the winner's write is visible.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        int[] callCount = {0};
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                throw new InvalidItemStateException("stale item state");
            }
            return CaptureStatus.UNCHANGED;
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> true, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.UNCHANGED, result);
        assertEquals(1, resetCalls.size(), "only the failed first attempt requires a reset");
        assertTrue(failuresRecorded.isEmpty(), "a retry that succeeds is not a failure");
        assertEquals(2, callCount[0]);
    }

    @Test
    @DisplayName("InvalidItemStateException then a same-name collision on retry is still benign, not FAILED")
    void invalidItemStateExceptionThenItemExistsIsStillBenign() throws RepositoryException {
        // Arrange -- covers the nested branch: the retry itself can also hit the benign
        // same-name-same-content case, and that must not be conflated with the FAILED branch.
        List<String> resetCalls = new ArrayList<>();
        List<String> failuresRecorded = new ArrayList<>();
        int[] callCount = {0};
        RevisionSnapshotService.CaptureAttempt attempt = () -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                throw new InvalidItemStateException("stale item state");
            }
            throw new ItemExistsException("node already exists under the same name");
        };

        // Act
        CaptureStatus result = RevisionSnapshotService.withConcurrencyRetry(
                attempt, () -> resetCalls.add("reset"), () -> true, failuresRecorded::add,
                "11111111-1111-1111-1111-111111111111", "en");

        // Assert
        assertEquals(CaptureStatus.UNCHANGED, result);
        assertEquals(2, resetCalls.size(),
                "the outer InvalidItemStateException catch and the nested benign-collision handler each reset once");
        assertTrue(failuresRecorded.isEmpty());
    }

    // ------------------------------------------------------------ validation regexes

    @Test
    @DisplayName("SITE_KEY pattern accepts alphanumerics, underscore and hyphen, up to 100 chars")
    void siteKeyPatternAcceptsValidValues() throws Exception {
        Pattern siteKey = fieldPattern("SITE_KEY");

        assertTrue(siteKey.matcher("digitall").matches());
        assertTrue(siteKey.matcher("my-site_123").matches());
        assertTrue(siteKey.matcher("A").matches());
        assertTrue(siteKey.matcher(repeat('a', 100)).matches(), "100 chars is the documented upper bound");
    }

    @Test
    @DisplayName("SITE_KEY pattern rejects empty, oversized, path-traversal-shaped and separator-bearing values")
    void siteKeyPatternRejectsInvalidValues() throws Exception {
        Pattern siteKey = fieldPattern("SITE_KEY");

        assertFalse(siteKey.matcher("").matches(), "empty must be rejected");
        assertFalse(siteKey.matcher(repeat('a', 101)).matches(), "101 chars exceeds the cap");
        assertFalse(siteKey.matcher("my site").matches(), "spaces are not permitted");
        assertFalse(siteKey.matcher("../../etc/passwd").matches(),
                "path-traversal-shaped input must never reach the JCR path concatenation");
        assertFalse(siteKey.matcher("..").matches());
        assertFalse(siteKey.matcher("site/name").matches(), "path separators are not permitted");
    }

    @Test
    @DisplayName("UUID pattern accepts only canonical 8-4-4-4-12 hex form")
    void uuidPatternAcceptsValidValues() throws Exception {
        Pattern uuid = fieldPattern("UUID");

        assertTrue(uuid.matcher("11111111-1111-1111-1111-111111111111").matches());
        assertTrue(uuid.matcher("aAbBcC12-aAbB-cC12-aAbB-aAbBcC12aAbB").matches(), "mixed-case hex is allowed");
    }

    @Test
    @DisplayName("UUID pattern rejects malformed, path-traversal-shaped and wrong-length values")
    void uuidPatternRejectsInvalidValues() throws Exception {
        Pattern uuid = fieldPattern("UUID");

        assertFalse(uuid.matcher("not-a-uuid").matches());
        assertFalse(uuid.matcher("11111111-1111-1111-1111-11111111111").matches(), "one hex digit short");
        assertFalse(uuid.matcher("11111111-1111-1111-1111-1111111111111").matches(), "one hex digit long");
        assertFalse(uuid.matcher("../../../etc/passwd").matches());
        assertFalse(uuid.matcher("g1111111-1111-1111-1111-111111111111").matches(), "g is not hex");
    }

    @Test
    @DisplayName("LANGUAGE pattern accepts ISO-shaped codes, with or without region/script subtag")
    void languagePatternAcceptsValidValues() throws Exception {
        Pattern language = fieldPattern("LANGUAGE");

        assertTrue(language.matcher("en").matches());
        assertTrue(language.matcher("eng").matches());
        assertTrue(language.matcher("en_US").matches());
        assertTrue(language.matcher("zh_Hans").matches(), "up to an 8-char subtag is allowed");
    }

    @Test
    @DisplayName("LANGUAGE pattern rejects uppercase primary tags, too-short/long codes and path-traversal-shaped values")
    void languagePatternRejectsInvalidValues() throws Exception {
        Pattern language = fieldPattern("LANGUAGE");

        assertFalse(language.matcher("EN").matches(), "the primary subtag must be lowercase");
        assertFalse(language.matcher("e").matches(), "one letter is too short");
        assertFalse(language.matcher("english").matches(), "more than 3 letters is not ISO-shaped");
        assertFalse(language.matcher("../en").matches());
        assertFalse(language.matcher("en/../..").matches());
    }

    // ------------------------------------------------------------ truncate

    @Test
    @DisplayName("truncate returns an empty string for null input")
    void truncateHandlesNull() throws Exception {
        assertEquals("", invokeTruncate(null));
    }

    @Test
    @DisplayName("truncate leaves messages of 500 chars or fewer untouched")
    void truncateLeavesShortMessagesUntouched() throws Exception {
        String short500 = repeat('x', 500);
        assertEquals(short500, invokeTruncate(short500), "exactly 500 chars is the documented boundary, not yet truncated");
        assertEquals("short message", invokeTruncate("short message"));
    }

    @Test
    @DisplayName("truncate cuts messages over 500 chars down to exactly 500")
    void truncateCutsLongMessages() throws Exception {
        String long501 = repeat('y', 501);

        String result = invokeTruncate(long501);

        assertEquals(500, result.length());
        assertEquals(repeat('y', 500), result);
    }

    // ------------------------------------------------------------ reflection helpers

    private static Pattern fieldPattern(String name) throws Exception {
        Field field = RevisionSnapshotService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }

    private static String invokeTruncate(String message) throws Exception {
        Method method = RevisionSnapshotService.class.getDeclaredMethod("truncate", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, (Object) message);
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder(times);
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ #36

    @Test
    @DisplayName("#36: the write path validates its coordinates before touching the repository")
    void captureRefusesBadCoordinatesBeforeAnyRepositoryAccess() {
        // Through the public entry point, not by reflection on the patterns: these three values are
        // concatenated into a path handed to addNode() on an unrestricted system session, so what
        // matters is that the WRITE PATH refuses them -- not that a regex exists somewhere.
        RevisionSnapshotService service = new RevisionSnapshotService();
        String uuid = "11111111-1111-1111-1111-111111111111";

        assertThrows(IllegalArgumentException.class, () -> service.captureIfChanged(
                "../etc", uuid, "en", "# x", java.time.Instant.now(), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.captureIfChanged(
                "site", "not-a-uuid", "en", "# x", java.time.Instant.now(), null, null));
        assertThrows(IllegalArgumentException.class, () -> service.captureIfChanged(
                "site", uuid, "en/../fr", "# x", java.time.Instant.now(), null, null));
    }

    @Test
    @DisplayName("#36: validate() itself, for the shapes each coordinate must and must not have")
    void validateAcceptsAndRefusesTheRightShapes() {
        String uuid = "11111111-1111-1111-1111-111111111111";
        RevisionSnapshotService.validate("digitall", uuid, "en");
        RevisionSnapshotService.validate("_intranet", uuid, "pt_BR");
        assertThrows(IllegalArgumentException.class, () -> RevisionSnapshotService.validate("a.b", uuid, "en"));
        assertThrows(IllegalArgumentException.class, () -> RevisionSnapshotService.validate("site", uuid, "EN"));
        assertThrows(IllegalArgumentException.class, () -> RevisionSnapshotService.validate("site", uuid, null));
        assertThrows(IllegalArgumentException.class, () -> RevisionSnapshotService.validate(null, uuid, "en"));
    }

    @Test
    @DisplayName("#36: the snapshot name is built once, and the collision check looks for exactly it")
    void collisionCheckLooksForTheNameThatWasWritten() throws RepositoryException {
        java.time.Instant instant = java.time.Instant.parse("2026-08-15T12:00:00Z");
        String hash = MarkdownNormalizer.hash("# page");
        String name = RevisionSnapshotService.snapshotName(instant, hash);
        assertTrue(name.endsWith("-" + hash.substring(0, 8)), name);

        org.jahia.services.content.JCRSessionWrapper session =
                org.mockito.Mockito.mock(org.jahia.services.content.JCRSessionWrapper.class);
        String expectedPath = "/sites/digitall/contents/revision-history/"
                + "11111111-1111-1111-1111-111111111111/en/" + name;
        org.mockito.Mockito.when(session.nodeExists(expectedPath)).thenReturn(true);

        assertTrue(RevisionSnapshotService.storedByTheWinner(session, "digitall",
                "11111111-1111-1111-1111-111111111111", "en", name));
        assertEquals(expectedPath, RevisionSnapshotService.snapshotPath("digitall",
                "11111111-1111-1111-1111-111111111111", "en", name));
    }

    @Test
    @DisplayName("#36: when the winner's snapshot cannot be seen or checked, the loss is not called benign")
    void winnerNotProvenMeansNotBenign() throws RepositoryException {
        org.jahia.services.content.JCRSessionWrapper session =
                org.mockito.Mockito.mock(org.jahia.services.content.JCRSessionWrapper.class);
        org.mockito.Mockito.when(session.nodeExists(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        assertFalse(RevisionSnapshotService.storedByTheWinner(session, "s",
                "11111111-1111-1111-1111-111111111111", "en", "n"));

        org.jahia.services.content.JCRSessionWrapper broken =
                org.mockito.Mockito.mock(org.jahia.services.content.JCRSessionWrapper.class);
        org.mockito.Mockito.when(broken.nodeExists(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RepositoryException("unreadable"));
        assertFalse(RevisionSnapshotService.storedByTheWinner(broken, "s",
                "11111111-1111-1111-1111-111111111111", "en", "n"),
                "unable to prove the winner stored it, so UNCHANGED must not be claimed");
    }

    // ------------------------------------------------------------------ #48

    @Test
    @DisplayName("#48: a language known only as active-live is accepted, so its status can be recorded")
    void isKnownLanguageUnionsBothSets() {
        org.jahia.services.content.decorator.JCRSiteNode site =
                org.mockito.Mockito.mock(org.jahia.services.content.decorator.JCRSiteNode.class);
        // Configured languages no longer list "de"; it is only active for live -- the state an
        // async capture can find after a language was dropped. recordStatus reaches this through
        // ensureFolder; validating against getLanguages() alone lost the durable FAILED record.
        org.mockito.Mockito.when(site.getLanguages())
                .thenReturn(new java.util.HashSet<>(java.util.Arrays.asList("en", "fr")));
        org.mockito.Mockito.when(site.getActiveLiveLanguages())
                .thenReturn(new java.util.HashSet<>(java.util.Arrays.asList("en", "de")));

        assertTrue(RevisionSnapshotService.isKnownLanguage(site, "de"), "active-live language accepted");
        assertTrue(RevisionSnapshotService.isKnownLanguage(site, "fr"), "configured language accepted");
        assertFalse(RevisionSnapshotService.isKnownLanguage(site, "es"), "an unknown language is not");
    }

    @Test
    @DisplayName("#48: a site that reports no languages blocks nothing")
    void isKnownLanguageAllowsAllWhenSiteReportsNone() {
        org.jahia.services.content.decorator.JCRSiteNode site =
                org.mockito.Mockito.mock(org.jahia.services.content.decorator.JCRSiteNode.class);
        org.mockito.Mockito.when(site.getLanguages()).thenReturn(java.util.Collections.emptySet());
        org.mockito.Mockito.when(site.getActiveLiveLanguages()).thenReturn(null);

        assertTrue(RevisionSnapshotService.isKnownLanguage(site, "en"));
    }

    // ------------------------------------------------------------ enforceCuratorReadOnly (#2)

    @Test
    @DisplayName("#2: the history root is locked read-only -- inheritance broken, READER granted, nothing writable")
    void enforceCuratorReadOnlyLocksTheHistoryRoot() throws RepositoryException {
        // The snapshot record is `hidden`, not `protected`, so a contributor with write in the
        // site's content tree could otherwise rewrite what a public revision says the page said
        // (GHSA-q67w-prc3-ch5h #2). The ACL is the record's only tamper protection: break
        // inheritance, grant read, grant nothing that confers write.
        org.jahia.services.content.JCRNodeWrapper root =
                org.mockito.Mockito.mock(org.jahia.services.content.JCRNodeWrapper.class);
        org.mockito.Mockito.when(root.getAclInheritanceBreak()).thenReturn(false);
        org.mockito.Mockito.when(root.getPath()).thenReturn("/sites/x/contents/revision-history");

        new RevisionSnapshotService().enforceCuratorReadOnly(root);

        org.mockito.Mockito.verify(root).setAclInheritanceBreak(true);
        // The ONLY grant is reader: an exact-set match, so any write-conferring role would fail it.
        org.mockito.Mockito.verify(root).grantRoles("g:privileged",
                java.util.Collections.singleton("reader"));
        org.mockito.Mockito.verify(root, org.mockito.Mockito.never()).setAclInheritanceBreak(false);
    }

    @Test
    @DisplayName("#2: an already-locked root is not re-broken, so repair is idempotent")
    void enforceCuratorReadOnlyIsIdempotent() throws RepositoryException {
        // Called on every capture; a root carrying the lock already must not be disturbed. grantRoles
        // is idempotent in Jahia (it returns false when the grant is already present), so re-issuing
        // it is safe and keeps the method a single settling point.
        org.jahia.services.content.JCRNodeWrapper root =
                org.mockito.Mockito.mock(org.jahia.services.content.JCRNodeWrapper.class);
        org.mockito.Mockito.when(root.getAclInheritanceBreak()).thenReturn(true);

        new RevisionSnapshotService().enforceCuratorReadOnly(root);

        org.mockito.Mockito.verify(root, org.mockito.Mockito.never()).setAclInheritanceBreak(true);
        org.mockito.Mockito.verify(root).grantRoles("g:privileged",
                java.util.Collections.singleton("reader"));
    }
}
