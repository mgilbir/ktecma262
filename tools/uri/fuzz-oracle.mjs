#!/usr/bin/env node
// Oracle side of the URI fuzzer.
//
// Reads "<fn> <b64>" per line, where b64 is the input's UTF-16 code units, and
// writes the result — or "!" for a URIError, which is a legitimate answer here
// rather than an oracle failure.
import readline from "node:readline";

const FNS = { c: encodeURIComponent, u: encodeURI, C: decodeURIComponent, U: decodeURI };

function decodeUtf16(b64) {
  const bytes = Buffer.from(b64, "base64");
  let out = "";
  for (let i = 0; i + 1 < bytes.length; i += 2) out += String.fromCharCode(bytes.readUInt16LE(i));
  return out;
}
function encodeUtf16(s) {
  const buf = Buffer.alloc(s.length * 2);
  for (let i = 0; i < s.length; i++) buf.writeUInt16LE(s.charCodeAt(i), i * 2);
  return buf.toString("base64");
}

readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on("line", (line) => {
  if (!line) return;
  const [op, payload] = line.split(" ");
  const fn = FNS[op];
  if (!fn) return process.stdout.write("!unknown-op\n");
  try {
    // Results can contain anything, so they come back encoded too.
    process.stdout.write(encodeUtf16(fn(decodeUtf16(payload ?? ""))) + "\n");
  } catch (e) {
    process.stdout.write((e && e.constructor && e.constructor.name === "URIError" ? "!" : "!other") + "\n");
  }
});
