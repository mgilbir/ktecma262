// The shared pattern/input corpus for differential testing against node.
//
// Kept separate from the fixture generator so the same corpus can drive both the
// generated (offline) fixture and the live fuzzing runner.

/**
 * Detects a second known V8 defect, so comparisons can skip it.
 *
 * Under `/vi`, a single-character `\q{…}` element should behave exactly like the
 * same character written plainly — the specification treats a length-1 string as
 * a character, which is why `[^\q{a}]` is legal at all. V8 folds the pattern
 * side but not the input, so `[\q{a}]/vi` fails to match "A" while `[a]/vi`
 * matches it. The inconsistency shows up inside a single class:
 *
 *   /[a\q{b}]/vi.test("A")  // true  — the plain character folds
 *   /[a\q{b}]/vi.test("B")  // false — the \q{} character does not
 *
 * Longer strings are unaffected: `[\q{ab}]/vi` matches "AB" correctly.
 * This engine follows the specification and folds both.
 */
export function hasSingleCharQuotedString(pattern, flags) {
  if (!flags.includes("v") || !flags.includes("i")) return false;
  for (const m of pattern.matchAll(/\\q\{([^}]*)\}/g)) {
    for (const alt of m[1].split("|")) {
      // Count code points, and treat any escape as a single character.
      const collapsed = alt.replace(/\\u\{[0-9a-fA-F]+\}|\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\./g, "x");
      if ([...collapsed].length === 1) return true;
    }
  }
  return false;
}

/**
 * Detects a third known V8 defect, so comparisons can skip it.
 *
 * The mere presence of a modifier group perturbs how V8 builds the
 * WordCharacters set for `\w`, `\W`, `\b` and `\B` elsewhere in the pattern.
 * It goes wrong in both directions, and this fuzzer found both.
 *
 * An added `i` leaks *out* of the group:
 *
 *   /(?i:c)\w/u.test("c\u017F")   // true in V8 - the long-s leaked in
 *   /(?:c)\w/u.test("c\u017F")    // false, as it should be
 *   /(?i:c)d/u.test("cD")        // false - literals do scope correctly
 *
 * And a *negated* class loses the case extension it should have, even when the
 * group only removes flags, so `[^\w]` wrongly admits the long s:
 *
 *   /(?-i:a)?[^\w]/vi.exec("a\u017F")  // matches in V8
 *   /(?:a)?[^\w]/vi.exec("a\u017F")    // null, as it should be
 *   /(?-i:a)?\w/vi.test("\u017F")      // true - the positive form is still
 *                                    // extended, so V8 contradicts itself
 *
 * This engine scopes all of them. The check is deliberately broad: any modifier
 * group at all, combined with any word-class escape anywhere in the pattern,
 * whenever ignoreCase is in effect - case extension is what V8 gets wrong, and
 * it only exists under `i`.
 */
export function hasModifierWithWordEscape(pattern, flags = "") {
  // A modifier group as opposed to a plain `(?:` - at least one flag letter,
  // before or after the dash.
  const hasModifier = /\(\?(?:[ims]+(?:-[ims]+)?|-[ims]+):/.test(pattern);
  if (!hasModifier) return false;
  if (!/\\[wWbB]/.test(pattern)) return false;
  const addsIgnoreCase = /\(\?[ms]*i[ms]*(?:-[ims]+)?:/.test(pattern);
  return flags.includes("i") || addsIgnoreCase;
}

/**
 * Detects a fourth known V8 defect, so comparisons can skip it.
 *
 * A non-multiline `$` lets V8 start its scan near the end of the input instead
 * of at position 0. The offset it jumps to is a minimum match length counted in
 * code points, but it is applied to a UTF-16 index, so when the tail holds
 * astral characters the scan begins past the position that would have matched:
 *
 *   /[^\w]$/u.exec("\u{1F600}")      // null in V8
 *   /^[^\w]$/u.test("\u{1F600}")     // true  - same class, same character
 *   /[^\w]$/uy.exec("\u{1F600}")     // matches at 0 with lastIndex 0
 *   /[^\w](?![\s\S])/u.exec(...)   // matches - same meaning, no `$`
 *   /[^\w]$/um.exec(...)            // matches - `m` disables the optimisation
 *
 * The spec matches over a list of code points, so all of these are the same
 * question and the answer is a match at 0. V8 contradicts itself, which is what
 * this checks: scan the code-point boundaries with a sticky copy of the same
 * pattern, and report a defect when sticky finds a match that plain `exec`
 * skipped. That tests the actual inconsistency rather than a guess about which
 * patterns trigger it.
 *
 * The cheap conditions are checked first because the sticky scan is not free.
 */
export function hasEndAnchorAstralMiss(pattern, flags, input) {
  if (!/[uv]/.test(flags)) return false;
  if (flags.includes("m")) return false;
  if (!pattern.includes("$")) return false;
  if (!/[\uD800-\uDBFF][\uDC00-\uDFFF]/.test(input)) return false;

  let plain, sticky;
  try {
    const base = flags.replace(/[gy]/g, "");
    plain = new RegExp(pattern, base);
    sticky = new RegExp(pattern, base + "y");
  } catch {
    return false;
  }

  const first = plain.exec(input);
  for (let i = 0; i < input.length; ) {
    if (first && first.index <= i) break;
    sticky.lastIndex = i;
    if (sticky.exec(input) !== null) return true;
    i += input.codePointAt(i) > 0xffff ? 2 : 1;
  }
  return false;
}

/** Deterministic LCG, so a fuzz failure is reproducible from its seed. */
export function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

// Patterns grouped by the behaviour they exercise. Every one of these is run
// against every input in INPUTS, so a pattern here costs ~40 cases.
export const PATTERNS = [
  // literals and escapes
  "a", "abc", "a\\.c", "\\n", "\\t", "\\x41", "\\u0041", "\\0", "a\\/b",
  "\\$", "\\^", "\\\\",

  // quantifiers, greedy and lazy
  "a*", "a+", "a?", "a*?", "a+?", "a??",
  "a{2}", "a{2,}", "a{2,4}", "a{0,3}", "a{2,4}?", "a{0}",
  "ab*c", "a.*b", "a.*?b", "(a+)(a*)", "(a+?)(a*)",
  "[ab]*c", "(ab)+", "(ab)*?c",

  // catastrophic-ish backtracking shapes (bounded by the step budget)
  "(a+)+b", "(a|a)*b", "(a*)*b", "(a?){3}b",

  // alternation
  "a|b", "ab|cd", "^(a|ab)c", "(a|ab)(c|bcd)", "(|a)*", "(a||b)+",

  // groups and captures
  "(a)(b)(c)", "(a(b(c)))", "(?:ab)+", "(a)|(b)", "((a)|b)+",
  "(?:(a)|(b))*", "(z)((a+)?(b+)?(c))*",

  // backreferences
  "(a)\\1", "(a+)\\1", "\\1(a)", "(a)?\\1b", "(?:(a)|b)\\1c",
  "(a)(b)\\2\\1", "(?<x>a)\\k<x>", "(?<x>a)|(?<x>b)",
  "(?:(?<x>a)|(?<x>b))\\k<x>",

  // anchors
  "^a", "a$", "^a$", "^", "$", "\\ba", "a\\b", "\\Ba", "a\\B",
  "\\bfoo\\b", "^\\w+$",

  // character classes
  "[abc]", "[^abc]", "[a-z]", "[^a-z]", "[a-zA-Z0-9_]", "[]", "[^]",
  "[-a]", "[a-]", "[\\b]", "[\\d]", "[\\D]", "[\\w]", "[\\W]", "[\\s]", "[\\S]",
  "[\\d\\s]", "[^\\d]", "[a-c-e]", "[\\]]", "[.]", "[*+?]",
  // digit escapes inside a class: \0 is NUL in every mode, the rest are not
  "[\\0]", "[+\\0d]", "[\\01]", "[\\1]", "[\\9]", "[\\0-\\x10]",
  // Annex B `\c`: an invalid control escape denotes the backslash alone, so a
  // quantifier binds to the `c` and a class range can open on it
  "\\c", "\\c*", "a\\c*", "\\c{2}", "\\cA", "\\cz", "\\c1", "\\c-",
  "[\\c]", "[\\c-z]", "[\\c1]", "a\\c*{?",
  // Control escapes inside a class, which the v-mode class-set path did not
  // handle at all: valid ones, and the two shapes that stay SyntaxErrors there.
  "[\cf_]", "[\cA]", "[\cf-\cz]", "[a\cfb]", "[^\cf]", "[\cf\cg]",

  // shorthand classes
  "\\d+", "\\D+", "\\w+", "\\W+", "\\s+", "\\S+", "\\d\\w\\s",

  // dot
  ".", ".+", "^.$", "^.*$", "a.b",

  // lookaround
  "a(?=b)", "a(?!b)", "(?<=a)b", "(?<!a)b",
  "(?=(a))", "(?!(a))b", "(?<=(a))b", "(?<!(a))b",
  "(?<=a+)b", "(?<=(a|bb))c", "(?<=^a)b", "\\d+(?= dollars)",
  "(?<=\\$\\s*)\\d+", "(?=.*a)(?=.*b)", "(?<=(\\w)(\\w))c",

  // nested lookaround
  "(?=a(?=b))ab", "(?<=(?<=a)b)c", "(?!(?!a))a",

  // multiline / dotAll interaction
  "^b", "b$",

  // unicode
  "\\u{1F600}", "😀", "[\\u{1F600}-\\u{1F602}]", "^.$", "\\p{L}+", "\\p{Lu}",
  "\\P{L}", "\\p{Script=Greek}+", "\\p{Nd}", "\\p{White_Space}",
  "[\\p{L}\\p{N}]+", "\\p{Extended_Pictographic}",

  // Annex B constructs
  "\\5", "\\8", "\\58", "\\08", "\\c1", "\\c", "a{2 x}", "{", "}", "]",
  "\\p{L}", "\\k<a>", "[\\c1]", "[\\5]", "(a)[\\1]", "\\q", "\\-",
  "(?=a)*",

  // empty and degenerate
  "", "(?:)", "()", "(){2}", "a{0}b", "(?:a)?",

  // v-flag class sets. Harmless under other flag sets too: node decides whether
  // each is valid, and the engine must agree either way.
  "[[a][b]]", "[[a-z][0-9]]", "[a--b]", "[[a-z]--[aeiou]]", "[a&&b]",
  "[[a-z]&&[b-d]]", "[\\p{L}--\\p{Lu}]", "[[a-c]--b]", "[a&&b&&c]", "[a--b--c]",
  "[a&&b--c]", "[^[a][b]]", "[\\q{abc}]", "[\\q{a|bc}]", "[\\q{}]", "[\\q{ab|a}]",
  "[a\\q{ab}]", "^[\\q{ab|abc}]$", "[^\\q{a}]", "[^\\q{ab}]", "[\\q{ab}--\\q{ab}]",
  "[[\\q{ab}]&&[\\q{ab}]]", "^[\\q{ab|a}]b$", "[(]", "[\\(]", "[&]", "[&&]",
  "[!!]", "[\\&]", "[a&b]", "[^^]", "[-]", "[\\-]", "[|]", "[/]",
  "(?<=[\\q{ab}])c", "[\\q{a}\\q{b}]",
  // properties of strings (v only)
  "\\p{RGI_Emoji}", "\\p{Basic_Emoji}", "\\p{Emoji_Keycap_Sequence}",
  "[\\p{RGI_Emoji}]", "[\\p{Basic_Emoji}--\\q{a}]", "[\\p{Basic_Emoji}\\q{ab}]",
  "^\\p{RGI_Emoji}$", "\\P{RGI_Emoji}", "[^\\p{RGI_Emoji}]",

  // regexp modifiers (ES2025)
  "(?i:a)", "(?i:a)b", "a(?i:b)c", "(?-i:a)", "(?i-m:a)", "(?m:^b)",
  "(?s:.)", "(?i:(?-i:a)b)", "(?i:(a))\\1", "(?i:(a)\\1)", "(?i:[a-z])",
  "(?i:\\w)", "(?m:$)", "(?-m:$)", "(?i:a)*", "(?<x>(?i:a))",
  "(?u:a)", "(?-:a)", "(?ii:a)", "(?i-i:a)", "(?:a)",
  "(?<=(?i:ab))c", "(?i:\\p{Lu})",
];

/** Flag sets applied to every pattern; incompatible combinations are skipped. */
export const FLAGSETS = [
  "", "i", "m", "s", "u", "iu", "ms", "im", "su", "gi", "y", "d", "v", "vi", "vg",
];

export const INPUTS = [
  "", "a", "b", "ab", "ba", "abc", "aaa", "aaab", "aab", "xaay", "xy",
  // Long enough to make the "catastrophic" patterns backtrack hard, short
  // enough that they still finish inside the default step budget. The
  // unbounded case is covered by StepLimitTest instead, where diverging from
  // node is the intended behaviour rather than a conformance failure.
  "aaaaaaaaaaaaX",
  "abab", "abcabc", "a1b2c3", "  spaced  ",
  "foo bar", "foo_bar", "Foo BAR", "_", "0", "9", "-",
  // Backslash inputs: the Annex B `\c` fallback matches a literal backslash.
  "a\\ccb", "\\c-z", "x\\cy",
  "line1\nline2", "a\nb", "a\r\nb", "a b",
  "Hello, World!", "café", "ﬀ", "ſ", "K", "Y", "y", "A", "z",
  "😀", "😀😁", "a😀b", "\uD83D", "\uDE00", "\uD83Dx",
  // A lone high surrogate immediately followed by a real pair. This shape found
  // a backreference bug: comparing by code unit let \1 match the leading half of
  // the pair, consuming half a character.
  "c\uD83D😀1ſſ\t0 \uDE00",
  "\uD83D😀", "\uDE00\uD83D", "a\uD83D😀b",
  "αβγ", "ΑΒΓ", "Σ", "σ", "ς", "৪", "١٢٣",
  "$ 99", "42 dollars", "price: $100",
  // emoji sequences, for properties of strings
  "#\uFE0F\u20E3", "\uD83C\uDDEC\uD83C\uDDE7",
  "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDE00\uD83C\uDFFB",
  " ", "", "8", "", "", "ÿ", " 0",
  "k<a>", "p{L}", "{", "}", "]", "a{2 x}", "\\c1", "q", "-",
  "aXa", "tab\there", "a-b-c", "z-a",
];

/**
 * Randomly generated patterns, filtered by the caller to those the oracle
 * accepts. Deliberately small and dense so they backtrack rather than fail fast.
 */
export function fuzzPatterns(count, seed = 0x5eed) {
  const rand = rng(seed);
  const pick = (arr) => arr[Math.floor(rand() * arr.length)];

  const ATOM = ["a", "b", "c", ".", "\\d", "\\w", "\\s", "[ab]", "[^a]", "[a-c]", "x", "\\b"];
  const QUANT = ["", "", "", "*", "+", "?", "*?", "+?", "??", "{2}", "{1,2}", "{0,2}", "{2,}"];

  const gen = (depth) => {
    const r = rand();
    if (depth <= 0 || r < 0.45) return pick(ATOM) + pick(QUANT);
    if (r < 0.6) return gen(depth - 1) + gen(depth - 1);
    if (r < 0.72) return "(" + gen(depth - 1) + "|" + gen(depth - 1) + ")" + pick(QUANT);
    if (r < 0.82) return "(" + gen(depth - 1) + ")" + pick(QUANT);
    if (r < 0.88) return "(?:" + gen(depth - 1) + ")" + pick(QUANT);
    if (r < 0.92) return "(?=" + gen(depth - 1) + ")";
    if (r < 0.95) return "(?!" + gen(depth - 1) + ")";
    if (r < 0.98) return "(?<=" + gen(depth - 1) + ")";
    return "(?<!" + gen(depth - 1) + ")";
  };

  const out = new Set();
  let guard = 0;
  while (out.size < count && guard++ < count * 40) {
    out.add(gen(3));
  }
  return [...out];
}

export const FUZZ_INPUTS = [
  "", "a", "b", "c", "ab", "ba", "abc", "cba", "aab", "abb", "aaa", "bbb",
  "abcabc", "xabc", "abcx", "a b", "a1b", "  ", "aaaa", "abab", "bcbc",
];
