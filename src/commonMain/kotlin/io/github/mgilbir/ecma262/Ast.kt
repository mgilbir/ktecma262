package io.github.mgilbir.ecma262

/**
 * AST for an ECMA-262 pattern.
 *
 * Code points are stored as [Int] throughout. In Unicode mode (`u`/`v`) an atom
 * may be any code point up to U+10FFFF; outside it, atoms are single UTF-16 code
 * units and so never exceed U+FFFF — including unpaired surrogates, which the
 * spec requires to be matchable individually.
 */
internal sealed interface Expr

/** A complete parsed pattern. */
internal class Pattern(
    val body: Expr,
    /**
     * Total capturing groups, numbered by source order of the opening paren at
     * parse time. This is the single source of truth for group numbering;
     * quantifiers never create additional groups.
     */
    val numGroups: Int,
    val flags: Flags,
    /** Group name to every index carrying it (ES2022 allows duplicates across alternatives). */
    val groupNames: Map<String, List<Int>>,
)

/** Alternatives: `a|b|c`. */
internal class Disjunction(val alternatives: List<Expr>) : Expr

/** A concatenation. An empty sequence matches the empty string. */
internal class Sequence(val elements: List<Expr>) : Expr

/** A single literal code point. */
internal class Literal(val codePoint: Int) : Expr

/** `.` — any code point, excluding line terminators unless the `s` flag is set. */
internal data object Dot : Expr

/** `[...]` or `[^...]`. */
internal class CharClass(val negated: Boolean, val atoms: List<ClassAtom>) : Expr

/**
 * `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`.
 *
 * [max] is -1 for unbounded.
 */
internal class Quantifier(
    val min: Int,
    val max: Int,
    val greedy: Boolean,
    val body: Expr,
) : Expr

/**
 * A capturing group, `(...)` or `(?<name>...)`.
 *
 * Named and unnamed groups share one numbering space, so they are one node type
 * distinguished by [name] rather than two.
 */
internal class Group(val index: Int, val name: String?, val body: Expr) : Expr

/** `(?:...)`. */
internal class NonCapturingGroup(val body: Expr) : Expr

/**
 * A modifier group: `(?i:...)`, `(?-i:...)`, `(?i-ms:...)`.
 *
 * Adds and removes `i`, `m` and `s` for the enclosed subexpression only. The
 * Unicode, global, sticky and indices flags cannot be modified, because they
 * change how the input is read or how a match is reported rather than how a
 * subexpression matches.
 */
internal class ModifierGroup(
    val add: Flags,
    val remove: Flags,
    val body: Expr,
) : Expr

/**
 * `(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)`.
 *
 * The four spellings differ only in direction and polarity, so they share a node.
 */
internal class Lookaround(
    val body: Expr,
    /** True for lookbehind, which the compiler emits reversed for right-to-left matching. */
    val behind: Boolean,
    val negated: Boolean,
) : Expr

/** `^`, `$`, `\b`, `\B`. */
internal class Anchor(val kind: AnchorKind) : Expr

internal enum class AnchorKind { START, END, WORD_BOUNDARY, NON_WORD_BOUNDARY }

/** `\d`, `\D`, `\w`, `\W`, `\s`, `\S`, `\p{...}`, `\P{...}` used as a standalone atom. */
internal class EscapeClass(
    val kind: EscapeKind,
    val negated: Boolean,
    /** Property name for [EscapeKind.UNICODE_PROPERTY], otherwise null. */
    val property: String? = null,
) : Expr

internal enum class EscapeKind { DIGIT, WORD, SPACE, UNICODE_PROPERTY }

/**
 * `\1`..`\9` or `\k<name>`.
 *
 * Resolution needs the whole pattern (a backreference may precede its group), so
 * the parser fills these in during a pass after the body is parsed. The fields
 * are mutable only for that reason; the node is effectively immutable once
 * [Parser.parse] returns.
 */
internal class Backreference(
    /** 1-based group index; 0 until resolved. */
    var index: Int = 0,
    /** Group name for `\k<name>`, else null. */
    var name: String? = null,
    /** Additional indices when [name] is carried by several groups (ES2022 duplicates). */
    var altIndices: List<Int> = emptyList(),
    /**
     * Set when a numeric escape turned out not to name a real group and Annex B
     * re-reads it as literal characters — a legacy octal escape and/or literal
     * digits (`\5` is U+0005, `\8` is "8", `\58` is U+0005 then "8"). The
     * compiler emits those literals instead of a backreference.
     */
    var fallback: IntArray? = null,
) : Expr

/**
 * A `v`-mode character class: `ClassSetExpression`.
 *
 * The `v` grammar is not `u`'s with extras — it is a set algebra. A class may
 * nest (`[[a][b]]`), intersect (`[a&&b]`), subtract (`[a--b]`), and hold
 * multi-character *strings* via `\q{…}`, which is why it cannot share the
 * flat [CharClass] representation.
 */
internal class ClassSetExpr(val body: ClassSetNode, val negated: Boolean) : Expr

internal sealed interface ClassSetNode

internal class ClassSetLiteral(val codePoint: Int) : ClassSetNode

internal class ClassSetRange(val start: Int, val end: Int) : ClassSetNode

internal class ClassSetEscape(
    val kind: EscapeKind,
    val negated: Boolean,
    val property: String? = null,
) : ClassSetNode

/** `\q{ab|cd}` — a set of literal strings, each a code point sequence. */
internal class ClassSetStrings(val strings: List<IntArray>) : ClassSetNode

/** A nested `[...]` or `[^...]`. */
internal class ClassSetNested(val body: ClassSetNode, val negated: Boolean) : ClassSetNode

internal class ClassSetOperation(val kind: SetOpKind, val items: List<ClassSetNode>) : ClassSetNode

internal enum class SetOpKind { UNION, INTERSECTION, DIFFERENCE }

/**
 * A character class already resolved to concrete code point ranges.
 *
 * Produced when lowering a [ClassSetExpr], whose set operations must be
 * evaluated before matching.
 */
internal class ResolvedCharClass(val set: CharSet) : Expr

/** A member of a character class. */
internal sealed interface ClassAtom

internal class ClassLiteral(val codePoint: Int) : ClassAtom

/** An inclusive range such as `a-z`. */
internal class ClassRange(val start: Int, val end: Int) : ClassAtom

internal class ClassEscape(
    val kind: EscapeKind,
    val negated: Boolean,
    val property: String? = null,
) : ClassAtom
