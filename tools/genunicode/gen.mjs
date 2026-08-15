#!/usr/bin/env node
// Generates pure-Kotlin Unicode tables for ktecma262 from the UCD.
//
//   node tools/genunicode/gen.mjs <ucd-dir> <output-kotlin-file>
//
// The engine targets Kotlin Multiplatform, so it cannot use java.lang.Character.
// Every property ECMA-262 exposes through \p{...} is baked in here instead.
//
// Encoding: each property is a sorted, merged list of code point ranges,
// delta-encoded and packed into an ASCII string (5 payload bits per char, high
// bit = "another char follows"). Strings live in the class constant pool, which
// avoids the 64KB-per-method bytecode limit that a large intArrayOf(...) would
// blow through.

import fs from "node:fs";
import path from "node:path";

const UCD = process.argv[2];
const OUT = process.argv[3];
if (!UCD || !OUT) {
  console.error("usage: gen.mjs <ucd-dir> <output-kotlin-file>");
  process.exit(2);
}

const UNICODE_VERSION = "17.0.0";
const MAX_CP = 0x10ffff;

// ---------------------------------------------------------------- UCD parsing

const readLines = (f) => fs.readFileSync(path.join(UCD, f), "utf8").split("\n");
const stripComment = (l) => {
  const i = l.indexOf("#");
  return (i >= 0 ? l.slice(0, i) : l).trim();
};
const parseRange = (s) => {
  const [a, b] = s.split("..");
  const start = parseInt(a, 16);
  return [start, b === undefined ? start : parseInt(b, 16)];
};

/** Reads a `range ; value` UCD file into value -> ranges. */
function readPropFile(file) {
  const map = new Map();
  for (const raw of readLines(file)) {
    const l = stripComment(raw);
    if (!l) continue;
    const parts = l.split(";").map((s) => s.trim());
    if (parts.length < 2) continue;
    const [s, e] = parseRange(parts[0]);
    if (!Number.isFinite(s)) continue;
    if (!map.has(parts[1])) map.set(parts[1], []);
    map.get(parts[1]).push([s, e]);
  }
  return map;
}

/** Sorts and merges ranges, coalescing adjacent ones. */
function normalize(ranges) {
  const rs = ranges.map((r) => [r[0], r[1]]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const out = [];
  for (const r of rs) {
    const last = out[out.length - 1];
    if (last && r[0] <= last[1] + 1) last[1] = Math.max(last[1], r[1]);
    else out.push(r);
  }
  return out;
}

const union = (...lists) => normalize([].concat(...lists));

function complement(ranges) {
  const out = [];
  let next = 0;
  for (const [s, e] of ranges) {
    if (s > next) out.push([next, s - 1]);
    next = e + 1;
  }
  if (next <= MAX_CP) out.push([next, MAX_CP]);
  return out;
}

// ------------------------------------------------------------------ encoding

const ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
if (new Set(ALPHA).size !== 64) throw new Error("alphabet must hold 64 distinct chars");

function pushVarint(v, out) {
  if (v < 0) throw new Error(`varint must be non-negative, got ${v}`);
  do {
    let d = v & 31;
    v = Math.floor(v / 32);
    if (v > 0) d |= 32;
    out.push(ALPHA[d]);
  } while (v > 0);
}

const zigzag = (v) => (v < 0 ? -2 * v - 1 : 2 * v);

/** Encodes ranges as (gap-from-previous, length) varint pairs. */
function encodeRanges(ranges) {
  const out = [];
  let prev = 0;
  for (const [s, e] of ranges) {
    if (s < prev) throw new Error(`ranges must be sorted and disjoint: ${s} < ${prev}`);
    pushVarint(s - prev, out);
    pushVarint(e - s, out);
    prev = e + 1;
  }
  return out.join("");
}

/** Encodes a code point -> code point map as (gap, zigzag delta) varint pairs. */
function encodeMapping(pairs) {
  const sorted = [...pairs].sort((a, b) => a[0] - b[0]);
  const out = [];
  let prev = 0;
  for (const [cp, to] of sorted) {
    pushVarint(cp - prev, out);
    pushVarint(zigzag(to - cp), out);
    prev = cp;
  }
  return out.join("");
}

// -------------------------------------------------------------- general category

const gcRaw = readPropFile("extracted/DerivedGeneralCategory.txt");
const generalCategory = new Map();
for (const [k, v] of gcRaw) generalCategory.set(k, normalize(v));

// The one-letter groups are unions of their two-letter members, and LC is the
// cased-letter subset; the UCD does not list them explicitly.
const GROUPS = {
  L: ["Lu", "Ll", "Lt", "Lm", "Lo"],
  LC: ["Lu", "Ll", "Lt"],
  M: ["Mn", "Mc", "Me"],
  N: ["Nd", "Nl", "No"],
  P: ["Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po"],
  S: ["Sm", "Sc", "Sk", "So"],
  Z: ["Zs", "Zl", "Zp"],
  C: ["Cc", "Cf", "Cs", "Co", "Cn"],
};
for (const [g, members] of Object.entries(GROUPS)) {
  generalCategory.set(g, union(...members.map((m) => generalCategory.get(m) ?? [])));
}

// ---------------------------------------------------------------- scripts

const script = new Map();
for (const [k, v] of readPropFile("Scripts.txt")) script.set(k, normalize(v));

// Script_Extensions: values are short script codes, and any code point absent
// from the file inherits its Script value.
const scAliasToLong = new Map();
for (const raw of readLines("PropertyValueAliases.txt")) {
  const l = stripComment(raw);
  if (!l.startsWith("sc ;")) continue;
  const parts = l.split(";").map((s) => s.trim());
  const long = parts[2];
  for (const a of parts.slice(1)) scAliasToLong.set(a, long);
}

const scxAccum = new Map();
const scxListed = [];
for (const raw of readLines("ScriptExtensions.txt")) {
  const l = stripComment(raw);
  if (!l) continue;
  const parts = l.split(";").map((s) => s.trim());
  if (parts.length < 2) continue;
  const [s, e] = parseRange(parts[0]);
  scxListed.push([s, e]);
  for (const code of parts[1].split(/\s+/)) {
    const long = scAliasToLong.get(code);
    if (!long) throw new Error(`unknown script code in ScriptExtensions: ${code}`);
    if (!scxAccum.has(long)) scxAccum.set(long, []);
    scxAccum.get(long).push([s, e]);
  }
}
const scxListedNorm = normalize(scxListed);
const intersect = (a, b) => {
  const out = [];
  let i = 0, j = 0;
  while (i < a.length && j < b.length) {
    const s = Math.max(a[i][0], b[j][0]);
    const e = Math.min(a[i][1], b[j][1]);
    if (s <= e) out.push([s, e]);
    if (a[i][1] < b[j][1]) i++; else j++;
  }
  return out;
};
const subtract = (a, b) => intersect(a, complement(b));

const scriptExtensions = new Map();
for (const [name, ranges] of script) {
  // scx = explicit entries, plus this script's own code points that were not
  // overridden by an explicit ScriptExtensions line.
  const inherited = subtract(ranges, scxListedNorm);
  scriptExtensions.set(name, union(scxAccum.get(name) ?? [], inherited));
}
for (const [name, ranges] of scxAccum) {
  if (!scriptExtensions.has(name)) scriptExtensions.set(name, normalize(ranges));
}

// ------------------------------------------------------------ binary properties

const propList = readPropFile("PropList.txt");
const derivedCore = readPropFile("DerivedCoreProperties.txt");
const derivedBinary = readPropFile("extracted/DerivedBinaryProperties.txt");
const derivedNorm = readPropFile("DerivedNormalizationProps.txt");
const emojiData = readPropFile("emoji/emoji-data.txt");

// Exactly the binary properties ECMA-262 §22.2.1 exposes.
const BINARY_SOURCES = {
  ASCII_Hex_Digit: propList, Bidi_Control: propList, Dash: propList,
  Deprecated: propList, Diacritic: propList, Extender: propList,
  Hex_Digit: propList, IDS_Binary_Operator: propList, IDS_Trinary_Operator: propList,
  Ideographic: propList, Join_Control: propList, Logical_Order_Exception: propList,
  Noncharacter_Code_Point: propList, Pattern_Syntax: propList,
  Pattern_White_Space: propList, Quotation_Mark: propList, Radical: propList,
  Regional_Indicator: propList, Sentence_Terminal: propList, Soft_Dotted: propList,
  Terminal_Punctuation: propList, Unified_Ideograph: propList,
  Variation_Selector: propList, White_Space: propList,

  Alphabetic: derivedCore, Case_Ignorable: derivedCore, Cased: derivedCore,
  Changes_When_Casefolded: derivedCore, Changes_When_Casemapped: derivedCore,
  Changes_When_Lowercased: derivedCore, Changes_When_Titlecased: derivedCore,
  Changes_When_Uppercased: derivedCore, Default_Ignorable_Code_Point: derivedCore,
  Grapheme_Base: derivedCore, Grapheme_Extend: derivedCore,
  ID_Continue: derivedCore, ID_Start: derivedCore, Lowercase: derivedCore,
  Math: derivedCore, Uppercase: derivedCore,
  XID_Continue: derivedCore, XID_Start: derivedCore,

  Bidi_Mirrored: derivedBinary,
  Changes_When_NFKC_Casefolded: derivedNorm,

  Emoji: emojiData, Emoji_Component: emojiData, Emoji_Modifier: emojiData,
  Emoji_Modifier_Base: emojiData, Emoji_Presentation: emojiData,
  Extended_Pictographic: emojiData,
};

const binary = new Map();
const missing = [];
for (const [name, src] of Object.entries(BINARY_SOURCES)) {
  const ranges = src.get(name);
  if (!ranges) { missing.push(name); continue; }
  binary.set(name, normalize(ranges));
}
if (missing.length) {
  // Fail loudly: a silently absent property would match nothing at runtime.
  throw new Error(`properties not found in the UCD: ${missing.join(", ")}`);
}

// Computed properties with no UCD file of their own.
binary.set("ASCII", [[0, 0x7f]]);
binary.set("Any", [[0, MAX_CP]]);
binary.set("Assigned", complement(generalCategory.get("Cn") ?? []));

// ------------------------------------------------------------------- aliases

const binaryAliases = new Map();
for (const raw of readLines("PropertyAliases.txt")) {
  const l = stripComment(raw);
  if (!l) continue;
  const parts = l.split(";").map((s) => s.trim());
  const canonical = parts.find((p) => binary.has(p));
  if (!canonical) continue;
  for (const a of parts) if (a && a !== canonical) binaryAliases.set(a, canonical);
}

const gcAliases = new Map();
const scAliases = new Map();
for (const raw of readLines("PropertyValueAliases.txt")) {
  const l = stripComment(raw);
  if (!l) continue;
  const parts = l.split(";").map((s) => s.trim());
  if (parts[0] === "gc") {
    const canonical = parts.slice(1).find((p) => generalCategory.has(p));
    if (canonical) for (const a of parts.slice(1)) if (a !== canonical) gcAliases.set(a, canonical);
  } else if (parts[0] === "sc") {
    const canonical = parts.slice(1).find((p) => script.has(p) || scriptExtensions.has(p));
    if (canonical) for (const a of parts.slice(1)) if (a !== canonical) scAliases.set(a, canonical);
  }
}

// -------------------------------------------------------------- case mappings

// Simple case folding (status C + S) drives case-insensitive matching under /u.
const caseFold = [];
for (const raw of readLines("CaseFolding.txt")) {
  const l = stripComment(raw);
  if (!l) continue;
  const parts = l.split(";").map((s) => s.trim());
  if (parts.length < 3) continue;
  const status = parts[1];
  if (status !== "C" && status !== "S") continue;
  const cp = parseInt(parts[0], 16);
  const to = parseInt(parts[2], 16);
  if (cp !== to) caseFold.push([cp, to]);
}

// Simple uppercase mapping drives the legacy (non-/u) Canonicalize.
const simpleUpper = [];
for (const raw of readLines("UnicodeData.txt")) {
  if (!raw.trim()) continue;
  const f = raw.split(";");
  const cp = parseInt(f[0], 16);
  const up = f[12];
  if (up) {
    const to = parseInt(up, 16);
    if (to !== cp) simpleUpper.push([cp, to]);
  }
}

// ---------------------------------------------------- properties of strings

// The `v` flag exposes a handful of *properties of strings* — sets whose members
// are emoji sequences rather than single code points. Their data lives in the
// UTS #51 files, not the UCD proper.

function readEmojiSequences(file) {
  const map = new Map();
  for (const raw of readLines(file)) {
    const l = stripComment(raw);
    if (!l) continue;
    const parts = l.split(";").map((s) => s.trim());
    if (parts.length < 2) continue;
    const prop = parts[1];
    if (!map.has(prop)) map.set(prop, []);
    const field = parts[0];
    if (field.includes("..")) {
      // A range line always denotes single code points.
      const [a, b] = field.split("..").map((h) => parseInt(h, 16));
      for (let cp = a; cp <= b; cp++) map.get(prop).push([cp]);
    } else {
      map.get(prop).push(field.split(/\s+/).map((h) => parseInt(h, 16)));
    }
  }
  return map;
}

const emojiSeq = readEmojiSequences("emoji-sequences.txt");
const emojiZwj = readEmojiSequences("emoji-zwj-sequences.txt");

const STRING_PROPERTY_SOURCES = {
  Basic_Emoji: emojiSeq,
  Emoji_Keycap_Sequence: emojiSeq,
  RGI_Emoji_Flag_Sequence: emojiSeq,
  RGI_Emoji_Modifier_Sequence: emojiSeq,
  RGI_Emoji_Tag_Sequence: emojiSeq,
  RGI_Emoji_ZWJ_Sequence: emojiZwj,
};

const propertiesOfStrings = new Map();
const missingStringProps = [];
for (const [name, src] of Object.entries(STRING_PROPERTY_SOURCES)) {
  const seqs = src.get(name);
  if (!seqs) { missingStringProps.push(name); continue; }
  propertiesOfStrings.set(name, seqs);
}
if (missingStringProps.length) {
  throw new Error(`properties of strings not found: ${missingStringProps.join(", ")}`);
}

// RGI_Emoji is the union of the other six.
propertiesOfStrings.set(
  "RGI_Emoji",
  [...propertiesOfStrings.values()].flat(),
);

/** Encodes sequences as varint(length) followed by one varint per code point. */
function encodeSequences(seqs) {
  const out = [];
  for (const seq of seqs) {
    pushVarint(seq.length, out);
    for (const cp of seq) pushVarint(cp, out);
  }
  return out.join("");
}

// ------------------------------------------------------- case-equivalence orbits

// Case-insensitive matching needs more than "fold this character": ECMA-262 asks
// whether ANY member of a CharSet canonicalizes to the same value as the input.
// Precomputing each equivalence class as a cycle lets the compiler expand a set
// to its case closure, which is what makes /[Y-b]/i match both "y" and "a".

const scfOf = new Map(caseFold);
const scf = (cp) => scfOf.get(cp) ?? cp;
for (const [, to] of caseFold) {
  if (scf(to) !== to) throw new Error(`fold target U+${to.toString(16)} is not itself a fold fixpoint`);
}

const upOf = new Map(simpleUpper);
const up = (cp) => upOf.get(cp) ?? cp;
// The legacy (non-/u) Canonicalize: simple uppercase, except a non-ASCII
// character never canonicalizes onto an ASCII one (so ſ does not match "s").
const legacyCanon = (cp) => {
  const u = up(cp);
  return cp >= 128 && u < 128 ? cp : u;
};

/** Groups candidates into equivalence classes and emits them as cycles. */
function orbitCycles(candidates, canonFn) {
  const classes = new Map();
  for (const cp of candidates) {
    const k = canonFn(cp);
    if (!classes.has(k)) classes.set(k, new Set());
    classes.get(k).add(cp);
  }
  const pairs = [];
  for (const [k, members] of classes) {
    // The canonical value is itself a member whenever it canonicalizes to itself.
    if (canonFn(k) === k) members.add(k);
    if (members.size < 2) continue;
    const sorted = [...members].sort((a, b) => a - b);
    for (let i = 0; i < sorted.length; i++) {
      pairs.push([sorted[i], sorted[(i + 1) % sorted.length]]);
    }
  }
  return pairs;
}

const foldCandidates = new Set();
for (const [cp, to] of caseFold) { foldCandidates.add(cp); foldCandidates.add(to); }
const foldOrbit = orbitCycles(foldCandidates, scf);

const upperCandidates = new Set();
for (const [cp, to] of simpleUpper) { upperCandidates.add(cp); upperCandidates.add(to); }
const legacyOrbit = orbitCycles(upperCandidates, legacyCanon);

// ------------------------------------------------------------------- emit

const MAX_LITERAL = 60000; // stay clear of the 64KB constant-pool string limit

function kotlinString(s) {
  if (s.length > MAX_LITERAL) throw new Error(`encoded table too long (${s.length})`);
  return `"${s}"`; // alphabet is ASCII with no escapes, quotes, backslashes or '$'
}

function emitMap(name, map) {
  const entries = [...map.entries()]
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([k, v]) => `        "${k}" to ${kotlinString(encodeRanges(v))},`)
    .join("\n");
  return `    internal val ${name}: Map<String, String> = mapOf(\n${entries}\n    )\n`;
}

function emitAliasMap(name, map) {
  const entries = [...map.entries()]
    .sort((a, b) => (a[0] < b[0] ? -1 : 1))
    .map(([k, v]) => `        "${k}" to "${v}",`)
    .join("\n");
  return `    internal val ${name}: Map<String, String> = mapOf(\n${entries}\n    )\n`;
}

const stringPropertyEntries = [...propertiesOfStrings.entries()]
  .sort((a, b) => (a[0] < b[0] ? -1 : 1))
  .map(([k, v]) => `        "${k}" to ${kotlinString(encodeSequences(v))},`)
  .join("\n");

const parts = [];
parts.push(`// GENERATED FILE - DO NOT EDIT.
// Regenerate with: node tools/genunicode/gen.mjs <ucd-dir> ${path.basename(OUT)}
// Source: Unicode Character Database ${UNICODE_VERSION}
@file:Suppress("LargeClass", "MaxLineLength")

package io.github.mgilbir.ecma262.unicode

/**
 * Unicode data tables, delta-encoded into ASCII strings.
 *
 * Kept as strings rather than int arrays because a table this size emitted as
 * \`intArrayOf(...)\` would exceed the JVM's 64KB method bytecode limit in the
 * static initializer. See [RangeTable] for the decoder.
 */
internal object UnicodeTables {
    internal const val UNICODE_VERSION: String = "${UNICODE_VERSION}"

    /** Alphabet for the varint encoding: 5 payload bits per char, bit 5 = continuation. */
    internal const val ALPHABET: String = "${ALPHA}"
`);
parts.push(emitMap("generalCategory", generalCategory));
parts.push(emitMap("script", script));
parts.push(emitMap("scriptExtensions", scriptExtensions));
parts.push(emitMap("binary", binary));
parts.push(emitAliasMap("generalCategoryAliases", gcAliases));
parts.push(emitAliasMap("scriptAliases", scAliases));
parts.push(emitAliasMap("binaryAliases", binaryAliases));
parts.push(`
    /** Simple case folding (CaseFolding.txt status C and S), for /u matching. */
    internal val simpleCaseFolding: String = ${kotlinString(encodeMapping(caseFold))}

    /** Simple uppercase mapping, for the legacy non-/u Canonicalize. */
    internal val simpleUppercase: String = ${kotlinString(encodeMapping(simpleUpper))}

    /**
     * Case-equivalence classes under simple case folding, stored as cycles:
     * each entry maps a code point to the next member of its class. Following
     * the chain back to the start enumerates the class. Used under /iu.
     */
    internal val foldOrbit: String = ${kotlinString(encodeMapping(foldOrbit))}

    /** The same, for the legacy uppercase-based Canonicalize used under /i. */
    internal val legacyOrbit: String = ${kotlinString(encodeMapping(legacyOrbit))}

    /**
     * Properties of strings, exposed by the \`v\` flag only.
     *
     * Members are emoji sequences rather than single code points, so these are
     * stored as varint(length) followed by one varint per code point.
     */
    internal val propertiesOfStrings: Map<String, String> = mapOf(
${stringPropertyEntries}
    )
}
`);

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, parts.join("\n"));

const bytes = fs.statSync(OUT).size;
console.log(`wrote ${OUT} (${(bytes / 1024).toFixed(1)} KiB)`);
console.log(`  general categories: ${generalCategory.size}`);
console.log(`  scripts:            ${script.size}`);
console.log(`  script extensions:  ${scriptExtensions.size}`);
console.log(`  binary properties:  ${binary.size}`);
console.log(`  case fold entries:  ${caseFold.length}`);
console.log(`  uppercase entries:  ${simpleUpper.length}`);
console.log(`  fold orbit pairs:   ${foldOrbit.length}`);
console.log(`  legacy orbit pairs: ${legacyOrbit.length}`);
for (const [k, v] of propertiesOfStrings) console.log(`  strings ${k.padEnd(28)} ${v.length}`);
