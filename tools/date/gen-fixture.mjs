// Records node's answers for the Date operations.
//
// Two rules govern what may be asked of node here.
//
// 1. Only strings *inside* the Date Time String Format are used as oracles.
//    Date.parse falls back to "an implementation-specific format" for anything
//    else, and V8 uses that licence to read `March 1, 2024` and `2024/03/01`.
//    Recording those would pin V8's fallback, not the specification.
//
// 2. The local-time expectations are taken under a fixed-offset zone
//    (Etc/GMT-5), never a political one. A zone with transitions would make the
//    fixture depend on which tzdata the machine shipped, which is how a
//    Europe/Amsterdam expectation once passed on macOS and failed on Linux.
import { writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SELF = fileURLToPath(import.meta.url);

// A deterministic generator: a fixture that moves on its own is not a fixture.
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pad = (n, w) => String(Math.abs(n)).padStart(w, "0");

// Strings that are all inside the grammar of 21.4.1.32.
function corpus() {
  const out = [
    // Date-only forms. UTC by definition, in every zone.
    "2024", "1970", "0000", "0001", "9999",
    "2024-01", "2024-07", "2024-12",
    "2024-01-01", "2024-02-29", "2024-12-31", "1970-01-01",
    // Fields that are inside the grammar but roll when MakeDay sees them.
    "2024-02-30", "2024-02-31", "2024-04-31", "2023-02-29", "2024-01-31",
    // Expanded years.
    "+002024-03-01", "+275760-09-13", "-271821-04-20", "+000000-01-01", "-000001-01-01",
    "+010000-01-01", "-010000-01-01",
    // Time forms, no offset: local time, which is the only case the zone sees.
    "2024-07-01T00:00", "2024-07-01T12:00", "2024-07-01T23:59",
    "2024-07-01T12:00:00", "2024-07-01T12:00:30", "2024-07-01T12:00:00.000",
    "2024-07-01T12:00:00.001", "2024-07-01T12:00:00.999",
    "2024-01-01T00:00:00.000", "2024-12-31T23:59:59.999",
    // Hour 24 is the end of the day.
    "2024-07-01T24:00", "2024-07-01T24:00:00", "2024-07-01T24:00:00.000",
    "2024-02-28T24:00:00", "2024-12-31T24:00:00",
    // Explicit offsets. These never consult the zone.
    "2024-07-01T12:00:00Z", "2024-07-01T12:00Z", "2024-07-01T12:00:00.123Z",
    "2024-07-01T12:00:00+00:00", "2024-07-01T12:00:00-00:00",
    "2024-07-01T12:00:00+05:30", "2024-07-01T12:00:00-05:30",
    "2024-07-01T12:00:00+23:59", "2024-07-01T12:00:00-23:59",
    "2024-01-01T00:00:00+23:59", "2024-12-31T23:59:59.999-23:59",
    // The exact edges of the representable range.
    "+275760-09-13T00:00:00.000Z", "-271821-04-20T00:00:00.000Z",
    "+275760-09-12T23:59:59.999Z", "-271821-04-20T00:00:00.001Z",
  ];

  // Breadth, deterministically. Every field is drawn inside its grammar range.
  const rnd = mulberry32(0x2626262);
  const pick = (a) => a[Math.floor(rnd() * a.length)];
  for (let n = 0; n < 400; n++) {
    const expanded = rnd() < 0.15;
    let s;
    if (expanded) {
      const y = Math.floor(rnd() * 300000);
      s = (rnd() < 0.5 ? "+" : "-") + pad(y, 6);
    } else {
      s = pad(Math.floor(rnd() * 10000), 4);
    }
    const shape = pick(["y", "ym", "ymd", "ymd", "ymd", "ymd"]);
    if (shape !== "y") s += "-" + pad(1 + Math.floor(rnd() * 12), 2);
    if (shape === "ymd") s += "-" + pad(1 + Math.floor(rnd() * 31), 2);
    if (shape === "ymd" && rnd() < 0.75) {
      const use24 = rnd() < 0.05;
      const h = use24 ? 24 : Math.floor(rnd() * 24);
      const mi = use24 ? 0 : Math.floor(rnd() * 60);
      s += "T" + pad(h, 2) + ":" + pad(mi, 2);
      const t = use24 ? pick(["hm", "hms", "hmsf"]) : pick(["hm", "hms", "hmsf", "hmsf"]);
      if (t !== "hm") s += ":" + pad(use24 ? 0 : Math.floor(rnd() * 60), 2);
      if (t === "hmsf") s += "." + pad(use24 ? 0 : Math.floor(rnd() * 1000), 3);
      const z = pick(["", "", "Z", "off", "off"]);
      if (z === "Z") s += "Z";
      else if (z === "off") {
        s += (rnd() < 0.5 ? "+" : "-") +
          pad(Math.floor(rnd() * 24), 2) + ":" + pad(Math.floor(rnd() * 60), 2);
      }
    }
    out.push(s);
  }
  return out;
}

// A short list taken under a real political zone, for the one thing a fixed
// offset cannot exercise: what happens on the days the clocks move. Every entry
// is in 2024, deliberately - an expectation pinned in a local zone before that
// zone's first recorded transition is not stable, which is how a
// Europe/Amsterdam assertion once passed on macOS and failed on Linux after
// tzdata 2022b relinked it to Europe/Brussels.
const NEW_YORK = [
  "2024-01-15T12:00:00",      // EST, -05:00
  "2024-07-15T12:00:00",      // EDT, -04:00
  "2024-12-25T12:00:00",
  "2024-03-10T01:59:59.999",  // the last moment before the spring gap
  "2024-03-10T02:00:00",      // the gap itself: this local time never happens
  "2024-03-10T02:30:00",
  "2024-03-10T02:59:59.999",
  "2024-03-10T03:00:00",      // the other side of the gap
  "2024-11-03T00:59:59.999",
  "2024-11-03T01:00:00",      // the autumn overlap: this local time happens twice
  "2024-11-03T01:30:00",
  "2024-11-03T01:59:59.999",
  "2024-11-03T02:00:00",
  "2024-07-15",               // date-only, so UTC even here
  "2024-07-15T12:00:00Z",     // explicit, so the zone is not consulted
  "2024-07-15T12:00:00-04:00",
];

// The worker exists only so each set of expectations is taken in its own
// process under its own TZ; V8 reads the zone once and caches it.
if (process.argv[2] === "--worker") {
  const list = process.env.LIST === "newYork" ? NEW_YORK : corpus();
  const values = list.map((s) => {
    const v = Date.parse(s);
    return Number.isNaN(v) ? null : v;
  });
  process.stdout.write(JSON.stringify(values));
  process.exit(0);
}

const under = (tz, list) =>
  JSON.parse(execFileSync(process.execPath, [SELF, "--worker"], {
    env: { ...process.env, TZ: tz, LIST: list ?? "corpus" }, maxBuffer: 1 << 28,
  }).toString());

const strings = corpus();
const utc = under("UTC");
const newYork = under("America/New_York", "newYork");
const plus5 = under("Etc/GMT-5"); // POSIX sign inversion: this really is UTC+05:00.

// A NaN from node means one of two very different things: the string was
// rejected, or it parsed and then overflowed TimeClip. Node cannot tell us
// which, so the grammar is checked here instead, independently of the Kotlin.
// Anything that fails this must not be in the corpus at all: outside the
// grammar node is free to fall back to its own parser, and recording that
// would pin V8 rather than the specification.
const SHAPE = /^(?:\d{4}|[+-]\d{6})(?:-\d{2}(?:-\d{2})?)?(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d{3})?)?(?:Z|[+-]\d{2}:\d{2})?)?$/;
function inGrammar(s) {
  if (!SHAPE.test(s)) return false;
  if (s.startsWith("-000000")) return false; // named as invalid by the specification
  const m = /^(?:\d{4}|[+-]\d{6})(?:-(\d{2})(?:-(\d{2}))?)?(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{3}))?)?(?:Z|([+-])(\d{2}):(\d{2}))?)?$/.exec(s);
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
for (const s of strings) {
  if (!inGrammar(s)) throw new Error(`corpus string is outside the grammar, so node is not an oracle for it: ${s}`);
}

// A date-only string is UTC in every zone; if that ever fails, the premise is wrong.
for (let i = 0; i < strings.length; i++) {
  if (!strings[i].includes("T") && utc[i] !== plus5[i]) {
    throw new Error(`date-only string moved with the zone: ${strings[i]}`);
  }
}
// And the fixed offset really is +05:00, so EcmaTimeZone.fixed(300) is the match.
let bareCount = 0;
for (let i = 0; i < strings.length; i++) {
  const bare = strings[i].includes("T") && !/(Z|[+-]\d\d:\d\d)$/.test(strings[i]);
  if (!bare || utc[i] === null || plus5[i] === null) continue;
  bareCount++;
  if (utc[i] - plus5[i] !== 300 * 60000) throw new Error(`Etc/GMT-5 is not +05:00 for ${strings[i]}`);
}
if (bareCount < 50) throw new Error(`only ${bareCount} bare date-time strings; the zone path is barely covered`);
console.log(`${bareCount} bare date-time strings exercise the zone`);

const fnv1a = (h, s) => {
  for (let i = 0; i < s.length; i++) { h = (h ^ s.charCodeAt(i)) >>> 0; h = Math.imul(h, 16777619) >>> 0; }
  h = (h ^ 0x7c) >>> 0;
  return Math.imul(h, 16777619) >>> 0;
};

// Date.UTC is exactly TimeClip(MakeDate(MakeDay(MakeFullYear(y), mo, d), MakeTime(...))),
// so hashing it over a wide sweep tests all five operations composed.
const tuples = [];
{
  const rnd = mulberry32(0x51ced);
  const wild = [0, 1, -1, 11, 12, 13, -12, 24, 31, 32, 60, 99, 100, 365, 1000, -1000, 1e6, -1e6];
  for (const y of [1970, 2024, 1899, 0, 99, 100, -1, 275760, -271821, 300000, -300000]) {
    for (const mo of [0, 1, 11, 12, -1, 24]) {
      for (const d of [1, 0, -1, 28, 29, 30, 31, 32]) tuples.push([y, mo, d, 0, 0, 0, 0]);
    }
  }
  for (const v of wild) {
    tuples.push([2024, 0, 1, v, 0, 0, 0], [2024, 0, 1, 0, v, 0, 0],
                [2024, 0, 1, 0, 0, v, 0], [2024, 0, 1, 0, 0, 0, v]);
  }
  for (let n = 0; n < 20000; n++) {
    const r = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
    tuples.push([r(-300000, 300000), r(-30, 30), r(-40, 40), r(-30, 30), r(-90, 90), r(-90, 90), r(-2000, 2000)]);
  }
  // Fractional and non-finite arguments, which every step truncates or rejects.
  for (const v of [0.5, -0.5, 1.9, -1.9, NaN, Infinity, -Infinity, 1e308])
    tuples.push([2024, 0, 1, v, 0, 0, 0], [v, 0, 1, 0, 0, 0, 0], [2024, v, 1, 0, 0, 0, 0]);
}
let utcHash = 2166136261 >>> 0;
for (const t of tuples) utcHash = fnv1a(utcHash, String(Date.UTC(...t)));

// TimeClip on its own.
const clipInputs = [];
{
  const rnd = mulberry32(0xc11d);
  for (const v of [0, -0, 0.5, -0.5, -0.9, 1.5, -1.5, 8.64e15, 8.64e15 + 1, -8.64e15, -8.64e15 - 1,
                   NaN, Infinity, -Infinity, 1e300, -1e300, 4503599627370497]) clipInputs.push(v);
  for (let n = 0; n < 5000; n++) clipInputs.push((rnd() - 0.5) * 2.2e16);
  for (let n = 0; n < 2000; n++) clipInputs.push((rnd() - 0.5) * 1e10);
}
let clipHash = 2166136261 >>> 0;
for (const v of clipInputs) clipHash = fnv1a(clipHash, String(new Date(v).getTime()));

const kotlinString = (s) => '"' + s.replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
// Kotlin reads a bare integer literal as Long, so every time value needs a
// decimal point to be the Double the fixture arrays are declared as.
const kotlinDouble = (v) => {
  if (v === null) return "Double.NaN";
  const s = String(v);
  return /[.eE]/.test(s) ? s : s + ".0";
};

const OUT = process.argv[2];
writeFileSync(OUT, `// GENERATED FILE - DO NOT EDIT.
// Produced by tools/date/gen-fixture.mjs against node ${process.version}.

package io.github.mgilbir.ecma262.date

internal object DateFixture {
    /** Date.UTC over a sweep of field tuples: MakeFullYear, MakeDay, MakeTime, MakeDate and TimeClip composed. */
    internal const val UTC_SAMPLES: Int = ${tuples.length}
    internal const val UTC_HASH: UInt = ${utcHash}u

    /** TimeClip alone, through the Date constructor. */
    internal const val CLIP_SAMPLES: Int = ${clipInputs.length}
    internal const val CLIP_HASH: UInt = ${clipHash}u

    /** Strings inside the Date Time String Format. Nothing outside it is recorded from node. */
    internal val PARSE_INPUT: Array<String> = arrayOf(
${strings.map((s) => "        " + kotlinString(s) + ",").join("\n")}
    )

    /** Date.parse of each, under TZ=UTC. */
    internal val PARSE_UTC: DoubleArray = doubleArrayOf(
${utc.map((v) => "        " + kotlinDouble(v) + ",").join("\n")}
    )

    /** The same, under a fixed UTC+05:00 zone, which is EcmaTimeZone.fixed(300). */
    internal val PARSE_PLUS_5: DoubleArray = doubleArrayOf(
${plus5.map((v) => "        " + kotlinDouble(v) + ",").join("\n")}
    )

    /** A short list under America/New_York, which really does change offset. Used by the JVM test. */
    internal val NEW_YORK_INPUT: Array<String> = arrayOf(
${NEW_YORK.map((s) => "        " + kotlinString(s) + ",").join("\n")}
    )

    /** Date.parse of each, under TZ=America/New_York. */
    internal val NEW_YORK_EXPECTED: DoubleArray = doubleArrayOf(
${newYork.map((v) => "        " + kotlinDouble(v) + ",").join("\n")}
    )
}
`);
console.log(`wrote ${OUT}  (${strings.length} strings, ${tuples.length} tuples, ${clipInputs.length} clip inputs)`);
