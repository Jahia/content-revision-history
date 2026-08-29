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

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
