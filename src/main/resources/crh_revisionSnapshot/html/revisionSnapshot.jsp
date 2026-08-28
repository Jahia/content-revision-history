<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="template" uri="http://www.jahia.org/tags/templateLib" %>
<%@ taglib prefix="crh" uri="http://www.jahia.org/content-revision-history/functions" %>
<%--
  Default view for crh:revisionSnapshot, so a snapshot can be PREVIEWED in jContent.

  Before this existed a snapshot had no html view at all: jContent could list the node and show
  its properties, but previewing it rendered nothing, and crh:markdown is a binary property so the
  one thing worth looking at -- what the page actually said -- was the one thing not shown.

  The Markdown is rendered as PREFORMATTED TEXT, not converted to HTML. That is deliberate:
    * This module generates Markdown and never parses it. Rendering it would mean adding a
      Markdown parser to turn an archived record back into markup, and any difference between
      that parser and the original page would make the preview quietly unfaithful.
    * The snapshot IS the evidence. An editor checking what a page said on a given date should
      see the stored bytes, not a re-interpretation of them.

  Escaped with <c:out>: the payload is page content captured from the live site and can contain
  anything an editor ever typed, including markup. It has never been sanitised -- it was never
  meant to be rendered as HTML -- so it must not be emitted as any.

  VISIBILITY: the revision-history tree has ACL inheritance broken and grants nobody, so in
  practice only server administrators (who bypass ACLs) can reach this preview. That is the
  storage design working as intended -- snapshots are the evidentiary basis of a public claim and
  site contributors must not be able to read or rewrite them -- and it is a deliberate decision
  rather than an oversight. Widening it is an ACL question, not a view question.
--%>
<template:addResources type="css" resources="revision-history.css"/>

<c:set var="markdown" value="${crh:snapshotMarkdown(currentNode)}"/>

<section class="crh-snapshot-preview" aria-labelledby="crh-snapshot-heading-${currentNode.identifier}">
    <h2 id="crh-snapshot-heading-${currentNode.identifier}">
        <fmt:message key="crh_snapshotPreview.heading"/>
    </h2>

    <dl class="crh-entry-facts">
        <dt><fmt:message key="crh_snapshotPreview.capturedAt"/></dt>
        <dd>
            <fmt:formatDate var="capturedIso" value="${currentNode.properties['crh:snapshotDate'].date.time}" pattern="yyyy-MM-dd'T'HH:mm:ss"/>
            <%-- type="both" is required: without it <fmt:formatDate> formats the DATE only and
                 silently ignores timeStyle. Several snapshots can share a day, so the time is
                 what tells them apart. --%>
            <fmt:formatDate var="capturedHuman" value="${currentNode.properties['crh:snapshotDate'].date.time}" type="both" dateStyle="long" timeStyle="medium"/>
            <time datetime="${capturedIso}"><c:out value="${capturedHuman}"/></time>
        </dd>

        <dt><fmt:message key="crh_snapshotPreview.language"/></dt>
        <dd><c:out value="${currentNode.properties['crh:language'].string}"/></dd>

        <%-- Always "guest". Worth surfacing precisely because it is the guarantee the whole
             capture design rests on: a snapshot is built from what the public can see, never
             from the privileges of whoever triggered it. --%>
        <dt><fmt:message key="crh_snapshotPreview.capturedBy"/></dt>
        <dd><c:out value="${currentNode.properties['crh:capturedBy'].string}"/></dd>

        <dt><fmt:message key="crh_snapshotPreview.generatorVersion"/></dt>
        <dd><c:out value="${currentNode.properties['crh:generatorVersion'].string}"/></dd>

        <dt><fmt:message key="crh_snapshotPreview.contentHash"/></dt>
        <dd><code class="crh-snapshot-hash"><c:out value="${currentNode.properties['crh:contentHash'].string}"/></code></dd>

        <c:if test="${not empty currentNode.properties['crh:sourceUrl']}">
            <dt><fmt:message key="crh_snapshotPreview.sourceUrl"/></dt>
            <%-- Rendered as TEXT, never as a link. It is a loopback capture URL recorded for
                 audit, not a destination anyone should be invited to fetch. --%>
            <dd><code><c:out value="${currentNode.properties['crh:sourceUrl'].string}"/></code></dd>
        </c:if>
    </dl>

    <h3><fmt:message key="crh_snapshotPreview.content"/></h3>
    <c:choose>
        <c:when test="${empty markdown}">
            <p class="crh-diff-notice"><fmt:message key="crh_snapshotPreview.empty"/></p>
        </c:when>
        <c:otherwise>
            <pre class="crh-snapshot-markdown"><c:out value="${markdown}"/></pre>
        </c:otherwise>
    </c:choose>
</section>
