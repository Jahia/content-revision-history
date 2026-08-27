<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- Rich text. Emitted as-is; HTML->Markdown conversion lives in MarkdownNormalizer so the
     rules sit in one unit-testable place instead of spread across views. --%>
${currentNode.propertiesAsString['text']}<%= System.getProperty("line.separator") %>
