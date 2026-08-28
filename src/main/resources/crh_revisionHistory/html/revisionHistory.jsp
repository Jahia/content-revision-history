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

<c:set var="historyTitle" value="${currentNode.properties.historyTitle.string}"/>
<c:set var="requestedEntry" value="${param.crhDiff}"/>
<%-- Handed down to the entry view in REQUEST scope rather than letting that view read
     ${param.crhDiff} itself. A crh:revisionEntry is rendered through <template:module>, i.e. a
     nested render, and the query parameter does not survive into it -- the entry view read an
     empty value and silently omitted aria-current, with no error anywhere. Request scope spans
     the nested render, so the two views cannot disagree about which entry was asked for. --%>
<c:set var="crhRequestedEntry" value="${param.crhDiff}" scope="request"/>

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

    <ol class="crh-revision-list">
        <c:forEach items="${currentNode.nodes}" var="entryNode">
            <li>
                <template:module node="${entryNode}" templateType="html"/>
            </li>
        </c:forEach>
    </ol>

    <c:if test="${not empty requestedEntry}">
        <%-- The first argument is THIS history node, server-supplied. It is what confines the
             second -- which comes straight from the query string -- to entries of this list.
             Without it a crafted identifier would have the service read an arbitrary node with
             a system session and render it here. --%>
        <c:set var="comparison"
               value="${crh:compare(currentNode.identifier, requestedEntry, currentResource.locale.language)}"/>

        <%-- tabindex="-1" so the #crh-diff-panel fragment in each compare link actually moves
             keyboard focus here; without it browsers scroll the panel into view but leave focus
             at the top of the document (SC 2.4.3). --%>
        <%-- Both ids are qualified with this history node's identifier. crh:revisionHistory is
             droppable, so a page can legitimately carry more than one, and a fixed id would then
             be emitted twice: invalid HTML, and #crh-diff-panel would send focus to whichever
             panel came first rather than the one the visitor's link belongs to. Each entry's
             link targets its OWN history's panel for the same reason. A second history on the
             page renders its own panel saying the revision is not in *that* history, which is
             exactly what happened. --%>
        <section id="crh-diff-panel-${currentNode.identifier}" class="crh-diff-panel" tabindex="-1"
                 aria-labelledby="crh-diff-heading-${currentNode.identifier}">
            <c:choose>
                <c:when test="${comparison.available}">
                    <fmt:formatDate var="diffCurrentDate" value="${comparison.currentDate.time}" dateStyle="long"/>
                    <fmt:formatDate var="diffPreviousDate" value="${comparison.previousDate.time}" dateStyle="long"/>
                    <%-- <fmt:message> writes its output UNESCAPED and has no escapeXml
                         attribute, and revisionLabel is editor-authored free text, so every
                         param is escaped AT THE POINT OF USE. Do not hoist these into
                         intermediate "safe" variables: a previous version of this module did,
                         the <c:set> ended up above the assignments it depended on, and the
                         result was silently blank labels rather than a visible failure. --%>
                    <h3 id="crh-diff-heading-${currentNode.identifier}">
                        <fmt:message key="crh_diff.heading">
                            <fmt:param value="${fn:escapeXml(comparison.currentLabel)}"/>
                            <fmt:param value="${fn:escapeXml(diffCurrentDate)}"/>
                            <fmt:param value="${fn:escapeXml(comparison.previousLabel)}"/>
                            <fmt:param value="${fn:escapeXml(diffPreviousDate)}"/>
                        </fmt:message>
                    </h3>

                    <c:if test="${comparison.generatorMismatch}">
                        <p class="crh-diff-notice"><fmt:message key="crh_diff.generatorMismatch"/></p>
                    </c:if>
                    <c:if test="${comparison.diff.truncated}">
                        <p class="crh-diff-notice"><fmt:message key="crh_diff.truncated"/></p>
                    </c:if>

                    <c:choose>
                        <c:when test="${comparison.diff.identical}">
                            <p><fmt:message key="crh_diff.identical"/></p>
                        </c:when>
                        <c:otherwise>
                            <p class="crh-diff-stats">
                                <fmt:message key="crh_diff.stats">
                                    <fmt:param value="${comparison.diff.addedCount}"/>
                                    <fmt:param value="${comparison.diff.removedCount}"/>
                                </fmt:message>
                            </p>
                            <ol class="crh-diff-lines">
                                <c:forEach items="${comparison.diff.lines}" var="line">
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
                    <%-- comparison.reason is one of RevisionDiffService's own REASON_*
                         constants, never visitor input, so composing a bundle key from it is
                         not attacker-controlled. --%>
                    <p><fmt:message key="crh_diff.unavailable.${comparison.reason}"/></p>
                </c:otherwise>
            </c:choose>

            <c:if test="${not empty url.base}">
                <a class="crh-diff-close"
                   href="${fn:escapeXml(url.base)}${fn:escapeXml(renderContext.mainResource.node.path)}.html">
                    <fmt:message key="crh_diff.close"/>
                </a>
            </c:if>
        </section>
    </c:if>
</section>
