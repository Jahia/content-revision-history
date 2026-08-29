/*
 * Turns the comparison into a popup, and keeps it out of the address bar.
 *
 * WITHOUT THIS SCRIPT the feature still works completely: the selector is a plain GET form, the
 * server renders the comparison, and it appears inline below the selector. Everything here is an
 * enhancement on top of that, and every branch falls back to it.
 *
 * WHAT IT FIXES.
 *  1. The comparison used to arrive by navigation, so ?crhFrom=&crhTo=&#crh-comparison- ended up
 *     in the URL of a page the visitor was only reading.
 *  2. Worse, it could not be reopened. The form action carries a fragment, so once the URL
 *     already equalled action + query + fragment, submitting the same pair again navigated to an
 *     IDENTICAL URL -- which the browser treats as a same-document fragment navigation, not a
 *     reload. The page never re-ran, this script never re-fired, and the popup stayed shut.
 *     Both symptoms are the same cause: the comparison was driven by a page load.
 *
 * WHAT IT IS ALLOWED TO DO.
 * Fetch a comparison this same server renders, and move the resulting nodes into a popup. It does
 * not build markup from snapshot text, and never assigns innerHTML: the response is parsed inertly
 * by DOMParser (which runs no scripts) and the already-parsed nodes are imported. So the property
 * that mattered still holds -- no client-side code interprets snapshot content, it only relocates
 * output the server already escaped.
 *
 * Escape, click-outside dismissal and focus return come from popover="auto" itself.
 */
(function () {
    'use strict';

    var PANEL_CLASS = 'crh-diff-panel';

    function canPopover(el) {
        return el && typeof el.showPopover === 'function';
    }

    /**
     * Whether this browser supports popovers at all, asked WITHOUT touching the document.
     *
     * The capability check has to happen before the panel is created: creating it first and then
     * bailing out would leave an empty bordered box on the page of a browser that was about to
     * navigate away anyway.
     */
    function popoverSupported() {
        return canPopover(document.createElement('section'));
    }

    /**
     * Wires the full-screen toggle inside a panel.
     *
     * The button is server-rendered (so its label comes from the resource bundle rather than
     * being hardcoded here) and CSS-hidden unless the panel is actually a popup. Re-wired after
     * every fetch, because replacing the panel's children replaces the button with a fresh one;
     * the data attribute is what stops a surviving button being bound twice.
     */
    function wireFullScreen(panel) {
        var toggle = panel.querySelector('.crh-diff-expand');

        if (!toggle || toggle.getAttribute('data-crh-wired') === 'true') {
            return;
        }
        toggle.setAttribute('data-crh-wired', 'true');
        toggle.addEventListener('click', function () {
            // The class lives on the PANEL, not on its children, so it survives the content
            // being replaced when a different pair is compared.
            var full = panel.classList.toggle('crh-diff-panel--full');
            // aria-pressed carries the state, so one stable label serves both directions and a
            // screen reader is never told the control is something it is not.
            toggle.setAttribute('aria-pressed', full ? 'true' : 'false');
        });
    }

    /** Makes a panel a popup and opens it. Safe to call on an already-open panel. */
    function present(panel) {
        if (!canPopover(panel)) {
            return false;
        }
        panel.setAttribute('popover', 'auto');
        // role=dialog only becomes true once it is a popup; on the inline fallback it is an
        // ordinary section, and announcing an undismissable dialog is worse than saying nothing.
        // Deliberately NOT aria-modal: an auto popover does not trap focus or inert the page.
        panel.setAttribute('role', 'dialog');
        try {
            if (!panel.matches(':popover-open')) {
                panel.showPopover();
            }
            wireFullScreen(panel);
            closeWhenFocusLeaves(panel);
            panel.focus();
            return true;
        } catch (ignored) {
            // Leaving [popover] on a panel that was never shown would keep it hidden and lose the
            // comparison entirely, so drop back to the inline rendering.
            panel.removeAttribute('popover');
            panel.removeAttribute('role');
            return false;
        }
    }

    /**
     * Says something in the form's live region, or clears it.
     *
     * <p>The comparison arrives asynchronously and replaces content elsewhere on the page, which
     * a screen reader has no reason to notice. Without this the only feedback for several seconds
     * was nothing at all, so the natural response was to press the button again.
     */
    function announce(form, message) {
        var id = form.getAttribute('data-crh-status');
        var region = id && document.getElementById(id);
        if (region) {
            region.textContent = message;
        }
    }

    /**
     * Closes the popup once focus leaves it.
     *
     * <p>An `auto` popover neither traps focus nor inerts the page, so tabbing past the last
     * control inside the panel put focus on page content sitting UNDERNEATH an opaque, fixed
     * panel: focus indicator invisible, SC 2.4.12 failed. Trapping focus would be the other
     * answer, but it would also mean promising modal behaviour the popover does not implement.
     * Closing is what the panel's own light-dismiss behaviour already means.
     */
    function closeWhenFocusLeaves(panel) {
        if (panel.hasAttribute('data-crh-focus-wired')) {
            return;
        }
        panel.setAttribute('data-crh-focus-wired', '');
        panel.addEventListener('focusout', function (event) {
            // relatedTarget is where focus is going; null means it left the document entirely,
            // which is not a reason to close.
            var moving = event.relatedTarget;
            if (moving && !panel.contains(moving) && panel.matches(':popover-open')) {
                panel.hidePopover();
            }
        });
    }

    /** The panel belonging to a form, created empty if this page has never rendered one. */
    function panelFor(form) {
        var id = form.getAttribute('data-crh-panel');
        var panel = id && document.getElementById(id);

        if (!panel) {
            panel = document.createElement('section');
            panel.id = id;
            panel.className = PANEL_CLASS;
            panel.setAttribute('tabindex', '-1');
            form.parentNode.insertBefore(panel, form.nextSibling);
        }
        return panel;
    }

    function comparisonUrl(form) {
        var url = new URL(form.action, document.baseURI);
        url.search = new URLSearchParams(new FormData(form)).toString();
        // The action's fragment is what made a repeat submission a no-op navigation. It is of no
        // use here anyway: nothing is navigating.
        url.hash = '';
        return url.toString();
    }

    function enhance(form) {
        form.addEventListener('submit', function (event) {
            // Anything missing, and the form submits normally: the server renders the comparison
            // inline exactly as it does with no script at all. Checked BEFORE the panel exists,
            // so nothing is added to a page that is about to navigate.
            if (!popoverSupported() || typeof window.fetch !== 'function' ||
                typeof DOMParser !== 'function') {
                return;
            }
            event.preventDefault();

            var panel = panelFor(form);

            // Hide it BEFORE anything goes into it. A browser keeps [popover] hidden until
            // something shows it, so setting the attribute now is what stops an empty panel --
            // and then a filled one -- painting inline for the length of the request before
            // jumping into the top layer. That flash was visible as a flip on every first
            // comparison.
            panel.setAttribute('popover', 'auto');
            // role=dialog is only true once it is a popup, which from here it always will be:
            // the fallback path removes both attributes again.
            panel.setAttribute('role', 'dialog');

            announce(form, form.getAttribute('data-crh-loading') || '');
            fetch(comparisonUrl(form), {credentials: 'same-origin'})
                .then(function (response) {
                    if (!response.ok) {
                        throw new Error('comparison request failed');
                    }
                    return response.text();
                })
                .then(function (html) {
                    // DOMParser does not execute scripts and does not load subresources, so this
                    // is an inert parse of our own page.
                    var fresh = new DOMParser()
                        .parseFromString(html, 'text/html')
                        .getElementById(panel.id);

                    if (!fresh) {
                        throw new Error('no comparison in the response');
                    }
                    // Import the parsed nodes rather than assigning innerHTML: no re-parse, no
                    // markup built here, nothing interpreted.
                    var imported = document.importNode(fresh, true);
                    // Carry the wrapper's accessible name across. Only the CHILD nodes are
                    // imported, so the server-rendered section's aria-labelledby -- pointing at
                    // the heading that names both revisions -- was dropped on the floor, and a
                    // panel created by panelFor() has none of its own. A screen reader then
                    // announced "dialog" with no indication of what was being compared.
                    var label = fresh.getAttribute('aria-labelledby');
                    if (label) {
                        panel.setAttribute('aria-labelledby', label);
                    }
                    panel.replaceChildren.apply(panel, Array.prototype.slice.call(imported.childNodes));
                    announce(form, '');
                    present(panel);
                })
                .catch(function () {
                    announce(form, '');
                    // Give the visitor the comparison the slow way rather than nothing at all.
                    // The attributes are dropped first: a panel marked [popover] that was never
                    // shown stays hidden, and the navigation that follows would otherwise land on
                    // a page whose comparison is invisible.
                    panel.removeAttribute('popover');
                    panel.removeAttribute('role');
                    form.submit();
                });
        });
    }

    function init() {
        // A comparison already on the page: someone followed a shared link, or scripting arrived
        // after a plain form submission. Pop it, so both routes look the same.
        var rendered = document.querySelectorAll('.' + PANEL_CLASS);
        for (var i = 0; i < rendered.length; i++) {
            if (rendered[i].children.length > 0) {
                present(rendered[i]);
            }
        }

        var forms = document.querySelectorAll('.crh-compare-form');
        for (var f = 0; f < forms.length; f++) {
            enhance(forms[f]);
        }
    }

    // Jahia injects module JavaScript into <head> (template:addResources), so this file runs
    // BEFORE the body is parsed. Running immediately found nothing and silently did nothing,
    // which looked exactly like the intended no-JavaScript fallback.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
}());
