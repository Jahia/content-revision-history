Changelog
=========

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
