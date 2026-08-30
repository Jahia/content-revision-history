Changelog
=========

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
