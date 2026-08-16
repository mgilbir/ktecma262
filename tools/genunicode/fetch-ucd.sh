#!/usr/bin/env bash
# Downloads the Unicode Character Database files the table generator needs.
#
#   tools/genunicode/fetch-ucd.sh <target-dir> [version]
#
# The version must match the Unicode version of the JavaScript engine used as a
# differential-testing oracle, otherwise \p{...} results legitimately disagree.
# Check node's with: node -p process.versions.unicode
set -euo pipefail

DIR="${1:?usage: fetch-ucd.sh <target-dir> [version]}"
VERSION="${2:-17.0.0}"
BASE="https://www.unicode.org/Public/${VERSION}/ucd"

FILES=(
  UnicodeData.txt
  Scripts.txt
  ScriptExtensions.txt
  PropList.txt
  DerivedCoreProperties.txt
  DerivedNormalizationProps.txt
  NormalizationTest.txt
  CaseFolding.txt
  PropertyAliases.txt
  PropertyValueAliases.txt
  extracted/DerivedGeneralCategory.txt
  extracted/DerivedBinaryProperties.txt
  emoji/emoji-data.txt
)

# The `v` flag's properties of strings come from the UTS #51 emoji files, which
# are versioned separately from the UCD and live under /Public/emoji/.
EMOJI_FILES=(emoji-sequences.txt emoji-zwj-sequences.txt)
EMOJI_BASE="https://www.unicode.org/Public/emoji/latest"

mkdir -p "$DIR/extracted" "$DIR/emoji"
for f in "${FILES[@]}"; do
  # -f matters: without it a 404 HTML page is written to the file and the
  # generator sees an empty property rather than a download failure.
  curl -fsS --max-time 120 -o "$DIR/$f" "$BASE/$f"
  head -c 200 "$DIR/$f" | grep -qi '<!DOCTYPE\|<html' && {
    echo "error: $f is an HTML page, not UCD data" >&2
    exit 1
  }
  printf '  %-45s %s\n' "$f" "ok"
done
for f in "${EMOJI_FILES[@]}"; do
  curl -fsS --max-time 120 -o "$DIR/$f" "$EMOJI_BASE/$f"
  head -c 200 "$DIR/$f" | grep -qi '<!DOCTYPE\|<html' && {
    echo "error: $f is an HTML page, not emoji data" >&2
    exit 1
  }
  printf '  %-45s %s\n' "$f" "ok"
done

echo "UCD $VERSION + emoji sequences downloaded to $DIR"
