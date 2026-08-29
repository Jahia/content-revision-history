<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%@ taglib prefix="crh" uri="http://www.jahia.org/content-revision-history/functions" %>
<%--
  Public view for crh:revisionHistory: the revision list, plus the comparison panel when a
  visitor has asked for one.

  Semantic list, not a data table (SC 1.4.10 Reflow): each revision is a self-contained record,
  so an <ol> of <article>/<dl> reflows correctly at 400% zoom / 320px, where a fixed-width
  table would force horizontal page scroll. Each <li> delegates to the crh:revisionEntry node's
  own view via <template:module>, so that view stays independently correct.

  Heading structure (SC 1.3.1, 2.4.6) -- documented assumption:
    crh:revisionHistory is jmix:droppableContent, so an editor can place it anywhere on any
    page, and this view has no reliable signal for the surrounding heading level (no such
    property exists in the CND). It assumes the component sits below the page's <h1> and
    renders its own heading as <h2>, with entries and the comparison panel at <h3>. A page
    needing a different starting level is not supported -- a documented limitation, not a
    silent guess.

  Fallback title (SC 2.4.6): historyTitle is optional; when empty the
  crh_revisionHistory.defaultTitle bundle key is used, so the heading is never empty.

  CACHING: see revisionHistory.properties -- this view opts out of the HTML cache because the
  panel below varies on a query parameter the cache key does not include.
--%>
<template:addResources type="css" resources="revision-history.css"/>
<%-- Progressive enhancement only: it turns the rendered comparison into a popup. With scripting
     unavailable the comparison still renders, inline, complete and readable. See the script for
     why the popover attribute is added there rather than written into the markup here. --%>
<template:addResources type="javascript" resources="revision-history.js"/>

<c:set var="historyTitle" value="${currentNode.properties.historyTitle.string}"/>
<%-- The two selected revisions come from the form below, i.e. straight from the visitor. They are
     passed to crh:compare together with THIS history node, which is what confines them to entries
     of this list: the service reads with a session that bypasses ACLs, so an unconstrained
     identifier would render an arbitrary node onto a public page.

     Exactly one comparison is built, and only when one was asked for. An earlier design
     pre-rendered every adjacent comparison so a popup could open with no round trip; that cannot
     extend to arbitrary pairs (ten revisions have forty-five of them) and a visitor asking "what
     changed between the version I signed and today" is usually asking about a pair that is not
     adjacent. --%>
<c:set var="selectedFrom" value="${param.crhFrom}"/>
<c:set var="selectedTo" value="${param.crhTo}"/>
<c:set var="comparisonRequested" value="${not empty selectedFrom and not empty selectedTo}"/>

<section class="crh-revision-history" aria-labelledby="crh-history-heading-${currentNode.identifier}">
    <h2 id="crh-history-heading-${currentNode.identifier}">
        <c:choose>
            <c:when test="${not empty historyTitle}">
                <c:out value="${historyTitle}"/>
            </c:when>
            <c:otherwise>
                <fmt:message key="crh_revisionHistory.defaultTitle"/>
            </c:otherwise>
        </c:choose>
    </h2>

    <%-- Newest first BY revisionDate, not by the order the entries happen to sit in.
         Content Editor appends a new child at the END, which under a newest-first reading is the
         OLDEST position -- so simply adding a revision used to put the newest one last, where it
         rendered as "the earliest recorded revision" with no comparison, while the entry beside
         it began comparing against the wrong revision. See RevisionEntryOrder.

         This is also the list the comparisons are paired from, so a control and the panel it
         opens can never disagree about which revision is "previous".

         Counted BEFORE the summary that reports it: JSTL evaluates strictly top to bottom and has
         no hoisting, so a <c:set> placed after its reader silently yields an empty value -- which
         is how this module shipped blank Compare controls twice. --%>
    <c:set var="entries" value="${crh:orderedEntries(currentNode)}"/>
    <c:set var="entryCount" value="${fn:length(entries)}"/>

    <%-- The selector. A plain GET form: no JavaScript, the result is a shareable URL, and the
         browser's Back button behaves. It replaced one "compare with the previous revision"
         control per revision, which could only ever answer about adjacent pairs.

         Rendered only when there is something to compare. A single revision has no pair, and
         offering a control that cannot do anything is the dead-control failure (SC 4.1.2) this
         component has already had once.

         The action carries a fragment so that submitting moves focus to the result rather than
         leaving the visitor at the top of a reloaded page. --%>
    <c:if test="${entryCount > 1}">
        <%-- data-crh-panel tells the enhancement script which panel this form fills, so it can
             fetch a comparison and show it without navigating. Without the script the form
             submits normally and the server renders the comparison inline.

             The action keeps its fragment for that no-script path, where it is what moves focus
             to the result on a fresh page load. The script strips it when building its fetch URL:
             it was also the reason a repeat submission became a no-op, since navigating to a URL
             that differs only by fragment is a same-document navigation and never reloads. --%>
        <fmt:message var="crhLoadingMessage" key="crh_compare.loading"/>
        <form class="crh-compare-form" method="get"
              data-crh-panel="crh-comparison-${currentNode.identifier}"
              data-crh-status="crh-status-${currentNode.identifier}"
              data-crh-loading="${fn:escapeXml(crhLoadingMessage)}"
              action="${fn:escapeXml(url.base)}${fn:escapeXml(renderContext.mainResource.node.path)}.html#crh-comparison-${currentNode.identifier}">
            <%-- The fieldset/legend is kept in the markup but carries no visual styling at all
                 (see the stylesheet: border, padding and margin are all zeroed). It is what tells
                 a screen reader that these controls form one group, which matters here because
                 the visible labels alone are "Compare" and "with" -- and "with" is not a usable
                 accessible name for a control on its own. The legend is visually hidden rather
                 than removed so the group keeps its purpose without adding anything to look at. --%>
            <fieldset>
                <legend class="crh-visually-hidden"><fmt:message key="crh_compare.legend"/></legend>

                <label for="crh-from-${currentNode.identifier}"><fmt:message key="crh_compare.from"/></label>
                <select id="crh-from-${currentNode.identifier}" name="crhFrom">
                    <c:forEach items="${entries}" var="option" varStatus="optionStatus">
                        <fmt:formatDate var="optionDate" value="${option.properties.revisionDate.date.time}" dateStyle="medium"/>
                        <%-- Defaults to the second-newest paired with the newest: the most common
                             question, and a selection that is always valid. Any previous choice
                             wins, so a resubmitted form keeps what the visitor picked. --%>
                        <option value="${fn:escapeXml(option.identifier)}"
                                <c:if test="${(empty selectedFrom and optionStatus.index == 1) or selectedFrom eq option.identifier}">selected</c:if>><c:out
                                value="${option.properties.revisionLabel.string}"/> (<c:out value="${optionDate}"/>)</option>
                    </c:forEach>
                </select>

                <label for="crh-to-${currentNode.identifier}"><fmt:message key="crh_compare.to"/></label>
                <select id="crh-to-${currentNode.identifier}" name="crhTo">
                    <c:forEach items="${entries}" var="option" varStatus="optionStatus">
                        <fmt:formatDate var="optionDate" value="${option.properties.revisionDate.date.time}" dateStyle="medium"/>
                        <option value="${fn:escapeXml(option.identifier)}"
                                <c:if test="${(empty selectedTo and optionStatus.index == 0) or selectedTo eq option.identifier}">selected</c:if>><c:out
                                value="${option.properties.revisionLabel.string}"/> (<c:out value="${optionDate}"/>)</option>
                    </c:forEach>
                </select>

                <%-- SC 4.1.3. Empty and present from the start: a live region has to exist before
                     the text is put into it, or the change is not announced. Only the script ever
                     writes here, so without scripting it stays empty and says nothing, which is
                     correct -- the no-script path is a full page navigation with no waiting. --%>
                <span id="crh-status-${currentNode.identifier}" class="crh-visually-hidden"
                      role="status" aria-live="polite"></span>

                <%-- Small icon button, following the store's own pattern: 16x16 viewBox,
                     stroke="currentColor", stroke-width 1.3, aria-hidden + focusable="false" on
                     the graphic, and the accessible name carried by aria-label.

                     Two deliberate differences from the store markup:
                       * It keeps this module's own class rather than store-btn/store-btn--primary.
                         Those classes live in the store's stylesheet, so borrowing them would
                         style the control on exactly one site and silently do nothing everywhere
                         else. The look is reproduced in our own stylesheet instead.
                       * title="" rather than data-tooltip="": the store's tooltip is driven by its
                         JavaScript, and this feature has none. title is the native equivalent.

                     ACCESSIBILITY, stated rather than buried: at 32x32 this meets the WCAG 2.2 AA
                     target size (SC 2.5.8, 24x24) but NOT the AAA enhanced size (SC 2.5.5, 44x44)
                     the rest of this module holds to. That is the cost of "small", and it was
                     asked for explicitly. The control is not the only way to reach a comparison
                     -- the URL it produces is shareable -- and it sits beside two full-height
                     selects rather than among other small targets. --%>
                <button type="submit" class="crh-compare-btn"
                        aria-label="<fmt:message key="crh_compare.submit"/>"
                        title="<fmt:message key="crh_compare.submit"/>">
                    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
                        <path d="M2 5.5h9.5M9 3l2.5 2.5L9 8" fill="none" stroke="currentColor"
                              stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                        <path d="M14 10.5H4.5M7 8l-2.5 2.5L7 13" fill="none" stroke="currentColor"
                              stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </button>
            </fieldset>
        </form>
    </c:if>

    <c:if test="${comparisonRequested}">
        <%-- currentResource.locale, NOT locale.language: EL coerces the Locale with
             toString(), which yields the underscore form Jahia uses for language codes and for
             the snapshot folder name ("pt_BR"). locale.language returns only the primary subtag
             ("pt"), so on any region-qualified site the lookup missed the folder capture had
             written and every comparison answered "no snapshot recorded" forever. --%>
        <c:set var="view" value="${crh:compare(currentNode.identifier, selectedFrom, selectedTo, currentResource.locale)}"/>

        <%-- A plain section, deliberately. revision-history.js adds popover="auto" and
             role="dialog" at runtime; writing them here would hide the comparison outright from
             anyone without JavaScript, because a browser keeps [popover] hidden until something
             shows it.

             tabindex="-1" serves both states: it is the focus target the script moves to when the
             panel becomes a popup, and the target of the fragment in the form action when it does
             not (SC 2.4.3). --%>
        <section id="crh-comparison-${currentNode.identifier}" class="crh-diff-panel" tabindex="-1"
                 aria-labelledby="crh-diff-heading-${currentNode.identifier}">
            <%-- Panel tools, top right, first in the DOM so keyboard order matches what the eye
                 sees (SC 2.4.3).

                 The full-screen toggle is server-rendered but CSS-hidden unless the panel is
                 actually a popup (:popover-open). Without JavaScript the comparison renders
                 inline, where "full screen" means nothing -- and a control that cannot do
                 anything is the dead-control failure this component has had once already.

                 The close control is a LINK, not a popovertarget button, because it must work in
                 both states: as a popup, following it navigates away and the popup goes with it;
                 inline, it simply clears the comparison. Escape and clicking outside also dismiss
                 the popup, both from popover="auto". --%>
            <div class="crh-diff-tools">
                <button type="button" class="crh-diff-expand" aria-pressed="false"
                        aria-label="<fmt:message key="crh_diff.fullscreen"/>"
                        title="<fmt:message key="crh_diff.fullscreen"/>">
                    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
                        <path d="M6 2H2v4M10 2h4v4M6 14H2v-4M10 14h4v-4" fill="none"
                              stroke="currentColor" stroke-width="1.3" stroke-linecap="round"
                              stroke-linejoin="round"/>
                    </svg>
                </button>
                <a class="crh-diff-close"
                   href="${fn:escapeXml(url.base)}${fn:escapeXml(renderContext.mainResource.node.path)}.html"
                   aria-label="<fmt:message key="crh_diff.close"/>"
                   title="<fmt:message key="crh_diff.close"/>">
                    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
                        <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor"
                              stroke-width="1.3" stroke-linecap="round"/>
                    </svg>
                </a>
            </div>
            <c:choose>
                <c:when test="${view.available}">
                    <fmt:formatDate var="diffCurrentDate" value="${view.currentDate.time}" dateStyle="long"/>
                    <fmt:formatDate var="diffPreviousDate" value="${view.previousDate.time}" dateStyle="long"/>
                    <%-- <fmt:message> writes its output UNESCAPED and has no escapeXml attribute,
                         and revisionLabel is editor-authored free text, so every param is escaped
                         AT THE POINT OF USE. Do not hoist these into intermediate "safe"
                         variables: a previous version did, the <c:set> ended up above the
                         assignments it depended on, and the result was silently blank labels
                         rather than a visible failure. --%>
                    <h3 id="crh-diff-heading-${currentNode.identifier}">
                        <fmt:message key="crh_diff.heading">
                            <fmt:param value="${fn:escapeXml(view.currentLabel)}"/>
                            <fmt:param value="${fn:escapeXml(diffCurrentDate)}"/>
                            <fmt:param value="${fn:escapeXml(view.previousLabel)}"/>
                            <fmt:param value="${fn:escapeXml(diffPreviousDate)}"/>
                        </fmt:message>
                    </h3>

                    <c:if test="${view.generatorMismatch}">
                        <p class="crh-diff-notice"><fmt:message key="crh_diff.generatorMismatch"/></p>
                    </c:if>
                    <c:if test="${view.diff.truncated}">
                        <p class="crh-diff-notice"><fmt:message key="crh_diff.truncated"/></p>
                    </c:if>

                    <c:choose>
                        <c:when test="${view.diff.identical}">
                            <p><fmt:message key="crh_diff.identical"/></p>
                        </c:when>
                        <c:otherwise>
                            <p class="crh-diff-stats">
                                <fmt:message key="crh_diff.stats">
                                    <fmt:param value="${view.diff.addedCount}"/>
                                    <fmt:param value="${view.diff.removedCount}"/>
                                </fmt:message>
                            </p>
                            <%-- Side by side: the older revision on the left, the newer on the
                                 right, which is what a reader expects a diff to look like.

                                 Rows come from MarkdownDiff.Result.getRows(), derived from the
                                 same line list the counts above are taken from -- one diff behind
                                 both, so the two can never disagree about what changed.

                                 Grid rather than a table. A <table> would carry row/column
                                 semantics but cannot reflow: at 320px or 400% zoom two fixed
                                 columns force horizontal scrolling of the page, which SC 1.4.10
                                 forbids. The grid collapses to a single column below 48rem, where
                                 each row reads as "before" above "after" -- the unified view,
                                 arrived at by layout rather than by a second template. --%>
                            <p class="crh-diff-heads" aria-hidden="true">
                                <span class="crh-diff-head"><c:out value="${view.previousLabel}"/></span><span
                                      class="crh-diff-head"><c:out value="${view.currentLabel}"/></span>
                            </p>
                            <ol class="crh-diff-rows" role="list">
                                <c:forEach items="${view.diff.rows}" var="row">
                                    <c:choose>
                                        <c:when test="${row.gap}">
                                            <li class="crh-diff-row crh-diff-gap">
                                                <fmt:message key="crh_diff.gap">
                                                    <fmt:param value="${row.gapSize}"/>
                                                </fmt:message>
                                            </li>
                                        </c:when>
                                        <c:otherwise>
                                            <li class="crh-diff-row">
                                                <%-- OLDER revision, left --%>
                                                <c:choose>
                                                    <c:when test="${empty row.left}">
                                                        <span class="crh-diff-side crh-diff-absent" aria-hidden="true"></span>
                                                    </c:when>
                                                    <%-- Changed cells already carry a visually-hidden "Added"/"Removed"
                                                         label; unchanged cells were the only ones without one, so the same
                                                         sentence was announced twice with nothing to tell the two copies
                                                         apart. The column headers that disambiguate them visually are
                                                         aria-hidden, so there was no label anywhere. Every cell is labelled
                                                         now, which is also what makes the grid consistent. --%>
                                                    <c:when test="${row.unchanged}">
                                                        <span class="crh-diff-side"><span
                                                            class="crh-visually-hidden"><fmt:message key="crh_diff.olderSide"/></span><c:out value="${row.left.text}"/></span>
                                                    </c:when>
                                                    <c:otherwise>
                                                        <span class="crh-diff-side crh-diff-removed"><span
                                                            class="crh-visually-hidden"><fmt:message key="crh_diff.removed"/></span><span
                                                            class="crh-diff-marker" aria-hidden="true">-</span><del><%--
                                                        --%><c:choose>
                                                                <c:when test="${empty row.left.segments}"><c:out value="${row.left.text}"/></c:when>
                                                                <c:otherwise><c:forEach items="${row.left.segments}" var="segment"><c:choose><c:when test="${segment.changed}"><mark><c:out value="${segment.text}"/></mark></c:when><c:otherwise><c:out value="${segment.text}"/></c:otherwise></c:choose></c:forEach></c:otherwise>
                                                            </c:choose><%--
                                                        --%></del></span>
                                                    </c:otherwise>
                                                </c:choose>

                                                <%-- NEWER revision, right --%>
                                                <c:choose>
                                                    <c:when test="${empty row.right}">
                                                        <span class="crh-diff-side crh-diff-absent" aria-hidden="true"></span>
                                                    </c:when>
                                                    <c:when test="${row.unchanged}">
                                                        <span class="crh-diff-side"><span
                                                            class="crh-visually-hidden"><fmt:message key="crh_diff.newerSide"/></span><c:out value="${row.right.text}"/></span>
                                                    </c:when>
                                                    <c:otherwise>
                                                        <span class="crh-diff-side crh-diff-added"><span
                                                            class="crh-visually-hidden"><fmt:message key="crh_diff.added"/></span><span
                                                            class="crh-diff-marker" aria-hidden="true">+</span><ins><%--
                                                        --%><c:choose>
                                                                <c:when test="${empty row.right.segments}"><c:out value="${row.right.text}"/></c:when>
                                                                <c:otherwise><c:forEach items="${row.right.segments}" var="segment"><c:choose><c:when test="${segment.changed}"><mark><c:out value="${segment.text}"/></mark></c:when><c:otherwise><c:out value="${segment.text}"/></c:otherwise></c:choose></c:forEach></c:otherwise>
                                                            </c:choose><%--
                                                        --%></ins></span>
                                                    </c:otherwise>
                                                </c:choose>
                                            </li>
                                        </c:otherwise>
                                    </c:choose>
                                </c:forEach>
                            </ol>
                        </c:otherwise>
                    </c:choose>
                </c:when>
                <c:otherwise>
                    <h3 id="crh-diff-heading-${currentNode.identifier}"><fmt:message key="crh_diff.headingUnavailable"/></h3>
                    <%-- view.reason is one of RevisionDiffService's own REASON_* constants, never
                         visitor input, so composing a bundle key from it is not
                         attacker-controlled. --%>
                    <p><fmt:message key="crh_diff.unavailable.${view.reason}"/></p>
                </c:otherwise>
            </c:choose>

        </section>
    </c:if>
    <%-- Collapsible via native <details>/<summary>: no JavaScript, keyboard and switch operable
         with no role or tabindex of our own, and the browser announces expanded/collapsed state
         for free. The entries remain in the DOM when closed, so search engines, find-in-page and
         assistive technology still reach them -- closing hides them from view, not from the
         record.

         Open when the editor chose an expanded list, and ALWAYS open when a comparison was
         requested: the panel below names the two revisions, and leaving the list closed would
         hide the aria-current marker showing which link produced it. A missing
         collapsedByDefault (nodes created before the property existed) is read as collapsed,
         matching the CND default rather than inventing a second one. --%>
    <c:set var="startClosed"
           value="${empty currentNode.properties['collapsedByDefault']
                    or currentNode.properties['collapsedByDefault'].boolean}"/>

    <details class="crh-revision-disclosure"<c:if test="${not startClosed}"> open</c:if>>
        <summary class="crh-revision-toggle">
            <fmt:message key="crh_revisionHistory.toggle">
                <fmt:param value="${entryCount}"/>
            </fmt:message>
        </summary>

        <ol class="crh-revision-list" role="list">
            <c:forEach items="${entries}" var="entryNode" varStatus="status">
                <li>
                    <template:module node="${entryNode}" templateType="html"/>

                </li>
            </c:forEach>
        </ol>
    </details>
</section>
