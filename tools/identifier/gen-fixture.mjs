#!/usr/bin/env node
// Records which code points may start or continue an identifier, and checks a
// sample against what the engine will actually accept.
//
//   node tools/identifier/gen-fixture.mjs <output-kotlin-file>
//
// Two levels, because they answer different questions. The exhaustive pass uses
// the composed rule from 12.7 as a regular expression, which is cheap enough to
// run over all 1,114,112 code points. The sampled pass builds a function and
// sees whether the parser accepts the name, which is the real question but far
// too slow to ask a million times.
import { writeFileSync } from "node:fs";

const OUT = process.argv[2];
if (!OUT) { console.error("usage: gen-fixture.mjs <out>"); process.exit(2); }

const START = /[\p{ID_Start}$_]/u;
const PART = /[\p{ID_Continue}$‌‍]/u;

const fnv1a = (h, s) => {
  for (let i = 0; i < s.length; i++) {
    h = (h ^ s.charCodeAt(i)) >>> 0;
    h = Math.imul(h, 16777619) >>> 0;
  }
  h = (h ^ 0x7c) >>> 0;
  return Math.imul(h, 16777619) >>> 0;
};

let startHash = 2166136261 >>> 0;
let partHash = 2166136261 >>> 0;
let starts = 0;
let parts = 0;
let counted = 0;
for (let cp = 0; cp <= 0x10ffff; cp++) {
  if (cp >= 0xd800 && cp <= 0xdfff) continue;
  const s = String.fromCodePoint(cp);
  const isStart = START.test(s);
  const isPart = PART.test(s);
  if (isStart) starts++;
  if (isPart) parts++;
  startHash = fnv1a(startHash, isStart ? "1" : "0");
  partHash = fnv1a(partHash, isPart ? "1" : "0");
  counted++;
}

// The authoritative check, on a sample: does the parser accept it as a name?
const accepts = (s) => {
  try { new Function("var " + s + ";"); return true; } catch { return false; }
};
// And as an object literal key, which is the IdentifierName question.
//
// Not `o.<s>`: for "a-b" that parses as `(o.a) - b` and looks valid. A literal
// key has to be an IdentifierName, a string, or a number, and the numeric case
// is excluded separately.
const acceptsAsName = (s) => {
  if (/^[0-9]/.test(s)) return false; // a numeric key is not an IdentifierName
  try { new Function("var o = {" + s + ": 1};"); return true; } catch { return false; }
};

const sample = [
  "a", "_", "$", "a1", "1a", "a-b", "", "ab", "日本語", "À", "ʰ", "a·b", "·",
  "if", "true", "null", "await", "yield", "let", "static", "undefined", "NaN",
  "class", "enum", "public", "with", "$$", "_$_", "a‌b", "a‍b",
  "‌ab", "€", "ᅠ", "x​y", "a𝐀", "𝐀", "\uD800", "a\uD800",
  "℘", "ℬ", "A", "aA0_$", "for", "of", "as", "from", "async", "get", "set",
];
const cases = sample.map((s) => ({
  s,
  name: acceptsAsName(s),
  binding: accepts(s),
}));

const units = (s) =>
  "intArrayOf(" +
  Array.from({ length: s.length }, (_, i) => "0x" + s.charCodeAt(i).toString(16).toUpperCase()).join(", ") +
  ")";

writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/identifier/gen-fixture.mjs against node ${process.version}.
//
// The hashes cover every code point using the composed rule from 12.7. The
// explicit cases are checked by actually parsing them, which is the real
// question and too slow to ask 1,114,112 times.
//
// Inputs are code unit arrays: some are lone surrogates, which Kotlin/JS
// rewrites inside a compile-time constant.

package io.github.mgilbir.ecma262.text

internal object IdentifierFixture {
    internal const val ORACLE: String = "node ${process.version}"

    internal const val CODE_POINTS: Int = ${counted}
    internal const val START_COUNT: Int = ${starts}
    internal const val PART_COUNT: Int = ${parts}
    internal const val START_HASH: UInt = ${startHash}u
    internal const val PART_HASH: UInt = ${partHash}u

    /** [isName] is "obj.x parses"; [isBinding] is "var x parses". */
    internal class Case(val units: IntArray, val isName: Boolean, val isBinding: Boolean)

    internal val EXPLICIT: List<Case> = listOf(
${cases.map((c) => `        Case(${units(c.s)}, ${c.name}, ${c.binding}),`).join("\n")}
    )
}
`);
console.log(`wrote ${OUT}`);
console.log(`  code points: ${counted}, starts: ${starts}, parts: ${parts}`);
console.log(`  explicit:    ${cases.length}`);
