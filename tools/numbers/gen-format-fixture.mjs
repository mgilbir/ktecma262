#!/usr/bin/env node
// Records node's toFixed / toExponential / toPrecision over a deterministic grid.
//
//   node tools/numbers/gen-format-fixture.mjs <output-kotlin-file>
//
// Same shape as the toString fixture: the sample is walked from an index on
// both sides, so this stores a count and a hash rather than a megabyte of
// strings, plus a short explicit list for diagnosis.
import { writeFileSync } from "node:fs";
const OUT = process.argv[2];
if (!OUT) { console.error("usage: gen-format-fixture.mjs <out>"); process.exit(2); }

const buf = new ArrayBuffer(8), dv = new DataView(buf);
const MASK64 = (1n << 64n) - 1n, GOLDEN = 0x9e3779b97f4a7c15n;
const fromBits = (b) => { dv.setBigUint64(0, b & MASK64); return dv.getFloat64(0); };
const bitsOf = (v) => { dv.setFloat64(0, v); return dv.getBigUint64(0); };

// Values spread across the exponent range, plus the awkward small ones.
const values = [];
for (let i = 1n; i <= 4000n; i++) {
  const d = fromBits(i * GOLDEN);
  if (Number.isFinite(d)) values.push(d);
}
for (const v of [0, 1, 1.005, 1.5, 2.5, 0.5, 1234.5678, 0.000001, 1e-7, 1e20,
                 1e21, 9.999999999999999e20, 123.456, 0.1, 1/3, 5e-324, 1e-320,
                 Number.MAX_VALUE, 2.2250738585072014e-308, 99.995, 0.00001,
                 1e-6, 999999.5, 0.0000001234, 1e100, 4.35, 1.45, 8.005]) {
  values.push(v);
  values.push(-v);
}

const outputs = [];
const record = (s) => outputs.push(s);
for (const v of values) {
  for (const f of [0, 1, 2, 3, 6, 17, 20, 100]) {
    try { record(v.toFixed(f)); } catch { record("!range"); }
    try { record(v.toExponential(f)); } catch { record("!range"); }
  }
  record(v.toExponential());
  for (const p of [1, 2, 3, 6, 17, 21, 100]) {
    try { record(v.toPrecision(p)); } catch { record("!range"); }
  }
}

let hash = 2166136261 >>> 0;
for (const s of outputs) {
  for (let i = 0; i < s.length; i++) {
    hash = (hash ^ s.charCodeAt(i)) >>> 0;
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  hash = (hash ^ 0x7c) >>> 0;
  hash = Math.imul(hash, 16777619) >>> 0;
}

// A readable subset for diagnosis when the hash disagrees.
const explicit = [];
for (const [v, kind, arg] of [
  [1.005, "fixed", 2], [1.45, "fixed", 1], [8.005, "fixed", 2], [2.5, "fixed", 0],
  [0.5, "fixed", 0], [1.5, "fixed", 0], [99.995, "fixed", 2], [1e21, "fixed", 2],
  [0.000001, "fixed", 2], [1234.5678, "fixed", 2], [-1.005, "fixed", 2],
  [1234.5678, "exp", 2], [1234.5678, "exp", null], [0, "exp", 2], [1e-7, "exp", 3],
  [123.456, "prec", 2], [1234.5678, "prec", 6], [0.000123, "prec", 2],
  [123, "prec", 5], [0, "prec", 3], [1e21, "prec", 3], [1e-7, "prec", 2],
]) {
  const s = kind === "fixed" ? v.toFixed(arg)
          : kind === "exp" ? (arg === null ? v.toExponential() : v.toExponential(arg))
          : v.toPrecision(arg);
  explicit.push(`        Triple(${bitsOf(v)}uL, "${kind}${arg === null ? "" : ":" + arg}") to ${JSON.stringify(s)},`);
}

writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-format-fixture.mjs against node ${process.version}.

package io.github.mgilbir.ecma262.number

internal object FormatFixture {
    internal const val ORACLE: String = "node ${process.version}"
    internal const val SAMPLE_COUNT: Int = ${outputs.length}
    internal const val SAMPLE_HASH: UInt = ${hash}u
    internal val VALUE_COUNT: Int = ${values.length}

    /** (raw bits, "method:arg") to what node prints. */
    internal val EXPLICIT: List<Pair<Triple<ULong, String, Unit>, String>> = emptyList()
}
`.replace("    /** (raw bits, \"method:arg\") to what node prints. */\n    internal val EXPLICIT: List<Pair<Triple<ULong, String, Unit>, String>> = emptyList()\n",
`    /** (raw bits, "method:arg") to what node prints. */
    internal val EXPLICIT: List<Pair<Pair<ULong, String>, String>> = listOf(
${explicit.map((l) => l.replace("Triple(", "Pair(").replace("uL, \"", "uL, \"")).join("\n")}
    )
`));
console.log(`wrote ${OUT}`);
console.log(`  values: ${values.length}, strings: ${outputs.length}, hash: ${hash}`);
