Changelog
=========

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
