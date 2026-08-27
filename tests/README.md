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

**Host ports are shifted to 8081 / 8001.** A local Jahia distribution in this workspace
already owns 8080/8000. Cypress reaches Jahia at `jahia:8080` on the `stack` network, so the
CI path is unaffected; only host-side debugging uses the mapped ports (Jahia at
`http://localhost:8081`, JPDA on 8001).

**The module must be enabled on the site.** `assets/provisioning.yml` enables
`content-revision-history` on `digitall` after the site import. A module's views and render
filters only apply to sites where it is enabled — without this the module still looks healthy
(bundle ACTIVE, node types registered) while every snapshot assertion fails.

**Specs force a cache miss on purpose.** The capture filter runs late in the render chain, and
Jahia's HTML output cache short-circuits that chain, so a cached page produces no capture at
all. `renderLive()` appends a unique query string. Without it the suite would pass vacuously —
asserting "no new snapshot" while the filter never ran. This is a real limitation of the
render-filter trigger, not a test artefact.

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
