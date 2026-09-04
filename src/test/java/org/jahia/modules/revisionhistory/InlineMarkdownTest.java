package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading back the Markdown this module writes, so a diff row can be shown formatted.
 *
 * <p>The interesting property is not "does it find the bold" -- it is that rendering must not
 * disturb the word-level highlighting. Rendering deletes characters (delimiters, escapes, the line
 * prefix) while the diff's segments are expressed in positions in the RAW line, so the two are
 * only consistent if every visible character is tracked back to where it came from. Most of the
 * cases below are about that crossing rather than about the syntax.
 */
class InlineMarkdownTest {

    private static List<MarkdownDiff.Segment> none() {
        return Collections.emptyList();
    }

    /** Builds the segment list the diff would produce, as (text, changed) pairs. */
    private static List<MarkdownDiff.Segment> segments(Object... pairs) {
        List<MarkdownDiff.Segment> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new MarkdownDiff.Segment((String) pairs[i], (Boolean) pairs[i + 1]));
        }
        return out;
    }

    private static String visible(InlineMarkdown.Parsed parsed) {
        StringBuilder out = new StringBuilder();
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            out.append(piece.getText());
        }
        return out.toString();
    }

    private static String changedText(InlineMarkdown.Parsed parsed) {
        StringBuilder out = new StringBuilder();
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            if (piece.isChanged()) {
                out.append(piece.getText());
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ line-level shape

    @Test
    @DisplayName("a heading loses its hashes and reports its level")
    void headingPrefix() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("## Maintenance policy", none());

        assertEquals(2, parsed.getHeadingLevel());
        assertEquals("Maintenance policy", visible(parsed));
        assertNull(parsed.getListMarker());
    }

    @Test
    @DisplayName("a '#' with no space after it is content, not a heading")
    void hashWithoutSpaceIsContent() {
        // Reported as a real shape: an advisory that mentions "#1 priority" or a CSS colour.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("#1 priority is uptime", none());

        assertEquals(0, parsed.getHeadingLevel());
        assertEquals("#1 priority is uptime", visible(parsed),
                "the hash is part of the text and must survive");
    }

    @Test
    @DisplayName("a bullet and an ordered item report their marker and depth")
    void listPrefixes() {
        InlineMarkdown.Parsed bullet = InlineMarkdown.parse("- first item", none());
        assertEquals("-", bullet.getListMarker());
        assertEquals(0, bullet.getListDepth());
        assertEquals("first item", visible(bullet));

        InlineMarkdown.Parsed nested = InlineMarkdown.parse("    - nested twice", none());
        assertEquals("-", nested.getListMarker());
        assertEquals(2, nested.getListDepth(), "two spaces per level");
        assertEquals("nested twice", visible(nested));

        InlineMarkdown.Parsed ordered = InlineMarkdown.parse("3. third step", none());
        assertEquals("3.", ordered.getListMarker(),
                "the number is information: an ordered list that renumbers has changed");
        assertEquals("third step", visible(ordered));
    }

    @Test
    @DisplayName("a hyphen that is not a list marker stays in the text")
    void hyphenIsNotAlwaysAList() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("-30% capacity", none());

        assertNull(parsed.getListMarker());
        assertEquals("-30% capacity", visible(parsed));
    }

    // ------------------------------------------------------------------ inline marks

    @Test
    @DisplayName("bold is rendered and its delimiters removed")
    void boldIsRendered() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Support is **not** included", none());

        assertEquals("Support is not included", visible(parsed));
        List<String> bolded = new ArrayList<>();
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            if (piece.isBold()) {
                bolded.add(piece.getText());
            }
        }
        assertEquals(Arrays.asList("not"), bolded);
    }

    @Test
    @DisplayName("an unterminated '**' is content, and does not bold the rest of the line")
    void unterminatedBoldIsContent() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("2 ** 8 is 256", none());

        assertEquals("2 ** 8 is 256", visible(parsed));
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            assertFalse(piece.isBold(), "nothing may be bold: there is no closing delimiter");
        }
    }

    @Test
    @DisplayName("escapes the normalizer added are unescaped for display")
    void escapesAreUnescaped() {
        // MarkdownNormalizer.escapeInline writes '\', '[' and ']' escaped. Showing the escape is
        // showing the module's own bookkeeping to a visitor.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("see \\[note 4\\] and C:\\\\temp", none());

        assertEquals("see [note 4] and C:\\temp", visible(parsed));
    }

    @Test
    @DisplayName("a link keeps its literal syntax, so an href change cannot be hidden")
    void linksStayLiteral() {
        // Collapsing this to "the policy" would mean an href change -- same words, different
        // destination -- produced no visible difference at all, in a record that exists to show
        // what changed.
        String raw = "see [the policy](https://example.com/v2)";

        InlineMarkdown.Parsed parsed = InlineMarkdown.parse(raw, none());

        assertEquals(raw, visible(parsed));
    }

    // ------------------------------------------------------------------ the crossing

    @Test
    @DisplayName("a changed word is still marked changed after the prefix is removed")
    void changedWordSurvivesPrefixRemoval() {
        // The whole risk of rendering: segments are positions in the RAW line, and '## ' is gone
        // from the visible text. Off-by-three here would highlight the wrong word.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("## Support policy",
                segments("## Support ", false, "policy", true));

        assertEquals("Support policy", visible(parsed));
        assertEquals("policy", changedText(parsed), "the highlight must land on the changed word");
    }

    @Test
    @DisplayName("a changed word inside bold keeps both its mark and its highlight")
    void changedWordInsideBold() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("is **not** included",
                segments("is **", false, "not", true, "** included", false));

        assertEquals("is not included", visible(parsed));
        assertEquals("not", changedText(parsed));
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            if (piece.isChanged()) {
                assertTrue(piece.isBold(), "the changed run is inside the bold span");
            }
        }
    }

    @Test
    @DisplayName("a changed word after an escape is still located correctly")
    void changedWordAfterEscape() {
        // An escape consumes two raw characters and emits one, so everything after it is offset.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("\\[note\\] withdrawn",
                segments("\\[note\\] ", false, "withdrawn", true));

        assertEquals("[note] withdrawn", visible(parsed));
        assertEquals("withdrawn", changedText(parsed));
    }

    @Test
    @DisplayName("with no word-level breakdown, nothing is marked changed")
    void noSegmentsMarksNothing() {
        // The normal case for an unchanged line, and for a change too broad to highlight
        // usefully. The row's own added/removed styling carries the meaning there.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("## Support policy", none());

        assertEquals("", changedText(parsed));
    }

    @Test
    @DisplayName("null and empty lines parse to nothing rather than throwing into a render")
    void degenerateInput() {
        assertEquals("", visible(InlineMarkdown.parse(null, none())));
        assertEquals("", visible(InlineMarkdown.parse("", none())));
        assertEquals(0, InlineMarkdown.parse(null, none()).getHeadingLevel());
    }

    @Test
    @DisplayName("every visible character is traceable to the raw line")
    void rawSpansCoverTheLine() {
        // The invariant the crossing depends on: spans are ordered and never overlap. If they
        // ever did, a highlight could be attributed to the wrong run without any test noticing.
        InlineMarkdown.Parsed parsed =
                InlineMarkdown.parse("- **bold** and \\[escaped\\] text", none());

        int previousEnd = -1;
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            assertTrue(piece.getRawStart() >= previousEnd,
                    "runs must not overlap: " + piece.getText());
            assertTrue(piece.getRawEnd() > piece.getRawStart(), "a run spans at least one char");
            previousEnd = piece.getRawEnd();
        }
        assertEquals("bold and [escaped] text", visible(parsed));
    }

    // ------------------------------------------------------------------ #27: symmetric escaping

    @Test
    @DisplayName("#27: an escaped asterisk is shown literally and toggles nothing")
    void escapedAsteriskIsLiteral() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("2 \\*\\* 8 is 256 and 3 \\*\\* 2", none());

        assertEquals("2 ** 8 is 256 and 3 ** 2", visible(parsed));
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            assertFalse(piece.isBold());
            assertFalse(piece.isItalic());
        }
    }

    @Test
    @DisplayName("#27: an escaped backslash is one visible backslash")
    void escapedBackslashIsVisible() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Path is C:\\\\Users\\\\bob", none());

        assertEquals("Path is C:\\Users\\bob", visible(parsed));
    }

    @Test
    @DisplayName("#27: italic is rendered and its delimiters removed")
    void italicIsRendered() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("This is *not* **ever** allowed", none());

        assertEquals("This is not ever allowed", visible(parsed));
        List<String> italics = new ArrayList<>();
        List<String> bolds = new ArrayList<>();
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            if (piece.isItalic()) {
                italics.add(piece.getText());
            }
            if (piece.isBold()) {
                bolds.add(piece.getText());
            }
        }
        assertEquals(Arrays.asList("not"), italics);
        assertEquals(Arrays.asList("ever"), bolds);
    }

    @Test
    @DisplayName("#27: a lone asterisk from an older snapshot stays content")
    void loneAsteriskIsContent() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Rated 4* overall", none());

        assertEquals("Rated 4* overall", visible(parsed));
    }

    @Test
    @DisplayName("#27: an escaped delimiter does not close an open span")
    void escapedDelimiterDoesNotClose() {
        // "**a \\*\\* b" -- the only other ** is escaped, so the opener is content.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("**a \\*\\* b", none());

        assertEquals("**a ** b", visible(parsed));
    }
}
