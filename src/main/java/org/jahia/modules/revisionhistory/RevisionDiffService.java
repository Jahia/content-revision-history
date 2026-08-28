package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * Compares any two revisions of a page.
 *
 * <p>Reads snapshots from the {@code default} workspace with a system session, deliberately.
 * Snapshots are never published, so nothing in {@code live} could serve this. That is defensible
 * and worth stating plainly, because it looks wrong at a glance: the snapshot is not a draft that
 * has escaped review, it is an immutable record <em>generated from the live page</em> -- captured
 * over HTTP as {@code guest}, which is exactly the content the visitor reading the comparison was
 * already entitled to see.
 *
 * <p><b>One comparison, on request.</b> An earlier design pre-rendered every adjacent comparison
 * so a popup could open with no round trip. That cannot extend to arbitrary pairs -- ten revisions
 * have forty-five of them, twenty have a hundred and ninety -- and a visitor asking "what changed
 * between the version I signed and today" is asking about a pair that is usually not adjacent. So
 * exactly one comparison is built, only when one is asked for, which also costs less than the
 * pre-rendering it replaced.
 *
 * <p><b>The selection is visitor input.</b> Both identifiers arrive from a form, and this service
 * reads with a session that bypasses ACLs, so both are proven to be children of the
 * <em>server-supplied</em> history node before anything is read. Without that check a crafted
 * value would render an arbitrary node onto a public page.
 *
 * <p>Failure is always a message, never an exception reaching the page. A revision history whose
 * comparison produces a stack trace is worse than one that says why it cannot compare.
 */
public class RevisionDiffService {

    private static final Logger logger = LoggerFactory.getLogger(RevisionDiffService.class);

    /** Matches a JCR identifier. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Why no comparison is shown. Resource-bundle key suffixes, resolved by the view, so the
     * reason is always stated to the visitor in their language instead of the panel rendering
     * empty.
     */
    public static final String REASON_NOT_FOUND = "notFound";
    public static final String REASON_SAME_REVISION = "sameRevision";
    public static final String REASON_NO_SNAPSHOT = "noSnapshot";
    public static final String REASON_NO_PREVIOUS_SNAPSHOT = "noPreviousSnapshot";

    /**
     * Compares two revisions of the same history.
     *
     * <p>The pair is ordered by {@code revisionDate} before diffing, so additions and removals are
     * always reported in chronological order no matter which way round the visitor picked them.
     *
     * @param historyIdentifier the {@code crh:revisionHistory} being rendered; server-supplied
     * @param oneIdentifier     one selected revision; <b>visitor-supplied, never trusted</b>
     * @param otherIdentifier   the other selected revision; likewise untrusted
     * @param language          the rendering language, selecting the snapshot partition
     * @return always a view; ask {@link RevisionDiffView#isAvailable()} before reading the diff
     */
    public RevisionDiffView compare(String historyIdentifier, String oneIdentifier,
                                    String otherIdentifier, String language) {
        if (!isIdentifier(historyIdentifier) || !isIdentifier(oneIdentifier)
                || !isIdentifier(otherIdentifier)) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
        if (oneIdentifier.equals(otherIdentifier)) {
            return RevisionDiffView.unavailable(REASON_SAME_REVISION, null);
        }
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null,
                    (JCRCallback<RevisionDiffView>) session ->
                            resolve(session, historyIdentifier, oneIdentifier, otherIdentifier,
                                    language));
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Could not compare revisions {} and {}", oneIdentifier, otherIdentifier, e);
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
    }

    private RevisionDiffView resolve(JCRSessionWrapper session, String historyIdentifier,
                                     String oneIdentifier, String otherIdentifier, String language)
            throws RepositoryException {

        JCRNodeWrapper history = nodeOrNull(session, historyIdentifier);
        if (history == null || !history.isNodeType(HISTORY_TYPE)) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }

        // Newest first, and the SAME ordering the list view renders, because both go through
        // RevisionEntryOrder. The list also decides which of the two selections is the older.
        List<JCRNodeWrapper> entries = RevisionEntryOrder.newestFirst(history);
        int newerIndex = indexOf(entries, oneIdentifier);
        int olderIndex = indexOf(entries, otherIdentifier);
        // Containment IS the access control: an identifier that is not an entry of THIS history
        // never reaches a repository read.
        if (newerIndex < 0 || olderIndex < 0) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
        if (newerIndex > olderIndex) {
            int swap = newerIndex;
            newerIndex = olderIndex;
            olderIndex = swap;
        }
        JCRNodeWrapper newer = entries.get(newerIndex);
        JCRNodeWrapper older = entries.get(olderIndex);

        String newerLabel = stringOrNull(newer, PROP_REVISION_LABEL);
        String olderLabel = stringOrNull(older, PROP_REVISION_LABEL);

        Map<String, JCRNodeWrapper> snapshotByEntry = snapshotsFor(session, history, language);
        JCRNodeWrapper newerSnapshot = snapshotByEntry.get(newer.getIdentifier());
        JCRNodeWrapper olderSnapshot = snapshotByEntry.get(older.getIdentifier());
        if (newerSnapshot == null) {
            return RevisionDiffView.unavailable(REASON_NO_SNAPSHOT, newerLabel);
        }
        if (olderSnapshot == null) {
            return RevisionDiffView.unavailable(REASON_NO_PREVIOUS_SNAPSHOT, olderLabel);
        }

        MarkdownDiff.Result diff = MarkdownDiff.compare(
                SnapshotPayload.read(olderSnapshot), SnapshotPayload.read(newerSnapshot));

        boolean mismatch = !equalStrings(
                stringOrNull(olderSnapshot, PROP_GENERATOR_VERSION),
                stringOrNull(newerSnapshot, PROP_GENERATOR_VERSION));

        return new RevisionDiffView(null, newerLabel, olderLabel,
                dateOrNull(newer), dateOrNull(older), diff, mismatch);
    }

    private static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static int indexOf(List<JCRNodeWrapper> entries, String identifier)
            throws RepositoryException {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getIdentifier().equals(identifier)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Inverts {@code crh:entryRefs} into entry identifier -&gt; snapshot, in one folder scan.
     *
     * <p>Bounded by {@code MAX_SNAPSHOTS_PER_PAGE_LANGUAGE} (500) and run only when a visitor
     * actually asks for a comparison, so the scan is cheaper than maintaining an index that could
     * fall out of step with the snapshots themselves.
     */
    private Map<String, JCRNodeWrapper> snapshotsFor(JCRSessionWrapper session,
                                                     JCRNodeWrapper history, String language)
            throws RepositoryException {
        JCRNodeWrapper page = enclosingPage(history);
        if (page == null) {
            return Collections.emptyMap();
        }
        String siteKey = page.getResolveSite().getSiteKey();
        try {
            RevisionSnapshotService.validate(siteKey, page.getIdentifier(), language);
        } catch (IllegalArgumentException rejected) {
            logger.warn("Refusing to look for snapshots of {} [{}]: {}",
                    page.getPath(), language, rejected.getMessage());
            return Collections.emptyMap();
        }

        Map<String, JCRNodeWrapper> byEntry = new HashMap<>();
        JCRNodeWrapper folder;
        try {
            folder = session.getNode("/sites/" + siteKey + "/contents/" + ROOT_FOLDER_NAME
                    + '/' + page.getIdentifier() + '/' + language);
        } catch (RepositoryException noHistoryYet) {
            return byEntry;
        }
        for (JCRNodeWrapper snapshot : folder.getNodes()) {
            if (!snapshot.isNodeType(SNAPSHOT_TYPE) || !snapshot.hasProperty(PROP_ENTRY_REFS)) {
                continue;
            }
            for (Value value : snapshot.getProperty(PROP_ENTRY_REFS).getValues()) {
                byEntry.put(value.getString(), snapshot);
            }
        }
        return byEntry;
    }

    /** Nearest ancestor page. The history component may sit several containers deep in a page. */
    private JCRNodeWrapper enclosingPage(JCRNodeWrapper node) throws RepositoryException {
        JCRNodeWrapper current = node;
        while (current != null && !"/".equals(current.getPath())) {
            if (current.isNodeType(PAGE_TYPE)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean equalStrings(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String stringOrNull(JCRNodeWrapper node, String property)
            throws RepositoryException {
        return node.hasProperty(property) ? node.getProperty(property).getString() : null;
    }

    private static Calendar dateOrNull(JCRNodeWrapper node) throws RepositoryException {
        return node.hasProperty(PROP_REVISION_DATE)
                ? node.getProperty(PROP_REVISION_DATE).getDate() : null;
    }

    private JCRNodeWrapper nodeOrNull(JCRSessionWrapper session, String identifier) {
        try {
            return session.getNodeByIdentifier(identifier);
        } catch (RepositoryException gone) { // includes ItemNotFoundException
            return null;
        }
    }
}
