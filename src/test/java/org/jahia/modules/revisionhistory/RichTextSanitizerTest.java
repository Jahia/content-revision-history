package org.jahia.modules.revisionhistory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the rich-text sanitiser.
 *
 * <p>{@code summary} is writable by any site contributor and is rendered unescaped on a public
 * page, so this class is the single control standing between the two. The tests are therefore
 * written as attacks first and features second.
 */
class RichTextSanitizerTest {

    // ------------------------------------------------------------------ what must not survive

    @Test
    @DisplayName("removes script elements and their contents")
    void removesScripts() {
        String cleaned = RichTextSanitizer.sanitize("<p>Fine</p><script>alert(1)</script>");

        assertFalse(cleaned.contains("<script"));
        assertFalse(cleaned.contains("alert(1)"), "script bodies must not survive as text either");
        assertTrue(cleaned.contains("Fine"));
    }

    @Test
    @DisplayName("removes inline event handlers")
    void removesEventHandlers() {
        String cleaned = RichTextSanitizer.sanitize("<p onmouseover=\"steal()\">Hover</p>");

        assertFalse(cleaned.toLowerCase().contains("onmouseover"));
        assertTrue(cleaned.contains("Hover"));
    }

    @Test
    @DisplayName("removes javascript: and data: URLs while keeping the link text")
    void removesDangerousUrlSchemes() {
        String javascriptHref = RichTextSanitizer.sanitize("<a href=\"javascript:alert(1)\">Click</a>");
        assertFalse(javascriptHref.toLowerCase().contains("javascript:"));
        assertTrue(javascriptHref.contains("Click"), "the words an editor wrote must survive");

        String dataHref = RichTextSanitizer.sanitize(
                "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">Doc</a>");
        assertFalse(dataHref.toLowerCase().contains("data:"));
        assertTrue(dataHref.contains("Doc"));
    }

    @Test
    @DisplayName("removes images, iframes and objects")
    void removesEmbeddedContent() {
        String cleaned = RichTextSanitizer.sanitize(
                "<p>a</p><img src=\"x\" onerror=\"alert(1)\"><iframe src=\"//evil\"></iframe>"
                        + "<object data=\"x\"></object>");

        assertFalse(cleaned.contains("<img"));
        assertFalse(cleaned.contains("<iframe"));
        assertFalse(cleaned.contains("<object"));
        assertFalse(cleaned.toLowerCase().contains("onerror"));
    }

    @Test
    @DisplayName("removes style attributes and style elements")
    void removesStyling() {
        String cleaned = RichTextSanitizer.sanitize(
                "<style>body{display:none}</style><p style=\"position:fixed;top:0\">x</p>");

        assertFalse(cleaned.contains("<style"));
        assertFalse(cleaned.contains("position:fixed"),
                "an editor must not be able to position content over the rest of the page");
    }

    @Test
    @DisplayName("unwraps headings, keeping their text, so the page heading order stays intact")
    void unwrapsHeadings() {
        // The summary renders inside an entry that already owns an <h3>; an editor-supplied
        // <h2> there would break the document outline for assistive technology (SC 1.3.1).
        String cleaned = RichTextSanitizer.sanitize("<h2>Important</h2>");

        assertFalse(cleaned.contains("<h2"));
        assertTrue(cleaned.contains("Important"), "the text must not be dropped with the tag");
    }

    @Test
    @DisplayName("survives malformed and deliberately confusing markup")
    void survivesMalformedMarkup() {
        // Mismatched and nested-broken tags: the point is that jsoup parses these the way a
        // browser does, so there is no gap between what is filtered and what would be rendered.
        String cleaned = RichTextSanitizer.sanitize(
                "<p><b>bold<i>both</p></b>trailing<scr<script>ipt>alert(1)</script>");

        // No script ELEMENT survives, and the stray ">" is escaped rather than closing a tag.
        assertFalse(cleaned.toLowerCase().contains("<script"));
        assertFalse(cleaned.contains("ipt>"), "a raw > must not survive as markup");
        assertTrue(cleaned.contains("ipt&gt;"));
        // The characters "alert(1)" DO survive -- as inert, escaped text inside an <i>. That is
        // the correct outcome and not a leak: the sanitiser's job is to guarantee nothing
        // executes, not to censor strings. Asserting their absence would be asserting the wrong
        // property, and would fail the day an editor legitimately writes about a script.
        assertTrue(cleaned.contains("alert(1)"));
    }

    // ------------------------------------------------------------------ what must survive

    @Test
    @DisplayName("keeps inline emphasis and lists")
    void keepsBasicFormatting() {
        String cleaned = RichTextSanitizer.sanitize(
                "<p>Clarified <strong>scope</strong> and <em>wording</em></p>"
                        + "<ul><li>one</li><li>two</li></ul>");

        assertTrue(cleaned.contains("<strong>scope</strong>"));
        assertTrue(cleaned.contains("<em>wording</em>"));
        assertTrue(cleaned.contains("<li>one</li>"));
    }

    @Test
    @DisplayName("keeps http and https links and hardens their rel attribute")
    void keepsSafeLinksWithHardenedRel() {
        String cleaned = RichTextSanitizer.sanitize("<a href=\"https://example.com\">docs</a>");

        assertTrue(cleaned.contains("href=\"https://example.com\""));
        assertTrue(cleaned.contains("nofollow"));
        assertTrue(cleaned.contains("noopener"));
        assertTrue(cleaned.contains("noreferrer"));
    }

    @Test
    @DisplayName("passes plain text through unchanged apart from escaping")
    void passesPlainTextThrough() {
        // Entries authored before rich text was rendered hold bare text; they must still read
        // correctly rather than being mangled by a sanitiser that assumes HTML.
        assertEquals("Updated section 3", RichTextSanitizer.sanitize("Updated section 3"));
        // And a stray angle bracket must be escaped, not treated as the start of an element.
        assertEquals("a &lt; b", RichTextSanitizer.sanitize("a < b"));
    }

    @Test
    @DisplayName("returns an empty string for null or empty input")
    void handlesNullAndEmpty() {
        assertEquals("", RichTextSanitizer.sanitize(null));
        assertEquals("", RichTextSanitizer.sanitize(""));
    }

    @Test
    @DisplayName("does not reformat or re-indent the fragment it is given")
    void doesNotReformat() {
        // jsoup's pretty-printer would insert newlines and indentation here, changing the
        // rendered spacing around inline elements. A sanitiser must not restyle its input.
        String input = "<p>one</p><p>two</p>";
        assertEquals(input, RichTextSanitizer.sanitize(input));
    }

    // ------------------------------------------------------------------ #26

    @Test
    @DisplayName("#26: a site-relative link keeps its href, as written")
    void keepsRelativeLinks() {
        // "Updated the policy page" with a dead <a> around "policy page" is what every summary
        // linking within the site rendered as: jsoup resolved the href against the empty base URI,
        // failed, and removed the attribute.
        String cleaned = RichTextSanitizer.sanitize(
                "Updated the <a href=\"/sites/mysite/home/policy.html\">policy page</a>.");

        assertTrue(cleaned.contains("href=\"/sites/mysite/home/policy.html\""), cleaned);
        assertFalse(cleaned.contains("relative-links.invalid"), "the check base must never be emitted");
    }

    @Test
    @DisplayName("#26: fragment and file links survive too; javascript: still does not")
    void keepsFragmentAndFileLinksButNotScripts() {
        assertTrue(RichTextSanitizer.sanitize("<a href=\"#section-3\">3</a>").contains("href=\"#section-3\""));
        assertTrue(RichTextSanitizer.sanitize("<a href=\"/files/live/sites/x/a.pdf\">pdf</a>")
                .contains("href=\"/files/live/sites/x/a.pdf\""));
        assertTrue(RichTextSanitizer.sanitize("<a href=\"mailto:legal@example.com\">mail</a>")
                .contains("href=\"mailto:legal@example.com\""));
        String script = RichTextSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>");
        assertFalse(script.contains("javascript"), script);
    }

    @Test
    @DisplayName("#26: unwrapped blocks stay separate words instead of running together")
    void separatesUnwrappedBlocks() {
        assertEquals("one two", RichTextSanitizer.sanitize("<div>one</div><div>two</div>"));
        String table = RichTextSanitizer.sanitize(
                "<table><tr><td>alpha</td><td>beta</td></tr><tr><td>gamma</td></tr></table>");
        assertTrue(table.matches("alpha\\s+beta\\s+gamma"), table);
        // Allowed blocks are untouched, so the no-reformat guarantee above still holds.
        assertEquals("<p>one</p><p>two</p>", RichTextSanitizer.sanitize("<p>one</p><p>two</p>"));
    }
}
