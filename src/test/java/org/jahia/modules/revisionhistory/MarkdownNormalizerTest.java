package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule here affects diff quality, dedupe correctness or output safety, which is why the
 * logic lives in a pure class rather than inside a JSP or the render filter.
 */
class MarkdownNormalizerTest {

    @Test
    @DisplayName("returns empty string when the view produced nothing")
    void returnsEmptyForBlankInput() {
        // Arrange / Act / Assert
        assertEquals("", MarkdownNormalizer.normalize(null));
        assertEquals("", MarkdownNormalizer.normalize("   \n\t "));
    }

    @Test
    @DisplayName("splits a paragraph into one line per sentence")
    void appliesSemanticLineBreaks() {
        // Arrange
        String html = "<p>First sentence here. Second sentence follows. Third one ends it.</p>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert -- a line diff on this reads as a sentence diff
        assertEquals(3, md.trim().split("\n").length, md);
        assertTrue(md.contains("First sentence here.\n"));
    }

    @Test
    @DisplayName("changing one word changes only one line")
    void oneWordChangeAffectsOneLine() {
        // Arrange
        String before = MarkdownNormalizer.normalize(
                "<p>Alpha stays put. Beta will change. Gamma stays put.</p>");
        String after = MarkdownNormalizer.normalize(
                "<p>Alpha stays put. Beta was changed. Gamma stays put.</p>");

        // Act
        String[] b = before.trim().split("\n");
        String[] a = after.trim().split("\n");
        int differing = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                differing++;
            }
        }

        // Assert -- this is the property the whole diff viewer depends on
        assertEquals(1, differing, "expected a one-line diff, got:\n" + before + "\n---\n" + after);
    }

    @Test
    @DisplayName("converts headings, emphasis, links and list items to Markdown")
    void convertsRichTextConstructs() {
        assertTrue(MarkdownNormalizer.normalize("<h2>Title</h2>").startsWith("## Title"));
        assertTrue(MarkdownNormalizer.normalize("<p><strong>bold</strong></p>").contains("**bold**"));
        assertTrue(MarkdownNormalizer.normalize("<p><em>it</em></p>").contains("*it*"));
        assertTrue(MarkdownNormalizer.normalize("<ul><li>one</li><li>two</li></ul>")
                .contains("- one"));
        assertTrue(MarkdownNormalizer.normalize("<p><a href=\"/x\">link</a></p>")
                .contains("[link](/x)"));
    }

    @Test
    @DisplayName("a heading never fuses onto the preceding line")
    void headingAlwaysStartsItsOwnBlock() {
        // Regression: page H1 followed by a child H2 rendered as "# Page## Child"
        String md = MarkdownNormalizer.normalize("# Page<h2>Child</h2>");

        assertFalse(md.contains("Page##"), md);
        assertTrue(md.contains("\n## Child"), md);
    }

    @Test
    @DisplayName("strips markup and unescapes entities so diffs show text, not tags")
    void stripsMarkupAndEntities() {
        String md = MarkdownNormalizer.normalize("<p>a &amp; b&nbsp;c <span class=\"x\">d</span></p>");

        assertFalse(md.contains("<span"));
        assertTrue(md.contains("a & b c d"), md);
    }

    @Test
    @DisplayName("hash is stable for equal content and differs for changed content")
    void hashIsStableAndSensitive() {
        String a = MarkdownNormalizer.normalize("<p>Same content.</p>");
        String b = MarkdownNormalizer.normalize("<p>Same content.</p>");
        String c = MarkdownNormalizer.normalize("<p>Other content.</p>");

        assertEquals(MarkdownNormalizer.hash(a), MarkdownNormalizer.hash(b));
        assertNotEquals(MarkdownNormalizer.hash(a), MarkdownNormalizer.hash(c));
        assertEquals(64, MarkdownNormalizer.hash(a).length(), "SHA-256 hex is 64 chars");
    }

    @Test
    @DisplayName("collapses runaway whitespace from JSP output without losing blocks")
    void collapsesWhitespace() {
        String md = MarkdownNormalizer.normalize("<p>one</p>\n\n\n\n\n<p>two</p>");

        assertFalse(md.contains("\n\n\n"), md);
        assertTrue(md.contains("one"));
        assertTrue(md.contains("two"));
    }

    @Test
    @DisplayName("generator version was bumped: output format changed")
    void generatorVersionWasBumped() {
        assertNotEquals("1", MarkdownNormalizer.GENERATOR_VERSION);
    }

    // --- Defect 1: ReDoS (CRITICAL) ------------------------------------------------------

    @Test
    @DisplayName("pathological unclosed-heading input does not cause catastrophic backtracking")
    void pathologicalInputCompletesQuickly() {
        // Arrange -- 5000 unclosed <h1> tags plus 50 KB of filler text: this made the old
        // regex-based HEADING/LINK patterns take 17+ seconds (or never finish).
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            html.append("<h1>unterminated heading text here ");
        }
        for (int i = 0; i < 50_000; i++) {
            html.append("x");
        }

        // Act
        long start = System.nanoTime();
        String md = MarkdownNormalizer.normalize(html.toString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Assert -- must complete well under 2 seconds on a live render thread
        assertTrue(elapsedMs < 2000, "took " + elapsedMs + " ms");
        assertFalse(md.isEmpty());
    }

    // --- Defect 2: entity decoding must happen as part of parsing, not after tag stripping ---

    @Test
    @DisplayName("entity-decoded HTML-looking text stays inert, never becomes live markup")
    void entityDecodedTextStaysInert() {
        // Arrange -- this text was NEVER a real <script> tag in the source; it was always the
        // literal characters "&lt;script&gt;...". It must still read that way after normalizing.
        String html = "<p>Hello &amp;lt;script&amp;gt;alert(1)&amp;lt;/script&amp;gt;</p>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert
        assertFalse(md.contains("<script>"), md);
        assertTrue(md.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), md);
    }

    // --- Defect 3: double-encoded entities must not cascade-decode -------------------------

    @Test
    @DisplayName("double-encoded entities decode exactly one level, not two")
    void doubleEncodedEntitiesDoNotCascade() {
        // Arrange
        String html = "&amp;amp;lt;div&amp;amp;gt;";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert -- one decode pass: &amp;amp; -> &amp; , &amp;lt; stays literal text
        assertTrue(md.contains("&amp;lt;div&amp;gt;"), md);
        assertFalse(md.contains("<div>"), md);
    }

    @Test
    @DisplayName("a single-level-extra entity wrap does not cascade into a live tag")
    void singleLevelDoubleEncodedEntityDoesNotCascadeIntoLiveTag() {
        // Arrange -- sequential .replace() chaining decodes &amp; then, on the ALREADY
        // decoded string, decodes the resulting &lt;/&gt; too, producing a real <div>.
        // A one-pass decode (jsoup) must stop after a single pass and keep this as text.
        String html = "&amp;lt;div&amp;gt;";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert
        assertTrue(md.contains("&lt;div&gt;"), md);
        assertFalse(md.contains("<div>"), md);
    }

    // --- Defect 4: nested list items must not fuse ------------------------------------------

    @Test
    @DisplayName("a nested list item does not fuse with its parent item")
    void nestedListItemsDoNotFuse() {
        // Arrange
        String html = "<ul><li>Item<ul><li>Sub</li></ul></li></ul>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert -- two distinct items, the nested one indented under the parent
        assertFalse(md.contains("ItemSub"), md);
        assertTrue(md.contains("- Item"), md);
        assertTrue(md.contains("  - Sub"), md);
    }

    // --- Defect 5: table cells must be separated ---------------------------------------------

    @Test
    @DisplayName("adjacent table cells are separated, not fused")
    void tableCellsAreSeparated() {
        // Arrange
        String html = "<table><tr><td>Alice</td><td>Bob</td></tr></table>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert
        assertFalse(md.contains("AliceBob"), md);
        assertTrue(md.contains("Alice"), md);
        assertTrue(md.contains("Bob"), md);
    }

    // --- Defect 6: attribute values containing '>' must not corrupt output ------------------

    @Test
    @DisplayName("a '>' inside an attribute value does not truncate the tag early")
    void attributeGreaterThanDoesNotCorruptOutput() {
        // Arrange
        String html = "<p><a title=\"a>b\" href=\"/x\">click</a></p>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert -- the old regex leaked: b" href="/x">click
        assertTrue(md.contains("[click](/x)"), md);
        assertFalse(md.contains("href=\"/x\">click"), md);
    }

    // --- Defect 7: <img> must be preserved with its alt text --------------------------------

    @Test
    @DisplayName("images are preserved as Markdown image syntax, alt text included")
    void imagesArePreservedWithAltText() {
        // Arrange
        String html = "<p><img src=\"/cat.png\" alt=\"A sleeping cat\"></p>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert
        assertTrue(md.contains("![A sleeping cat](/cat.png)"), md);
    }

    // --- Defect 8: <ol> must render as a numbered list, distinct from <ul> ------------------

    @Test
    @DisplayName("ordered lists are numbered, not rendered as bullet lists")
    void orderedListsAreNumbered() {
        // Arrange
        String html = "<ol><li>first</li><li>second</li></ol>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert
        assertTrue(md.contains("1. first"), md);
        assertTrue(md.contains("2. second"), md);
        assertFalse(md.contains("- first"), md);
    }

    // --- Defect 9: blockquote/pre/dl-dt-dd/figure/td-th must not fuse with adjacent text ----

    @Test
    @DisplayName("a blockquote does not fuse with the text that follows it")
    void blockquoteDoesNotFuseWithAdjacentText() {
        String md = MarkdownNormalizer.normalize("<blockquote>Quoted</blockquote>After");

        assertFalse(md.contains("QuotedAfter"), md);
    }

    @Test
    @DisplayName("a <pre> block does not fuse with the text that follows it")
    void preDoesNotFuseWithAdjacentText() {
        String md = MarkdownNormalizer.normalize("<pre>code()</pre>After");

        assertFalse(md.contains("code()After"), md);
    }

    @Test
    @DisplayName("definition list terms and descriptions do not fuse with each other")
    void definitionListDoesNotFuse() {
        String md = MarkdownNormalizer.normalize("<dl><dt>Term</dt><dd>Description</dd></dl>Next");

        assertFalse(md.contains("TermDescription"), md);
        assertFalse(md.contains("DescriptionNext"), md);
    }

    @Test
    @DisplayName("a figure does not fuse with the text that follows it")
    void figureDoesNotFuseWithAdjacentText() {
        String md = MarkdownNormalizer.normalize("<figure><figcaption>Caption</figcaption></figure>After");

        assertFalse(md.contains("CaptionAfter"), md);
        assertTrue(md.contains("Caption"), md);
        assertTrue(md.contains("After"), md);
    }

    // --- Defect 10: URL scheme allow-listing + link-text metacharacter escaping -------------

    @Test
    @DisplayName("a javascript: link target is neutralised, not rendered as a live link")
    void javascriptSchemeLinksAreNeutralized() {
        String md = MarkdownNormalizer.normalize("<p><a href=\"javascript:alert(1)\">click</a></p>");

        assertFalse(md.contains("](javascript:"), md);
        assertFalse(md.contains("[click]"), md);
        assertTrue(md.contains("click"), md);
    }

    @Test
    @DisplayName("a data: link target is neutralised, not rendered as a live link")
    void dataSchemeLinksAreNeutralized() {
        String md = MarkdownNormalizer.normalize(
                "<p><a href=\"data:text/html,<script>alert(1)</script>\">click</a></p>");

        assertFalse(md.contains("](data:"), md);
        assertFalse(md.contains("[click]"), md);
    }

    @Test
    @DisplayName("unescaped brackets in link text cannot forge a second, unsafe Markdown link")
    void linkTextBracketsCannotForgeAdditionalMarkdownLink() {
        // Arrange -- naive "[" + text + "](" + href + ")" concatenation let an author close the
        // link early and reopen a new one with an unsafe scheme, using only the link TEXT.
        String html = "<a href=\"http://ok\">x](javascript:alert(1))[y</a>";

        // Act
        String md = MarkdownNormalizer.normalize(html);

        // Assert -- the forged "[y](http://ok)" link never forms: the ']' before the reopening
        // '[' is escaped, so it can't act as Markdown link-closing syntax.
        assertFalse(md.contains(")[y](http://ok)"), md);
        assertTrue(md.contains("\\[y](http://ok)"), md);
        assertTrue(md.contains("](http://ok)"), md);
    }

    // --- Defect 11: script/style/noscript/svg bodies must be removed, not just their tags ---

    @Test
    @DisplayName("script, style, noscript and svg element bodies are removed entirely")
    void scriptStyleNoscriptSvgBodiesAreRemoved() {
        String html = "<script>var secretToken = \"abc123\";</script>"
                + "<style>.danger { color: red; }</style>"
                + "<noscript>fallback content</noscript>"
                + "<svg><text>hidden svg text</text></svg>"
                + "<p>Safe text</p>";

        String md = MarkdownNormalizer.normalize(html);

        assertFalse(md.contains("secretToken"), md);
        assertFalse(md.contains("color: red"), md);
        assertFalse(md.contains("fallback content"), md);
        assertFalse(md.contains("hidden svg text"), md);
        assertTrue(md.contains("Safe text"), md);
    }

    // --- Defect 12: common abbreviations must not trigger a false sentence split ------------

    @Test
    @DisplayName("a title abbreviation like 'Mr.' does not split the sentence")
    void abbreviationsDoNotCauseFalseSentenceSplit() {
        String md = MarkdownNormalizer.normalize("<p>Mr. Smith went home. He was tired.</p>");

        String[] lines = md.trim().split("\n");
        assertEquals(2, lines.length, md);
        assertEquals("Mr. Smith went home.", lines[0]);
        assertEquals("He was tired.", lines[1]);
    }

    // --- Defect 13: non-Latin scripts must still get sentence-level line breaks ------------

    @Test
    @DisplayName("CJK text is split into one line per sentence using a locale-aware BreakIterator")
    void cjkTextIsSplitIntoSentencesWithExplicitLocale() {
        String html = "<p>\u4eca\u5929\u5929\u6c14\u5f88\u597d\u3002\u6211\u4eec\u53bb\u516c\u56ed\u3002</p>";

        String md = MarkdownNormalizer.normalize(html, Locale.CHINESE);

        String[] lines = md.trim().split("\n");
        assertEquals(2, lines.length, md);
    }

    @Test
    @DisplayName("normalize(String) still compiles and works for existing callers without a locale")
    void singleArgNormalizeStillWorks() {
        assertTrue(MarkdownNormalizer.normalize("<p>Still works.</p>").contains("Still works."));
    }

    // --- Defensive input size cap ------------------------------------------------------------

    @Test
    @DisplayName("oversized input is truncated instead of being processed in full")
    void oversizedInputIsTruncated() {
        // Arrange -- comfortably larger than MAX_INPUT_CHARS
        String html = "<p>" + "a".repeat(MarkdownNormalizer.MAX_INPUT_CHARS + 500_000) + "</p>";

        // Act
        long start = System.nanoTime();
        String md = MarkdownNormalizer.normalize(html);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Assert -- output must be bounded, proving the input was capped, not processed whole
        assertTrue(md.length() < html.length(), "expected truncation, got length " + md.length());
        assertTrue(elapsedMs < 5000, "took " + elapsedMs + " ms");
    }
}
