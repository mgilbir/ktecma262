#!/usr/bin/env node
// Oracle side of the live fuzzer.
//
// Reads one case per line from stdin and writes one result per line to stdout,
// preserving order. Strings travel as base64 of their UTF-16 code units, so any
// character — newlines, lone surrogates, control characters — survives the round
// trip byte for byte.
//
// Request:  <op> <b64 pattern> <b64 flags> <b64 input> [<b64 extra>]
//   op = "x"  single exec from index 0
//      = "a"  all matches, as String.prototype.matchAll (requires the g flag)
//      = "r"  String.prototype.replace with the extra field as the replacement
//      = "s"  String.prototype.split with the extra field as a decimal limit
//
// Response: "E"                            syntax error
//           "N"                            no match
//           "M <index> <n> <g0> ..."       a match; each group is b64, or "-" for null
//           "A <count> [<match> | ...]"    all matches, each rendered as above
//           "R <b64 result>"               replace result
//           "S <count> <b64 part> ..."     split result ("-" for an undefined part)
//           "T"                            oracle-side failure (not a spec behaviour)
// A leading "!", "~" or "%" marks a known V8 defect the comparison skips.

import readline from "node:readline";
import { hasSingleCharQuotedString, hasModifierWithWordEscape } from "./corpus.mjs";

/** base64 of UTF-16LE code units -> string, preserving lone surrogates. */
function decode(s) {
  const buf = Buffer.from(s, "base64");
  let out = "";
  for (let i = 0; i + 1 < buf.length; i += 2) out += String.fromCharCode(buf.readUInt16LE(i));
  return out;
}

function encode(s) {
  const buf = Buffer.alloc(s.length * 2);
  for (let i = 0; i < s.length; i++) buf.writeUInt16LE(s.charCodeAt(i), i * 2);
  return buf.toString("base64");
}

const renderMatch = (m) => {
  const groups = [...m].map((g) => (g === undefined ? "-" : encode(g)));
  return `${m.index} ${groups.length} ${groups.join(" ")}`;
};

const splitsPair = (s, i) =>
  i > 0 && i < s.length &&
  s.charCodeAt(i - 1) >= 0xd800 && s.charCodeAt(i - 1) <= 0xdbff &&
  s.charCodeAt(i) >= 0xdc00 && s.charCodeAt(i) <= 0xdfff;

/**
 * True when this engine placed a match at a position that splits a surrogate
 * pair under u/v.
 *
 * ECMA-262 matches such a pattern over code points, so no match can begin
 * inside a pair — but V8 does exactly that for zero-width assertions
 * (`/\B/u.exec("b😀").index` is 2 where the spec requires 3). Flagging the
 * case here lets the comparison skip it rather than report a false failure.
 */
function hasSurrogateSplitMatch(re, input, allMatches) {
  if (!/[uv]/.test(re.flags)) return false;
  if (!allMatches) {
    const probe = new RegExp(re.source, re.flags.replace("g", ""));
    const m = probe.exec(input);
    return m !== null && splitsPair(input, m.index);
  }
  const g = new RegExp(re.source, re.flags.includes("g") ? re.flags : re.flags + "g");
  for (const m of input.matchAll(g)) {
    if (splitsPair(input, m.index)) return true;
  }
  return false;
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

/**
 * Writes each result as it is produced.
 *
 * Buffering everything until close held the whole run in memory and, worse, hid
 * how far the oracle had got when a pattern made V8 spin — the output was lost
 * with the process.
 */
const emit = (line) => process.stdout.write(line + "\n");

/**
 * With FUZZ_TRACE=1, logs each case before running it, so the last line on
 * stderr names the case that hung.
 *
 * V8's regular expression engine cannot be interrupted from JavaScript and has
 * no step limit, so a catastrophic pattern runs until the process is killed.
 * Tracing is the only way to find which case it was.
 */
const trace = process.env.FUZZ_TRACE === "1";

rl.on("line", (line) => {
  if (!line) return;
  if (trace) process.stderr.write(`CASE ${line}\n`);
  const [op, p, f, s, extra] = line.split(" ");
  try {
    const pattern = decode(p);
    const flags = decode(f);
    const input = decode(s);

    let re;
    try {
      re = new RegExp(pattern, flags);
    } catch {
      emit("E");
      return;
    }

    // A "!" prefix marks a result the comparison should skip: see
    // hasSurrogateSplitMatch.
    const global = op === "a" || op === "s" || re.flags.includes("g");
    // "!" = V8 places a match inside a surrogate pair; "~" = V8 mis-folds a
    // single-character \q{} element. Both are skipped by the comparison, and
    // counted separately so the report stays honest about which is which.
    const mark = hasSurrogateSplitMatch(re, input, global)
      ? "!"
      : hasSingleCharQuotedString(pattern, flags)
        ? "~"
        : hasModifierWithWordEscape(pattern)
          ? "%"
          : "";

    if (op === "x") {
      re.lastIndex = 0;
      const m = re.exec(input);
      emit(mark + (m === null ? "N" : `M ${renderMatch(m)}`));
    } else if (op === "a") {
      // matchAll is the spec's own definition of "every non-overlapping match",
      // including how it steps past an empty one.
      const all = [...input.matchAll(re)];
      emit(mark + `A ${all.length} ${all.map(renderMatch).join(" | ")}`);
    } else if (op === "r") {
      re.lastIndex = 0;
      emit(mark + `R ${encode(input.replace(re, decode(extra)))}`);
    } else {
      re.lastIndex = 0;
      const limit = Number(decode(extra));
      const parts = limit < 0 ? input.split(re) : input.split(re, limit);
      const rendered = parts.map((x) => (x === undefined ? "-" : encode(x)));
      emit(mark + `S ${parts.length} ${rendered.join(" ")}`);
    }
  } catch {
    emit("T");
  }
});

rl.on("close", () => {
  // Nothing to flush: every result was written as it was produced.
});
