package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * Answers "what changed in this revision" for the public page.
 *
 * <p>Reads snapshots from the {@code default} workspace with a system session, deliberately.
 * Snapshots are never published, so nothing in {@code live} could serve this. That is defensible
 * and worth stating plainly, because it looks wrong at a glance: the snapshot is not a draft
 * that has escaped review, it is an immutable record <em>generated from the live page</em> --
 * captured over HTTP as {@code guest}, which is exactly the content the visitor asking for the
 * comparison was already entitled to see.
 *
 * <p>Failure is always a message, never an exception reaching the page. A revision history whose
 * comparison link produces a stack trace is worse than one that says why it cannot compare.
 */
public class RevisionDiffService {

    private static final Logger logger = LoggerFactory.getLogger(RevisionDiffService.class);

    /** Matches a JCR identifier. Applied before any lookup, since this value is user-supplied. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Why no comparison is shown. Resource-bundle key suffixes, resolved by the view, so the
     * reason is always stated to the visitor in their language instead of the panel silently
     * rendering empty.
     */
    public static final String REASON_NOT_FOUND = "notFound";
    public static final String REASON_NO_PREVIOUS = "noPrevious";
    public static final String REASON_NO_SNAPSHOT = "noSnapshot";
    public static final String REASON_NO_PREVIOUS_SNAPSHOT = "noPreviousSnapshot";

    /**
     * @param historyIdentifier the {@code crh:revisionHistory} being rendered; server-supplied
     * @param entryIdentifier   the entry to compare; <b>visitor-supplied, never trusted</b>
     * @param language          the rendering language, selecting the snapshot partition
     */
    public RevisionDiffView compare(String historyIdentifier, String entryIdentifier, String language) {
        if (entryIdentifier == null || !IDENTIFIER.matcher(entryIdentifier).matches()
                || historyIdentifier == null || !IDENTIFIER.matcher(historyIdentifier).matches()) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null,
                    (JCRCallback<RevisionDiffView>) session ->
                            resolve(session, historyIdentifier, entryIdentifier, language));
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Could not build the revision comparison for entry {}", entryIdentifier, e);
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
    }

    private RevisionDiffView resolve(JCRSessionWrapper session, String historyIdentifier,
                             String entryIdentifier, String language) throws RepositoryException {

        JCRNodeWrapper entry = nodeOrNull(session, entryIdentifier);
        JCRNodeWrapper history = nodeOrNull(session, historyIdentifier);
        if (entry == null || history == null
                || !entry.isNodeType(ENTRY_TYPE) || !history.isNodeType(HISTORY_TYPE)
                // The containment check is the access control. Without it, any visitor could
                // put an arbitrary identifier in the query string and have this service read a
                // node -- with a SYSTEM session, which bypasses ACLs -- and render its content.
                // The history node itself is server-supplied, so proving the entry belongs to it
                // is what keeps a rendered comparison inside the page it was requested from.
                || !history.getIdentifier().equals(entry.getParent().getIdentifier())) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }

        String currentLabel = stringOrNull(entry, PROP_REVISION_LABEL);

        JCRNodeWrapper previous = previousSibling(history, entryIdentifier);
        if (previous == null) {
            return RevisionDiffView.unavailable(REASON_NO_PREVIOUS, currentLabel);
        }

        JCRNodeWrapper page = enclosingPage(history);
        if (page == null) {
            return RevisionDiffView.unavailable(REASON_NO_SNAPSHOT, currentLabel);
        }
        String siteKey = page.getResolveSite().getSiteKey();
        RevisionSnapshotService.validate(siteKey, page.getIdentifier(), language);

        Map<String, JCRNodeWrapper> byEntry =
                snapshotsByEntry(session, siteKey, page.getIdentifier(), language);

        JCRNodeWrapper currentSnapshot = byEntry.get(entryIdentifier);
        if (currentSnapshot == null) {
            return RevisionDiffView.unavailable(REASON_NO_SNAPSHOT, currentLabel);
        }
        JCRNodeWrapper previousSnapshot = byEntry.get(previous.getIdentifier());
        if (previousSnapshot == null) {
            return RevisionDiffView.unavailable(REASON_NO_PREVIOUS_SNAPSHOT, currentLabel);
        }

        MarkdownDiff.Result diff = MarkdownDiff.compare(
                readMarkdown(previousSnapshot), readMarkdown(currentSnapshot));

        boolean mismatch = !equalStrings(
                stringOrNull(previousSnapshot, PROP_GENERATOR_VERSION),
                stringOrNull(currentSnapshot, PROP_GENERATOR_VERSION));

        return new RevisionDiffView(null, currentLabel, stringOrNull(previous, PROP_REVISION_LABEL),
                dateOrNull(entry), dateOrNull(previous), diff, mismatch);
    }

    // ------------------------------------------------------------------ internals

    /**
     * The entry immediately after this one in editorial order, i.e. the older revision.
     *
     * <p>Positional, not sorted by {@code revisionDate}, matching what the list view renders and
     * documents: {@code crh:revisionHistory} extends {@code jmix:list}, so editors order entries
     * by drag-and-drop and the convention is newest-first. Sorting by date here while the view
     * sorts by position would let the two disagree about which revision "previous" means, and
     * the comparison would silently describe a different pair than the link that opened it.
     */
    private JCRNodeWrapper previousSibling(JCRNodeWrapper history, String entryIdentifier)
            throws RepositoryException {
        boolean found = false;
        for (JCRNodeWrapper sibling : history.getNodes()) {
            if (found && sibling.isNodeType(ENTRY_TYPE)) {
                return sibling;
            }
            if (sibling.getIdentifier().equals(entryIdentifier)) {
                found = true;
            }
        }
        return null;
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

    /**
     * Inverts {@code crh:entryRefs} into entry identifier -&gt; snapshot, in one folder scan.
     *
     * <p>Bounded by {@code MAX_SNAPSHOTS_PER_PAGE_LANGUAGE} (500) and runs only when a visitor
     * explicitly asks for a comparison, so the scan is cheaper than maintaining an index that
     * could fall out of step with the snapshots themselves.
     */
    private Map<String, JCRNodeWrapper> snapshotsByEntry(JCRSessionWrapper session, String siteKey,
                                                         String pageUuid, String language)
            throws RepositoryException {
        Map<String, JCRNodeWrapper> byEntry = new HashMap<>();
        JCRNodeWrapper folder;
        try {
            folder = session.getNode("/sites/" + siteKey + "/contents/" + ROOT_FOLDER_NAME
                    + '/' + pageUuid + '/' + language);
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

    /**
     * Reads a snapshot's Markdown payload.
     *
     * <p>The cap is re-applied on read even though capture enforces it on write: this is a
     * public request path, and it must not become a way to pull an arbitrarily large binary
     * into heap because something else wrote one.
     */
    private String readMarkdown(JCRNodeWrapper snapshot) throws RepositoryException {
        if (!snapshot.hasProperty(PROP_MARKDOWN)) {
            return "";
        }
        Binary binary = snapshot.getProperty(PROP_MARKDOWN).getBinary();
        try (InputStream in = binary.getStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > MAX_MARKDOWN_BYTES) {
                    logger.warn("Snapshot {} exceeds the {} byte cap on read; comparison truncated",
                            snapshot.getPath(), MAX_MARKDOWN_BYTES);
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RepositoryException("Could not read snapshot " + snapshot.getPath(), e);
        } finally {
            binary.dispose();
        }
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
