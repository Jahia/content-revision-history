package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule here affects diff quality or dedupe correctness, which is why the logic lives in
 * a pure class rather than inside a JSP or the render filter.
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
}
