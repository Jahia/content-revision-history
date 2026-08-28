<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%--
  Production view for crh:revisionHistory (replaces the deleted spike view --
  see git history: the spike hardcoded a single site path, rendered the internal
  `markdown` snapshot template type into an HTML <pre>, and emitted jcr:title via
  unescaped ${} EL, giving every visitor a stored-XSS vector on any page an editor
  dropped it on).

  Semantic list, not a data table (SC 1.4.10 Reflow): each revision is a
  self-contained record, so an <ol> of <article>/<dl> reflows correctly at 400%
  zoom / 320px, where a fixed-width table would force horizontal page scroll.
  Each <li> delegates rendering to the crh:revisionEntry node's own view
  (crh_revisionEntry/html/revisionEntry.jsp) via <template:module>, so that view
  stays independently correct/self-contained.

  Heading structure (SC 1.3.1, 2.4.6) -- documented assumption:
    crh:revisionHistory is jmix:droppableContent, so an editor can place it
    anywhere on any page; this view has no reliable signal for the surrounding
    heading level (no such property exists in the CND). It assumes the component
    sits below the page's <h1> and renders its own heading as <h2>, with each
    entry's heading one level below at <h3> (see crh_revisionEntry view). A page
    that needs a different starting level is not supported by this view --
    documented limitation, not silently guessed.

  Fallback title (SC 2.4.6): historyTitle is optional (i18n string); when empty,
  falls back to the crh_revisionHistory.defaultTitle resource-bundle key so the
  heading is never empty.
--%>
<c:set var="historyTitle" value="${currentNode.properties.historyTitle.string}"/>

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
</section>
