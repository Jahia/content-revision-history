package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
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
 * has escaped review, it is an immutable record <em>generated from the live page</em>.
 *
 * <p><b>Who may read it is enforced here, not inferred from who captured it.</b> This class used
 * to rest its whole case on captures rendering as {@code guest} -- a snapshot could then contain
 * nothing the anonymous public was not already entitled to see, so an ACL-bypassing read of it
 * leaked nothing. That argument is not available once a deployment captures as a technical user
 * in order to give restricted pages a revision history, and an argument that holds only under a
 * configuration nobody re-checks is not a safety property. {@link #viewerMayReadHistory} makes it
 * one: the current user must be able to read the history node in their own session, or no
 * comparison is produced.
 *
 * <p><b>Known limit, by construction.</b> A snapshot has one visibility because one principal
 * flattened it to text; JCR permissions are per-node and per-viewer. So component-level ACLs
 * <em>inside</em> a revisioned page are not reflected per viewer: a snapshot shows what its
 * capture principal could read. Placing a revision history on a page whose components have
 * differing audiences is therefore an administrative decision, and the README says so.
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
    /** The workspace a visitor sees. Entries are publishable content; snapshots never are. */
    private static final String PUBLISHED_WORKSPACE = "live";

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
        if (!viewerMayReadHistory(historyIdentifier)) {
            // Deliberately the same answer as a bad identifier: a viewer who may not see this
            // history must not be able to tell it apart from one that does not exist.
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
        try {
            // Two workspaces, deliberately, because the two halves of a comparison are different
            // KINDS of thing. Revision entries are ordinary publishable content and the selector
            // beside them is built from the rendering workspace, so the panel has to describe the
            // same entries the visitor just chose from. Snapshots are never published at all, so
            // they can only come from `default`. Reading both from `default` meant the control
            // and its result could describe different revisions.
            final String entryWorkspace = renderingWorkspace();
            // Locale, not null: crh:snapshotRef is i18n, so without one the session cannot resolve
            // the pin and every pinned revision would compare as though it had never been pinned.
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, entryWorkspace,
                    MarkdownNormalizer.localeFor(language),
                    (JCRCallback<RevisionDiffView>) entrySession ->
                            JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
                                    null, WORKSPACE, null,
                                    (JCRCallback<RevisionDiffView>) snapshotSession ->
                                            resolve(entrySession, snapshotSession,
                                                    historyIdentifier, oneIdentifier,
                                                    otherIdentifier, language)));
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Could not compare revisions {} and {}", oneIdentifier, otherIdentifier, e);
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }
    }

    /**
     * @param entrySession    the rendering workspace: revision entries, their labels and dates
     * @param snapshotSession {@code default}: the snapshots, which are never published
     */
    private RevisionDiffView resolve(JCRSessionWrapper entrySession,
                                     JCRSessionWrapper snapshotSession, String historyIdentifier,
                                     String oneIdentifier, String otherIdentifier, String language)
            throws RepositoryException {

        JCRNodeWrapper history = nodeOrNull(entrySession, historyIdentifier);
        if (history == null || !history.isNodeType(HISTORY_TYPE)) {
            return RevisionDiffView.unavailable(REASON_NOT_FOUND, null);
        }

        // Newest first, from the SAME workspace and through the SAME comparator as the list
        // view, which is what makes the control and its result describe the same revisions.
        // Sharing RevisionEntryOrder was never sufficient on its own: a shared comparator over
        // different data still disagrees.
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

        Map<String, JCRNodeWrapper> snapshotByEntry =
                snapshotsFor(snapshotSession, history, language);
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

    /**
     * Does the <em>current</em> user have read access to this revision history?
     *
     * <p>Everything below this point runs with a system session that bypasses ACLs. This used to
     * add "and the snapshots it reads are locked down so that no ordinary user can read them
     * directly", which is no longer true and was dangerous left standing: a reviewer weighing
     * whether a configured capture principal is safe for a site would read it as a guarantee that
     * snapshots are unreadable to contributors, and enable privileged capture on that basis.
     * {@code RevisionSnapshotService.restoreInheritance} repairs the pre-1.4 lockdown on every
     * capture, so any contributor with read on {@code /sites/<site>/contents} can open a snapshot
     * in jContent and read it.
     *
     * <p>The gate was self-evidently safe while captures rendered as {@code guest}: a snapshot
     * could not contain anything the anonymous public was not already entitled to see, so who was
     * asking did not matter.
     *
     * <p>It stops being self-evident the moment a capture runs as anything else. A snapshot is a
     * single artifact flattened to text by a single principal, so it cannot answer "what may
     * <em>this</em> viewer see" the way a live render can. The check therefore has to happen
     * before any of it is read, and it is the viewer's own session that has to answer.
     *
     * <p>Reading the history node is necessary but NOT sufficient, which is what this used to
     * assume. "Being able to read the component means being able to read the page" holds only
     * while the component inherits the page's ACLs; an editor who breaks inheritance to make a
     * changelog public on a restricted page falsifies it. {@code snapshotsFor} therefore checks the
     * enclosing page as well, against the same viewer session.
     *
     * <p><b>Fails closed.</b> No request context, an unreadable node, a repository error -- all
     * of them deny. A permission check that cannot reach a verdict has not granted anything.
     *
     * <p>Package-private so a test can assert that verdict directly. Asserting it through
     * {@link #compare} instead proves nothing: with no repository, compare denies for a dozen
     * reasons at once, so the assertion passes just as well with this gate deleted.
     */
    boolean viewerMayReadHistory(String historyIdentifier) {
        return viewerMayRead(historyIdentifier);
    }

    /**
     * Can the current user read this node in their own session?
     *
     * <p>One implementation for both questions the gate asks -- the history component, and the page
     * the snapshots actually belong to. Two copies of a fail-closed permission check are two
     * chances for one of them to stop failing closed.
     */
    private boolean viewerMayRead(String identifier) {
        try {
            JCRSessionWrapper viewer = JCRSessionFactory.getInstance().getCurrentUserSession();
            return viewer != null && viewer.getNodeByIdentifier(identifier) != null;
        } catch (RepositoryException | RuntimeException denied) {
            logger.debug("Refusing access to {}: the current user cannot read it",
                    identifier, denied);
            return false;
        }
    }

    /**
     * The workspace the page is being rendered from: {@code live} for a visitor, {@code default}
     * inside jContent preview.
     *
     * <p>Following the render is what keeps preview honest too. Hardcoding {@code live} would
     * make an editor's preview of an unpublished entry answer "not found" for a revision that is
     * plainly on the screen in front of them.
     *
     * <p>Falls back to {@code live} when there is no request context, because the fallback for a
     * public-facing feature should be the published view, never the editorial one. Package-private
     * so that choice is pinned by a test rather than left to whoever edits this next.
     */
    static String renderingWorkspace() {
        try {
            JCRSessionWrapper current = JCRSessionFactory.getInstance().getCurrentUserSession();
            String name = current == null || current.getWorkspace() == null
                    ? null : current.getWorkspace().getName();
            return name == null || name.trim().isEmpty() ? PUBLISHED_WORKSPACE : name;
        } catch (RepositoryException | RuntimeException noContext) {
            logger.debug("No rendering workspace could be determined; comparing published entries",
                    noContext);
            return PUBLISHED_WORKSPACE;
        }
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
        // The PAGE, not only the history component. Reading the component was taken to imply
        // reading the page, and for a history inheriting its page's ACLs it does -- but an editor
        // can break inheritance on the component to publish a changelog on an otherwise restricted
        // page, which is a reasonable thing to want. The check then passed on a node deliberately
        // made public while everything served underneath it -- snapshots of the page, flattened by
        // a capture principal and read here with a system session -- came from the page that is
        // not. Authorise the object whose content is actually returned.
        if (!viewerMayRead(page.getIdentifier())) {
            logger.debug("Refusing snapshots of {}: the current user cannot read the page itself",
                    page.getPath());
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

        // An entry that NAMES its snapshot is honoured here as well as through the back-reference.
        //
        // crh:entryRefs is written by RevisionEntryBinder, and the binder runs only after a capture,
        // which happens only on publication. So an editor who pins an entry to a snapshot and does
        // not publish afterwards had a comparison that answered "No snapshot of the page was
        // recorded for this revision" while the snapshot existed and the entry named it. That is the
        // normal workflow for BACKFILLED history, where every entry has to be pinned by hand to a
        // historical snapshot, so the one case that most needs comparing was the one that could not.
        //
        // The pin wins over the back-reference when both exist: it is an explicit editorial
        // statement about which snapshot a revision describes, and the binder's automatic choice is
        // a default. Resolution is by name within THIS page-and-language folder only, exactly as the
        // binder does it, so an editor-supplied value cannot reach anything else in the repository.
        for (JCRNodeWrapper entry : history.getNodes()) {
            if (!entry.isNodeType(ENTRY_TYPE)) {
                continue;
            }
            // RevisionEntryBinder owns this reading, including the fallback to a value written
            // before crh:snapshotRef became i18n. Reading the property directly here would have
            // meant the comparison and the binder disagreeing about what an entry is pinned to.
            String name = RevisionEntryBinder.pinnedSnapshotName(entry);
            if (name == null) {
                continue;
            }
            JCRNodeWrapper pinned = snapshotNamed(folder, name);
            if (pinned != null) {
                byEntry.put(entry.getIdentifier(), pinned);
            } else {
                logger.warn("Revision {} names snapshot '{}', which is not a snapshot of {} [{}];"
                        + " the comparison will fall back to the bound snapshot, if any",
                        entry.getPath(), name, page.getPath(), language);
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

    /**
     * Resolves a snapshot name against ONE page-and-language folder, never as a path.
     *
     * <p>The value is editor-supplied, so it is looked up as a child of the folder rather than
     * interpolated: a name carrying a separator or a parent reference cannot reach another page's
     * history. Mirrors RevisionEntryBinder deliberately; the two must agree about what a pin means.
     */
    private JCRNodeWrapper snapshotNamed(JCRNodeWrapper folder, String name) {
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        try {
            JCRNodeWrapper snapshot = folder.getNode(name);
            return snapshot.isNodeType(SNAPSHOT_TYPE) ? snapshot : null;
        } catch (RepositoryException notThere) {
            return null;
        }
    }
}
