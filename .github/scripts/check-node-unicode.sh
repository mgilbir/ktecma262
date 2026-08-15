#!/usr/bin/env bash
# Fails unless the JavaScript engine on PATH carries the same Unicode version as
# the compiled tables.
#
# The differential fuzzer treats node as the oracle, so a version mismatch makes
# every \p{...} case disagree for a reason that has nothing to do with the
# engine. Catching it here turns a wall of confusing failures into one clear
# message.
set -euo pipefail

TABLES=src/commonMain/kotlin/io/github/mgilbir/ecma262/unicode/UnicodeTables.kt

expected=$(sed -n 's/.*UNICODE_VERSION: String = "\([0-9.]*\)".*/\1/p' "$TABLES")
if [ -z "$expected" ]; then
  echo "could not read UNICODE_VERSION from $TABLES" >&2
  exit 1
fi

actual=$(node -p 'process.versions.unicode')
node_version=$(node --version)

# Compare the major version only: node reports "17.0" where the UCD is "17.0.0".
if [ "${expected%%.*}" != "${actual%%.*}" ]; then
  echo "::error::${node_version} reports Unicode ${actual}, but the compiled tables are ${expected}."
  cat >&2 <<EOF

The fuzzer compares against node, so the two must agree on Unicode or every
\\p{...} case will disagree spuriously.

Fix by either:
  - running a node whose Unicode version is ${expected%%.*}.x, or
  - regenerating the tables for Unicode ${actual} (see the Unicode section of
    README.md) and re-verifying them with
    tools/genunicode/verify-against-node.mjs.
EOF
  exit 1
fi

echo "${node_version} carries Unicode ${actual}; tables are ${expected} — match."
