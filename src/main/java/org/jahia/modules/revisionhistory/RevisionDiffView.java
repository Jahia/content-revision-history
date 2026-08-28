package org.jahia.modules.revisionhistory;

import java.util.Calendar;

/**
 * Everything the diff panel needs, resolved in one repository visit by
 * {@link RevisionDiffService} and then read purely as properties by the view.
 *
 * <p>Top-level rather than nested inside the service for a concrete reason: it is named in the
 * {@code function-signature} of a TLD entry, and nested-class signatures are a known source of
 * Jasper resolution failures that surface only at first render.
 *
 * <p>Immutable, and it holds no JCR objects. Once this is built the repository session is
 * finished with, so a JSP cannot trigger lazy loading against a closed session -- the classic
 * way a page that renders fine in tests fails under a real request.
 */
public final class RevisionDiffView {

    private final String reason;
    private final String currentLabel;
    private final String previousLabel;
    private final Calendar currentDate;
    private final Calendar previousDate;
    private final MarkdownDiff.Result diff;
    private final boolean generatorMismatch;

    RevisionDiffView(String reason, String currentLabel, String previousLabel,
                     Calendar currentDate, Calendar previousDate,
                     MarkdownDiff.Result diff, boolean generatorMismatch) {
        this.reason = reason;
        this.currentLabel = currentLabel;
        this.previousLabel = previousLabel;
        this.currentDate = currentDate;
        this.previousDate = previousDate;
        this.diff = diff;
        this.generatorMismatch = generatorMismatch;
    }

    static RevisionDiffView unavailable(String reason, String currentLabel) {
        return new RevisionDiffView(reason, currentLabel, null, null, null, null, false);
    }

    /** True when {@link #getDiff()} holds a comparison; false when {@link #getReason()} does. */
    public boolean isAvailable() {
        return reason == null;
    }

    /**
     * Why no comparison is shown: one of the {@code REASON_*} constants on
     * {@link RevisionDiffService}, used by the view as a resource-bundle key suffix. Null when
     * a comparison is available.
     */
    public String getReason() {
        return reason;
    }

    public String getCurrentLabel() {
        return currentLabel;
    }

    public String getPreviousLabel() {
        return previousLabel;
    }

    public Calendar getCurrentDate() {
        return currentDate;
    }

    public Calendar getPreviousDate() {
        return previousDate;
    }

    public MarkdownDiff.Result getDiff() {
        return diff;
    }

    /**
     * True when the two snapshots were produced by different generator versions.
     *
     * <p>The Markdown views can change between releases, so two snapshots of identical page
     * content can still differ textually. Unflagged, such a diff reads as an editorial change
     * that never happened -- on a page whose entire purpose is to be believed.
     */
    public boolean isGeneratorMismatch() {
        return generatorMismatch;
    }
}
