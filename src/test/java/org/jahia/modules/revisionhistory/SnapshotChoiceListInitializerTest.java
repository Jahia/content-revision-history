package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of the snapshot picker: what an editor reads in the dropdown.
 *
 * <p>The JCR half is not unit-testable without a repository, so everything that decides what a
 * label says is static and lives here. That is the part that can be wrong in a way an editor would
 * act on, and picking the wrong snapshot for a revision entry publishes the wrong history.
 */
class SnapshotChoiceListInitializerTest {

    private static final char BELL = (char) 7;
    private static final char NUL = (char) 0;

    private static Calendar utc(int year, int month, int day, int hour, int minute, int second) {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(year, month - 1, day, hour, minute, second);
        return c;
    }

    @Test
    @DisplayName("the label leads with the capture instant, to the second")
    void labelLeadsWithTheDate() {
        String label = SnapshotChoiceListInitializer.label(
                utc(2026, 3, 14, 8, 55, 12), "## Affected versions", Locale.ENGLISH);

        assertTrue(label.startsWith("2026-03-14 08:55:12"), label);
        assertTrue(label.contains("## Affected versions"), label);
    }

    @Test
    @DisplayName("a snapshot with no readable text is still selectable")
    void emptyExcerptStillProducesALabel() {
        // An excerpt is a convenience. When it cannot be read the editor must still be able to
        // pick the snapshot by date, rather than lose the option or be shown a dangling separator.
        String label = SnapshotChoiceListInitializer.label(
                utc(2026, 3, 14, 8, 55, 12), "", Locale.ENGLISH);

        assertEquals("2026-03-14 08:55:12", label);
        assertFalse(label.contains("null"), label);
    }

    @Test
    @DisplayName("the excerpt is the first line that carries text")
    void excerptSkipsLeadingBlankLines() {
        assertEquals("# Advisory",
                SnapshotChoiceListInitializer.firstMeaningfulLine("\n\n# Advisory\n\n## Affected\n", 80));
    }

    @Test
    @DisplayName("a long first line is truncated rather than left to stretch the dropdown")
    void longExcerptIsTruncated() {
        String excerpt = SnapshotChoiceListInitializer.firstMeaningfulLine("x".repeat(200), 20);

        assertEquals(20, excerpt.length(), excerpt);
        assertTrue(excerpt.endsWith("…"), excerpt);
    }

    @Test
    @DisplayName("a short first line is left exactly as it is")
    void shortExcerptIsNotPadded() {
        assertEquals("# Advisory", SnapshotChoiceListInitializer.firstMeaningfulLine("# Advisory", 80));
    }

    @Test
    @DisplayName("text that carries nothing yields no excerpt")
    void blankTextYieldsNoExcerpt() {
        assertEquals("", SnapshotChoiceListInitializer.firstMeaningfulLine("   \n\t\n", 80));
        assertEquals("", SnapshotChoiceListInitializer.firstMeaningfulLine(null, 80));
    }

    @Test
    @DisplayName("a control character in the stored text cannot corrupt the dropdown")
    void controlCharactersAreStripped() {
        // The excerpt comes from a snapshot of a rendered page, so it is not trusted input. A bell
        // or a NUL smuggled into an option label would corrupt the list the editor chooses from.
        String excerpt = SnapshotChoiceListInitializer.firstMeaningfulLine(
                "# Ad" + BELL + "vis" + NUL + "ory", 80);

        assertFalse(excerpt.indexOf(BELL) >= 0, excerpt);
        assertFalse(excerpt.indexOf(NUL) >= 0, excerpt);
        assertTrue(excerpt.startsWith("# Ad"), excerpt);
    }

    // --- the excerpt has to DISTINGUISH snapshots, not just describe them ------------------
    //
    // Every snapshot of a page starts with the same line, because jnt_page/markdown emits
    // "# <page title>" first. Showing that as the excerpt made every option in the dropdown read
    // identically ("2026-08-28 12:51 — # Demo Roles and Users"), which is worse than showing
    // nothing: it looks like information while telling the editor nothing about which to pick.

    @Test
    @DisplayName("the excerpt skips the page heading every snapshot shares")
    void excerptSkipsTheSharedPageHeading() {
        String excerpt = SnapshotChoiceListInitializer.distinguishingExcerpt(
                "# Demo Roles and Users\n\n## Affected versions\n\nBody text.\n", 60);

        assertEquals("## Affected versions", excerpt);
    }

    @Test
    @DisplayName("a snapshot that is only the page heading still shows it")
    void headingOnlySnapshotStillShowsTheHeading() {
        // Falling through to empty would leave the option as a bare date, which is less useful
        // than the heading, and this is exactly what an almost-empty page looks like.
        assertEquals("# Demo Roles and Users",
                SnapshotChoiceListInitializer.distinguishingExcerpt("# Demo Roles and Users\n", 60));
    }

    @Test
    @DisplayName("a snapshot that does not start with a heading is shown from its first line")
    void nonHeadingSnapshotUsesItsFirstLine() {
        assertEquals("Body text.",
                SnapshotChoiceListInitializer.distinguishingExcerpt("Body text.\n\nMore.\n", 60));
    }

    @Test
    @DisplayName("a deeper heading is not mistaken for the shared page heading")
    void onlyTheTopLevelHeadingIsSkipped() {
        // "## Affected" is content. Only the single leading "# " line is the page title.
        assertEquals("## Affected",
                SnapshotChoiceListInitializer.distinguishingExcerpt("## Affected\n\nBody.\n", 60));
    }
}
