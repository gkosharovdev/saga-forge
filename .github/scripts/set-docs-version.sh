#!/bin/sh
# Sets the Saga Forge version the documentation names to X.Y.Z, the latest version on Maven Central.
# Usage: .github/scripts/set-docs-version.sh 0.4.0
#
# Touches only Saga Forge versions, never other dependencies:
#   - Maven snippets: <artifactId>saga-forge-*</artifactId> followed by <version>...</version>
#   - Gradle coordinates: io.github.gkosharovdev:saga-forge-*:VERSION
#   - prose: "Saga Forge N.N.N", "design around in N.N.N", "Limitations to design around (N.N.N)"
# Run docs/guide/build-llms-full.sh afterwards. Called by .github/workflows/release.yml (README "Releasing").
set -eu

version=${1:-}
if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "usage: $0 MAJOR.MINOR.PATCH (got '$version')" >&2
    exit 1
fi

root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"

v='[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?'
for file in README.md saga-forge-spring-boot-starter/README.md saga-forge-test/README.md docs/guide/*.md \
    docs/agents/AGENTS-snippet.md docs/agents/saga-forge/SKILL.md; do
    SAGA_FORGE_VERSION="$version" perl -0pi -e '
        my $new = $ENV{SAGA_FORGE_VERSION};
        s{(<artifactId>saga-forge-[A-Za-z0-9-]+</artifactId>\s*<version>)'"$v"'(</version>)}{$1$new$2}g;
        s{(io\.github\.gkosharovdev:saga-forge-[A-Za-z0-9-]+:)'"$v"'}{$1$new}g;
        s{(Saga Forge )'"$v"'\b}{$1$new}g;
        s{(design around in )'"$v"'\b}{$1$new}g;
        s{(Limitations to design around \()'"$v"'(\))}{$1$new$2}g;
    ' "$file"
done
