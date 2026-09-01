package org.jahia.modules.revisionhistory;

/**
 * Durable outcome of one capture attempt, written to {@code crh:lastCaptureStatus} on the
 * per-language {@code crh:snapshotFolder}.
 *
 * <p>This exists because the feature rests on exactly one distinction: "no new snapshot
 * because nothing changed" versus "no new snapshot because something went wrong". Logging the
 * difference is not enough -- logs roll. Six months later the folder itself has to be able to
 * answer the question.
 */
public enum CaptureStatus {

    /** A new snapshot node was written. */
    STORED,
    /** Rendered fine, content identical to the previous snapshot. Nothing to record. */
    UNCHANGED,
    /** Rendered fine but produced no content. Never stored -- an empty snapshot is content loss. */
    EMPTY,
    /** Markdown exceeded {@link RevisionHistoryConstants#MAX_MARKDOWN_BYTES}. Refused, not truncated. */
    OVERSIZE,
    /** Refused by the per-page rate limiter. */
    RATE_LIMITED,
    /** The guest render did not return 200, or the page is not publicly readable. */
    NOT_PUBLIC,
    /**
     * Capture is switched off for this site.
     *
     * <p>Recorded rather than passed over in silence: the folder's crh:lastCaptureStatus is what
     * makes a gap in the record self-explaining, and "someone turned this off" is a different
     * answer from "the content did not change" or "the capture failed".
     */
    DISABLED,

    /** Rendering or storing threw. */
    FAILED
}
