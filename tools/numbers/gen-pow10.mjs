#!/usr/bin/env node
// Generates the cached powers of ten Grisu3 multiplies by.
//
//   node tools/numbers/gen-pow10.mjs <output-kotlin-file>
//
// Each entry approximates 10^k as f * 2^e with f normalised to 64 bits, so the
// error is under half a unit in the last place. Computed exactly with BigInt
// and rounded once, rather than transcribed from another implementation's
// source — a single wrong digit in a table like this produces answers that are
// wrong only for a handful of inputs.

import { writeFileSync } from "node:fs";

const OUT = process.argv[2];
if (!OUT) {
  console.error("usage: gen-pow10.mjs <output-kotlin-file>");
  process.exit(2);
}

const MIN_K = -348;
const MAX_K = 340;

const bitLength = (n) => (n === 0n ? 0 : n.toString(2).length);

/** Round-to-nearest division of two positive BigInts, ties away from zero. */
function roundDiv(num, den) {
  const q = num / den;
  const r = num - q * den;
  return r * 2n >= den ? q + 1n : q;
}

/** 10^k as a normalised 64-bit significand and a binary exponent. */
function cachedPower(k) {
  // 10^k as an exact fraction.
  let num = 1n;
  let den = 1n;
  if (k >= 0) num = 10n ** BigInt(k);
  else den = 10n ** BigInt(-k);

  // Choose e so that the significand lands in [2^63, 2^64).
  let e = bitLength(num) - bitLength(den) - 64;
  for (;;) {
    const f =
      e >= 0 ? roundDiv(num, den << BigInt(e)) : roundDiv(num << BigInt(-e), den);
    if (f < 1n << 63n) {
      e -= 1;
      continue;
    }
    if (f >= 1n << 64n) {
      e += 1;
      continue;
    }
    return { f, e };
  }
}

const entries = [];
for (let k = MIN_K; k <= MAX_K; k++) entries.push({ k, ...cachedPower(k) });

// Self-check: every entry must be within half an ulp of the true value, and the
// exponents must increase monotonically so the lookup can scan.
let worst = 0;
for (const { k, f, e } of entries) {
  const approxNum = f * (e >= 0 ? 1n << BigInt(e) : 1n);
  const approxDen = e >= 0 ? 1n : 1n << BigInt(-e);
  let trueNum = 1n;
  let trueDen = 1n;
  if (k >= 0) trueNum = 10n ** BigInt(k);
  else trueDen = 10n ** BigInt(-k);
  // |approx/true - 1| scaled to ulps of f.
  const lhs = approxNum * trueDen;
  const rhs = trueNum * approxDen;
  const diff = lhs > rhs ? lhs - rhs : rhs - lhs;
  // diff / (trueNum*approxDen) is the relative error; an ulp is 1/f. Scaled by
  // a million before the integer division, or everything under half an ulp
  // would round to zero and the check would pass on anything.
  const SCALE = 1000000n;
  const ulps = Number((diff * f * SCALE) / (trueNum * approxDen)) / Number(SCALE);
  if (ulps > worst) worst = ulps;
  if (ulps > 0.5) {
    console.error(`entry k=${k} is ${ulps} ulps out`);
    process.exit(1);
  }
}
for (let i = 1; i < entries.length; i++) {
  if (entries[i].e < entries[i - 1].e) {
    console.error(`exponents not monotonic at k=${entries[i].k}`);
    process.exit(1);
  }
}

const asHexULong = (f) => "0x" + f.toString(16).toUpperCase().padStart(16, "0") + "uL";
const wrap = (values, perLine) => {
  const lines = [];
  for (let i = 0; i < values.length; i += perLine) {
    lines.push("        " + values.slice(i, i + perLine).join(", ") + ",");
  }
  return lines.join("\n");
};

const kotlin = `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-pow10.mjs.
//
// Cached powers of ten for Grisu3: 10^k as f * 2^e, with f normalised so its
// top bit is set. Every entry is within half a unit in the last place of the
// true value, checked exactly during generation.

package io.github.mgilbir.ecma262.number

internal object Pow10Table {
    /** Smallest k in the table. */
    internal const val MIN_K: Int = ${MIN_K}

    /** Largest k in the table. */
    internal const val MAX_K: Int = ${MAX_K}

    /**
     * Normalised significands: the top bit of each is set.
     *
     * Held as ULong rather than Long so the values read as what they are;
     * Long.MIN_VALUE is among them and cannot be written as a signed literal.
     */
    internal val SIGNIFICANDS: ULongArray = ulongArrayOf(
${wrap(entries.map((x) => asHexULong(x.f)), 4)}
    )

    /** Binary exponents, in the same order. */
    internal val EXPONENTS: IntArray = intArrayOf(
${wrap(entries.map((x) => String(x.e)), 12)}
    )
}
`;

writeFileSync(OUT, kotlin);
console.log(`wrote ${OUT}`);
console.log(`  entries:   ${entries.length} (k = ${MIN_K}..${MAX_K})`);
console.log(`  worst err: ${worst.toFixed(6)} ulp`);
