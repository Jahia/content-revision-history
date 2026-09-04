package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.PublicationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PAGE_TYPE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.REVISIONED_MIXIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which pages a publication captures, and what happens to a capture that never runs.
 *
 * <p>This class decides whether a publication produces a snapshot at all, and it had no test. Two
 * defects lived there unnoticed: the path memo shadowing a revisioned content node inside a
 * container (#19), and pending jobs cancelled on module stop with nothing written (#24). Both would
 * have failed a table-driven test over publication infos, which is what this is.
 */
class PublicationSnapshotListenerTest {

    /** Everything a recorder was asked to write, as "siteKey|page|lang|STATUS". */
    private final List<String> recorded = new ArrayList<>();

    private PublicationSnapshotListener listener() {
        return new PublicationSnapshotListener(
                (siteKey, page, language, status, message) ->
                        recorded.add(siteKey + "|" + page + "|" + language + "|" + status),
                pageUuid -> "site-of-" + pageUuid);
    }

    private static JCRNodeWrapper node(String path, String uuid, boolean page, boolean revisioned)
            throws RepositoryException {
        JCRNodeWrapper n = mock(JCRNodeWrapper.class);
        when(n.getPath()).thenReturn(path);
        when(n.getIdentifier()).thenReturn(uuid);
        when(n.isNodeType(PAGE_TYPE)).thenReturn(page);
        when(n.isNodeType(REVISIONED_MIXIN)).thenReturn(revisioned);
        return n;
    }

    private static void chain(JCRNodeWrapper... deepestFirst) throws RepositoryException {
        for (int i = 0; i < deepestFirst.length - 1; i++) {
            when(deepestFirst[i].getParent()).thenReturn(deepestFirst[i + 1]);
        }
    }

    private static JCRSessionWrapper sessionWith(JCRNodeWrapper... nodes) throws RepositoryException {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        for (JCRNodeWrapper n : nodes) {
            when(session.getNodeByIdentifier(n.getIdentifier())).thenReturn(n);
        }
        return session;
    }

    /** A publication info in the shape Jahia emits: identifier, path, type, languages. */
    private static PublicationEvent.ContentPublicationInfo published(JCRNodeWrapper n, String... languages)
            throws RepositoryException {
        return new PublicationEvent.ContentPublicationInfo(n.getIdentifier(), n.getPath(), "jnt:content",
                Arrays.asList(languages));
    }

    // ------------------------------------------------------------------ #19: which node owns it

    @Test
    @DisplayName("#19: a revisioned block inside a container is captured as itself, not as its page")
    void revisionedBlockInsideContainerOwnsItself() throws Exception {
        // Arrange: the tree order Jahia emits -- the container first, then the block inside it.
        JCRNodeWrapper page = node("/sites/s/home/faq", "FAQ", true, true);
        JCRNodeWrapper container = node("/sites/s/home/faq/pagecontent", "PC", false, false);
        JCRNodeWrapper block = node("/sites/s/home/faq/pagecontent/policy", "POLICY", false, true);
        chain(block, container, page);
        JCRSessionWrapper session = sessionWith(page, container, block);

        // Act
        Map<String, Set<String>> result = listener().resolveRevisionedPages(
                Arrays.asList(published(container, "en"), published(block, "en")), session);

        // Assert: both histories are written. Before, the container's memo entry answered for the
        // block and POLICY never appeared.
        assertEquals(new LinkedHashSet<>(Arrays.asList("FAQ", "POLICY")), result.keySet());
        assertEquals(Collections.singleton("en"), result.get("POLICY"));
    }

    @Test
    @DisplayName("#19: on a page that is NOT revisioned, the block inside it is still captured")
    void revisionedBlockOnPlainPageIsCaptured() throws Exception {
        JCRNodeWrapper page = node("/sites/s/home/faq", "FAQ", true, false);
        JCRNodeWrapper container = node("/sites/s/home/faq/pagecontent", "PC", false, false);
        JCRNodeWrapper block = node("/sites/s/home/faq/pagecontent/policy", "POLICY", false, true);
        chain(block, container, page);
        JCRSessionWrapper session = sessionWith(page, container, block);

        Map<String, Set<String>> result = listener().resolveRevisionedPages(
                Arrays.asList(published(container, "en"), published(block, "en")), session);

        // The memo held "" (none) for the container; that must not be the block's answer.
        assertEquals(Collections.singleton("POLICY"), result.keySet());
    }

    @Test
    @DisplayName("a revisioned sub-page under a revisioned page owns its own content")
    void subPageOwnsItsOwnContent() throws Exception {
        JCRNodeWrapper home = node("/sites/s/home", "HOME", true, true);
        JCRNodeWrapper sub = node("/sites/s/home/sub", "SUB", true, true);
        JCRNodeWrapper text = node("/sites/s/home/sub/area/text", "TEXT", false, false);
        JCRNodeWrapper area = node("/sites/s/home/sub/area", "AREA", false, false);
        chain(text, area, sub, home);
        JCRSessionWrapper session = sessionWith(home, sub, text, area);

        Map<String, Set<String>> result = listener().resolveRevisionedPages(
                Arrays.asList(published(home, "en"), published(sub, "en"), published(text, "en")), session);

        assertEquals(new LinkedHashSet<>(Arrays.asList("HOME", "SUB")), result.keySet());
    }

    @Test
    @DisplayName("a plain page produces nothing")
    void plainPageProducesNothing() throws Exception {
        JCRNodeWrapper page = node("/sites/s/home/plain", "PLAIN", true, false);
        JCRNodeWrapper text = node("/sites/s/home/plain/area/text", "TEXT", false, false);
        JCRNodeWrapper area = node("/sites/s/home/plain/area", "AREA", false, false);
        chain(text, area, page);
        JCRSessionWrapper session = sessionWith(page, text, area);

        Map<String, Set<String>> result = listener().resolveRevisionedPages(
                Arrays.asList(published(page, "en"), published(text, "en")), session);

        assertTrue(result.isEmpty(), result.toString());
    }

    @Test
    @DisplayName("languages of every published node under one page are unioned")
    void languagesAreUnioned() throws Exception {
        JCRNodeWrapper page = node("/sites/s/home/p", "P", true, true);
        JCRNodeWrapper a = node("/sites/s/home/p/area/a", "A", false, false);
        JCRNodeWrapper b = node("/sites/s/home/p/area/b", "B", false, false);
        JCRNodeWrapper area = node("/sites/s/home/p/area", "AREA", false, false);
        chain(a, area, page);
        chain(b, area, page);
        JCRSessionWrapper session = sessionWith(page, a, b, area);

        Map<String, Set<String>> result = listener().resolveRevisionedPages(
                Arrays.asList(published(a, "en"), published(b, "fr", "en")), session);

        assertEquals(new LinkedHashSet<>(Arrays.asList("en", "fr")), result.get("P"));
    }

    @Test
    @DisplayName("the memo still spares the walk for a second plain node under a resolved container")
    void memoSparesTheWalk() throws Exception {
        JCRNodeWrapper page = node("/sites/s/home/p", "P", true, true);
        JCRNodeWrapper area = node("/sites/s/home/p/area", "AREA", false, false);
        JCRNodeWrapper a = node("/sites/s/home/p/area/a", "A", false, false);
        JCRNodeWrapper b = node("/sites/s/home/p/area/b", "B", false, false);
        chain(a, area, page);
        chain(b, area, page);
        JCRSessionWrapper session = sessionWith(page, area, a, b);

        listener().resolveRevisionedPages(Arrays.asList(published(area, "en"), published(b, "en")), session);

        // b is looked at (to see whether it is revisioned itself) but its parents are not walked.
        verify(b, never()).getParent();
    }

    // ------------------------------------------------------------------ #24: a capture that never ran

    @Test
    @DisplayName("#24: a job cancelled on stop leaves a FAILED status on every page and language")
    void cancelledJobIsRecorded() throws Exception {
        PublicationSnapshotListener listener = listener();
        Map<String, Set<String>> pending = new LinkedHashMap<>();
        pending.put("P1", new LinkedHashSet<>(Arrays.asList("en", "fr")));
        pending.put("P2", new LinkedHashSet<>(Collections.singleton("en")));
        listener.remember("job-1", "group", pending);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.deleteJob("job-1", "group")).thenReturn(true);

        int cancelled = listener.cancelOutstandingJobs(scheduler);

        assertEquals(1, cancelled);
        assertEquals(Arrays.asList(
                "site-of-P1|P1|en|FAILED", "site-of-P1|P1|fr|FAILED", "site-of-P2|P2|en|FAILED"), recorded);
        assertEquals(0, listener.pendingJobs());
    }

    @Test
    @DisplayName("#24: a job that already ran is not recorded as failed")
    void completedJobIsNotRecorded() throws Exception {
        PublicationSnapshotListener listener = listener();
        listener.remember("job-1", "group", Collections.singletonMap("P1", Collections.singleton("en")));
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.deleteJob(anyString(), anyString())).thenReturn(false);

        int cancelled = listener.cancelOutstandingJobs(scheduler);

        assertEquals(0, cancelled);
        assertTrue(recorded.isEmpty(), "the job recorded its own outcome; nothing to add: " + recorded);
    }

    @Test
    @DisplayName("#24: a recorder failure does not stop the other pages being recorded")
    void recorderFailureIsContained() throws Exception {
        List<String> seen = new ArrayList<>();
        PublicationSnapshotListener listener = new PublicationSnapshotListener(
                (siteKey, page, language, status, message) -> {
                    seen.add(page);
                    if ("P1".equals(page)) {
                        throw new IllegalStateException("repository down");
                    }
                },
                pageUuid -> "s");
        Map<String, Set<String>> pending = new LinkedHashMap<>();
        pending.put("P1", Collections.singleton("en"));
        pending.put("P2", Collections.singleton("en"));
        listener.remember("job-1", "group", pending);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.deleteJob(eq("job-1"), eq("group"))).thenReturn(true);

        listener.cancelOutstandingJobs(scheduler);

        assertEquals(Arrays.asList("P1", "P2"), seen);
    }
}
