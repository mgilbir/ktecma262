# ktecma262

[![CI](https://github.com/mgilbir/ktecma262/actions/workflows/ci.yml/badge.svg)](https://github.com/mgilbir/ktecma262/actions/workflows/ci.yml)

An ECMA-262 (JavaScript) regular expression engine in pure Kotlin, for Kotlin
Multiplatform.

Patterns and match results behave exactly as they do in JavaScript — the same
flags, the same Annex B web-compatibility syntax, the same capture and
replacement semantics, and the same UTF-16 offsets. Correctness is established
by differential testing against a real JavaScript engine rather than by reading
the specification: every build replays a recorded corpus of ~36,000 cases, and
the fuzzer has run millions more.

Reach for this when you need regex features Kotlin's own `Regex` cannot express
the same way — JavaScript's exact `lastIndex` semantics, Annex B behaviour,
`\p{…}` pinned to a known Unicode version — or when you are porting JavaScript
code and need identical results.

## Installation

```kotlin
dependencies {
    implementation("io.github.mgilbir:ktecma262:0.1.0")
}
```

> Publishing to Maven Central needs a verified `io.github.mgilbir` namespace and
> a signing key. Until those are configured (see [Releasing](#releasing)), build
> from source with `./gradlew publishToMavenLocal` and add `mavenLocal()` to your
> repositories, or take the jars from the
> [release page](https://github.com/mgilbir/ktecma262/releases).

**Targets:** JVM and JS today; the engine is pure `commonMain` Kotlin with no
`java.*` dependencies, so other Kotlin targets need only a build-file change.

**JVM bytecode is Java 17** (class file major version 61), so the library can be
consumed from modules targeting 17. This is enforced by a build check that reads
the emitted class files rather than trusting the compiler setting.

## Quick start

```kotlin
import io.github.mgilbir.ecma262.RegExp

val re = RegExp.compile("""(\d{4})-(\d{2})-(\d{2})""")
val m = re.exec("Date: 2024-03-15")!!
m.value   // "2024-03-15"
m[1]      // "2024"
m.index   // 6

// Named groups
val named = RegExp.compile("""(?<year>\d{4})-(?<month>\d{2})""")
named.exec("2024-03")!!["month"]   // "03"

// Replacement, with ECMA-262 $-substitution
RegExp.compile("""(\w+) (\w+)""").replace("Ada Lovelace", "$2, $1")
// "Lovelace, Ada"

// Global iteration, mirroring JavaScript's lastIndex
val g = RegExp.compile("a", "g")
g.exec("aXa")   // index 0, lastIndex becomes 1
g.exec("aXa")   // index 2, lastIndex becomes 3
g.exec("aXa")   // null,   lastIndex resets to 0

// Or ignore the cursor entirely
RegExp.compile("""\d+""").findAll("a1b22c333").map { it.value }  // [1, 22, 333]

// Splicing untrusted text into a pattern safely
val re = RegExp.compile("^" + RegExp.escape(userInput) + "$")
```

## Features

- Literals, `.`, character classes, shorthand classes, anchors, quantifiers
  (greedy and lazy), alternation, capturing and non-capturing groups
- Flags `i`, `g`, `m`, `s`, `u`, `v`, `y`, `d`
- Named capture groups `(?<name>…)` and `\k<name>`, including ES2022 duplicate
  names across alternatives
- Backreferences, including forward references
- Lookahead and lookbehind, positive and negative, including variable-length
  lookbehind with ECMA-262 right-to-left capture semantics
- Unicode property escapes `\p{…}` / `\P{…}`: all general categories, scripts,
  script extensions, and every binary property the specification lists
- `v` (UnicodeSets): nested classes, `&&` intersection, `--` difference,
  `\q{…}` string literals, and properties of strings (`\p{RGI_Emoji}` and its
  five constituents)
- Regexp modifiers (ES2025): `(?i:…)`, `(?-i:…)`, `(?i-ms:…)` scope `i`, `m`
  and `s` to a subexpression
- Annex B web-compatibility syntax by default, with strict ECMA-262 available
  via `Syntax.STRICT`

## API

`RegExp` mirrors JavaScript's:

| Member | Behaviour |
| --- | --- |
| `exec(input)` | Next match, honouring and advancing `lastIndex` under `g`/`y` |
| `test(input)` | As `exec`, returning a boolean |
| `findAll(input)` | Every non-overlapping match, ignoring `lastIndex` |
| `replace(input, replacement)` | `$&`, `$1`…`$99`, `$<name>`, `` $` ``, `$'`, `$$` |
| `replace(input) { m -> … }` | Replacement computed per match |
| `split(input, limit)` | Separator captures interleaved, as in JavaScript |
| `search(input)` | Index of the first match, or -1 |
| `lastIndex` | The mutable cursor used by `g`/`y` |
| `maxSteps` | Instruction budget for one operation (see below) |
| `RegExp.escape(text)` | Escapes text for literal use in a pattern (ES2025) |

`MatchResult` exposes `value`, `index`, `input`, `get(i)`, `get(name)`,
`range(i)`, `groupValues()` and `groups`.

### Offsets are UTF-16 code units

All indices — `lastIndex`, `MatchResult.index`, `range()` — are UTF-16 code unit
offsets into a Kotlin `String`, exactly as in JavaScript. `"😀".length` is 2
here as it is there, and without `u` the engine matches individual code units,
so `.` matches half a surrogate pair just as JavaScript does.

### Thread safety

A `RegExp` compiled without `g` or `y` is immutable and safe to share between
threads; matching allocates its own small matcher per call. With `g` or `y` the
instance carries the mutable `lastIndex` cursor and must not be shared without
synchronisation.

## Match safety (ReDoS)

The engine is a backtracker, which is what makes backreferences and lookaround
possible — engines built on finite automata structurally cannot support them.
The cost is that a hostile pattern and input can take exponential time.

Every match is therefore bounded by an instruction budget, `RegExp.maxSteps`
(default 1,000,000, the same default PCRE uses for its backtrack limit).
Exceeding it raises `RegExpStepLimitError` rather than reporting a non-match, so
a caller can tell "too expensive" apart from "did not match":

```kotlin
val re = RegExp.compile("(a+)+b")
try {
    re.exec(untrustedInput)
} catch (e: RegExpStepLimitError) {
    // pattern and input too expensive, not a non-match
}
```

The budget covers a whole operation — one `exec` scan, or an entire `findAll`,
`replace` or `split` — not each start position or each match. A failing search
therefore stays O(budget) instead of O(length × budget), and a repeated
operation cannot multiply the budget by a match count the input controls. Memory
is bounded by the same budget, since each step pushes at most one backtrack
entry.

Backtracking runs on an explicit stack rather than the call stack, so deep
backtracking cannot overflow it. (`java.util.regex` recurses per repetition and
throws `StackOverflowError` on inputs this engine handles — see the benchmark
notes below.)

## Unicode

Unicode data is generated from the UCD and compiled in, so `\p{…}` results do
not drift with the host JDK's Unicode version. The current tables are **Unicode
17.0.0**, matching node 26's ICU, and all 439 supported character properties
were verified against it code point by code point.

The `v` flag's *properties of strings* — `\p{RGI_Emoji}`, `\p{Basic_Emoji}`,
`\p{Emoji_Keycap_Sequence}`, `\p{RGI_Emoji_Flag_Sequence}`,
`\p{RGI_Emoji_Modifier_Sequence}`, `\p{RGI_Emoji_Tag_Sequence}` and
`\p{RGI_Emoji_ZWJ_Sequence}` — come from the UTS #51 emoji sequence files
(3,953 sequences in total) and are checked against Test262's `rgi-emoji-*`
cases.

To regenerate against a different version:

```bash
tools/genunicode/fetch-ucd.sh /tmp/ucd 17.0.0
# plus emoji-sequences.txt and emoji-zwj-sequences.txt from
# https://www.unicode.org/Public/emoji/latest/ for the properties of strings
node tools/genunicode/gen.mjs /tmp/ucd \
    src/commonMain/kotlin/io/github/mgilbir/ecma262/unicode/UnicodeTables.kt
node tools/genunicode/verify-against-node.mjs \
    src/commonMain/kotlin/io/github/mgilbir/ecma262/unicode/UnicodeTables.kt \
    src/commonTest/kotlin/io/github/mgilbir/ecma262/unicode/UnicodePropertyFixture.kt
```

The verifier compares every property against the host JavaScript engine and
refuses to write the fixture unless all of them agree.

## Testing

```bash
./gradlew build                              # unit + differential suite, JVM and JS
./gradlew fuzz -Pcount=200000 -Pseed=7       # live differential fuzzing against node
./gradlew bench                              # micro-benchmarks
```

Correctness rests on four layers:

1. **Recorded differential suite** — ~36,000 pattern/flags/input cases whose
   expected results were captured from node, replayed on every build on every
   platform. Regenerate with `node tools/difftest/gen-fixture.mjs …`.
2. **Live fuzzing** — random patterns and inputs compared against a running node
   for `exec`, all-matches, `replace` and `split`. Millions of cases have been
   run clean; it is what found the surrogate backreference bug that
   `SurrogateBoundaryTest.backreferenceComparesCodePointsUnderUnicode` now
   guards.
3. **Test262** — the conformance suite's generated `v`-flag class tests (114
   cases covering nesting, set operations, `\q{…}` and properties of strings),
   replayed offline. These are written by the specification's authors, so they
   are independent of the node-derived corpus above. Regenerate with
   `node tools/test262/gen-unicodesets-fixture.mjs …`.
4. **Targeted suites** — parser acceptance/rejection, Unicode tables, the `v`
   grammar, modifier scoping, surrogate boundaries, the API surface, the step
   limit, and `RegExp.escape` over every code point.

The fuzzer also generates unstructured "syntax soup" patterns, not just ones its
own grammar knows how to build — that is what checks the parser against
constructs this codebase has never heard of.

### Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

- the full build on Linux — JVM tests, JS tests, and the Java 17 bytecode check;
- the JVM tests on macOS and Windows, which is where a platform-dependent
  difference in the engine would show up (the JS toolchain is identical
  everywhere, since Gradle downloads its own node);
- 100,000 live fuzz cases against node, with a seed that varies per run and is
  printed so a failure reproduces exactly.

The offline suites need no JavaScript engine — they replay recorded results — so
only the fuzz job pins a node version. It is guarded: CI fails with a clear
message if node's Unicode version does not match the compiled tables, because
otherwise every `\p{…}` case would disagree for reasons unrelated to the engine.

`.github/workflows/nightly.yml` runs the longer checks: 1.5M fuzz cases across
three seeds, and a drift check that regenerates the Unicode tables from upstream
and re-verifies every property against node. The drift job is expected to fail
when Unicode publishes new data — that is the signal to regenerate.

## Notes for contributors

**Kotlin/JS cannot carry an unpaired surrogate in a compile-time string
constant.** Both a literal `"\uD83D"` and a constant-folded
`0xD83D.toChar().toString()` come back as `"?"`, because the constant is
materialised into the generated JavaScript source where a lone surrogate is not
representable. Kotlin/JVM keeps it. Values computed at run time are fine on both
targets, so the engine itself is unaffected — but test data is not. Two rules
follow, both enforced by `LoneSurrogateLiteralTest`:

- derive lone surrogates from a well-formed pair at run time
  (`"😀".substring(0, 1)`), never write them as constants;
- keep generated test data in a pure-ASCII transport form and decode it at run
  time, which is what `DiffFixture` does.

## Deliberate deviations from node

Four, all intentional and all covered by tests.

**The step limit.** node has no bound and will hang on a catastrophic pattern;
this engine stops and reports. See *Match safety* above.

**A V8 defect around surrogate pairs.** Under `u`/`v` the specification matches
over a list of *code points*, so no match — not even a zero-width one — can
begin part-way through a surrogate pair. V8 honours that for most patterns
(`/(?:)/yu` with `lastIndex = 2` on `"😀"` snaps back to index 0) but not for
zero-width assertions:

```js
/\B/u.exec("b😀").index   // node: 2  — index 2 splits the pair
                          // spec:  3
```

This engine follows the specification. `SurrogateBoundaryTest` pins the
behaviour down, and the fuzzer's oracle flags and skips these cases rather than
reporting them as failures.

**A V8 defect folding one-character `\q{…}` elements.** ECMA-262 treats a
length-1 string as a character — that is why `[^\q{a}]` is legal at all — so it
should fold under `/i` exactly like the same character written plainly. V8 folds
the pattern side but not the input, and the inconsistency is visible inside a
single class:

```js
/[a\q{b}]/vi.test("A")   // true  — the plain character folds
/[a\q{b}]/vi.test("B")   // false — the \q{} character does not
/[\q{ab}]/vi.test("AB")  // true  — longer strings are unaffected
```

This engine folds both, so `[\q{a}]/vi` matches `"A"`. `UnicodeSetsTest` pins
it down.

**A V8 defect scoping modifiers into `\w`.** A modifier group scopes an added
`i` correctly for literals and classes, but not for the word-class escapes:

```js
/(?i:c)\w/u.test("cſ")   // true in V8 — the group's `i` leaked into \w
/(?:c)\w/u.test("cſ")    // false, as it should be
/(?i:c)d/u.test("cD")    // false — literals are scoped correctly
```

Only an *added* `i` is affected; `(?m:…)`, `(?s:…)` and `(?-i:…)` scope
correctly. This engine scopes all of them, and `ModifierGroupTest` pins it
down.

## Performance

The engine parses to an AST, compiles to bytecode, and executes on a
backtracking VM with an explicit stack and an undo log — captures are written in
place and rewound, never copied per step. Case-insensitivity and `\p{…}` are
resolved at compile time into concrete code point sets, so the matcher never
folds a character at match time. A first-character prefilter (with an `indexOf`
fast path) skips start positions that cannot begin a match.

Indicative figures on a Ryzen 9 6900HX, JDK 21, with `java.util.regex` alongside
purely for scale — it implements a different dialect and has no ReDoS bound:

| Benchmark | ktecma262 | java.util.regex |
| --- | --- | --- |
| literal scan, 100k chars | 41 µs | 81 µs |
| date with 3 captures | 174 ns | 170 ns |
| `\d+` first match, 100k chars | 428 ns | 300 ns |
| email match ×1000 | 619 µs | 185 µs |
| `\b\w+\b` findAll, 100k chars | 2.8 ms | 1.0 ms |
| `(a\|b)*c`, 1600 chars | 88 µs | `StackOverflowError` |

Compilation is ~0.5 µs for a simple pattern; a `\p{…}` pattern costs ~40 µs
because the property table is decoded per compile, so compile once and reuse.

Reproduce with `./gradlew bench`.

## Releasing

Tagging is the trigger: `.github/workflows/release.yml` runs on a `v*` tag,
checks the tag against the version in `build.gradle.kts`, builds, runs the full
test suite and 200,000 fuzz cases, then publishes.

```bash
# 1. bump `version` in build.gradle.kts, commit
# 2. tag and push
git tag -a v0.1.0 -m "ktecma262 0.1.0"
git push origin v0.1.0
```

Publication to Maven Central is skipped — with a notice, not a failure — until
these repository secrets exist:

| Secret | What it is |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central token password |
| `SIGNING_KEY` | ASCII-armoured GPG private key |
| `SIGNING_PASSWORD` | Passphrase for that key |

It also needs the `io.github.mgilbir` namespace verified in the Central portal.
Nothing else in the build reads credentials, and they are only ever taken from
the environment — never from a file in the repository.

To check the artifacts without publishing anything:

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/io/github/mgilbir/
```

## Known limitations

1. **`d` flag** — parsed and accepted, but match indices are always available
   through `MatchResult.range()`, so it has no additional effect.
2. **Compile-time limits** — patterns nested more than 200 deep, a single
   quantifier bound above 10,000, or more than 200,000 emitted instructions are
   rejected at compile time.
3. **Full case folding** — case-insensitive matching uses Unicode *simple* case
   folding under `u`/`v` and the legacy uppercase canonicalization otherwise,
   matching JavaScript in both modes. Full-mapping cases such as `ß`↔`SS` are
   not folded, as in other engines.

## License

MIT

## References

- [ECMA-262 specification](https://tc39.es/ecma262/#sec-regexp-regular-expression-objects)
- [Unicode Character Database](https://www.unicode.org/Public/)
- [goecma262](https://github.com/mgilbir/goecma262) — the Go implementation this
  began as a port of
