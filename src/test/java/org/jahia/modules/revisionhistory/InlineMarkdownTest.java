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

    // ------------------------------------------------------------------ blockquote

    @Test
    @DisplayName("a quoted line reports its depth and drops the '> ' prefix")
    void blockquotePrefix() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("> Quoted advice", none());

        assertEquals(1, parsed.getQuoteDepth());
        assertEquals("Quoted advice", visible(parsed));
        assertEquals(0, parsed.getHeadingLevel());
    }

    @Test
    @DisplayName("nested quotes count each '> ' as a level")
    void nestedBlockquote() {
        // MarkdownNormalizer re-applies '> ' per level, so a quote inside a quote is "> > ".
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("> > Deeply quoted", none());

        assertEquals(2, parsed.getQuoteDepth());
        assertEquals("Deeply quoted", visible(parsed));
    }

    @Test
    @DisplayName("a heading inside a quote keeps both its depth and its level")
    void headingInsideBlockquote() {
        // renderBlockquote prefixes every produced line, so a quoted heading is "> ## Title".
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("> ## Quoted heading", none());

        assertEquals(1, parsed.getQuoteDepth());
        assertEquals(2, parsed.getHeadingLevel());
        assertEquals("Quoted heading", visible(parsed));
    }

    @Test
    @DisplayName("a '>' not followed by a space is content, not a quote")
    void angleWithoutSpaceIsContent() {
        // The prefix the normalizer writes is exactly "> "; a bare '>' is arithmetic or a chevron.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse(">3 items remain", none());

        assertEquals(0, parsed.getQuoteDepth());
        assertEquals(">3 items remain", visible(parsed));
    }

    @Test
    @DisplayName("a changed word in a quote is still highlighted after the prefix is removed")
    void changedWordSurvivesQuotePrefix() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("> Quoted policy",
                segments("> Quoted ", false, "policy", true));

        assertEquals("Quoted policy", visible(parsed));
        assertEquals("policy", changedText(parsed), "the highlight must land past the '> '");
    }

    // ------------------------------------------------------------------ horizontal rule

    @Test
    @DisplayName("a line that is exactly '---' is a horizontal rule with no content")
    void horizontalRule() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("---", none());

        assertTrue(parsed.isHorizontalRule());
        assertEquals("", visible(parsed), "a rule carries no text");
    }

    @Test
    @DisplayName("a table separator row is not mistaken for a horizontal rule")
    void tableSeparatorIsNotARule() {
        // "--- | --- | ---" contains dashes but is a table separator, handled elsewhere.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("--- | --- | ---", none());

        assertFalse(parsed.isHorizontalRule(), "the pipes make this a separator row, not a rule");
    }

    @Test
    @DisplayName("dashes with trailing text are content, not a rule")
    void dashesWithTextAreContent() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("---ish, roughly", none());

        assertFalse(parsed.isHorizontalRule());
        assertEquals("---ish, roughly", visible(parsed));
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

    // ------------------------------------------------------------------ links (href-safe)

    private static InlineMarkdown.Piece onlyLink(InlineMarkdown.Parsed parsed) {
        InlineMarkdown.Piece found = null;
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            if (piece.isLink()) {
                assertNull(found, "expected exactly one link piece");
                found = piece;
            }
        }
        return found;
    }

    @Test
    @DisplayName("a link renders as its text and exposes a sanitised href")
    void linkRendersAsTextWithHref() {
        // The old contract left links literal so an href change could not hide. The new contract
        // renders the anchor but keeps that guarantee through span-wide change propagation
        // (see hrefChangeStillHighlightsTheLink).
        InlineMarkdown.Parsed parsed =
                InlineMarkdown.parse("see [the policy](https://example.com/v2)", none());

        assertEquals("see the policy", visible(parsed), "the reader sees the words, not the syntax");
        InlineMarkdown.Piece link = onlyLink(parsed);
        assertEquals("the policy", link.getText());
        assertEquals("https://example.com/v2", link.getHref());
    }

    @Test
    @DisplayName("an href-only change still marks the whole link changed")
    void hrefChangeStillHighlightsTheLink() {
        // Same words, different destination. The word diff lands on the URL, which is not part of
        // the visible text; without span-wide propagation the link would show no change at all --
        // exactly the hole the old literal rendering existed to avoid.
        String raw = "see [the policy](https://example.com/v2)";
        //             the changed token is the version at the end of the href
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse(raw,
                segments("see [the policy](https://example.com/v", false, "2", true, ")", false));

        InlineMarkdown.Piece link = onlyLink(parsed);
        assertTrue(link.isChanged(), "a destination change must be visible even when the words are not");
    }

    @Test
    @DisplayName("mailto and site-relative hrefs are allowed")
    void mailtoAndRelativeHrefsAllowed() {
        assertEquals("mailto:ops@example.com",
                onlyLink(InlineMarkdown.parse("[email us](mailto:ops@example.com)", none())).getHref());
        assertEquals("/about",
                onlyLink(InlineMarkdown.parse("[home](/about)", none())).getHref());
    }

    @Test
    @DisplayName("a disallowed scheme is never emitted as an href; the span stays literal")
    void disallowedSchemeStaysLiteral() {
        // The normalizer already drops such hrefs, so this is defence in depth: InlineMarkdown must
        // never put a javascript: URL onto a public page even if one reaches it.
        String raw = "[x](javascript:alert(1))";
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse(raw, none());

        assertNull(onlyLink(parsed), "not treated as a link");
        assertEquals(raw, visible(parsed), "left exactly as stored, syntax and all");
        for (InlineMarkdown.Piece piece : parsed.getPieces()) {
            assertNull(piece.getHref());
        }
    }

    @Test
    @DisplayName("an image is not turned into a link and stays literal")
    void imageStaysLiteral() {
        // Images are out of scope: the '!' guards the '[' so the link parser never fires.
        String raw = "![alt text](/logo.png)";
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse(raw, none());

        assertNull(onlyLink(parsed));
        assertEquals(raw, visible(parsed));
    }

    @Test
    @DisplayName("escaped brackets inside link text are resolved for display")
    void escapedBracketsInLinkText() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("[note \\[4\\]](/n)", none());

        InlineMarkdown.Piece link = onlyLink(parsed);
        assertEquals("note [4]", link.getText());
        assertEquals("/n", link.getHref());
    }

    @Test
    @DisplayName("a bracket with no link structure stays literal")
    void bracketWithoutLinkStructure() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("a [bracketed] word", none());

        assertNull(onlyLink(parsed));
        assertEquals("a [bracketed] word", visible(parsed));
    }

    @Test
    @DisplayName("a link inside bold carries the surrounding emphasis")
    void linkInsideBold() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("**[the policy](/p)**", none());

        InlineMarkdown.Piece link = onlyLink(parsed);
        assertEquals("the policy", link.getText());
        assertTrue(link.isBold(), "the link sits inside a bold span");
    }

    // ------------------------------------------------------------------ tables

    private static String cellVisible(InlineMarkdown.Parsed parsed, int index) {
        StringBuilder out = new StringBuilder();
        for (InlineMarkdown.Piece piece : parsed.getCells().get(index)) {
            out.append(piece.getText());
        }
        return out.toString();
    }

    @Test
    @DisplayName("in a table block, a '--- | ---' row is the separator, not content")
    void tableSeparatorRow() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("--- | --- | ---", none(), true);

        assertTrue(parsed.isTableSeparator());
        assertFalse(parsed.isHorizontalRule(), "a separator is table structure, not a rule");
    }

    @Test
    @DisplayName("in a table block, a row splits into cells at ' | '")
    void tableRowSplitsIntoCells() {
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Name | Status | Owner", none(), true);

        assertFalse(parsed.isTableSeparator());
        assertEquals(3, parsed.getCells().size());
        assertEquals("Name", cellVisible(parsed, 0));
        assertEquals("Status", cellVisible(parsed, 1));
        assertEquals("Owner", cellVisible(parsed, 2));
    }

    @Test
    @DisplayName("cells carry their own inline formatting and links")
    void tableCellsAreInlineParsed() {
        InlineMarkdown.Parsed parsed =
                InlineMarkdown.parse("**Name** | [site](/s)", none(), true);

        assertEquals(2, parsed.getCells().size());
        assertEquals("Name", cellVisible(parsed, 0));
        assertTrue(parsed.getCells().get(0).get(0).isBold(), "first cell is bold");

        InlineMarkdown.Piece linkCell = parsed.getCells().get(1).get(0);
        assertTrue(linkCell.isLink());
        assertEquals("site", linkCell.getText());
        assertEquals("/s", linkCell.getHref());
    }

    @Test
    @DisplayName("a changed word is highlighted in its own cell only")
    void tableCellHighlightCrossing() {
        // The crossing again, now per cell: segments are positions in the whole raw row.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Draft | Status",
                segments("Draft", true, " | Status", false), true);

        assertTrue(parsed.getCells().get(0).get(0).isChanged(), "first cell changed");
        for (InlineMarkdown.Piece piece : parsed.getCells().get(1)) {
            assertFalse(piece.isChanged(), "second cell unchanged");
        }
    }

    @Test
    @DisplayName("outside a table block, a pipe line stays literal (the safe default)")
    void pipeLineOutsideTableStaysLiteral() {
        // 2-arg parse is table-unaware: a bare pipe in prose must not be read as a cell boundary.
        InlineMarkdown.Parsed parsed = InlineMarkdown.parse("Name | Status", none());

        assertNull(parsed.getCells());
        assertEquals("Name | Status", visible(parsed));
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
