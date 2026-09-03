package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link RevisionDiffService} is the module's only public read path, and everything past its
 * front door runs with a session that bypasses ACLs. These tests cover the part of it that can
 * be reached without a repository: the argument checks, and -- the reason this class exists --
 * that the permission gate <b>fails closed</b>.
 *
 * <p>Nothing here stands up a JCR repository, so a call made from a plain JUnit process has no
 * request context and no current user. That is exactly the condition a security check must not
 * mishandle: it must deny, and it must not throw. A gate that throws is not a gate -- the
 * exception surfaces as a server error, or worse, is swallowed by a caller that then carries on.
 */
class RevisionDiffServiceTest {

    private static final String HISTORY = "11111111-1111-1111-1111-111111111111";
    private static final String ONE = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER = "33333333-3333-3333-3333-333333333333";

    private final RevisionDiffService service = new RevisionDiffService();

    @Test
    @DisplayName("The permission gate itself denies when it cannot identify the current user")
    void theGateFailsClosed() {
        // Arrange -- no Jahia context, so JCRSessionFactory cannot produce a current user
        // session. Act/Assert on the gate DIRECTLY: asserting this through compare() proves
        // nothing, because with no repository compare() denies for several reasons at once and
        // the assertion passes just as well with the gate deleted. Verified by mutation.
        assertFalse(service.viewerMayReadHistory(HISTORY, "live"),
                "a permission check that cannot reach a verdict has not granted anything");
    }

    @Test
    @DisplayName("compare() always returns a view and never throws, whatever the environment")
    void compareNeverThrows() {
        // This does NOT prove the gate works -- see theGateFailsClosed for that. It pins the
        // weaker but still necessary property: a security refusal must not escape as an
        // exception, which would surface as a server error page or be swallowed by a caller
        // that then carries on regardless.
        RevisionDiffView view = service.compare(HISTORY, ONE, OTHER, "en", "live");

        assertNotNull(view, "the service must always return a view, never null");
        assertFalse(view.isAvailable());
        assertEquals(RevisionDiffService.REASON_NOT_FOUND, view.getReason(),
                "denial must be indistinguishable from a history that does not exist, so the "
                        + "public page cannot be used to confirm which identifiers are real");
    }

    @Test
    @DisplayName("The render's own workspace is honoured, both of them")
    void renderingWorkspaceIsHonoured() {
        // Both directions matter, and neither used to happen. The workspace is now supplied by
        // the view from renderContext, because JCRSessionFactory's no-argument accessor answers
        // "default" whatever is being rendered -- so the previous implementation could not return
        // "live" at all except by throwing, and the permission gate built on it refused every
        // anonymous visitor. See RevisionDiffService#viewerMayRead.
        assertEquals("live", RevisionDiffService.renderingWorkspace("live"),
                "a visitor's comparison must describe the entries they can actually see");
        assertEquals("default", RevisionDiffService.renderingWorkspace("default"),
                "hardcoding live would answer 'not found' for an unpublished revision that is "
                        + "plainly on the screen in an editor's preview");
    }

    @Test
    @DisplayName("An unrecognised workspace falls back to the PUBLISHED one")
    void renderingWorkspaceFallsBackToPublished() {
        // The direction of this fallback matters: defaulting to "default" would make a comparison
        // describe unpublished editorial values (a renamed label, a changed date) on a public
        // page. Defaulting to "live" can only ever show less than the viewer might be entitled
        // to, which is the safe direction for a public-facing feature.
        for (String unusable : new String[]{null, "", "  ", "DEFAULT", "live2", "../default"}) {
            assertEquals("live", RevisionDiffService.renderingWorkspace(unusable),
                    "must fall back rather than pass through: " + unusable);
        }
    }

    @Test
    @DisplayName("A malformed identifier is refused before anything is read")
    void malformedIdentifiersAreRefused() {
        // Arrange -- these arrive from a visitor-submitted form and are concatenated into a
        // repository path further down, so the shape check has to come first.
        for (String bad : new String[]{null, "", "../../etc", "not-a-uuid",
                "11111111-1111-1111-1111-11111111111"}) {
            // Act
            RevisionDiffView view = service.compare(HISTORY, bad, OTHER, "en", "live");

            // Assert
            assertFalse(view.isAvailable(), "must refuse: " + bad);
            assertEquals(RevisionDiffService.REASON_NOT_FOUND, view.getReason());
        }
    }

    @Test
    @DisplayName("Comparing a revision with itself is answered before the permission gate")
    void sameRevisionIsAnsweredEarly() {
        // Arrange/Act -- a pure argument fact, so it needs no session and must not depend on one.
        RevisionDiffView view = service.compare(HISTORY, ONE, ONE, "en", "live");

        // Assert
        assertFalse(view.isAvailable());
        assertEquals(RevisionDiffService.REASON_SAME_REVISION, view.getReason(),
                "the visitor picked the same entry twice; that is worth saying plainly rather "
                        + "than reporting it as a missing snapshot");
    }
}
