package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PAGE_TYPE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.REVISIONED_MIXIN;

/**
 * Which node owns the revision history a given node belongs to.
 *
 * <p>One implementation, deliberately. This question was answered in three places and two of them
 * answered it differently: the publication listener walked up to the nearest ancestor carrying
 * {@code jmix:publiclyRevisioned}, while the comparison service and the snapshot picker walked up
 * to the nearest {@code jnt:page}. That was indistinguishable while only pages could carry the
 * mixin. It stops being so the moment a content node can, and the way it fails is the worst
 * available: capture would key snapshots on the content node, the comparison would look for them
 * under the enclosing page, and every comparison would report "no snapshot of the page was
 * recorded for this revision" for a page whose snapshots exist and are correct.
 *
 * <p>Two code paths that must agree, and can drift. Now they cannot.
 */
final class RevisionedAncestor {

    private RevisionedAncestor() {
        // static utility
    }

    /**
     * @param node the node to start from, which may itself be the revisioned one
     * @return the nearest node at or above {@code node} carrying {@code jmix:publiclyRevisioned},
     *         or null when there is none
     *
     * <p>Nearest, not outermost: a revisioned content node inside a revisioned page owns its own
     * history, and its own snapshots are the ones a comparison on it must read. Walking to the
     * outermost would attach a component's revisions to the whole page's text.
     */
    static JCRNodeWrapper of(JCRNodeWrapper node) throws RepositoryException {
        return of(node, new ArrayList<String>());
    }

    /**
     * @param visited collects the paths walked past BEFORE the answer was found, for a caller that
     *                memoises per path; untouched when the answer is the starting node itself
     *
     * <p>The mixin is checked before the page type, and that order is the whole fix: the previous
     * walk asked "am I a page?" first and only then "is this page revisioned?", so a revisioned
     * CONTENT node was added to the visited list and walked straight past. Content published
     * outside any page then resolved to nothing and was never captured -- silently, because
     * resolving to nothing is also the correct answer for content nobody asked to revision.
     *
     * <p>A page that is NOT revisioned still ends the search, which is the behaviour that already
     * shipped: a page owns its own content, so its children do not belong to a history above it.
     */
    static JCRNodeWrapper of(JCRNodeWrapper node, List<String> visited) throws RepositoryException {
        JCRNodeWrapper current = node;
        while (current != null && current.getPath().startsWith("/sites/")) {
            if (current.isNodeType(REVISIONED_MIXIN)) {
                return current;
            }
            if (current.isNodeType(PAGE_TYPE)) {
                return null;
            }
            visited.add(current.getPath());
            try {
                current = current.getParent();
            } catch (ItemNotFoundException atTheRoot) {
                // getParent on the root throws rather than returning null, and a node reached
                // through a session that cannot see further up looks the same.
                return null;
            }
        }
        return null;
    }

    /**
     * @return whether this node owns a revision history of its own
     *
     * <p>Used when DESCENDING rather than ascending: a walk gathering one node's revision entries
     * must not descend into a node that owns its own, or a component's entries end up bound to the
     * text of the page around it.
     */
    static boolean ownsItsOwnHistory(JCRNodeWrapper node) throws RepositoryException {
        return node != null && node.isNodeType(REVISIONED_MIXIN);
    }
}
