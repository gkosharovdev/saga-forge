#!/bin/sh
# Regenerates llms-full.txt at the repository root from the guide pages, in reading order.
# Run from anywhere: docs/guide/build-llms-full.sh. CI fails when the committed file is out of date.
set -eu
root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/llms-full.txt"
{
    echo "# Saga Forge user guide (all pages)"
    echo
    echo "Generated from docs/guide/*.md by docs/guide/build-llms-full.sh; do not edit by hand."
    echo "Source: https://github.com/gkosharovdev/saga-forge/tree/master/docs/guide"
    for page in README getting-started model-reference validation-rules recipes testing operations limitations; do
        echo
        echo "<!-- docs/guide/$page.md -->"
        echo
        cat "$root/docs/guide/$page.md"
    done
} > "$out"
