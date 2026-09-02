package org.jahia.modules.revisionhistory;

/**
 * Raised when view output is too large to normalise, instead of normalising part of it.
 *
 * <p>The module's rule everywhere else is refuse, never truncate: output over
 * {@link RevisionHistoryConstants#MAX_MARKDOWN_BYTES} is recorded as
 * {@link CaptureStatus#OVERSIZE} and no snapshot is written, and {@code MarkdownDiff.Result}
 * carries an {@code isTruncated} flag so a clipped comparison can say so. The input cap in
 * {@link MarkdownNormalizer} was the one place that silently dropped the tail and returned the
 * remainder as though it were the whole document.
 *
 * <p>That is the worst shape a defect can take here. A snapshot is a permanent, publicly served
 * record, and a truncated one is <em>indistinguishable from a page that was genuinely shorter</em>:
 * it does not look like damage, it looks like history. A later comparison then reports text as
 * having been removed on a date when nothing was removed at all.
 *
 * <p>Unchecked on purpose. The alternative forces every caller to handle it, including the backfill
 * script -- which wants exactly this abort -- and the safety net is sound either way: the one
 * production caller maps it to {@code OVERSIZE}, and the generic {@code RuntimeException} handler
 * around it would otherwise record {@code FAILED}. Both are loud refusals that store nothing, so
 * forgetting to catch this cannot degrade into storing a partial record.
 */
public class MarkdownTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int length;
    private final int cap;

    MarkdownTooLargeException(int length, int cap) {
        super("View output is " + length + " characters, and the normaliser accepts at most " + cap
                + ". Refused rather than truncated: normalising the first " + cap + " characters"
                + " would produce a snapshot missing its tail, which is indistinguishable from a"
                + " page that was genuinely shorter. If this page really is this large, raise"
                + " MarkdownNormalizer.MAX_INPUT_CHARS and RevisionHistoryConstants"
                + ".MAX_MARKDOWN_BYTES together; raising only one moves the refusal rather than"
                + " lifting it.");
        this.length = length;
        this.cap = cap;
    }

    /** @return the length of the output that was refused */
    public int getLength() {
        return length;
    }

    /** @return the cap it exceeded */
    public int getCap() {
        return cap;
    }
}
