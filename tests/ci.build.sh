#!/bin/bash
source ./set-env.sh

if [[ -e ../target ]]; then
  # Clear first: nothing here is wiped otherwise, and the provisioning step deploys EVERY jar it
  # finds, so a jar left from an earlier build installs alongside the current one.
  rm -f ./artifacts/*.jar
  # -sources/-javadoc are excluded deliberately: they are jars, and deploying one as a module
  # gives a bundle with no code and a very confusing failure.
  #
  # NOTE: staging a non-SNAPSHOT jar here does NOT make a release build testable. The pinned
  # @jahia/cypress tooling globs for '*-SNAPSHOT.jar' when it submits files to Jahia, so a
  # release-versioned jar is staged and then silently never installed -- the specs fail on
  # missing preconditions rather than on anything about the module. Verify a release artifact by
  # deploying it directly instead.
  find ../target -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    -exec cp {} ./artifacts/ \;
fi

# 11-backfillScript.cy.ts executes the SHIPPED backfill script, and the test image is built from
# this directory only -- there is no ../src inside the container. Stage it as a fixture, copied at
# build time from the one source of truth: a second copy committed in tests/ would drift, and the
# spec would then prove that the copy runs rather than that the shipped script does.
backfill='../src/main/resources/META-INF/groovyConsole/backfill-revision-snapshots.groovy'
if [[ -e "$backfill" ]]; then
  mkdir -p ./cypress/fixtures
  cp "$backfill" ./cypress/fixtures/
else
  echo "WARNING: $backfill not found; 11-backfillScript.cy.ts will fail on a missing fixture" >&2
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
