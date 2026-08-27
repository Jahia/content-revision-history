<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %><%--
  Rich text. Emitted as-is; the HTML->Markdown normalisation happens in MarkdownRenderer
  so the conversion rules live in one testable place rather than spread across views.
--%>
${currentNode.propertiesAsString['text']}
