package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the diff model.
 *
 * <p>Two properties matter more than any individual case and are asserted repeatedly below:
 * <ol>
 *   <li>The rendered text is <em>exactly</em> the snapshot text. This is evidence for a public
 *       claim; a diff viewer that normalises whitespace has altered the record it displays.</li>
 *   <li>Nothing that could be mistaken for markup is produced. The model carries text only.</li>
 * </ol>
 */
class MarkdownDiffTest {

    // ------------------------------------------------------------------ helpers

    private static String textOf(MarkdownDiff.Line line) {
        return line.getSegments().isEmpty()
                ? line.getText()
                : line.getSegments().stream().map(MarkdownDiff.Segment::getText)
                        .collect(Collectors.joining());
    }

    private static List<MarkdownDiff.Line> linesOfType(MarkdownDiff.Result result,
                                                       MarkdownDiff.LineType type) {
        return result.getLines().stream()
                .filter(l -> l.getType() == type)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ basics

    @Test
    @DisplayName("reports identical when both revisions have the same content")
    void reportsIdenticalForEqualInput() {
        // Arrange
        String markdown = "# Title\n\nA sentence.\n";

        // Act
        MarkdownDiff.Result result = MarkdownDiff.compare(markdown, markdown);

        // Assert
        assertTrue(result.isIdentical());
        assertEquals(0, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());
    }

    @Test
    @DisplayName("treats null and empty input as an empty document rather than failing")
    void handlesNullAndEmpty() {
        assertTrue(MarkdownDiff.compare(null, null).isIdentical());
        assertTrue(MarkdownDiff.compare("", "").isIdentical());

        MarkdownDiff.Result added = MarkdownDiff.compare(null, "line");
        assertEquals(1, added.getAddedCount());
        assertEquals(0, added.getRemovedCount());
    }

    @Test
    @DisplayName("counts a pure insertion as added only")
    void countsPureInsertion() {
        MarkdownDiff.Result result = MarkdownDiff.compare("one\ntwo\n", "one\nmiddle\ntwo\n");

        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());
        assertEquals("middle", linesOfType(result, MarkdownDiff.LineType.ADDED).get(0).getText());
    }

    @Test
    @DisplayName("counts a pure deletion as removed only")
    void countsPureDeletion() {
        MarkdownDiff.Result result = MarkdownDiff.compare("one\ngone\ntwo\n", "one\ntwo\n");

        assertEquals(0, result.getAddedCount());
        assertEquals(1, result.getRemovedCount());
        assertEquals("gone", linesOfType(result, MarkdownDiff.LineType.REMOVED).get(0).getText());
    }

    @Test
    @DisplayName("numbers lines against their own revision, and not against the other one")
    void numbersLinesPerRevision() {
        // A line added at the top shifts every later line's number in the NEW revision only.
        MarkdownDiff.Result result = MarkdownDiff.compare("a\nb\n", "new\na\nb\n");

        MarkdownDiff.Line addedLine = linesOfType(result, MarkdownDiff.LineType.ADDED).get(0);
        assertEquals(1, addedLine.getNewNumber());
        assertEquals(-1, addedLine.getOldNumber(), "an added line has no line number in the old revision");

        MarkdownDiff.Line firstContext = linesOfType(result, MarkdownDiff.LineType.UNCHANGED).get(0);
        assertEquals(1, firstContext.getOldNumber());
        assertEquals(2, firstContext.getNewNumber());
    }

    // ------------------------------------------------------------------ word-level diff

    @Test
    @DisplayName("marks only the words that changed within an otherwise identical line")
    void marksOnlyChangedWords() {
        // Arrange -- the case the whole feature exists for: a one-word edit in a policy line.
        String before = "Support is provided for twelve months after release.";
        String after = "Support is provided for eighteen months after release.";

        // Act
        MarkdownDiff.Result result = MarkdownDiff.compare(before, after);

        // Assert
        MarkdownDiff.Line removed = linesOfType(result, MarkdownDiff.LineType.REMOVED).get(0);
        MarkdownDiff.Line added = linesOfType(result, MarkdownDiff.LineType.ADDED).get(0);

        assertEquals("twelve", removed.getSegments().stream()
                .filter(MarkdownDiff.Segment::isChanged)
                .map(MarkdownDiff.Segment::getText).collect(Collectors.joining()));
        assertEquals("eighteen", added.getSegments().stream()
                .filter(MarkdownDiff.Segment::isChanged)
                .map(MarkdownDiff.Segment::getText).collect(Collectors.joining()));
    }

    @Test
    @DisplayName("segments always rejoin into the exact original line, whitespace included")
    void segmentsRejoinToOriginalLine() {
        // Leading indent and a double space: both are content in a record, and both are the
        // kind of thing a naive split(" ")/join(" ") tokenizer silently rewrites.
        String before = "    indented  text with   gaps here";
        String after = "    indented  text with   holes here";

        MarkdownDiff.Result result = MarkdownDiff.compare(before, after);

        assertEquals(before, textOf(linesOfType(result, MarkdownDiff.LineType.REMOVED).get(0)));
        assertEquals(after, textOf(linesOfType(result, MarkdownDiff.LineType.ADDED).get(0)));
    }

    @Test
    @DisplayName("drops word highlighting when nearly the whole line changed")
    void dropsWordHighlightingWhenLineIsWhollyRewritten() {
        // Highlighting 90% of a line marks nothing; the line should read as replaced instead.
        MarkdownDiff.Result result = MarkdownDiff.compare(
                "alpha beta gamma delta epsilon",
                "one two three four five");

        assertTrue(linesOfType(result, MarkdownDiff.LineType.REMOVED).get(0).getSegments().isEmpty());
        assertTrue(linesOfType(result, MarkdownDiff.LineType.ADDED).get(0).getSegments().isEmpty());
    }

    @Test
    @DisplayName("does not pair lines for word diffing when the block sizes differ")
    void doesNotInventAlignmentForUnevenBlocks() {
        // Two lines replaced by one: any pairing would be a guess, so none is made.
        MarkdownDiff.Result result = MarkdownDiff.compare("first line\nsecond line\n", "merged\n");

        assertEquals(2, result.getRemovedCount());
        assertEquals(1, result.getAddedCount());
        result.getLines().stream()
                .filter(l -> !l.isUnchanged() && !l.isGap())
                .forEach(l -> assertTrue(l.getSegments().isEmpty()));
    }

    // ------------------------------------------------------------------ context collapsing

    @Test
    @DisplayName("collapses long unchanged runs into a gap carrying the omitted line count")
    void collapsesLongUnchangedRuns() {
        // Arrange -- 40 identical lines, with a single change at the very end.
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            before.append("line ").append(i).append('\n');
        }
        String after = before.toString().replace("line 39", "line thirty-nine");

        // Act
        MarkdownDiff.Result result = MarkdownDiff.compare(before.toString(), after);

        // Assert
        List<MarkdownDiff.Line> gaps = linesOfType(result, MarkdownDiff.LineType.GAP);
        assertEquals(1, gaps.size());
        // 39 unchanged lines precede the change; 3 are kept as leading context.
        assertEquals(36, gaps.get(0).getGapSize());
        assertEquals(3, linesOfType(result, MarkdownDiff.LineType.UNCHANGED).size());
    }

    @Test
    @DisplayName("keeps a short unchanged run whole rather than collapsing it")
    void keepsShortUnchangedRunsWhole() {
        MarkdownDiff.Result result = MarkdownDiff.compare(
                "changed a\nkeep 1\nkeep 2\nchanged b\n",
                "changed A\nkeep 1\nkeep 2\nchanged B\n");

        assertTrue(linesOfType(result, MarkdownDiff.LineType.GAP).isEmpty());
        assertEquals(2, linesOfType(result, MarkdownDiff.LineType.UNCHANGED).size());
    }

    // ------------------------------------------------------------------ structure and limits

    @Test
    @DisplayName("preserves blank lines, which separate paragraphs in Markdown")
    void preservesBlankLines() {
        // Losing the blank line would report a paragraph split as no change at all.
        MarkdownDiff.Result result = MarkdownDiff.compare("one\ntwo\n", "one\n\ntwo\n");

        assertFalse(result.isIdentical());
        assertEquals(1, result.getAddedCount());
    }

    @Test
    @DisplayName("splits on CR, LF and CRLF alike")
    void splitsOnEveryLineEnding() {
        assertEquals(List.of("a", "b"), MarkdownDiff.splitLines("a\r\nb"));
        assertEquals(List.of("a", "b"), MarkdownDiff.splitLines("a\nb"));
        assertEquals(List.of("a", "b"), MarkdownDiff.splitLines("a\rb"));
        // A CRLF must count as one break, not two -- otherwise every line of a Windows-authored
        // snapshot gains a phantom blank line and every diff against it is total.
        assertEquals(2, MarkdownDiff.splitLines("a\r\nb").size());
    }

    @Test
    @DisplayName("flags truncation instead of silently comparing only part of a long page")
    void flagsTruncation() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < MarkdownDiff.MAX_LINES + 10; i++) {
            huge.append("line ").append(i).append('\n');
        }

        MarkdownDiff.Result result = MarkdownDiff.compare(huge.toString(), huge.toString());

        assertTrue(result.isTruncated(), "a partial comparison must say so");
    }

    @Test
    @DisplayName("does not treat page content that looks like markup as markup")
    void doesNotProduceMarkupFromContent() {
        // The model must carry text verbatim; escaping is the view's job. If a diff library
        // were emitting HTML into these strings, this is where it would show up.
        MarkdownDiff.Result result = MarkdownDiff.compare(
                "before <script>alert(1)</script> end",
                "after <script>alert(1)</script> end");

        String rendered = result.getLines().stream()
                .map(MarkdownDiffTest::textOf)
                .collect(Collectors.joining("\n"));
        assertTrue(rendered.contains("<script>alert(1)</script>"),
                "content must survive unchanged");
        assertFalse(rendered.contains("<span"), "no generated markup may leak into the model");
    }

    // ------------------------------------------------------------------ side by side

    @Test
    @DisplayName("pairs a changed line with its replacement, older left and newer right")
    void pairsChangedLines() {
        MarkdownDiff.Result result = MarkdownDiff.compare(
                "Support lasts twelve months.", "Support lasts eighteen months.");

        List<MarkdownDiff.Row> changed = result.getRows().stream()
                .filter(r -> !r.isUnchanged() && !r.isGap())
                .collect(Collectors.toList());

        assertEquals(1, changed.size(), "one line replaced by one line is one row, not two");
        assertEquals("Support lasts twelve months.", changed.get(0).getLeft().getText());
        assertEquals("Support lasts eighteen months.", changed.get(0).getRight().getText());
    }

    @Test
    @DisplayName("puts an unchanged line on both sides as the same object")
    void unchangedLinesAppearOnBothSides() {
        // Two columns are two views of ONE document, not two documents: the shared reference is
        // what lets the view render an unchanged row once and know the sides cannot diverge.
        MarkdownDiff.Result result = MarkdownDiff.compare("same\nold\n", "same\nnew\n");

        MarkdownDiff.Row first = result.getRows().get(0);
        assertTrue(first.isUnchanged());
        assertSame(first.getLeft(), first.getRight());
    }

    @Test
    @DisplayName("leaves the opposite side empty for a pure insertion or deletion")
    void pureInsertionHasNoLeftSide() {
        MarkdownDiff.Result inserted = MarkdownDiff.compare("one\n", "one\ntwo\n");
        MarkdownDiff.Row addedRow = inserted.getRows().stream()
                .filter(r -> !r.isUnchanged() && !r.isGap()).findFirst().orElseThrow();
        assertNull(addedRow.getLeft(), "nothing was there before");
        assertEquals("two", addedRow.getRight().getText());

        MarkdownDiff.Result deleted = MarkdownDiff.compare("one\ntwo\n", "one\n");
        MarkdownDiff.Row removedRow = deleted.getRows().stream()
                .filter(r -> !r.isUnchanged() && !r.isGap()).findFirst().orElseThrow();
        assertEquals("two", removedRow.getLeft().getText());
        assertNull(removedRow.getRight(), "nothing is there after");
    }

    @Test
    @DisplayName("zips uneven blocks rather than dropping the surplus")
    void unevenBlocksKeepEveryLine() {
        // Two lines replaced by one: the second removed line still needs a row, with nothing
        // opposite it. Losing it would silently drop content from the record.
        MarkdownDiff.Result result = MarkdownDiff.compare("first\nsecond\n", "merged\n");

        List<MarkdownDiff.Row> changed = result.getRows().stream()
                .filter(r -> !r.isUnchanged() && !r.isGap())
                .collect(Collectors.toList());

        assertEquals(2, changed.size());
        assertEquals("first", changed.get(0).getLeft().getText());
        assertEquals("merged", changed.get(0).getRight().getText());
        assertEquals("second", changed.get(1).getLeft().getText());
        assertNull(changed.get(1).getRight());
    }

    @Test
    @DisplayName("carries a collapsed run through as a single gap row")
    void gapsSurvivePairing() {
        StringBuilder before = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            before.append("line ").append(i).append('\n');
        }
        MarkdownDiff.Result result =
                MarkdownDiff.compare(before.toString(), before.toString().replace("line 39", "changed"));

        List<MarkdownDiff.Row> gaps = result.getRows().stream()
                .filter(MarkdownDiff.Row::isGap).collect(Collectors.toList());
        assertEquals(1, gaps.size());
        assertEquals(36, gaps.get(0).getGapSize());
        assertNull(gaps.get(0).getLeft());
    }

    @Test
    @DisplayName("says the same thing as the unified view about what changed")
    void rowsAgreeWithLines() {
        // Both presentations must come from one diff; if the row model ever ran its own
        // comparison the two views could disagree about what changed on the same page.
        MarkdownDiff.Result result = MarkdownDiff.compare(
                "alpha\nbeta\ngamma\n", "alpha\nBETA\ngamma\ndelta\n");

        long addedRows = result.getRows().stream().filter(r -> r.getRight() != null && !r.isUnchanged()).count();
        long removedRows = result.getRows().stream().filter(r -> r.getLeft() != null && !r.isUnchanged()).count();

        assertEquals(result.getAddedCount(), addedRows);
        assertEquals(result.getRemovedCount(), removedRows);
    }

    @Test
    @DisplayName("tokenizes into alternating whitespace and word runs that rejoin exactly")
    void tokenizesReversibly() {
        String line = "  two  spaces\tand a tab ";
        assertEquals(line, String.join("", MarkdownDiff.tokenize(line)));
    }

    // ------------------------------------------------------------------ tables (block context)

    private static MarkdownDiff.Line lineWithText(MarkdownDiff.Result result, String text) {
        return result.getLines().stream()
                .filter(l -> text.equals(l.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line with text: " + text));
    }

    /**
     * A table lives across several lines, so only the diff -- which sees the whole sequence -- can
     * tell {@link InlineMarkdown} that a "Name | Status" line is a table row rather than prose that
     * happens to contain a pipe. These assert that context is supplied.
     */
    // The change sits in the table body so the header, separator and a trailing prose line all stay
    // within the 3 lines of context either side and are emitted as real lines rather than collapsed
    // into a gap -- which is what lets these assert on them.
    private static final String TABLE_OLD = "Name | Status\n--- | ---\nAlpha | Active\nSee notes\n";
    private static final String TABLE_NEW = "Name | Status\n--- | ---\nAlpha | Retired\nSee notes\n";

    @Test
    @DisplayName("a table's header and body rows are parsed into cells; the separator is marked")
    void tableRowsBecomeCells() {
        MarkdownDiff.Result result = MarkdownDiff.compare(TABLE_OLD, TABLE_NEW);

        // Header row: two cells, read as a table because a separator follows it.
        assertNotNull(lineWithText(result, "Name | Status").getFormat().getCells());
        assertEquals(2, lineWithText(result, "Name | Status").getFormat().getCells().size());
        // Separator row: recognised as structure, not content.
        assertTrue(lineWithText(result, "--- | ---").getFormat().isTableSeparator());
    }

    @Test
    @DisplayName("prose next to a table is not turned into cells")
    void proseBesideTableStaysProse() {
        MarkdownDiff.Result result = MarkdownDiff.compare(TABLE_OLD, TABLE_NEW);

        assertNull(lineWithText(result, "See notes").getFormat().getCells(),
                "a paragraph is not a table row even when a table sits right above it");
    }

    @Test
    @DisplayName("a table nested in a blockquote is still detected as a table")
    void blockquotedTableDetected() {
        // The normalizer prefixes '> ' to every row, so the separator is "> --- | ---". Detection
        // must strip the quote prefix before recognising it, exactly as InlineMarkdown does.
        String old = "> Name | Status\n> --- | ---\n> Alpha | Active\n> See notes\n";
        String changed = "> Name | Status\n> --- | ---\n> Alpha | Retired\n> See notes\n";

        MarkdownDiff.Result result = MarkdownDiff.compare(old, changed);

        assertNotNull(lineWithText(result, "> Name | Status").getFormat().getCells(),
                "a quoted header row is still a table row");
        assertTrue(lineWithText(result, "> --- | ---").getFormat().isTableSeparator());
    }

    @Test
    @DisplayName("an unchanged row is shown as a table when either revision makes it one")
    void unchangedRowIsTableFromEitherSide() {
        // The header text is byte-identical (hence unchanged), but a separator added beneath it
        // turns it into a table header on the new side only. The shared line must still show cells.
        String old = "Header | Status\n";
        String changed = "Header | Status\n--- | ---\nRow1 | Active\n";

        MarkdownDiff.Result result = MarkdownDiff.compare(old, changed);

        assertNotNull(lineWithText(result, "Header | Status").getFormat().getCells(),
                "an unchanged line that becomes a table header must render as cells");
    }

    @Test
    @DisplayName("a single-column table has no cross-row separator, so it falls back to a rule + text")
    void singleColumnTableFallsBackToRule() {
        // Documented ambiguity: a one-column separator is just "---", indistinguishable from a rule.
        // isSeparatorRow requires >=2 cells, so no table block forms and the header stays plain text.
        String old = "Name\n---\nAlpha\nSee notes\n";
        String changed = "Name\n---\nBravo\nSee notes\n";

        MarkdownDiff.Result result = MarkdownDiff.compare(old, changed);

        assertNull(lineWithText(result, "Name").getFormat().getCells(), "no table: header stays text");
        assertTrue(lineWithText(result, "---").getFormat().isHorizontalRule(), "the '---' is a rule");
    }

    @Test
    @DisplayName("an unchanged row stays a table when only the OLD revision had the table shape")
    void unchangedRowIsTableFromOldSide() {
        // Mirror of unchangedRowIsTableFromEitherSide: the table existed before and its body was
        // replaced by prose, so the unchanged header must still render as cells from the OLD flag.
        String old = "Header | Status\n--- | ---\nRow1 | Active\n";
        String changed = "Header | Status\nJust prose now.\n";

        MarkdownDiff.Result result = MarkdownDiff.compare(old, changed);

        assertNotNull(lineWithText(result, "Header | Status").getFormat().getCells(),
                "an unchanged header whose table was removed must still render as cells");
    }

    @Test
    @DisplayName("a changed cell is highlighted in its own cell only")
    void changedCellHighlightsPerCell() {
        String old = TABLE_OLD;
        String changed = TABLE_NEW;

        MarkdownDiff.Result result = MarkdownDiff.compare(old, changed);

        MarkdownDiff.Line added = result.getLines().stream()
                .filter(MarkdownDiff.Line::isAdded)
                .findFirst().orElseThrow(AssertionError::new);
        List<List<InlineMarkdown.Piece>> cells = added.getFormat().getCells();
        assertNotNull(cells, "the changed body row is still a table row");
        assertEquals(2, cells.size());
        // First cell "Alpha" unchanged, second cell "Retired" carries the highlight.
        assertTrue(cells.get(0).stream().noneMatch(InlineMarkdown.Piece::isChanged));
        assertTrue(cells.get(1).stream().anyMatch(InlineMarkdown.Piece::isChanged));
    }
}
