package org.jahia.modules.revisionhistory;

/**
 * The only surface the JSP views may call into.
 *
 * <p>Everything reachable from a view goes through here, so the boundary between "content a
 * visitor supplied" and "content this module renders" is one small, reviewable file rather than
 * being spread across JSPs. Both methods are total: they answer for every input, including
 * null, because a view has no sensible way to handle an exception thrown mid-render.
 */
public final class RevisionHistoryFunctions {

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
     * Compares a revision entry with the one before it.
     *
     * @param historyIdentifier the rendered {@code crh:revisionHistory}; server-supplied, and
     *                          what constrains which entries a visitor may ask about
     * @param entryIdentifier   from the query string, therefore untrusted
     * @param language          the rendering language
     * @return always a view; ask {@link RevisionDiffView#isAvailable()} before reading the diff
     */
    public static RevisionDiffView compare(String historyIdentifier, String entryIdentifier,
                                           String language) {
        return DIFF_SERVICE.compare(historyIdentifier, entryIdentifier, language);
    }
}
