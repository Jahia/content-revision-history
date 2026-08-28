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
    public static final String GENERATOR_VERSION = "2";

    /**
     * Defensive cap on raw view output accepted for normalization. This runs on a live render
     * thread for every publish, so an oversized or pathological payload (accidental or
     * malicious) must not be allowed to consume unbounded memory/CPU. 2,000,000 characters
     * (~2 MB of UTF-16) comfortably covers any realistic rendered page while bounding the
     * worst-case parse cost; input beyond this is truncated rather than rejected so a snapshot
     * is still captured (partial data beats a failed publish).
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
            "blockquote", "dl", "dt", "dd", "figure", "figcaption", "main", "nav", "address");

    /** Elements whose bodies must never survive into the snapshot (scripts, styles, embedded SVG markup). */
    private static final String DANGEROUS_ELEMENTS = "script, style, noscript, svg, template";

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
    private static final Pattern LINK_PLACEHOLDER = Pattern.compile("\uE000(\\d+)\uE000");

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
        String s = toMarkdown(capInputSize(rawViewOutput));
        s = collapseWhitespace(s);
        s = semanticLineBreaks(s, locale);
        return s.trim() + "\n";
    }

    private static String capInputSize(String input) {
        return input.length() > MAX_INPUT_CHARS ? input.substring(0, MAX_INPUT_CHARS) : input;
    }

    /** Converts the HTML that rich-text properties carry into Markdown equivalents. */
    static String toMarkdown(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        doc.select(DANGEROUS_ELEMENTS).remove();
        StringBuilder out = new StringBuilder(html.length());
        for (Node child : doc.body().childNodes()) {
            renderNode(child, out, 0);
        }
        return out.toString();
    }

    private static void renderNode(Node node, StringBuilder out, int listDepth) {
        if (node instanceof TextNode) {
            out.append(((TextNode) node).text());
            return;
        }
        if (!(node instanceof Element)) {
            return;
        }
        Element el = (Element) node;
        switch (el.tagName().toLowerCase(Locale.ROOT)) {
            case "br":
                out.append('\n');
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
        out.append("#".repeat(level)).append(' ').append(inner.toString().trim());
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
        out.append(fence).append('\n').append(content).append('\n').append(fence);
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
        int counter = 1;
        for (Element li : list.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            ensureNewline(out);
            out.append(indent).append(ordered ? (counter++ + ". ") : "- ");
            renderChildren(li, out, depth + 1);
        }
        if (depth == 0) {
            ensureBlankLine(out);
        }
    }

    private static void renderTable(Element table, StringBuilder out, int listDepth) {
        ensureBlankLine(out);
        Elements rows = table.select("tr");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                ensureNewline(out);
            }
            renderTableRow(rows.get(i), out, listDepth);
        }
        ensureBlankLine(out);
    }

    private static void renderTableRow(Element row, StringBuilder out, int listDepth) {
        Elements cells = row.select("> td, > th");
        for (int j = 0; j < cells.size(); j++) {
            if (j > 0) {
                out.append(" | ");
            }
            StringBuilder cellBuf = new StringBuilder();
            renderChildren(cells.get(j), cellBuf, listDepth);
            out.append(cellBuf.toString().trim());
        }
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

    /** Prevents link/image text from reintroducing Markdown link/image syntax. */
    private static String escapeMarkdownText(String text) {
        return text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
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
        List<String> links = new ArrayList<>();
        String masked = maskLinks(paragraph, links);
        String split = splitSentences(masked, locale);
        return unmaskLinks(split, links);
    }

    private static String maskLinks(String text, List<String> links) {
        Matcher m = MARKDOWN_LINK.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            links.add(m.group());
            sb.append(text, last, m.start()).append("\uE000").append(links.size() - 1).append("\uE000");
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    private static String unmaskLinks(String text, List<String> links) {
        Matcher m = LINK_PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start()).append(links.get(Integer.parseInt(m.group(1))));
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
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
        String line = paragraph.substring(start, end).trim();
        if (!line.isEmpty()) {
            sb.append(line).append('\n');
        }
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
