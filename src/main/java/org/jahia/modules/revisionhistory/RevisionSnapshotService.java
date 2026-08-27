package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Append-only store for Markdown page snapshots.
 *
 * <p>Snapshots live in site-local content at
 * {@code /sites/<siteKey>/contents/revision-history/<pageUuid>/<lang>/<timestamp>} and are
 * written to the {@code default} workspace only -- never published. Rationale:
 *
 * <ul>
 *   <li>Site-local content travels with site export, backup and migration; {@code /settings}
 *       does not, and this data outlives the pages it describes.</li>
 *   <li>Never publishing avoids default/live divergence and any nested publication.</li>
 *   <li>Keyed on UUID, not path, so renaming or moving a page keeps its history.</li>
 *   <li>The {@code <pageUuid>} level is a JCR performance guard: Jackrabbit keeps a
 *       child-node-entry list per parent, so partitioning per page bounds each folder.</li>
 * </ul>
 */
public class RevisionSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(RevisionSnapshotService.class);

    public static final String FOLDER = "revision-history";
    public static final String SNAPSHOT_TYPE = "crh:revisionSnapshot";
    private static final String CONTENT_FOLDER_TYPE = "jnt:contentFolder";
    private static final String WORKSPACE = "default";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withLocale(Locale.ROOT);

    /**
     * Stores a snapshot unless the newest existing one has identical content.
     *
     * @return true if a snapshot node was created, false if deduped away
     */
    public boolean captureIfChanged(String siteKey, String pageUuid, String language,
                                    String markdown, String fallbackTypes) throws RepositoryException {
        final String contentHash = MarkdownNormalizer.hash(markdown);
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null,
                (JCRCallback<Boolean>) session -> {
                    JCRNodeWrapper languageFolder = ensureFolder(session, siteKey, pageUuid, language);
                    if (contentHash.equals(newestHash(languageFolder))) {
                        logger.debug("Snapshot for page {} [{}] unchanged (hash {}), skipping",
                                pageUuid, language, contentHash);
                        return Boolean.FALSE;
                    }
                    createSnapshot(languageFolder, language, markdown, contentHash, fallbackTypes);
                    session.save();
                    logger.info("Stored revision snapshot for page {} [{}] under {}",
                            pageUuid, language, languageFolder.getPath());
                    return Boolean.TRUE;
                });
    }

    /** Content hash of the most recent snapshot, or null when there is none. */
    private String newestHash(JCRNodeWrapper languageFolder) throws RepositoryException {
        String newestName = null;
        JCRNodeWrapper newest = null;
        for (JCRNodeWrapper child : languageFolder.getNodes()) {
            if (!child.isNodeType(SNAPSHOT_TYPE)) {
                continue;
            }
            // Names are sortable timestamps, so lexicographic max is chronological max.
            if (newestName == null || child.getName().compareTo(newestName) > 0) {
                newestName = child.getName();
                newest = child;
            }
        }
        return newest == null ? null : newest.getPropertyAsString("contentHash");
    }

    private void createSnapshot(JCRNodeWrapper languageFolder, String language, String markdown,
                               String contentHash, String fallbackTypes) throws RepositoryException {
        Calendar now = Calendar.getInstance(TimeZone.getDefault());
        String name = STAMP.format(now.toInstant().atZone(TimeZone.getDefault().toZoneId()));
        // Collision only if two captures land in the same second; suffix keeps it deterministic.
        String unique = languageFolder.hasNode(name) ? name + "-" + contentHash.substring(0, 6) : name;

        JCRNodeWrapper snapshot = languageFolder.addNode(unique, SNAPSHOT_TYPE);
        snapshot.setProperty("snapshotDate", now);
        snapshot.setProperty("language", language);
        snapshot.setProperty("contentHash", contentHash);
        snapshot.setProperty("generatorVersion", MarkdownNormalizer.GENERATOR_VERSION);
        snapshot.setProperty("markdown", markdown);
        if (fallbackTypes != null && !fallbackTypes.isEmpty()) {
            snapshot.setProperty("fallbackTypes", fallbackTypes.split(","));
        }
    }

    /** Creates the revision-history/<uuid>/<lang> chain on demand. */
    private JCRNodeWrapper ensureFolder(JCRSessionWrapper session, String siteKey,
                                        String pageUuid, String language) throws RepositoryException {
        JCRNodeWrapper contents = session.getNode("/sites/" + siteKey + "/contents");
        JCRNodeWrapper root = childOrCreate(contents, FOLDER);
        JCRNodeWrapper perPage = childOrCreate(root, pageUuid);
        return childOrCreate(perPage, language);
    }

    private JCRNodeWrapper childOrCreate(JCRNodeWrapper parent, String name) throws RepositoryException {
        try {
            return parent.getNode(name);
        } catch (PathNotFoundException notThereYet) {
            return parent.addNode(name, CONTENT_FOLDER_TYPE);
        }
    }
}
