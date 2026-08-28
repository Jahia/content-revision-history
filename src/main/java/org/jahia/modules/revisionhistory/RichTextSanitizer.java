package org.jahia.modules.revisionhistory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

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

    private RichTextSanitizer() {
        // static utility
    }

    private static Safelist buildSafelist() {
        return Safelist.basic()
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
        // Empty base URI: relative links stay relative and are never resolved against some
        // host this code has no business guessing.
        return Jsoup.clean(html, "", SAFELIST, OUTPUT);
    }
}
