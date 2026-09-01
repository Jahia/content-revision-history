# Content Revision History

A Jahia 8.2 module that gives selected public pages a **customer-facing revision history**:
a curated list of named revisions, each backed by a permanent Markdown snapshot of the page
as it was published.

## Requirements

| | |
|---|---|
| Jahia | 8.2.3.2 or later |
| Java | 11 |
| Dependencies | none beyond the platform: no third-party libraries, no external service |

The module works on any site. It changes nothing until you switch it on for a specific page, so
installing it on a shared instance is safe.

## Installing

**From the store**: install it like any other module, then enable it on the sites that need it in
**Administration > Modules**.

**From a jar**: deploy through **Administration > Modules > Upload a module**, or through the
provisioning API:

```bash
curl -u <user>:<secret> -X POST https://<your-jahia>/modules/api/provisioning \
  -F 'script=@install.yml;type=text/yaml' \
  -F 'file=@content-revision-history-1.1.0.jar'
```

```yaml
# install.yml
- installModule: "content-revision-history-1.1.0.jar"
  autoStart: true
```

Do **not** drop the jar into the `digital-factory-data/modules/` folder. Felix FileInstall throws
an NPE there because Jahia's artifact installer returns no bundle to checksum, and the module
appears to install and then does not.

Nothing else is required. The module creates its own storage the first time a page is captured,
and ships a documented default configuration that needs no editing for public pages.

## Using it

Four steps, all in the editor UI.

### 1. Turn history on for a page

Open the page in Content Editor and enable **Public revision history**. Nothing is captured for a
page until you do this, which is deliberate: a site with thousands of pages should not accumulate
snapshots for all of them.

### 2. Add the revision list to the page

Drop the **Revision history** component wherever the list should appear, usually near the foot of
the page. It renders nothing until the first revision is recorded, so you can place it before you
have anything to show.

Two options on the component:

- **Heading shown above the revision list** - optional; the default is "Revision history".
- **Start with the revision list collapsed** - on by default. Visitors see one line they can
  expand. The revisions stay in the page either way, so collapsing hides them from view but not
  from search engines or screen readers.

### 3. Publish the page

Publishing is what captures a snapshot: the module renders the page exactly as a visitor sees it,
converts it to Markdown and stores it. This happens in the background, so publishing is no slower
than usual.

Publishing again with no change to the text stores nothing new. Snapshots are deduplicated on
content, not on how often you publish, so routine republishing costs nothing.

### 4. Record a revision

A snapshot on its own is not shown to anyone. What visitors see is a **Revision** you author
inside the Revision history component:

| Field | What it is for |
|---|---|
| **Version label** | free text - `1.2`, `March 2026`, `Effective 1 April`. Whatever convention the page needs. |
| **Revision date** | the date the change became visible, not the date you drafted it. |
| **What changed** | rich text, shown to visitors. This is the part people actually read. |
| **Change type** | Editorial, Substantive or Correction. Editorial means wording only; Substantive means the meaning changed; Correction fixes an error in an earlier revision. |

Publish the revision and it appears in the list.

**Only revisions you write are shown.** Snapshots are captured on every publication, but the
public list contains exactly the entries you chose to author. That is what lets a run of typo
fixes sit between two milestones without cluttering the history: comparing the two milestones
shows the whole difference, and the intermediate publications stay invisible.

### What visitors get

A dated list of revisions, newest first, each with its label and your description of what changed.
Selecting any two and pressing **Compare** opens a side-by-side view of the page text as it stood
at each point, with the changed words highlighted.

Comparison works between any two revisions, not just consecutive ones, which is the question
people usually have: *what changed between the version I read in March and today?*

### Pages the public cannot read

By default the module captures pages anonymously, so a page a visitor cannot open is never
captured and its history stays empty. To give restricted pages a history, name an account for the
module to render as - see [Who capture renders as](#who-capture-renders-as) below, and read the
limitation stated there before enabling it.

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

## Binding

An editor's `crh:revisionEntry` is joined to a snapshot by binding. There are two ways it happens,
and the second exists because the first cannot describe history that already happened.

### Automatic, which is right for the normal rhythm

Every entry on the page that is not yet bound is attached to the snapshot captured for the
publication that carried it. One publication produces one entry and one snapshot, and they belong
together. Leave `crh:snapshotRef` empty and this is what you get.

### Chosen, which is what backfilled history needs

Reconstructing history produces many snapshots at once, and the entries describing them can only
be written afterwards. Left automatic, every one of them would attach to the *newest* snapshot and
every comparison between them would report that nothing changed.

So a revision entry has an optional **Snapshot this revision describes** field. It lists that
page's snapshots for the language being edited, newest first, each showing when it was captured
and the first line that distinguishes it:

```
2026-08-28 18:15:31 — Post-backfill live capture check.
2026-08-28 12:51:08 — Jahia provides support for supported releases for eighteen …
2026-08-28 12:45:02 — Jahia provides support for supported releases for eighteen …
```

The date is shown to the second because captures within one publication land milliseconds apart,
and the excerpt skips the page's own `# Title` line, which every snapshot of a page shares.

Three rules worth knowing:

- **Changing the choice moves the entry.** Binding is otherwise append-only, because a later
  *capture* must never rewrite what an existing revision claims the page said. An editor
  re-pointing an entry is the opposite: a deliberate correction, and history assembled by hand
  after a backfill is exactly where a wrong choice is most likely.
- **A choice that no longer resolves leaves the entry unbound**, and says so in the log. It does
  not quietly fall back to the current snapshot, which would attach a revision to content it does
  not describe.
- **Only that page and language.** The value is a name resolved inside the page's own snapshot
  folder, never a path, so it cannot reach another page's history.

### One case where automatic binding misleads

**Where it misleads.** If captures stop for a while — a page that is not publicly readable, a
run of `FAILED` captures, sustained rate limiting, or the component being added to a page that
already has entries — several entries accumulate unbound. The first capture that succeeds then
binds *all* of them to that single snapshot.

Comparing two such entries resolves both to the same content, so the panel reports:

> These two revisions have identical page content. The revision was recorded, but nothing in the
> text of the page changed between them.

For revisions that did in fact change. Binding never re-runs, so this does not correct itself.

**Automatic binding is not going to guess its way out of this.** The alternatives were binding
only the newest entry — leaving the others reporting "no snapshot recorded" permanently — or
matching each entry to the snapshot nearest its date, which means inventing and maintaining a rule
for "nearest". Both were judged worse than a documented caveat for a situation that only arises
*after* captures have already been failing, which `crh:lastCaptureStatus` records on the folder.

If a page's history matters and its captures have been failing, check that field before trusting a
comparison across the outage — and **fix it by choosing the snapshot** on the affected entries,
which moves them off the one they were lumped onto.

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

The snapshot store is browsable on purpose: describing backfilled history means reading a snapshot
before writing the revision entry for it. It used to be invisible, because both snapshot types
carried `jmix:hiddenType` — which is not merely "keep it out of the components list", since
jContent's browse query lists that mixin in `excludeTypes` and so hid the whole tree from the
content browser too. That was the one thing making backfilled history undescribable, so it was
dropped.

What protects the record now is narrower, and worth stating plainly: `jmix:nolive` keeps the tree
out of the live workspace entirely, and a comparison served to a visitor still checks the *current*
user's own JCR rights before any snapshot is read (`RevisionDiffService#viewerMayReadHistory`).
The tree is no longer locked down: anyone who may delete content under `/sites/<siteKey>/contents`
may delete a snapshot. That is a deliberate trade for a store editors can actually read — the
alternative left it readable only by `root`, which made the picker unusable.

The template is the part that matters, and it is not obvious: jContent previews a node by asking
for its `displayableNode` and rendering that, and a node is displayable **only when a
`jnt:contentTemplate` declares `j:applyOn` for its type**. Without one, a snapshot returned a null
`displayableNode` and every render URL answered 404 — regardless of views, of `jmix:renderable`,
of `jmix:mainResource`, or of permissions. (`jnt:person` previews in the demo site for exactly
this reason: `dx-base-demo-templates` ships `person-bio-content-template` for it.)

The preview shows the capture metadata — including `crh:capturedBy`, the principal the render ran
as — and the Markdown itself as **preformatted text**. Read `crh:capturedBy` as *provenance*: whose
view of the page this text represents, and therefore who may safely be shown it. It is `guest`
unless a deployment configures a capture user. An earlier version of this section called it "always
`guest`" and treated that as the guarantee the design rests on; that stopped being true when
capture became configurable so that restricted pages could have a history at all, and it was never
what kept the read path safe. `RevisionDiffService#viewerMayReadHistory` is, by checking the
current user's own JCR rights before any snapshot is read.
Deliberately not rendered as HTML: this module generates Markdown and never parses it, so
rendering would mean adding a parser to turn an archived record back into markup, and any
difference between that parser and the original page would make the preview quietly unfaithful.
The snapshot is the evidence; the preview shows the stored bytes.

Two constraints worth knowing:

- **It resolves for whoever may read the site's content.** The revision-history tree inherits from
  `/sites/<siteKey>/contents`, so an editor who can read content there can read a snapshot. Up to
  1.3.x the tree had ACL inheritance broken with nothing granted, which the code justified by saying
  server administrators bypass ACLs. They do not: a server administrator is a *role*, and a role is
  delivered through ACL entries — the very thing breaking inheritance removes. Measured on 8.2.x,
  every non-`root` account was denied, administrators included, which made the snapshot picker
  useless because an editor could not read what it offered.
- **The site's template set must have a `/base` template** (`j:rootTemplatePath`), following the
  convention of the shipped `news` module. A template set without one will not render the preview.

Initial JCR content is imported **once per module version**, so changing `repository.xml` requires
a version bump to take effect.

## Per-site configuration

Three settings can differ per site. Each site that needs its own gets one file in
`karaf/etc`, and a site with no file behaves exactly as the module did before per-site settings
existed — an upgrade must not change what gets captured.

```properties
# karaf/etc/org.jahia.modules.revisionhistory.site-academy.cfg

siteKey                 = academy      # required: what says which site this file is for
capture.enabled         = true         # false stops capture without touching any page
retention.maxSnapshots  = 500          # per page and language, oldest pruned first
capture.user            = crh-academy  # optional; the account capture renders as
capture.secretFile      = /etc/jahia/crh-academy.secret
capture.baseUrl         =              # rarely needed; see below
```

Changes apply without a restart: Felix FileInstall delivers the file to the module in a few
seconds.

### `capture.baseUrl`, and why it is almost never what you want per site

It can be set per site, and it usually should not be. It addresses **this node's own HTTP
connector** — not the site's public address. A public host has SEO URL rewriting and usually a
reverse proxy in front, and those rewrite or refuse the `/cms/render/...` paths capture asks for.
The symptom is a flat HTTP 404 on **every** page of that site, whatever its type, its version count,
or the rights of the capture principal, reported as `FAILED`.

There is also no content-correctness reason to reach for the public host. Measured on 8.2.x:

- fetching over loopback puts **no** `127.0.0.1` into a snapshot — site-relative links stay relative
- the same page fetched with `Host: <public name>` and without produces **byte-identical** output,
  so the request host does not change what Jahia generates

An absolute URL *does* appear in a snapshot when an editor authored one. That is the record being
faithful, and the capture endpoint has no bearing on it.

**Critical security note:** A capture credential (`capture.user`/`capture.secretFile`), if configured,
is sent **only** to loopback addresses (127.0.0.1, localhost, [::1]). If `capture.baseUrl` points
elsewhere, the credential is withheld and capture renders anonymously instead. This prevents a site
administrator from receiving the operator's capture password. The rendered snapshot is recorded as
guest, which is what the unauthenticated render will actually have been.

A non-loopback value is accepted — an unusual deployment may genuinely need one — and logged as a
warning naming the site.

### Not per-site at all

**Rate limits** protect the node rather than the site. Several sites each staying under their own
limit could still overwhelm it together, so per-site would be the wrong axis.

### Why a file rather than ConfigurationAdmin

A factory configuration created through `ConfigurationAdmin.createFactoryConfiguration` is
bundle-scoped: it lives under `bundleNN/data/config` and is orphaned or dropped when the bundle is
reinstalled, or when a default `.cfg` later ships for the same factory pid. That loses a site's
configuration on a restart, silently. A file under `karaf/etc` survives reinstalls and is what an
administrator can back up, diff, and keep in configuration management.

The file name is a convention for humans; the `siteKey` **property** is what the module trusts. It
is validated as one safe path segment before it is ever interpolated into a file name.

### Turning capture off for a site

`capture.enabled = false` records `DISABLED` against the page rather than passing over it in
silence. The folder's `crh:lastCaptureStatus` is what makes a gap in the record self-explaining, and
"someone turned this off" is a different answer from "the content did not change".

## Storage layout

```
/sites/<siteKey>/contents/revision-history/     inherits the site's content permissions
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
- **ACL inheritance is deliberately NOT broken.** An earlier design broke it at the root, on the
  reasoning that snapshots are the evidentiary basis of a public claim and contributors should not
  read or rewrite them. That made the folder unreadable to the editors who must read a snapshot in
  order to write the revision entry describing it, so
  `RevisionSnapshotService.restoreInheritance` now restores inheritance on every capture, and
  existing installations repair themselves the next time a page is published.

  The consequence is worth stating plainly: **anyone with read on `/sites/<site>/contents` can read
  every snapshot**, and a snapshot captured as a configured `capture.user` may contain content the
  public cannot see. `crh:capturedBy` records which case a given snapshot is. If that is not
  acceptable for a site, restrict `contents` itself rather than expecting this module to.

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

## Who capture renders as

By default, nobody: capture renders anonymously, which is correct for a site whose revisioned
pages are public, and is what an installation keeps without configuring anything.

Set a **capture principal** to give *restricted* pages a revision history. An anonymous render of
a page guest may not read returns 403, so without one such a page is never captured at all —
there is no partial answer, just no history.

Configuration lives in `karaf/etc/org.jahia.modules.revisionhistory.cfg` (the module ships a
documented default):

```properties
capture.user       = crh-capture
capture.secretFile = /etc/jahia/crh-capture.secret
```

`capture.secretFile` names a file whose first non-blank line is the secret; prefer it to
`capture.secret`, because a file's permissions decide who can read it. A half-finished
configuration — a user with no secret, an unreadable or empty secret file — leaves capture
**anonymous** and logs an error. It never becomes a credential with an empty password, and it
never silently looks configured. Changing the file takes effect without a restart.

### What a capture principal costs you

**Give the account the narrowest access that covers the revisioned pages, and nothing else.** A
snapshot is flattened to text by whoever captured it, so its content is whatever that account
could see, permanently, for everyone later allowed to read the history.

Two consequences follow, and the second is a real limit rather than a caveat:

1. **The comparison enforces the viewer's own JCR rights.** `RevisionDiffService` reads snapshots
   with a system session that bypasses ACLs, so before it reads anything it checks that the
   current user can read the revision history in their *own* session. It fails closed, and a
   denial is reported identically to a history that does not exist, so the page cannot be used to
   probe which identifiers are real.

2. **Component-level ACLs inside a revisioned page are not reflected per viewer.** A snapshot is
   one artifact with one visibility; JCR permissions are per-node and per-viewer. A live render
   resolves access per request, so two users can legitimately see different pages at one URL. A
   historical record cannot: it was flattened at capture time by one principal, and no read-time
   check recovers structure discarded at write time.

   So a viewer entitled to the page sees everything the capture principal put in the snapshot,
   including a paragraph they could not read on the live page. Placing a revision history on a
   page whose components have different audiences is therefore an administrative decision. If
   that matters for a given page, do not revision it.

When a principal is configured, a 401/403 from the render is recorded as `FAILED` rather than
`NOT_PUBLIC`, with a message naming the setting to check — an operator who named an account so
that restricted pages *could* be captured is looking at a misconfiguration, not at a page working
as intended.

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
- **A module that uses Spring must declare the blueprint extender capability**
  (`osgi.extender=org.jahia.bundles.blueprint.extender.config`). Without it the module gets no
  Spring context and every Spring-based extension point is silently inert, while CNDs and views
  keep working perfectly. Nothing is logged.

  **This module does not declare it, deliberately.** It has no Spring context: its code extension
  points are Declarative Services components, and `maven-bundle-plugin`'s `_dsannotations` adds
  the `osgi.extender=osgi.component` requirement that DS needs. The note is kept because the
  symptom is so hard to diagnose from the outside, and because a future Spring-based extension
  point here would need the capability added.

jsoup is **embedded** in the bundle (`Bundle-ClassPath`), not imported: the platform ships
jsoup but does not export `org.jsoup` to modules.

## Before you commit

Enable the repository's hook once per clone:

```bash
git config core.hooksPath .githooks
```

It refuses a commit that would carry a credential into the repository. The backfill script has to
be edited with a real account and password to run, and a later `git add` of that same file for an
unrelated change has already swept such an edit into a public commit once. The script's own comment
saying "there is deliberately NO default" sat three lines above the value that got committed, so a
comment is not enough. The hook inspects staged content, so fixing the working tree afterwards does
not satisfy it.

If you need to keep your local edit, stage the rest with `git add -p` rather than the whole file.

## Tests

- `mvn test` — unit tests for the Markdown pipeline (the pure, rule-bearing part).
- `tests/` — Docker-based Cypress e2e. See [tests/README.md](tests/README.md).

## Backfilling pages that predate the module

`src/main/resources/META-INF/groovyConsole/backfill-revision-snapshots.groovy` reconstructs
snapshots for pages that already existed, from JCR version storage. Run it from **Tools > Groovy
console**; it is a one-shot migration, not a feature, so it ships as a script rather than as
module surface.

**How it can work.** `JCRSessionWrapper.setVersionDate(Date)` pins an entire session to an instant:
every node read through it, including nodes reached by walking down from the page, resolves to its
state then. The render chain does *not* do the same — `?v=<millis>` on a URL renders one content
node historically, but a container or a page renders its children at current content. So the
script walks the pinned session for structure and fetches each leaf's own `.markdown?v=` render,
which is the real view rather than a reimplementation of it.

**It validates itself before writing.** It reconstructs every instant for which a real captured
snapshot exists and compares byte for byte, sorting the results into three outcomes: exact,
date-skewed (the rebuilt text matches a *different* snapshot), and unexplained. It aborts on
unexplained unless `ALLOW_UNEXPLAINED` is set, because a migration that writes a subtly wrong
record is worse than one that refuses to run.

That gate cannot fire on a page with no snapshots at all — which is exactly the page you want to
backfill. Run it first on a page that *does* have captured history to establish that the
composition is faithful for your content types.

**What it cannot do.** Deleted components are unrecoverable: `?v=` needs an addressable node, so a
component removed since then has no current path. Coverage is bounded by version purging. And
`crh:capturedBy` is written as `reconstructed`, never `guest` — live snapshots are captured over
HTTP *as guest*, which is what guarantees they hold only what the public could see, and
retroactive reconstruction has no such guarantee.

Measured on a page with 11 captured snapshots: 57 candidate instants, 10 of 11 reproduced exactly,
16 snapshots written and 41 collapsed by content-hash dedupe. The single difference was not
composition drift but a captured snapshot whose date and content disagree — its neighbour's
capture had been refused by the rate limiter and never stored, so the record carried a later
publication's text under an earlier instant. The reconstruction was right and the stored record
was the inexact one.

The script saves and restores the folder's `crh:latestHash` / `crh:latestSnapshot` pointers around
the run: `captureIfChanged` assumes it is always storing the newest snapshot, and leaving those
aimed at a backfilled instant would make the next live capture compare against the wrong baseline.
Verified by publishing a real change afterwards and confirming the capture was `STORED`.

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
