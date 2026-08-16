#!/usr/bin/env node
// Records what node prints for a large, deterministic set of doubles.
//
//   node tools/numbers/gen-fixture.mjs <output-kotlin-file>
//
// The sample is generated from an index rather than stored, so both sides can
// walk the identical sequence and the fixture stays small: a count, a hash of
// every string node produced, and a short explicit list for diagnosis. That is
// the same shape as the RegExp.escape fixture, and for the same reason — the
// interesting property is "identical over a very large sample", which a hash
// captures in a few bytes.
//
// Doubles travel as raw bit patterns, so nothing here depends on how either
// side parses decimal text.

import { writeFileSync } from "node:fs";

const OUT = process.argv[2];
if (!OUT) {
  console.error("usage: gen-fixture.mjs <output-kotlin-file>");
  process.exit(2);
}

const buf = new ArrayBuffer(8);
const dv = new DataView(buf);
const MASK64 = (1n << 64n) - 1n;
const GOLDEN = 0x9e3779b97f4a7c15n;

const fromBits = (bits) => {
  dv.setBigUint64(0, bits & MASK64);
  return dv.getFloat64(0);
};

/** Hand-picked values: layout thresholds, range extremes, known-awkward digits. */
const EXPLICIT = [
  0, -0, 1, -1, 2, 100, -100, 0.1, 0.3, -1.5, 4.35, 1 / 3,
  1e6, 1e7, 1e20, 1e21, 1e-5, 1e-6, 1e-7,
  9.999999999999999e20, 1.23456789012345678e20, 1.234567890123456789e18,
  9007199254740992, 9007199254740993, 4503599627370497,
  Number.MAX_VALUE, Number.MIN_VALUE, 2.2250738585072014e-308,
  5e-324, 1e-323, 5e-323, 2.5e-323,
  1.5e300, 1e100, 1e-100, 123456.789, 0.000001, 1e-21,
  // Values whose shortest form is famously easy to get wrong.
  5e-1, 0.5, 8.98846567431158e307, 2.2250738585072011e-308,
  1.7976931348623157e308, 5.0e-324, 3.141592653589793, 2.718281828459045,
  1e23, 9.109383e-31, 6.02214076e23, 1.1, 2.2, 3.3, 1e-10, 1234.5678,
];

const outputs = [];
const explicitPairs = [];

for (const v of EXPLICIT) {
  dv.setFloat64(0, v);
  explicitPairs.push([dv.getBigUint64(0), String(v)]);
}

// Phase A — a deterministic sweep of the whole bit space.
for (let i = 1n; i <= 200000n; i++) {
  const bits = (i * GOLDEN) & MASK64;
  const d = fromBits(bits);
  if (!Number.isFinite(d)) continue;
  outputs.push(String(d));
}
// Phase B — the smallest subnormals, where shortest-digit algorithms fail first.
for (let bits = 1n; bits <= 20000n; bits++) outputs.push(String(fromBits(bits)));
// Phase C — every power of two, for the asymmetric gap below each one.
for (let e = 1n; e <= 2046n; e++) outputs.push(String(fromBits(e << 52n)));
// Phase D — short decimals, where "shortest" is most visible.
for (let i = 1; i <= 5000; i++) {
  outputs.push(String(i / 10));
  outputs.push(String(i / 1000));
}

// FNV-1a over every character of every string, in order.
let hash = 2166136261 >>> 0;
for (const s of outputs) {
  for (let i = 0; i < s.length; i++) {
    hash = (hash ^ s.charCodeAt(i)) >>> 0;
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  hash = (hash ^ 0x7c) >>> 0; // separator, so concatenation cannot collide
  hash = Math.imul(hash, 16777619) >>> 0;
}

const explicitLines = explicitPairs
  .map(([bits, s]) => `        ${bits}UL to ${JSON.stringify(s)},`)
  .join("\n");

const kotlin = `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-fixture.mjs against node ${process.version}.
//
// The sample is walked from an index on both sides rather than stored, so this
// records only a count, a hash of every string node produced, and an explicit
// list for diagnosis. See NumberToStringDifferentialTest.

package io.github.mgilbir.ecma262.number

internal object NumberFixture {
    internal const val ORACLE: String = "node ${process.version}"

    /** Number of strings covered by [SAMPLE_HASH]. */
    internal const val SAMPLE_COUNT: Int = ${outputs.length}

    /** FNV-1a over every string node produced, in sequence order. */
    internal const val SAMPLE_HASH: UInt = ${hash}u

    /** Raw bit pattern to the string node prints for it. */
    internal val EXPLICIT: List<Pair<ULong, String>> = listOf(
${explicitLines}
    )
}
`;

writeFileSync(OUT, kotlin);
console.log(`wrote ${OUT}`);
console.log(`  sample:   ${outputs.length} strings`);
console.log(`  hash:     ${hash}`);
console.log(`  explicit: ${explicitPairs.length}`);
