package org.jahia.modules.revisionhistory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Turns editor-authored rich text into HTML that is safe to write into a public page.
 *
 * <p>{@code crh:revisionEntry/summary} is declared {@code (string, richtext)}, so it is
 * authored through CKEditor and the stored value is an HTML fragment. Until now the view
 * rendered it through {@code <c:out>}, i.e. as escaped plain text: safe, but it showed
 * visitors literal {@code <p>} tags and threw away every link and emphasis the editor wrote.
 *
 * <p>The fix is not to stop escaping -- that reopens stored XSS on a public page, from a field
 * any contributor can write -- but to sanitise. This uses jsoup, which is <em>already embedded
 * in this bundle</em> for {@link MarkdownNormalizer}, so the safe path costs no new dependency
 * and no new supply chain to track. jsoup parses with the same HTML5 tree-construction rules a
 * browser uses, which is the property that matters: a sanitiser that models the markup
 * differently from the browser rendering it is exactly how filters get bypassed.
 *
 * <p><b>What survives, and why so little.</b> The allow-list is {@link Safelist#basic()}:
 * inline emphasis, links, lists, quotes, code. Deliberately absent:
 * <ul>
 *   <li><b>Headings.</b> The summary renders inside the {@code <dd>} of an entry that already
 *       owns an {@code <h3>}. An editor-supplied {@code <h2>} there would break the page's
 *       heading hierarchy (WCAG 1.3.1) for every assistive-technology user, so dropping them
 *       is an accessibility decision as much as a security one.</li>
 *   <li><b>Images, iframes, objects, styles, event handlers, {@code id}/{@code class}.</b>
 *       Nothing in a "what changed" note needs them, and each is a way to load or position
 *       third-party content on a page that exists to be an authoritative record.</li>
 * </ul>
 *
 * <p>Unknown elements are unwrapped rather than dropped: the text an editor wrote always
 * survives sanitisation, even when its markup does not. Silently deleting content from a
 * change description would be the same class of failure this module exists to prevent.
 */
public final class RichTextSanitizer {

    /**
     * Built once: {@link Safelist} is immutable in use here and its construction is pure, so
     * there is no reason to rebuild the rule set per render.
     */
    private static final Safelist SAFELIST = buildSafelist();

    /**
     * {@code prettyPrint(false)} matters: jsoup's pretty-printer reflows and re-indents the
     * fragment, which inserts whitespace into the rendered output and can visibly change
     * spacing around inline elements. A sanitiser must not restyle its input.
     */
    private static final Document.OutputSettings OUTPUT =
            new Document.OutputSettings().prettyPrint(false);

    /**
     * The base URI relative links are checked against, never emitted.
     *
     * <p>jsoup's protocol check resolves every URL attribute against the document's base URI and
     * removes the attribute when that fails, whatever {@code preserveRelativeLinks} says. With the
     * empty base URI this class used to pass, every relative and fragment {@code href} failed to
     * resolve and was stripped -- {@code <a href="/sites/x/home/policy.html">} became a dead
     * {@code <a>}, the opposite of what the comment beside it promised (issue #26). A reserved
     * {@code .invalid} host makes the check pass for {@code https}; the attribute itself keeps the
     * value the editor wrote, because the safelist preserves relative links.
     */
    private static final String BASE_URI = "https://relative-links.invalid/";

    /**
     * Block-level elements the safelist unwraps. Unwrapping keeps the text and drops the tag, so
     * {@code <div>one</div><div>two</div>} became {@code onetwo} and a pasted table's cells ran
     * together as {@code alphabeta}. A space is put after each before cleaning, so unwrapped blocks
     * stay separate words.
     */
    private static final Set<String> UNWRAPPED_BLOCKS = new HashSet<>(Arrays.asList(
            "div", "section", "article", "header", "footer", "aside", "main", "nav", "address",
            "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "caption", "dl", "dt", "dd", "figure", "figcaption", "hr", "pre"));

    private RichTextSanitizer() {
        // static utility
    }

    private static Safelist buildSafelist() {
        return Safelist.basic()
                // Site-relative and fragment links are legitimate in a revision summary ("see the
                // policy page"). Kept as written; see BASE_URI for why a base is still needed.
                .preserveRelativeLinks(true)
                // basic() already enforces rel=nofollow; noopener/noreferrer additionally stop
                // a link from reaching back through window.opener or leaking the referrer of a
                // page whose whole point is to be cited.
                .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer");
    }

    /**
     * @param html the stored rich-text value; may be null, empty, plain text or HTML
     * @return sanitised HTML safe to emit unescaped, never null
     */
    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Document dirty = Jsoup.parseBodyFragment(html, BASE_URI);
        for (Element block : dirty.body().getAllElements()) {
            if (UNWRAPPED_BLOCKS.contains(block.normalName()) && !SAFELIST.isSafeTag(block.normalName())) {
                block.after(new TextNode(" "));
            }
        }
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings(OUTPUT);
        return clean.body().html().trim();
    }
}
