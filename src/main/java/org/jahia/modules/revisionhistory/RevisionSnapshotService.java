package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.ItemExistsException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * Append-only store for Markdown page snapshots.
 *
 * <p>Snapshots live in site-local content at
 * {@code /sites/<siteKey>/contents/revision-history/<pageUuid>/<lang>/<name>} and are written
 * to the {@code default} workspace only -- never published. Rationale:
 *
 * <ul>
 *   <li>Site-local content travels with site export, backup and migration; {@code /settings}
 *       does not, and this data outlives the pages it describes.</li>
 *   <li>Never publishing avoids default/live divergence and any nested publication.</li>
 *   <li>Keyed on UUID, not path, so renaming or moving a page keeps its history.</li>
 *   <li>The {@code <pageUuid>} level is a JCR performance guard: Jackrabbit keeps a
 *       child-node-entry list per parent, so partitioning per page bounds each folder.</li>
 * </ul>
 *
 * <p><b>Node naming.</b> Names are {@code <UTC timestamp with millis>-<hash prefix>}. UTC, not
 * local time: dedupe and pruning both rely on "lexicographic order == chronological order",
 * and a DST rollback or a cluster spanning time zones breaks that invariant for local-time
 * names -- producing either duplicates or, worse, a silently missed capture. The timestamp is
 * the <em>publication</em> instant supplied by the caller, not {@code now}, which makes the
 * whole operation idempotent: two concurrent captures of the same publication compute the same
 * name, and the second one is a no-op instead of a lost snapshot.
 *
 * <p><b>Dedupe is O(1).</b> The newest content hash is denormalised onto the per-language
 * folder ({@link RevisionHistoryConstants#PROP_LATEST_HASH}), so deciding "unchanged" never
 * touches the historical nodes. Scanning them would grow linearly forever, and each of them
 * carries the whole Markdown payload.
 */
public class RevisionSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(RevisionSnapshotService.class);

    /**
     * UTC, fixed width, millisecond precision. Fixed width matters as much as UTC: it is what
     * makes string order and time order the same thing.
     */
    private static final DateTimeFormatter NAME_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final int HASH_SUFFIX_LENGTH = 8;

    // Validation at the service boundary. The only current caller derives all three from JCR,
    // so none of these are reachable today -- but they are concatenated into a JCR path and
    // passed to addNode() on an unrestricted system session, and "unreachable today" is not a
    // property a security boundary should depend on.
    private static final Pattern SITE_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(_[A-Za-z]{2,8})?$");

    /**
     * Stores a snapshot unless the previous one has identical content.
     *
     * @param captureInstant the publication instant; makes the write idempotent
     * @param capturedBy     the principal the render ran as, stamped onto the snapshot
     * @return what happened, durably recorded on the per-language folder as well
     */
    public CaptureStatus captureIfChanged(String siteKey, String pageUuid, String language,
                                          String markdown, Instant captureInstant,
                                          String capturedBy, String sourceUrl)
            throws RepositoryException {

        validate(siteKey, pageUuid, language);

        if (markdown == null || markdown.trim().isEmpty()) {
            recordStatus(siteKey, pageUuid, language, CaptureStatus.EMPTY,
                    "Guest render produced no content");
            return CaptureStatus.EMPTY;
        }
        byte[] payload = markdown.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_MARKDOWN_BYTES) {
            recordStatus(siteKey, pageUuid, language, CaptureStatus.OVERSIZE,
                    "Markdown is " + payload.length + " bytes, cap is " + MAX_MARKDOWN_BYTES);
            return CaptureStatus.OVERSIZE;
        }

        final String contentHash = MarkdownNormalizer.hash(markdown);
        final Instant instant = captureInstant == null ? Instant.now() : captureInstant;
        final String principal = capturedBy == null ? CAPTURE_PRINCIPAL : capturedBy;

        // Collected inside the capture session, recorded from a fresh one afterwards: the
        // session that just lost a state conflict is the last thing that should be asked to
        // write the record of having lost it.
        AtomicReference<String> concurrencyFailure = new AtomicReference<>();

        // Same construction attemptCapture uses, so the two names cannot drift apart.
        final String snapshotName = NAME_STAMP.format(instant) + '-'
                + contentHash.substring(0, HASH_SUFFIX_LENGTH);

        CaptureStatus status = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null, (JCRCallback<CaptureStatus>) session -> withConcurrencyRetry(
                        () -> attemptCapture(session, siteKey, pageUuid, language, payload,
                                contentHash, instant, principal, sourceUrl),
                        () -> session.refresh(false),
                        () -> storedByTheWinner(session, siteKey, pageUuid, language, snapshotName),
                        concurrencyFailure::set,
                        pageUuid, language));

        if (status == CaptureStatus.FAILED) {
            String message = concurrencyFailure.get();
            recordStatus(siteKey, pageUuid, language, CaptureStatus.FAILED,
                    message == null ? "Concurrent repository conflict" : message);
        }
        return status;
    }

    /** One full capture attempt against an already-open session. Never retries on its own. */
    private CaptureStatus attemptCapture(JCRSessionWrapper session, String siteKey,
                                         String pageUuid, String language, byte[] payload,
                                         String contentHash, Instant instant, String principal,
                                         String sourceUrl) throws RepositoryException {

        JCRNodeWrapper folder = ensureFolder(session, siteKey, pageUuid, language);
        String name = NAME_STAMP.format(instant) + '-'
                + contentHash.substring(0, HASH_SUFFIX_LENGTH);

        if (contentHash.equals(stringProperty(folder, PROP_LATEST_HASH))
                || folder.hasNode(name)) {
            logger.debug("Snapshot for page {} [{}] unchanged (hash {}), skipping",
                    pageUuid, language, contentHash);
            markUnchanged(folder, session, instant);
            return CaptureStatus.UNCHANGED;
        }

        long kept = pruneIfNeeded(folder, siteKey);
        createSnapshot(session, folder, name, language, payload, contentHash,
                instant, principal, sourceUrl);
        updateFolderState(folder, name, contentHash, kept + 1, instant);
        session.save();
        logger.info("Stored revision snapshot {} for page {} [{}] under {}",
                name, pageUuid, language, folder.getPath());
        return CaptureStatus.STORED;
    }

    // ------------------------------------------------------- concurrency retry policy

    /** One full capture attempt; the unit {@link #withConcurrencyRetry} replays. */
    @FunctionalInterface
    interface CaptureAttempt {
        CaptureStatus run() throws RepositoryException;
    }

    /** Discards the working session's transient state so an attempt can be replayed cleanly. */
    @FunctionalInterface
    interface SessionReset {
        void reset() throws RepositoryException;
    }

    /**
     * Runs a capture attempt, telling a benign name collision apart from a genuinely lost write.
     *
     * <p>These two must not share a branch, which is what the previous
     * {@code catch (ItemExistsException | InvalidItemStateException)} did:
     *
     * <ul>
     *   <li>{@link ItemExistsException} is benign by construction. The node name is
     *       {@code <publication instant>-<content hash prefix>}, so the same name proves the
     *       same content for the same publication: whoever won wrote exactly what this thread
     *       was about to write. Nothing was lost, and {@code UNCHANGED} is the truth.</li>
     *   <li>{@link InvalidItemStateException} is the generic JCR optimistic-concurrency
     *       failure. It also fires when two system sessions mutate the <em>same parent
     *       folder</em> ({@code crh:latestHash}, {@code crh:snapshotCount}, ...) carrying
     *       <em>different</em> payloads -- two publications of genuinely different content
     *       picked up by two Quartz threads. There the loser's snapshot was never persisted,
     *       {@code refresh(false)} throws it away, and reporting {@code UNCHANGED} would erase
     *       a real revision while asserting nothing changed. That is precisely the silent gap
     *       this module exists to prevent.</li>
     * </ul>
     *
     * <p>So the state conflict is retried once against a refreshed session -- which usually
     * succeeds, the winner's write now being visible, and legitimately yields
     * {@code UNCHANGED} if the winner happened to store identical content. If the retry loses
     * as well, the outcome is {@link CaptureStatus#FAILED}, recorded durably on the folder.
     * Never {@code UNCHANGED}.
     *
     * @param recordFailure sink for the failure detail, invoked only when both attempts lose
     *                      the race; the caller turns it into the durable {@code FAILED} record
     */
    static CaptureStatus withConcurrencyRetry(CaptureAttempt attempt, SessionReset reset,
                                              BooleanSupplier storedByTheWinner,
                                              Consumer<String> recordFailure,
                                              String pageUuid, String language)
            throws RepositoryException {
        try {
            return attempt.run();
        } catch (ItemExistsException collision) {
            return resolveCollision(reset, storedByTheWinner, recordFailure,
                    pageUuid, language, collision);
        } catch (InvalidItemStateException conflict) {
            logger.warn("Concurrent repository change while capturing page {} [{}]; retrying"
                    + " once against a refreshed session", pageUuid, language, conflict);
            reset.reset();
            try {
                return attempt.run();
            } catch (ItemExistsException collision) {
                return resolveCollision(reset, storedByTheWinner, recordFailure,
                        pageUuid, language, collision);
            } catch (InvalidItemStateException stillConflicting) {
                reset.reset();
                String message = "Lost a concurrent repository race twice; the snapshot was not"
                        + " stored (" + stillConflicting.getClass().getSimpleName() + ": "
                        + stillConflicting.getMessage() + ")";
                logger.error("Revision snapshot for page {} [{}] was NOT stored: {}",
                        pageUuid, language, message, stillConflicting);
                recordFailure.accept(message);
                return CaptureStatus.FAILED;
            }
        }
    }

    /**
     * Decides whether an {@link ItemExistsException} really was the harmless case.
     *
     * <p>It is harmless only when a node exists under the <em>deterministic snapshot name</em>,
     * whose suffix is the content hash: that proves the winner stored exactly what this thread
     * was about to store. The exception can however come from anywhere in the attempt, and the
     * first thing an attempt does is create the shared {@code <pageUuid>} folder -- so two
     * languages of a never-before-captured page race there, and the loser's collision has
     * nothing to do with snapshot content. Treating that as benign discarded a genuinely new
     * snapshot and reported {@code UNCHANGED}: a real revision erased while asserting that
     * nothing changed, which is the failure this module exists to prevent.
     */
    private static CaptureStatus resolveCollision(SessionReset reset,
                                                  BooleanSupplier storedByTheWinner,
                                                  Consumer<String> recordFailure,
                                                  String pageUuid, String language,
                                                  ItemExistsException collision)
            throws RepositoryException {
        reset.reset();
        if (storedByTheWinner.getAsBoolean()) {
            logger.info("Snapshot for page {} [{}] was written concurrently under the same name:"
                    + " {}", pageUuid, language, collision.getMessage());
            return CaptureStatus.UNCHANGED;
        }
        String message = "Lost a concurrent creation race and no snapshot exists under the"
                + " expected name, so nothing of this capture was stored ("
                + collision.getClass().getSimpleName() + ": " + collision.getMessage() + ")";
        logger.error("Revision snapshot for page {} [{}] was NOT stored: {}",
                pageUuid, language, message, collision);
        recordFailure.accept(message);
        return CaptureStatus.FAILED;
    }

    /**
     * Absolute path of the deterministically-named snapshot for one capture.
     *
     * <p>Three other places build this same path by concatenation; consolidating them is a
     * reported finding of its own and deliberately not folded into this fix.
     */
    private static String snapshotPath(String siteKey, String pageUuid, String language,
                                       String snapshotName) {
        return "/sites/" + siteKey + "/contents/" + ROOT_FOLDER_NAME
                + '/' + pageUuid + '/' + language + '/' + snapshotName;
    }

    /** @return true only when the winner's snapshot is actually visible to this session */
    private static boolean storedByTheWinner(JCRSessionWrapper session, String siteKey,
                                             String pageUuid, String language,
                                             String snapshotName) {
        try {
            return session.nodeExists(snapshotPath(siteKey, pageUuid, language, snapshotName));
        } catch (RepositoryException unreadable) {
            // Unable to prove the winner stored it, so do not claim UNCHANGED on its behalf.
            logger.warn("Could not confirm whether snapshot {} exists after a collision on page"
                    + " {} [{}]", snapshotName, pageUuid, language, unreadable);
            return false;
        }
    }

    /**
     * Writes a capture outcome onto the per-language folder, creating the folder chain if the
     * page has never been captured.
     *
     * <p>Uses its own session so it still works when the capture session is unusable, which is
     * exactly the case it exists for.
     */
    public void recordStatus(String siteKey, String pageUuid, String language,
                             CaptureStatus status, String message) {
        try {
            validate(siteKey, pageUuid, language);
            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null, (JCRCallback<Void>) session -> {
                        JCRNodeWrapper folder = ensureFolder(session, siteKey, pageUuid, language);
                        folder.setProperty(PROP_LAST_CAPTURE_STATUS, status.name());
                        folder.setProperty(PROP_LAST_CAPTURE_MESSAGE, truncate(message));
                        folder.setProperty(PROP_LAST_CAPTURE_DATE, calendar(Instant.now()));
                        session.save();
                        return null;
                    });
        } catch (RepositoryException | RuntimeException e) {
            // Last resort only. If even the durable record cannot be written, the log is all
            // that is left -- but the normal path above is what the feature relies on.
            logger.error("Could not record capture status {} for page {} [{}]",
                    status, pageUuid, language, e);
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Package-private so {@link RevisionEntryBinder} enforces exactly the same rules on exactly
     * the same coordinates. These three values are concatenated into a repository path, so a
     * second, subtly different copy of this check elsewhere is how a path-traversal hole gets
     * introduced later by someone who only reads one of them.
     */
    /**
     * The one rule for what a site key may look like, so callers cannot disagree with capture.
     *
     * <p>SiteSettingsRegistry had its own, subtly different pattern: it accepted a dot and required
     * an alphanumeric first character, this one rejects a dot and allows a leading underscore. A key
     * with a dot was therefore accepted and persisted by the settings panel and then rejected by
     * every capture for that site, and a key starting with an underscore captured fine but could
     * never be given settings. Whichever way it fell, the two halves of the module disagreed about
     * a site that plainly exists.
     */
    static boolean isValidSiteKey(String siteKey) {
        return siteKey != null && SITE_KEY.matcher(siteKey).matches();
    }

    static void validate(String siteKey, String pageUuid, String language) {
        require(siteKey != null && SITE_KEY.matcher(siteKey).matches(), "siteKey", siteKey);
        require(pageUuid != null && UUID.matcher(pageUuid).matches(), "pageUuid", pageUuid);
        require(language != null && LANGUAGE.matcher(language).matches(), "language", language);
    }

    private static void require(boolean condition, String what, String value) {
        if (!condition) {
            throw new IllegalArgumentException("Illegal " + what + " for snapshot capture: " + value);
        }
    }

    private void createSnapshot(JCRSessionWrapper session, JCRNodeWrapper folder, String name,
                                String language, byte[] payload, String contentHash,
                                Instant instant, String capturedBy, String sourceUrl)
            throws RepositoryException {
        JCRNodeWrapper snapshot = folder.addNode(name, SNAPSHOT_TYPE);
        snapshot.setProperty(PROP_SNAPSHOT_DATE, calendar(instant));
        snapshot.setProperty(PROP_LANGUAGE, language);
        snapshot.setProperty(PROP_CONTENT_HASH, contentHash);
        snapshot.setProperty(PROP_GENERATOR_VERSION, MarkdownNormalizer.GENERATOR_VERSION);
        snapshot.setProperty(PROP_CAPTURED_BY, capturedBy);
        if (sourceUrl != null) {
            snapshot.setProperty(PROP_SOURCE_URL, sourceUrl);
        }
        // Binary, not string: every metadata read of a snapshot node would otherwise drag the
        // whole page's Markdown into memory with it.
        Binary binary = session.getValueFactory().createBinary(new ByteArrayInputStream(payload));
        try {
            snapshot.setProperty(PROP_MARKDOWN, session.getValueFactory().createValue(binary));
        } finally {
            // Every read path in this module disposes: SnapshotPayload and
            // SnapshotChoiceListInitializer both do.
            // the write path, which creates one on every stored capture, did not. The property keeps
            // its own copy once set, so releasing the handle here is safe and is what stops a busy
            // publishing site accumulating them.
            binary.dispose();
        }
    }

    private void updateFolderState(JCRNodeWrapper folder, String name, String contentHash,
                                   long count, Instant instant) throws RepositoryException {
        folder.setProperty(PROP_LATEST_HASH, contentHash);
        folder.setProperty(PROP_LATEST_SNAPSHOT, name);
        folder.setProperty(PROP_SNAPSHOT_COUNT, count);
        folder.setProperty(PROP_LAST_CAPTURE_STATUS, CaptureStatus.STORED.name());
        folder.setProperty(PROP_LAST_CAPTURE_MESSAGE, "");
        folder.setProperty(PROP_LAST_CAPTURE_DATE, calendar(instant));
    }

    private void markUnchanged(JCRNodeWrapper folder, JCRSessionWrapper session, Instant instant)
            throws RepositoryException {
        folder.setProperty(PROP_LAST_CAPTURE_STATUS, CaptureStatus.UNCHANGED.name());
        folder.setProperty(PROP_LAST_CAPTURE_MESSAGE, "");
        folder.setProperty(PROP_LAST_CAPTURE_DATE, calendar(instant));
        session.save();
    }

    /**
     * Enforces this site's retention: {@code retention.maxSnapshots}, defaulting to
     * {@link RevisionHistoryConstants#MAX_SNAPSHOTS_PER_PAGE_LANGUAGE}.
     *
     * <p>Only enumerates children once the denormalised counter says the cap is in reach, so
     * the common path stays O(1). Pruned snapshots are counted into
     * {@link RevisionHistoryConstants#PROP_PRUNED_COUNT}: history that was dropped has to be
     * visible as dropped, not indistinguishable from history that never existed.
     *
     * @return the number of snapshots remaining in the folder
     */
    /**
     * Does any revision entry name this snapshot as the content it describes?
     *
     * <p>{@code crh:entryRefs} lives on the snapshot rather than on the entry, so it is both the
     * binding and the only record of it. That makes this check the whole protection: once the node
     * is gone there is nothing left to consult.
     */
    private boolean hasEntryReferences(JCRNodeWrapper snapshot) throws RepositoryException {
        return snapshot.hasProperty(PROP_ENTRY_REFS)
                && snapshot.getProperty(PROP_ENTRY_REFS).getValues().length > 0;
    }

    private long pruneIfNeeded(JCRNodeWrapper folder, String siteKey) throws RepositoryException {
        int maxSnapshots = SiteSettingsRegistry.settingsFor(siteKey).getMaxSnapshots();
        long count = longProperty(folder, PROP_SNAPSHOT_COUNT, -1);
        if (count >= 0 && count < maxSnapshots) {
            return count;
        }
        return prune(folder, maxSnapshots);
    }

    /**
     * The pruning mechanism, separated from the policy lookup so it can be tested against an
     * explicit cap. Package-private for that reason, as {@link #validate} is.
     */
    long prune(JCRNodeWrapper folder, int maxSnapshots) throws RepositoryException {
        List<String> names = new ArrayList<>();
        for (JCRNodeWrapper child : folder.getNodes()) {
            if (child.isNodeType(SNAPSHOT_TYPE)) {
                names.add(child.getName());
            }
        }
        // Timestamp-prefixed names, so lexicographic order is chronological: oldest first.
        Collections.sort(names);
        int excess = names.size() - (maxSnapshots - 1);
        long pruned = 0;
        long protectedByReference = 0;
        // Oldest first, and NEVER the newest: it is the baseline the next capture's dedupe compares
        // against. Walking the whole list rather than just the first `excess` entries is what lets a
        // referenced snapshot be skipped and a younger unreferenced one taken in its place.
        // A while loop with a single if/else, deliberately. Expressed as a for loop this needs
        // either `pruned < excess` in the condition, which java:S1994 objects to because `pruned`
        // is updated in the body, or a break, which then joins the continue below and java:S135
        // objects to two jumps. Both objections are about readability and both are fair; this form
        // has neither, and reads as what it is: walk the oldest first, skip what is referenced,
        // stop once enough is freed.
        int index = 0;
        while (index < names.size() - 1 && pruned < excess) {
            JCRNodeWrapper candidate = folder.getNode(names.get(index));
            index++;
            if (hasEntryReferences(candidate)) {
                // A snapshot named by a revision entry is the EVIDENCE behind a published claim,
                // and deleting it does not merely lose the evidence: crh:entryRefs lives on the
                // snapshot, so removing the node destroys the only record that the entry described
                // it. The binder then sees that entry as never bound and, on the next capture,
                // binds it to the CURRENT snapshot -- so a years-old revision silently begins
                // claiming today's text, and a comparison against a recent revision reports the
                // page as unchanged. That is worse than exceeding the cap: it is the record
                // asserting something false, with no error anywhere.
                //
                // RevisionEntryBinder states the invariant this protects ("append-only: an entry
                // that already has a snapshot is never rebound"); it cannot enforce it alone,
                // because the property it relies on is deleted from underneath it.
                protectedByReference++;
            } else {
                candidate.remove();
                pruned++;
            }
        }
        if (pruned > 0) {
            folder.setProperty(PROP_PRUNED_COUNT, longProperty(folder, PROP_PRUNED_COUNT, 0) + pruned);
            logger.warn("Pruned {} oldest unreferenced snapshot(s) under {} to stay within the cap"
                    + " of {}", pruned, folder.getPath(), maxSnapshots);
        }
        if (protectedByReference > 0) {
            // Said once per capture and deliberately at WARN: retention is now advisory for this
            // page, and an operator who set a cap expecting it to be honoured needs to know why it
            // is not, rather than discovering the folder growing.
            logger.warn("Kept {} snapshot(s) under {} that a revision entry references, so this"
                    + " page holds {} snapshot(s) against a cap of {}. Retention will not delete"
                    + " the evidence behind a published revision; remove the revision entry first"
                    + " if a snapshot really must go.",
                    protectedByReference, folder.getPath(), names.size() - pruned, maxSnapshots);
        }
        return names.size() - pruned;
    }

    /** Creates the revision-history/&lt;uuid&gt;/&lt;lang&gt; chain on demand. */
    private JCRNodeWrapper ensureFolder(JCRSessionWrapper session, String siteKey,
                                        String pageUuid, String language) throws RepositoryException {
        requireKnownLanguage(session, siteKey, language);
        JCRNodeWrapper contents = session.getNode("/sites/" + siteKey + "/contents");
        JCRNodeWrapper root = childOrCreate(contents, ROOT_FOLDER_NAME, true);
        JCRNodeWrapper perPage = childOrCreate(root, pageUuid, false);
        return childOrCreate(perPage, language, false);
    }

    private void requireKnownLanguage(JCRSessionWrapper session, String siteKey, String language)
            throws RepositoryException {
        JCRNodeWrapper site = session.getNode("/sites/" + siteKey);
        if (site instanceof JCRSiteNode) {
            Set<String> languages = ((JCRSiteNode) site).getLanguages();
            if (languages != null && !languages.isEmpty() && !languages.contains(language)) {
                throw new IllegalArgumentException(
                        "Language " + language + " is not active on site " + siteKey);
            }
        }
    }

    private JCRNodeWrapper childOrCreate(JCRNodeWrapper parent, String name, boolean isHistoryRoot)
            throws RepositoryException {
        try {
            JCRNodeWrapper adopted = parent.getNode(name);
            if (isHistoryRoot) {
                restoreInheritance(adopted);
            }
            return adopted;
        } catch (PathNotFoundException notThereYet) {
            return parent.addNode(name, FOLDER_TYPE);
        }
    }

    /**
     * Puts the history root back on the site's own permissions, if an earlier version took it off.
     *
     * <p>Up to 1.3.x this tree had its ACL inheritance broken with nothing granted, on the reasoning
     * that an evidentiary record its own subjects can rewrite is not one. The reasoning was sound;
     * the implementation was not. It claimed to leave access to "server administrators, who bypass
     * ACLs", and that is simply untrue: in Jahia a server administrator is a ROLE, and a role is
     * delivered through ACL entries, which is exactly what breaking inheritance removes. Measured on
     * 8.2.x, every non-{@code root} account was denied -- editors, site administrators and server
     * administrators alike. Only {@code root} could read a snapshot, because {@code root} is the JCR
     * system user and bypasses the access manager itself rather than holding any role.
     *
     * <p>That made the feature unusable. An editor has to read a snapshot to write the revision
     * entry describing it, and the snapshot picker offers exactly those snapshots, so a store only
     * {@code root} can read is a store nobody can curate.
     *
     * <p>The tree now simply inherits from {@code /sites/<site>/contents}, so whoever may read and
     * write the site's content may read and write its snapshots too. The trade is deliberate and
     * worth stating plainly: a contributor who can delete content under {@code /contents} can now
     * delete a snapshot. What still holds is that this tree never reaches the live workspace
     * ({@code jmix:nolive}), and that a comparison served to a visitor still checks the CURRENT
     * user's own JCR rights before any snapshot is read -- see
     * {@code RevisionDiffService#viewerMayReadHistory}.
     *
     * <p>Called on every capture rather than only at creation, so an instance that already carries
     * the old lockdown is repaired by its next capture instead of needing a manual fix. It reads one
     * property and writes nothing in the normal case.
     */
    private void restoreInheritance(JCRNodeWrapper root) throws RepositoryException {
        if (!root.getAclInheritanceBreak()) {
            return;
        }
        root.setAclInheritanceBreak(false);
        logger.info("Restored inherited permissions on {}. Versions up to 1.3.x broke inheritance and"
                + " granted nothing, which left snapshots readable only by the system session and by"
                + " root -- not by administrators, whose privileges are themselves delivered through"
                + " ACL entries. Snapshots now follow the permissions of the site's content folder.",
                root.getPath());
    }

    private static String stringProperty(JCRNodeWrapper node, String name) throws RepositoryException {
        return node.hasProperty(name) ? node.getProperty(name).getString() : null;
    }

    private static long longProperty(JCRNodeWrapper node, String name, long fallback)
            throws RepositoryException {
        return node.hasProperty(name) ? node.getProperty(name).getLong() : fallback;
    }

    private static Calendar calendar(Instant instant) {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
        calendar.setTimeInMillis(instant.toEpochMilli());
        return calendar;
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
