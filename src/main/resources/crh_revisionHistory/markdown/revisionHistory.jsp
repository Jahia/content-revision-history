<%--
  DELIBERATELY EMPTY -- this view exists to render nothing, and removing it would be a bug.

  Without it, Jahia resolves crh:revisionHistory up the type hierarchy to the generic
  jnt_content/markdown/content.jsp fallback, which recurses into children. The revision list
  would then be captured INTO the snapshots, which is self-referential in a way that corrupts
  the whole feature:

    * Publishing a new revision entry changes the page's Markdown (the list gained a row), so
      capture stores a snapshot whose only difference from the previous one is the description
      of the difference. Every diff would show the changelog rather than the change.
    * Each entry's own summary text would appear inside the record it describes, so the
      evidence and the claim about the evidence stop being separable.

  A snapshot is meant to be the page's content, not the page's account of its own history.
--%>
