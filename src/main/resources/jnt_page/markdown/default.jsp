<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %><%--
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
  Markdown template type: page root. Emits an H1 then recurses; <template:module>
  resolves children in the SAME template type, so the whole subtree renders as Markdown.
--%># ${currentNode.displayableName}
<c:forEach items="${currentNode.nodes}" var="child"><c:if test="${not fn:startsWith(child.name, 'j:')}"><template:module node="${child}" editable="false"/></c:if></c:forEach>
