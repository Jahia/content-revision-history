package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.ENTRY_TYPE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PROP_REVISION_DATE;

/**
 * The one definition of what "newest first" means for a revision history.
 *
 * <p><b>Why this exists.</b> Order used to be whatever order the entries sat in, with the views
 * documenting an assumption that editors would keep the list newest-first by drag-and-drop. That
 * assumption is unkeepable: Content Editor appends a new child at the <em>end</em>, which under a
 * newest-first reading is the <em>oldest</em> position. So the ordinary act of adding a revision
 * put the newest one last, where it rendered as "the earliest recorded revision" and offered no
 * comparison at all -- while its neighbour silently began comparing against the wrong revision.
 * Nothing warned anyone, because from the code's point of view the list was simply in the order
 * it was in.
 *
 * <p>Order is therefore <b>derived from {@code revisionDate}</b>, which is mandatory,
 * editor-visible, and already means exactly this. Document order survives as the tie-breaker, so
 * drag-and-drop still settles revisions sharing a date -- {@code orderable} is not decoration.
 *
 * <p>Both the rendered list and the comparisons come through here. That is the point: when the
 * view ordered entries one way and the comparison service another, "previous" could mean two
 * different revisions on the same page, and a control's label would describe a pair its panel did
 * not show.
 */
public final class RevisionEntryOrder {

    private static final Logger logger = LoggerFactory.getLogger(RevisionEntryOrder.class);

    private RevisionEntryOrder() {
        // static utility
    }

    /** A node paired with the date it sorts on, read once rather than once per comparison. */
    private static final class Dated {
        private final JCRNodeWrapper node;
        private final Calendar date;

        private Dated(JCRNodeWrapper node, Calendar date) {
            this.node = node;
            this.date = date;
        }
    }

    /**
     * Newest first. An entry with no date sorts last: it cannot be placed in the chronology, and
     * putting it first would raise a revision of unknown age above dated ones.
     *
     * <p>{@link List#sort} is stable, so entries sharing a date keep their editorial order.
     */
    private static final Comparator<Dated> NEWEST_FIRST = (a, b) -> {
        if (a.date == null || b.date == null) {
            return a.date == b.date ? 0 : (a.date == null ? 1 : -1);
        }
        return b.date.compareTo(a.date);
    };

    /**
     * The history's revision entries, newest first.
     *
     * @return never null; empty when the node holds no entries
     */
    public static List<JCRNodeWrapper> newestFirst(JCRNodeWrapper history) {
        List<Dated> dated = new ArrayList<>();
        try {
            for (JCRNodeWrapper child : history.getNodes()) {
                // j:* children (j:acl and friends) are not revisions.
                if (child.isNodeType(ENTRY_TYPE)) {
                    dated.add(new Dated(child, dateOrNull(child)));
                }
            }
        } catch (RepositoryException e) {
            // Keep what was collected rather than returning nothing: a list in an imperfect
            // order is a far smaller failure than a public revision history that renders empty.
            logger.error("Could not read the revision entries of a history node", e);
        }
        dated.sort(NEWEST_FIRST);

        List<JCRNodeWrapper> entries = new ArrayList<>(dated.size());
        for (Dated d : dated) {
            entries.add(d.node);
        }
        return entries;
    }

    private static Calendar dateOrNull(JCRNodeWrapper node) throws RepositoryException {
        return node.hasProperty(PROP_REVISION_DATE)
                ? node.getProperty(PROP_REVISION_DATE).getDate() : null;
    }
}
