# Content Revision History

A Jahia 8.2 module that gives selected public pages a **customer-facing revision history**:
a curated list of named revisions, each backed by a permanent Markdown snapshot of the page
as it was published.

## Why not JCR versioning

Jahia already versions content on publication, but that mechanism cannot serve a public
revision history, for five independent reasons:

| Blocker | Cause |
|---|---|
| Versions can't be regrouped | A version exists per *publication event*, not per *meaningful change*. Editorial intent isn't in the data. |
| Versions can't be named or described | A JCR version carries `jcr:created` and Jahia's live-tracking label — no author-facing label or summary. |
| Versions are purged | Deliberate: version storage grows unbounded and slows the repository. There is no retention property; purge is a maintenance job. Any retention window is a data-loss window for a public claim. |
| Subtree noise | Publishing a page checkpoints every versioned descendant, so a "history" would be dozens of meaningless rows per change. |
| No editorial gate | A version exists the instant you publish, so a typo fix is indistinguishable from a change of contractual terms. |

Those are symptoms of one category error: JCR versioning is *infrastructure state*
(machine-granular, purgeable, for rollback), whereas a public revision history is *published
content* (curated, named, permanent, reviewed).

## How it works

```
publication ──▶ PublicationSnapshotListener ──▶ SnapshotCaptureJob (async, scheduler)
                (maps published nodes up to                │
                 the nearest jmix:publiclyRevisioned page) │
                                                           ▼
                                        GuestMarkdownFetcher (HTTP loopback, as guest)
                                                           │
                                                           ▼
                                          MarkdownNormalizer (jsoup → Markdown)
                                                           │
                                                           ▼
                                     RevisionSnapshotService (dedupe, store, prune)
                                                           │
                                                           ▼
                                    RevisionEntryBinder (link new entries to the snapshot)
```

1. **Trigger.** `PublicationSnapshotListener` reacts to publication and enqueues a background
   job. It never renders and never writes, so publication latency is untouched.
2. **Fetch as guest.** `GuestMarkdownFetcher` does an HTTP loopback GET of
   `/cms/render/live/<lang><pagePath>.markdown` on `127.0.0.1`, carrying no cookie and no
   credentials. The render therefore resolves to **guest** by construction, so a snapshot is
   only ever "what the public can see". Every snapshot records `crh:capturedBy`.
3. **Normalise.** `MarkdownNormalizer` converts the rendered HTML to Markdown with jsoup,
   breaking text at sentence boundaries (`BreakIterator`, locale-aware) so a one-word edit
   yields a one-line diff.
4. **Store.** `RevisionSnapshotService` hashes the Markdown and stores it only if it differs
   from the previous snapshot.
5. **Bind.** `RevisionEntryBinder` attaches any revision entry that has no snapshot yet to the
   current one, so the editor's description of a change and the evidence for it are joined.

### Why capture is *not* a render filter

The original design captured inside a render filter. That is wrong, and the reasons are worth
recording because the filter approach looks attractive:

- Jahia's HTML output cache short-circuits the render chain, so capture depended on **cache
  state and traffic**. A page or language nobody visited had no history at all — and a gap was
  indistinguishable from "content unchanged", which is the one distinction the feature exists
  to make.
- An anonymous visitor could defeat the cache with `?cb=<random>` and force unlimited captures,
  each doing a full second render plus a system-session write: an unauthenticated DoS and
  unbounded repository growth.
- The render used the **visitor's** session, so an editor's first visit captured content that
  anonymous users may not see — and that snapshot was destined to be published.

## How the editorial and captured halves join

An editor authors a **revision entry** (label, date, summary); the module captures **snapshots**.
Those are two independent streams, and the link between them is what makes a comparison possible.

The reference is stored on the **snapshot** (`crh:entryRefs`), pointing back at the entry — not
on the entry pointing at the snapshot, which is the way round it looks like it should go. The
reason is publication state: a revision entry is editorial content, so writing any property to it
bumps `jcr:lastModified` and jContent shows the page as **modified again seconds after the editor
published it**, every single time. Every revision would then need publishing twice. A snapshot is
system content in `default` that is never published, so binding there is invisible to the
editorial workflow.

Binding runs after any capture attempt that leaves the store consistent with the live page —
`STORED` **and** `UNCHANGED` — because both editorial habits have to work:

| The editor... | Capture reports | What binds |
|---|---|---|
| changes the page and describes the change in one publication | `STORED` | the new entry, to the new snapshot |
| publishes the change first, writes the entry afterwards | `UNCHANGED` (page text did not change) | the new entry, to the snapshot already current |

`RATE_LIMITED`, `FAILED` and the rest deliberately do **not** bind: there the newest snapshot is
known not to reflect the live page, and attaching an entry to content it does not describe would
fabricate evidence. The entry stays unbound, says so on the page, and binds at the next
publication.

Binding is **append-only**. An entry that already has a snapshot is never rebound, or a later
capture would silently rewrite what an existing public revision claims the page used to say.

## The comparison

The list itself sits in a native `<details>` disclosure and **starts closed** (editors override
this per component with `collapsedByDefault`). A history is supporting evidence for the page, not
the page; left open, twenty revisions push the content they describe off the screen. Closing hides
the entries from view, not from the document — they stay in the DOM, so search engines,
find-in-page and assistive technology still reach them, and the disclosure is keyboard operable
with no script and no ARIA of our own. A requested comparison always forces the list open, or the
panel would name two revisions while the list that produced it stayed hidden.

The history carries a **selector**: two dropdowns listing every revision, and a Compare button.
It is a plain `GET` form, so:

- **The comparison is computed and rendered server-side.** A small script upgrades the rendered
  panel into a popup (`popover="auto"`, so Escape and click-outside dismiss it and focus returns
  to where it was); with scripting unavailable the same panel renders inline, complete and
  readable. The script only toggles the visibility of markup the server already produced, so the
  property that matters holds either way: **no client-side code path ever handles snapshot
  content**.
  The `popover` attribute is added by that script rather than written into the markup, because a
  browser keeps `[popover]` hidden until something shows it: shipping it would make the comparison
  invisible without JavaScript rather than merely un-popped.
- **Any two revisions**, not just adjacent ones. "What changed between the version I agreed to and
  today" is almost never a question about consecutive revisions, and that is the question a
  support policy or an advisory actually gets asked.
- The result is a **shareable URL**, and the browser Back button behaves.
- The pair is **normalised chronologically** before diffing, so picking newest-then-oldest gives
  the same answer as oldest-then-newest rather than reporting every addition as a removal.

This replaced one "compare with the previous revision" popup per revision. Those opened instantly
because every adjacent comparison was pre-rendered — which is exactly why they could not answer
about arbitrary pairs: ten revisions have forty-five of them, twenty have a hundred and ninety.
Building one comparison on request is both more capable and cheaper than pre-rendering N−1 of them
on every render.

The selector renders only when there is more than one revision, since a control that cannot do
anything is the dead-control failure (SC 4.1.2) this component has already had once. The form's
action carries a fragment, so submitting moves focus to the result instead of leaving the visitor
at the top of a reloaded page.

**Both selected identifiers are visitor input**, and the service reads with a session that
bypasses ACLs, so both are proven to be entries of the *server-supplied* history node before
anything is read. Without that containment check a crafted value would render an arbitrary node
onto a public page.

Revisions are ordered **newest first by `revisionDate`**, with document order as the tie-breaker
so drag-and-drop still settles same-day revisions. Order used to be purely positional, which was
unkeepable: Content Editor appends a new child at the *end*, i.e. the oldest position, so simply
adding a revision rendered the newest one last with no comparison offered, while its neighbour
compared against the wrong revision. `RevisionEntryOrder` is the single definition, used by both
the rendered list and the comparison, so a control and its result can never disagree.

The comparison renders **side by side**, older revision left and newer right, with column
headings naming each. Both columns come from one diff (`MarkdownDiff.Result.getRows()` is derived
from the same line list the counts are taken from), so the two presentations cannot disagree about
what changed. It is a CSS grid rather than a `<table>`: a table carries row/column semantics but
cannot reflow, and two fixed columns at 320px or 400% zoom force horizontal page scrolling, which
SC 1.4.10 forbids. Below 48rem the columns collapse to one and each row reads as "before" above
"after" — the unified view, reached by layout rather than a second template.

Diffing is line-based over the Markdown, with word-level highlighting inside changed lines.
Because `MarkdownNormalizer` breaks text at sentence boundaries, a one-word edit produces a
one-sentence diff rather than "this whole paragraph changed". Long unchanged runs collapse to a
counted gap. The algorithm is Myers, from the platform's own `difflib` (`diffutils-1.3.0`, which
is exported to modules) — so no dependency is added.

`difflib` also ships a `DiffRowGenerator` that emits HTML directly. It is deliberately unused:
the text being diffed is page content and can contain anything an editor typed, and a value that
mixes generated markup with text-to-be-escaped has no safe rendering. `MarkdownDiff` carries text
only; the view escapes every piece of it.

The panels are built from the `default` workspace with a system session, because snapshots are
never published. That is safe by construction rather than by permission check: the snapshot was
captured over HTTP **as guest**, so it contains only what the visitor reading the comparison could
already see.

The revision-history view still opts out of the HTML cache (`revisionHistory.properties`,
`cache.expiration=0`), now for a different reason: it renders comparisons built from snapshots
under `/sites/<site>/contents`, and Jahia's fragment-dependency tracking does not see those, so a
cached fragment would keep showing an out-of-date history after a new revision was captured.
Restoring caching needs the capture job to flush the page's fragments *after* binding rather than
before it.

## Rich-text summaries

`summary` is a `richtext` field, so it is authored in CKEditor and stored as HTML. It is rendered
**unescaped, but only after** `RichTextSanitizer` — an allow-list clean using the jsoup already
embedded in this bundle for `MarkdownNormalizer`, so the safe path costs no new dependency.

Inline emphasis, links and lists survive. Scripts, event handlers, styles, images, iframes and
`javascript:`/`data:` URLs do not. Neither do **headings**: the summary renders inside an entry
that already owns an `<h3>`, so an editor-supplied `<h2>` would break the page's heading
hierarchy — that exclusion is an accessibility decision as much as a security one. Unknown
elements are unwrapped rather than dropped, so the text an editor wrote always survives even when
its markup does not.

## Previewing a snapshot in jContent

`crh:revisionSnapshot` has an html view, and the module ships a **content template** for it
(`src/main/import/repository.xml`), so a snapshot can be selected in jContent and previewed like
any other content.

The template is the part that matters, and it is not obvious: jContent previews a node by asking
for its `displayableNode` and rendering that, and a node is displayable **only when a
`jnt:contentTemplate` declares `j:applyOn` for its type**. Without one, a snapshot returned a null
`displayableNode` and every render URL answered 404 — regardless of views, of `jmix:renderable`,
of `jmix:mainResource`, or of permissions. (`jnt:person` previews in the demo site for exactly
this reason: `dx-base-demo-templates` ships `person-bio-content-template` for it.)

The preview shows the capture metadata — including `crh:capturedBy`, which is always `guest` and
is the guarantee the whole design rests on — and the Markdown itself as **preformatted text**.
Deliberately not rendered as HTML: this module generates Markdown and never parses it, so
rendering would mean adding a parser to turn an archived record back into markup, and any
difference between that parser and the original page would make the preview quietly unfaithful.
The snapshot is the evidence; the preview shows the stored bytes.

Two constraints worth knowing:

- **It resolves for administrators only.** The revision-history tree has ACL inheritance broken
  and grants nobody, so only server administrators (who bypass ACLs) can reach a snapshot at all.
  That is the storage design working as intended, not an oversight — widening it is an ACL
  decision, not a view one.
- **The site's template set must have a `/base` template** (`j:rootTemplatePath`), following the
  convention of the shipped `news` module. A template set without one will not render the preview.

Initial JCR content is imported **once per module version**, so changing `repository.xml` requires
a version bump to take effect.

## Storage layout

```
/sites/<siteKey>/contents/revision-history/     ACL inheritance broken, no grants
    <pageUuid>/                                 crh:snapshotFolder
        <lang>/                                 crh:snapshotFolder + capture status
            <yyyyMMdd'T'HHmmssSSS'Z'-hash8>     crh:revisionSnapshot
```

- **Site-local** so the history travels with site export, backup and migration (`/settings`
  does not), and outlives the pages it describes.
- **`default` workspace only, never published**, and now enforced rather than assumed: the
  snapshot types carry **`jmix:nolive`**, which `JCRPublicationService` honours directly (the
  platform applies it to `jnt:role`, `jnt:permission` and `jnt:component` — types that must not
  exist in live at all). Publishing an ancestor such as `/sites/<site>/contents` therefore cannot
  drag the tree across.

  The comparison never needs them in `live`: it is computed server-side with a system session on
  `default`, so the visitor never reads the repository at all. That is safe by construction rather
  than by permission check — every snapshot was captured over HTTP **as `guest`**, so it can only
  contain what an anonymous visitor could already see.

  Publishing them would be worse than pointless: two permanent copies of the same record with no
  answer to which is authoritative, and an editorial gate these deliberately do not have.

  Work-in-progress (`j:workInProgressStatus`) would also skip publication and was rejected. It is
  an *editorial* signal meaning "not finished yet", shown as a badge in jContent and clearable by
  any editor on any node — so it states something untrue about an immutable record, and does not
  hold. `jmix:nolive` is declared on the type; there is no per-node flag to clear.
- **Keyed on UUID, not path**, so history survives a page rename or move.
- **UTC, fixed-width names** including a content-hash suffix. Local time broke the dedupe
  invariant (`newestHash` relies on lexicographic order being chronological) across DST and
  across cluster nodes in different zones. The hash suffix also makes concurrent captures of
  the same publication compute the same node name, so a duplicate write is a harmless no-op.
- **ACL locked down** at the root: snapshots are the evidentiary basis of a public claim, and
  inheriting `/contents` ACLs would let any site contributor read every historical version of
  every page — and silently rewrite them.

## Content types

| Type | Purpose |
|---|---|
| `jmix:publiclyRevisioned` | Opt-in marker on `jnt:page`. Only tagged pages are captured. |
| `crh:revisionHistory` | The editorial container dropped on a page; holds revision entries. `collapsedByDefault` controls whether the list starts closed. |
| `crh:revisionEntry` | One public revision: label, date, summary, change type. |
| `crh:snapshotFolder` | System container; carries dedupe state and last-capture status. |
| `crh:revisionSnapshot` | One immutable Markdown snapshot; `crh:entryRefs` links it to the revisions it is evidence for. |

`crh:revisionHistory` is declared **`orderable`**. That is not implied by `jmix:list` —
`jnt:contentList` declares it separately for the same reason — and without it Jackrabbit refuses
reordering outright, so editors cannot drag entries into order and the newest-first convention
both views depend on is unachievable.

Snapshot types carry `jmix:hiddenType` so they never appear in the components tree.
They keep `jmix:droppableContent` only because `jnt:contentFolder` accepts no other child
type — it is a structural requirement, not an editorial affordance.

Snapshot properties are `indexed=no` / `nofulltext` so historical copies never pollute site
search, and `crh:markdown` is `binary` so metadata reads don't drag the payload.

## Operational notes

- **Capture is asynchronous.** A snapshot appears shortly after publication, not during it.
- **Rate limited** to 1 capture/second and 60/hour per page+language, with a 1 MB Markdown cap
  and 500 snapshots per page/language (oldest pruned, counted in `crh:prunedCount`).
- **Failures are durable, not silent.** Every non-stored outcome is written to
  `crh:lastCaptureStatus` / `crh:lastCaptureMessage` / `crh:lastCaptureDate`. A gap in the
  history is therefore visible rather than indistinguishable from "nothing changed".
- **The module must be enabled on the site.** A module's views and listeners only apply where
  it is enabled; otherwise the bundle looks healthy while nothing happens.

## Building and deploying

```bash
mvn clean package
# deploy via the Provisioning API (NOT the modules/ drop folder — Felix FileInstall NPEs
# in Util.getBundleKey when Jahia's artifact installer handles the jar)
curl -u root:root -H "Origin: http://localhost:8080" \
  -F "script=@-;type=application/yaml" \
  -F "file=@target/content-revision-history-1.1.0-SNAPSHOT.jar" \
  http://localhost:8080/modules/api/provisioning <<'YAML'
- installBundle: "content-revision-history-1.1.0-SNAPSHOT.jar"
  autoStart: true
YAML
```

Two constraints that cost real debugging time, recorded so they don't again:

- **Do not use `groupId org.jahia.modules`.** On an Enterprise distribution
  `BundleLicenseCheckerListener` demands a valid `Jahia-Signature` from that reserved
  namespace and **uninstalls the bundle a few hundred milliseconds after the install reports
  success** — with the "Invalid license check" ERROR logged *after* the uninstall lines, so
  log order actively misleads.
- **The pom must declare the blueprint extender capability**
  (`osgi.extender=org.jahia.bundles.blueprint.extender.config`). Without it the module gets no
  Spring context and every code extension point is silently inert, while CNDs and views keep
  working perfectly. Nothing is logged.

jsoup is **embedded** in the bundle (`Bundle-ClassPath`), not imported: the platform ships
jsoup but does not export `org.jsoup` to modules.

## Tests

- `mvn test` — unit tests for the Markdown pipeline (the pure, rule-bearing part).
- `tests/` — Docker-based Cypress e2e. See [tests/README.md](tests/README.md).

## Retention

Snapshots are kept indefinitely and are **not** pruned by age. That is deliberate: the record
exists to answer "what did this page say on that date", and a retention window is a window in
which that question stops having an answer. The only bound is
`MAX_SNAPSHOTS_PER_PAGE_LANGUAGE` (500 per page and language); when it is reached the oldest are
dropped and the running total is written to `crh:prunedCount`, so history that was discarded is
visible as discarded rather than indistinguishable from history that never existed.

## Not yet implemented

- **Comparing across languages.** Snapshots are partitioned per language and a comparison is
  always within one of them. The model supports a cross-language pair; only the UI does not offer
  it.
- **Paging the comparison selector.** Every revision is listed in both dropdowns. That is fine for
  the dozens of revisions these pages accumulate and would not be for hundreds.
