#!/usr/bin/env bash
# Pre-flight validation of the UNSIGNED artifacts a release would push to Maven Central.
#
# Maven Central (Sonatype Central Portal) rejects any deployment whose POM is missing name,
# description, url, licenses, developers or scm — but that rejection only happens mid-publish, after
# a deployment has been created. This script validates the exact POMs the build generated, locally,
# so an incomplete submodule POM (or a version that drifted from the rest) fails the `validate` job
# BEFORE the irreversible publish step ever runs.
#
# Checks, over every `com.ditchoom:websocket*` POM in the given Maven repo:
#   1. POM completeness — name, description, url, licenses, developers, scm all present.
#   2. Version consistency — all websocket-family modules share ONE version (guards the
#      "submodule published 1.0.0 while root was 2.0.0" drift).
#
# Usage:
#   .ci/validate-poms.sh <maven-repo-root>
#     CI:    .ci/validate-poms.sh /tmp/maven-local
#     local: ./gradlew publishToMavenLocal -PincrementMajor=true && .ci/validate-poms.sh ~/.m2/repository
set -euo pipefail

REPO="${1:?usage: validate-poms.sh <maven-repo-root>}"
GROUP_DIR="$REPO/com/ditchoom"
[ -d "$GROUP_DIR" ] || { echo "❌ no com/ditchoom artifacts under $REPO"; exit 1; }

REQUIRED=(name description url licenses developers scm)
fail=0
checked=0
declare -A seen_versions=()

while IFS= read -r pom; do
    # layout: <repo>/com/ditchoom/<artifactId>/<version>/<artifactId>-<version>.pom
    version="$(basename "$(dirname "$pom")")"
    artifact="$(basename "$(dirname "$(dirname "$pom")")")"
    # Only the artifacts THIS project publishes — skip any cached dependency POMs (buffer/socket).
    case "$artifact" in
        websocket*) ;;
        *) continue ;;
    esac
    checked=$((checked + 1))

    missing=""
    for tag in "${REQUIRED[@]}"; do
        grep -q "<$tag>" "$pom" || missing="$missing $tag"
    done
    if [ -n "$missing" ]; then
        echo "❌ INCOMPLETE POM  ${pom#"$REPO"/}  — missing:$missing"
        fail=1
    fi
    seen_versions["$version"]=1
done < <(find "$GROUP_DIR" -name "*.pom" | sort)

if [ "$checked" -eq 0 ]; then
    echo "❌ no com.ditchoom:websocket* POMs found under $REPO"
    exit 1
fi

if [ "${#seen_versions[@]}" -gt 1 ]; then
    echo "❌ VERSION DRIFT across websocket modules: ${!seen_versions[*]}"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    echo "❌ POM pre-flight FAILED — this build would be rejected by Maven Central."
    exit 1
fi
echo "✓ POM pre-flight OK — $checked websocket POM(s), all complete, single version: ${!seen_versions[*]}"
