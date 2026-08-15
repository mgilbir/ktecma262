package io.github.mgilbir.ecma262

/**
 * The result of a successful match.
 *
 * Mirrors the array JavaScript's `RegExp.prototype.exec` returns: element 0 is
 * the whole match and element *n* is capture group *n*, with null for a group
 * that did not participate.
 */
public class MatchResult internal constructor(
    /** The subject string the match was found in. */
    public val input: String,
    /** Start of the whole match, as a UTF-16 code unit offset. */
    public val index: Int,
    private val slots: IntArray,
    private val names: Map<String, List<Int>>,
) {
    /** Number of entries, including entry 0 for the whole match. */
    public val size: Int get() = slots.size / 2

    /** Capture [group], or null if it did not participate. 0 is the whole match. */
    public operator fun get(group: Int): String? {
        if (group < 0 || group >= size) return null
        val s = slots[group * 2]
        val e = slots[group * 2 + 1]
        return if (s < 0 || e < 0) null else input.substring(s, e)
    }

    /** Capture by group name, or null if absent or non-participating. */
    public operator fun get(name: String): String? {
        val idx = indexOfGroup(name) ?: return null
        return get(idx)
    }

    /** The whole match. */
    public val value: String get() = get(0) ?: ""

    /** `[start, end)` for [group] as code unit offsets, or null if unmatched. */
    public fun range(group: Int): IntRange? {
        if (group < 0 || group >= size) return null
        val s = slots[group * 2]
        val e = slots[group * 2 + 1]
        return if (s < 0 || e < 0) null else s until e
    }

    /** `[start, end)` for a named group, or null if absent or unmatched. */
    public fun range(name: String): IntRange? = indexOfGroup(name)?.let { range(it) }

    /** All captures in order, whole match first, with nulls for non-participants. */
    public fun groupValues(): List<String?> = (0 until size).map { get(it) }

    /**
     * Named captures, as JavaScript's `groups` object.
     *
     * Empty when the pattern declares no named groups.
     */
    public val groups: Map<String, String?>
        get() = names.keys.associateWith { get(it) }

    /**
     * Which group number a name refers to.
     *
     * ES2022 allows one name on several groups across different alternatives;
     * this returns the one that participated, or the first if none did.
     */
    private fun indexOfGroup(name: String): Int? {
        val candidates = names[name] ?: return null
        for (i in candidates) {
            if (slots[i * 2] >= 0 && slots[i * 2 + 1] >= 0) return i
        }
        return candidates.firstOrNull()
    }

    override fun toString(): String = "MatchResult(index=$index, value=${get(0)})"
}

/**
 * A compiled ECMA-262 regular expression.
 *
 * The API follows JavaScript's `RegExp`: [exec] and [test] honour [lastIndex]
 * when the `g` or `y` flag is set, and all offsets are UTF-16 code unit indices
 * into the subject string, so they line up with `String.length` exactly as in
 * JavaScript.
 *
 * An instance without `g` or `y` is immutable and safe to share between threads.
 * With either flag it carries the mutable [lastIndex] cursor and must not be
 * shared without synchronisation.
 */
public class RegExp private constructor(
    /** The pattern text, as supplied. */
    public val source: String,
    /** The flag set. */
    public val flags: Flags,
    private val program: Program,
) {
    /**
     * Where the next [exec] starts when `g` or `y` is set.
     *
     * Advanced past each match and reset to 0 when a match fails, mirroring
     * JavaScript.
     */
    public var lastIndex: Int = 0

    /**
     * Instruction budget for one operation.
     *
     * The engine is a backtracker, so a hostile pattern and input can otherwise
     * take exponential time. Exceeding this raises [RegExpStepLimitError] rather
     * than reporting a non-match, so a caller can tell the two apart. Memory is
     * bounded by the same budget, since each step pushes at most one backtrack
     * entry.
     *
     * The budget covers a whole operation, not each match attempt: a single
     * [exec] scan, or an entire [findAll], [replace] or [split]. Per-match
     * budgets would let total cost scale with the number of matches, which an
     * attacker controls through the input length.
     */
    public var maxSteps: Long = Vm.DEFAULT_MAX_STEPS

    public val global: Boolean get() = flags.global
    public val ignoreCase: Boolean get() = flags.ignoreCase
    public val multiline: Boolean get() = flags.multiline
    public val dotAll: Boolean get() = flags.dotAll
    public val unicode: Boolean get() = flags.unicode
    public val unicodeSets: Boolean get() = flags.unicodeSets
    public val sticky: Boolean get() = flags.sticky
    public val hasIndices: Boolean get() = flags.hasIndices

    /** Group names declared by the pattern, in source order. */
    public val groupNames: Set<String> get() = program.groupNames.keys

    /** Number of capturing groups, not counting the whole match. */
    public val groupCount: Int get() = program.numGroups

    /**
     * Finds the next match.
     *
     * With `g` or `y` the search starts at [lastIndex], which is then advanced
     * past the match or reset to 0 on failure. Without them the search always
     * starts at the beginning and [lastIndex] is untouched.
     *
     * @throws RegExpStepLimitError if the match exceeds [maxSteps].
     */
    public fun exec(input: String): MatchResult? {
        val stateful = flags.global || flags.sticky
        val start = if (stateful) lastIndex else 0

        if (start > input.length || start < 0) {
            if (stateful) lastIndex = 0
            return null
        }

        val slots = Vm(program).exec(input, start, sticky = flags.sticky, maxStepBudget = maxSteps)
        if (slots == null) {
            if (stateful) lastIndex = 0
            return null
        }

        if (stateful) lastIndex = slots[1]
        return MatchResult(input, slots[0], slots, program.groupNames)
    }

    /**
     * Whether the pattern matches, honouring and updating [lastIndex] under
     * `g`/`y` exactly as JavaScript's `test` does.
     */
    public fun test(input: String): Boolean = exec(input) != null

    /**
     * Every non-overlapping match, ignoring and leaving [lastIndex] untouched.
     *
     * An empty match advances by one position (a whole code point under `u`/`v`)
     * so the iteration always terminates.
     */
    public fun findAll(input: String): List<MatchResult> {
        val out = mutableListOf<MatchResult>()
        val vm = Vm(program)
        var pos = 0
        var fresh = true
        while (pos <= input.length) {
            val slots = vm.exec(input, pos, flags.sticky, maxSteps, fresh) ?: break
            fresh = false
            out += MatchResult(input, slots[0], slots, program.groupNames)
            pos = if (slots[1] == slots[0]) advance(input, slots[1]) else slots[1]
        }
        return out
    }

    private fun advance(input: String, pos: Int): Int {
        if (flags.isUnicodeMode && pos + 1 < input.length &&
            input[pos].isHighSurrogate() && input[pos + 1].isLowSurrogate()
        ) {
            return pos + 2
        }
        return pos + 1
    }

    /**
     * Replaces matches using [transform].
     *
     * Replaces every match when `g` is set, otherwise only the first — the same
     * rule JavaScript's `String.prototype.replace` applies.
     */
    public fun replace(input: String, transform: (MatchResult) -> String): String {
        val sb = StringBuilder()
        var last = 0
        val vm = Vm(program)
        var pos = 0
        var fresh = true
        while (pos <= input.length) {
            val slots = vm.exec(input, pos, flags.sticky, maxSteps, fresh) ?: break
            fresh = false
            val m = MatchResult(input, slots[0], slots, program.groupNames)
            sb.append(input, last, slots[0])
            sb.append(transform(m))
            last = slots[1]
            pos = if (slots[1] == slots[0]) advance(input, slots[1]) else slots[1]
            if (!flags.global) break
        }
        sb.append(input, last, input.length)
        return sb.toString()
    }

    /**
     * Replaces matches, interpreting `$` in [replacement] as ECMA-262 does:
     * `$&` whole match, `$1`…`$99` group, `$<name>` named group, `` $` `` and
     * `$'` the text before and after the match, `$$` a literal `$`. An invalid
     * reference such as `$0` is emitted literally.
     */
    public fun replace(input: String, replacement: String): String =
        replace(input) { m -> expandReplacement(replacement, m) }

    private fun expandReplacement(replacement: String, m: MatchResult): String {
        if ('$' !in replacement) return replacement

        val sb = StringBuilder(replacement.length)
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c != '$' || i + 1 >= replacement.length) {
                sb.append(c)
                i++
                continue
            }
            when (val next = replacement[i + 1]) {
                '$' -> { sb.append('$'); i += 2 }
                '&' -> { sb.append(m.value); i += 2 }
                '`' -> { sb.append(m.input, 0, m.index); i += 2 }
                '\'' -> {
                    val end = m.index + m.value.length
                    sb.append(m.input, end, m.input.length)
                    i += 2
                }
                '<' -> {
                    val close = replacement.indexOf('>', i + 2)
                    // With no named groups, or no closing '>', "$<" is literal.
                    if (close < 0 || program.groupNames.isEmpty()) {
                        sb.append(c)
                        i++
                    } else {
                        val name = replacement.substring(i + 2, close)
                        sb.append(if (name in program.groupNames) m[name] ?: "" else "")
                        i = close + 1
                    }
                }
                in '0'..'9' -> {
                    // Prefer a two-digit reference when it names a real group.
                    var num = next - '0'
                    var width = 1
                    if (i + 2 < replacement.length) {
                        val d2 = replacement[i + 2]
                        if (d2 in '0'..'9') {
                            val two = num * 10 + (d2 - '0')
                            if (two in 1..m.size - 1) {
                                num = two
                                width = 2
                            }
                        }
                    }
                    if (num in 1..m.size - 1) {
                        sb.append(m[num] ?: "")
                        i += 1 + width
                    } else {
                        sb.append(c) // "$0" and out-of-range refs stay literal
                        i++
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Splits [input] around matches, as `String.prototype.split` with a RegExp.
     *
     * Capture groups from the separator are interleaved into the result, and
     * [limit] caps the number of returned pieces (negative means no limit).
     */
    public fun split(input: String, limit: Int = -1): List<String?> {
        if (limit == 0) return emptyList()
        val out = mutableListOf<String?>()

        // JavaScript special case: splitting the empty string yields one empty
        // piece unless the pattern matches it.
        if (input.isEmpty()) {
            val slots = Vm(program).exec(input, 0, sticky = flags.sticky, maxStepBudget = maxSteps)
            return if (slots != null) emptyList() else listOf("")
        }

        val vm = Vm(program)
        var last = 0
        var pos = 0
        var fresh = true
        while (pos < input.length) {
            val slots = vm.exec(input, pos, flags.sticky, maxSteps, fresh) ?: break
            fresh = false
            val mStart = slots[0]
            val mEnd = slots[1]
            // A separator match that is empty, or sits at the very end, does not
            // produce a split point.
            if (mEnd == last && mStart == mEnd) {
                pos = advance(input, pos)
                continue
            }
            if (mStart >= input.length) break

            out += input.substring(last, mStart)
            if (limit in 1..out.size) return out.take(limit)

            for (g in 1..program.numGroups) {
                val s = slots[g * 2]
                val e = slots[g * 2 + 1]
                out += if (s < 0 || e < 0) null else input.substring(s, e)
                if (limit in 1..out.size) return out.take(limit)
            }

            last = mEnd
            pos = if (mEnd == mStart) advance(input, mEnd) else mEnd
        }

        out += input.substring(last)
        return if (limit >= 0) out.take(limit) else out
    }

    /** The index of the first match, or -1. Does not use [lastIndex]. */
    public fun search(input: String): Int {
        val slots = Vm(program).exec(input, 0, sticky = flags.sticky, maxStepBudget = maxSteps)
        return slots?.get(0) ?: -1
    }

    /** Disassembled bytecode, for debugging. */
    internal fun disassemble(): String = program.disassemble()

    override fun toString(): String = "/$source/$flags"

    public companion object {
        /**
         * Compiles [source] with [flags].
         *
         * @throws RegExpSyntaxError if the pattern or the flag string is invalid.
         */
        public fun compile(source: String, flags: String = ""): RegExp =
            compile(source, Flags.parse(flags))

        /**
         * Compiles [source] with an already-parsed flag set.
         *
         * @throws RegExpSyntaxError if the pattern is invalid.
         */
        public fun compile(source: String, flags: Flags, syntax: Syntax = Syntax.ANNEX_B): RegExp {
            val pattern = Parser(source, flags, syntax).parse()
            val program = Compiler(pattern).compile()
            return RegExp(source, flags, program)
        }

        /**
         * Escapes [text] so it matches itself when used inside a pattern.
         *
         * Implements ECMA-262 `RegExp.escape`. Beyond the obvious metacharacters
         * this also escapes punctuation that is harmless alone but not when
         * pasted next to other syntax, and escapes a leading digit or letter as
         * `\xNN` so the result cannot merge with a preceding construct — for
         * example so that `"\\" + escape("d")` is not `\d`.
         *
         * ```kotlin
         * val re = RegExp.compile("^" + RegExp.escape(userInput) + "$")
         * ```
         */
        public fun escape(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            var first = true
            while (i < text.length) {
                val high = text[i]
                val cp: Int
                val width: Int
                if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    cp = 0x10000 + ((high.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                    width = 2
                } else {
                    cp = high.code
                    width = 1
                }

                if (first && (cp in '0'.code..'9'.code || cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code)) {
                    // A leading identifier character is hex-escaped so the result
                    // cannot combine with whatever precedes it.
                    sb.append("\\x").append(cp.toString(16).padStart(2, '0'))
                } else {
                    appendEscaped(sb, cp, text, i, width)
                }
                first = false
                i += width
            }
            return sb.toString()
        }

        private fun appendEscaped(sb: StringBuilder, cp: Int, text: String, at: Int, width: Int) {
            when {
                cp < 0x80 && SYNTAX_CHARACTERS.indexOf(cp.toChar()) >= 0 -> {
                    sb.append('\\').append(cp.toChar())
                }
                cp == 0x09 -> sb.append("\\t")
                cp == 0x0A -> sb.append("\\n")
                cp == 0x0B -> sb.append("\\v")
                cp == 0x0C -> sb.append("\\f")
                cp == 0x0D -> sb.append("\\r")
                needsHexEscape(cp) -> {
                    if (cp <= 0xFF) {
                        sb.append("\\x").append(cp.toString(16).padStart(2, '0'))
                    } else {
                        // Escape each code unit, so an astral character becomes a
                        // pair of \uXXXX escapes.
                        for (k in 0 until width) {
                            sb.append("\\u").append(text[at + k].code.toString(16).padStart(4, '0'))
                        }
                    }
                }
                else -> {
                    for (k in 0 until width) sb.append(text[at + k])
                }
            }
        }

        /** `SyntaxCharacter` plus `/`. */
        private const val SYNTAX_CHARACTERS = "^\$\\.*+?()[]{}|/"

        /**
         * Punctuation, whitespace and lone surrogates that are escaped as `\xNN`
         * or `\uXXXX`: harmless on their own, but not when concatenated next to
         * other syntax.
         */
        private fun needsHexEscape(cp: Int): Boolean {
            if (cp < 0x80 && OTHER_PUNCTUATORS.indexOf(cp.toChar()) >= 0) return true
            if (cp in 0xD800..0xDFFF) return true // lone surrogate
            return isWhiteSpaceOrLineTerminator(cp)
        }

        private const val OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\""

        private fun isWhiteSpaceOrLineTerminator(cp: Int): Boolean = when (cp) {
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029,
            0x202F, 0x205F, 0x3000, 0xFEFF,
            -> true
            else -> cp in 0x2000..0x200A
        }

        /** Compiles [source], returning null instead of throwing on a bad pattern. */
        public fun compileOrNull(source: String, flags: String = ""): RegExp? =
            try {
                compile(source, flags)
            } catch (_: RegExpSyntaxError) {
                null
            }
    }
}
