package org.jahia.modules.revisionhistory;

import javax.jcr.RepositoryException;

/**
 * The only surface the JSP views may call into.
 *
 * <p>Everything reachable from a view goes through here, so the boundary between "content a
 * visitor supplied" and "content this module renders" is one small, reviewable file rather than
 * being spread across JSPs. Both methods are total: they answer for every input, including
 * null, because a view has no sensible way to handle an exception thrown mid-render.
 */
public final class RevisionHistoryFunctions {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(RevisionHistoryFunctions.class);

    /**
     * Stateless and cheap to construct, but there is no reason to build one per rendered page.
     */
    private static final RevisionDiffService DIFF_SERVICE = new RevisionDiffService();

    private RevisionHistoryFunctions() {
        // EL function holder
    }

    /**
     * Makes editor-authored rich text safe to emit unescaped.
     *
     * @see RichTextSanitizer
     */
    public static String sanitize(String html) {
        return RichTextSanitizer.sanitize(html);
    }

    /**
     * A captured snapshot's Markdown, for the jContent preview.
     *
     * <p>Reads through the caller's own session, deliberately: unlike the public comparison, this
     * is an editorial view of a single node, so whoever is previewing must already have been
     * allowed to see it. Escalating to a system session here would hand the snapshot tree to
     * anyone who could reach the preview.
     *
     * @return the Markdown, or an empty string if it cannot be read; never null, because a view
     *         has no sensible way to handle an exception thrown mid-render
     */
    public static String snapshotMarkdown(org.jahia.services.content.JCRNodeWrapper snapshot) {
        try {
            return SnapshotPayload.read(snapshot);
        } catch (RepositoryException e) {
            LOGGER.error("Could not read the snapshot payload for preview", e);
            return "";
        }
    }

    /**
     * A revision history's entries, newest first by {@code revisionDate}.
     *
     * <p>The list view renders in this order and {@link #compareAll} pairs in this order, so the
     * control that opens a comparison and the panel it opens can never mean different revisions.
     *
     * @see RevisionEntryOrder for why order is derived rather than positional
     */
    public static java.util.List<org.jahia.services.content.JCRNodeWrapper> orderedEntries(
            org.jahia.services.content.JCRNodeWrapper history) {
        return RevisionEntryOrder.newestFirst(history);
    }

    /**
     * Compares two revisions of the same history.
     *
     * <p>Both identifiers come from the visitor's form selection and are therefore untrusted; the
     * history node is server-supplied and is what constrains them to entries of that history.
     *
     * @return always a view; ask {@link RevisionDiffView#isAvailable()} before reading the diff
     */
    public static RevisionDiffView compare(String historyIdentifier, String oneIdentifier,
                                           String otherIdentifier, String language) {
        return DIFF_SERVICE.compare(historyIdentifier, oneIdentifier, otherIdentifier, language);
    }
}
