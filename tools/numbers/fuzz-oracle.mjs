#!/usr/bin/env node
// Oracle side of the number fuzzer.
//
// Reads one case per line from stdin and writes one result per line to stdout,
// preserving order. Doubles travel as raw hex bit patterns so nothing depends
// on how either side parses decimal text; strings to be parsed travel as base64
// of their UTF-16 code units, so whitespace and lone surrogates survive.
//
// Request:  s <bits>            String(v)
//           f <bits> <digits>   v.toFixed(digits)
//           e <bits> <digits>   v.toExponential(digits), or no argument for -1
//           p <bits> <digits>   v.toPrecision(digits)
//           r <bits> <radix>    v.toString(radix)
//           n <b64>             Number(str), answered as bits
//
// Response: the string produced, or for "n" the resulting double's bits;
//           "!" prefixes a case the engine rejected, which the comparison
//           treats as "must also be rejected".

import readline from "node:readline";

const buf = new ArrayBuffer(8);
const dv = new DataView(buf);
const fromBits = (hex) => {
  dv.setBigUint64(0, BigInt("0x" + hex));
  return dv.getFloat64(0);
};
const toBits = (v) => {
  dv.setFloat64(0, v);
  return dv.getBigUint64(0).toString(16);
};

function decodeUtf16(b64) {
  const bytes = Buffer.from(b64, "base64");
  let out = "";
  for (let i = 0; i + 1 < bytes.length; i += 2) out += String.fromCharCode(bytes.readUInt16LE(i));
  return out;
}

const emit = (line) => process.stdout.write(line + "\n");

readline.createInterface({ input: process.stdin, crlfDelay: Infinity }).on("line", (line) => {
  if (!line) return;
  const parts = line.split(" ");
  const op = parts[0];
  try {
    if (op === "n") {
      emit(toBits(Number(decodeUtf16(parts[1]))));
      return;
    }
    const value = fromBits(parts[1]);
    const argument = parts.length > 2 ? Number(parts[2]) : -1;
    switch (op) {
      case "s":
        emit(String(value));
        break;
      case "f":
        emit(value.toFixed(argument));
        break;
      case "e":
        emit(argument < 0 ? value.toExponential() : value.toExponential(argument));
        break;
      case "p":
        emit(value.toPrecision(argument));
        break;
      case "r":
        emit(value.toString(argument));
        break;
      default:
        emit("!unknown-op");
    }
  } catch (error) {
    // A RangeError here is a legitimate answer, not an oracle failure.
    emit("!" + (error && error.constructor ? error.constructor.name : "Error"));
  }
});
