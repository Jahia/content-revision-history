package org.jahia.modules.revisionhistory;

import difflib.Chunk;
import difflib.Delta;
import difflib.DiffUtils;
import difflib.Patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compares two Markdown snapshots and produces a presentation-neutral, unified diff model.
 *
 * <p>Pure: no JCR, no HTTP, no rendering. That is the point -- this is the only part of the
 * diff feature whose correctness can be pinned down by unit tests, so everything that can live
 * here does.
 *
 * <p><b>No HTML is produced here.</b> {@code difflib} ships a {@code DiffRowGenerator} that
 * emits {@code <span class="editOldInline">} markup directly, and using it would have been
 * fewer lines. It is not used, because the text being diffed is page content: it can contain
 * {@code <}, {@code &} and anything else an editor typed, and a model that mixes "markup I
 * generated" with "text I must escape" in one string has no safe way to be rendered. The model
 * below carries text only; the view escapes every piece of it. {@code difflib} is used purely
 * for the Myers diff algorithm, which is the part worth reusing.
 *
 * <p>{@code difflib} is provided by the platform ({@code diffutils-1.3.0}, exported to modules
 * as {@code difflib}), so this adds no dependency to the bundle.
 */
public final class MarkdownDiff {

    /**
     * Unchanged lines kept either side of a change.
     *
     * <p>A support policy renders to hundreds of Markdown lines and a typical revision touches
     * one sentence. Showing the whole document to display a one-line change buries the answer;
     * showing no context at all makes the change unreadable. Three is the long-standing
     * unified-diff convention and it reads well at sentence granularity, which is what
     * {@link MarkdownNormalizer}'s sentence-per-line output gives us.
     */
    static final int CONTEXT_LINES = 3;

    /**
     * Hard cap on lines compared per side. Myers is O(ND) in time and the collected model is
     * O(N) in memory; this runs on a public request path, so it needs a ceiling. Exceeding it
     * truncates -- and says so, loudly, through {@link Result#isTruncated()}, because a diff
     * that silently omitted the tail would be worse than no diff at all.
     */
    static final int MAX_LINES = 5_000;

    /** Beyond this, a line is compared whole rather than word by word. */
    static final int MAX_TOKENS_PER_LINE = 400;

    /**
     * If more than this fraction of a line's words changed, the word-level highlight is
     * dropped and the line is shown as wholly replaced. Highlighting 90% of a line marks
     * nothing: the reader gets a wall of emphasis with no signal in it.
     *
     * <p>Measured over words only -- see {@link #segmentsFor}, where counting whitespace tokens
     * as well made this threshold unreachable in practice.
     */
    static final double MAX_CHANGED_TOKEN_RATIO = 0.7;

    private MarkdownDiff() {
        // static utility
    }

    /** What a rendered line represents. */
    public enum LineType {
        /** Present and identical in both revisions. */
        UNCHANGED,
        /** Present only in the newer revision. */
        ADDED,
        /** Present only in the older revision. */
        REMOVED,
        /** A run of unchanged lines that was collapsed; carries only a count. */
        GAP
    }

    /**
     * A run of characters within one line, flagged as changed or not.
     *
     * <p>Concatenating every segment's text reproduces the source line byte for byte,
     * whitespace included. That is a deliberate invariant, and it is what lets the view escape
     * segments independently without ever altering the record it is displaying.
     */
    public static final class Segment {
        private final String text;
        private final boolean changed;

        Segment(String text, boolean changed) {
            this.text = text;
            this.changed = changed;
        }

        public String getText() {
            return text;
        }

        public boolean isChanged() {
            return changed;
        }
    }

    /** One row of the unified diff. */
    public static final class Line {
        private final LineType type;
        private final String text;
        private final int oldNumber;
        private final int newNumber;
        private final int gapSize;
        private final List<Segment> segments;
        private final InlineMarkdown.Parsed format;

        Line(LineType type, String text, int oldNumber, int newNumber, int gapSize,
             List<Segment> segments) {
            this.type = type;
            this.text = text;
            this.oldNumber = oldNumber;
            this.newNumber = newNumber;
            this.gapSize = gapSize;
            this.segments = segments == null
                    ? Collections.<Segment>emptyList()
                    : Collections.unmodifiableList(segments);
            // Computed here rather than in the view: this class is documented as immutable and as
            // holding no JCR, and the view is a JSP with no session left to lean on. It also means
            // the crossing of formatting against the word-level segments happens exactly once.
            this.format = InlineMarkdown.parse(text, this.segments);
        }

        public LineType getType() {
            return type;
        }

        public String getText() {
            return text;
        }

        /** 1-based line number in the older revision, or -1 when the line is an addition. */
        public int getOldNumber() {
            return oldNumber;
        }

        /** 1-based line number in the newer revision, or -1 when the line is a removal. */
        public int getNewNumber() {
            return newNumber;
        }

        /** Number of collapsed lines; meaningful only for {@link LineType#GAP}. */
        public int getGapSize() {
            return gapSize;
        }

        /**
         * Word-level breakdown, or empty when the line should be shown as changed in full.
         * Empty is not an error state -- it is the normal case for unchanged lines and for
         * lines whose change was too broad to highlight usefully.
         */
        public List<Segment> getSegments() {
            return segments;
        }

        /**
         * The same line read back as formatting: heading level, list marker, and inline runs with
         * their delimiters removed.
         *
         * <p>{@link #getSegments()} is still the word-level breakdown over the RAW text, and both
         * are kept because they answer different questions. The diff is computed on the raw
         * Markdown and must stay that way -- it is what makes a snapshot comparable to another
         * snapshot regardless of how either is displayed. This is presentation only, derived
         * afterwards, and carries the changed flags across so rendering cannot cost the highlight.
         */
        public InlineMarkdown.Parsed getFormat() {
            return format;
        }

        // Convenience predicates. JSTL comparison of an enum against a string literal depends
        // on the EL version's coercion rules, so the view asks these instead of guessing.
        public boolean isAdded() {
            return type == LineType.ADDED;
        }

        public boolean isRemoved() {
            return type == LineType.REMOVED;
        }

        public boolean isUnchanged() {
            return type == LineType.UNCHANGED;
        }

        public boolean isGap() {
            return type == LineType.GAP;
        }
    }

    /**
     * One row of a side-by-side comparison: the older revision on the left, the newer on the
     * right, either of which may be absent.
     *
     * <p>Built from the same {@link Line} list the unified view uses, rather than from a second
     * diff. The flat list emits a change as every removed line followed by every added line; a
     * side-by-side view needs them paired, and pairing after the fact keeps one diff algorithm
     * and one set of word-level segments behind both presentations.
     *
     * <p>An unchanged line appears on BOTH sides, referencing the same object: the two columns
     * are two views of one document, not two documents.
     */
    public static final class Row {
        private final Line left;
        private final Line right;
        private final int gapSize;

        Row(Line left, Line right, int gapSize) {
            this.left = left;
            this.right = right;
            this.gapSize = gapSize;
        }

        /** The older revision's line, or null where this row is an insertion. */
        public Line getLeft() {
            return left;
        }

        /** The newer revision's line, or null where this row is a deletion. */
        public Line getRight() {
            return right;
        }

        public int getGapSize() {
            return gapSize;
        }

        public boolean isGap() {
            return gapSize > 0;
        }

        /** True when both sides carry the same unchanged line. */
        public boolean isUnchanged() {
            return left != null && left == right;
        }
    }

    /** The complete comparison. */
    public static final class Result {
        private final List<Line> lines;
        private final int addedCount;
        private final int removedCount;
        private final boolean truncated;

        private final List<Row> rows;

        Result(List<Line> lines, int addedCount, int removedCount, boolean truncated) {
            this.lines = Collections.unmodifiableList(lines);
            this.rows = Collections.unmodifiableList(pair(lines));
            this.addedCount = addedCount;
            this.removedCount = removedCount;
            this.truncated = truncated;
        }

        public List<Line> getLines() {
            return lines;
        }

        /**
         * The same comparison as side-by-side rows, older revision left, newer right.
         *
         * <p>Derived from {@link #getLines()}, so both presentations are the same diff and cannot
         * disagree about what changed.
         */
        public List<Row> getRows() {
            return rows;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public int getRemovedCount() {
            return removedCount;
        }

        /** True when either side hit {@link #MAX_LINES} and the comparison is partial. */
        public boolean isTruncated() {
            return truncated;
        }

        /** True when the two revisions have identical content. */
        public boolean isIdentical() {
            return addedCount == 0 && removedCount == 0;
        }
    }

    /**
     * @param oldMarkdown the older revision's snapshot; null is treated as empty
     * @param newMarkdown the newer revision's snapshot; null is treated as empty
     * @return the unified diff model, never null
     */
    public static Result compare(String oldMarkdown, String newMarkdown) {
        List<String> oldLines = splitLines(oldMarkdown);
        List<String> newLines = splitLines(newMarkdown);

        boolean truncated = oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES;
        if (truncated) {
            oldLines = capped(oldLines);
            newLines = capped(newLines);
        }

        Patch patch = DiffUtils.diff(oldLines, newLines);
        List<Delta> deltas = patch.getDeltas();

        List<Line> out = new ArrayList<>();
        Counter counter = new Counter();
        int oldIndex = 0;
        int newIndex = 0;

        for (int i = 0; i < deltas.size(); i++) {
            Delta delta = deltas.get(i);
            int changeStart = delta.getOriginal().getPosition();

            emitUnchangedRun(out, oldLines, oldIndex, newIndex, changeStart - oldIndex,
                    i > 0, true);

            newIndex += changeStart - oldIndex;
            oldIndex = changeStart;

            List<String> removed = stringLines(delta.getOriginal());
            List<String> added = stringLines(delta.getRevised());
            emitChange(out, removed, added, oldIndex, newIndex, counter);

            oldIndex += removed.size();
            newIndex += added.size();
        }

        emitUnchangedRun(out, oldLines, oldIndex, newIndex, oldLines.size() - oldIndex,
                !deltas.isEmpty(), false);

        return new Result(out, counter.added, counter.removed, truncated);
    }

    /**
     * Pairs a unified line list into side-by-side rows.
     *
     * <p>A change arrives as a run of removed lines followed by a run of added lines, so the runs
     * are buffered and then zipped: row i takes removed[i] on the left and added[i] on the right.
     * When the runs are different lengths the shorter side simply runs out and those rows carry a
     * null, which is what a pure insertion or deletion looks like.
     */
    private static List<Row> pair(List<Line> lines) {
        List<Row> rows = new ArrayList<>();
        List<Line> removed = new ArrayList<>();
        List<Line> added = new ArrayList<>();

        for (Line line : lines) {
            if (line.isRemoved()) {
                removed.add(line);
                continue;
            }
            if (line.isAdded()) {
                added.add(line);
                continue;
            }
            flush(rows, removed, added);
            if (line.isGap()) {
                rows.add(new Row(null, null, line.getGapSize()));
            } else {
                // The same object on both sides: two views of one document.
                rows.add(new Row(line, line, 0));
            }
        }
        flush(rows, removed, added);
        return rows;
    }

    private static void flush(List<Row> rows, List<Line> removed, List<Line> added) {
        int pairs = Math.max(removed.size(), added.size());
        for (int i = 0; i < pairs; i++) {
            rows.add(new Row(
                    i < removed.size() ? removed.get(i) : null,
                    i < added.size() ? added.get(i) : null,
                    0));
        }
        removed.clear();
        added.clear();
    }

    // ------------------------------------------------------------------ internals

    /** Mutable tally threaded through emission; avoids returning tuples from void helpers. */
    private static final class Counter {
        private int added;
        private int removed;
    }

    /**
     * Emits an unchanged region, collapsing its middle when it is longer than the context
     * either side needs.
     *
     * @param afterChange  whether a change precedes this run (so it needs trailing context)
     * @param beforeChange whether a change follows it (so it needs leading context)
     */
    private static void emitUnchangedRun(List<Line> out, List<String> oldLines, int oldStart,
                                         int newStart, int length, boolean afterChange,
                                         boolean beforeChange) {
        if (length <= 0) {
            return;
        }
        int head = afterChange ? CONTEXT_LINES : 0;
        int tail = beforeChange ? CONTEXT_LINES : 0;

        if (length <= head + tail) {
            for (int i = 0; i < length; i++) {
                out.add(unchanged(oldLines.get(oldStart + i), oldStart + i + 1, newStart + i + 1));
            }
            return;
        }
        for (int i = 0; i < head; i++) {
            out.add(unchanged(oldLines.get(oldStart + i), oldStart + i + 1, newStart + i + 1));
        }
        out.add(new Line(LineType.GAP, "", -1, -1, length - head - tail, null));
        for (int i = length - tail; i < length; i++) {
            out.add(unchanged(oldLines.get(oldStart + i), oldStart + i + 1, newStart + i + 1));
        }
    }

    private static Line unchanged(String text, int oldNumber, int newNumber) {
        return new Line(LineType.UNCHANGED, text, oldNumber, newNumber, 0, null);
    }

    /**
     * Emits one delta.
     *
     * <p>When a delta replaces the same number of lines it replaced, the lines are paired and
     * compared word by word -- that is what turns "this paragraph changed" into "this word
     * changed". When the counts differ there is no defensible pairing, so the lines are shown
     * as a plain block removal followed by a block addition rather than an invented alignment.
     */
    private static void emitChange(List<Line> out, List<String> removed, List<String> added,
                                   int oldIndex, int newIndex, Counter counter) {
        boolean pairable = !removed.isEmpty() && removed.size() == added.size();

        for (int i = 0; i < removed.size(); i++) {
            List<Segment> segments = pairable
                    ? segmentsFor(removed.get(i), added.get(i), true) : null;
            out.add(new Line(LineType.REMOVED, removed.get(i), oldIndex + i + 1, -1, 0, segments));
            counter.removed++;
        }
        for (int i = 0; i < added.size(); i++) {
            List<Segment> segments = pairable
                    ? segmentsFor(removed.get(i), added.get(i), false) : null;
            out.add(new Line(LineType.ADDED, added.get(i), -1, newIndex + i + 1, 0, segments));
            counter.added++;
        }
    }

    /**
     * Word-level segmentation of one side of a changed line pair.
     *
     * @param forRemoved true to mark the words that disappeared, false for those that appeared
     * @return segments, or null when the line should be presented as changed in full
     */
    private static List<Segment> segmentsFor(String oldLine, String newLine, boolean forRemoved) {
        List<String> oldTokens = tokenize(oldLine);
        List<String> newTokens = tokenize(newLine);
        if (oldTokens.size() > MAX_TOKENS_PER_LINE || newTokens.size() > MAX_TOKENS_PER_LINE) {
            return null;
        }

        List<String> side = forRemoved ? oldTokens : newTokens;
        boolean[] changed = new boolean[side.size()];
        int changedCount = 0;

        for (Delta delta : DiffUtils.diff(oldTokens, newTokens).getDeltas()) {
            Chunk chunk = forRemoved ? delta.getOriginal() : delta.getRevised();
            // An INSERT has an empty original chunk and a DELETE an empty revised one, so the
            // loop below simply contributes nothing on the side that did not participate.
            for (int i = 0; i < chunk.size(); i++) {
                int index = chunk.getPosition() + i;
                if (index >= 0 && index < changed.length && !changed[index]) {
                    changed[index] = true;
                    changedCount++;
                }
            }
        }

        // The ratio is measured over WORDS, not over tokens. tokenize() emits whitespace runs
        // as tokens of their own and whitespace almost never changes, so counting them roughly
        // halves every ratio -- enough that a wholly rewritten line ("alpha beta gamma" ->
        // "one two three": 5 of 9 tokens) stayed under the threshold and came back fully
        // highlighted, which is precisely the noise this guard exists to suppress.
        int words = 0;
        int changedWords = 0;
        for (int i = 0; i < side.size(); i++) {
            if (isWhitespace(side.get(i))) {
                continue;
            }
            words++;
            if (changed[i]) {
                changedWords++;
            }
        }
        if (changedWords == 0 || changedWords > words * MAX_CHANGED_TOKEN_RATIO) {
            return null;
        }
        return merge(side, changed);
    }

    /** Tokens are homogeneous by construction, so the first character settles it. */
    private static boolean isWhitespace(String token) {
        return !token.isEmpty() && Character.isWhitespace(token.charAt(0));
    }

    /** Coalesces adjacent tokens sharing a flag, so the view emits one element per run. */
    private static List<Segment> merge(List<String> tokens, boolean[] changed) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean current = changed.length > 0 && changed[0];
        for (int i = 0; i < tokens.size(); i++) {
            if (changed[i] != current) {
                segments.add(new Segment(buffer.toString(), current));
                buffer.setLength(0);
                current = changed[i];
            }
            buffer.append(tokens.get(i));
        }
        if (buffer.length() > 0) {
            segments.add(new Segment(buffer.toString(), current));
        }
        return segments;
    }

    /**
     * Splits into alternating runs of whitespace and non-whitespace.
     *
     * <p>Whitespace is kept as tokens rather than discarded so that joining the tokens back
     * together reproduces the line exactly -- see {@link Segment}. Splitting on whitespace and
     * re-joining with single spaces would quietly rewrite the text being displayed as evidence.
     */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            int start = i;
            boolean space = Character.isWhitespace(line.charAt(i));
            while (i < line.length() && Character.isWhitespace(line.charAt(i)) == space) {
                i++;
            }
            tokens.add(line.substring(start, i));
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringLines(Chunk chunk) {
        return new ArrayList<>((List<String>) chunk.getLines());
    }

    private static List<String> capped(List<String> lines) {
        return lines.size() <= MAX_LINES ? lines : new ArrayList<>(lines.subList(0, MAX_LINES));
    }

    /**
     * Splits on any line ending without collapsing blank lines: a blank line is structure in
     * Markdown (it separates paragraphs), so dropping it would report a paragraph split as no
     * change at all.
     */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
