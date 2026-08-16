#!/usr/bin/env node
// Records node's answers for the places Kotlin quietly disagrees with JavaScript.
//
//   node tools/semantics/gen-fixture.mjs <output-kotlin-file>
//
// Trimming is checked per code point: whether a character is whitespace is a
// yes-or-no question over a finite set, so the whole set is recorded as a
// bitmap rather than sampled. The Math functions get a deterministic sweep of
// doubles and integers, hashed.

import { writeFileSync } from "node:fs";

const OUT = process.argv[2];
if (!OUT) {
  console.error("usage: gen-fixture.mjs <output-kotlin-file>");
  process.exit(2);
}

// --- whitespace: which BMP characters does trim() remove? --------------------
const whitespace = [];
for (let c = 0; c <= 0xffff; c++) {
  const ch = String.fromCharCode(c);
  // A lone surrogate is not whitespace and would confuse the round trip.
  if (c >= 0xd800 && c <= 0xdfff) continue;
  if ((ch + "x" + ch).trim() === "x") whitespace.push(c);
}

// --- Math --------------------------------------------------------------------
const buf = new ArrayBuffer(8);
const dv = new DataView(buf);
const MASK64 = (1n << 64n) - 1n;
const GOLDEN = 0x9e3779b97f4a7c15n;
const fromBits = (b) => {
  dv.setBigUint64(0, b & MASK64);
  return dv.getFloat64(0);
};
const bitsOf = (v) => {
  dv.setFloat64(0, v);
  return dv.getBigUint64(0);
};

const doubles = [];
for (let i = 1n; i <= 20000n; i++) {
  const d = fromBits(i * GOLDEN);
  if (Number.isFinite(d)) doubles.push(d);
}
for (let i = -2000; i <= 2000; i++) {
  doubles.push(i, i + 0.5, i - 0.5, i / 3, i / 7, i * 1e10, i * 1e-10);
}
for (const special of [
  0, -0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 0.49999999999999994, -0.49999999999999994,
  4503599627370495.5, 4503599627370496, -4503599627370496, 9007199254740993,
  1e300, -1e300, 5e-324, Number.MAX_VALUE, Infinity, -Infinity, NaN,
]) {
  doubles.push(special);
}

const fnv1a = (h, s) => {
  for (let i = 0; i < s.length; i++) {
    h = (h ^ s.charCodeAt(i)) >>> 0;
    h = Math.imul(h, 16777619) >>> 0;
  }
  h = (h ^ 0x7c) >>> 0;
  return Math.imul(h, 16777619) >>> 0;
};

// Always the raw bits. An earlier version hashed small integers as decimal
// strings instead, which the Kotlin side had no way to know about: the values
// were all correct and only the hash disagreed, which is a confusing way to
// fail. One rule, mirrored exactly.
const hashOf = (fn) => {
  let h = 2166136261 >>> 0;
  for (const d of doubles) h = fnv1a(h, bitsOf(fn(d)).toString(16));
  return h;
};

// Hash the inputs too. Without this, a sweep that drifts out of step with the
// Kotlin side looks exactly like a wrong answer, and the count alone does not
// catch a reordering.
let inputHash = 2166136261 >>> 0;
for (const d of doubles) inputHash = fnv1a(inputHash, bitsOf(d).toString(16));

const roundHash = hashOf((d) => Math.round(d));
const truncHash = hashOf((d) => Math.trunc(d));
const signHash = hashOf((d) => Math.sign(d));
const froundHash = hashOf((d) => Math.fround(d));

let clzHash = 2166136261 >>> 0;
let imulHash = 2166136261 >>> 0;
for (const d of doubles) {
  clzHash = fnv1a(clzHash, String(Math.clz32(d)));
}
for (let i = 0; i < doubles.length - 1; i++) {
  imulHash = fnv1a(imulHash, String(Math.imul(doubles[i], doubles[i + 1])));
}

const explicit = [
  ["round", 0.5], ["round", -0.5], ["round", 2.5], ["round", -2.5],
  ["round", 0.49999999999999994], ["round", -0.2], ["round", -0.6],
  ["trunc", -0.5], ["trunc", 0.5], ["trunc", -1.5],
  ["sign", -0], ["sign", 0], ["sign", -3], ["sign", 3],
  ["fround", 5.05], ["fround", 1e40], ["fround", -0],
];

writeFileSync(
  OUT,
  `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/semantics/gen-fixture.mjs against node ${process.version}.
//
// Whitespace is recorded exhaustively: which BMP characters trim() removes is a
// finite yes-or-no question, so there is no reason to sample it. The Math
// hashes cover a deterministic sweep of doubles, hashed over raw bits so that
// -0 and 0 are told apart.

package io.github.mgilbir.ecma262.text

internal object SemanticsFixture {
    internal const val ORACLE: String = "node ${process.version}"

    /** Every BMP code point that \`trim()\` strips. */
    internal val WHITESPACE: IntArray = intArrayOf(
${whitespace.map((c) => "0x" + c.toString(16).toUpperCase()).join(", ").replace(/(.{88})\s/g, "$1\n        ").replace(/^/, "        ")}
    )

    /** Doubles the Math hashes were taken over. */
    internal const val SAMPLE_COUNT: Int = ${doubles.length}

    /** The sweep itself, so drift is distinguishable from a wrong answer. */
    internal const val INPUT_HASH: UInt = ${inputHash}u

    internal const val ROUND_HASH: UInt = ${roundHash}u
    internal const val TRUNC_HASH: UInt = ${truncHash}u
    internal const val SIGN_HASH: UInt = ${signHash}u
    internal const val FROUND_HASH: UInt = ${froundHash}u
    internal const val CLZ32_HASH: UInt = ${clzHash}u
    internal const val IMUL_HASH: UInt = ${imulHash}u

    internal class Case(val function: String, val argumentBits: ULong, val resultBits: ULong)

    internal val EXPLICIT: List<Case> = listOf(
${explicit
  .map(([fn, arg]) => `        Case("${fn}", ${bitsOf(arg)}uL, ${bitsOf(Math[fn](arg))}uL),`)
  .join("\n")}
    )
}
`,
);

console.log(`wrote ${OUT}`);
console.log(`  whitespace code points: ${whitespace.length}`);
console.log(`  doubles sampled:        ${doubles.length}`);
