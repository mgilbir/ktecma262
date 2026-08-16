#!/usr/bin/env node
// Builds the tables `String.prototype.normalize` needs, from the UCD.
//
//   tools/genunicode/fetch-ucd.sh <ucd-dir> 17.0.0
//   node tools/genunicode/gen-normalization.mjs <ucd-dir> <output-kotlin-file>
//
// Decompositions are stored **fully expanded**, so normalising never recurses:
// UnicodeData.txt gives one step at a time, and applying it repeatedly at run
// time would be both slower and easy to get subtly wrong for the handful of
// characters that decompose three deep.
//
// Hangul is deliberately absent. Its composition and decomposition are
// arithmetic (UAX #15, section 16), and 11,172 syllables would dominate a table
// that is otherwise a few thousand entries.
//
// The same varint alphabet as the other Unicode tables: five payload bits per
// character, bit five marks a continuation. Large arrays cannot be Kotlin
// literals — the JVM caps a method at 64 KB of bytecode and a static
// initialiser is a method.

import fs from "node:fs";

const UCD = process.argv[2];
const OUT = process.argv[3];
if (!UCD || !OUT) {
  console.error("usage: gen-normalization.mjs <ucd-dir> <output-kotlin-file>");
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
const encode = (values) => {
  const out = [];
  for (const v of values) pushVarint(v, out);
  return out.join("");
};

// --- read the UCD ------------------------------------------------------------
const combiningClass = new Map();
const oneStep = new Map(); // cp -> { compat: bool, to: number[] }

for (const line of fs.readFileSync(`${UCD}/UnicodeData.txt`, "utf8").split("\n")) {
  if (!line) continue;
  const f = line.split(";");
  const cp = parseInt(f[0], 16);
  const ccc = parseInt(f[3], 10);
  if (ccc !== 0) combiningClass.set(cp, ccc);
  const decomposition = f[5];
  if (!decomposition) continue;
  const compat = decomposition.startsWith("<");
  const parts = decomposition.replace(/<[^>]*>\s*/, "").trim().split(/\s+/);
  oneStep.set(cp, { compat, to: parts.map((h) => parseInt(h, 16)) });
}

const fullCompositionExclusion = new Set();
for (const line of fs.readFileSync(`${UCD}/DerivedNormalizationProps.txt`, "utf8").split("\n")) {
  const text = line.split("#")[0].trim();
  if (!text) continue;
  const [range, property] = text.split(";").map((s) => s.trim());
  if (property !== "Full_Composition_Exclusion") continue;
  const [lo, hi] = range.split("..");
  const start = parseInt(lo, 16);
  const end = hi === undefined ? start : parseInt(hi, 16);
  for (let cp = start; cp <= end; cp++) fullCompositionExclusion.add(cp);
}

// --- expand decompositions fully --------------------------------------------
const HANGUL_SYLLABLE_START = 0xac00;
const HANGUL_SYLLABLE_END = 0xd7a3;
const isHangulSyllable = (cp) => cp >= HANGUL_SYLLABLE_START && cp <= HANGUL_SYLLABLE_END;

function expand(cp, compatibility, seen = new Set()) {
  if (isHangulSyllable(cp)) return [cp]; // handled arithmetically
  const step = oneStep.get(cp);
  if (!step) return [cp];
  if (step.compat && !compatibility) return [cp];
  if (seen.has(cp)) throw new Error(`decomposition cycle at U+${cp.toString(16)}`);
  seen.add(cp);
  const out = [];
  for (const part of step.to) out.push(...expand(part, compatibility, seen));
  seen.delete(cp);
  return out;
}

const canonical = [];
const compatibility = [];
for (const cp of [...oneStep.keys()].sort((a, b) => a - b)) {
  const c = expand(cp, false);
  if (c.length !== 1 || c[0] !== cp) canonical.push([cp, c]);
  const k = expand(cp, true);
  if (k.length !== 1 || k[0] !== cp) compatibility.push([cp, k]);
}

// --- composition pairs -------------------------------------------------------
// A canonical decomposition of exactly two characters can be recomposed, unless
// the composite is a full composition exclusion. That property already covers
// singletons and non-starter decompositions, so no separate test is needed.
const composition = [];
for (const [cp, step] of oneStep) {
  if (step.compat || step.to.length !== 2) continue;
  if (fullCompositionExclusion.has(cp)) continue;
  composition.push([step.to[0], step.to[1], cp]);
}
composition.sort((a, b) => a[0] - b[0] || a[1] - b[1]);

// --- encode ------------------------------------------------------------------
const encodeCcc = () => {
  const sorted = [...combiningClass.entries()].sort((a, b) => a[0] - b[0]);
  const values = [];
  let previous = 0;
  for (const [cp, ccc] of sorted) {
    values.push(cp - previous, ccc);
    previous = cp;
  }
  return { encoded: encode(values), count: sorted.length };
};

const encodeDecompositions = (entries) => {
  const values = [];
  let previous = 0;
  for (const [cp, to] of entries) {
    values.push(cp - previous, to.length, ...to);
    previous = cp;
  }
  return { encoded: encode(values), count: entries.length };
};

const encodeComposition = () => {
  const values = [];
  let previous = 0;
  for (const [a, b, composite] of composition) {
    values.push(a - previous, b, composite);
    previous = a;
  }
  return { encoded: encode(values), count: composition.length };
};

const ccc = encodeCcc();
const canon = encodeDecompositions(canonical);
const compat = encodeDecompositions(compatibility);
const compose = encodeComposition();

const version = fs
  .readFileSync(`${UCD}/UnicodeData.txt`, "utf8")
  .length > 0
  ? (process.argv[4] ?? "17.0.0")
  : "unknown";

// One literal per table, never a chain of them joined with `+`: the Kotlin
// compiler builds an expression tree for that and stack-overflows a few
// thousand terms in. A single literal is capped instead by the JVM's 64 KB
// limit on a constant-pool entry, which these stay well under.
const MAX_LITERAL = 40000;
const chunk = (s) => {
  if (s.length > MAX_LITERAL) throw new Error(`table too long for one literal: ${s.length}`);
  return `        "${s}"`;
};

fs.writeFileSync(
  OUT,
  `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/genunicode/gen-normalization.mjs from the Unicode ${version} UCD.
//
// Decompositions are stored fully expanded, so normalising is a lookup rather
// than a recursion. Hangul is absent because its mappings are arithmetic.
//
// Encoded with the varint alphabet in UnicodeTables: five payload bits per
// character, bit five marks a continuation. A Kotlin literal array of this size
// would exceed the JVM's 64 KB limit on a single method, and a static
// initialiser is a method.

package io.github.mgilbir.ecma262.unicode

internal object NormalizationTables {
    internal const val UNICODE_VERSION: String = "${version}"

    /** Code point delta, then canonical combining class. ${ccc.count} entries. */
    internal val COMBINING_CLASS: String =
${chunk(ccc.encoded)}

    /** Code point delta, length, then the characters. ${canon.count} entries. */
    internal val CANONICAL: String =
${chunk(canon.encoded)}

    /** The same, for compatibility decomposition. ${compat.count} entries. */
    internal val COMPATIBILITY: String =
${chunk(compat.encoded)}

    /** Starter delta, combining mark, composite. ${compose.count} entries. */
    internal val COMPOSITION: String =
${chunk(compose.encoded)}
}
`,
);

console.log(`wrote ${OUT}`);
console.log(`  combining classes: ${ccc.count}`);
console.log(`  canonical:         ${canon.count}`);
console.log(`  compatibility:     ${compat.count}`);
console.log(`  composition pairs: ${compose.count}`);
console.log(`  size:              ${Math.round(fs.statSync(OUT).size / 1024)} KiB`);
