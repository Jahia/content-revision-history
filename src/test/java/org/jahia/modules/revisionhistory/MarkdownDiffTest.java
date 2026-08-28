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

    @Test
    @DisplayName("tokenizes into alternating whitespace and word runs that rejoin exactly")
    void tokenizesReversibly() {
        String line = "  two  spaces\tand a tab ";
        assertEquals(line, String.join("", MarkdownDiff.tokenize(line)));
    }
}
