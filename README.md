# ktecma262

[![CI](https://github.com/mgilbir/ktecma262/actions/workflows/ci.yml/badge.svg)](https://github.com/mgilbir/ktecma262/actions/workflows/ci.yml)

ECMA-262 (JavaScript) algorithms in pure Kotlin, for Kotlin Multiplatform: a
regular expression engine, and JavaScript's number formatting.

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
    implementation("io.github.mgilbir:ktecma262:0.1.4")
}
```

> Publishing to Maven Central needs a verified `io.github.mgilbir` namespace and
> a signing key. Until those are configured (see [Releasing](#releasing)), build
> from source with `./gradlew publishToMavenLocal` and add `mavenLocal()` to your
> repositories, or take the jars from the
> [release page](https://github.com/mgilbir/ktecma262/releases).

**Targets:** JVM, JS, `linuxX64`, `macosArm64`, `iosArm64` and
`iosSimulatorArm64`. The engine is pure `commonMain` Kotlin with no `java.*`
dependencies and no `expect`/`actual` anywhere, so adding a further target is a
one-line build-file change.

Apple targets can only be compiled on a macOS host, and Kotlin drops a target
the host cannot build without failing the build — so releases are published
from macOS, and `./gradlew verifyPublishedVariants` fails if any declared
target would be left out of the publication.

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

**Numbers** — JavaScript's own number formatting and parsing, none of which any
Kotlin target reproduces:

- `Double.toEcmaString()` and `Double.toEcmaString(radix)`
- `String.toEcmaDouble()` — `Number("…")`, correctly rounded
- `Double.toEcmaFixed()`, `toEcmaExponential()`, `toEcmaPrecision()`

**URIs** — the four escaping functions, which common Kotlin does not have:

- `String.encodeUriComponent()`, `String.encodeUri()`
- `String.decodeUriComponent()`, `String.decodeUri()`

**Text** — Unicode normalisation and the string and number semantics Kotlin
gets differently:

- `String.normalize(NormalizationForm.NFC | NFD | NFKC | NFKD)`
- `String.ecmaTrim()`, `ecmaTrimStart()`, `ecmaTrimEnd()`
- `EcmaMath.round/trunc/sign/clz32/imul/fround`

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

## Numbers

`Number::toString` — ECMA-262 6.1.6.1.20 — is what JavaScript prints for a
number, and no Kotlin target reproduces it. Kotlin/JS does, because it *is*
JavaScript; Kotlin/JVM and Kotlin/Native both disagree:

```kotlin
import io.github.mgilbir.ecma262.number.toEcmaString

1.0.toEcmaString()       // "1"          — JVM and Native print "1.0"
1e21.toEcmaString()      // "1e+21"      — "1.0E21"
1e20.toEcmaString()      // "100000000000000000000"
1e-7.toEcmaString()      // "1e-7"       — "1.0E-7"
(-0.0).toEcmaString()    // "0"          — "-0.0"
Double.MIN_VALUE.toEcmaString()  // "5e-324" — "4.9E-324"
```

Over 200,000 random doubles, the JVM's string differs from JavaScript's **98.4%**
of the time. Almost all of that is layout: JavaScript stays positional out to
10^21 and in to 10^-6, where the JVM switches to scientific notation at 10^7 and
10^-3, never omits the `.0`, and prints `-0.0`.

The digits agree far more often, but not always. A JDK 21 `Double.toString` is
not shortest for the smallest subnormals — it prints `4.9E-324` where `5e-324`
round-trips — which rules out the tempting shortcut of reusing the platform's
digits and re-laying them out.

### Parsing

`String.toEcmaDouble()` is `StringToNumber` (7.1.4.1.1) — what `Number("…")`
does, not what `parseFloat` does, so the whole string must be a numeric literal:

```kotlin
"1e3".toEcmaDouble()        // 1000.0
"0x1f".toEcmaDouble()       // 31.0    — but "-0x1f" is NaN, the grammar takes no sign
"  -1.5e-3  ".toEcmaDouble() // -0.0015 — surrounding whitespace is allowed
"".toEcmaDouble()           // 0.0     — an empty literal is +0
"12abc".toEcmaDouble()      // NaN     — not parseFloat
```

The result is correctly rounded, which can turn on the 767th significant digit:
`"2.2250738585072012e-308"` — the value that used to hang `Double.parseDouble`
— must give `2.2250738585072014e-308`, and `"9007199254740993"` must give
`9007199254740992`.

Because correctly rounded parsing is where decimal handling has historically
been attacked, every loop is bounded before any large arithmetic starts:
significant digits are capped with the remainder folded into a sticky flag that
can only break an exact tie, the exponent is clamped as it is read, and
magnitudes outside the double range resolve before a big integer exists.
`StringToNumberBoundsTest` covers exponents of a billion, mantissas of 100,000
digits, and long strings that are not literals at all.

### Other radices, and why they are different

```kotlin
255.0.toEcmaString(16)  // "ff"
0.5.toEcmaString(2)     // "0.1"
0.1.toEcmaString(3)     // "0.0022002200220022002200220022002201"
```

This is the one function here that is **compatibility rather than
conformance**. ECMA-262 says the result for a radix other than 10 is
*implementation-approximated* — a string representation of the value in that
radix, and nothing more. It does not say how many digits to emit, where to
stop, or how to round, so there is no correct answer to be right about.

What is implemented is V8's choice, because matching what JavaScript actually
prints is the only useful target. The 21,280 recorded strings therefore *are*
the contract rather than a corroboration of one, and if a future V8 changes
them the differential test is what will say so. Radix 10 delegates to the
specified path.

### Why this is provable rather than merely tested

6.1.6.1.20 defines the result rather than an algorithm: the shortest digit
string that identifies the double, the closest one when several are equally
short, and the even one on a tie. Two properties therefore *are* the
specification, and both are checked over random doubles, every power of two,
the subnormal range and short decimals:

- **round trip** — parsing the output returns the same double, bit for bit;
- **shortest** — no decimal with fewer significant digits does.

With the parser in place the round trip is also checked against *our own*
parser, so the pair is verified without depending on any platform: whatever
`toEcmaString` writes, `toEcmaDouble` reads back as the identical double.

That is a stronger position than the regular expression side, where the
specification's semantics can only be checked against an interpreter. It also
makes the implementation replaceable: the tests hold any algorithm to the
specification, not to the current one.

They do not cover everything. Ties satisfy both properties either way, so
`exactTiesTakeTheEvenSignificand` and the differential fixture carry that rule
— rounding ties down instead of to even costs 48 values in 231,948, and neither
property notices.

### Fixed, exponential and precision

`toFixed`, `toExponential` and `toPrecision` (21.1.3.3, 21.1.3.2, 21.1.3.5):

```kotlin
1234.5678.toEcmaFixed(2)          // "1234.57"
1234.5678.toEcmaExponential(2)    // "1.23e+3"
1234.5678.toEcmaExponential(null) // "1.2345678e+3" - shortest, as toString picks
1234.5678.toEcmaPrecision(6)      // "1234.57"
1234.5678.toEcmaPrecision(2)      // "1.2e+3"
```

These three round **ties up**; `toString` rounds ties to even. That is not an
inconsistency to paper over — the specification says "pick the larger n" for
these and "choose the one that is even" for `toString`, and both are checked
against node.

What it does *not* mean is that `(1.005).toFixed(2)` is `"1.01"`. It is
`"1.00"`, because 1.005 is not 1.005: the nearest double is
1.00499999999999989..., and the rounding applies to the value that is actually
there. Out-of-range arguments throw `IllegalArgumentException` where JavaScript
throws a RangeError.

Verified over a grid of 4,054 values against every argument from 0 to 100,
which is 97,296 strings compared with node.

### How the digits are produced

Two implementations, and the fast one is only allowed to answer when it can
prove it should:

- **Grisu3** does the work in 64-bit arithmetic against a table of cached
  powers of ten. It tracks the rounding error it accumulates and **declines**
  whenever that error could change the answer — about one value in two hundred.
- **The exact rational method** of Steele & White, in Burger & Dybvig's form,
  answers the rest. Big integers, no tables, every step checkable by reading it.

Correctness therefore does not depend on Grisu3 being right about which values
are hard, only on it refusing to answer when unsure. A bug that makes it
doubtful costs speed; a bug that makes it confidently wrong is caught by
`Grisu3AgreesWithExactTest`, which compares the two implementations directly
over subnormals, powers of two, simple decimals and 50,000 random doubles. That
test also asserts the fallback is still *reached*, so it cannot rot into dead
code.

The cached powers are generated, not transcribed — `tools/numbers/gen-pow10.mjs`
computes each entry exactly with `BigInt` and verifies it lands within half a
unit in the last place. A single wrong digit in a table like that produces
answers that are wrong for only a handful of inputs.

Against `java.lang.Double.toString` (`./gradlew bench`):

| | exact only | with Grisu3 |
| --- | --- | --- |
| mixed exponents | 63.5x | **3.5x** |
| short decimals | 6.8x | **2.0x** |

The JDK is not producing the same string — it lays the digits out differently
and is not shortest for the smallest subnormals — so that is a scale reference,
not an equivalence.

## Where Kotlin disagrees with JavaScript

Two of these produce wrong answers rather than errors, on every target:

```kotlin
"x\uFEFF".trim()          // "x\uFEFF" - Kotlin keeps the byte order mark
"x\uFEFF".ecmaTrim()      // "x"

kotlin.math.round(0.5)    // 0.0 - ties to even
EcmaMath.round(0.5)       // 1.0 - ties toward positive infinity
kotlin.math.round(2.5)    // 2.0
EcmaMath.round(2.5)       // 3.0
```

The trim difference is five characters, measured rather than assumed: Kotlin
strips U+001C to U+001F, which JavaScript keeps, and keeps U+FEFF, which
JavaScript strips. U+00A0 is stripped by both. Which characters count is
verified over the whole BMP.

`EcmaMath` covers only the `Math` functions ECMA-262 specifies exactly —
`round`, `trunc`, `sign`, `clz32`, `imul`, `fround`. The rest of `Math` is
implementation-approximated, so there would be nothing to be correct against.

`round` is not `floor(x + 0.5)`: for `0.49999999999999994` that formula answers
1 where the answer is 0, because the addition rounds up. `fround` is not
`toFloat().toDouble()` either — on Kotlin/JS a `Float` is a JavaScript number,
so that returns the input unchanged, silently and only there.

## Normalisation

```kotlin
"e\u0301".normalize()                          // "é"  - combining acute composed
"é".normalize(NormalizationForm.NFD)           // "e\u0301"
"\uFB01".normalize(NormalizationForm.NFKC)     // "fi" - the ligature unpicked
```

`java.text.Normalizer` is JVM-only and Kotlin/Native has nothing, so
multiplatform code comparing user-entered text has been comparing sequences
that look identical and are not. That matters beyond display: a username, a
filename or a domain compared without normalising can be spoofed by a different
encoding of the same glyphs.

The tables come from the UCD and are generated, not transcribed. Hangul is
absent from them deliberately — its mappings are arithmetic, and 11,172
syllables would dwarf everything else.

Correctness rests on Unicode's own conformance suite. `NormalizationTest.txt`
supplies 20,034 rows whose expectations no implementation produced, and each is
checked against the invariants the file states — every one of its five columns
must map to the same place under each form, which is what catches an
implementation that is right about examples and wrong about idempotence.
Generation refuses to write the fixture if node disagrees with any row, so the
two sources are known to agree first. On top of that, all 1,112,064 code points
are checked individually in all four forms against node.

## URI escaping

```kotlin
"a b+c/d?e=f&g".encodeUriComponent()     // "a%20b%2Bc%2Fd%3Fe%3Df%26g"
"http://x.com/a b?c=d".encodeUri()       // "http://x.com/a%20b?c=d"
"http://x.com/a%20b%2Fc".decodeUri()     // "http://x.com/a b%2Fc" - %2F stays
```

`java.net.URLEncoder` is **not** this function. It implements
`application/x-www-form-urlencoded`: a space becomes `+`, and `~`, `!`, `*`,
`'`, `(` and `)` are escaped when ECMA-262 leaves them alone. It is correct for
form bodies and wrong for URIs, and reaching for it is a standing source of
subtly broken links. Common Kotlin has no equivalent at all.

Escaping is what callers rely on to stop untrusted text from changing a URI's
structure, so it is verified over **every one of the 1,114,112 code points**
rather than a sample — the same reason `RegExp.escape` is. Unpaired surrogates
throw `UriError`, as they must: they have no UTF-8 form.

Decoding is where the security lives, and it rejects what UTF-8 forbids:
overlong forms, encoded surrogates, sequences past U+10FFFF, and truncated
escapes. Accepting any of those turns a decoder into a way to smuggle
characters past a filter. Since decoding's input is text and cannot be
enumerated, `./gradlew uriFuzz` explores it against node; 600,000 cases run
clean, roughly half of them rejections.

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

1. **Recorded differential suite** — ~42,750 pattern/flags/input cases whose
   expected results were captured from node, replayed on every build on every
   platform. Regenerate with
   `node tools/difftest/gen-fixture.mjs src/commonTest/kotlin/io/github/mgilbir/ecma262/DiffFixture.kt 12`
   — the trailing `12` is the number of inputs sampled per pattern, and
   changing it changes the whole corpus.
2. **Live fuzzing** — random patterns and inputs compared against a running node
   for `exec`, all-matches, `replace` and `split`. Millions of cases have been
   run clean; it is what found the surrogate backreference bug that
   `SurrogateBoundaryTest.backreferenceComparesCodePointsUnderUnicode` now
   guards.
3. **Test262** — the conformance suite's generated `v`-flag class tests (114
   cases covering nesting, set operations, `\q{…}` and properties of strings)
   and its Number formatting tests (205 cases, expectations taken from the
   suite's own assertions rather than from any engine),
   replayed offline. These are written by the specification's authors, so they
   are independent of the node-derived corpus above. Regenerate with
   `node tools/test262/gen-unicodesets-fixture.mjs …`.
4. **Targeted suites** — parser acceptance/rejection, Unicode tables, the `v`
   grammar, modifier scoping, surrogate boundaries, the API surface, the step
   limit, and `RegExp.escape` over every code point.

The fuzzer also generates unstructured "syntax soup" patterns, not just ones its
own grammar knows how to build — that is what checks the parser against
constructs this codebase has never heard of.

Numbers get the same two layers. `./gradlew numberFuzz -Pcount=200000 -Pseed=7`
generates fresh cases — random doubles through every method, and random strings
through the parser — and compares each against node. The parser is what needs
it: its input space is text, and no recorded fixture covers the ways a string
can almost be a numeric literal.

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

It goes wrong in the other direction too. A *negated* class loses the case
extension it should have as soon as any modifier group appears — even one that
only removes flags — while a bare `\w` in the same pattern keeps it, so V8
contradicts itself within one pattern:

```js
/(?-i:a)?[^\w]/vi.exec("aſ")  // matches in V8 — ſ folds to "s", so [^\w]
/(?:a)?[^\w]/vi.exec("aſ")    // null, as it should be
/(?-i:a)?\w/vi.test("ſ")      // true — the positive form is still extended
```

This engine scopes all of them, and `ModifierGroupTest` pins both directions
down. Any modifier group combined with a word-class escape under `i` is skipped
by the differential harnesses; it costs 180 of ~42,750 recorded cases.

**A V8 defect skipping matches before an astral tail.** A non-multiline `$`
lets V8 begin its scan near the end of the input rather than at position 0. The
offset it jumps to is a minimum match length counted in code points but applied
to a UTF-16 index, so an astral tail pushes the scan past a position that
matches:

```js
/[^\w]$/u.exec("\u{1F600}")     // null in V8
/^[^\w]$/u.test("\u{1F600}")    // true  — same class, same character
/[^\w]$/uy.exec("\u{1F600}")    // matches at 0 with lastIndex 0
/[^\w](?![\s\S])/u.exec(…)     // matches — same meaning, no `$`
/[^\w]$/um.exec(…)              // matches — `m` disables the optimisation
/[^\w]$/v.exec(…)               // matches — `v` is unaffected
```

Under `u` and `v` the input is a list of code points, so all of these ask the
same question and the answer is a match at 0; V8 contradicts itself. This
engine matches over code points throughout, and `EndAnchorAstralTest` pins it
down. The differential harnesses detect it behaviourally — scanning code-point
boundaries with a sticky copy of the same pattern and skipping the case when
sticky finds a match that plain `exec` missed — rather than guessing which
patterns trigger it. Found by the nightly fuzzer, one case in 500,000.

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

Tagging is the trigger: `.github/workflows/release.yml` runs on a `v*` tag and
does the whole release. It checks the tag against the version in
`build.gradle.kts` and that the changelog has a section for it, builds and runs
the full test suite plus 200,000 fuzz cases on Linux, then rebuilds every
target on macOS, publishes, releases to Maven Central, and creates the GitHub
release page with notes from `CHANGELOG.md` and the jars attached.

Two things about that are deliberate. Publishing happens from **macOS** because
it is the only host that can compile every target. And the Central deployment
is released **automatically** — publishing a version is irreversible, and a
tag is the point of no return, not a later button.

```bash
# 1. bump `version` in build.gradle.kts, commit
# 2. tag and push
git tag -a v0.1.4 -m "ktecma262 0.1.4"
git push origin v0.1.4
```

Publication is skipped — with a notice, not a failure — unless these repository
secrets are present. The same names work as environment variables locally:

| Secret | What it is |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | Token username from central.sonatype.com/usertoken |
| `CENTRAL_TOKEN_PASSWORD` | Token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armoured GPG private key |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for that key |

It also needs the `io.github.mgilbir` namespace verified in the Central portal.
Credentials are only ever read from the environment — never from a file in the
repository, and never written to one.

### How publishing works, and why it works that way

The build stages every publication into one directory, zips it, and uploads
that single bundle to the Central Portal:

```bash
./gradlew centralBundle verifyCentralBundle
# -> build/distributions/ktecma262-<version>-bundle.zip
```

It does **not** upload each publication separately for the server to assemble.
That is what broke 0.1.3: all seven publications uploaded without error into a
single OSSRH staging repository, and the deployment the Portal assembled from
it contained four. `ktecma262-js`, `ktecma262-iosarm64` and
`ktecma262-iossimulatorarm64` were simply absent, nothing failed, and by the
time it was visible the release was already publishing. A version on Central
cannot be replaced.

Two checks guard it now, and both have been shown to fail when they should:

- `verifyCentralBundle` fails if a publication is missing from the bundle, or
  missing its POM, module metadata or signature — before anything is uploaded.
- `.github/scripts/check-deployment.py` compares the modules bundled against
  the components the Portal reports for the deployment, and fails if any are
  missing. Against 0.1.3's real deployment it names all three.

Deployments are uploaded as `USER_MANAGED`, so the Portal validates and then
waits: releasing to Central is irreversible, so it stays a decision rather than
a side effect of pushing a tag.

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
