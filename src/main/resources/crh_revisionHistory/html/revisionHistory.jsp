<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%--
  SPIKE VIEW (Phase 2 validation, not the final public list view).
  Proves that the `markdown` template type composes a whole page subtree via
  template:module's templateType attribute -- no Java, no Spring, no Action.
--%>
<c:set var="target" value="${currentNode.session.getNode('/sites/digitall/home/demo-roles-and-users')}"/>
<h2>Markdown snapshot of ${target.path}</h2>
<pre id="crh-markdown"><template:module node="${target}" templateType="markdown" editable="false"/></pre>
