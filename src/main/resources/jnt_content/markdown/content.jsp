<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%@ taglib prefix="crh" uri="http://www.jahia.org/content-revision-history/functions" %>
<%--
  GENERIC FALLBACK. Jahia resolves views up the node-type hierarchy, so every unspecialised type
  lands here rather than rendering empty; an empty render is silent content loss in a record meant
  to be authoritative.

  It used to emit jcr:title and then recurse, and nothing else. That made the fallback a fallback in
  name only: any node holding its text in some OTHER property rendered COMPLETELY empty. Measured on
  a real advisory page, a leaf carrying 388 characters of stored text produced nothing, every instant
  of a backfill composed to the page heading alone, and the run stored a single snapshot for a page
  that had changed five times. Live capture had the identical hole.

  crh:textProperties returns every text-bearing property, so an unspecialised type now contributes
  its content. Emitting slightly too much is recoverable by specialising a view for that type;
  emitting nothing loses the record and looks like success. The function also logs a WARN for a node
  that yields neither text nor children, which is the loud fall-through this design always required.
--%>
<%-- Titles and plain strings are TEXT and are escaped so the HTML parser reads them as text:
     a literal <style> or <!-- in a title otherwise swallows the rest of the page (issue #18).
     Rich-text properties are the only markup, and crh:textProperties passes those through raw. --%>
<c:if test="${not empty currentNode.propertiesAsString['jcr:title']}">## ${fn:escapeXml(currentNode.propertiesAsString['jcr:title'])}<%= System.getProperty("line.separator") %><%= System.getProperty("line.separator") %></c:if>
<c:forEach items="${crh:textProperties(currentNode)}" var="value">${value}<%= System.getProperty("line.separator") %><%= System.getProperty("line.separator") %></c:forEach>
<c:forEach items="${currentNode.nodes}" var="child"><c:if test="${not fn:startsWith(child.name, 'j:')}"><template:module node="${child}" editable="false"/><%= System.getProperty("line.separator") %></c:if></c:forEach>
