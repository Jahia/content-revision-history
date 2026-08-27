<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%-- GENERIC FALLBACK. Jahia resolves views up the node-type hierarchy, so every
     unspecialised type lands here rather than rendering empty -- an empty render would be
     silent content loss in a record meant to be authoritative. --%>
<c:if test="${not empty currentNode.propertiesAsString['jcr:title']}">## ${currentNode.propertiesAsString['jcr:title']}<%= System.getProperty("line.separator") %><%= System.getProperty("line.separator") %></c:if>
<c:forEach items="${currentNode.nodes}" var="child"><c:if test="${not fn:startsWith(child.name, 'j:')}"><template:module node="${child}" editable="false"/><%= System.getProperty("line.separator") %></c:if></c:forEach>
