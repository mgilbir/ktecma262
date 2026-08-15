#!/usr/bin/env node
// Records a fingerprint of RegExp.escape over every code point, so the Kotlin
// implementation can be checked against the real one without a JS engine.
//
//   node tools/difftest/gen-escape-fixture.mjs <output-kotlin-file>
//
// Escaping is security-relevant — it is what callers use to splice untrusted
// text into a pattern — so it is verified over the whole range rather than a
// sample.

import fs from "node:fs";
import path from "node:path";

const OUT = process.argv[2];
if (!OUT) {
  console.error("usage: gen-escape-fixture.mjs <output-kotlin-file>");
  process.exit(2);
}
if (typeof RegExp.escape !== "function") {
  console.error("this node has no RegExp.escape");
  process.exit(2);
}

/** FNV-1a over a string's code units. */
function fnv1a(h, s) {
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

// Single code points, escaped in isolation (so each is the *first* character).
let single = 0x811c9dc5;
// The same code points with an "x" in front, which exercises the non-first path.
let trailing = 0x811c9dc5;
let count = 0;

for (let cp = 0; cp <= 0x10ffff; cp++) {
  const ch = String.fromCodePoint(cp);
  single = fnv1a(single, RegExp.escape(ch));
  trailing = fnv1a(trailing, RegExp.escape("x" + ch));
  count++;
}

// A handful of readable spot checks, so a failure is diagnosable.
const spots = [
  "", "a", "abc", "a.b*c", "0abc", "_abc", ".abc", "-", "a-b", "hello world",
  "\t\n\r", "\u00A0", "\u2028", "\u{1F600}", "\uD83D", "$1", "^a$", "[a-z]",
  "a/b", "c:\\path", "«quoted»", "#tag", "~x~",
];
// Spot checks travel in the same pure-ASCII transport the differential fixture
// uses: Kotlin/JS turns a lone surrogate in a compile-time constant into "?",
// and one of these inputs is exactly that.
const spotBlob = spots
  .map((s) => {
    const e = RegExp.escape(s);
    return `${s.length}:${s}${e.length}:${e}`;
  })
  .join("");

function toAsciiTransport(str) {
  let out = "";
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    if (c === 0x5c) out += "\\\\";
    else if (c >= 0x20 && c <= 0x7e) out += str[i];
    else out += "\\" + c.toString(16).padStart(4, "0");
  }
  return out;
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

const src = `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/difftest/gen-escape-fixture.mjs against node ${process.version}.

package io.github.mgilbir.ecma262

internal object EscapeFixture {
    internal const val CODE_POINTS: Int = ${count}

    /** FNV-1a over RegExp.escape(c) for every code point c. */
    internal const val SINGLE_HASH: UInt = ${single}u

    /** The same, for RegExp.escape("x" + c) — the non-leading path. */
    internal const val TRAILING_HASH: UInt = ${trailing}u

    private const val SPOTS: String = ${ktLiteral(toAsciiTransport(spotBlob))}

    /** input -> expected escaped form, decoded from the ASCII transport. */
    internal val spotChecks: Map<String, String> by lazy {
        val s = StringBuilder(SPOTS.length).apply {
            var i = 0
            while (i < SPOTS.length) {
                val c = SPOTS[i]
                if (c != '\\\\') {
                    append(c); i++
                } else if (SPOTS[i + 1] == '\\\\') {
                    append('\\\\'); i += 2
                } else {
                    append(SPOTS.substring(i + 1, i + 5).toInt(16).toChar()); i += 5
                }
            }
        }.toString()

        val out = LinkedHashMap<String, String>()
        var i = 0
        fun read(): String {
            val colon = s.indexOf(':', i)
            val len = s.substring(i, colon).toInt()
            val v = s.substring(colon + 1, colon + 1 + len)
            i = colon + 1 + len
            return v
        }
        while (i < s.length) {
            val input = read()
            out[input] = read()
        }
        out
    }
}
`;

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, src);
console.log(`wrote ${OUT}`);
console.log(`  code points hashed: ${count}`);
console.log(`  spot checks:        ${spots.length}`);
