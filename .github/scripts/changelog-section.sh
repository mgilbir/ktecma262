#!/usr/bin/env bash
# Prints one version's section of CHANGELOG.md, for use as GitHub release notes.
#
#   .github/scripts/changelog-section.sh 0.1.3 [CHANGELOG.md]
#
# Fails when the section is missing rather than printing nothing. An empty
# result would publish a release page with no notes, which reads as a
# successful release rather than the mistake it is.
set -euo pipefail

version="${1:?usage: changelog-section.sh <version> [changelog]}"
file="${2:-CHANGELOG.md}"

# Drops blank lines before the first paragraph and after the last one, while
# keeping the blank lines between them. Written in awk rather than sed/tac so
# it behaves the same on a macOS runner.
section=$(awk -v want="## $version" '
  $0 == want            { inside = 1; next }
  inside && /^## /      { exit }
  inside {
    if ($0 ~ /^[[:space:]]*$/) { pending++; next }
    while (started && pending > 0) { print ""; pending-- }
    pending = 0; started = 1
    print
  }
' "$file")

if [ -z "$section" ]; then
  echo "::error::No '## $version' section in $file." >&2
  echo "Release notes come from the changelog, so add the section first." >&2
  exit 1
fi

printf '%s\n' "$section"
