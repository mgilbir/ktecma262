#!/usr/bin/env node
// Turns the Unicode conformance suite into a Kotlin fixture, and records node's
// answers for every single code point.
//
//   node tools/genunicode/gen-normalization-fixture.mjs <ucd-dir> <out> [version]
//
// NormalizationTest.txt is the authority for UAX #15 — the expectations are
// Unicode's own, not an engine's, which makes it the counterpart of the Test262
// fixture used for numbers. Its Part 1 also carries the specific guarantee that
// every code point not otherwise listed normalises to itself in all four forms.
//
// The exhaustive per-code-point hashes are a second, independent source: they
// catch anything the conformance file does not happen to cover.
import fs from "node:fs";

const UCD = process.argv[2];
const OUT = process.argv[3];
const VERSION = process.argv[4] ?? "17.0.0";
if (!UCD || !OUT) {
  console.error("usage: gen-normalization-fixture.mjs <ucd-dir> <out> [version]");
  process.exit(2);
}

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const pushVarint = (value, out) => {
  let v = value;
  for (;;) {
    const chunk = v & 31;
    v >>>= 5;
    out.push(ALPHABET[v === 0 ? chunk : chunk | 32]);
    if (v === 0) break;
  }
};

const parseSeq = (field) => field.trim().split(/\s+/).filter(Boolean).map((h) => parseInt(h, 16));

const rows = [];
for (const line of fs.readFileSync(`${UCD}/NormalizationTest.txt`, "utf8").split("\n")) {
  const text = line.split("#")[0].trim();
  if (!text || text.startsWith("@")) continue;
  const fields = text.split(";").slice(0, 5);
  if (fields.length !== 5) continue;
  rows.push(fields.map(parseSeq));
}

// Cross-check every row against node before recording it. A disagreement means
// either node or this reading of the file is wrong, and either way the fixture
// should not be written.
const str = (cps) => String.fromCodePoint(...cps);
let mismatches = 0;
for (const [source, nfc, nfd, nfkc, nfkd] of rows) {
  const s = str(source);
  if (s.normalize("NFC") !== str(nfc)) mismatches++;
  if (s.normalize("NFD") !== str(nfd)) mismatches++;
  if (s.normalize("NFKC") !== str(nfkc)) mismatches++;
  if (s.normalize("NFKD") !== str(nfkd)) mismatches++;
}
if (mismatches > 0) {
  console.error(`${mismatches} conformance rows where node disagrees with Unicode - not writing`);
  process.exit(1);
}

// Encode: per row, five sequences, each a length then its code points.
const encoded = [];
for (const row of rows) {
  for (const seq of row) {
    pushVarint(seq.length, encoded);
    for (const cp of seq) pushVarint(cp, encoded);
  }
}
const blob = encoded.join("");

// Exhaustive single code points, hashed per form.
const fnv1a = (h, s) => {
  for (let i = 0; i < s.length; i++) {
    h = (h ^ s.charCodeAt(i)) >>> 0;
    h = Math.imul(h, 16777619) >>> 0;
  }
  h = (h ^ 0x7c) >>> 0;
  return Math.imul(h, 16777619) >>> 0;
};
const forms = ["NFC", "NFD", "NFKC", "NFKD"];
const hashes = {};
let counted = 0;
for (const form of forms) {
  let h = 2166136261 >>> 0;
  counted = 0;
  for (let cp = 0; cp <= 0x10ffff; cp++) {
    if (cp >= 0xd800 && cp <= 0xdfff) continue; // no code point
    h = fnv1a(h, String.fromCodePoint(cp).normalize(form));
    counted++;
  }
  hashes[form] = h;
}

// An array of literals rather than one, and never a `+` chain: a single
// constant-pool entry caps at 64 KB, and chaining literals stack-overflows the
// Kotlin compiler once there are a few thousand of them.
const MAX_LITERAL = 40000;
const chunk = (s) => {
  const parts = [];
  for (let i = 0; i < s.length; i += MAX_LITERAL) parts.push(`        "${s.slice(i, i + MAX_LITERAL)}",`);
  return parts.join("\n");
};

fs.writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/genunicode/gen-normalization-fixture.mjs.
//
// Two independent sources. The conformance rows come from Unicode's own
// NormalizationTest.txt for ${VERSION} - expectations no implementation
// produced - and generation fails if node disagrees with any of them. The
// hashes cover every code point individually, which the conformance file does
// not claim to.
//
// The oracle is recorded by its *Unicode* version, not its release version.
// What determines these hashes is the Unicode data node carries, and a node
// patch bump that changes nothing else would otherwise make the nightly drift
// check fail for no reason - which is how it failed the first night it ran.

package io.github.mgilbir.ecma262.text

internal object NormalizationFixture {
    internal const val UNICODE_VERSION: String = "${VERSION}"
    internal const val ORACLE: String = "node (Unicode ${process.versions.unicode})"

    /** Rows of source, NFC, NFD, NFKC, NFKD. */
    internal const val ROW_COUNT: Int = ${rows.length}

    /** Code points hashed per form. */
    internal const val CODE_POINTS: Int = ${counted}
    internal const val NFC_HASH: UInt = ${hashes.NFC}u
    internal const val NFD_HASH: UInt = ${hashes.NFD}u
    internal const val NFKC_HASH: UInt = ${hashes.NFKC}u
    internal const val NFKD_HASH: UInt = ${hashes.NFKD}u

    /** Five varint sequences per row: length then code points, in parts. */
    internal val ROW_PARTS: Array<String> = arrayOf(
${chunk(blob)}
    )
}
`);
console.log(`wrote ${OUT}`);
console.log(`  conformance rows: ${rows.length}`);
console.log(`  code points:      ${counted}`);
console.log(`  size:             ${Math.round(fs.statSync(OUT).size / 1024)} KiB`);
