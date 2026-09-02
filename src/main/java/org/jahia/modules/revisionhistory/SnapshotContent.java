package org.jahia.modules.revisionhistory;

/**
 * A snapshot's Markdown, and whether all of it was read.
 *
 * <p>The flag is the point of this type. {@link SnapshotPayload#read} used to return the Markdown
 * alone and log a WARN when it hit the read cap, so no caller could tell a complete snapshot from a
 * shortened one -- and a shortened one is indistinguishable from a page that was genuinely shorter.
 * The public comparison then computed a diff against partial text and reported lines as removed
 * that were never removed, presenting it as the record of what changed. A WARN reaches an operator
 * reading logs at the time; it never reached the visitor being shown the result, who is who the
 * record is for.
 *
 * <p>Modelled on {@code MarkdownDiff.Result.isTruncated()}, which already carries this fact for the
 * other truncation the panel can hit. They stay two separate flags because they are two different
 * facts: that one means the comparison was clipped for DISPLAY and the rest exists, this one means
 * its INPUT was incomplete.
 *
 * <p>Top-level rather than nested inside {@link SnapshotPayload}, for the same reason
 * {@link RevisionDiffView} is: it is named in the {@code function-signature} of a TLD entry, and
 * nested-class signatures are a known source of Jasper resolution failures that surface only at
 * first render, in a JSP, on the public site.
 */
public final class SnapshotContent {

    /** Nothing to read: no node, or no payload property on it. */
    static final SnapshotContent EMPTY = new SnapshotContent("", false);

    private final String markdown;
    private final boolean truncated;

    SnapshotContent(String markdown, boolean truncated) {
        this.markdown = markdown;
        this.truncated = truncated;
    }

    /** @return the Markdown that was read; never null */
    public String getMarkdown() {
        return markdown;
    }

    /**
     * @return whether the stored payload exceeded the read cap, so this holds only its start
     *
     * <p>Anything shown to a reader from a truncated payload has to say so. It is not a display
     * detail: a comparison computed from it asserts changes that did not happen.
     */
    public boolean isTruncated() {
        return truncated;
    }
}
