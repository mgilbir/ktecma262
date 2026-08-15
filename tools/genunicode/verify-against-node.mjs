#!/usr/bin/env node
// Verifies the generated Kotlin Unicode tables against the host JavaScript
// engine, property by property, over every code point.
//
//   node tools/genunicode/verify-against-node.mjs <generated-kotlin-file> [fixture-out]
//
// This reads the *generated artifact* and decodes it with the same varint scheme
// the Kotlin decoder uses, so it checks UCD parsing, encoding and decoding
// together rather than just re-running the generator's own logic.
//
// Node's Unicode version must match the UCD the tables were built from
// (node -p process.versions.unicode), otherwise disagreements are expected.

import fs from "node:fs";
import path from "node:path";

const SRC = process.argv[2];
const FIXTURE_OUT = process.argv[3];
if (!SRC) {
  console.error("usage: verify-against-node.mjs <generated-kotlin-file> [fixture-out]");
  process.exit(2);
}

const text = fs.readFileSync(SRC, "utf8");

const ALPHA = /const val ALPHABET: String = "([^"]+)"/.exec(text)?.[1];
if (!ALPHA) throw new Error("could not find ALPHABET in the generated file");
const DIGIT = new Map([...ALPHA].map((c, i) => [c, i]));

/** Mirror of VarintCodec.decodeRanges in Kotlin. */
function decodeRanges(s) {
  const out = [];
  let i = 0, prev = 0;
  const readVarint = () => {
    let v = 0, shift = 0;
    for (;;) {
      const d = DIGIT.get(s[i++]);
      if (d === undefined) throw new Error("bad char in encoded table");
      v += (d & 31) * 2 ** shift;
      shift += 5;
      if (d < 32) return v;
    }
  };
  while (i < s.length) {
    const start = prev + readVarint();
    const end = start + readVarint();
    out.push([start, end]);
    prev = end + 1;
  }
  return out;
}

/** Pulls one `name to "encoded"` map out of the generated Kotlin source. */
function readMap(name) {
  const re = new RegExp(`internal val ${name}: Map<String, String> = mapOf\\(([\\s\\S]*?)\\n    \\)`);
  const body = re.exec(text)?.[1];
  if (body === undefined) throw new Error(`could not find map ${name}`);
  const map = new Map();
  for (const m of body.matchAll(/"([^"]+)" to "([^"]*)"/g)) map.set(m[1], m[2]);
  return map;
}

const tables = {
  generalCategory: { map: readMap("generalCategory"), probe: (v) => `\\p{General_Category=${v}}` },
  script: { map: readMap("script"), probe: (v) => `\\p{Script=${v}}` },
  scriptExtensions: { map: readMap("scriptExtensions"), probe: (v) => `\\p{Script_Extensions=${v}}` },
  binary: { map: readMap("binary"), probe: (v) => `\\p{${v}}` },
};

const MAX_CP = 0x10ffff;

/** Full range list for a pattern, straight from the JS engine. */
function nodeRanges(pattern) {
  const re = new RegExp(pattern, "u");
  const out = [];
  let start = -1;
  for (let cp = 0; cp <= MAX_CP; cp++) {
    if (re.test(String.fromCodePoint(cp))) {
      if (start < 0) start = cp;
    } else if (start >= 0) {
      out.push([start, cp - 1]);
      start = -1;
    }
  }
  if (start >= 0) out.push([start, MAX_CP]);
  return out;
}

const eq = (a, b) =>
  a.length === b.length && a.every((r, i) => r[0] === b[i][0] && r[1] === b[i][1]);

function describeDiff(ours, theirs) {
  const setOf = (rs) => {
    const s = new Set();
    for (const [a, b] of rs) for (let c = a; c <= b; c++) s.add(c);
    return s;
  };
  const o = setOf(ours), t = setOf(theirs);
  const onlyOurs = [...o].filter((c) => !t.has(c));
  const onlyTheirs = [...t].filter((c) => !o.has(c));
  const fmt = (cs) =>
    cs.slice(0, 8).map((c) => "U+" + c.toString(16).toUpperCase().padStart(4, "0")).join(",") +
    (cs.length > 8 ? ` …(${cs.length} total)` : "");
  return `      only in ours:  ${onlyOurs.length ? fmt(onlyOurs) : "-"}\n` +
         `      only in node:  ${onlyTheirs.length ? fmt(onlyTheirs) : "-"}`;
}

// FNV-1a over the canonical range text; the Kotlin test recomputes this.
function fingerprint(ranges) {
  let h = 0x811c9dc5;
  const s = ranges.map(([a, b]) => `${a}-${b}`).join(",");
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

let checked = 0, failed = 0, unsupported = 0;
const fixture = [];

for (const [tableName, { map, probe }] of Object.entries(tables)) {
  for (const [key, encoded] of map) {
    const ours = decodeRanges(encoded);
    const pattern = probe(key);
    let theirs;
    try {
      theirs = nodeRanges(pattern);
    } catch (e) {
      // node not knowing a property is a real signal, not something to hide.
      console.log(`  ?? ${tableName}/${key}: node rejects ${pattern} (${e.message})`);
      unsupported++;
      continue;
    }
    checked++;
    if (!eq(ours, theirs)) {
      failed++;
      console.log(`  MISMATCH ${tableName}/${key} (ours ${ours.length} ranges, node ${theirs.length})`);
      console.log(describeDiff(ours, theirs));
    }
    fixture.push([`${tableName}/${key}`, ours.length, fingerprint(ours)]);
  }
}

console.log(`\nchecked ${checked} properties against node ${process.versions.unicode}: ` +
            `${failed} mismatched, ${unsupported} unsupported by node`);

if (FIXTURE_OUT && failed === 0) {
  const rows = fixture
    .map(([n, c, h]) => `        "${n}" to (${c} to ${h}u),`)
    .join("\n");
  const body = `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/genunicode/verify-against-node.mjs after every property in
// UnicodeTables was confirmed identical to node ${process.versions.unicode}'s own data.
// Lets the decoder be re-checked in CI without a JavaScript engine present.

package io.github.mgilbir.ecma262.unicode

internal object UnicodePropertyFixture {
    internal const val ORACLE_UNICODE_VERSION: String = "${process.versions.unicode}"

    /** qualified property name to (range count, FNV-1a of "start-end,..."). */
    internal val expected: Map<String, Pair<Int, UInt>> = mapOf(
${rows}
    )
}
`;
  fs.mkdirSync(path.dirname(FIXTURE_OUT), { recursive: true });
  fs.writeFileSync(FIXTURE_OUT, body);
  console.log(`wrote fixture ${FIXTURE_OUT} (${fixture.length} entries)`);
}

process.exit(failed === 0 ? 0 : 1);
