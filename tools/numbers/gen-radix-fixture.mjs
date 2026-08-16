#!/usr/bin/env node
// Records what node prints for toString(radix) with radix != 10.
//
//   node tools/numbers/gen-radix-fixture.mjs <output-kotlin-file>
//
// This is the one number function with no specified answer, so the fixture is
// the definition of "right" rather than a corroboration of it.
import { writeFileSync } from "node:fs";
const OUT = process.argv[2];
if (!OUT) { console.error("usage: gen-radix-fixture.mjs <out>"); process.exit(2); }

const buf = new ArrayBuffer(8), dv = new DataView(buf);
const MASK64 = (1n << 64n) - 1n, GOLDEN = 0x9e3779b97f4a7c15n;
const fromBits = (b) => { dv.setBigUint64(0, b & MASK64); return dv.getFloat64(0); };
const bitsOf = (v) => { dv.setFloat64(0, v); return dv.getBigUint64(0); };

const values = [];
for (let i = 1n; i <= 3000n; i++) {
  const d = fromBits(i * GOLDEN);
  if (Number.isFinite(d)) values.push(d);
}
for (const v of [0, 1, 2, 255, 4095, 0.5, 0.25, 0.1, 1/3, 1e21, 1e-7, 123.456,
                 9007199254740992, 9007199254740993, 1e100, 5e-324, 1e-320,
                 Number.MAX_VALUE, 2.2250738585072014e-308, 0.0625, 1023.999]) {
  values.push(v); values.push(-v);
}

const radices = [2, 3, 7, 8, 16, 20, 36];
const outputs = [];
for (const v of values) for (const r of radices) outputs.push(v.toString(r));

let hash = 2166136261 >>> 0;
for (const s of outputs) {
  for (let i = 0; i < s.length; i++) {
    hash = (hash ^ s.charCodeAt(i)) >>> 0;
    hash = Math.imul(hash, 16777619) >>> 0;
  }
  hash = (hash ^ 0x7c) >>> 0;
  hash = Math.imul(hash, 16777619) >>> 0;
}

const explicit = [];
for (const [v, r] of [[255, 16], [4095, 16], [0.5, 2], [0.0625, 2], [0.1, 3],
                      [-0.1, 7], [1e21, 36], [123.456, 8], [1/3, 3], [2, 2],
                      [9007199254740993, 16], [1e100, 36], [1023.999, 2]]) {
  explicit.push(`        Pair(${bitsOf(v)}uL, ${r}) to ${JSON.stringify(v.toString(r))},`);
}

writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/numbers/gen-radix-fixture.mjs against node ${process.version}.
//
// toString(radix) for radix != 10 is implementation-approximated: the
// specification defines no answer, so these recorded strings *are* the
// contract. If a future V8 changes them, this fixture is what will say so.

package io.github.mgilbir.ecma262.number

internal object RadixFixture {
    internal const val ORACLE: String = "node ${process.version}"
    internal const val SAMPLE_COUNT: Int = ${outputs.length}
    internal const val SAMPLE_HASH: UInt = ${hash}u
    internal const val VALUE_COUNT: Int = ${values.length}
    internal val RADICES: IntArray = intArrayOf(${radices.join(", ")})

    /** (raw bits, radix) to what node prints. */
    internal val EXPLICIT: List<Pair<Pair<ULong, Int>, String>> = listOf(
${explicit.join("\n")}
    )
}
`);
console.log(`wrote ${OUT}`);
console.log(`  values: ${values.length}, strings: ${outputs.length}, hash: ${hash}`);
