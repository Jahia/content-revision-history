package org.jahia.modules.revisionhistory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw view output into a stable Markdown snapshot, and hashes it.
 *
 * <p>Pure functions only, no JCR and no rendering: this is where every rule that affects
 * diff quality lives, so it can be unit-tested without a running Jahia.
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
    public static final String GENERATOR_VERSION = "1";

    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");
    private static final Pattern LINK = Pattern.compile("(?is)<a[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>");
    private static final Pattern STRONG = Pattern.compile("(?is)<(strong|b)[^>]*>(.*?)</\\1>");
    private static final Pattern EMPHASIS = Pattern.compile("(?is)<(em|i)[^>]*>(.*?)</\\1>");
    private static final Pattern LIST_ITEM = Pattern.compile("(?is)<li[^>]*>(.*?)</li>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|section|article|tr|ul|ol|table|h[1-6])>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    private static final Pattern INLINE_SPACE = Pattern.compile("[ \\t]{2,}");
    /** Sentence boundary: .!? followed by whitespace and an uppercase-ish start. */
    private static final Pattern SENTENCE_END = Pattern.compile("([.!?])\\s+(?=[\\p{Lu}\\[`*#])");
    /** A heading must start its own block, or it fuses onto the previous line. */
    private static final Pattern GLUED_HEADING = Pattern.compile("(?m)(?<=[^\\s#])(#{1,6}\\s)");

    private MarkdownNormalizer() {
    }

    /** Full pipeline: HTML-ish view output to normalised Markdown. */
    public static String normalize(String rawViewOutput) {
        if (rawViewOutput == null || rawViewOutput.trim().isEmpty()) {
            return "";
        }
        String s = toMarkdown(rawViewOutput);
        s = GLUED_HEADING.matcher(s).replaceAll("\n\n$1");
        s = collapseWhitespace(s);
        s = semanticLineBreaks(s);
        return s.trim() + "\n";
    }

    /** Converts the HTML that rich-text properties carry into Markdown equivalents. */
    static String toMarkdown(String html) {
        String s = html;
        s = replaceAll(HEADING, s, m -> repeat("#", Integer.parseInt(m.group(1))) + " " + m.group(2).trim() + "\n\n");
        s = replaceAll(LINK, s, m -> "[" + m.group(2).trim() + "](" + m.group(1) + ")");
        s = replaceAll(STRONG, s, m -> "**" + m.group(2).trim() + "**");
        s = replaceAll(EMPHASIS, s, m -> "*" + m.group(2).trim() + "*");
        s = replaceAll(LIST_ITEM, s, m -> "- " + m.group(1).trim() + "\n");
        s = LINE_BREAK.matcher(s).replaceAll("\n");
        s = BLOCK_END.matcher(s).replaceAll("\n\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        return unescapeEntities(s);
    }

    /** Squeezes runaway whitespace introduced by JSP output without losing paragraph breaks. */
    static String collapseWhitespace(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = INLINE_SPACE.matcher(s).replaceAll(" ");
        s = TRAILING_SPACE.matcher(s).replaceAll("\n");
        return BLANK_RUN.matcher(s).replaceAll("\n\n");
    }

    /** One sentence per line, so a line diff reads as a sentence diff. */
    static String semanticLineBreaks(String text) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        for (String paragraph : text.split("\n\n", -1)) {
            if (paragraph.trim().isEmpty()) {
                continue;
            }
            out.append(SENTENCE_END.matcher(paragraph.trim()).replaceAll("$1\n")).append("\n\n");
        }
        return out.toString();
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

    private static String unescapeEntities(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern pattern, String input, Replacer replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start()).append(replacer.apply(m));
            last = m.end();
        }
        return sb.append(input.substring(last)).toString();
    }
}
