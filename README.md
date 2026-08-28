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

Each entry renders a "Compare with the previous revision" link. It is a plain link to the same
page with a `?crhDiff=<entry uuid>` query, so:

- it works with **no JavaScript** — the comparison is computed and rendered server-side, and
  there is no client-side code path that could handle snapshot content unsafely;
- the URL is shareable and the browser Back button behaves;
- the accessible name is the full visible text, naming both revisions and both dates, so a screen
  reader user scanning a list of links gets N distinct names rather than N copies of "Compare".

"Previous" is **positional** — the next sibling in editorial order — matching what the list view
renders. `crh:revisionHistory` is declared `orderable` so editors control that order by
drag-and-drop; keep the list newest-first.

Diffing is line-based over the Markdown, with word-level highlighting inside changed lines.
Because `MarkdownNormalizer` breaks text at sentence boundaries, a one-word edit produces a
one-sentence diff rather than "this whole paragraph changed". Long unchanged runs collapse to a
counted gap. The algorithm is Myers, from the platform's own `difflib` (`diffutils-1.3.0`, which
is exported to modules) — so no dependency is added.

`difflib` also ships a `DiffRowGenerator` that emits HTML directly. It is deliberately unused:
the text being diffed is page content and can contain anything an editor typed, and a value that
mixes generated markup with text-to-be-escaped has no safe rendering. `MarkdownDiff` carries text
only; the view escapes every piece of it.

The panel is served from the `default` workspace with a system session, because snapshots are
never published. That is safe by construction rather than by permission check: the snapshot was
captured over HTTP **as guest**, so it contains only what the visitor asking for the comparison
could already see. The visitor-supplied entry identifier is constrained to entries of the
*rendered* history node — without that containment check, a crafted identifier would have a
system session read an arbitrary node onto a public page.

The revision-history view opts out of the HTML cache (`revisionHistory.properties`,
`cache.expiration=0`): Jahia keys fragments on node, template type, user and locale — not on the
query string — so a cached fragment would serve one visitor's comparison to everyone else.

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

## Storage layout

```
/sites/<siteKey>/contents/revision-history/     ACL inheritance broken, no grants
    <pageUuid>/                                 crh:snapshotFolder
        <lang>/                                 crh:snapshotFolder + capture status
            <yyyyMMdd'T'HHmmssSSS'Z'-hash8>     crh:revisionSnapshot
```

- **Site-local** so the history travels with site export, backup and migration (`/settings`
  does not), and outlives the pages it describes.
- **`default` workspace only, never published** — the diff/read path is server-side, so public
  visibility never required publishing the snapshots.
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
| `crh:revisionHistory` | The editorial container dropped on a page; holds revision entries. |
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
  -F "file=@target/content-revision-history-1.0.0-SNAPSHOT.jar" \
  http://localhost:8080/modules/api/provisioning <<'YAML'
- installBundle: "content-revision-history-1.0.0-SNAPSHOT.jar"
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

## Not yet implemented

- **Retention / erasure policy.** Snapshots are permanent full-text copies of page content.
  Before this stores production data, decide the retention rule and the GDPR erasure path — it is
  cheap now and expensive after go-live.
- **Comparing across languages, or against an arbitrary revision.** The comparison is always
  "this revision versus the one before it", within one language. Snapshots are partitioned per
  language and the model supports any pair; only the UI is fixed.
- **Ordering is a convention, not a constraint.** The views treat the next sibling as the older
  revision. The type is `orderable` so editors can honour that, but nothing enforces it: a list
  ordered oldest-first will compare pairs in the wrong direction. Sorting by `revisionDate`
  instead would need a Java helper, and would then disagree with the order editors see.
