<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="crh" uri="http://www.jahia.org/content-revision-history/functions" %>
<%--
  Renders ONE revision as a self-contained record (<article> + <dl>).

  IT DELIBERATELY KNOWS NOTHING ABOUT ITS SIBLINGS. The "compare with the previous revision"
  control used to live here, which made this view's output depend on the nodes around it -- and
  Jahia keys HTML cache fragments on the node itself, never on its neighbours. So adding a
  revision left the entry beside it cached with the wrong control: the new newest revision showed
  "earliest recorded revision", and the one before it kept offering a comparison against a
  revision that was no longer its predecessor. Nothing invalidated those fragments, because from
  the cache's point of view nothing about those nodes had changed.

  Everything that depends on more than one revision -- ordering, which revision precedes which,
  the compare control and the comparison panels -- now belongs to the crh:revisionHistory view,
  which owns the whole list. That is what makes this view safely cacheable rather than needing a
  cache opt-out to paper over the coupling.

  SECURITY -- summary/richtext handling:
    `summary` is declared `(string, richtext) i18n mandatory`, i.e. authored through the CKEditor
    widget, so the stored value is an HTML fragment. It is emitted UNESCAPED, but only ever after
    passing through crh:sanitize (jsoup, allow-list based -- see RichTextSanitizer for what
    survives and why).
      This replaced a <c:out>, which was safe but showed visitors literal "<p>" tags and discarded
      every link and emphasis an editor wrote. The rule that has NOT changed: nothing reaches the
      page unescaped unless it went through the sanitiser first. Do not swap crh:sanitize for a
      plain ${...} -- `summary` is writable by any site contributor, so that is a stored-XSS hole
      on a public page.

  Heading level: <h3>, one below the crh:revisionHistory view's <h2>. See that view for the
  documented assumption about where the component sits in the page's heading hierarchy.
--%>
<fmt:formatDate var="isoDate" value="${currentNode.properties.revisionDate.date.time}" pattern="yyyy-MM-dd"/>
<fmt:formatDate var="humanDate" value="${currentNode.properties.revisionDate.date.time}" dateStyle="long"/>
<c:set var="changeTypeCode" value="${currentNode.properties.changeType.string}"/>

<article class="crh-entry" aria-labelledby="crh-entry-heading-${currentNode.identifier}">
    <%-- The date sits on the heading line beside the version, not in a row of its own. A
         version number followed by a date needs no "Revision date" label to be understood, and
         the dedicated row cost a whole line per revision for a fact that fits in the margin of
         the one above it. <time datetime> keeps the machine-readable form regardless of how it
         is presented. --%>
    <h3 id="crh-entry-heading-${currentNode.identifier}">
        <c:out value="${currentNode.properties.revisionLabel.string}"/><time
            class="crh-entry-date" datetime="${isoDate}"><c:out value="${humanDate}"/></time>
    </h3>

    <dl class="crh-entry-facts">
        <dt><fmt:message key="crh_revisionEntry.changeType"/></dt>
        <%-- changeTypeCode is a node property interpolated into a resource-bundle KEY, and two
             JSTL behaviours make that load-bearing: fmt:message writes its output unescaped, and
             a MISSING key renders as ???the.key???, echoing the key into the page.

             The CND choicelist constrains the value to initial|editorial|substantive|correction and
             Jackrabbit enforces it on setProperty, so this holds today. It is escaped anyway.
             This was the only output in the module whose safety came from a repository
             constraint rather than from escaping where the value is used, and a value constraint
             is enforced on setProperty while a system-view import is exactly the path where JCR
             validation gets relaxed and nobody re-checks this line.

             Escaping deliberately does NOT whitelist: an unexpected value still renders the
             visible ??? marker rather than being silently shown as "Substantive", because
             quietly displaying the wrong change type in an evidentiary record is worse than
             visibly displaying a broken one. --%>
        <dd><c:choose>
            <c:when test="${not empty changeTypeCode}"><fmt:message key="crh_revisionEntry.changeType.${fn:escapeXml(changeTypeCode)}"/></c:when>
            <c:otherwise><fmt:message key="crh_revisionEntry.changeType.substantive"/></c:otherwise>
        </c:choose></dd>

        <dt><fmt:message key="crh_revisionEntry.summary"/></dt>
        <%-- Sanitised HTML, deliberately unescaped -- see the security note above. --%>
        <dd>${crh:sanitize(currentNode.properties.summary.string)}</dd>
    </dl>
</article>
