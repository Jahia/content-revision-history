# Content Revision History 1.4.0 Release Notes

**Last Updated:** 2026-09-01

## Overview

Version 1.4.0 adds per-site configuration, a UI panel for managing those settings, and several security and correctness fixes. The module maintains full backward compatibility: a site with no configuration behaves exactly as 1.3.1 did.

## What's New

### Per-Site Configuration

Capture settings can now differ by site. Each site that needs custom settings gets a configuration file in `karaf/etc`:

```properties
# karaf/etc/org.jahia.modules.revisionhistory.site-mysite.cfg

siteKey                 = mysite        # required: identifies which site this applies to
capture.enabled         = true          # optional; false disables capture for this site only
retention.maxSnapshots  = 500           # optional; per page and language, oldest pruned first
capture.user            = crh-mysite    # optional; account to render as (restricted pages only)
capture.secretFile      = /etc/jahia/crh-mysite.secret  # optional; path to secret file
capture.baseUrl         =               # optional; rarely needed (see critical note below)
```

**Key points:**

- A site with no file behaves exactly as before: uses the module-level settings from `org.jahia.modules.revisionhistory.cfg`.
- Changes apply without a restart: Felix FileInstall delivers the file within seconds.
- The `siteKey` property is validated as a safe path segment; an invalid key is refused.
- A value that violates the schema (e.g., `retention.maxSnapshots = 0` or `-1`) is rejected at the API boundary and never written.

### Per-Site Settings UI Panel

A new panel appears under **Administration > Sites > <site> > Revision history**, where site administrators can view and edit capture settings for their site. Access is controlled by the `siteAdminContentRevisionHistory` permission (granted automatically to site-admin role).

The panel:
- Reads and writes settings through a GraphQL API
- Preserves the capture credential across edits (the secret itself is never readable or writable)
- Validates settings before saving
- Requires `site-admin` role on the site; editors with only `jcr:write` cannot access it

### Capture Configuration Moved to OSGi

The capture endpoint (`capture.baseUrl`) was previously set via a JVM system property (`jahia.crh.captureBaseUrl`). It now lives in `org.jahia.modules.revisionhistory.cfg` alongside the capture principal, so configuring capture means editing one file. Configuration changes take effect without a restart.

### Security: Capture Credential Bound to Loopback

**Critical security fix:** If a capture credential is configured, it is sent **only** to loopback addresses (127.0.0.1, localhost, [::1]). If `capture.baseUrl` points to a non-loopback address, the credential is withheld and capture renders anonymously instead.

This prevents a site administrator from configuring a public hostname and receiving the operator's capture password on every publication. The rendered snapshot is correctly recorded as guest, which reflects what the unauthenticated render actually was.

When capture renders anonymously due to a non-loopback URL, restricted pages report `NOT_PUBLIC` status, which signals the operator to correct the `capture.baseUrl` setting.

### ACL Inheritance Restored

Snapshots now inherit permissions from the parent `/sites/<site>/contents` folder. Earlier versions broke this inheritance to prevent editors from reading snapshots, but that made the folder unreadable to editors who need to read a snapshot to write the revision entry describing it.

**Consequence:** Anyone with read access to `/sites/<site>/contents` can read snapshots. This is intentional. A snapshot captured as a configured `capture.user` may contain content the public cannot see; `crh:capturedBy` records which principal did the rendering. If this is unacceptable for a site, restrict `/sites/<site>/contents` itself.

Existing installations repair themselves the next time a page is published; the snapshot folder's ACLs are restored automatically.

### Bug Fixes

#### Snapshot Preview and Picker Now Browsable

Snapshot types no longer carry `jmix:hiddenType`, so the revision-history tree is browsable in jContent. This allows editors to:
- Preview a snapshot as they would any other content
- Select and re-point revision entries to the correct snapshot when backfilling history

The tree was previously invisible in the content browser, making it impossible to describe backfilled history.

#### System-Written Snapshot Fields Now Protected

All system-written properties on snapshots and snapshot folders (`crh:markdown`, `crh:contentHash`, `crh:capturedBy`, `crh:snapshotDate`, `crh:latestHash`, `crh:latestSnapshot`, etc.) are now marked `hidden` in the CND. Previously they were rendered as editable form fields, allowing editors to alter the immutable evidence record. Snapshots remain browsable and previewable in jContent, but their system-written fields can no longer be edited.

#### Configuration File Format Safety

Fixed several property-file parsing issues:

- **Backslash escaping:** Backslashes are now rejected in property values, preventing a trailing backslash from continuing the value onto the next line and consuming the next setting.
- **Property separators:** Whitespace and colons are now recognized as property separators alongside `=`, preventing `capture.enabled false` from being misinterpreted as an unmanaged setting.
- **Character encoding:** Files are now consistently read and written as ISO-8859-1 (the Java properties file standard), not UTF-8, preventing Latin-1 characters in comments from breaking subsequent saves.

#### Snapshot Retention and Weak References

Fixed a bug where repeatedly adding, publishing, and deleting a revision entry pinned every snapshot forever. The issue was that `crh:entryRefs` are weak references, and the retention check treated any non-empty reference array as protection. A reference is now resolved, and only entries that still exist (or encounter errors) count as protection.

#### Credential Preservation Across Settings Edits

`capture.baseUrl` was not being written when site settings were saved through the panel, even though the panel rendered an input for it. Both the API and the UI are now corrected.

#### Capture Principal Fidelity

Fixed a case where the recorded `crh:capturedBy` principal disagreed with the one that actually rendered the page. The configuration was read twice: once to set the Authorization header and again to record who fetched. A configuration change between those reads would create a false record. Now the principal that was actually sent is carried along with the result.

#### Concurrency Safety

Component singletons are now held in `AtomicReference` rather than plain fields, making configuration changes thread-safe when Quartz workers and FileInstall modify at the same time.

#### Snapshot Deduplication Performance

Removed unnecessary repository calls in logging statements that may not even be written, reducing I/O overhead on high-volume captures.

#### Multi-Valued Properties

Fixed loss of multi-valued properties when reading configuration files.

#### AJP Connector Distinction

Added a check to distinguish the AJP connector from the HTTP one, preventing misdetection of the capture endpoint when both are present.

#### Backfill Script Robustness

The backfill script no longer renders deleted components, and correctly handles pages that were not on the live publication path.

## Upgrading from 1.3.1

### Required Actions

**One, and only if you capture restricted pages.** If you configured a capture principal
(`capture.user` with `capture.secret` or `capture.secretFile`) AND your `capture.baseUrl` points
anywhere other than this node's own loopback connector, the credential is now withheld and the
render is anonymous. Restricted pages will silently begin recording `NOT_PUBLIC` instead of being
captured. Point `capture.baseUrl` at the loopback connector, or remove it and let the port be
detected. This is deliberate — see *Capture credential bound to loopback* above — but it is a
behaviour change, not a no-op.

Everything else upgrades without operator action, with one visible consequence described next.

### What you will see on the first publication after upgrading

The Markdown generator version moves from **4** to **5**, because what a snapshot contains has
changed: the generic fallback view now emits every text-bearing property of an unspecialised content
type, where before it emitted only `jcr:title`. That fixed real content loss — a node holding its
text anywhere other than the title contributed nothing to the record at all — but it means the same
unchanged page composes to different Markdown than it did under 1.3.1.

Two consequences follow, and neither is a fault:

1. **One extra snapshot per revisioned page.** The next publication of each page composes text that
   differs from the stored snapshot, so capture stores a new one even though nothing editorial
   changed. Retention counts it like any other.
2. **A comparison spanning the upgrade shows non-editorial differences.** A diff between a
   generator-4 snapshot and a generator-5 one includes the content the older generator was dropping.
   `crh:generatorVersion` on each snapshot is what tells the two apart, and the backfill script's
   validation gate deliberately refuses to treat a cross-generator comparison as verification.

If the difference matters for a page whose history is a contractual record, note the upgrade date
alongside the revision entries either side of it. Snapshots taken before the upgrade remain readable
and unchanged; nothing is rewritten.

### Recommended Actions

1. **Review per-site capture settings.** If you previously set `jahia.crh.captureBaseUrl` as a JVM property, move that value to `capture.baseUrl` in `karaf/etc/org.jahia.modules.revisionhistory.cfg` and remove the system property. This consolidates all capture configuration in one place.

3. **If you use restricted-page capture,** confirm that your capture account still has the right access. The snapshot folder now inherits permissions from `/sites/<site>/contents`, so editors can read snapshots — which was the goal, but worth confirming on each site.

4. **Enable the pre-commit hook** (once per clone) to prevent credentials from accidentally entering the repository:
   ```bash
   git config core.hooksPath .githooks
   ```

### Backward Compatibility

- Sites with no per-site configuration keep the module-wide behaviour, with the one exception above:
  what a snapshot CONTAINS has changed, so the first publication after upgrading stores a new
  snapshot for any page holding an unspecialised content type.
- The module-level `org.jahia.modules.revisionhistory.cfg` is the fallback for all sites.
- JCR content structure is unchanged; snapshots stored by 1.3.1 are readable as-is.
- All existing revision entries and snapshots remain valid.

## Data Integrity

### ACL Inheritance Change

The snapshot folder's ACL inheritance is now active, whereas 1.3.1 broke it. On upgrade:

- The first time any page on a site is published, the folder's ACLs are restored.
- Editors and administrators with read access to `/sites/<site>/contents` can now read snapshots.
- A snapshot captured as a privileged user may contain content the public cannot see; `crh:capturedBy` records the principal.

If you restrict which editors see snapshot history, restrict `/sites/<site>/contents` itself rather than expecting the snapshot tree to be locked down.

### Retention Behavior

Retention rules (`retention.maxSnapshots`, default 500) are applied consistently and correctly:

- Per page and language (as before).
- Snapshots referenced by revision entries are no longer pinned indefinitely; retention applies normally.
- Pruned snapshots are counted in `crh:prunedCount` on the language folder.

## Known Limitations

- **Cross-language comparison:** Snapshots are partitioned per language; comparisons always occur within one language. The data model supports cross-language pairs, but the UI does not offer it.
- **Comparison paging:** All revisions appear in both selector dropdowns. This works fine for dozens of revisions but would need paging for hundreds.

## Testing

Run the test suite before deploying:

```bash
mvn clean test
```

For end-to-end testing, see `tests/README.md`.

## Security Advisories

### Capture Credential is Loopback-Only

Site administrators can configure per-site settings, including `capture.baseUrl`. A non-loopback value is now properly handled: the operator's capture password is not sent to any host except the node's own loopback connector.

### Pre-Commit Hook for Credentials

The backfill script must be edited with real credentials to run. A hook now refuses any commit carrying credentials (in single or double quotes), preventing accidental commits of secrets.

Enable it:

```bash
git config core.hooksPath .githooks
```

## References

- README.md — full documentation
- CHANGELOG.md — commit history
