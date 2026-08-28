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
 * lookup to pick whichever it met last. The capture rate limiter serialises attempts for a given
 * page and language to one per second, which is what keeps that window shut in practice; and the
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
        JCRNodeWrapper latest = latestSnapshot(folder);
        if (latest == null) {
            logger.debug("No snapshot yet for page {} [{}]; entries stay unbound", pageUuid, language);
            return 0;
        }

        JCRNodeWrapper page = nodeOrNull(session, pageUuid);
        if (page == null) {
            return 0;
        }

        Set<String> alreadyBound = boundEntryIdentifiers(folder);
        List<JCRNodeWrapper> unbound = new ArrayList<>();
        collectEntries(page, alreadyBound, unbound, new int[]{0});
        if (unbound.isEmpty()) {
            return 0;
        }

        appendReferences(session, latest, unbound);
        session.save();
        logger.info("Bound {} revision entr{} on page {} [{}] to snapshot {}",
                unbound.size(), unbound.size() == 1 ? "y" : "ies", pageUuid, language,
                latest.getName());
        return unbound.size();
    }

    // ------------------------------------------------------------------ lookups

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
     * Every entry identifier already referenced by any snapshot in this folder.
     *
     * <p>Bounded by {@code MAX_SNAPSHOTS_PER_PAGE_LANGUAGE}. A weak reference to a deleted
     * entry is kept in the set as a plain string, so a deleted-and-recreated entry (which gets
     * a new identifier) binds again rather than being mistaken for the old one.
     */
    private Set<String> boundEntryIdentifiers(JCRNodeWrapper folder) throws RepositoryException {
        Set<String> bound = new HashSet<>();
        for (JCRNodeWrapper snapshot : folder.getNodes()) {
            if (!snapshot.isNodeType(SNAPSHOT_TYPE) || !snapshot.hasProperty(PROP_ENTRY_REFS)) {
                continue;
            }
            for (Value value : snapshot.getProperty(PROP_ENTRY_REFS).getValues()) {
                bound.add(value.getString());
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
