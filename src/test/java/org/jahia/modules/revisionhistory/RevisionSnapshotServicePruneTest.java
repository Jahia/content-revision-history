package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Retention must never delete the evidence behind a published revision.
 *
 * <p>crh:entryRefs lives on the SNAPSHOT, not on the entry, so it is both the binding and the only
 * record of it. Removing a referenced snapshot therefore did not merely lose the evidence: it made
 * RevisionEntryBinder see that entry as never bound, and the next capture bound it to the CURRENT
 * snapshot. A years-old revision then silently claimed today's text, and comparing it with a recent
 * revision reported the page as unchanged. The record asserted something false, with no error
 * anywhere, which is worse than exceeding the cap.
 *
 * <p>RevisionEntryBinder documents the invariant ("append-only: an entry that already has a
 * snapshot is never rebound") but cannot enforce it, because the property it relies on was being
 * deleted from underneath it. So it is enforced here, where the deletion happens.
 */
class RevisionSnapshotServicePruneTest {

    /**
     * Whether the entries these tests dangle are still published. Pruning has to ask, because an
     * entry deleted from the editorial tree may still be cited by a revision the public can read,
     * and there is no repository here to ask.
     */
    private boolean publishedEntryExists;

    private final RevisionSnapshotService service = new RevisionSnapshotService() {
        @Override
        boolean existsInLive(String identifier) {
            return publishedEntryExists;
        }
    };

    /** Snapshot names are timestamp-prefixed, so lexicographic order is chronological. */
    private static String name(int index) {
        return String.format("2026081%dT120000000Z-abcdef01", index);
    }

    /** A folder of snapshots; those whose index is in `referenced` carry a crh:entryRefs value. */
    private Map<String, JCRNodeWrapper> folderOf(JCRNodeWrapper folder, int howMany,
                                                 List<Integer> referenced) throws RepositoryException {
        return folderOf(folder, howMany, referenced, Collections.emptyList());
    }

    private Map<String, JCRNodeWrapper> folderOf(JCRNodeWrapper folder, int howMany,
                                                 List<Integer> referenced,
                                                 List<Integer> deletedEntries) throws RepositoryException {
        return folderOf(folder, howMany, referenced, deletedEntries, false);
    }

    /**
     * @param reverseIteration iterate the children newest-first, as a repository may. Every
     *                         earlier fixture iterated in chronological order, which made the sort
     *                         in prune() a no-op under test (#33).
     */
    private Map<String, JCRNodeWrapper> folderOf(JCRNodeWrapper folder, int howMany,
                                                 List<Integer> referenced,
                                                 List<Integer> deletedEntries,
                                                 boolean reverseIteration) throws RepositoryException {
        Map<String, JCRNodeWrapper> byName = new LinkedHashMap<>();
        List<JCRNodeWrapper> children = new ArrayList<>();
        for (int i = 0; i < howMany; i++) {
            JCRNodeWrapper snapshot = mock(JCRNodeWrapper.class);
            when(snapshot.getName()).thenReturn(name(i));
            when(snapshot.isNodeType(RevisionHistoryConstants.SNAPSHOT_TYPE)).thenReturn(true);
            boolean isReferenced = referenced.contains(i);
            when(snapshot.hasProperty(RevisionHistoryConstants.PROP_ENTRY_REFS)).thenReturn(isReferenced);
            if (isReferenced) {
                JCRPropertyWrapper refs = mock(JCRPropertyWrapper.class);
                JCRValueWrapper ref = mock(JCRValueWrapper.class);
                when(ref.getString()).thenReturn("entry-uuid-" + i);
                when(refs.getValues()).thenReturn(new JCRValueWrapper[]{ref});
                when(snapshot.getProperty(RevisionHistoryConstants.PROP_ENTRY_REFS)).thenReturn(refs);
                // The reference is resolved now, so a session has to answer for it. Entries whose
                // index is in `deletedEntries` are gone, which must NOT protect the snapshot.
                JCRSessionWrapper session = mock(JCRSessionWrapper.class);
                if (deletedEntries.contains(i)) {
                    when(session.getNodeByIdentifier("entry-uuid-" + i))
                            .thenThrow(new ItemNotFoundException("entry deleted"));
                } else {
                    when(session.getNodeByIdentifier("entry-uuid-" + i))
                            .thenReturn(mock(JCRNodeWrapper.class));
                }
                when(snapshot.getSession()).thenReturn(session);
            }
            byName.put(name(i), snapshot);
            children.add(snapshot);
        }
        if (reverseIteration) {
            Collections.reverse(children);
        }
        JCRNodeIteratorWrapper it = mock(JCRNodeIteratorWrapper.class);
        Iterator<JCRNodeWrapper> cursor = children.iterator();
        when(it.hasNext()).thenAnswer(i -> cursor.hasNext());
        when(it.nextNode()).thenAnswer(i -> cursor.next());
        when(it.iterator()).thenReturn(new ArrayList<>(children).iterator());
        when(folder.getNodes()).thenReturn(it);
        when(folder.getPath()).thenReturn("/sites/x/contents/revision-history/uuid/en");
        byName.forEach((n, snapshot) -> {
            try {
                when(folder.getNode(n)).thenReturn(snapshot);
            } catch (RepositoryException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        return byName;
    }

    @Test
    @DisplayName("an unreferenced oldest snapshot is pruned, as before")
    void prunesUnreferenced() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 4, Arrays.asList());

        int cap = 2;
        long remaining = service.prune(folder, cap);

        verify(snapshots.get(name(0))).remove();
        verify(snapshots.get(name(1))).remove();
        verify(snapshots.get(name(3)), never()).remove();
        // Asserting the property, not a number guessed from the fixture: pruning targets
        // cap - 1, leaving room for the snapshot the caller is about to write. An earlier version
        // of this test asserted 2 because it had mis-derived the arithmetic, which would have made
        // the test the thing that needed changing every time the fixture size changed.
        assertEquals(cap - 1, remaining,
                "pruning leaves room for the incoming snapshot, so cap - 1 survive");
    }

    @Test
    @DisplayName("a snapshot a revision entry references is NOT pruned")
    void refusesToPruneReferenced() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        // The oldest is the evidence behind a published revision.
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 4, Arrays.asList(0));

        service.prune(folder, 2);

        verify(snapshots.get(name(0)), never()).remove();
    }

    @Test
    @DisplayName("a younger unreferenced snapshot is taken in its place, so the cap still bites")
    void skipsToTheNextUnreferenced() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 4, Arrays.asList(0));

        service.prune(folder, 2);

        // Two needed to be removed; index 0 is protected, so 1 and 2 go instead.
        verify(snapshots.get(name(1))).remove();
        verify(snapshots.get(name(2))).remove();
    }

    @Test
    @DisplayName("the newest snapshot is never pruned: it is the next dedupe baseline")
    void neverPrunesTheNewest() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 3, Arrays.asList());

        // A cap of 1 would otherwise want to remove everything.
        service.prune(folder, 1);

        verify(snapshots.get(name(2)), never()).remove();
    }

    @Test
    @DisplayName("when every candidate is referenced, nothing is pruned and the count says so")
    void keepsEverythingWhenAllReferenced() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 4, Arrays.asList(0, 1, 2));

        long remaining = service.prune(folder, 2);

        for (int i = 0; i < 4; i++) {
            verify(snapshots.get(name(i)), never()).remove();
        }
        // Retention is advisory here by design: the folder exceeds the cap rather than the record
        // losing the content a published revision describes. The returned count must be honest
        // about that, or the folder's own bookkeeping would claim a size it does not have.
        assertEquals(4, remaining);
    }

    @Test
    @DisplayName("a reference whose entry was deleted protects nothing")
    void danglingReferenceDoesNotProtect() throws Exception {
        // crh:entryRefs holds WEAK references and the binder deliberately leaves dangling ones, so
        // treating any non-empty value as protection made retention unenforceable: repeatedly
        // adding an entry, publishing and deleting the entry would pin every snapshot forever and
        // the cap would never bite again.
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots =
                folderOf(folder, 4, Arrays.asList(0), Arrays.asList(0));

        publishedEntryExists = false;

        service.prune(folder, 2);

        verify(snapshots.get(name(0))).remove();
    }

    @Test
    @DisplayName("an entry deleted in default but still PUBLISHED protects its snapshot")
    void anEntryStillLiveProtectsItsSnapshot() throws Exception {
        // Capture runs against `default`, so an entry an editor deleted in jContent and has not
        // published vanishes from the workspace pruning looks in -- while the published revision is
        // still on the live page, citing this snapshot as the text it describes. Deleting it
        // destroys the evidence behind a claim the public can still read, and takes crh:entryRefs
        // with it, so the binder later rebinds that revision to the CURRENT text: a years-old
        // revision silently begins claiming today's page.
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots =
                folderOf(folder, 4, Arrays.asList(0), Arrays.asList(0));
        publishedEntryExists = true;

        service.prune(folder, 2);

        verify(snapshots.get(name(0)), never()).remove();
    }

    @Test
    @DisplayName("the pruned counter only counts what was actually removed")
    void prunedCounterExcludesProtected() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        folderOf(folder, 4, Arrays.asList(0));
        JCRPropertyWrapper existing = mock(JCRPropertyWrapper.class);
        when(existing.getLong()).thenReturn(5L);
        when(folder.hasProperty(RevisionHistoryConstants.PROP_PRUNED_COUNT)).thenReturn(true);
        when(folder.getProperty(RevisionHistoryConstants.PROP_PRUNED_COUNT)).thenReturn(existing);

        service.prune(folder, 2);

        // 5 already pruned, 2 removed now: the protected one must not inflate it.
        verify(folder).setProperty(RevisionHistoryConstants.PROP_PRUNED_COUNT, 7L);
    }

    // ------------------------------------------------------------------ #33: the cap must bind

    @Test
    @DisplayName("#33: with many more snapshots than the cap, exactly the excess is removed")
    void capIsTheBindingConstraint() throws Exception {
        // Every earlier fixture had excess >= n - 1, so the loop always stopped on "never the
        // newest" and the cap itself was never what ended it. Miscompute excess, or drop
        // `pruned < excess` from the guard, and those tests stayed green while prune deleted
        // everything but the newest snapshot.
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 10, Arrays.asList());

        int cap = 5;
        long remaining = service.prune(folder, cap);

        // 10 - (5 - 1) = 6 go: the six oldest. The four newest stay.
        for (int i = 0; i < 6; i++) {
            verify(snapshots.get(name(i)), description("index " + i + " is among the oldest six")).remove();
        }
        for (int i = 6; i < 10; i++) {
            verify(snapshots.get(name(i)), never()).remove();
        }
        assertEquals(cap - 1, remaining);
    }

    @Test
    @DisplayName("#33: under the cap, nothing is removed")
    void underTheCapNothingIsPruned() throws Exception {
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots = folderOf(folder, 3, Arrays.asList());

        long remaining = service.prune(folder, 5);

        for (int i = 0; i < 3; i++) {
            verify(snapshots.get(name(i)), never()).remove();
        }
        assertEquals(3, remaining);
    }

    @Test
    @DisplayName("#33: the oldest are chosen by NAME, not by the order the repository lists them")
    void ordersByNameNotByIteration() throws Exception {
        // Snapshot names are timestamp-prefixed, so the sort is what makes "oldest first" true.
        // Iterating newest-first here would, without the sort, delete the newest snapshots.
        // Cap 3 with 4 snapshots: excess = 4 - (3 - 1) = 2, so exactly the two OLDEST go and the
        // two newest stay -- a fixture where the cap, not "never the newest", ends the loop.
        JCRNodeWrapper folder = mock(JCRNodeWrapper.class);
        Map<String, JCRNodeWrapper> snapshots =
                folderOf(folder, 4, Arrays.asList(), Arrays.asList(), true);

        service.prune(folder, 3);

        verify(snapshots.get(name(0))).remove();
        verify(snapshots.get(name(1))).remove();
        verify(snapshots.get(name(2)), never()).remove();
        verify(snapshots.get(name(3)), never()).remove();
    }
}
