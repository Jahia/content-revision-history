<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="crh" uri="http://www.jahia.org/content-revision-history/functions" %>
<%--
  Renders a single crh:revisionEntry as a self-contained record (<article> + <dl>).
  Designed to work standalone from just `currentNode` -- no data is threaded down
  from the crh_revisionHistory container view.

  SECURITY -- summary/richtext handling:
    `summary` is declared `(string, richtext) i18n mandatory`, i.e. authored through
    the CKEditor widget, so the stored value is an HTML fragment. It is emitted
    UNESCAPED, but only ever after passing through crh:sanitize (jsoup, allow-list
    based -- see RichTextSanitizer for what survives and why).
      This replaced a <c:out>, which was safe but showed visitors literal "<p>" tags
      and discarded every link and emphasis an editor wrote. The rule that has NOT
      changed: nothing reaches the page unescaped unless it went through the
      sanitiser first. Do not swap crh:sanitize for a plain ${...} -- `summary` is
      writable by any site contributor, so that is a stored-XSS hole on a public page.

  "Compare with previous" naming/positioning (SC 2.4.6, 2.5.3, 4.1.2):
    Each control's accessible name is its full VISIBLE text (no separate aria-label),
    so the accessible name always equals the visible label per SC 2.5.3, and always
    embeds both this entry's version+date and the previous entry's version+date, so
    a screen-reader user scanning a links list sees N distinct names instead of
    N copies of "Compare".
    "Previous" is POSITIONAL, not computed from `revisionDate`: crh:revisionHistory
    is declared `orderable` in the CND, so editors control the child order by
    drag-and-drop in Content Editor. Note that `orderable` is what makes this true --
    extending jmix:list does NOT confer it, and while the type lacked the keyword
    Jackrabbit refused reordering outright, leaving this whole convention
    unachievable. This view treats the *next sibling in that editorial order* as the
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
<%-- SECURITY: <fmt:message> writes its output UNESCAPED -- unlike <c:out> it has no
     escapeXml attribute -- and revisionLabel is editor-authored free text. Every
     <fmt:param> below therefore escapes AT THE POINT OF USE. Do not reintroduce
     intermediate <c:set> "safe" variables: they did not survive to <fmt:message>,
     and an empty param silently blanks the control instead of failing loudly. --%>

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
        <dd><c:choose>
            <c:when test="${not empty changeTypeCode}"><fmt:message key="crh_revisionEntry.changeType.${changeTypeCode}"/></c:when>
            <c:otherwise><fmt:message key="crh_revisionEntry.changeType.substantive"/></c:otherwise>
        </c:choose></dd>

        <dt><fmt:message key="crh_revisionEntry.summary"/></dt>
        <%-- Sanitised HTML, deliberately unescaped -- see the security note above. --%>
        <dd>${crh:sanitize(currentNode.properties.summary.string)}</dd>
    </dl>

    <c:choose>
        <c:when test="${not empty previousEntry}">
            <fmt:formatDate var="previousHumanDate" value="${previousEntry.properties.revisionDate.date.time}" dateStyle="long"/>
            <c:set var="previousLabel" value="${previousEntry.properties.revisionLabel.string}"/>
            <%-- A LINK, not a button, and deliberately so. This used to be a <button> with
                 data- attributes and no script behind it, so it was a dead control: focusable,
                 announced as a button, and doing nothing when activated (SC 4.1.2).

                 The href is query-only and therefore relative to the current page URL, which
                 keeps this correct under vanity URLs and SEO rewriting without the view having
                 to reconstruct the page address. The fragment moves focus to the panel, which
                 carries tabindex="-1" for exactly that reason.

                 No JavaScript anywhere in this feature: the comparison is rendered server-side,
                 so it works with scripting unavailable, and there is no client-side code path
                 that could handle snapshot content unsafely.

                 aria-current marks the comparison currently on screen, so a visitor returning
                 to the list can tell which of N identically-shaped links is the active one. It
                 reads crhRequestedEntry, set in REQUEST scope by the parent revisionHistory view:
                 ${param.crhDiff} does not survive into this nested <template:module> render. --%>
            <a class="crh-compare-link"
               href="?crhDiff=${fn:escapeXml(currentNode.identifier)}#crh-diff-panel-${fn:escapeXml(parentHistory.identifier)}"
               <c:if test="${crhRequestedEntry eq currentNode.identifier}">aria-current="true"</c:if>>
                <fmt:message key="crh_revisionEntry.compareWithPrevious">
                    <fmt:param value="${fn:escapeXml(currentNode.properties.revisionLabel.string)}"/>
                    <fmt:param value="${fn:escapeXml(humanDate)}"/>
                    <fmt:param value="${fn:escapeXml(previousEntry.properties.revisionLabel.string)}"/>
                    <fmt:param value="${fn:escapeXml(previousHumanDate)}"/>
                </fmt:message>
            </a>
        </c:when>
        <c:otherwise>
            <%-- Oldest entry (last in editorial order): explained, non-interactive state --
                 never a disabled/dead control with no explanation. --%>
            <p class="crh-compare-unavailable">
                <fmt:message key="crh_revisionEntry.compareUnavailable">
                    <fmt:param value="${fn:escapeXml(currentNode.properties.revisionLabel.string)}"/>
                </fmt:message>
            </p>
        </c:otherwise>
    </c:choose>
</article>
