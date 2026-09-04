Changelog
=========

## Unreleased

### Fixed -- fidelity of the record

* **A literal `<style>`, `<script>`, `<xmp>`, `<noscript>` or `<!--` in a plain string property or
  a title no longer deletes the rest of the page from the snapshot** (#18). Plain strings and titles
  are text and are now escaped before the HTML parser sees them; only rich-text properties are
  parsed as markup. Before, `"Set the <style> attribute here."` followed by a second section stored
  `Set the\n` -- no warning, no flag, and the next capture diffed "identical" against it.
* **Nested tables** render once, inside their cell (#20). `table.select("tr")` was a descendant
  selector, so inner rows were emitted once per enclosing level: about fourfold growth per level,
  duplicated rows in the record, and an `OutOfMemoryError` in the capture job at depth 26.
* **A list item wrapping a block element keeps its bullet** (#22). `<li><p>first</p></li>`, which is
  CKEditor's routine output, produced a bare hyphen, a blank line and unindented text.
* **Quotations, embedded media, table captions, header rows, rules and ordered-list start numbers
  are in the record** (#25). Each used to be dropped, so moving a sentence into a `<blockquote>`,
  pointing an `<iframe>` at a different video or renaming a caption diffed as identical.
* **The comparison panel shows what the archive says** (#27). The normaliser escaped backslashes
  only inside link text while the viewer treated every backslash as an escape and any `**` as a
  delimiter, so `C:\Users\bob` displayed as `C:Usersbob`. Literal `\` and `*` are now escaped
  everywhere, the viewer honours the escapes, and `*italic*` is rendered.
* Generator version bumped to 6. Snapshots written by earlier generators are unchanged; a
  comparison across the boundary is flagged as a formatting change, as before.
### Fixed -- capture trigger

* **A revisioned content node inside a container is captured** (#19). The path memo answered for
  it with the enclosing page's uuid (or "none"), because the container had been memoised first in
  tree order; the block's history was never written, with no status and no log line. A node that
  carries the mixin is now its own owner before the memo is consulted.
* **A capture job cancelled on module stop or orderly shutdown leaves a durable `FAILED` status**
  on every page and language it was to capture, with a message saying to republish (#24). It used
  to be dropped with one INFO line, so the folder kept saying `STORED` and the gap looked like "no
  change". A crash cannot be recorded; everything else now is.
* `PublicationSnapshotListener` has unit tests for the first time (#37): revisioned block in a
  container, on a plain page, revisioned sub-page, plain page, language union, memo reuse, and the
  three cancellation outcomes.
### Fixed -- per-site settings

* **Saving from the panel writes back to the file that configures the site** (#21). The target
  used to be derived from the site key alone, so a site configured from an operator-named file
  (`...site-corp-prod.cfg`) got a second `...site-corp.cfg`, the hand-added `capture.secret` was
  not carried into it, the two files competed across restarts and "Use defaults" removed only the
  new one. The file FileInstall delivered the site from is remembered and rewritten.
* **"Use defaults" takes effect immediately** (#28): the in-memory settings are cleared with the
  file, so the panel's refetch no longer shows the deleted values and a publication in the
  FileInstall window is not captured under them.
* **One site-key rule** (#29): the registry applied a stricter pattern to the file name than the
  capture path applied to the site, so `_intranet` captured normally but could not save settings.
  Both now use the capture rule (`[A-Za-z0-9_-]`, one safe path segment).
* **Validation failures reach the administrator** (#30): a line break or backslash in a value, an
  unusable key, or a file refused unread now surface as GraphQL validation errors with the
  registry's message instead of "Internal Server Error(s) while executing query".
* The "Kept from the previous file" banner is written once, not once per save (#31).
* One retention minimum everywhere (#32): a `retention.maxSnapshots` below 2 in the file is raised
  to 2 with a warning, as `save()` and the mutation already refused it, and the panel's input says
  `min=2`.
* Tests: `carriageReturnIsRefused` now exercises the line-break check rather than the loopback guard
  it was accidentally hitting, and the backslash refusal is tested (#34); `CaptureIdentityTest`
  resets the shared static credential after each test, and the global-principal fallback is
  asserted positively instead of relying on test order (#35).

### Fixed -- revision summaries

* **Site-relative and fragment links in a revision summary keep their `href`** (#26). The
  sanitiser resolved every URL against an empty base URI, which fails for a relative link, so
  jsoup stripped the attribute and "see the <a>policy page</a>" rendered as dead text. Unwrapped
  block elements (`div`, table cells, headings) are now separated by a space instead of running
  together as `onetwo`.

### Tests

* Retention: fixtures where the cap is the binding constraint (10 snapshots, cap 5), where nothing
  is over the cap, and where the repository lists children newest-first -- so the `excess`
  arithmetic, the loop guard and the sort are each actually exercised (#33).
* Capture: coordinate validation is tested through the public write path rather than by reflection
  on the patterns; the snapshot name is built in one place and the collision check is tested
  against a real session mock, including the unreadable case (#36).

### Security

* **The `.markdown` endpoint is gated to opted-in pages** (#46). The markdown views are registered
  on the core `jnt:page`/`jnt:content` types, so installing the module opened an anonymous
  `.markdown` URL on *every* page of every site, and `crh:textProperties` handed a visitor every
  text-bearing property of every guest-readable node -- including properties the page's HTML view
  never displays. Not an ACL bypass, but it crossed "not shown by the template" into "not exposed"
  and broke the promise to change nothing until a page opts in. A `.markdown` render whose page is
  not within a `jmix:publiclyRevisioned` node now answers 404 with no body. Capture is unaffected:
  it only ever fetches `.markdown` for a revisioned page or content node.

### Fixed -- comparison viewer

* **An emphasis span covering more than one sentence is no longer shredded** (#47). The sentence
  splitter masks `[...](...)` links but nothing masked `*...*` / `**...**`, so a two-sentence
  italic had its delimiters split across lines and the viewer showed literal asterisks. Emphasis
  spans are masked like links now (escaped `\*` from #27 is not mistaken for a delimiter).

### Fixed -- durable status

* **A capture failure can always be recorded** (#48). `recordStatus` reached a language guard that
  validated against the site's configured languages while capture is triggered for its active-live
  languages; when they differed the durable FAILED record threw and was swallowed, leaving the gap
  only in the log. The guard now accepts the union of both sets.


* **Content beginning a line with `#`, `- ` or `N. ` is no longer shown as a heading or list item**
  (#44). The normaliser escapes those shapes at the start of content lines (`\#`, `\-`, `2\.`),
  leaving its own headings, list items and fenced code untouched; the viewer already unescapes them.
  Same class as #27, at the other position.

### Fixed -- page snapshots

* **A page's snapshot no longer includes its sub-pages** (#23). Sub-pages are children of the page
  node and the page view recursed into them, so revisioning `/home` snapshotted the whole site
  (a permanent `OVERSIZE`), and under the cap a republish after an unrelated sub-page changed made
  the comparison show text that never appeared on the page. A sub-page owns its own history when
  it opts in.

### Security

* **GHSA-4hvq-2x8x-49w2 -- the 1.4.7 fix held for one request per cache lifetime.**
  `MarkdownContentTypeFilter` ran at priority 98, above Jahia's fragment cache (`CacheFilter`
  16.5), and the render chain stops at the first filter whose `prepare()` returns content -- which
  on a cache hit is the cache filter. So the `text/plain` + `nosniff` headers were set on the cache
  *miss* only; every following anonymous request of the same `.markdown` URL was served as
  `text/html` with the unescaped body. Measured on 8.2.3.2 with 1.4.7 deployed: request 1
  `text/plain`, requests 2 and 3 `text/html`. The filter now runs at priority 5, ahead of every
  filter that can end the chain early. **Treat 1.4.7 as vulnerable.**
* The unit test pins the real bound (`< 16`, the cache) instead of `< 99` (`TemplateScriptFilter`),
  and `12-markdownResponseType` asserts the headers on a sequence of byte-identical requests after
  the publication's asynchronous cache flush has settled -- the single fetches it made before could
  pass against a filter that only worked on a miss, and did.

## [1.4.7](https://github.com/Jahia/content-revision-history/compare/1_4_6...1_4_7) (2026-09-03)

**Security release. Upgrade if this module is deployed at all, whether or not any page uses the
feature.** No migration is needed and no content changes.

### Security

* **GHSA-4hvq-2x8x-49w2 -- stored cross-site scripting via the `.markdown` URL.** The markdown
  views print node content unescaped, which is deliberate: `bigText.jsp` emits the rich-text
  `text` property verbatim so `MarkdownNormalizer` can convert HTML to Markdown in one testable
  place. What made it exploitable was the response *header*. Jahia's `Render` servlet falls back to
  `getDefaultContentType(templateType)` for a template type absent from its injected map -- which
  holds only `csv, ics, json, html, rss, text, vcf, xml, js` -- so `markdown` fell through to
  `text/html; charset=UTF-8`, and every `.markdown` URL was an unescaped HTML document reachable
  with no session. Reproduced on 8.2.3.2 against 1.4.6: an anonymous GET answered `200` with
  `Content-Type: text/html` and `<img src=x onerror=...>` placed in a page title came back
  byte-for-byte intact. **Scope was wider than the module's own feature:** the markdown views are
  registered for the core types `jnt:page`, `jnt:content` and `jnt:bigText`, so deploying this
  module added the surface to every page in the installation -- a site that never enabled revision
  history was exposed anyway. Planting requires editor-tier rights and the victim must open the
  `.markdown` URL, which is not a normal browsing path, so severity is moderate (CVSS 5.4); the URL
  is nonetheless public, linkable and unauthenticated. `MarkdownContentTypeFilter` now declares
  `text/plain; charset=UTF-8` and `X-Content-Type-Options: nosniff` for the whole template type,
  which fixes the surface rather than the four print sites. Escaping was rejected as the fix: it
  would archive `<p>Hello</p>` as its own source instead of converting it to `Hello`, corrupting
  the record the module exists to keep, and it fixes four print sites rather than the class -- the
  fourth was added to a markdown view after the first report. The response *body* is deliberately
  unchanged, because the snapshot has to say what the page said, and the display paths were already
  escaped. Capture is unaffected and was re-tested end to end.
  ([commit](https://github.com/Jahia/content-revision-history/commit/ec59ec5))

### Other changes

* **tests**: `12-markdownResponseType` asserts the response type on a real render, for a revisioned
  page and for a page that never opted in, and asserts the content is still returned verbatim -- so
  a future "fix" that escapes instead of labelling fails loudly. Six unit tests pin the wiring the
  header depends on, including that the filter's priority stays below `TemplateScriptFilter`'s
  final 99.0: above it the filter is registered, reported as active, and never runs, and the only
  symptom would be the header quietly going back to `text/html`.

## [1.4.6](https://github.com/Jahia/content-revision-history/compare/1_4_5...1_4_6) (2026-09-03)

**Recommended for every deployment serving a revision history to the public.** Comparing two
revisions was refused for anyone not logged in, so the module's central feature did not work for
the audience it exists for. No migration is needed.

One behaviour change to be aware of: a visitor's comparison now describes **published** revision
entries. It previously described drafts, so an unpublished label or date change was visible on the
live site.

### Bug fixes

* **diff**: A comparison is authorised in the workspace being rendered. It was authorised in the
  `default` (edit) workspace no matter what was being served, because
  `JCRSessionFactory.getCurrentUserSession()` resolves the current *user* from the thread but
  hard-defaults the *workspace* -- verified in the 8.2.3.2 bytecode, where a null workspace is
  replaced by the literal `"default"` with no inference of the render's own workspace anywhere.
  Jahia does not grant `jcr:read_default` to `guest` (measured: 404 on `/cms/render/default`, 200
  on `/cms/render/live`), so every anonymous visitor was refused with *"One of the selected
  revisions is not part of this history."* on entirely public content, while every editor passed.
  The workspace now comes from the view's own `renderContext`. Two further consequences of the same
  cause are fixed with it: the rendering workspace could never resolve to `live` except by throwing,
  so a live visitor's comparison described draft entries; and the page-level check behind the
  snapshot lookup had the identical defect, failing more quietly as an empty panel.
  ([commit](https://github.com/Jahia/content-revision-history/commit/853e666))

* **backfill**: The shipped Groovy script reconstructs a content node through its own view instead
  of a page's. A `jacademy:kbEntry` keeps its body in an i18n property *on* the node, which a child
  walk never sees, so the snapshot held only the title -- and a later comparison against it would
  have reported the whole body as removed. `reconstruct` now mirrors the three markdown views:
  a page composes heading-then-areas, a self-rendering type is fetched directly, and anything else
  emits its own title, its own text properties, then its children, reading those properties through
  the same `crh:textProperties` the view calls so the two cannot disagree. The same change stops a
  legitimately empty `jmix:list` aborting the whole run: the empty-body guard could not tell an
  empty container from silent content loss, and now skips it.
  ([commit](https://github.com/Jahia/content-revision-history/commit/170d40c))

### Other changes

* **model**: Both component types carry `jmix:structuredContent`, so the editor's "Add content"
  list files them under *Content:Structured* -- beside `jnt:banner`, `jnt:event` and
  `jnt:introduction`, which is what they are -- instead of in the default `nt:base` branch labelled
  "base". Not `jmix:listContent` ("Jahia - Lists"), which holds generic containers like
  `jnt:contentList`: a revision history is a list in its mechanics and a component in its purpose,
  and the picker groups by purpose. The `base` branch disappears from the picker entirely as a
  result. Measured on 8.2.4.0: adding a supertype is not a MAJOR definition change, and both types
  move to the intended branch.
  ([commit](https://github.com/Jahia/content-revision-history/commit/6c70567))

* **tests**: The comparison suite now exercises the public surface as a genuine anonymous visitor.
  Every spec logs in first and `cy.request` carries the session cookie, so 34 tests asserting the
  public page were in fact asserting it as `root` -- which is why the defect above shipped. The new
  tests prove they are anonymous by asserting the edit workspace refuses them, without which a
  guest test that quietly stayed authenticated would pass just as well.

## [1.4.5](https://github.com/Jahia/content-revision-history/compare/1_4_4...1_4_5) (2026-09-02)

Fixes the slot 1.4.4 added: it was typed so tightly that no host view could create it, so edit mode
offered no add button at all. Recommended for anyone who took 1.4.4 to put a revision history on a
content type. No migration is needed and pages are unaffected.

### Bug fixes

* **model**: The `revisionHistory` slot on `jmix:publiclyRevisioned` accepts
  `jmix:droppableContent` rather than `crh:revisionHistory`. Typed as the component itself it could
  not be created: `template:area` creates its area node as a `jnt:contentList`, which a
  component-typed slot rejects, so the area never came into existence and edit mode showed nothing
  to click. This mirrors the `relatedlinks` definition on `jacademy:kbEntry` -- a pattern working in
  production rather than one reasoned out from the taglib documentation: the area node is the list,
  and the component goes inside it constrained by the host view's own `nodeTypes`. Confirmed working
  on a real KB entry. Measured rather than assumed: widening a child definition's required type is
  not a MAJOR definition change, a `jnt:contentList` can be created in the widened slot, and
  `contentTypesAsTree` -- the API Content Editor builds its add list from -- then offers
  `crh:revisionHistory` inside it. The cost, since it is a real loss: the list can hold more than
  one history, and nothing at the definition level now says "exactly one".
  ([commit](https://github.com/Jahia/content-revision-history/commit/dc67332))

### Known limitations

* **Corrected after release.** This entry originally said that `crh:revisionHistory`, carrying no
  category mixin, "does not appear in a GENERIC add-content list". That is wrong. A type with no
  category is not absent from the list: `contentTypesAsTree` puts it in the default `nt:base`
  branch, labelled simply "base". It is findable there, merely filed badly. Both component types
  are grouped properly in the next release. The rest of the note stands: every end-to-end test
  creates content over GraphQL, which bypasses the picker, so nothing here exercises the path an
  editor uses.

## [1.4.4](https://github.com/Jahia/content-revision-history/compare/1_4_3...1_4_4) (2026-09-02)

A revisioned node now has a legal place to put its revision history. Nothing changes for pages,
which already had one, and no migration is needed: an optional child definition can only permit
more.

### Features

* **model**: `jmix:publiclyRevisioned` carries the slot as well as the marker --
  `+ revisionHistory (crh:revisionHistory)`. Ticking "Public revision history" is what should make
  it possible to put the list on the node, and until now it was not: a page has wildcard areas so
  the component can simply be dropped into one, but a structured content type usually cannot.
  `jacademy:kbEntry`, for instance, declares one named child and no wildcard, so it could be
  revisioned and still have nowhere to render its own history. The list has to live INSIDE the
  revisioned node rather than beside it on the page, which is why this is a slot and not a note in
  the documentation: the owner of a history is resolved by walking UP from the component and
  stopping at the first page, so a history in a page area next to a revisioned content node
  resolves to no owner and reports "no snapshot recorded" permanently. Declared on the mixin rather
  than in each consuming module on purpose -- the alternative makes every module owning a
  revisionable type refuse to start unless this one is deployed, a hard dependency for one optional
  feature on one content type. Single and named rather than a wildcard: one node has one
  authoritative public revision history. ([commit](https://github.com/Jahia/content-revision-history/commit/94880cc))

### Other changes

* **test**: 95 -> 96 end-to-end tests. The new case asserts both halves, and the negative control
  is the one that matters: without the mixin the same mutation fails with "No child node definition
  for revisionHistory", so the test cannot pass because some parent happened to allow any child.
  Verified against a running 8.2.4.0 as well -- adding a child definition is a compatible change
  with no MAJOR-change refusal, and existing content is unaffected: on one revisioned page all
  three placements were accepted together (a history in a page area, which is the shape every
  existing install has; a direct child named exactly `revisionHistory`; and a direct child under
  the page's own wildcard), after which publishing captured normally and entries from two different
  histories bound to the same snapshot. ([commit](https://github.com/Jahia/content-revision-history/commit/94880cc))

## [1.4.3](https://github.com/Jahia/content-revision-history/compare/1_4_2...1_4_3) (2026-09-02)

Content that is published and visible without a page of its own can now carry a revision history.
Nothing changes for pages, and no migration is needed: the storage was never page-shaped, it was
always keyed on the marked node's UUID.

### Features

* **model**: `jmix:publiclyRevisioned` extends `jnt:content` as well as `jnt:page`, so a policy
  block reused across pages, or any component with its own editorial lifecycle, is captured on
  publication on the same terms as a page. Widening the mixin was not sufficient on its own: the
  walk that resolves which node owns a history asked "am I a page?" before "am I revisioned?", so a
  revisioned content node was walked straight past, resolved to no owner, and was never captured --
  silently, because resolving to no owner is also the correct answer for content nobody asked to
  revision. That question was answered in three places and two of them answered it differently, so
  with the mixin widened capture would have keyed snapshots on the content node while the
  comparison and the snapshot picker looked for them under the enclosing page, reporting "no
  snapshot recorded" for a history whose snapshots exist and are correct. All three now go through
  one resolver that checks the mixin first. Two behaviours are preserved deliberately: a page that
  is NOT revisioned still ends the search, because a page owns its own content; and the nearest
  owner wins, so a revisioned block inside a revisioned page keeps its own history rather than being
  folded into the page's text. ([commit](https://github.com/Jahia/content-revision-history/commit/d67c4d5))

### Other changes

* **test**: 257 -> 265 unit tests and 94 -> 95 end-to-end. The new unit tests pin the resolution
  order and both preserved behaviours, and are mutation-verified: restoring the previous
  pages-before-mixin order fails exactly the two cases that state the consequences -- content never
  captured, and a component's revisions attached to the whole page's text. The pre-existing test
  that a deeply-nested non-page node maps to its owning page still passes, which is what proves
  widening the mixin did not change how pages resolve. Verified against a running 8.2.4.0 as well:
  the definition change deploys with no MAJOR-change refusal, the mixin applies to a `jnt:bigText`
  under `/sites/<site>/contents`, and publishing it stores a snapshot keyed on its own UUID.
  ([commit](https://github.com/Jahia/content-revision-history/commit/d67c4d5))

## [1.4.2](https://github.com/Jahia/content-revision-history/compare/1_4_1...1_4_2) (2026-09-02)

The comparison rows show the snapshot's Markdown rendered instead of as its own syntax. Display
only: what is compared, and what is stored, are unchanged, so no migration is needed and existing
snapshots read exactly as before.

### Features

* **diff**: The comparison rows render the Markdown rather than printing it. A `##` line reads as a
  heading, `**bold**` as bold, a `- ` item as a bullet, and the normaliser's `\[`/`\]` escapes --
  which are the module's own bookkeeping -- are unescaped. The diff itself is still computed on the
  RAW Markdown and always will be: that is what makes one snapshot comparable to another regardless
  of how either is displayed. Rendering is derived afterwards and carries the word-level changed
  flags across, so it costs no highlighting. `InlineMarkdown` reads only the closed grammar
  `MarkdownNormalizer` emits, so the module still has no Markdown parser and no HTML sink on a
  public page built from captured content; anything unrecognised stays literal text. Links and
  images deliberately keep their literal `[text](href)` form, because collapsing them to their text
  would hide an href change -- same words, new destination, no visible difference -- in a record
  whose purpose is showing what changed. Heading levels are styled with classes and never real
  `<h1>`-`<h6>`, which would splice the snapshot's outline into the host page's and break heading
  order for anyone navigating by headings. ([commit](https://github.com/Jahia/content-revision-history/commit/40c0765))

### Other changes

* **test**: 243 -> 257 unit tests and 93 -> 94 end-to-end. Most of the new unit tests cover one
  thing: rendering deletes characters -- delimiters, escapes, the line prefix -- while the
  word-level segments are positions in the RAW line, so every visible character has to be tracked
  back to where it came from or the highlight lands on the wrong word. Mutation-verified; using the
  visible index instead of the raw offset gives `expected: <policy> but was: <icy>`. The
  pre-existing assertions on `<mark>twelve</mark>` and `<mark>eighteen</mark>` still pass, which is
  what proves rendering did not cost the highlighting. ([commit](https://github.com/Jahia/content-revision-history/commit/40c0765))

## [1.4.1](https://github.com/Jahia/content-revision-history/compare/1_4_0...1_4_1) (2026-09-02)

A host site's theme could decorate the module's own list items. Cosmetic on the revision list and
considerably worse in the comparison panel, which can be hundreds of rows. Recommended for anyone
running 1.4.0 on a site whose theme numbers ordered lists; nothing else changes, and no migration
is needed.

### Bug fixes

* **css**: Stopped a host theme numbering the module's own list items. The Jahia Academy's theme
  carries `.jac-content ol>li:before { content: counters(b,"."); counter-increment: b; ... }`,
  which puts a numbered blue badge on every ordered-list item on the page; this module renders
  inside `.jac-content` and emits two `<ol>`s, so the badge landed on every revision in the list and
  on every row of every comparison. Both lists already set `list-style: none`, which removes the
  NATIVE marker and does nothing about a generated one -- which is why it painted through a
  stylesheet that looks like it should have stopped it. The override names the component root as
  well as the list, deliberately: the host rule is (0,1,3) and the obvious
  `.crh-revision-list > li::before` is only (0,1,2), so it loses and looks exactly like the fix not
  working. `counter-increment` is reset as well as `content`, because the host rule advances a
  counter shared with the page's own ordered lists -- left running, our rows would silently renumber
  a genuine numbered list further down the host page. ([commit](https://github.com/Jahia/content-revision-history/commit/1e3215d))

### Other changes

* **test**: The new end-to-end case asserts what a browser computes rather than what a specificity
  calculation predicts, and injects a control `<ol>` the module does not own to prove the host rule
  is in force -- without it, a silently failed style injection would report `none` everywhere and
  pass for the wrong reason. Verified by mutation: with the selectors weakened to the naive
  one-class form, the suite goes 92/93 with only that test failing, on the real badge content. 93
  end-to-end tests, up from 92. ([commit](https://github.com/Jahia/content-revision-history/commit/1e3215d))

## [1.4.0](https://github.com/Jahia/content-revision-history/compare/1_3_1...1_4_0) (2026-09-02)

Per-site configuration, a settings panel to drive it, and the security work that turned out to be
required once capture became configurable at all. Two defects in this release were found
independently by two reviewers each: a site administrator could point capture at any host and have
its response stored as that site's public revision history, and a page published in two languages
could end up with a stored snapshot whose folder said the capture had failed.

`crh:generatorVersion` moves to `5`, so the first publication after upgrading stores one new
snapshot per revisioned page, and a comparison spanning the upgrade will show content the previous
generator dropped. Neither is a fault. Read *Upgrading from 1.3.1* in
[RELEASE-NOTES-1.4.0.md](RELEASE-NOTES-1.4.0.md) before deploying: one setting is now refused
rather than warned about, and `retention.maxSnapshots = 1` is refused with a floor of 2.

### Features

* **settings**: Per-site capture configuration, backed by a file-backed OSGi factory
  configuration rather than a programmatic one, so it survives a bundle reinstall instead of being
  orphaned under `bundleNN/data/config`. Each site is a `.cfg` in `karaf/etc` that Felix FileInstall
  delivers, written temp-file-then-`ATOMIC_MOVE` so a reader never sees half a file. ([commit](https://github.com/Jahia/content-revision-history/commit/5742e8e))
* **settings**: A site-administration panel to edit it, built from moonstone's own layout and field
  components, with Ctrl+Enter to save. Requires `siteAdminContentRevisionHistory` on the site;
  an editor with `jcr:write` on the same site does not have it. ([commit](https://github.com/Jahia/content-revision-history/commit/c02fc45))
* **graphql**: A site-settings API under one namespaced field, never a flat root field: two bundles
  registering the same global field make the provider refuse the duplicate and the whole schema
  fails to build. ([commit](https://github.com/Jahia/content-revision-history/commit/fb3e4a2))
* **entries**: The snapshot an entry names is now per language, as `crh:pinnedSnapshot`. The value
  names a snapshot inside the per-language folder and those names embed a per-language content hash,
  so one shared value could not express it: pinning while editing in English left the French
  comparison reporting "no snapshot recorded" permanently. A new property rather than an `i18n` flag
  on the old one, because Jahia classes that change as a MAJOR definition change and cancels the
  deployment; `crh:snapshotRef` is still read, so nothing already pinned loses its pin. ([commit](https://github.com/Jahia/content-revision-history/commit/c1b3eb4))

### Bug fixes

* **security**: Refused a capture endpoint that does not address this node. `capture.baseUrl`
  decides which host the server issues its capture GET to, and whatever answers is normalised and
  stored as that site's public revision snapshot -- so a role scoped to one site could obtain an
  arbitrary outbound GET from inside the network plus a forged record of what a page said. The
  class Javadoc claimed "no SSRF surface: the caller cannot influence the host", which was true
  only before the value became site-configurable. A `.cfg` edited by hand is still accepted with a
  warning: that escape hatch is for a server administrator, who already holds every privilege it
  would grant. ([commit](https://github.com/Jahia/content-revision-history/commit/3926e17))
* **security**: Bound the capture credential to loopback. A site administrator could set a public
  hostname and receive the operator's capture password on the next publication. ([commit](https://github.com/Jahia/content-revision-history/commit/cea3012))
* **capture**: Stopped recording one language's failure against another. The per-page catch wrapped
  the whole language loop and then wrote `FAILED` for every language, so a throw on the second
  language of a bilingual page overwrote the first language's already-durable `STORED`: a stored
  snapshot whose folder says the capture failed, which no later capture corrects. ([commit](https://github.com/Jahia/content-revision-history/commit/6286a1c))
* **security**: A site naming its own `capture.user` no longer inherits the module-wide credential
  when its own secret fails to resolve, which inverted the documented rule and captured a
  deliberately narrow site with the broad account. The panel also now reports the account capture
  ACTUALLY uses, so a site inheriting the module-wide one is no longer described as anonymous --
  which invited putting a public revision history on a page holding restricted content. ([commit](https://github.com/Jahia/content-revision-history/commit/be97f7a))
* **security**: A comparison now authorises the enclosing PAGE, not only the history component. The
  component can have its ACL inheritance broken to publish a changelog on a restricted page, and
  everything served underneath it came from the page that is not. ([commit](https://github.com/Jahia/content-revision-history/commit/6286a1c))
* **retention**: Retention refuses to prune a snapshot a revision entry references, and consults
  `live` before believing an entry is gone. An entry deleted in jContent and not published has
  already vanished from `default` while the published revision still cites that snapshot; deleting
  it also deletes `crh:entryRefs`, after which the binder re-attaches that revision to the current
  text. ([commit](https://github.com/Jahia/content-revision-history/commit/ed39c84))
* **markdown**: Refused oversized view output instead of silently truncating it, and a snapshot too
  large to read in full now says so in the comparison popup and the jContent preview. A diff
  computed from a payload missing its tail reports every line past the cut as removed and presents
  that as the record of what changed; a WARN in the log never reached the visitor being shown the
  result. ([commit](https://github.com/Jahia/content-revision-history/commit/843eee0))
* **backfill**: The script no longer aborts on every page that has a revision history component --
  which is every page with captured history, and therefore exactly the page the README says to
  backfill first. It also emits a container's own text properties, matching the generic fallback
  view, and normalises with the locale-aware overload the live path uses. ([commit](https://github.com/Jahia/content-revision-history/commit/8c06a7e))
* **backfill**: The credential is withheld from a non-loopback `BASE_URL`, as the Java path already
  did and as the README already promised. ([commit](https://github.com/Jahia/content-revision-history/commit/9ff55c8))
* **settings**: A field can be cleared. `null` was the only way to say "no value" and was read as
  "leave unchanged", so a mistyped capture endpoint could not be removed at all. ([commit](https://github.com/Jahia/content-revision-history/commit/3926e17))
* **settings**: `save()` no longer drops an operator's comments when rewriting a `.cfg`, and applies
  the write to the in-memory map so the API is read-after-write consistent -- the panel used to snap
  back to the previous values and report the site as unconfigured. ([commit](https://github.com/Jahia/content-revision-history/commit/552e50c))
* **a11y**: The comparison panel's focus ring, state and response ordering. The ring used the accent
  at about 2.2:1 against the popover backdrop it actually sits on (SC 2.4.13); the full-screen
  toggle came back reporting the opposite of the panel's state (SC 4.1.2); the settings panel's
  status region changed only its accessible name, so the change was never announced (SC 4.1.3); and
  overlapping comparisons applied whichever response arrived last. ([commit](https://github.com/Jahia/content-revision-history/commit/6053135))

### Other changes

* **test**: 224 -> 243 unit tests and 67 -> 92 end-to-end. The suite now executes the shipped
  backfill script, which nothing had ever run, and proves the credential guard by observing the
  request on the wire rather than by unit-testing the predicate beside it -- the predicate's own
  test passes with the guard deleted. ([commit](https://github.com/Jahia/content-revision-history/commit/935c212))
* **security**: A pre-commit hook refuses to commit a credential, rather than a comment asking
  nobody to. Enable it once per clone with `git config core.hooksPath .githooks`. ([commit](https://github.com/Jahia/content-revision-history/commit/c0520c4))
* **docs**: Corrected the comments and documentation that no longer described the code, including
  three that could have caused a wrong call about safety -- two claiming the snapshot tree is
  unreadable to contributors, which `restoreInheritance` undoes on every capture, and a README
  paragraph contradicting its own section above it. ([commit](https://github.com/Jahia/content-revision-history/commit/eae7eec))

## [1.3.1](https://github.com/Jahia/content-revision-history/compare/1_3_0...1_3_1) (2026-08-30)

1.3.0 shipped its own headline feature broken. The snapshot store was meant to become browsable in
jContent so that backfilled history could be described, and it was not: the snapshots were invisible,
the folder could not be opened, and the whole tree was readable only by `root`. Recommended for
anyone on 1.3.0.

No migration is needed. An instance carrying the old permissions repairs itself on its next capture.

### Bug fixes

* **jcontent**: Used the filters jContent's content browser actually applies. The types were checked against `includeTypes [jmix:droppableContent, jnt:page, jnt:file]`, which is the content PICKER's filter. The browser lists flat views on `[jmix:editorialContent, jmix:queryContent]` and recurses the tree only into `[jnt:page, jnt:contentFolder, jnt:folder, ...]`, so `crh:snapshotFolder` now extends `jnt:contentFolder` and `crh:revisionSnapshot` carries `jmix:editorialContent`. Measured on the same instance, tree recursion into `revision-history` went from 0 to 1 and snapshots listed in the default view from 0 to 28. ([commit](https://github.com/Jahia/content-revision-history/commit/769c41d))
* **acl**: Stopped breaking ACL inheritance on the history root. It granted nothing, on the stated reasoning that server administrators bypass ACLs -- which is untrue, because a server administrator is a role and a role is delivered through ACL entries, the very thing the break removes. Every non-`root` account was denied, administrators included, which left the snapshot picker offering snapshots its user could not read. The tree now inherits the site's content permissions. The trade is that a contributor who can delete content under `/contents` can delete a snapshot; `jmix:nolive` still keeps the tree out of the live workspace, and a comparison still checks the current user's own rights before serving. ([commit](https://github.com/Jahia/content-revision-history/commit/5e0a588))
* **jcontent**: Made snapshots visible at all, having previously fixed only their folders. 1.3.0 verified the folder against the browse filter and not the snapshots inside it, so it shipped with every snapshot present and none shown -- folders that open onto an empty list. ([commit](https://github.com/Jahia/content-revision-history/commit/d5c62e8))

### Other changes

* **backfill**: The report now lists every snapshot in the folder, oldest first, with its date, its node name, who captured it and which generator produced it. Instants written by the run are marked. The node name is printed beside the date because that is the value the snapshot picker stores, so a listing can be matched against what the edit form offers. Dry runs list every instant rather than the first five. ([commit](https://github.com/Jahia/content-revision-history/commit/d5c62e8))
* **test**: End-to-end coverage now loads jContent in a browser and asserts against its own test hooks, and exercises a non-`root` editor account. Both gaps are why the two defects above shipped: a GraphQL assertion only proves what its author believed the client asks for, and every check that ever ran against this tree used `root`, the one principal for which ACLs are meaningless. The suite is 67 specs, up from 53. ([commit](https://github.com/Jahia/content-revision-history/commit/5e0a588))

## [1.3.0](https://github.com/Jahia/content-revision-history/compare/1_2_1...1_3_0) (2026-08-30)

The backfill script works. It did not in any previous release: reconstructing a page's history
failed on the first run, then on vanity URL nodes, then on almost every page with content. Each
failure was only visible once the one before it was fixed.

Two of the fixes below change what a snapshot CONTAINS, so `crh:generatorVersion` moves to `4`.
Existing snapshots are left exactly as they are, and a comparison that spans the change already
tells the reader some differences may be formatting rather than content.

### Features

* **entries**: A revision entry can now name the snapshot it describes, chosen from a dropdown of that page's captures, newest first, each showing when it was taken and the line that tells it apart. Empty keeps the previous automatic behaviour, which is right when one publication produces one entry and one snapshot. It is wrong for backfilled history, where many snapshots already exist and the entries describing them are written afterwards, so every one of them attached to the newest snapshot and every comparison reported that nothing changed. Changing the choice moves the entry; a choice that no longer resolves leaves it unbound rather than attaching a revision to content it does not describe. ([commit](https://github.com/Jahia/content-revision-history/commit/ab941e7))
* **jcontent**: The snapshot store is browsable and previewable again. Both snapshot types carried `jmix:hiddenType`, which is not only "keep it out of the components list": jContent's browse query lists that mixin in `excludeTypes`, so the whole tree was hidden from the content browser, and a snapshot could not be read before writing the revision entry for it. The record is still protected by the two mechanisms that always protected it, the ACL inheritance break with no grants and `jmix:nolive`. ([commit](https://github.com/Jahia/content-revision-history/commit/ab941e7))

### Bug fixes

* **normalizer**: Kept the line structure the markdown views emit. `toMarkdown` read every text node with jsoup's `TextNode.text()`, which normalises whitespace, so each line separator became a space and only structure carried by an HTML block tag survived. A heading-rich page, which is what a security advisory or a support policy is, collapsed onto one line and lost the sentence-level diff granularity that generating Markdown exists to buy. Text inside rich-text HTML still collapses, where a newline really is only whitespace. ([commit](https://github.com/Jahia/content-revision-history/commit/10c66e8))
* **backfill**: Declined instants at which a frozen node cannot be read at all. `JCRFrozenNodeAsRegular` answers `true` from `hasI18N` and `null` from `getI18N` when the translation subnode has no version at or before the instant, and `LastModifiedInterceptor` guards with the first and dereferences the second, so reading any property threw `NullPointerException` and the render answered HTTP 500. A save checkpoints a node milliseconds before its translation, and the candidate instant landed in that gap. The translation's own checkpoints are now gathered too, and unrenderable instants are declined and reported rather than aborting the run. ([commit](https://github.com/Jahia/content-revision-history/commit/d9fbf4b))
* **backfill**: Rendered only nodes that have a markdown view, and percent-encoded the URL. `jnt:vanityUrls` and `jnt:vanityUrl` extend `nt:base`, not `jnt:content`, so a request for one was never a render request and answered HTTP 401 with a message blaming the credentials. Their names come from the URL and contain spaces, which produced a request Jahia rejects. ([commit](https://github.com/Jahia/content-revision-history/commit/71bf356))
* **backfill**: Survived the first run, when the snapshot folder does not exist yet. ([commit](https://github.com/Jahia/content-revision-history/commit/a6afe51))

### Other changes

* **docs**: Corrected a stale security claim. The README described `crh:capturedBy` as always `guest` and as the guarantee the design rests on. Both stopped being true when capture became configurable so that restricted pages could have a history at all; the CND was corrected at the time and the README was not. `RevisionDiffService#viewerMayReadHistory` is what keeps the read path safe. ([commit](https://github.com/Jahia/content-revision-history/commit/0cd2333))

## [1.2.1](https://github.com/Jahia/content-revision-history/compare/1_2_0...1_2_1) (2026-08-30)

Fixes an undeclared module dependency that would break the snapshot preview template on any
install without the grid module.

### Bug fixes

* **import**: Removed `jnt:row` from the snapshot preview template, which the grid module provides while `jahia-depends` declared only `default`. The row was a single full-width column wrapping one element, so inlining `jnt:mainResourceDisplay` leaves only platform core types and removes the dependency rather than declaring it. ([commit](https://github.com/Jahia/content-revision-history/commit/1a9f30e))
* **import**: Stopped packaging `repository.xml.generated` into `import.zip`. It is gitignored and does not regenerate; Maven swept it in because it globs `src/main/import/`, so it shipped in 1.1.0 and 1.2.0. ([commit](https://github.com/Jahia/content-revision-history/commit/1a9f30e))

### Other changes

* **docs**: Added this changelog, in the CKEditor 5 format. ([commit](https://github.com/Jahia/content-revision-history/commit/cbaae7d))

## [1.2.0](https://github.com/Jahia/content-revision-history/compare/1_1_0...1_2_0) (2026-08-30)

Prefer this release over 1.1.0 for a fresh deployment: it is the first build whose manifest does
not carry the absolute path of the machine that produced it.

### Features

* **icons**: Added a module mark and node type icons for `crh:revisionHistory`, `crh:revisionEntry` and `crh:revisionSnapshot`, so the components no longer appear with the platform default in jContent. SVG sources ship beside the 16x16 and 48x48 PNGs, because jContent hardcodes the `.png` extension when it builds a node type's icon URL. ([commit](https://github.com/Jahia/content-revision-history/commit/1a4e2b0))

### Bug fixes

* **build**: Removed the build machine's absolute path from the shipped manifest. `Jahia-Source-Folders` was interpolated from `${sourcesRoot}` and named a directory that exists on no customer install. ([commit](https://github.com/Jahia/content-revision-history/commit/05a5eb4))
* **build**: Pointed the source control headers at this repository instead of the one inherited from the Jahia parent pom, which advertised Jahia's internal repository as the module's source. ([commit](https://github.com/Jahia/content-revision-history/commit/05a5eb4))
* **views**: Escaped the resource bundle key built from `changeType`, so the rendered output no longer depends on a CND value constraint holding. Closes [#52](https://github.com/Jahia/content-revision-history/issues/52). ([commit](https://github.com/Jahia/content-revision-history/commit/b52beeb))
* **definitions**: Corrected the `crh:capturedBy` comment in the CND, which still stated the value is always `guest` and used that to argue a snapshot is safe to show publicly. Neither has been true since capture became configurable. Closes [#53](https://github.com/Jahia/content-revision-history/issues/53). ([commit](https://github.com/Jahia/content-revision-history/commit/b52beeb))

### Other changes

* **docs**: Added requirements, installation and usage sections to the README for the store listing, covering the three install routes and the four steps of recording a revision. ([commit](https://github.com/Jahia/content-revision-history/commit/b1bea69))

## [1.1.0](https://github.com/Jahia/content-revision-history/releases/tag/1_1_0) (2026-08-29)

### Features

* Initial release. Publication-triggered Markdown snapshots of pages carrying `jmix:publiclyRevisioned`, editor-curated revision entries, and a side-by-side comparison between any two revisions. Optional technical capture principal so pages the public cannot read can have a history, with the comparison enforcing the viewer's own JCR rights before any snapshot is read.
