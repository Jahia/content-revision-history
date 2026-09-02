package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.*;

/**
 * Joins the two halves of this module: the editor's revision entries and the captured
 * snapshots.
 *
 * <p>Until now they were unconnected. {@code crh:revisionEntry} declared a {@code snapshotRef}
 * that no code ever wrote, so a "Compare" control had nothing to compare and the public
 * history was a list of assertions with no evidence behind it.
 *
 * <h2>When a binding happens</h2>
 * After every capture <em>attempt</em> that leaves the store consistent with the live page --
 * {@link CaptureStatus#STORED} and {@link CaptureStatus#UNCHANGED} alike. Both are needed,
 * because the two editorial habits produce different outcomes and both must work:
 * <ul>
 *   <li>Editor changes the page <em>and</em> describes the change in one publication: capture
 *       stores a new snapshot, and the new entry binds to it.</li>
 *   <li>Editor publishes the change first and writes the entry afterwards: the second
 *       publication changes no page content, so capture reports {@code UNCHANGED} -- and the
 *       entry must still bind, to the snapshot that is already the current one.</li>
 * </ul>
 * Deliberately excluded are {@code RATE_LIMITED}, {@code FAILED} and the rest: there the latest
 * snapshot is <em>known</em> not to reflect the live page, and binding an entry to content it
 * does not describe would fabricate evidence. Leaving it unbound is honest, and the next
 * publication binds it correctly.
 *
 * <h2>Which way the reference points</h2>
 * The reference lives on the snapshot ({@code crh:entryRefs}), not on the entry. A snapshot is
 * system content in the {@code default} workspace that is never published; an entry is
 * editorial content. Writing to the entry would bump its {@code jcr:lastModified} and leave the
 * page flagged "modified" in jContent the moment capture ran -- seconds after the editor
 * published it -- so every revision would need publishing twice. See the CND for the full note.
 *
 * <p>Binding is <b>append-only</b>: an entry that already has a snapshot is never rebound, or a
 * later capture would silently rewrite what an existing public revision claims the page said.
 *
 * <p><b>Concurrency, and its bound.</b> Two capture jobs for the same page and language could in
 * principle both see an entry as unbound and bind it to two different snapshots, leaving the
 * lookup to pick whichever it met last. The capture rate limiter REJECTS a second attempt for the
 * same page and language within its minimum interval -- it rejects, it does not serialise, and it
 * puts no bound on how long an accepted capture then runs, so two publications far enough apart to
 * both be admitted can still overlap. It narrows the window rather than closing it; and the
 * consequence if it ever opened is a comparison against a neighbouring snapshot, not a lost or
 * altered record. Locking a page for the duration of a background walk would cost more than that.
 */
public class RevisionEntryBinder {

    private static final Logger logger = LoggerFactory.getLogger(RevisionEntryBinder.class);

    /**
     * Binds every not-yet-bound revision entry on the page to the current snapshot.
     *
     * <p>Never throws: this runs at the tail of a capture job whose real work has already
     * succeeded, and a binding failure must not turn a stored snapshot into a reported failure.
     * It is logged instead, and the next publication retries it for free.
     *
     * @return the number of entries newly bound
     */
    public int bindNewEntries(String siteKey, String pageUuid, String language) {
        try {
            RevisionSnapshotService.validate(siteKey, pageUuid, language);
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, WORKSPACE, null,
                    (JCRCallback<Integer>) session -> bind(session, siteKey, pageUuid, language));
        } catch (RepositoryException | RuntimeException e) {
            logger.error("Could not bind revision entries for page {} [{}]", pageUuid, language, e);
            return 0;
        }
    }

    private int bind(JCRSessionWrapper session, String siteKey, String pageUuid, String language)
            throws RepositoryException {

        JCRNodeWrapper folder = languageFolder(session, siteKey, pageUuid, language);
        if (folder == null) {
            return 0;
        }
        JCRNodeWrapper page = nodeOrNull(session, pageUuid);
        if (page == null) {
            return 0;
        }

        // May be null: a page whose first capture has not landed yet still has entries to pin, and
        // an entry that names its own snapshot does not need a current one to exist.
        JCRNodeWrapper latest = latestSnapshot(folder);

        Map<String, JCRNodeWrapper> boundTo = snapshotByBoundEntry(folder);
        List<JCRNodeWrapper> unbound = new ArrayList<>();
        collectEntries(page, boundTo.keySet(), unbound, new int[]{0});

        int moved = repin(session, folder, page, boundTo);
        if (unbound.isEmpty()) {
            if (moved > 0) {
                session.save();
            }
            return moved;
        }

        Map<String, List<JCRNodeWrapper>> byTarget = new LinkedHashMap<>();
        for (JCRNodeWrapper entry : unbound) {
            JCRNodeWrapper target = targetFor(folder, entry, latest);
            if (target == null) {
                continue;
            }
            byTarget.computeIfAbsent(target.getName(), name -> new ArrayList<>()).add(entry);
        }
        if (byTarget.isEmpty() && moved == 0) {
            logger.debug("Nothing to bind for page {} [{}]", pageUuid, language);
            return 0;
        }

        int newlyBound = 0;
        for (Map.Entry<String, List<JCRNodeWrapper>> group : byTarget.entrySet()) {
            appendReferences(session, folder.getNode(group.getKey()), group.getValue());
            newlyBound += group.getValue().size();
            logger.info("Bound {} revision entr{} on page {} [{}] to snapshot {}",
                    group.getValue().size(), group.getValue().size() == 1 ? "y" : "ies",
                    pageUuid, language, group.getKey());
        }
        session.save();
        return newlyBound + moved;
    }

    /**
     * Which snapshot an entry should be attached to.
     *
     * <p>An entry that names one ({@code crh:snapshotRef}) gets that one. An entry that names none
     * gets the current snapshot, which is the whole of the previous behaviour.
     *
     * <p>A named snapshot that is gone -- pruned, or the name mistyped by an import -- returns
     * null, leaving the entry unbound. It deliberately does NOT fall back to the current snapshot:
     * that would attach a revision to content it does not describe, silently, which is the one
     * failure this module exists to prevent. Unbound reports "no snapshot recorded", which is true.
     */
    private JCRNodeWrapper targetFor(JCRNodeWrapper folder, JCRNodeWrapper entry,
                                     JCRNodeWrapper latest) throws RepositoryException {
        String pinned = pinnedName(entry);
        if (pinned == null) {
            return latest;
        }
        JCRNodeWrapper named = snapshotNamed(folder, pinned);
        if (named == null) {
            logger.warn("Revision entry {} names snapshot '{}', which is not in {}."
                    + " Leaving it unbound rather than attaching it to different content.",
                    entry.getPath(), pinned, folder.getPath());
        }
        return named;
    }

    /**
     * Moves entries whose editor has changed which snapshot they name.
     *
     * <p>Binding is otherwise append-only, because a later CAPTURE must never rewrite what an
     * existing revision claims the page said. An editor re-pointing an entry is the opposite of
     * that: it is a deliberate correction, and without it a wrong choice made once could never be
     * fixed -- which, for history assembled by hand after a backfill, is where wrong choices are
     * most likely to happen.
     *
     * @return how many entries were moved
     */
    private int repin(JCRSessionWrapper session, JCRNodeWrapper folder, JCRNodeWrapper page,
                      Map<String, JCRNodeWrapper> boundTo) throws RepositoryException {
        int moved = 0;
        for (Map.Entry<String, JCRNodeWrapper> bound : boundTo.entrySet()) {
            JCRNodeWrapper entry = nodeOrNull(session, bound.getKey());
            if (entry == null || !isUnder(entry, page)) {
                continue;
            }
            String pinned = pinnedName(entry);
            JCRNodeWrapper current = bound.getValue();
            if (pinned == null || pinned.equals(current.getName())) {
                continue;
            }
            JCRNodeWrapper wanted = snapshotNamed(folder, pinned);
            if (wanted == null) {
                logger.warn("Revision entry {} now names snapshot '{}', which is not in {};"
                        + " leaving it attached to {}", entry.getPath(), pinned, folder.getPath(),
                        current.getName());
                continue;
            }
            removeReference(session, current, entry);
            appendReferences(session, wanted, Collections.singletonList(entry));
            logger.info("Moved revision entry {} from snapshot {} to {}",
                    entry.getPath(), current.getName(), wanted.getName());
            moved++;
        }
        return moved;
    }

    private static boolean isUnder(JCRNodeWrapper node, JCRNodeWrapper ancestor) {
        try {
            return node.getPath().startsWith(ancestor.getPath() + '/');
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String pinnedName(JCRNodeWrapper entry) throws RepositoryException {
        if (!entry.hasProperty(PROP_SNAPSHOT_REF)) {
            return null;
        }
        String name = entry.getProperty(PROP_SNAPSHOT_REF).getString();
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    /**
     * Resolves a name against THIS page-and-language folder only, never as a path.
     *
     * <p>The value is editor-supplied, so it is looked up as a child of the folder rather than
     * interpolated anywhere: a name carrying '/' or '..' cannot reach another page's history, or
     * anything else in the repository.
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

    private void removeReference(JCRSessionWrapper session, JCRNodeWrapper snapshot,
                                 JCRNodeWrapper entry) throws RepositoryException {
        if (!snapshot.hasProperty(PROP_ENTRY_REFS)) {
            return;
        }
        List<Value> kept = new ArrayList<>();
        for (Value value : snapshot.getProperty(PROP_ENTRY_REFS).getValues()) {
            if (!entry.getIdentifier().equals(value.getString())) {
                kept.add(value);
            }
        }
        if (kept.isEmpty()) {
            snapshot.getProperty(PROP_ENTRY_REFS).remove();
        } else {
            snapshot.setProperty(PROP_ENTRY_REFS, kept.toArray(new Value[0]));
        }
    }

    // ------------------------------------------------------------------ lookups

    /*
     * ACCEPTED LIMITATION: entries authored during a capture outage may compare as unchanged.
     *
     * Every not-yet-bound entry on the page binds to the ONE current snapshot. In normal running
     * that is exactly right: a publication produces one entry and one snapshot, and they belong
     * together.
     *
     * It stops being right when captures stop for a while -- a NOT_PUBLIC spell, repeated
     * FAILED, sustained rate limiting, or the component being added to a page that already has
     * entries. Several entries accumulate unbound, and the first capture that succeeds binds all
     * of them to that single snapshot. Comparing two of them then resolves both to the same
     * content and the panel reports that nothing in the text changed, for revisions that did
     * change.
     *
     * This is a deliberate decision to leave as it is, not an oversight. The alternatives were
     * binding only the newest entry (leaving the rest permanently unbound, so they would report
     * "no snapshot recorded" forever) or matching each entry to the snapshot nearest its
     * revisionDate (more correct, and a rule that has to be invented and maintained). Both were
     * judged worse than a documented caveat for a situation that only arises after captures have
     * already been failing -- which is itself visible in crh:lastCaptureStatus.
     *
     * If you are changing this, the README says the same thing under "Binding".
     */
    private JCRNodeWrapper languageFolder(JCRSessionWrapper session, String siteKey,
                                          String pageUuid, String language) {
        try {
            return session.getNode("/sites/" + siteKey + '/' + "contents/" + ROOT_FOLDER_NAME
                    + '/' + pageUuid + '/' + language);
        } catch (RepositoryException notThereYet) {
            return null;
        }
    }

    /**
     * Resolves the folder's denormalised {@code crh:latestSnapshot} pointer to a node.
     *
     * <p>Falls back to null rather than scanning when the pointer names a node that is gone:
     * that combination means the store is mid-repair, and guessing a different snapshot would
     * bind entries to the wrong content.
     */
    private JCRNodeWrapper latestSnapshot(JCRNodeWrapper folder) throws RepositoryException {
        if (!folder.hasProperty(PROP_LATEST_SNAPSHOT)) {
            return null;
        }
        String name = folder.getProperty(PROP_LATEST_SNAPSHOT).getString();
        try {
            JCRNodeWrapper snapshot = folder.getNode(name);
            return snapshot.isNodeType(SNAPSHOT_TYPE) ? snapshot : null;
        } catch (PathNotFoundException gone) {
            logger.warn("Folder {} points at missing snapshot {}", folder.getPath(), name);
            return null;
        }
    }

    /**
     * Every entry identifier already referenced by any snapshot in this folder, and by which.
     *
     * <p>Bounded by {@code MAX_SNAPSHOTS_PER_PAGE_LANGUAGE}. A weak reference to a deleted
     * entry is kept in the set as a plain string, so a deleted-and-recreated entry (which gets
     * a new identifier) binds again rather than being mistaken for the old one.
     */
    private Map<String, JCRNodeWrapper> snapshotByBoundEntry(JCRNodeWrapper folder)
            throws RepositoryException {
        Map<String, JCRNodeWrapper> bound = new LinkedHashMap<>();
        for (JCRNodeWrapper snapshot : folder.getNodes()) {
            if (!snapshot.isNodeType(SNAPSHOT_TYPE) || !snapshot.hasProperty(PROP_ENTRY_REFS)) {
                continue;
            }
            for (Value value : snapshot.getProperty(PROP_ENTRY_REFS).getValues()) {
                bound.put(value.getString(), snapshot);
            }
        }
        return bound;
    }

    /**
     * Depth-first walk of the page for revision entries not yet bound.
     *
     * <p>A walk rather than a JCR query on purpose: the page path would have to be interpolated
     * into a query string, and a bounded traversal of editorial content has no such exposure
     * and no dependency on the search index being current -- which, right after a publication,
     * it may not be.
     *
     * @param budget single-element node budget, shared across the recursion
     */
    private void collectEntries(JCRNodeWrapper node, Set<String> alreadyBound,
                                List<JCRNodeWrapper> out, int[] budget) throws RepositoryException {
        for (JCRNodeWrapper child : node.getNodes()) {
            // Counted per CHILD EXAMINED, not per recursive call. Counting calls bounds only the
            // depth of the walk: a single container holding a hundred thousand children would
            // still be iterated in full, on a background thread, for one budget tick.
            if (budget[0]++ > MAX_NODES_WALKED_PER_PAGE) {
                logger.warn("Stopped looking for revision entries under {} after {} nodes",
                        node.getPath(), MAX_NODES_WALKED_PER_PAGE);
                return;
            }
            if (child.getName().startsWith("j:")) {
                continue;
            }
            // Nested pages own their own history and their own snapshots; descending into them
            // would bind a child page's entries to the parent page's content.
            if (child.isNodeType(PAGE_TYPE)) {
                continue;
            }
            if (child.isNodeType(ENTRY_TYPE)) {
                if (!alreadyBound.contains(child.getIdentifier())) {
                    out.add(child);
                }
                continue;
            }
            collectEntries(child, alreadyBound, out, budget);
        }
    }

    private void appendReferences(JCRSessionWrapper session, JCRNodeWrapper snapshot,
                                  List<JCRNodeWrapper> entries) throws RepositoryException {
        List<Value> values = new ArrayList<>();
        if (snapshot.hasProperty(PROP_ENTRY_REFS)) {
            Collections.addAll(values, snapshot.getProperty(PROP_ENTRY_REFS).getValues());
        }
        for (JCRNodeWrapper entry : entries) {
            // weak = true: an editor must stay free to delete a revision entry, and the record
            // of what the page said must not be what stops them.
            values.add(session.getValueFactory().createValue(entry, true));
        }
        snapshot.setProperty(PROP_ENTRY_REFS, values.toArray(new Value[0]));
    }

    private JCRNodeWrapper nodeOrNull(JCRSessionWrapper session, String identifier) {
        try {
            return session.getNodeByIdentifier(identifier);
        } catch (RepositoryException gone) { // includes ItemNotFoundException
            return null;
        }
    }
}
