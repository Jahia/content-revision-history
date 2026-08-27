<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %><%--
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
  GENERIC FALLBACK for the markdown template type. Jahia resolves views up the node-type
  hierarchy, so every unspecialised type lands here instead of rendering empty -- which
  would be silent content loss in a record that is supposed to be authoritative.
--%><c:if test="${not empty currentNode.propertiesAsString['jcr:title']}">
## ${currentNode.propertiesAsString['jcr:title']}
</c:if>
<c:forEach items="${currentNode.nodes}" var="child"><c:if test="${not fn:startsWith(child.name, 'j:')}"><template:module node="${child}" editable="false"/></c:if></c:forEach>
