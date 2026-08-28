/*
 * Upgrades a rendered comparison into a popup.
 *
 * WHY THE MARKUP DOES NOT CARRY popover="auto".
 * A browser hides any element with a `popover` attribute until something shows it, and a popover
 * cannot be opened declaratively on page load. Shipping the attribute in the HTML would therefore
 * make the comparison INVISIBLE to every visitor without JavaScript, rather than merely
 * un-popped. Adding it here instead means the server-rendered panel is an ordinary inline section
 * by default, and only becomes a popup where this script actually runs.
 *
 * WHY IT WAITS FOR THE DOM.
 * Jahia injects a module's JavaScript into <head> (template:addResources), so this file executes
 * BEFORE the body is parsed. Running immediately found zero panels and silently did nothing: the
 * comparison rendered inline and looked exactly like the no-JavaScript fallback, with no error
 * anywhere. Hence the readyState guard rather than a bare call.
 *
 * WHAT THIS SCRIPT IS ALLOWED TO DO.
 * Toggle the visibility of markup the server already produced. Nothing more. It never reads,
 * parses, fetches or writes snapshot content -- the comparison is built server-side and arrives
 * fully escaped -- so the property that mattered about having no client code still holds.
 *
 * Escape, click-outside dismissal and focus return to the invoker all come from popover="auto"
 * itself; none of them is reimplemented here.
 */
(function () {
    'use strict';

    function upgrade() {
        var panels = document.querySelectorAll('.crh-diff-panel');

        for (var i = 0; i < panels.length; i++) {
            var panel = panels[i];

            // Feature detection on the instance, not on a version: a browser without the Popover
            // API simply keeps the inline panel, which is a correct and complete rendering.
            if (typeof panel.showPopover !== 'function') {
                continue;
            }

            panel.setAttribute('popover', 'auto');
            // role=dialog is added HERE rather than in the JSP because it is only true once the
            // panel is a popup. On the inline fallback it is an ordinary section, and announcing
            // a dialog a visitor cannot dismiss would be a worse lie than saying nothing.
            // Deliberately NOT aria-modal: an auto popover does not trap focus or inert the page.
            panel.setAttribute('role', 'dialog');

            try {
                panel.showPopover();
                // The panel carries tabindex="-1"; move focus so a keyboard user lands on the
                // result instead of at the top of a freshly loaded page.
                panel.focus();
            } catch (ignored) {
                // Already open, or not connected. Fall back to the inline panel rather than
                // leaving an element that is marked as a popup but was never shown -- which the
                // browser would keep hidden, losing the comparison entirely.
                panel.removeAttribute('popover');
                panel.removeAttribute('role');
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', upgrade);
    } else {
        upgrade();
    }
}());
