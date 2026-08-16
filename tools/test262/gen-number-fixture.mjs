#!/usr/bin/env node
// Turns Test262's Number formatting tests into a Kotlin fixture.
//
//   node tools/test262/gen-number-fixture.mjs <output-kotlin-file> [cache-dir]
//
// These are hand-curated conformance tests for toFixed, toExponential,
// toPrecision and toString(radix). Their value here is the *inputs*: corner
// cases chosen by people who knew where implementations go wrong, which random
// sampling and a hand-written list both miss.
//
// Each file is evaluated with the harness stubbed and the four methods wrapped,
// so every call is captured with its receiver and arguments. Where an
// assertion names an expected string and exactly one wrapped call produced the
// value it checked, that literal is recorded as the expectation — an authority
// independent of any engine. Otherwise node's own answer is recorded, and the
// case still contributes its input. Which of the two a case came from is kept,
// so the split is visible rather than implied.

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const OUT = process.argv[2];
const CACHE = process.argv[3] ?? ".test262-cache";
if (!OUT) {
  console.error("usage: gen-number-fixture.mjs <output-kotlin-file> [cache-dir]");
  process.exit(2);
}

const DIRS = [
  "test/built-ins/Number/prototype/toFixed",
  "test/built-ins/Number/prototype/toExponential",
  "test/built-ins/Number/prototype/toPrecision",
  "test/built-ins/Number/prototype/toString",
];

const fetchText = async (url) => {
  const res = await fetch(url, { headers: { "user-agent": "ktecma262-build" } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.text();
};

async function listFiles(dir) {
  const key = path.join(CACHE, dir.replace(/[/]/g, "_") + "_index.json");
  if (fs.existsSync(key)) return JSON.parse(fs.readFileSync(key, "utf8"));
  const api = `https://api.github.com/repos/tc39/test262/contents/${dir}`;
  const names = JSON.parse(await fetchText(api))
    .filter((e) => e.type === "file" && e.name.endsWith(".js"))
    .map((e) => e.name);
  fs.mkdirSync(CACHE, { recursive: true });
  fs.writeFileSync(key, JSON.stringify(names));
  return names;
}

async function readFile(dir, name) {
  const cached = path.join(CACHE, dir.replace(/[/]/g, "_") + "_" + name);
  if (fs.existsSync(cached)) return fs.readFileSync(cached, "utf8");
  const text = await fetchText(
    `https://raw.githubusercontent.com/tc39/test262/main/${dir}/${name}`,
  );
  fs.mkdirSync(CACHE, { recursive: true });
  fs.writeFileSync(cached, text);
  return text;
}

const buf = new ArrayBuffer(8);
const dv = new DataView(buf);
const bitsOf = (v) => {
  dv.setFloat64(0, v);
  return dv.getBigUint64(0);
};

/** (bits, method, arg) -> expected string, keyed to dedupe across files. */
const cases = new Map();
let fromTest262 = 0;
let fromNode = 0;
let skippedFiles = 0;
let disagreements = 0;

function record(receiver, method, arg, actual, expected, source) {
  if (!Number.isFinite(receiver) && !Number.isNaN(receiver)) {
    // Infinities are covered by the hand-written tests; keep the fixture to
    // values whose digits are interesting.
  }
  if (typeof actual !== "string") return;
  const key = `${bitsOf(receiver)}|${method}|${arg}`;
  if (cases.has(key)) return;
  cases.set(key, { receiver, method, arg, expected, source });
  if (source === "test262") fromTest262++;
  else fromNode++;
}

for (const dir of DIRS) {
  const method = dir.split("/").pop();
  const names = await listFiles(dir);
  for (const name of names) {
    const source = await readFile(dir, name);
    // Files that must not be evaluated, or that test property attributes and
    // `this` coercion rather than the digits.
    if (/\$DONOTEVALUATE|negative:/.test(source)) continue;

    const calls = [];
    // Calls made since the previous assertion. Pairing has to happen when the
    // assertion fires, not afterwards: a file makes many calls, and matching on
    // the value alone pairs almost nothing.
    let pending = [];

    const wrap = (name) => {
      const original = Number.prototype[name];
      return function (...args) {
        const result = original.apply(this, args);
        const call = { receiver: Number(this), method: name, arg: args[0], result };
        calls.push(call);
        pending.push(call);
        return result;
      };
    };

    const sandbox = {
      Number,
      Math,
      String,
      Object,
      Array,
      Boolean,
      Symbol,
      TypeError,
      RangeError,
      Error,
      isNaN,
      isFinite,
      parseInt,
      parseFloat,
      undefined: undefined,
      Infinity,
      NaN,
      print: () => {},
      $262: { createRealm: () => { throw new Error("unsupported"); } },
      Test262Error: class Test262Error extends Error {},
      compareArray: () => true,
      verifyProperty: () => true,
      verifyPrimordialProperty: () => true,
      verifyNotEnumerable: () => true,
      verifyNotWritable: () => true,
      verifyNotConfigurable: () => true,
      verifyConfigurable: () => true,
      verifyWritable: () => true,
      verifyEnumerable: () => true,
      assert: Object.assign(
        function assert(condition) {
          return condition;
        },
        {
          sameValue(actual, expected) {
            // Exactly one call since the last assertion means this literal is
            // unambiguously that call's expected result.
            if (pending.length === 1 && typeof expected === "string" && actual === pending[0].result) {
              pending[0].expectedByTest262 = expected;
            }
            pending = [];
          },
          notSameValue() {
            pending = [];
          },
          throws() {
            pending = [];
          },
          compareArray: () => true,
          _isSameValue: (a, b) => Object.is(a, b),
        },
      ),
    };
    sandbox.globalThis = sandbox;

    const context = vm.createContext(sandbox);
    // Wrap inside the sandbox so the tests' own calls are the ones captured.
    const originals = {};
    for (const m of ["toFixed", "toExponential", "toPrecision", "toString"]) {
      originals[m] = Number.prototype[m];
      Number.prototype[m] = wrap(m);
    }
    try {
      vm.runInContext(source, context, { timeout: 5000 });
    } catch {
      skippedFiles++;
    } finally {
      for (const m of Object.keys(originals)) Number.prototype[m] = originals[m];
    }

    for (const call of calls) {
      if (!Number.isFinite(call.receiver) && !Number.isNaN(call.receiver)) continue;
      const paired = call.expectedByTest262 !== undefined;
      if (paired && call.expectedByTest262 !== call.result) disagreements++;
      record(
        call.receiver,
        call.method,
        call.arg,
        call.result,
        paired ? call.expectedByTest262 : call.result,
        paired ? "test262" : "node",
      );
    }
  }
}

if (disagreements > 0) {
  console.error(`${disagreements} cases where node disagrees with test262 - inspect before trusting`);
  process.exit(1);
}

const entries = [...cases.values()]
  .sort((a, b) => `${a.method}${a.receiver}${a.arg}`.localeCompare(`${b.method}${b.receiver}${b.arg}`));

// The argument goes through ToIntegerOrInfinity, so Test262 passes things like
// NaN and 2.5 deliberately: (1).toFixed(NaN) is (1).toFixed(0). Record the
// coerced value, since that is what the Kotlin API takes.
const toIntegerOrInfinity = (x) => {
  const n = Number(x);
  if (Number.isNaN(n)) return 0;
  if (!Number.isFinite(n)) return n;
  return Math.trunc(n);
};

const lines = [];
let coerced = 0;
let outOfRange = 0;
// Counted over what is actually emitted, so the fixture's own totals cannot
// drift from its contents.
let emittedFromTest262 = 0;
let emittedFromNode = 0;
const emit = (c, argument) => {
  lines.push(
    `        Case(${bitsOf(c.receiver)}uL, "${c.method}", ${argument}, ${JSON.stringify(c.expected)}),`,
  );
  if (c.source === "test262") emittedFromTest262++;
  else emittedFromNode++;
};
for (const c of entries) {
  if (c.arg === undefined) {
    // Omitting the argument is not the same thing for each method: toFixed
    // coerces undefined to 0, while the others treat it as "unspecified".
    emit(c, c.method === "toFixed" ? "0" : "null");
    continue;
  }
  const argument = toIntegerOrInfinity(c.arg);
  if (!Number.isFinite(argument)) { outOfRange++; continue; }
  const limits = { toFixed: [0, 100], toExponential: [0, 100], toPrecision: [1, 100], toString: [2, 36] };
  const [lo, hi] = limits[c.method];
  if (argument < lo || argument > hi) { outOfRange++; continue; }
  if (argument !== c.arg) coerced++;
  emit(c, String(argument));
}

fs.writeFileSync(
  OUT,
  `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/test262/gen-number-fixture.mjs from tc39/test262.
//
// Inputs chosen by Test262's authors, which is the point: these are the corner
// cases a random sweep and a hand-written list both miss. ${emittedFromTest262} of the
// expectations are the literal Test262 asserts, independent of any engine; the
// remaining ${emittedFromNode} come from node, where the assertion could not be paired
// with exactly one call. Generation fails if the two ever disagree.

package io.github.mgilbir.ecma262.number

internal object Test262NumberFixture {
    internal class Case(
        val bits: ULong,
        val method: String,
        val argument: Int?,
        val expected: String,
    )

    /** Expectations taken verbatim from a Test262 assertion. */
    internal const val FROM_TEST262: Int = ${emittedFromTest262}

    /** Expectations taken from node, for inputs Test262 supplied. */
    internal const val FROM_NODE: Int = ${emittedFromNode}

    internal val CASES: List<Case> = listOf(
${lines.join("\n")}
    )
}
`,
);

console.log(`wrote ${OUT}`);
console.log(`  cases:        ${lines.length}`);
console.log(`  from test262: ${emittedFromTest262}`);
console.log(`  from node:    ${emittedFromNode}`);
console.log(`  files skipped: ${skippedFiles}`);
console.log(`  args coerced:  ${coerced} (NaN and fractional arguments)`);
console.log(`  out of range:  ${outOfRange} (dropped)`);
