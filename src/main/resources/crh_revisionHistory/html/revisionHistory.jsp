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
        <form class="crh-compare-form" method="get"
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
        <c:set var="view" value="${crh:compare(currentNode.identifier, selectedFrom, selectedTo, currentResource.locale.language)}"/>

        <%-- A plain section, deliberately. revision-history.js adds popover="auto" and
             role="dialog" at runtime; writing them here would hide the comparison outright from
             anyone without JavaScript, because a browser keeps [popover] hidden until something
             shows it.

             tabindex="-1" serves both states: it is the focus target the script moves to when the
             panel becomes a popup, and the target of the fragment in the form action when it does
             not (SC 2.4.3). --%>
        <section id="crh-comparison-${currentNode.identifier}" class="crh-diff-panel" tabindex="-1"
                 aria-labelledby="crh-diff-heading-${currentNode.identifier}">
            <%-- Still a LINK rather than a popovertarget button, because it has to work in both
                 states: as a popup, following it navigates away and the popup goes with it;
                 inline, it simply clears the comparison. A popovertarget button would be a dead
                 control on the inline fallback, which is the failure this component has had once
                 already.

                 First in the DOM as well as first visually (top right), so keyboard order matches
                 reading order (SC 2.4.3). The cross is aria-hidden and the name comes from
                 aria-label, exactly as on the Compare control.

                 Escape and clicking outside also dismiss the popup. Both come from
                 popover="auto"; neither is reimplemented here. --%>
            <a class="crh-diff-close"
               href="${fn:escapeXml(url.base)}${fn:escapeXml(renderContext.mainResource.node.path)}.html"
               aria-label="<fmt:message key="crh_diff.close"/>"
               title="<fmt:message key="crh_diff.close"/>">
                <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
                    <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor"
                          stroke-width="1.3" stroke-linecap="round"/>
                </svg>
            </a>
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
                            <ol class="crh-diff-lines">
                                <c:forEach items="${view.diff.lines}" var="line">
                                    <c:choose>
                                        <c:when test="${line.gap}">
                                            <li class="crh-diff-line crh-diff-gap">
                                                <fmt:message key="crh_diff.gap">
                                                    <fmt:param value="${line.gapSize}"/>
                                                </fmt:message>
                                            </li>
                                        </c:when>
                                        <c:when test="${line.added}">
                                            <li class="crh-diff-line crh-diff-added">
                                                <span class="crh-visually-hidden"><fmt:message key="crh_diff.added"/></span><span
                                                    class="crh-diff-marker" aria-hidden="true">+</span><ins><%--
                                                --%><c:choose>
                                                        <c:when test="${empty line.segments}"><c:out value="${line.text}"/></c:when>
                                                        <c:otherwise><c:forEach items="${line.segments}" var="segment"><c:choose><c:when test="${segment.changed}"><mark><c:out value="${segment.text}"/></mark></c:when><c:otherwise><c:out value="${segment.text}"/></c:otherwise></c:choose></c:forEach></c:otherwise>
                                                    </c:choose><%--
                                                --%></ins>
                                            </li>
                                        </c:when>
                                        <c:when test="${line.removed}">
                                            <li class="crh-diff-line crh-diff-removed">
                                                <span class="crh-visually-hidden"><fmt:message key="crh_diff.removed"/></span><span
                                                    class="crh-diff-marker" aria-hidden="true">-</span><del><%--
                                                --%><c:choose>
                                                        <c:when test="${empty line.segments}"><c:out value="${line.text}"/></c:when>
                                                        <c:otherwise><c:forEach items="${line.segments}" var="segment"><c:choose><c:when test="${segment.changed}"><mark><c:out value="${segment.text}"/></mark></c:when><c:otherwise><c:out value="${segment.text}"/></c:otherwise></c:choose></c:forEach></c:otherwise>
                                                    </c:choose><%--
                                                --%></del>
                                            </li>
                                        </c:when>
                                        <c:otherwise>
                                            <li class="crh-diff-line">
                                                <span class="crh-diff-marker" aria-hidden="true">&nbsp;</span><c:out value="${line.text}"/>
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

        <ol class="crh-revision-list">
            <c:forEach items="${entries}" var="entryNode" varStatus="status">
                <li>
                    <template:module node="${entryNode}" templateType="html"/>

                </li>
            </c:forEach>
        </ol>
    </details>
</section>
