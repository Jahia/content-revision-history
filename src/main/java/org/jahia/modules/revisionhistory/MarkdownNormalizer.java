package org.jahia.modules.revisionhistory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw view output into a stable Markdown snapshot, and hashes it.
 *
 * <p>Pure functions only, no JCR and no rendering: this is where every rule that affects
 * diff quality lives, so it can be unit-tested without a running Jahia.
 *
 * <p>HTML is parsed with jsoup (a real, linear-time HTML5 parser) rather than regular
 * expressions: this removes catastrophic-backtracking (ReDoS) risk on attacker-influenced
 * markup, decodes entities exactly once as part of tokenizing (so text that was inert in the
 * source, e.g. an escaped {@code &lt;script&gt;}, can never become live markup), and gives
 * correct handling of malformed/unclosed tags, nested lists and tables.
 *
 * <p>The important rule is {@link #semanticLineBreaks}: a rendered paragraph is one very long
 * line, so a line-level diff would report "whole paragraph changed" for a one-word fix.
 * Breaking at sentence boundaries at generation time makes a plain line diff read at sentence
 * granularity, which does more for diff quality than any diff-library tuning.
 */
public final class MarkdownNormalizer {

    /**
     * Bumped whenever the markdown views or these rules change in a way that alters output.
     * Stamped on every snapshot so the diff viewer can flag "formatting change" instead of
     * showing spurious churn between snapshots produced by different generators.
     */
    public static final String GENERATOR_VERSION = "6";

    /**
     * Defensive cap on raw view output accepted for normalization. 2,000,000 characters (~2 MB of
     * UTF-16) comfortably covers any realistic rendered page while bounding the worst-case parse
     * cost against an oversized or pathological payload.
     *
     * <p>The justification used to read "this runs on a live render thread for every publish, so
     * partial data beats a failed publish". Neither half is true any more: capture moved to
     * {@code SnapshotCaptureJob}, a background Quartz job that touches neither the publication
     * thread nor the request path, so there is no publish to fail and no latency to trade against.
     *
     * <p>Exceeding it is now a REFUSAL, not a truncation: see {@link MarkdownTooLargeException}.
     * It used to return the first {@code MAX_INPUT_CHARS} characters as though they were the whole
     * document, which is inconsistent with {@link RevisionHistoryConstants#MAX_MARKDOWN_BYTES}
     * (recorded as {@code OVERSIZE}, nothing stored) and with {@code MarkdownDiff.Result}, which
     * flags a clipped comparison. A snapshot missing its tail is indistinguishable from a page that
     * was genuinely shorter, so the truncation could not be noticed by anyone reading the record.
     *
     * <p>Keep this and {@code MAX_MARKDOWN_BYTES} in step. Live capture bounds the HTTP body at
     * {@code MAX_MARKDOWN_BYTES} first, so on that path this cap is unreachable defence in depth;
     * the path where it bites is the backfill script, which concatenates every leaf render of a
     * page with no cap of its own -- and that is the path that writes authoritative history.
     */
    static final int MAX_INPUT_CHARS = 2_000_000;

    /** Default locale used by {@link #normalize(String)} for sentence-boundary detection. */
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    /** URL schemes allowed as link/image targets; everything else is dropped. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

    private static final Pattern URL_SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");

    /**
     * ASCII control characters (C0 plus DEL). Browsers remove these from a URL before parsing
     * its scheme, so the allow-list has to remove them first as well -- see
     * {@link #sanitizeUrl(String)}.
     */
    /** Two or more leading slashes in any mix of "/" and "\\": a protocol-relative URL. */
    private static final Pattern PROTOCOL_RELATIVE = Pattern.compile("^[/\\\\]{2,}");
    private static final Pattern URL_CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F]");

    /** Block-level tags handled generically: separated from surrounding content by a blank line. */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "header", "footer", "aside",
            "dl", "dt", "dd", "figure", "figcaption", "main", "nav", "address");

    /**
     * Embedded media. Each used to fall through to the generic branch, which rendered its children
     * and dropped the element -- so an {@code <iframe src>} pointing at a different video normalised
     * to the same text as before and the change diffed as identical. The record is a Markdown link
     * to the source, which {@code InlineMarkdown} deliberately leaves literal so a source change is
     * visible.
     */
    private static final Set<String> EMBED_TAGS = Set.of("iframe", "video", "audio", "object", "embed");

    /** Row containers the HTML5 parser inserts or authors write; rows are read from these only. */
    private static final Set<String> ROW_GROUP_TAGS = Set.of("thead", "tbody", "tfoot");

    /**
     * Characters {@link InlineMarkdown} reads as syntax: a backslash starts an escape and an
     * asterisk is an emphasis delimiter. A literal one in content must reach it escaped, or the
     * comparison panel shows something other than what the archive says -- {@code C:\Users\bob}
     * displayed as {@code C:Usersbob}. The normaliser used to escape only inside link text.
     */
    private static final Pattern INLINE_SYNTAX = Pattern.compile("[\\\\*]");

    /**
     * Blank lines inside one list item, produced by a block child such as CKEditor's routine
     * {@code <li><p>text</p></li>}. Left in place they separate the bullet from its text, and the
     * list is gone from both the snapshot and the comparison.
     */
    private static final Pattern BLANK_LINES_IN_ITEM = Pattern.compile("\\n{2,}");

    /** The Markdown quote prefix, re-applied per line by {@link #semanticLineBreaks}. */
    private static final String QUOTE_PREFIX = "> ";

    /**
     * Marks a line the normaliser itself made structural -- a heading, a list item, a fence or a
     * line of fenced code -- so that {@link #escapeLineStart} leaves it alone. Private-use, like
     * the link placeholder, and removed before output; it exists only between rendering and the
     * sentence splitter, because lines start wherever the splitter cuts a sentence and only the
     * renderer knows which starts are syntax (issue #44).
     */
    private static final char STRUCTURE = '\uE002';

    /** Content shapes {@link InlineMarkdown} reads as line-level syntax: heading, bullet, ordered item. */
    private static final Pattern LINE_START_HEADING = Pattern.compile("^#");
    private static final Pattern LINE_START_BULLET = Pattern.compile("^- ");
    private static final Pattern LINE_START_ORDERED = Pattern.compile("^(\\d+)\\. ");

    /** A heading the markdown VIEWS emit as text: {@code # title}, {@code ## title}. */
    private static final Pattern VIEW_HEADING = Pattern.compile("(?m)^(#{1,6} )");

    /** Elements whose bodies must never survive into the snapshot (scripts, styles, embedded SVG markup). */
    private static final String DANGEROUS_ELEMENTS = "script, style, noscript, svg, template";

    /**
     * Any Unicode space separator, including U+00A0 and the U+2000 block. Text inside a rich-text
     * fragment reaches us through jsoup's TextNode#text(), which folds these into a plain space as
     * part of normalising; view-emitted text does not go through that, so it has to be folded here
     * or a diff would report a change because an editor typed a non-breaking space.
     */
    private static final Pattern UNICODE_SPACE = Pattern.compile("\\p{Zs}");

    /**
     * Horizontal whitespace at the start of a line: the JSP template's own indentation, never
     * content. Four leading spaces is an indented code block in CommonMark, so leaving it in place
     * would silently reclassify body text as code.
     */
    private static final Pattern LINE_LEADING_SPACE = Pattern.compile("(?<=\\n)[ \\t]+");

    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    /** Only collapses runs of 2+ spaces that follow non-space text, so line-leading list indent survives. */
    private static final Pattern INLINE_SPACE = Pattern.compile("(?<=\\S)[ \\t]{2,}");

    /** Common abbreviations that must not be treated as sentence boundaries. */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "Mr.", "Mrs.", "Ms.", "Dr.", "St.", "Inc.", "Ltd.",
            "e.g.", "i.e.", "etc.", "vs.", "approx.", "No.", "Fig.");

    /**
     * Matches a Markdown link or image span, e.g. {@code [text](url)} or {@code ![alt](src)}.
     * Used to shield these spans from sentence-boundary detection: {@link BreakIterator}
     * treats a bare {@code !} as terminal punctuation even with no following whitespace, which
     * would otherwise split an image's {@code !} from its {@code [alt](src)}.
     */
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[[^\\]]*]\\([^)]*\\)");

    /**
     * Emphasis spans, masked from the sentence splitter alongside links (issue #47). A {@code
     * <em>}/{@code <strong>} that covers more than one sentence would otherwise have its delimiters
     * split across lines -- {@code *First. Second.*} became {@code *First.} / {@code Second} / {@code
     * .} / {@code *} -- and the viewer, finding no closing delimiter on the line, showed literal
     * asterisks. Bold is matched first so its {@code **} never reaches the single-{@code *} pass;
     * the negative lookbehind keeps a literal {@code \*} (escaped by {@link #escapeInlineSyntax}
     * since generator 6) from being read as a delimiter. Both are non-greedy and confined to one
     * line, so they are linear-time.
     */
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*.+?\\*\\*");
    private static final Pattern MARKDOWN_ITALIC = Pattern.compile("(?<!\\\\)\\*[^*\\n]+?(?<!\\\\)\\*");

    private MarkdownNormalizer() {
    }

    /** Full pipeline: HTML-ish view output to normalised Markdown, using the default locale. */
    public static String normalize(String rawViewOutput) {
        return normalize(rawViewOutput, DEFAULT_LOCALE);
    }

    /**
     * Full pipeline with an explicit locale for sentence-boundary detection, so languages that
     * don't rely on a Latin uppercase letter to start a sentence (CJK, Arabic, Hebrew, Thai...)
     * still get one-sentence-per-line output.
     */
    public static String normalize(String rawViewOutput, Locale locale) {
        if (rawViewOutput == null || rawViewOutput.trim().isEmpty()) {
            return "";
        }
        String s = toMarkdown(refuseIfTooLarge(rawViewOutput));
        s = collapseWhitespace(s);
        s = semanticLineBreaks(s, locale);
        return s.trim() + "\n";
    }

    /**
     * Turns a Jahia language code into a {@link Locale} for sentence-boundary detection.
     *
     * <p>Jahia language codes are underscore-separated ({@code en}, {@code pt_BR}), not BCP-47.
     * {@link Locale#forLanguageTag} cannot read them -- it wants a hyphen and, for anything it
     * fails to parse, silently returns the ROOT locale rather than failing. Using it here would
     * therefore look like it worked while reintroducing exactly the defect this method exists to
     * remove, which is why the parse is done by hand.
     *
     * <p>The platform's {@code LanguageCodeConverters#languageCodeToLocale} would also work --
     * {@code org.jahia.utils} IS among the packages exported to modules. It is not used here only
     * because eight lines with no platform coupling are easier to unit-test than a dependency on
     * an impl package, not because it is unavailable. (An earlier version of this comment claimed
     * it was not exported; that was wrong.)
     */
    public static Locale localeFor(String languageCode) {
        if (languageCode == null || languageCode.trim().isEmpty()) {
            return DEFAULT_LOCALE;
        }
        String[] parts = languageCode.trim().split("_", 3);
        if (parts.length == 1) {
            return new Locale(parts[0]);
        }
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(parts[0], parts[1], parts[2]);
    }

    /**
     * @throws MarkdownTooLargeException rather than returning a prefix of the input
     *
     * <p>Checked before parsing, not after, because the cap exists to bound the parse cost as well
     * as the output size.
     */
    private static String refuseIfTooLarge(String input) {
        if (input.length() > MAX_INPUT_CHARS) {
            throw new MarkdownTooLargeException(input.length(), MAX_INPUT_CHARS);
        }
        return input;
    }

    /** Converts the HTML that rich-text properties carry into Markdown equivalents. */
    static String toMarkdown(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select(DANGEROUS_ELEMENTS).remove();
        StringBuilder out = new StringBuilder(html.length());
        for (Node child : doc.body().childNodes()) {
            // A text node at body level is what the markdown VIEWS emitted, where a line separator
            // is structure: jnt_page/markdown writes "# <title>" + line.separator and
            // jnt_content/markdown writes "## <title>" + line.separator. Anything nested inside an
            // element came from a rich-text property, where a newline is only HTML whitespace and
            // must stay collapsible -- otherwise a source-wrapped sentence would be split in two.
            if (child instanceof TextNode) {
                out.append(viewText(((TextNode) child).getWholeText()));
            } else {
                renderNode(child, out, 0);
            }
        }
        return out.toString();
    }

    /**
     * Text emitted by a markdown view, as opposed to text inside a rich-text fragment.
     *
     * <p>Read with {@code getWholeText()} rather than {@code text()}: {@code text()} normalises
     * whitespace, so every line separator the views emit became a space and only structure carried
     * by an HTML block tag survived (ensureBlankLine put that back). A page whose children all
     * render as headings has no block tag anywhere, so the whole page collapsed onto one line --
     * destroying the sentence-level diff granularity that generating Markdown exists to buy.
     *
     * <p>The two normalisations that {@code text()} did usefully are kept: Unicode space
     * separators fold to a plain space, and per-line template indentation is dropped.
     */
    private static String viewText(String wholeText) {
        String spaced = UNICODE_SPACE.matcher(wholeText).replaceAll(" ");
        String escaped = escapeInlineSyntax(LINE_LEADING_SPACE.matcher(spaced).replaceAll(""));
        // The views write headings as text ("# title"), so those line starts ARE syntax. Anything
        // else a view emits at a line start -- a plain string beginning with "- " -- is content.
        return VIEW_HEADING.matcher(escaped).replaceAll(STRUCTURE + "$1");
    }

    /**
     * Escapes what {@link InlineMarkdown} would otherwise read as syntax, in ordinary text.
     *
     * <p>Applied to every text node and to view-emitted text, not only to link text as before, so
     * the two sides of the contract agree: the parser treats every backslash as an escape and every
     * asterisk pair as emphasis, and after this the only backslashes and asterisks it sees are the
     * ones the normaliser itself wrote as syntax. Code blocks are exempt -- {@link #renderPre}
     * fences them and the parser does not look inside a fence.
     */
    static String escapeInlineSyntax(String text) {
        return INLINE_SYNTAX.matcher(text).replaceAll("\\\\$0");
    }

    private static void renderNode(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode) {
            out.append(escapeInlineSyntax(((TextNode) node).text()));
            return;
        }
        if (!(node instanceof Element)) {
            return;
        }
        Element el = (Element) node;
        String tag = el.tagName().toLowerCase(Locale.ROOT);
        if (EMBED_TAGS.contains(tag)) {
            renderEmbed(el, out);
            return;
        }
        switch (tag) {
            case "br":
                out.append('\n');
                return;
            case "hr":
                ensureBlankLine(out);
                out.append("---");
                ensureBlankLine(out);
                return;
            case "blockquote":
                renderBlockquote(el, out, listDepth);
                return;
            case "img":
                renderImage(el, out);
                return;
            case "a":
                renderLink(el, out, listDepth);
                return;
            case "strong":
            case "b":
                renderWrapped(el, out, "**", listDepth);
                return;
            case "em":
            case "i":
                renderWrapped(el, out, "*", listDepth);
                return;
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
                renderHeading(el, out, el.tagName().charAt(1) - '0', listDepth);
                return;
            case "ul":
                renderList(el, out, false, listDepth);
                return;
            case "ol":
                renderList(el, out, true, listDepth);
                return;
            case "pre":
                renderPre(el, out);
                return;
            case "table":
                renderTable(el, out, listDepth);
                return;
            default:
                renderGenericBlockOrInline(el, out, listDepth);
        }
    }

    private static void renderGenericBlockOrInline(Element el, StringBuilder out, int listDepth) {
        boolean block = BLOCK_TAGS.contains(el.tagName().toLowerCase(Locale.ROOT));
        if (block) {
            ensureBlankLine(out);
        }
        renderChildren(el, out, listDepth);
        if (block) {
            ensureBlankLine(out);
        }
    }

    private static void renderChildren(Element el, StringBuilder out, int listDepth) {
        for (Node child : el.childNodes()) {
            renderNode(child, out, listDepth);
        }
    }

    private static void renderHeading(Element el, StringBuilder out, int level, int listDepth) {
        ensureBlankLine(out);
        StringBuilder inner = new StringBuilder();
        renderChildren(el, inner, listDepth);
        out.append(STRUCTURE).append("#".repeat(level)).append(' ').append(inner.toString().trim());
        ensureBlankLine(out);
    }

    private static void renderWrapped(Element el, StringBuilder out, String marker, int listDepth) {
        StringBuilder inner = new StringBuilder();
        renderChildren(el, inner, listDepth);
        String content = inner.toString().trim();
        if (!content.isEmpty()) {
            out.append(marker).append(content).append(marker);
        }
    }

    private static void renderLink(Element el, StringBuilder out, int listDepth) {
        StringBuilder inner = new StringBuilder();
        renderChildren(el, inner, listDepth);
        String text = escapeMarkdownText(inner.toString().trim());
        String href = sanitizeUrl(el.attr("href"));
        if (href == null) {
            out.append(text);
            return;
        }
        out.append('[').append(text).append("](").append(href).append(')');
    }

    private static void renderImage(Element el, StringBuilder out) {
        String alt = escapeMarkdownText(el.attr("alt"));
        String src = sanitizeUrl(el.attr("src"));
        out.append("![").append(alt).append("](").append(src == null ? "" : src).append(')');
    }

    private static void renderPre(Element el, StringBuilder out) {
        ensureBlankLine(out);
        String content = el.wholeText();
        String fence = fenceLongerThanAnyRunIn(content);
        // Every line of the block is structural: a code comment starting with '#' must not be
        // escaped as though it were prose.
        out.append(STRUCTURE).append(fence).append('\n')
                .append(content.replace("\n", "\n" + STRUCTURE).isEmpty() ? "" : STRUCTURE + content.replace("\n", "\n" + STRUCTURE))
                .append('\n').append(STRUCTURE).append(fence);
        ensureBlankLine(out);
    }

    /**
     * Picks a fence that the fenced content cannot close.
     *
     * <p>A {@code <pre>} whose text already contains a triple backtick would otherwise end the
     * block early, and everything after it -- attacker-supplied text that was meant to be inert
     * code -- would be parsed as Markdown structure by any downstream renderer. CommonMark
     * closes a fence only on a run of backticks at least as long as the opening one, so an
     * opening fence one backtick longer than the longest run inside is unbreakable.
     */
    static String fenceLongerThanAnyRunIn(String content) {
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                longestRun = Math.max(longestRun, run);
            } else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, longestRun + 1));
    }

    private static void renderList(Element list, StringBuilder out, boolean ordered, int depth) {
        if (depth == 0) {
            ensureBlankLine(out);
        } else {
            ensureNewline(out);
        }
        String indent = "  ".repeat(depth);
        int counter = ordered ? startNumber(list) : 1;
        for (Element li : list.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            ensureNewline(out);
            out.append(indent).append(STRUCTURE).append(ordered ? (counter++ + ". ") : "- ");
            // Rendered into its own buffer so block children cannot separate the marker from the
            // text: <li><p>first</p></li> is CKEditor's routine output, and rendering it straight
            // into `out` produced "-\n\nfirst" -- a bare hyphen the comparison did not read as a
            // list marker, followed by unindented text. A second paragraph inside the item
            // continues the item on the next line, indented under the marker.
            StringBuilder item = new StringBuilder();
            renderChildren(li, item, depth + 1);
            out.append(BLANK_LINES_IN_ITEM.matcher(item.toString().trim())
                    .replaceAll("\n" + indent + "  "));
        }
        if (depth == 0) {
            ensureBlankLine(out);
        }
    }

    /**
     * @param list an {@code <ol>}
     * @return its {@code start} attribute, or 1 when absent or unusable
     */
    private static int startNumber(Element list) {
        String start = list.attr("start").trim();
        if (start.isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(start));
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /**
     * Quoted text keeps its {@code > } prefix, which {@link #semanticLineBreaks} re-applies to every
     * sentence line of the paragraph. Before this, blockquote was a plain block tag, so moving a
     * sentence into a quotation -- or out of one -- diffed as identical.
     */
    private static void renderBlockquote(Element el, StringBuilder out, int listDepth) {
        ensureBlankLine(out);
        StringBuilder inner = new StringBuilder();
        renderChildren(el, inner, listDepth);
        // Each paragraph of the quotation is its own quoted block, so the sentence splitter sees
        // paragraphs that each start with the prefix rather than one with the prefix in the middle.
        for (String block : BLANK_LINES_IN_ITEM.split(inner.toString().trim())) {
            ensureBlankLine(out);
            for (String line : block.split("\n")) {
                out.append(QUOTE_PREFIX).append(line).append('\n');
            }
        }
        ensureBlankLine(out);
    }

    /**
     * Embedded media as a link to its source. Nothing of the element's own text is emitted; for
     * these elements the children are fallback content, not the thing the visitor saw.
     */
    private static void renderEmbed(Element el, StringBuilder out) {
        String src = sanitizeUrl(embedSource(el));
        if (src != null) {
            out.append("[embed](").append(src).append(')');
        }
    }

    private static String embedSource(Element el) {
        if (el.hasAttr("src")) {
            return el.attr("src");
        }
        if (el.hasAttr("data")) {
            return el.attr("data");
        }
        Element source = el.selectFirst("> source[src]");
        return source == null ? null : source.attr("src");
    }

    private static void renderTable(Element table, StringBuilder out, int listDepth) {
        ensureBlankLine(out);
        Element caption = table.selectFirst("> caption");
        if (caption != null) {
            StringBuilder captionBuf = new StringBuilder();
            renderChildren(caption, captionBuf, listDepth);
            out.append(captionBuf.toString().trim());
            ensureBlankLine(out);
        }
        List<Element> rows = directRows(table);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                ensureNewline(out);
            }
            int headerCells = renderTableRow(rows.get(i), out, listDepth);
            if (headerCells > 0) {
                // A header row is followed by a separator row, so <th> and <td> are distinguishable
                // in the record and a cell promoted to a header is a visible change.
                ensureNewline(out);
                out.append(String.join(" | ", java.util.Collections.nCopies(headerCells, "---")));
            }
        }
        ensureBlankLine(out);
    }

    /**
     * The table's own rows, and only those.
     *
     * <p>{@code table.select("tr")} is a descendant selector: with a table nested in a cell it
     * returned the inner rows as rows of the outer table too, so every inner row was rendered once
     * per enclosing level. Output grew about fourfold per level -- 595 bytes of input at depth 18
     * became 262 KB, and depth 26 exhausted a 512 MB heap in the capture job -- and the inner rows
     * appeared twice in the record. Nested tables now render inside their cell, once.
     */
    private static List<Element> directRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element child : table.children()) {
            String tag = child.tagName().toLowerCase(Locale.ROOT);
            if ("tr".equals(tag)) {
                rows.add(child);
            } else if (ROW_GROUP_TAGS.contains(tag)) {
                for (Element row : child.children()) {
                    if ("tr".equals(row.tagName().toLowerCase(Locale.ROOT))) {
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }

    /**
     * @return the number of cells when every cell is a {@code <th>} (a header row), else 0
     */
    private static int renderTableRow(Element row, StringBuilder out, int listDepth) {
        Elements cells = row.select("> td, > th");
        boolean allHeaders = !cells.isEmpty();
        for (int j = 0; j < cells.size(); j++) {
            if (j > 0) {
                out.append(" | ");
            }
            allHeaders &= "th".equals(cells.get(j).tagName().toLowerCase(Locale.ROOT));
            StringBuilder cellBuf = new StringBuilder();
            renderChildren(cells.get(j), cellBuf, listDepth);
            out.append(cellBuf.toString().trim());
        }
        return allHeaders ? cells.size() : 0;
    }

    /**
     * Allow-lists link/image URL schemes; site-relative paths (no scheme) are always allowed.
     *
     * <p>ASCII control characters are stripped <em>before</em> the scheme is matched, not after.
     * {@code trim()} alone removes only leading and trailing whitespace, so
     * {@code java&#9;script:alert(1)} -- which jsoup decodes to {@code java\tscript:alert(1)} --
     * would fail to match the scheme pattern and fall through to the "no scheme, safe relative
     * URL" branch untouched. Browsers strip tab, CR and LF from a URL before parsing its scheme,
     * so that string is {@code javascript:} to them and must be to the allow-list too.
     */
    private static String sanitizeUrl(String rawHref) {
        if (rawHref == null) {
            return null;
        }
        String href = URL_CONTROL_CHARS.matcher(rawHref).replaceAll("").trim();
        // Reject protocol-relative references in every form browsers accept. Per the
        // WHATWG URL spec a backslash is normalised to a forward slash against an
        // http(s) base, so "\\\\host/x" and "/\\host/x" resolve cross-origin exactly
        // like "//host/x" while matching neither a scheme nor a plain "//" prefix.
        if (href.isEmpty() || PROTOCOL_RELATIVE.matcher(href).find()) {
            return null;
        }
        Matcher m = URL_SCHEME.matcher(href);
        if (!m.find()) {
            return href;
        }
        return ALLOWED_SCHEMES.contains(m.group(1).toLowerCase(Locale.ROOT)) ? href : null;
    }

    /** Prevents link/image text from reintroducing Markdown link, image or emphasis syntax. */
    private static String escapeMarkdownText(String text) {
        return escapeInlineSyntax(text).replace("[", "\\[").replace("]", "\\]");
    }

    private static void ensureBlankLine(StringBuilder sb) {
        if (sb.length() == 0) {
            return;
        }
        while (trailingNewlines(sb) < 2) {
            sb.append('\n');
        }
    }

    private static void ensureNewline(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static int trailingNewlines(StringBuilder sb) {
        int n = 0;
        for (int i = sb.length() - 1; i >= 0 && sb.charAt(i) == '\n' && n < 2; i--) {
            n++;
        }
        return n;
    }

    /** Squeezes runaway whitespace introduced by JSP output without losing paragraph breaks. */
    static String collapseWhitespace(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = INLINE_SPACE.matcher(s).replaceAll(" ");
        s = TRAILING_SPACE.matcher(s).replaceAll("\n");
        return BLANK_RUN.matcher(s).replaceAll("\n\n");
    }

    /** One sentence per line, so a line diff reads as a sentence diff, for the given locale. */
    static String semanticLineBreaks(String text, Locale locale) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (String paragraph : text.split("\n\n", -1)) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            out.append(splitSentencesProtectingLinks(trimmed, locale)).append("\n\n");
        }
        return out.toString();
    }

    /** Shields Markdown link/image spans from the sentence splitter, then restores them. */
    private static String splitSentencesProtectingLinks(String paragraph, Locale locale) {
        if (paragraph.startsWith(QUOTE_PREFIX)) {
            // A quoted paragraph arrives as "> line\n> line". Splitting it into sentences would
            // leave the prefix on the first sentence only, so the quote is split unprefixed and the
            // prefix put back on every line.
            String unquoted = paragraph.replace("\n" + QUOTE_PREFIX, "\n").substring(QUOTE_PREFIX.length());
            String split = splitSentencesProtectingLinks(unquoted, locale);
            StringBuilder quoted = new StringBuilder(split.length() + 16);
            for (String line : split.split("\n")) {
                quoted.append(QUOTE_PREFIX).append(line).append('\n');
            }
            return quoted.toString().trim();
        }
        List<String> spans = new ArrayList<>();
        String masked = maskProtectedSpans(paragraph, spans);
        String split = splitSentences(masked, locale);
        return unmaskProtectedSpans(split, spans);
    }

    /**
     * Replaces link and emphasis spans with placeholders so the sentence splitter treats each as
     * one unbreakable token. Links first, then bold, then italic: an outer span stores the text it
     * already contains (a link placeholder included), so restoring outermost-first puts everything
     * back exactly.
     */
    private static String maskProtectedSpans(String text, List<String> spans) {
        String masked = maskWith(text, MARKDOWN_LINK, spans);
        masked = maskWith(masked, MARKDOWN_BOLD, spans);
        return maskWith(masked, MARKDOWN_ITALIC, spans);
    }

    private static String maskWith(String text, Pattern pattern, List<String> spans) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            spans.add(m.group());
            sb.append(text, last, m.start()).append('\uE000').append(spans.size() - 1).append('\uE000');
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    /**
     * Restores masked spans, highest index first. A span was masked after everything it contains,
     * so it has the higher index; restoring it first reveals any inner placeholder, which a later
     * iteration then restores. A literal replace, so a placeholder's own digits cannot be reparsed.
     */
    private static String unmaskProtectedSpans(String text, List<String> spans) {
        String result = text;
        for (int i = spans.size() - 1; i >= 0; i--) {
            result = result.replace("\uE000" + i + "\uE000", spans.get(i));
        }
        return result;
    }

    private static String splitSentences(String paragraph, Locale locale) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(locale);
        iterator.setText(paragraph);
        StringBuilder sb = new StringBuilder(paragraph.length() + 16);
        int segmentStart = iterator.first();
        int end;
        while ((end = iterator.next()) != BreakIterator.DONE) {
            if (endsWithAbbreviation(paragraph, segmentStart, end)) {
                continue;
            }
            appendTrimmedLine(sb, paragraph, segmentStart, end);
            segmentStart = end;
        }
        if (segmentStart < paragraph.length()) {
            appendTrimmedLine(sb, paragraph, segmentStart, paragraph.length());
        }
        return sb.toString().trim();
    }

    private static void appendTrimmedLine(StringBuilder sb, String paragraph, int start, int end) {
        String segment = paragraph.substring(start, end).trim();
        if (segment.isEmpty()) {
            return;
        }
        // A segment can hold several physical lines (a <br>, a nested list), and each one is a
        // line start the viewer will read.
        String[] lines = segment.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = escapeLineStart(lines[i]);
        }
        sb.append(String.join("\n", lines)).append('\n');
    }

    /**
     * Content that begins a line with what {@link InlineMarkdown} reads as line-level syntax is
     * escaped; a line the renderer marked as structure has its marker removed and is left as is.
     *
     * <p>{@code Done. # not a heading} used to yield the line {@code # not a heading}, which the
     * comparison panel showed as a heading; {@code - not a bullet} became a list item and
     * {@code 2. Not a list} an ordered one. The archive was right and the panel misrepresented it
     * -- the same defect as the inline asterisks of #27, at the other position (issue #44).
     */
    static String escapeLineStart(String line) {
        int firstNonSpace = 0;
        while (firstNonSpace < line.length() && line.charAt(firstNonSpace) == ' ') {
            firstNonSpace++;
        }
        String indent = line.substring(0, firstNonSpace);
        String body = line.substring(firstNonSpace);
        if (body.indexOf(STRUCTURE) >= 0) {
            return indent + body.replace(String.valueOf(STRUCTURE), "");
        }
        if (LINE_START_HEADING.matcher(body).find() || LINE_START_BULLET.matcher(body).find()) {
            return indent + '\\' + body;
        }
        Matcher ordered = LINE_START_ORDERED.matcher(body);
        if (ordered.find()) {
            return indent + ordered.group(1) + "\\. " + body.substring(ordered.end());
        }
        return line;
    }

    /** True when the candidate sentence fragment ends with a known abbreviation, not a real end. */
    private static boolean endsWithAbbreviation(String paragraph, int start, int end) {
        String segment = paragraph.substring(start, end).trim();
        int lastSpace = segment.lastIndexOf(' ');
        String lastToken = lastSpace >= 0 ? segment.substring(lastSpace + 1) : segment;
        return ABBREVIATIONS.contains(lastToken);
    }

    /** Stable content key used for dedupe: identical Markdown must never be stored twice. */
    public static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
