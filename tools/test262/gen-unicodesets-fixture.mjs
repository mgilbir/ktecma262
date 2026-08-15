#!/usr/bin/env node
// Turns Test262's generated `v`-flag class tests into a Kotlin fixture.
//
//   node tools/test262/gen-unicodesets-fixture.mjs <output-kotlin-file> [cache-dir]
//
// These are hand-curated conformance tests for the ClassSetExpression grammar —
// nesting, set operations, `\q{}` string literals and properties of strings —
// which is the part of this engine written from the specification rather than
// ported. They are an independent check on the differential suite, whose oracle
// is a single implementation.
//
// Each test file calls `testExtendedCharacterClass({regExp, matchStrings,
// nonMatchStrings})`. Rather than parse them, the file is evaluated with that
// function stubbed out, so the patterns and strings are captured exactly.

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const OUT = process.argv[2];
const CACHE = process.argv[3] ?? ".test262-cache";
if (!OUT) {
  console.error("usage: gen-unicodesets-fixture.mjs <output-kotlin-file> [cache-dir]");
  process.exit(2);
}

const DIR = "test/built-ins/RegExp/unicodeSets/generated";
const API = `https://api.github.com/repos/tc39/test262/contents/${DIR}`;
const RAW = `https://raw.githubusercontent.com/tc39/test262/main/${DIR}`;

async function fetchText(url) {
  const res = await fetch(url, { headers: { "user-agent": "ktecma262-build" } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.text();
}

async function listFiles() {
  const cached = path.join(CACHE, "_index.json");
  if (fs.existsSync(cached)) return JSON.parse(fs.readFileSync(cached, "utf8"));
  const names = JSON.parse(await fetchText(API))
    .filter((e) => e.type === "file" && e.name.endsWith(".js"))
    .map((e) => e.name);
  fs.mkdirSync(CACHE, { recursive: true });
  fs.writeFileSync(cached, JSON.stringify(names));
  return names;
}

async function getFile(name) {
  const cached = path.join(CACHE, name);
  if (fs.existsSync(cached)) return fs.readFileSync(cached, "utf8");
  const body = await fetchText(`${RAW}/${name}`);
  fs.mkdirSync(CACHE, { recursive: true });
  fs.writeFileSync(cached, body);
  return body;
}

const names = await listFiles();
console.log(`${names.length} Test262 files`);

const cases = [];
let skipped = 0;

for (const name of names) {
  const source = await getFile(name);
  const captured = [];
  const context = vm.createContext({
    testExtendedCharacterClass: (spec) => captured.push(spec),
    // The rgi-emoji-*.js files use this one; they cover properties of strings.
    testPropertyOfStrings: (spec) => captured.push(spec),
    // Any other helper from regExpUtils.js means the file cannot be read
    // faithfully, so it is skipped rather than silently mis-interpreted.
    buildString: () => {
      throw new Error("buildString unsupported");
    },
  });
  try {
    vm.runInContext(source, context, { timeout: 5000 });
  } catch {
    skipped++;
    continue;
  }
  for (const spec of captured) {
    if (!spec?.regExp) continue;
    cases.push({
      file: name,
      pattern: spec.regExp.source,
      flags: spec.regExp.flags,
      // testPropertyOfStrings supplies only matchStrings.
      match: spec.matchStrings ?? [],
      nonMatch: spec.nonMatchStrings ?? [],
    });
  }
}

console.log(`  captured ${cases.length} class expressions (${skipped} files skipped)`);

// --- encode, using the same ASCII transport as the differential fixture -------
const parts = [];
const str = (s) => `${s.length}:${s}`;
for (const c of cases) {
  parts.push(`${c.match.length},${c.nonMatch.length},`);
  parts.push(str(c.pattern));
  parts.push(str(c.flags));
  parts.push(str(c.file));
  for (const s of c.match) parts.push(str(s));
  for (const s of c.nonMatch) parts.push(str(s));
}
const blob = parts.join("");

function toAsciiTokens(s) {
  const tokens = [];
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 0x5c) tokens.push("\\\\");
    else if (c >= 0x20 && c <= 0x7e) tokens.push(s[i]);
    else tokens.push("\\" + c.toString(16).padStart(4, "0"));
  }
  return tokens;
}

function ktLiteral(s) {
  let out = '"';
  for (const ch of s) {
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (ch === "$") out += "\\$";
    else out += ch;
  }
  return out + '"';
}

const CHUNK = 20000;
const chunks = [];
{
  let current = "";
  for (const token of toAsciiTokens(blob)) {
    if (current.length + token.length > CHUNK) {
      chunks.push(current);
      current = "";
    }
    current += token;
  }
  if (current.length > 0) chunks.push(current);
}

const chunkDecls = chunks
  .map((c, i) => `    private const val C${i}: String = ${ktLiteral(c)}`)
  .join("\n");
const chunkRefs = chunks.map((_, i) => `C${i}`).join(", ");

const src = `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/test262/gen-unicodesets-fixture.mjs from
// test262/${DIR}.
//
// Hand-curated conformance cases for the \`v\` flag's ClassSetExpression grammar,
// independent of the node-derived differential corpus.

package io.github.mgilbir.ecma262

internal object Test262UnicodeSetsFixture {
    internal const val CASE_COUNT: Int = ${cases.length}

    internal class Case(
        val pattern: String,
        val flags: String,
        val file: String,
        val matchStrings: List<String>,
        val nonMatchStrings: List<String>,
    ) {
        override fun toString(): String = "/" + pattern + "/" + flags + "  (" + file + ")"
    }

${chunkDecls}

    /** Same ASCII transport as DiffFixture; see LoneSurrogateLiteralTest. */
    private fun decodeTransport(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\\\') {
                sb.append(c)
                i++
                continue
            }
            if (s[i + 1] == '\\\\') {
                sb.append('\\\\')
                i += 2
            } else {
                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                i += 5
            }
        }
        return sb.toString()
    }

    private val parsed: List<Case> by lazy {
        val s = decodeTransport(listOf(${chunkRefs}).joinToString(""))
        val out = ArrayList<Case>(CASE_COUNT)
        var i = 0

        fun readInt(terminator: Char): Int {
            val start = i
            while (s[i] != terminator) i++
            val v = s.substring(start, i).toInt()
            i++
            return v
        }

        fun readString(): String {
            val len = readInt(':')
            val v = s.substring(i, i + len)
            i += len
            return v
        }

        while (i < s.length) {
            val matchCount = readInt(',')
            val nonMatchCount = readInt(',')
            val pattern = readString()
            val flags = readString()
            val file = readString()
            val match = ArrayList<String>(matchCount)
            repeat(matchCount) { match.add(readString()) }
            val nonMatch = ArrayList<String>(nonMatchCount)
            repeat(nonMatchCount) { nonMatch.add(readString()) }
            out.add(Case(pattern, flags, file, match, nonMatch))
        }
        check(out.size == CASE_COUNT) { "decoded \${out.size}, expected \$CASE_COUNT" }
        out
    }

    internal fun all(): List<Case> = parsed
}
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, src);
console.log(`wrote ${OUT} (${(fs.statSync(OUT).size / 1024).toFixed(0)} KiB, ${chunks.length} chunks)`);
