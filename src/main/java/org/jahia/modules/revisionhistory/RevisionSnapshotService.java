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

        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, WORKSPACE,
                (JCRCallback<CaptureStatus>) session -> {
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

                    long kept = pruneIfNeeded(folder);
                    createSnapshot(session, folder, name, language, payload, contentHash,
                            instant, principal, sourceUrl);
                    updateFolderState(folder, name, contentHash, kept + 1, instant);
                    try {
                        session.save();
                    } catch (ItemExistsException | InvalidItemStateException concurrent) {
                        // Another node or thread wrote the very same snapshot name, which by
                        // construction means the very same content for the very same
                        // publication. Nothing was lost; do not turn it into an error.
                        logger.info("Snapshot {} for page {} [{}] was written concurrently",
                                name, pageUuid, language);
                        session.refresh(false);
                        return CaptureStatus.UNCHANGED;
                    }
                    logger.info("Stored revision snapshot {} for page {} [{}] under {}",
                            name, pageUuid, language, folder.getPath());
                    return CaptureStatus.STORED;
                });
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
            JCRTemplate.getInstance().doExecuteWithSystemSession(null, WORKSPACE,
                    (JCRCallback<Void>) session -> {
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

    private void validate(String siteKey, String pageUuid, String language) {
        require(siteKey != null && SITE_KEY.matcher(siteKey).matches(), "siteKey", siteKey);
        require(pageUuid != null && UUID.matcher(pageUuid).matches(), "pageUuid", pageUuid);
        require(language != null && LANGUAGE.matcher(language).matches(), "language", language);
    }

    private void require(boolean condition, String what, String value) {
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
        Value value = session.getValueFactory().createValue(binary);
        snapshot.setProperty(PROP_MARKDOWN, value);
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
     * Enforces {@link RevisionHistoryConstants#MAX_SNAPSHOTS_PER_PAGE_LANGUAGE}.
     *
     * <p>Only enumerates children once the denormalised counter says the cap is in reach, so
     * the common path stays O(1). Pruned snapshots are counted into
     * {@link RevisionHistoryConstants#PROP_PRUNED_COUNT}: history that was dropped has to be
     * visible as dropped, not indistinguishable from history that never existed.
     *
     * @return the number of snapshots remaining in the folder
     */
    private long pruneIfNeeded(JCRNodeWrapper folder) throws RepositoryException {
        long count = longProperty(folder, PROP_SNAPSHOT_COUNT, -1);
        if (count >= 0 && count < MAX_SNAPSHOTS_PER_PAGE_LANGUAGE) {
            return count;
        }
        List<String> names = new ArrayList<>();
        for (JCRNodeWrapper child : folder.getNodes()) {
            if (child.isNodeType(SNAPSHOT_TYPE)) {
                names.add(child.getName());
            }
        }
        Collections.sort(names);
        int excess = names.size() - (MAX_SNAPSHOTS_PER_PAGE_LANGUAGE - 1);
        long pruned = 0;
        for (int i = 0; i < excess; i++) {
            folder.getNode(names.get(i)).remove();
            pruned++;
        }
        if (pruned > 0) {
            folder.setProperty(PROP_PRUNED_COUNT, longProperty(folder, PROP_PRUNED_COUNT, 0) + pruned);
            logger.warn("Pruned {} oldest snapshot(s) under {} to stay within the cap of {}",
                    pruned, folder.getPath(), MAX_SNAPSHOTS_PER_PAGE_LANGUAGE);
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

    private JCRNodeWrapper childOrCreate(JCRNodeWrapper parent, String name, boolean lockDown)
            throws RepositoryException {
        try {
            return parent.getNode(name);
        } catch (PathNotFoundException notThereYet) {
            JCRNodeWrapper created = parent.addNode(name, FOLDER_TYPE);
            if (lockDown) {
                restrictAccess(created);
            }
            return created;
        }
    }

    /**
     * Locks the revision-history root down at creation time.
     *
     * <p>Inherited from {@code /sites/<site>/contents}, this tree would be readable <em>and
     * writable</em> by every site contributor: any of them could read historical versions of
     * pages they cannot read, and edit or delete the record of what a page used to say. An
     * evidentiary record that its subjects can rewrite is not one.
     *
     * <p>Breaking inheritance and granting nothing leaves access to the system session (which
     * writes the snapshots) and to server administrators, who bypass ACLs. Nothing in the
     * public rendering path reads this tree -- {@code crh:revisionEntry} nodes, which are what
     * the site actually renders, live with the page and are unaffected.
     */
    private void restrictAccess(JCRNodeWrapper root) throws RepositoryException {
        root.setAclInheritanceBreak(true);
        logger.info("Created {} with inherited permissions broken; snapshots are system-only",
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
