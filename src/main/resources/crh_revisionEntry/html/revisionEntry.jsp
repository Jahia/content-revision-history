<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%--
  Renders a single crh:revisionEntry as a self-contained record (<article> + <dl>).
  Designed to work standalone from just `currentNode` -- no data is threaded down
  from the crh_revisionHistory container view.

  SECURITY -- summary/richtext handling (see task item 1: no unsanitised HTML ever):
    `summary` is declared `(string, richtext) i18n mandatory`, i.e. authored through
    the CKEditor widget in Content Editor, so the stored value is normally an HTML
    fragment (e.g. "<p>...</p>"). This view renders it as ESCAPED PLAIN TEXT via
    <c:out>, NOT as raw HTML.
      Why: this module ships no vetted HTML sanitiser on its classpath (pom.xml is
      out of scope for this change; adding one is a Java/dependency change owned by
      another agent). Rendering the stored markup unescaped would reopen exactly the
      stored-XSS hole the deleted spike view had, just moved from `jcr:title` to
      `summary`.
      Consequence: authored rich formatting (bold, links, lists) is NOT preserved on
      the live page today -- editors will see literal "<p>...</p>" tags as text.
      To restore real rich-text rendering safely, a follow-up change must run
      `summary` through a vetted sanitiser (OWASP Java HTML Sanitizer is already on
      this workspace's local repo; Jahia also ships an official `html-filtering`
      module) in a Java render helper, and only then switch this line to unescaped
      output through that filter's result. Do not remove the <c:out> without adding
      that sanitisation step first.

  "Compare with previous" naming/positioning (SC 2.4.6, 2.5.3, 4.1.2):
    Each control's accessible name is its full VISIBLE text (no separate aria-label),
    so the accessible name always equals the visible label per SC 2.5.3, and always
    embeds both this entry's version+date and the previous entry's version+date, so
    a screen-reader user scanning a "buttons" list sees N distinct names instead of
    N copies of "Compare".
    "Previous" is POSITIONAL, not computed from `revisionDate`: crh:revisionHistory
    extends jmix:list, so editors control the child order by drag-and-drop in
    Content Editor (see the historyTitle/revisionDate field help in the resource
    bundle). This view treats the *next sibling in that editorial order* as the
    chronologically older revision, which only holds if editors keep the list
    newest-first. A plain JSTL view has no reliable way to sort a JCR NodeIterator
    by `revisionDate` without a Java helper (out of scope here), so this is a
    documented assumption, not a silent guess -- and it is exactly why the oldest
    entry is determined by *list position* (last child) rather than by comparing
    dates.
--%>
<c:set var="parentHistory" value="${currentNode.parent}"/>
<c:set var="previousEntry" value=""/>
<c:set var="foundCurrent" value="false"/>
<c:forEach items="${parentHistory.nodes}" var="sibling">
    <c:choose>
        <c:when test="${foundCurrent and empty previousEntry}">
            <c:set var="previousEntry" value="${sibling}"/>
        </c:when>
        <c:when test="${sibling.identifier eq currentNode.identifier}">
            <c:set var="foundCurrent" value="true"/>
        </c:when>
    </c:choose>
</c:forEach>

<fmt:formatDate var="isoDate" value="${currentNode.properties.revisionDate.date.time}" pattern="yyyy-MM-dd"/>
<fmt:formatDate var="humanDate" value="${currentNode.properties.revisionDate.date.time}" dateStyle="long"/>
<c:set var="changeTypeCode" value="${currentNode.properties.changeType.string}"/>
<c:set var="currentLabel" value="${currentNode.properties.revisionLabel.string}"/>

<article class="crh-entry" aria-labelledby="crh-entry-heading-${currentNode.identifier}">
    <h3 id="crh-entry-heading-${currentNode.identifier}">
        <c:out value="${currentLabel}"/>
    </h3>

    <dl class="crh-entry-facts">
        <dt><fmt:message key="crh_revisionEntry.revisionDate"/></dt>
        <dd><time datetime="${isoDate}"><c:out value="${humanDate}"/></time></dd>

        <dt><fmt:message key="crh_revisionEntry.changeType"/></dt>
        <%-- changeTypeCode is constrained by the CND choicelist to editorial|substantive|correction,
             so building the resource key from it is not attacker-controlled input. --%>
        <dd><fmt:message key="crh_revisionEntry.changeType.${changeTypeCode}"/></dd>

        <dt><fmt:message key="crh_revisionEntry.summary"/></dt>
        <%-- Escaped plain text, deliberately -- see the security note above. --%>
        <dd><c:out value="${currentNode.properties.summary.string}"/></dd>
    </dl>

    <c:choose>
        <c:when test="${not empty previousEntry}">
            <fmt:formatDate var="previousHumanDate" value="${previousEntry.properties.revisionDate.date.time}" dateStyle="long"/>
            <c:set var="previousLabel" value="${previousEntry.properties.revisionLabel.string}"/>
            <%-- Native <button>: keyboard/switch operable by default, no role/tabindex needed.
                 Target size and focus-indicator styling are NOT shipped by this module -- see
                 the accessibility notes in the parent agent's summary for what the host site
                 must provide (>=44x44 CSS px target, >=2px/3:1 visible focus outline). --%>
            <button type="button" class="crh-compare-btn"
                    data-crh-current="${currentNode.identifier}"
                    data-crh-previous="${previousEntry.identifier}">
                <fmt:message key="crh_revisionEntry.compareWithPrevious">
                    <fmt:param value="${currentLabel}"/>
                    <fmt:param value="${humanDate}"/>
                    <fmt:param value="${previousLabel}"/>
                    <fmt:param value="${previousHumanDate}"/>
                </fmt:message>
            </button>
        </c:when>
        <c:otherwise>
            <%-- Oldest entry (last in editorial order): explained, non-interactive state --
                 never a disabled/dead control with no explanation. --%>
            <p class="crh-compare-unavailable">
                <fmt:message key="crh_revisionEntry.compareUnavailable">
                    <fmt:param value="${currentLabel}"/>
                </fmt:message>
            </p>
        </c:otherwise>
    </c:choose>
</article>
