package org.jahia.modules.revisionhistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads one line of the Markdown <em>this module generates</em>, so a diff row can be shown
 * formatted instead of as its own syntax.
 *
 * <p><b>Why this is not a Markdown parser.</b> The module has deliberately never had one:
 * "Markdown is generated, never parsed", which is what keeps a third-party parser and its HTML
 * sink out of a public page built from captured content. This reads only what
 * {@link MarkdownNormalizer} emits, and that grammar is closed and tiny: {@code #}-repeat
 * headings, {@code - } and {@code N. } list items with two-space indents, {@code **bold**},
 * {@code *italic*}, {@code [text](href)}, {@code ![alt](src)}, {@code |} table cells, and the
 * backslash escapes {@code \\}, {@code \*}, {@code \[}, {@code \]}. Anything it does not recognise
 * stays literal text, which is exactly what the view did for everything before.
 *
 * <p><b>The escaping contract is symmetric.</b> The normaliser escapes every literal backslash and
 * asterisk in text; this reads every backslash as an escape and every delimiter pair as emphasis.
 * It used to be one-sided -- the normaliser escaped inside link text only -- so a stored
 * {@code C:\Users\bob} was displayed as {@code C:Usersbob} and {@code 2 ** 8 is 256 and 3 ** 2}
 * came out mostly bold: the archive was right and the panel misrepresented it.
 *
 * <p><b>Links and images are deliberately left as their literal Markdown.</b> Collapsing
 * {@code [text](href)} to {@code text} would hide an href change: the words stay the same, the
 * destination moves, and a diff that renders only the words would show no difference at all. In a
 * record whose entire purpose is to show what changed, rendering that hides a change is worse than
 * syntax on screen. The same goes for {@code ![alt](src)}.
 *
 * <p><b>Offsets are the point of {@link Piece#getRawStart()}.</b> Rendering removes characters --
 * delimiters, escapes, the line prefix -- so visible positions no longer match positions in the
 * raw line, and {@code MarkdownDiff}'s word-level segments are expressed in raw positions. Every
 * emitted character therefore carries the raw offset it came from, so the two can be crossed
 * without the diff having to know anything about formatting.
 */
public final class InlineMarkdown {

    /** Bold delimiter. */
    private static final String BOLD = "**";

    /** Italic delimiter: a single asterisk that is not half of a bold delimiter. */
    private static final char ITALIC = '*';

    private static final int MAX_HEADING_LEVEL = 6;

    /** Two spaces per level, matching {@code MarkdownNormalizer}'s list indent. */
    private static final int SPACES_PER_LIST_LEVEL = 2;

    private InlineMarkdown() {
        // static utility
    }

    /** A run of visible text sharing one set of marks, tracked back to the raw line. */
    public static final class Piece {
        private final String text;
        private final boolean bold;
        private final boolean italic;
        private final int rawStart;
        private final int rawEnd;
        private final boolean changed;

        Piece(String text, boolean bold, boolean italic, int rawStart, int rawEnd, boolean changed) {
            this.text = text;
            this.bold = bold;
            this.italic = italic;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.changed = changed;
        }

        /** @return the visible text, with delimiters and escapes already removed */
        public String getText() {
            return text;
        }

        public boolean isBold() {
            return bold;
        }

        public boolean isItalic() {
            return italic;
        }

        /** @return whether the word-level diff marked this run as changed */
        public boolean isChanged() {
            return changed;
        }

        /** @return offset of the first raw character this run came from */
        public int getRawStart() {
            return rawStart;
        }

        /** @return offset one past the last raw character this run came from */
        public int getRawEnd() {
            return rawEnd;
        }
    }

    /** One line, split into its line-level shape and its inline runs. */
    public static final class Parsed {
        private final int headingLevel;
        private final String listMarker;
        private final int listDepth;
        private final List<Piece> pieces;

        Parsed(int headingLevel, String listMarker, int listDepth, List<Piece> pieces) {
            this.headingLevel = headingLevel;
            this.listMarker = listMarker;
            this.listDepth = listDepth;
            this.pieces = Collections.unmodifiableList(pieces);
        }

        /**
         * @return 1..6 for a heading line, 0 otherwise
         *
         * <p>The view must render this as a class, never as a real {@code <h1>}-{@code <h6>}: the
         * comparison panel sits inside the host page, and emitting real headings would splice the
         * snapshot's outline into the page's own, breaking heading order for anyone navigating by
         * it. It is the appearance that is wanted here, not the semantics.
         */
        public int getHeadingLevel() {
            return headingLevel;
        }

        /** @return {@code "-"} for a bullet, the number text for an ordered item, else null */
        public String getListMarker() {
            return listMarker;
        }

        /** @return nesting depth of a list item, 0 for a top-level item or a non-list line */
        public int getListDepth() {
            return listDepth;
        }

        /** @return the inline runs of the line's body, prefix removed */
        public List<Piece> getPieces() {
            return pieces;
        }
    }

    /**
     * @param raw      one line of generated Markdown
     * @param segments the word-level breakdown for that same line, or empty when there is none
     * @return the line's rendered shape, with each run marked changed or not
     */
    public static Parsed parse(String raw, List<MarkdownDiff.Segment> segments) {
        if (raw == null) {
            return new Parsed(0, null, 0, new ArrayList<Piece>());
        }
        boolean[] changedByOffset = changedOffsets(raw, segments);

        int i = 0;
        int headingLevel = 0;
        while (headingLevel < MAX_HEADING_LEVEL && i + headingLevel < raw.length()
                && raw.charAt(i + headingLevel) == '#') {
            headingLevel++;
        }
        if (headingLevel > 0 && i + headingLevel < raw.length()
                && raw.charAt(i + headingLevel) == ' ') {
            i += headingLevel + 1;
        } else {
            // A '#' not followed by a space is not a heading, it is content.
            headingLevel = 0;
        }

        int indent = 0;
        while (i + indent < raw.length() && raw.charAt(i + indent) == ' ') {
            indent++;
        }
        String listMarker = null;
        int listDepth = 0;
        int afterIndent = i + indent;
        if (headingLevel == 0 && afterIndent + 1 < raw.length()
                && raw.charAt(afterIndent) == '-' && raw.charAt(afterIndent + 1) == ' ') {
            listMarker = "-";
            listDepth = indent / SPACES_PER_LIST_LEVEL;
            i = afterIndent + 2;
        } else if (headingLevel == 0) {
            int digits = 0;
            while (afterIndent + digits < raw.length()
                    && Character.isDigit(raw.charAt(afterIndent + digits))) {
                digits++;
            }
            if (digits > 0 && afterIndent + digits + 1 < raw.length()
                    && raw.charAt(afterIndent + digits) == '.'
                    && raw.charAt(afterIndent + digits + 1) == ' ') {
                listMarker = raw.substring(afterIndent, afterIndent + digits) + ".";
                listDepth = indent / SPACES_PER_LIST_LEVEL;
                i = afterIndent + digits + 2;
            }
        }

        return new Parsed(headingLevel, listMarker, listDepth, run(raw, i, changedByOffset));
    }

    /**
     * @return for each raw offset, whether the word-level diff marked it changed
     *
     * <p>Segments are contiguous and concatenate to the line, so their boundaries are recoverable
     * by accumulating lengths. An empty list means "no breakdown", which is the normal case for an
     * unchanged line and for a change too broad to highlight usefully; nothing is marked then, and
     * the row's own added/removed styling carries the meaning.
     */
    private static boolean[] changedOffsets(String raw, List<MarkdownDiff.Segment> segments) {
        boolean[] changed = new boolean[raw.length()];
        if (segments == null || segments.isEmpty()) {
            return changed;
        }
        int at = 0;
        for (MarkdownDiff.Segment segment : segments) {
            String text = segment.getText();
            int length = text == null ? 0 : text.length();
            for (int k = 0; k < length && at + k < changed.length; k++) {
                changed[at + k] = segment.isChanged();
            }
            at += length;
        }
        return changed;
    }

    /**
     * Walks the body and groups it into runs.
     *
     * <p>Two passes rather than one, deliberately. Emitting characters and closing runs in a
     * single loop meant tracking the current run's raw span across delimiters and escapes at the
     * same time, which was easy to get subtly wrong and impossible to read. The first pass here
     * only decides what is visible and where each visible character came from; the second only
     * groups. Neither needs to know about the other.
     */
    private static List<Piece> run(String raw, int from, boolean[] changedByOffset) {
        StringBuilder visible = new StringBuilder();
        List<int[]> spans = new ArrayList<>();
        List<Boolean> bolds = new ArrayList<>();
        List<Boolean> italics = new ArrayList<>();

        boolean bold = false;
        boolean italic = false;
        int i = from;
        while (i < raw.length()) {
            if (raw.charAt(i) == '\\' && i + 1 < raw.length()) {
                // MarkdownNormalizer escapes '\', '*', '[' and ']' on the way in. Showing the
                // escape is showing its own bookkeeping.
                visible.append(raw.charAt(i + 1));
                spans.add(new int[]{i, i + 2});
                bolds.add(bold);
                italics.add(italic);
                i += 2;
            } else if (raw.startsWith(BOLD, i) && (bold || closesLater(raw, i, BOLD))) {
                bold = !bold;
                i += BOLD.length();
            } else if (raw.charAt(i) == ITALIC && !raw.startsWith(BOLD, i)
                    && (italic || italicClosesLater(raw, i))) {
                // A single asterisk only: the first half of an unmatched "**" is content, not an
                // italic opener, or "2 ** 8" would lose both asterisks to an empty italic span.
                italic = !italic;
                i++;
            } else {
                visible.append(raw.charAt(i));
                spans.add(new int[]{i, i + 1});
                bolds.add(bold);
                italics.add(italic);
                i++;
            }
        }

        List<Piece> pieces = new ArrayList<>();
        int k = 0;
        while (k < visible.length()) {
            boolean runBold = bolds.get(k);
            boolean runItalic = italics.get(k);
            boolean runChanged = changedAt(changedByOffset, spans.get(k)[0]);
            int start = k;
            while (k < visible.length()
                    && bolds.get(k) == runBold
                    && italics.get(k) == runItalic
                    && changedAt(changedByOffset, spans.get(k)[0]) == runChanged) {
                k++;
            }
            pieces.add(new Piece(visible.substring(start, k), runBold, runItalic,
                    spans.get(start)[0], spans.get(k - 1)[1], runChanged));
        }
        return pieces;
    }

    private static boolean changedAt(boolean[] changedByOffset, int offset) {
        return offset >= 0 && offset < changedByOffset.length && changedByOffset[offset];
    }

    /**
     * Does a span opened here ever close?
     *
     * <p>Asked only of an OPENING delimiter. With the normaliser escaping literal asterisks a lone
     * delimiter should no longer occur, but an older snapshot (generator 5 or earlier) can still
     * hold one -- "2 ** 8 is 256" -- and it must not style the rest of the line. Once inside a
     * span the next delimiter closes it unconditionally, because nothing follows a closing
     * delimiter for this to find, and asking this of it left the closer on screen as literal text.
     * An escaped delimiter does not close anything.
     */
    private static boolean closesLater(String raw, int at, String delimiter) {
        int next = raw.indexOf(delimiter, at + delimiter.length());
        while (next > 0 && raw.charAt(next - 1) == '\\') {
            next = raw.indexOf(delimiter, next + delimiter.length());
        }
        return next >= 0;
    }

    /** Is there a later single, unescaped asterisk -- one that is not half of a {@code **}? */
    private static boolean italicClosesLater(String raw, int at) {
        for (int k = at + 1; k < raw.length(); k++) {
            if (raw.charAt(k) != ITALIC) {
                continue;
            }
            boolean escaped = raw.charAt(k - 1) == '\\';
            boolean pairedBefore = raw.charAt(k - 1) == ITALIC;
            boolean pairedAfter = k + 1 < raw.length() && raw.charAt(k + 1) == ITALIC;
            if (!escaped && !pairedBefore && !pairedAfter) {
                return true;
            }
        }
        return false;
    }
}
