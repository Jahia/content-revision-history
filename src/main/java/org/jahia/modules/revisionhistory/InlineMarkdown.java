package org.jahia.modules.revisionhistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Link/image schemes that may reach the page as an href. Kept in step with
     * {@code MarkdownNormalizer.ALLOWED_SCHEMES}: the normaliser already drops the rest at capture,
     * so this is defence in depth on the read side -- a stored snapshot that somehow carried a
     * {@code javascript:} target must still never be rendered as one.
     */
    private static final java.util.Set<String> ALLOWED_SCHEMES =
            new java.util.HashSet<>(java.util.Arrays.asList("http", "https", "mailto"));

    /** A URL scheme prefix, e.g. the {@code https} in {@code https://x}. */
    private static final Pattern LINK_SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");

    /** Two or more leading slashes or backslashes: a protocol-relative reference, always rejected. */
    private static final Pattern PROTOCOL_RELATIVE = Pattern.compile("^[/\\\\]{2,}");

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
        private final String href;

        Piece(String text, boolean bold, boolean italic, int rawStart, int rawEnd, boolean changed,
              String href) {
            this.text = text;
            this.bold = bold;
            this.italic = italic;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.changed = changed;
            this.href = href;
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

        /**
         * @return true when this run is a link, so the view wraps it in an anchor
         *
         * <p>A link is one atomic piece: its whole {@code [text](href)} span shares a single
         * changed flag, which is set when ANY character of the span -- the text OR the href --
         * changed. That is what keeps a destination-only change from hiding, now that the href is
         * no longer shown literally.
         */
        public boolean isLink() {
            return href != null;
        }

        /**
         * @return the sanitised, allow-listed link target, or null when this run is not a link
         *
         * <p>Only {@code http}, {@code https}, {@code mailto} and scheme-less (site-relative) URLs
         * survive; anything else leaves the span rendered as its literal Markdown instead, so a
         * {@code javascript:} target can never reach the page as an href.
         */
        public String getHref() {
            return href;
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
        private final int quoteDepth;
        private final boolean horizontalRule;
        private final String listMarker;
        private final int listDepth;
        private final List<Piece> pieces;

        Parsed(int headingLevel, int quoteDepth, boolean horizontalRule, String listMarker,
               int listDepth, List<Piece> pieces) {
            this.headingLevel = headingLevel;
            this.quoteDepth = quoteDepth;
            this.horizontalRule = horizontalRule;
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

        /**
         * @return the number of {@code > } quote levels this line sits under, 0 when it is not
         * quoted
         *
         * <p>{@link MarkdownNormalizer} re-applies the {@code > } prefix to every line of a
         * quotation, and nests it once per enclosing {@code <blockquote>}, so the count is the
         * nesting depth. The prefixes are removed from {@link #getPieces()}; what remains is the
         * quoted line's own shape, which is why a heading or list inside a quote still reports its
         * heading level or list marker.
         */
        public int getQuoteDepth() {
            return quoteDepth;
        }

        /**
         * @return true when the whole line is a horizontal rule ({@code ---}) and carries no text
         *
         * <p>{@link #getPieces()} is empty for a rule, so the view renders a divider and nothing
         * else. A table separator row ({@code --- | ---}) is deliberately NOT a rule -- it is table
         * structure, told apart by its pipes.
         */
        public boolean isHorizontalRule() {
            return horizontalRule;
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
            return new Parsed(0, 0, false, null, 0, new ArrayList<Piece>());
        }
        boolean[] changedByOffset = changedOffsets(raw, segments);

        int i = 0;
        // Blockquote first: MarkdownNormalizer writes "> " per quote level, re-applied to every
        // line and nested once per enclosing <blockquote>. Strip and count the prefixes; whatever
        // remains is parsed as an ordinary line, so a quoted heading or list keeps its own shape.
        int quoteDepth = 0;
        while (i + 1 < raw.length() && raw.charAt(i) == '>' && raw.charAt(i + 1) == ' ') {
            quoteDepth++;
            i += 2;
        }

        // Horizontal rule: the normalizer emits exactly "---" for <hr>. Require the remainder to be
        // those three dashes and nothing else, so a table separator row ("--- | ---") -- which has
        // pipes -- is left to the table path rather than swallowed here.
        if (raw.length() - i == 3 && raw.charAt(i) == '-' && raw.charAt(i + 1) == '-'
                && raw.charAt(i + 2) == '-') {
            return new Parsed(0, quoteDepth, true, null, 0, new ArrayList<Piece>());
        }

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

        return new Parsed(headingLevel, quoteDepth, false, listMarker, listDepth,
                run(raw, i, changedByOffset));
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
     * <p>Two passes rather than one, deliberately. The first pass decides what is visible, where
     * each visible character came from, and whether it belongs to a link; the second groups
     * adjacent characters that share every mark. Neither needs to know about the other.
     *
     * <p>A link is the one construct emitted as a unit: {@link #emitLinkIfPresent} consumes the
     * whole {@code [text](href)} span, appends only the text, tags every character with the href,
     * and -- when any character of the span changed -- forces them all changed, so the second pass
     * yields a single link run that highlights even when only the destination moved.
     */
    private static List<Piece> run(String raw, int from, boolean[] changedByOffset) {
        StringBuilder visible = new StringBuilder();
        List<int[]> spans = new ArrayList<>();
        List<Boolean> bolds = new ArrayList<>();
        List<Boolean> italics = new ArrayList<>();
        List<String> hrefs = new ArrayList<>();

        boolean bold = false;
        boolean italic = false;
        int i = from;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                // MarkdownNormalizer escapes '\', '*', '[' and ']' on the way in. Showing the
                // escape is showing its own bookkeeping.
                visible.append(raw.charAt(i + 1));
                spans.add(new int[]{i, i + 2});
                bolds.add(bold);
                italics.add(italic);
                hrefs.add(null);
                i += 2;
                continue;
            }
            if (c == '[' && (i == from || raw.charAt(i - 1) != '!')) {
                // A '[' not preceded by '!' may open a link; '!' guards an image, left literal.
                int after = emitLinkIfPresent(raw, i, bold, italic, changedByOffset,
                        visible, spans, bolds, italics, hrefs);
                if (after > i) {
                    i = after;
                    continue;
                }
                // Not a well-formed or allow-listed link: fall through and treat '[' as content.
            }
            if (raw.startsWith(BOLD, i) && (bold || closesLater(raw, i, BOLD))) {
                bold = !bold;
                i += BOLD.length();
            } else if (c == ITALIC && !raw.startsWith(BOLD, i)
                    && (italic || italicClosesLater(raw, i))) {
                // A single asterisk only: the first half of an unmatched "**" is content, not an
                // italic opener, or "2 ** 8" would lose both asterisks to an empty italic span.
                italic = !italic;
                i++;
            } else {
                visible.append(c);
                spans.add(new int[]{i, i + 1});
                bolds.add(bold);
                italics.add(italic);
                hrefs.add(null);
                i++;
            }
        }

        List<Piece> pieces = new ArrayList<>();
        int k = 0;
        while (k < visible.length()) {
            boolean runBold = bolds.get(k);
            boolean runItalic = italics.get(k);
            boolean runChanged = changedAt(changedByOffset, spans.get(k)[0]);
            String runHref = hrefs.get(k);
            int start = k;
            while (k < visible.length()
                    && bolds.get(k) == runBold
                    && italics.get(k) == runItalic
                    && changedAt(changedByOffset, spans.get(k)[0]) == runChanged
                    && Objects.equals(hrefs.get(k), runHref)) {
                k++;
            }
            pieces.add(new Piece(visible.substring(start, k), runBold, runItalic,
                    spans.get(start)[0], spans.get(k - 1)[1], runChanged, runHref));
        }
        return pieces;
    }

    /**
     * If a valid, allow-listed link opens at {@code open}, appends its visible text (escapes
     * resolved, each character tracked back to the raw line and tagged with the href) and returns
     * the offset just past the closing {@code ')'}. Otherwise appends nothing and returns
     * {@code open}, so the caller renders the {@code '['} as content.
     */
    private static int emitLinkIfPresent(String raw, int open, boolean bold, boolean italic,
            boolean[] changedByOffset, StringBuilder visible, List<int[]> spans,
            List<Boolean> bolds, List<Boolean> italics, List<String> hrefs) {
        int[] span = matchLink(raw, open);
        if (span == null) {
            return open;
        }
        int close = span[0];
        int paren = span[1];
        String href = raw.substring(close + 2, paren);
        if (!hrefAllowed(href)) {
            return open;
        }
        // One changed flag for the whole span: a destination-only edit lands on the href, which is
        // not shown, so without this the link would render as unchanged.
        boolean spanChanged = anyChanged(changedByOffset, open, paren + 1);
        int t = open + 1;
        while (t < close) {
            int rawStart = t;
            char vis;
            if (raw.charAt(t) == '\\' && t + 1 < close) {
                vis = raw.charAt(t + 1);
                t += 2;
            } else {
                vis = raw.charAt(t);
                t += 1;
            }
            if (spanChanged) {
                changedByOffset[rawStart] = true;
            }
            visible.append(vis);
            spans.add(new int[]{rawStart, t});
            bolds.add(bold);
            italics.add(italic);
            hrefs.add(href);
        }
        return paren + 1;
    }

    /**
     * @return {@code {closeBracket, closeParen}} for a well-formed {@code [text](href)} opening at
     * {@code open}, or null. The text must be non-empty and hold no nested {@code '['}; the
     * {@code ']'} must be immediately followed by {@code '('}; a {@code ')'} must follow. Escapes
     * inside the text are skipped so a {@code \]} does not close it early.
     */
    private static int[] matchLink(String raw, int open) {
        int close = -1;
        for (int j = open + 1; j < raw.length(); j++) {
            char c = raw.charAt(j);
            if (c == '\\') {
                j++;
                continue;
            }
            if (c == '[') {
                return null;
            }
            if (c == ']') {
                close = j;
                break;
            }
        }
        if (close < 0 || close == open + 1) {
            return null;
        }
        if (close + 1 >= raw.length() || raw.charAt(close + 1) != '(') {
            return null;
        }
        int paren = raw.indexOf(')', close + 2);
        if (paren < 0) {
            return null;
        }
        return new int[]{close, paren};
    }

    /** Mirrors {@code MarkdownNormalizer.sanitizeUrl}'s allow-list on the read side. */
    private static boolean hrefAllowed(String href) {
        if (href.isEmpty() || PROTOCOL_RELATIVE.matcher(href).find()) {
            return false;
        }
        Matcher m = LINK_SCHEME.matcher(href);
        if (!m.find()) {
            return true;
        }
        return ALLOWED_SCHEMES.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    private static boolean anyChanged(boolean[] changedByOffset, int start, int end) {
        for (int k = Math.max(0, start); k < end && k < changedByOffset.length; k++) {
            if (changedByOffset[k]) {
                return true;
            }
        }
        return false;
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
