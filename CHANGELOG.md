# Changelog

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

Three divergences from V8 are deliberate and documented in the README,
each covered by tests: match positions that split a surrogate pair under
`/u`, single-character `\q{}` folding under `/vi`, and modifier scoping
leaking into `\w`.
