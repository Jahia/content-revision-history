# Content Revision History — E2E tests

Docker-based Cypress harness (`@jahia/cypress`), cloned from the `securitytxt` module and
re-pointed. Standard shape: `docker-compose.yml` brings up `jahia` + `cypress` on a shared
`stack` network, and the `ci.*` / `env.run.sh` scripts delegate to the pinned `@jahia/cypress`
CLI.

## Run the full suite (CI-equivalent)

```bash
cd tests
bash ci.build.sh      # build the test image + stage ../target/*-SNAPSHOT.jar into ./artifacts
bash ci.startup.sh    # boot jahia + cypress, run specs, exit with the suite status
```

Re-run `ci.build.sh` after **any** change under `tests/`, or your change never reaches the
container.

## Develop / debug a single spec

```bash
cd tests
yarn
./ci.startup.sh notests   # boot Jahia only
./env.run.sh              # provision + run headless once
source set-env.sh         # REQUIRED, and again in every new terminal
yarn run e2e:debug
```

## Module-specific notes

**There is no Dockerfile in this directory, by design.** The test image is built by
`@jahia/cypress` from its own `env.Dockerfile` inside `node_modules`
(`ci.build.sh` runs `docker build -f $BASEDIR/env.Dockerfile ... .`), so the base image
(`cypress/browsers:node-…`) is not ours to choose — it moves when the pinned `@jahia/cypress`
version moves. A local `Dockerfile` here would look authoritative while having no effect on
anything, which is exactly why the inherited one was removed.


**Use Yarn Classic locally, not the machine default.** `package.json` declares
`packageManager: yarn@1.22.22`, the test image runs Yarn Classic 1.22.19, and `yarn.lock` is
Classic v1 format. If your global `yarn` is Berry (v2+), running it here **silently rewrites
the lockfile into Berry format**, which Classic then cannot read — it discards it and resolves
fresh, so the lockfile stops pinning anything for the container that actually runs the tests.

Install an isolated Classic binary and use that for every local `yarn` command:

```bash
npm i -g yarn@1.22.22 --prefix /tmp/yarn-classic
/tmp/yarn-classic/bin/yarn install
/tmp/yarn-classic/bin/yarn lint
```

You can tell which format is committed at a glance: Classic starts with
`# yarn lockfile v1`, Berry with `__metadata:`. A healthy container build shows no
`success Saved lockfile` line — if it appears, the lockfile was ignored.


**Host ports are shifted to 8081 / 8001.** A local Jahia distribution in this workspace
already owns 8080/8000. Cypress reaches Jahia at `jahia:8080` on the `stack` network, so the
CI path is unaffected; only host-side debugging uses the mapped ports (Jahia at
`http://localhost:8081`, JPDA on 8001).

**The module must be enabled on the site, and that happens in the spec, not in
provisioning.** A module's views and render filters only apply to sites where it is enabled —
without it the module still looks healthy (bundle ACTIVE, node types registered) while every
snapshot assertion fails. It cannot be done in `assets/provisioning.yml`: the harness installs
the module *after* the manifest runs, so a provisioning `enable` step silently no-ops (the
operation cannot find a module that is not installed yet). The spec's `before()` calls
`enableModule('content-revision-history', 'digitall')` instead.

**Capture is triggered by publication, not by rendering.** There is no capture render filter
any more — `PublicationSnapshotListener` enqueues an asynchronous `SnapshotCaptureJob`, which
fetches the page over HTTP loopback as guest. Rendering a page captures nothing, and the suite
asserts exactly that: anonymous requests with random cache-busting query strings must produce
**zero** snapshots.

Two consequences for writing specs:

- **Poll, don't sleep.** Capture completes some time after the publish call returns, so wait on
  the expected state with `cypress-wait-until` rather than a fixed delay.
- **Pace publications.** Capture is rate limited to one per second per page+language; a publish
  that lands inside that window is recorded `RATE_LIMITED` instead of `STORED`. The
  `publishTriggeringCapture` helper handles the pacing.

The cache-busting that *does* still exist is internal: `GuestMarkdownFetcher` appends its own
`?crhCapture=<publicationTimestamp>` and flushes the fragment cache before fetching, so the
capture cannot race the publication's own asynchronous cache flush.

**Snapshots live in the `default` workspace and are never published**, so all assertions query
`jcr(workspace: EDIT)` and require `cy.login()`.

**Queries target `/sites/digitall/contents`, not `revision-history`.** The history root does
not exist until the first capture, and `nodeByPath` on a missing path is a GraphQL error rather
than a null.

## What is covered

| Spec | Behaviour |
|------|-----------|
| captures a snapshot on first live render after publication | the core capture path |
| stores under `revision-history/<pageUuid>/<lang>/<timestamp>` | layout + UUID keying (survives page rename/move) |
| no second snapshot when content unchanged | content-hash dedupe |
| new snapshot when content changes | change detection |
| heading on its own line | regression: H1 used to fuse onto the first child's text |
| every snapshot carries `contentHash` + `generatorVersion` | dedupe key and formatting-change flag |

Unit tests for the pure Markdown logic live in `src/test/java` and run with `mvn test` —
they cover sentence-level line breaking, entity handling and hash stability.
