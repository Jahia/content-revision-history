package org.jahia.modules.revisionhistory;

/**
 * Every JCR name and every hard cap the module relies on, in one place.
 *
 * <p>Node type and property names were previously repeated as string literals across the
 * service, the capture trigger and the CND. A typo in any one of them fails at runtime, on a
 * write path whose whole purpose is an auditable record, so they live here and nowhere else.
 */
public final class RevisionHistoryConstants {

    private RevisionHistoryConstants() {
        // constants holder
    }

    // ---------------------------------------------------------------- node types

    /** Opt-in marker mixin. Only pages carrying it are snapshotted. */
    public static final String REVISIONED_PAGE_MIXIN = "jmix:publiclyRevisioned";
    public static final String PAGE_TYPE = "jnt:page";
    public static final String SNAPSHOT_TYPE = "crh:revisionSnapshot";
    public static final String FOLDER_TYPE = "crh:snapshotFolder";

    // ---------------------------------------------------------------- layout

    /** Folder created under {@code /sites/<siteKey>/contents}. */
    public static final String ROOT_FOLDER_NAME = "revision-history";
    /** Snapshots are written to the editing workspace only and are never published. */
    public static final String WORKSPACE = "default";
    /** Template type of the Markdown views ({@code jnt_page/markdown/page.jsp} etc.). */
    public static final String MARKDOWN_TEMPLATE_TYPE = "markdown";

    // ---------------------------------------------------- crh:revisionSnapshot properties

    public static final String PROP_SNAPSHOT_DATE = "crh:snapshotDate";
    public static final String PROP_LANGUAGE = "crh:language";
    public static final String PROP_CONTENT_HASH = "crh:contentHash";
    public static final String PROP_GENERATOR_VERSION = "crh:generatorVersion";
    /**
     * Principal a capture runs as when none is configured, which is the default.
     *
     * <p>This said "always {@code guest} by construction" until capture became configurable. It
     * is now the DEFAULT rather than a guarantee: see {@code CaptureIdentity}, and
     * {@code SnapshotCaptureJob#principalOfRecord} for what actually reaches
     * {@code crh:capturedBy}.
     */
    public static final String PROP_CAPTURED_BY = "crh:capturedBy";
    public static final String PROP_SOURCE_URL = "crh:sourceUrl";
    public static final String PROP_MARKDOWN = "crh:markdown";

    // ------------------------------------------------------ crh:snapshotFolder properties

    public static final String PROP_LATEST_HASH = "crh:latestHash";
    public static final String PROP_LATEST_SNAPSHOT = "crh:latestSnapshot";
    public static final String PROP_SNAPSHOT_COUNT = "crh:snapshotCount";
    public static final String PROP_PRUNED_COUNT = "crh:prunedCount";
    public static final String PROP_LAST_CAPTURE_STATUS = "crh:lastCaptureStatus";
    public static final String PROP_LAST_CAPTURE_MESSAGE = "crh:lastCaptureMessage";
    public static final String PROP_LAST_CAPTURE_DATE = "crh:lastCaptureDate";

    // ------------------------------------------------------- crh:revisionEntry properties

    /** Editorial container an editor drops on a page; holds the public revision entries. */
    public static final String HISTORY_TYPE = "crh:revisionHistory";
    /** One public revision, authored by an editor. */
    public static final String ENTRY_TYPE = "crh:revisionEntry";
    public static final String PROP_REVISION_LABEL = "revisionLabel";
    public static final String PROP_REVISION_DATE = "revisionDate";

    /**
     * Weak back-references from a snapshot to the {@code crh:revisionEntry} nodes it is the
     * content for. Lives on the snapshot, not on the entry: see the note in the CND.
     */
    public static final String PROP_ENTRY_REFS = "crh:entryRefs";

    /**
     * Optional, editor-set: the name of the snapshot this entry describes.
     *
     * <p>Empty means "whatever is current", which is the right answer for the normal editorial
     * rhythm of one publication producing one entry and one snapshot. It is the wrong answer for
     * backfilled history, where many snapshots already exist and the entries describing them are
     * written afterwards -- see {@link SnapshotChoiceListInitializer}.
     */
    public static final String PROP_SNAPSHOT_REF = "crh:snapshotRef";

    /**
     * Upper bound on nodes walked when looking for revision entries under a page. Pages hold
     * editorial content, not bulk data, so anything approaching this is a malformed tree
     * rather than a page -- and an unbounded walk on a capture path is how a background job
     * takes a node down.
     */
    public static final int MAX_NODES_WALKED_PER_PAGE = 5_000;

    // ---------------------------------------------------------------- capture identity

    /**
     * The one and only principal a capture may run as.
     *
     * <p>A snapshot is published to the world, so it must be built from what the world can
     * see. Rendering as anybody else -- in particular as whoever happened to trigger the
     * capture -- would let ACL-filtered content leak into a public record.
     */
    public static final String CAPTURE_PRINCIPAL = "guest";

    // ---------------------------------------------------------------- hard caps

    /**
     * Maximum size of a single Markdown snapshot. Beyond this the capture is refused and
     * recorded as {@link CaptureStatus#OVERSIZE} rather than truncated: a truncated snapshot
     * is a falsified record.
     */
    public static final int MAX_MARKDOWN_BYTES = 1024 * 1024;

    /**
     * Maximum number of snapshots kept per page and language. Once reached, the oldest are
     * pruned and the running total is recorded in {@link #PROP_PRUNED_COUNT}, so the loss is
     * visible rather than silent.
     */
    public static final int MAX_SNAPSHOTS_PER_PAGE_LANGUAGE = 500;

    /**
     * Minimum wall-clock gap between two capture attempts for the same page and language.
     *
     * <p>Deliberately short. Its job is only to collapse near-simultaneous duplicate events
     * for a single editorial action; the actual bound on work is
     * {@link #MAX_CAPTURES_PER_WINDOW}. A long interval would trade the abuse it no longer
     * needs to prevent -- capture is authenticated and publication-gated now -- against
     * dropping a real revision, which is the exact failure this whole design removes. When it
     * does refuse, the refusal is recorded on the folder, so it is never a silent hole.
     */
    public static final long MIN_CAPTURE_INTERVAL_MILLIS = 1_000L;

    /**
     * Maximum capture attempts per page and language within {@link #RATE_WINDOW_MILLIS}. Far
     * above any human editing rate, and low enough that no page can consume the node.
     */
    public static final int MAX_CAPTURES_PER_WINDOW = 60;

    public static final long RATE_WINDOW_MILLIS = 60L * 60L * 1000L;

    /** Upper bound on how many distinct pages one publication event may enqueue. */
    public static final int MAX_PAGES_PER_PUBLICATION = 500;

    /** Upper bound on nodes inspected per publication event when resolving pages. */
    public static final int MAX_PUBLICATION_INFOS_INSPECTED = 20_000;

    // The capture endpoint used to be the system property jahia.crh.captureBaseUrl. It is now
    // CaptureEndpoint.PROP_BASE_URL in this module's own OSGi configuration: a system property needs
    // a restart to change, is visible to anyone who can list processes, and was the only setting in
    // this module that lived outside its configuration file.
}
