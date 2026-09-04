<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%-- Markdown template type: page root. template:module resolves children in the SAME
     template type, so the whole subtree composes as Markdown for free.
     Line breaks are emitted explicitly via line.separator (the convention the shipped
     jnt_contentList/csv view uses): JSP whitespace trimming otherwise swallows the
     newline after the heading and fuses it onto the first child's text.
     The title is escaped: it is text, and the whole output is parsed as HTML downstream, where a
     literal <style> or <!-- in a title swallows the rest of the page (issue #18).
     Sub-pages are SKIPPED. They are children of the page node, and template:module on one resolves
     this same view and recurses, so a page's snapshot used to contain every page beneath it rather
     than what a visitor sees on it: revisioning /home snapshotted the whole site (permanent OVERSIZE),
     and under the cap, republishing after an unrelated sub-page changed made the comparison show
     text that never appeared on the page (issue #23). A sub-page owns its own history if it opts in;
     RevisionEntryBinder skips PAGE_TYPE for the same reason. --%>
# ${fn:escapeXml(currentNode.displayableName)}<%= System.getProperty("line.separator") %><%= System.getProperty("line.separator") %>
<c:forEach items="${currentNode.nodes}" var="child"><c:if test="${not fn:startsWith(child.name, 'j:') and not child.isNodeType('jnt:page')}"><template:module node="${child}" editable="false"/><%= System.getProperty("line.separator") %></c:if></c:forEach>
