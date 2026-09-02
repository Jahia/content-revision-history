package org.jahia.modules.revisionhistory;

import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.PAGE_TYPE;
import static org.jahia.modules.revisionhistory.RevisionHistoryConstants.REVISIONED_MIXIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which node owns a revision history.
 *
 * <p>This existed in three places and two of them disagreed, which was invisible while only pages
 * could be revisioned. The ordering below is the whole point: asking "am I a page?" before "am I
 * revisioned?" walks straight past a revisioned CONTENT node, and the result is not an error --
 * it is null, which is also the correct answer for content nobody asked to revision. So content
 * published outside a page was simply never captured, with nothing logged and nothing to see.
 */
class RevisionedAncestorTest {

    /** Builds a parent chain, deepest first, wired so getParent() walks it. */
    private static JCRNodeWrapper chain(JCRNodeWrapper... deepestFirst) throws RepositoryException {
        for (int i = 0; i < deepestFirst.length - 1; i++) {
            when(deepestFirst[i].getParent()).thenReturn(deepestFirst[i + 1]);
        }
        return deepestFirst[0];
    }

    private static JCRNodeWrapper node(String path, boolean page, boolean revisioned)
            throws RepositoryException {
        JCRNodeWrapper n = mock(JCRNodeWrapper.class);
        when(n.getPath()).thenReturn(path);
        when(n.getIdentifier()).thenReturn("id" + path.hashCode());
        when(n.isNodeType(PAGE_TYPE)).thenReturn(page);
        when(n.isNodeType(REVISIONED_MIXIN)).thenReturn(revisioned);
        return n;
    }

    @Test
    @DisplayName("a revisioned content node is its own owner, page or no page")
    void revisionedContentOwnsItself() throws Exception {
        // The case the previous walk could not express: content published and visible without a
        // page of its own. Verified against a running instance too -- a jnt:bigText under
        // /sites/<site>/contents captured with status STORED.
        JCRNodeWrapper block = node("/sites/digitall/contents/policy-block", false, true);
        JCRNodeWrapper contents = node("/sites/digitall/contents", false, false);
        chain(block, contents);

        assertSame(block, RevisionedAncestor.of(block));
    }

    @Test
    @DisplayName("content inside a revisioned page is owned by the page")
    void contentInsideRevisionedPage() throws Exception {
        JCRNodeWrapper text = node("/sites/d/home/p/area/text", false, false);
        JCRNodeWrapper area = node("/sites/d/home/p/area", false, false);
        JCRNodeWrapper page = node("/sites/d/home/p", true, true);
        chain(text, area, page);

        assertSame(page, RevisionedAncestor.of(text));
    }

    @Test
    @DisplayName("a page that is not revisioned ends the search")
    void nonRevisionedPageStopsTheWalk() throws Exception {
        // Behaviour that already shipped, preserved on purpose: a page owns its own content, so
        // its children do not belong to a history above it. Widening the mixin must not quietly
        // start attributing a sub-page's content to an ancestor's record.
        JCRNodeWrapper text = node("/sites/d/home/p/area/text", false, false);
        JCRNodeWrapper area = node("/sites/d/home/p/area", false, false);
        JCRNodeWrapper page = node("/sites/d/home/p", true, false);
        JCRNodeWrapper revisionedAncestor = node("/sites/d/home", true, true);
        chain(text, area, page, revisionedAncestor);

        assertNull(RevisionedAncestor.of(text),
                "the walk must stop at the page, not reach the revisioned page above it");
    }

    @Test
    @DisplayName("the NEAREST owner wins, so a revisioned block inside a revisioned page owns itself")
    void nearestOwnerWins() throws Exception {
        // Walking to the outermost would attach a component's revisions to the whole page's text,
        // and a comparison on the component would then report changes made anywhere on the page.
        JCRNodeWrapper block = node("/sites/d/home/p/area/block", false, true);
        JCRNodeWrapper area = node("/sites/d/home/p/area", false, false);
        JCRNodeWrapper page = node("/sites/d/home/p", true, true);
        chain(block, area, page);

        assertSame(block, RevisionedAncestor.of(block));
    }

    @Test
    @DisplayName("the mixin is checked before the page type")
    void mixinBeatsPageType() throws Exception {
        // A revisioned page is both. The order only shows up as a defect for a revisioned node
        // that is NOT a page, which is exactly what the previous walk got wrong, so this pins the
        // order rather than the outcome.
        JCRNodeWrapper both = node("/sites/d/home/p", true, true);
        chain(both, node("/sites/d/home", true, false));

        assertSame(both, RevisionedAncestor.of(both));
    }

    @Test
    @DisplayName("nothing outside /sites owns a history")
    void outsideSitesOwnsNothing() throws Exception {
        JCRNodeWrapper somewhereElse = node("/modules/whatever", false, true);

        assertNull(RevisionedAncestor.of(somewhereElse),
                "the walk is bounded to /sites: a revisioned node elsewhere is not site content");
    }

    @Test
    @DisplayName("only the nodes walked PAST are collected for memoisation")
    void visitedCollectsOnlyWhatWasWalkedPast() throws Exception {
        // The listener memoises path -> owner. Including the owner's own path would be harmless,
        // but including nothing would make the memo useless, so this pins what is collected.
        JCRNodeWrapper text = node("/sites/d/home/p/area/text", false, false);
        JCRNodeWrapper area = node("/sites/d/home/p/area", false, false);
        JCRNodeWrapper page = node("/sites/d/home/p", true, true);
        chain(text, area, page);

        List<String> visited = new ArrayList<>();
        RevisionedAncestor.of(text, visited);

        assertEquals(Arrays.asList("/sites/d/home/p/area/text", "/sites/d/home/p/area"), visited,
                "the owner itself is not a node that was walked past");
    }

    @Test
    @DisplayName("ownsItsOwnHistory answers the descent question, and tolerates null")
    void ownsItsOwnHistory() throws Exception {
        assertTrue(RevisionedAncestor.ownsItsOwnHistory(node("/sites/d/x", false, true)));
        assertFalse(RevisionedAncestor.ownsItsOwnHistory(node("/sites/d/x", false, false)));
        assertFalse(RevisionedAncestor.ownsItsOwnHistory(null),
                "a walk that has run off the end must not throw into a capture job");
    }
}
