#!/usr/bin/env bash
# Fails when a regenerated file differs from the committed one in anything that
# matters.
#
#   .github/scripts/check-regenerated.sh <file> [file...]
#
# Provenance lines are excluded. A generated fixture records which engine
# produced it, which is worth knowing when a mismatch has to be explained years
# later — but it changes whenever CI's node moves to a new patch release, and a
# drift check that fails for that reason is noise. Noise in a nightly is how a
# real signal gets ignored, which is exactly what happened the first night the
# normalisation check ran.
#
# What the exclusion does *not* cover: any recorded hash, count or expectation.
# If node's behaviour actually changed, those move and this still fails.
set -euo pipefail

if [ $# -eq 0 ]; then
  echo "usage: check-regenerated.sh <file> [file...]" >&2
  exit 2
fi

# Lines that record where a file came from rather than what is in it.
PROVENANCE='^(//|\s*//)?\s*(Produced by|internal const val ORACLE)'

status=0
for file in "$@"; do
  if ! committed=$(git show "HEAD:$file" 2>/dev/null); then
    echo "::error::$file is not committed, so there is nothing to compare against."
    status=1
    continue
  fi
  if diff -q \
      <(printf '%s\n' "$committed" | grep -Ev "$PROVENANCE") \
      <(grep -Ev "$PROVENANCE" "$file") > /dev/null; then
    echo "  unchanged: $file"
  else
    echo "::error::$file changed when regenerated from upstream."
    diff -u \
      <(printf '%s\n' "$committed" | grep -Ev "$PROVENANCE") \
      <(grep -Ev "$PROVENANCE" "$file") | head -40 || true
    status=1
  fi
done

if [ $status -eq 0 ]; then
  echo "All regenerated files match what is committed."
fi
exit $status
