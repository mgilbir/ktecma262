// Hashes ToInt32 / ToUint32 over the same sweep the Math fixture uses.
import { writeFileSync } from "node:fs";
const OUT = process.argv[2];
const buf = new ArrayBuffer(8), dv = new DataView(buf);
const MASK64 = (1n << 64n) - 1n, GOLDEN = 0x9e3779b97f4a7c15n;
const fromBits = (b) => { dv.setBigUint64(0, b & MASK64); return dv.getFloat64(0); };
const doubles = [];
for (let i = 1n; i <= 20000n; i++) { const d = fromBits(i * GOLDEN); if (Number.isFinite(d)) doubles.push(d); }
for (let i = -2000; i <= 2000; i++) doubles.push(i, i + 0.5, i - 0.5, i / 3, i / 7, i * 1e10, i * 1e-10);
for (const s of [0,-0,0.5,-0.5,1.5,-1.5,2.5,-2.5,0.49999999999999994,-0.49999999999999994,
  4503599627370495.5,4503599627370496,-4503599627370496,9007199254740993,
  1e300,-1e300,5e-324,Number.MAX_VALUE,Infinity,-Infinity,NaN,
  2147483647,2147483648,-2147483648,-2147483649,4294967295,4294967296,4294967295.9,1e21]) doubles.push(s);
const fnv1a=(h,s)=>{for(let i=0;i<s.length;i++){h=(h^s.charCodeAt(i))>>>0;h=Math.imul(h,16777619)>>>0;}h=(h^0x7c)>>>0;return Math.imul(h,16777619)>>>0;};
let i32=2166136261>>>0, u32=2166136261>>>0;
for (const d of doubles) { i32 = fnv1a(i32, String(d|0)); u32 = fnv1a(u32, String(d>>>0)); }
writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/semantics/gen-conversions-fixture.mjs against node ${process.version}.

package io.github.mgilbir.ecma262.number

internal object ConversionsFixture {
    internal const val SAMPLE_COUNT: Int = ${doubles.length}
    internal const val INT32_HASH: UInt = ${i32}u
    internal const val UINT32_HASH: UInt = ${u32}u
}
`);
console.log(`wrote ${OUT}  (${doubles.length} doubles)`);
