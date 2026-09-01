#!/usr/bin/env node
// Oracle side of the Date fuzzer.
//
// Two request kinds:
//
//   p <b64>            parse a Date Time String
//   u <7 numbers>      Date.UTC of a field tuple
//
// The parse reply is `g <value>` when the string is inside the grammar of
// 21.4.1.32 and `x` when it is not. That distinction has to be made here rather
// than by asking node, because Date.parse falls back to an implementation
// specific parser outside the grammar - it reads `March 1, 2024` quite happily -
// so its answer is only an oracle for strings the grammar admits. For the rest
// the requirement is simply that the library returns NaN, and the fuzzer checks
// that without consulting node at all.
import readline from "node:readline";

const SHAPE = /^(?:\d{4}|[+-]\d{6})(?:-\d{2}(?:-\d{2})?)?(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d{3})?)?(?:Z|[+-]\d{2}:\d{2})?)?$/;
const PARTS = /^(?:\d{4}|[+-]\d{6})(?:-(\d{2})(?:-(\d{2}))?)?(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{3}))?)?(?:Z|([+-])(\d{2}):(\d{2}))?)?$/;

function inGrammar(s) {
  if (!SHAPE.test(s)) return false;
  if (s.startsWith("-000000")) return false;
  const m = PARTS.exec(s);
  if (!m) return false;
  const [, mo, d, h, mi, sec, ms, , oh, om] = m;
  if (mo !== undefined && (+mo < 1 || +mo > 12)) return false;
  if (d !== undefined && (+d < 1 || +d > 31)) return false;
  if (h !== undefined && +h > 24) return false;
  if (mi !== undefined && +mi > 59) return false;
  if (sec !== undefined && +sec > 59) return false;
  if (h !== undefined && +h === 24 && (+mi !== 0 || +(sec ?? 0) !== 0 || +(ms ?? 0) !== 0)) return false;
  if (oh !== undefined && (+oh > 23 || +om > 59)) return false;
  return true;
}

function decodeUtf16(b64) {
  const bytes = Buffer.from(b64, "base64");
  let out = "";
  for (let i = 0; i + 1 < bytes.length; i += 2) out += String.fromCharCode(bytes.readUInt16LE(i));
  return out;
}

readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on("line", (line) => {
  if (!line) return;
  const space = line.indexOf(" ");
  const op = line.slice(0, space);
  const payload = line.slice(space + 1);
  if (op === "p") {
    const s = decodeUtf16(payload);
    process.stdout.write(inGrammar(s) ? "g " + String(Date.parse(s)) + "\n" : "x\n");
  } else if (op === "u") {
    const n = payload.split(",").map(Number);
    process.stdout.write(String(Date.UTC(n[0], n[1], n[2], n[3], n[4], n[5], n[6])) + "\n");
  } else {
    process.stdout.write("?\n");
  }
});
