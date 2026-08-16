# Changelog

## Unreleased

### Added

- `Double.toEcmaString()` in `io.github.mgilbir.ecma262.number` — JavaScript's
  `Number::toString`, ECMA-262 6.1.6.1.20. No Kotlin target produces it:
  Kotlin/JS does because it is JavaScript, but Kotlin/JVM and Kotlin/Native
  print `1.0`, `1.0E21` and `4.9E-324` where JavaScript prints `1`, `1e+21` and
  `5e-324`. Over 200,000 random doubles the JVM's string differs 98.4% of the
  time.

  Almost all of that is layout — JavaScript stays positional out to 10^21 and in
  to 10^-6 — but not all of it: a JDK 21 `Double.toString` is not shortest for
  the smallest subnormals, so reusing the platform digits and re-laying them out
  would be wrong in exactly the cases hardest to notice.

  The specification defines the result rather than an algorithm, which makes two
  properties equivalent to it: the output round-trips, and no shorter decimal
  does. Both are checked over random doubles, every power of two, the subnormal
  range and short decimals, so the tests hold any implementation to the
  specification rather than to this one. Four planted bugs — an extra digit, a
  missing round-up, ignoring round-half-to-even, and dropping the asymmetric gap
  below powers of two — are each caught by the property that should catch them.

  Ties escape both properties, since both candidates round-trip and are equally
  short. Rounding them down instead of to the even significand costs 48 values
  in 231,948; the differential fixture and an explicit test carry that rule.

  Implemented with the exact rational method of Steele & White as presented by
  Burger & Dybvig: big integers, no lookup tables. Correctness first — it is 63x
  slower than `java.lang.Double.toString` at the extremes of the exponent range
  and 6.8x slower for everyday values. A table-driven method would close that.

- `String.toEcmaDouble()` — `StringToNumber`, ECMA-262 7.1.4.1.1, the
  conversion `Number("…")` performs. The whole string must be a numeric
  literal, so this is not `parseFloat`; `0x`/`0o`/`0b` literals are accepted but
  take no sign, and an empty or all-whitespace string is `+0`. All 68 grammar
  and rounding cases are taken from node.

  Correctly rounded, which can turn on the 767th significant digit. Bounded
  against the inputs that have historically hung decimal parsers: significant
  digits are capped with the rest folded into a sticky flag, the exponent is
  clamped as it is read, and out-of-range magnitudes resolve before any big
  integer is built.

  Three planted bugs are caught — ties rounding up rather than to even,
  accepting trailing garbage, and ignoring the sticky flag. The third initially
  was **not**: no test exercised a value truncated at the cap that was also an
  exact tie, because a genuine tie never needs more than 767 digits.
  `digitsBeyondTheCapBreakTies` constructs one — the exact midpoint between 1
  and the next double, padded past the cap, with a single digit beyond it that
  decides the result.

- Formatting and parsing are now checked as inverses against each other, so the
  round-trip property no longer leans on the host's decimal parser.

- `Double.toEcmaString(radix)` for radices 2 to 36. Unlike everything else
  here this is compatibility rather than conformance: ECMA-262 calls the result
  for a radix other than 10 *implementation-approximated* and defines nothing
  further, so V8's behaviour is what is implemented and the 21,280 recorded
  strings are the contract rather than a check on one. Radix 10 delegates to the
  specified path. Four planted bugs are caught — dropping the round-half-to-even
  step, removing the floor on the error term, skipping the zero fill above 2^53,
  and stopping the fraction a digit early.

- `Double.toEcmaFixed()`, `Double.toEcmaExponential()` and
  `Double.toEcmaPrecision()` — `Number.prototype.toFixed`, `toExponential` and
  `toPrecision` (21.1.3.3, 21.1.3.2, 21.1.3.5). Checked against node over a grid
  of 4,054 values crossed with arguments from 0 to 100: 97,296 strings.

  All three round ties **up**, where `toString` rounds them to even. The
  specification asks for exactly that difference, and a planted swap to
  round-half-even is caught. So is losing the rounding carry — `(99.995)`
  `.toFixed(2)` is `"100.00"`, and taking the decimal exponent from a rounded
  first digit instead of an unrounded one gave `"999.95"` until the grid caught
  it — and getting the `toPrecision` exponential threshold off by one.

- Grisu3 as the fast path for `Double.toEcmaString()`, with the exact method
  kept as the fallback. It works in 64-bit arithmetic against a generated table
  of cached powers of ten, tracks the rounding error it accumulates, and
  declines when that error could change the answer — measured at 0.51% of
  random doubles and 0% of subnormals and simple decimals.

  Correctness does not depend on it being right about which values are hard,
  only on it refusing to answer when unsure. `Grisu3AgreesWithExactTest`
  compares the two implementations directly, and asserts the fallback is still
  reached so it cannot rot into dead code. Three planted bugs — never
  declining, truncating instead of rounding in the 128-bit multiply, and
  dropping the closer-lower-boundary rule at powers of two — are each caught by
  both that test and the node differential.

  The cached powers are generated with `BigInt` and checked to within half a
  unit in the last place during generation, rather than transcribed.

  Against `java.lang.Double.toString`: 63.5x slower becomes 3.5x for values at
  the extremes of the exponent range, and 6.8x becomes 2.0x for everyday ones.

- A differential fixture for numbers, checked against node over 231,948 values:
  a deterministic sweep of the bit space, every subnormal up to 20,000, every
  power of two, and short decimals. Walked from an index on both sides, so the
  fixture is 3 KB rather than a megabyte.

### Testing

- A fourth V8 defect is recognised and skipped. A non-multiline `$` lets V8
  start its scan near the end of the input, using a minimum match length
  counted in code points but applied to a UTF-16 index, so an astral tail
  pushes the scan past a position that matches: `/[^\w]$/u` finds nothing in
  `"\u{1F600}"` while `/^[^\w]$/u`, `/[^\w]$/uy`, `/[^\w]$/um` and
  `/[^\w]$/v` all match the same character. This engine matches over code
  points throughout; `EndAnchorAstralTest` pins the behaviour down.

  The detector is behavioural rather than syntactic: it scans code-point
  boundaries with a sticky copy of the pattern and reports a defect only when
  sticky finds a match plain `exec` skipped, so it tests V8's actual
  self-contradiction instead of a guess about which patterns trigger it. Over
  500,000 fuzz cases it excluded exactly one, and no recorded corpus case.

  Found by a local fuzz run on `/([^\w]??){1,2}$\B(?!(?<=\b))/sug`.

## 0.1.4

Republishes 0.1.3 complete. The library is unchanged; 0.1.3 reached Maven
Central with four of its seven modules.

### Fixed (publishing)

- Publishing no longer uploads each publication separately to the OSSRH
  Staging API for the server to assemble into a deployment. That assembly is
  what failed: all seven publications uploaded without error, into a single
  staging repository, and the deployment the Portal built from it contained
  four. Nothing on the upload side reported a problem.

  The build now stages every publication into one directory, zips it, and
  uploads that single bundle to the Central Portal API. What is uploaded is
  what is published, with no server-side assembly step in between.

- `./gradlew verifyCentralBundle` checks the bundle before it is uploaded:
  every publication present, each with a POM, Gradle module metadata and a
  detached signature.

- After uploading, the release workflow compares the modules it bundled
  against the components the Portal reports for the deployment, and fails if
  any are missing. Run against 0.1.3's actual deployment, this reports
  exactly `ktecma262-iosarm64, ktecma262-iossimulatorarm64, ktecma262-js`.

## 0.1.3

**Broken on Maven Central: only `ktecma262`, `ktecma262-jvm`,
`ktecma262-linuxx64` and `ktecma262-macosarm64` were published.** The root
module still declares variants for JS and both iOS targets, so resolving it
for those platforms fails outright. The cause was in publishing, not in the
build — the artifacts below were all produced and uploaded correctly. Use
0.1.4.

Adds native targets and fixes a parser bug found by the nightly fuzzer.

### Added

- `macosArm64`, `iosArm64`, `iosSimulatorArm64` and `linuxX64` targets. The
  engine is pure `commonMain` Kotlin with no `expect`/`actual`, so the sources
  are unchanged; all 153 tests run on Kotlin/Native exactly as they do on JVM
  and JS, including the ~42,750-case recorded differential suite.

### Fixed

- Annex B's fallback for an invalid `\c` consumed the `c`. Both
  `ExtendedAtom :: \ [lookahead = c]` and `ClassAtomNoDash :: \ [lookahead = c]`
  denote the backslash **alone**, leaving the `c` to be parsed as the next
  atom, so anything binding to it bound to the wrong thing:

  - a quantifier covered both characters, making them jointly optional —
    `/a\c*/` matched a bare "a", where the pattern is `a`, `\`, `c*` and
    requires a literal backslash;
  - in a class the `c` could not open a range, so `[\c-z]` was the three
    characters `\`, `c`, `-` rather than `\` plus `c-z`.

  Found by the nightly differential fuzzer on `/a\c*{?/ig`, one case in
  500,000. The class-range half was not reached by the fuzzer and came out of
  reading the grammar while fixing the first.

### Testing

- A second direction of the known V8 modifier-scoping defect is now recognised
  and skipped. The presence of a modifier group makes V8 drop the case
  extension from a *negated* word class — `/(?-i:a)?[^\w]/vi` matches the long
  s, which folds to "s" and so belongs to `\w` — while a bare `\w` in the same
  pattern keeps the extension, so V8 contradicts itself. Previously only an
  *added* `i` was recognised, on the belief that `(?-i:…)` scoped correctly.
  This engine's behaviour is unchanged and matches V8's own answer once the
  modifier group is removed; `ModifierGroupTest` now pins both directions.
  Broadening the skip costs 180 of ~42,750 recorded cases.
- The recorded corpus covers the `\c` fallback in all three modes, and grew
  from 41,271 to 42,753 cases.

### Release process

None of this changes the library, but all of it is why 0.1.2 was unusable for
native consumers.

- Releases are now built and published from macOS. It is the only host that
  can compile every target: Apple targets need Xcode, and Kotlin/Native
  cross-compiles the Linux target from macOS.
- `./gradlew verifyPublishedVariants` fails when a declared target would not
  actually be published. Kotlin creates a publication only for targets the
  host can build, while the root module lists a variant for every declared
  target — so publishing from the wrong host uploads a module referencing
  artifacts that do not exist.
- CI builds every target on macOS on each push, rather than discovering Apple
  breakage on release day.
- The GitHub release page is created by the release workflow, with notes taken
  from this file and the jars attached. `v0.1.1` and `v0.1.2` were tagged and
  published without one. The workflow fails early if the changelog has no
  section for the version being released, rather than after the artifacts are
  already immutable.
- The Central deployment is released automatically (`publishing_type=automatic`)
  instead of waiting for a manual Publish. Pushing a tag is now the point of no
  return.

## 0.1.2

No library changes: the compiled artifacts are byte-for-byte identical to
the 0.1.1 tag. This version exists only because the 0.1.1 release never
completed.

Its release run hung for over an hour in the differential fuzz step. The
fuzzer deliberately generates patterns that are catastrophic for a
backtracker; this engine bounds them with a step budget, but the node
process used as the oracle has no such limit and cannot be interrupted
from JavaScript, so V8 ran at full CPU until the run was cancelled.

### Fixed (test infrastructure only)

- Cases that exceed this engine's step budget are now screened out before
  being sent to the oracle. The comparison already skipped them, so no
  coverage is lost, and the failing seed now completes in 37 seconds.
- A watchdog kills the oracle after two minutes without output and names
  the case it stopped on.
- The oracle streams results instead of buffering them until close, so
  progress is no longer lost when it is killed.
- The oracle's stderr is inherited rather than left in an undrained pipe,
  which could have blocked it.
- The oracle process is destroyed on shutdown; a cancelled run previously
  left node spinning indefinitely.

`v0.1.1` remains as a tag but was never published.

**0.1.2 has JVM, JS and common variants only.** A Kotlin Multiplatform build
that declares a native target cannot resolve it, and — because Gradle takes the
first repository holding a coordinate — it will shadow a locally published
build of the same version. Use 0.1.3.

## 0.1.1

Fixes a parser bug found by the differential fuzzer immediately after 0.1.0
was tagged.

### Fixed

- `\0` inside a character class is the NUL escape in every mode. Both
  Unicode-mode class paths rejected it: the `u` path rejected every digit
  escape outright, where `CharacterEscape :: 0 [lookahead ∉ DecimalDigit]`
  keeps `\0` valid, and the `v` class-set path had no digit branch at all.
  `[\0]` and `[+\0d]` now match NUL under `u` and `v` as they already did
  under Annex B, while `[\01]`, `[\1]` and `[\9]` remain SyntaxErrors in
  Unicode mode.

  Found on `/st[+\0d]&+/vs` by the fuzzer's unstructured-pattern mode — a
  shape the grammar-driven generator cannot produce. The recorded corpus
  now covers digit escapes inside classes in all three modes.

**0.1.0 is affected by this bug and should not be used.**

## 0.1.0

First release. An ECMA-262 (JavaScript) regular expression engine in pure
Kotlin for Kotlin Multiplatform: recursive-descent parser, bytecode
compiler and backtracking VM.

### Added

- Flags `i g m s u v y d`; Annex B web-compatibility syntax by default,
  with strict ECMA-262 available through `Syntax.STRICT`
- Named groups including ES2022 duplicates across alternatives;
  backreferences including forward references
- Lookahead and lookbehind, including variable-length lookbehind with
  right-to-left capture semantics
- `v` (UnicodeSets): nested classes, `&&` intersection, `--` difference,
  `\q{…}` string literals, and properties of strings (`\p{RGI_Emoji}` and
  its five constituents)
- Regexp modifiers `(?i:…)`, `(?-i:…)`, `(?i-ms:…)` and `RegExp.escape`
- `exec`, `test`, `findAll`, `replace`, `split`, `search`, and a
  JavaScript-compatible `lastIndex`
- Unicode 17.0.0 compiled in, so `\p{…}` does not vary with the host JDK
- Java 17 bytecode for the JVM target, enforced by a build check that
  reads the emitted class files

### Notes

Backtracking runs on an explicit stack with an undo log, so deep
backtracking cannot overflow the call stack. Matching is bounded by
`RegExp.maxSteps` (default 1,000,000), and the budget spans a whole
operation rather than each match.

Three divergences from V8 were deliberate and documented in the README,
each covered by tests: match positions that split a surrogate pair under
`/u`, single-character `\q{}` folding under `/vi`, and modifier scoping
leaking into `\w`.
