package io.github.mgilbir.ecma262

/**
 * Bytecode opcodes.
 *
 * Instructions live in parallel [IntArray]s rather than an array of objects:
 * the inner loop then touches three contiguous int arrays instead of chasing a
 * pointer per step, which matters on both the JVM and JS.
 */
internal object Op {
    const val MATCH: Int = 0

    /** `a` = code point. */
    const val CHAR: Int = 1

    /** `a`, `b` = the two code points of a case pair; the common `/i` literal. */
    const val CHAR2: Int = 2

    /** `a` = index into [Program.sets]. */
    const val SET: Int = 3

    /** `.` — any code point except a line terminator. */
    const val ANY: Int = 4

    /** `.` under the `s` flag — any code point at all. */
    const val ANY_ALL: Int = 5

    /** `a` = 1 when multiline applies at this point, 0 otherwise. */
    const val ASSERT_START: Int = 6
    const val ASSERT_END: Int = 7

    /** `a` = index into [Program.sets] of the word-character set to use. */
    const val WORD_BOUNDARY: Int = 8
    const val NON_WORD_BOUNDARY: Int = 9

    /** `a` = group index. */
    const val SAVE_START: Int = 10
    const val SAVE_END: Int = 11

    /** `a`..`b` = inclusive group range to clear, for per-iteration capture reset. */
    const val RESET_GROUPS: Int = 12

    /** `a` = target pc. */
    const val JMP: Int = 13

    /** `a` = preferred branch, `b` = fallback branch pushed for backtracking. */
    const val SPLIT: Int = 14

    /** `a` = group index, `b` = index into [Program.backrefAlternatives] or -1. */
    const val BACKREF: Int = 15

    /**
     * As [BACKREF], but comparing case-insensitively.
     *
     * A separate opcode because a modifier group can make one backreference
     * case-insensitive while another in the same pattern is not.
     */
    const val BACKREF_IGNORE_CASE: Int = 22

    /** `a` = pc of the assertion body, which ends in its own [MATCH]. */
    const val LOOKAHEAD: Int = 16
    const val NEG_LOOKAHEAD: Int = 17
    const val LOOKBEHIND: Int = 18
    const val NEG_LOOKBEHIND: Int = 19

    /** `a` = mark slot; records the position at the start of a loop iteration. */
    const val SET_MARK: Int = 20

    /**
     * `a` = mark slot. Fails when the iteration consumed nothing, implementing
     * ECMA-262 RepeatMatcher's rule that an optional repetition matching the
     * empty string terminates the loop. Emitted only for iterations beyond the
     * mandatory minimum.
     */
    const val EMPTY_CHECK: Int = 21

    fun name(op: Int): String = when (op) {
        MATCH -> "match"
        CHAR -> "char"
        CHAR2 -> "char2"
        SET -> "set"
        ANY -> "any"
        ANY_ALL -> "any-all"
        ASSERT_START -> "assert-start"
        ASSERT_END -> "assert-end"
        WORD_BOUNDARY -> "word-boundary"
        NON_WORD_BOUNDARY -> "non-word-boundary"
        SAVE_START -> "save-start"
        SAVE_END -> "save-end"
        RESET_GROUPS -> "reset-groups"
        JMP -> "jmp"
        SPLIT -> "split"
        BACKREF -> "backref"
        BACKREF_IGNORE_CASE -> "backref-i"
        LOOKAHEAD -> "lookahead"
        NEG_LOOKAHEAD -> "neg-lookahead"
        LOOKBEHIND -> "lookbehind"
        NEG_LOOKBEHIND -> "neg-lookbehind"
        SET_MARK -> "set-mark"
        EMPTY_CHECK -> "empty-check"
        else -> "op$op"
    }
}

/** A compiled pattern, ready to be executed by [Vm]. */
internal class Program(
    val op: IntArray,
    val a: IntArray,
    val b: IntArray,
    val sets: Array<CharSet>,
    /** Extra group indices for `\k<name>` when duplicate names share a name. */
    val backrefAlternatives: Array<IntArray>,
    val numGroups: Int,
    val numMarks: Int,
    val flags: Flags,
    val groupNames: Map<String, List<Int>>,
    /**
     * Characters that can begin a match, or null when that cannot be determined
     * (the pattern may match empty, starts with a backreference, or starts with
     * `.`).
     *
     * Used purely as a prefilter: a start position whose character is outside
     * this set cannot match, so the matcher can skip it without setting up an
     * attempt. It is always a *superset* of the truth, so it can never turn a
     * match into a non-match.
     */
    val firstChars: CharSet?,
    /**
     * The single BMP code point every match must start with, or -1.
     *
     * Lets the scan use `String.indexOf`, which is an intrinsic on the JVM and a
     * native call on JS, instead of stepping position by position.
     */
    val firstCharSingle: Int,
    /**
     * True when every alternative begins with `^` and `m` is not set, so a match
     * can only start at index 0 and scanning is pointless.
     */
    val anchoredAtStart: Boolean,
) {
    val size: Int get() = op.size

    /** Human-readable disassembly, for debugging and tests. */
    fun disassemble(): String = buildString {
        for (i in op.indices) {
            append(i).append(": ").append(Op.name(op[i]))
            when (op[i]) {
                Op.CHAR, Op.SET, Op.SAVE_START, Op.SAVE_END, Op.JMP,
                Op.SET_MARK, Op.EMPTY_CHECK, Op.LOOKAHEAD, Op.NEG_LOOKAHEAD,
                Op.LOOKBEHIND, Op.NEG_LOOKBEHIND, Op.ASSERT_START, Op.ASSERT_END,
                Op.WORD_BOUNDARY, Op.NON_WORD_BOUNDARY,
                -> append(' ').append(a[i])
                Op.CHAR2, Op.SPLIT, Op.RESET_GROUPS, Op.BACKREF, Op.BACKREF_IGNORE_CASE ->
                    append(' ').append(a[i]).append(' ').append(b[i])
            }
            append('\n')
        }
    }
}
