package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.unicode.Unicode

/**
 * The backtracking matcher.
 *
 * Backtracking is driven by an explicit stack rather than recursion. That is not
 * only faster — it is what keeps a pattern like `(a|b)*` on a long input from
 * overflowing the call stack, since recursion depth would otherwise grow with
 * the input. The only recursion left is for lookarounds, whose depth is bounded
 * by the pattern's nesting limit.
 *
 * Mutating state (captures and loop marks) is written in place and recorded in
 * an undo log, so a backtrack restores it by rewinding the log instead of
 * copying the capture array at every step.
 *
 * All positions are UTF-16 code unit offsets into [input]. Under `u`/`v` the
 * matcher consumes whole code points; otherwise it consumes single code units,
 * which is what makes `/./` match half of a surrogate pair exactly as JavaScript
 * does.
 *
 * An instance is stateful and single-threaded; [RegExp] creates one per match.
 */
internal class Vm(private val program: Program) {

    private val flags = program.flags

    /**
     * Whether the input is read as code points.
     *
     * Unlike `i`, `m` and `s`, the Unicode flags cannot be changed by a modifier
     * group, so this is fixed for the whole program. The others arrive as
     * instruction operands instead.
     */
    private val unicodeMode = flags.isUnicodeMode

    private var input: String = ""
    private var inputLength = 0

    /** Captures occupy the first [groupSlots] entries; loop marks follow. */
    private val groupSlots = (program.numGroups + 1) * 2
    private val state = IntArray(groupSlots + program.numMarks)

    // Both of these grow on demand. They start small because a Vm is created per
    // exec() call — which is what keeps a RegExp itself immutable and shareable —
    // so their initial size is pure per-call allocation cost, and the vast
    // majority of matches never need more than a few entries.

    /** Interleaved (slot, previousValue) pairs. */
    private var undo = IntArray(32)
    private var undoTop = 0

    /** Interleaved (pc, pos, undoTop) triples. */
    private var stack = IntArray(3 * 16)
    private var stackTop = 0

    private var steps = 0L
    private var maxSteps = DEFAULT_MAX_STEPS

    /** Scratch for the code point reader, to avoid allocating a pair per read. */
    private var readCp = 0
    private var readNext = 0

    /**
     * Finds the leftmost match at or after [startIndex].
     *
     * Returns the capture array — `[start0, end0, start1, end1, …]` with -1 for
     * groups that did not participate — or null when there is no match.
     *
     * @throws RegExpStepLimitError if the match exceeds [maxStepBudget]. The
     * budget covers the entire scan, not each start position, so the worst case
     * stays O(budget) rather than O(length x budget).
     */
    fun exec(
        input: String,
        startIndex: Int,
        sticky: Boolean,
        maxStepBudget: Long = DEFAULT_MAX_STEPS,
        /**
         * Whether to start a fresh budget.
         *
         * Repeated operations — `findAll`, `replace`, `split` — pass false after
         * their first call so the budget covers the whole operation. Resetting
         * per match would make the total cost scale with the number of matches,
         * which an attacker controls through the input length.
         */
        freshBudget: Boolean = true,
    ): IntArray? {
        this.input = input
        this.inputLength = input.length
        this.maxSteps = maxStepBudget
        if (freshBudget) this.steps = 0

        if (startIndex > inputLength || startIndex < 0) return null

        var pos = startIndex

        // Every alternative starts with `^` and `m` is off, so only index 0 can
        // match; scanning the rest of the input would be wasted work.
        if (program.anchoredAtStart && pos > 0) return null

        val prefilter = if (sticky) null else program.firstChars

        while (true) {
            // Skip start positions whose character cannot begin a match. This is
            // only ever a superset of the real candidates, so it cannot hide a
            // match — it just avoids setting up an attempt that must fail.
            if (prefilter != null) {
                pos = skipToCandidate(pos, prefilter)
                if (pos < 0) return null
            }

            state.fill(-1)
            undoTop = 0
            stackTop = 0

            if (execute(0, pos, backward = false) >= 0) {
                return state.copyOf(groupSlots)
            }
            if (sticky || pos >= inputLength || program.anchoredAtStart) return null
            pos = advanceIndex(pos)
        }
    }

    /** First index at or after [from] whose character is in [set], or -1. */
    private fun skipToCandidate(from: Int, set: CharSet): Int {
        // A single required BMP character is the common case (any literal-prefixed
        // pattern), and indexOf beats stepping one position at a time. A plain BMP
        // character is never the trailing half of a surrogate pair, so the index it
        // finds is always a valid start.
        val single = program.firstCharSingle
        if (single >= 0) return input.indexOf(single.toChar(), from)

        var p = from
        while (p < inputLength) {
            if (set.matches(codePointAt(p))) return p
            p = advanceIndex(p)
        }
        return -1
    }

    /**
     * Advances a scan position by one unit of matching: a whole code point under
     * `u`/`v`, otherwise a single code unit. This is why a match can never start
     * in the middle of a surrogate pair in Unicode mode.
     */
    private fun advanceIndex(pos: Int): Int {
        if (unicodeMode && pos + 1 < inputLength &&
            input[pos].isHighSurrogate() && input[pos + 1].isLowSurrogate()
        ) {
            return pos + 2
        }
        return pos + 1
    }

    // ------------------------------------------------------------------ reading

    /** Reads the code point at [pos] (or ending at [pos] when [backward]). */
    private fun read(pos: Int, backward: Boolean): Boolean {
        if (backward) {
            if (pos <= 0) return false
            val c = input[pos - 1]
            if (unicodeMode && c.isLowSurrogate() && pos - 2 >= 0 && input[pos - 2].isHighSurrogate()) {
                readCp = combine(input[pos - 2], c)
                readNext = pos - 2
            } else {
                readCp = c.code
                readNext = pos - 1
            }
            return true
        }
        if (pos >= inputLength) return false
        val c = input[pos]
        if (unicodeMode && c.isHighSurrogate() && pos + 1 < inputLength && input[pos + 1].isLowSurrogate()) {
            readCp = combine(c, input[pos + 1])
            readNext = pos + 2
        } else {
            readCp = c.code
            readNext = pos + 1
        }
        return true
    }

    private fun combine(high: Char, low: Char): Int =
        0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)

    /** The code point ending just before [pos], or -1 at the start of input. */
    private fun codePointBefore(pos: Int): Int {
        if (pos <= 0) return -1
        val c = input[pos - 1]
        if (unicodeMode && c.isLowSurrogate() && pos - 2 >= 0 && input[pos - 2].isHighSurrogate()) {
            return combine(input[pos - 2], c)
        }
        return c.code
    }

    /** The code point starting at [pos], or -1 at the end of input. */
    private fun codePointAt(pos: Int): Int {
        if (pos >= inputLength) return -1
        val c = input[pos]
        if (unicodeMode && c.isHighSurrogate() && pos + 1 < inputLength && input[pos + 1].isLowSurrogate()) {
            return combine(c, input[pos + 1])
        }
        return c.code
    }

    // -------------------------------------------------------------- state & undo

    private fun setState(slot: Int, value: Int) {
        val old = state[slot]
        if (old == value) return // restoring to an unchanged value would be a no-op
        if (undoTop + 2 > undo.size) undo = undo.copyOf(undo.size * 2)
        undo[undoTop] = slot
        undo[undoTop + 1] = old
        undoTop += 2
        state[slot] = value
    }

    private fun rewindTo(target: Int) {
        var t = undoTop
        while (t > target) {
            t -= 2
            state[undo[t]] = undo[t + 1]
        }
        undoTop = target
    }

    private fun push(pc: Int, pos: Int) {
        if (stackTop + 3 > stack.size) stack = stack.copyOf(stack.size * 2)
        stack[stackTop] = pc
        stack[stackTop + 1] = pos
        stack[stackTop + 2] = undoTop
        stackTop += 3
    }

    // ---------------------------------------------------------------- execution

    /**
     * Runs the program from [startPc] at [startPos], returning the end position
     * or -1 on failure.
     *
     * On failure every state change made below [startPc]'s entry point is undone,
     * so a caller can treat a failed attempt as if it never ran. On success the
     * backtrack stack is truncated to its entry height: an assertion never gets
     * re-entered, which is exactly ECMA-262's atomic treatment of lookarounds.
     */
    private fun execute(startPc: Int, startPos: Int, backward: Boolean): Int {
        val stackBase = stackTop
        val undoBase = undoTop
        var pc = startPc
        var pos = startPos

        val op = program.op
        val argA = program.a
        val argB = program.b

        loop@ while (true) {
            if (++steps > maxSteps) throw RegExpStepLimitError(steps)

            when (op[pc]) {
                Op.MATCH -> {
                    stackTop = stackBase
                    return pos
                }

                Op.CHAR -> {
                    if (read(pos, backward) && readCp == argA[pc]) {
                        pos = readNext
                        pc++
                        continue@loop
                    }
                }

                Op.CHAR2 -> {
                    if (read(pos, backward) && (readCp == argA[pc] || readCp == argB[pc])) {
                        pos = readNext
                        pc++
                        continue@loop
                    }
                }

                Op.SET -> {
                    if (read(pos, backward) && program.sets[argA[pc]].matches(readCp)) {
                        pos = readNext
                        pc++
                        continue@loop
                    }
                }

                Op.ANY -> {
                    if (read(pos, backward) && !isLineTerminator(readCp)) {
                        pos = readNext
                        pc++
                        continue@loop
                    }
                }

                Op.ANY_ALL -> {
                    if (read(pos, backward)) {
                        pos = readNext
                        pc++
                        continue@loop
                    }
                }

                // The operand carries the multiline setting in force here, which a
                // modifier group can change part-way through a pattern.
                Op.ASSERT_START -> {
                    if (pos == 0 || (argA[pc] == 1 && isLineTerminator(codePointBefore(pos)))) {
                        pc++
                        continue@loop
                    }
                }

                Op.ASSERT_END -> {
                    if (pos == inputLength || (argA[pc] == 1 && isLineTerminator(codePointAt(pos)))) {
                        pc++
                        continue@loop
                    }
                }

                // Likewise the word set: `(?i:\b)` under /u uses the case-extended
                // one while a boundary outside the group does not.
                Op.WORD_BOUNDARY -> {
                    val w = program.sets[argA[pc]]
                    if (isWordAt(pos, true, w) != isWordAt(pos, false, w)) {
                        pc++
                        continue@loop
                    }
                }

                Op.NON_WORD_BOUNDARY -> {
                    val w = program.sets[argA[pc]]
                    if (isWordAt(pos, true, w) == isWordAt(pos, false, w)) {
                        pc++
                        continue@loop
                    }
                }

                Op.SAVE_START -> {
                    // Running backward this is reached at the group's right edge,
                    // so it records the end; SAVE_END then records the start. A
                    // stored range is always [start, end].
                    setState(if (backward) argA[pc] * 2 + 1 else argA[pc] * 2, pos)
                    pc++
                    continue@loop
                }

                Op.SAVE_END -> {
                    setState(if (backward) argA[pc] * 2 else argA[pc] * 2 + 1, pos)
                    pc++
                    continue@loop
                }

                Op.RESET_GROUPS -> {
                    for (g in argA[pc]..argB[pc]) {
                        setState(g * 2, -1)
                        setState(g * 2 + 1, -1)
                    }
                    pc++
                    continue@loop
                }

                Op.JMP -> {
                    pc = argA[pc]
                    continue@loop
                }

                Op.SPLIT -> {
                    push(argB[pc], pos)
                    pc = argA[pc]
                    continue@loop
                }

                Op.SET_MARK -> {
                    setState(groupSlots + argA[pc], pos)
                    pc++
                    continue@loop
                }

                Op.EMPTY_CHECK -> {
                    // The iteration consumed nothing, so repeating would loop
                    // forever: ECMA-262 fails the repetition instead.
                    if (pos != state[groupSlots + argA[pc]]) {
                        pc++
                        continue@loop
                    }
                }

                Op.BACKREF, Op.BACKREF_IGNORE_CASE -> {
                    val next = matchBackreference(
                        argA[pc],
                        argB[pc],
                        pos,
                        backward,
                        foldCase = op[pc] == Op.BACKREF_IGNORE_CASE,
                    )
                    if (next >= 0) {
                        pos = next
                        pc++
                        continue@loop
                    }
                }

                Op.LOOKAHEAD, Op.LOOKBEHIND -> {
                    val mark = undoTop
                    val r = execute(argA[pc], pos, backward = op[pc] == Op.LOOKBEHIND)
                    if (r >= 0) {
                        // A positive assertion keeps whatever it captured.
                        pc++
                        continue@loop
                    }
                    rewindTo(mark)
                }

                Op.NEG_LOOKAHEAD, Op.NEG_LOOKBEHIND -> {
                    val mark = undoTop
                    val r = execute(argA[pc], pos, backward = op[pc] == Op.NEG_LOOKBEHIND)
                    // A negative assertion never contributes captures, whether or
                    // not its body matched.
                    rewindTo(mark)
                    if (r < 0) {
                        pc++
                        continue@loop
                    }
                }
            }

            // Fell through: this path failed. Resume the most recent alternative.
            if (stackTop == stackBase) {
                rewindTo(undoBase)
                return -1
            }
            stackTop -= 3
            pc = stack[stackTop]
            pos = stack[stackTop + 1]
            rewindTo(stack[stackTop + 2])
        }
    }

    private fun isWordAt(pos: Int, before: Boolean, wordSet: CharSet): Boolean {
        val cp = if (before) codePointBefore(pos) else codePointAt(pos)
        return cp >= 0 && wordSet.matches(cp)
    }

    /**
     * Matches the text previously captured by group [group], or one of its
     * duplicate-name alternatives.
     *
     * A group that did not participate matches the empty string, per ECMA-262.
     * Returns the new position, or -1 on failure.
     */
    private fun matchBackreference(
        group: Int,
        altIndex: Int,
        pos: Int,
        backward: Boolean,
        foldCase: Boolean,
    ): Int {
        // Defence in depth: the compiler already rejects an out-of-range index,
        // so this only ever guards against a future bug reaching memory.
        if (group < 1 || group > program.numGroups) return pos

        var start = state[group * 2]
        var end = state[group * 2 + 1]

        if ((start < 0 || end < 0) && altIndex >= 0) {
            for (alt in program.backrefAlternatives[altIndex]) {
                if (alt < 1 || alt > program.numGroups) continue
                val s = state[alt * 2]
                val e = state[alt * 2 + 1]
                if (s >= 0 && e >= 0) {
                    start = s
                    end = e
                    break
                }
            }
        }

        if (start < 0 || end < 0) return pos // unset group matches the empty string

        return if (backward) {
            matchBackrefBackward(start, end, pos, foldCase)
        } else {
            matchBackrefForward(start, end, pos, foldCase)
        }
    }

    /**
     * A plain code-unit comparison is only valid outside Unicode mode.
     *
     * Under `u`/`v` the comparison is by code point, and the two differ in a way
     * that matters: a captured lone high surrogate would otherwise compare equal
     * to the leading half of a real surrogate pair in the input, letting a
     * backreference consume half a character. ECMA-262 compares the captured
     * code points one at a time, so `\1` must reject that.
     */
    private fun comparesByCodeUnit(foldCase: Boolean) = !foldCase && !unicodeMode

    private fun matchBackrefForward(start: Int, end: Int, pos: Int, foldCase: Boolean): Int {
        if (comparesByCodeUnit(foldCase)) {
            val len = end - start
            if (pos + len > inputLength) return -1
            for (i in 0 until len) {
                if (input[pos + i] != input[start + i]) return -1
            }
            return pos + len
        }
        // Walk both sides by code point. The lengths can also differ, since a
        // canonical pair need not occupy the same number of code units.
        var ri = start
        var ii = pos
        while (ri < end) {
            if (ii >= inputLength) return -1
            val refCp = codePointAtBounded(ri, end)
            val refNext = advanceFrom(ri, end)
            val inCp = codePointAtBounded(ii, inputLength)
            val inNext = advanceFrom(ii, inputLength)
            if (canonical(refCp, foldCase) != canonical(inCp, foldCase)) return -1
            ri = refNext
            ii = inNext
        }
        return ii
    }

    private fun matchBackrefBackward(start: Int, end: Int, pos: Int, foldCase: Boolean): Int {
        if (comparesByCodeUnit(foldCase)) {
            val len = end - start
            if (pos - len < 0) return -1
            for (i in 0 until len) {
                if (input[pos - len + i] != input[start + i]) return -1
            }
            return pos - len
        }
        var ri = end
        var ii = pos
        while (ri > start) {
            if (ii <= 0) return -1
            val refPrev = retreatFrom(ri, start)
            val inPrev = retreatFrom(ii, 0)
            if (canonical(codePointAtBounded(refPrev, ri), foldCase) !=
                canonical(codePointAtBounded(inPrev, ii), foldCase)
            ) {
                return -1
            }
            ri = refPrev
            ii = inPrev
        }
        return ii
    }

    private fun canonical(cp: Int, foldCase: Boolean): Int =
        Unicode.canonicalize(cp, foldCase, unicodeMode)

    /** Code point at [i], not reading past [limit]. */
    private fun codePointAtBounded(i: Int, limit: Int): Int {
        val c = input[i]
        if (unicodeMode && c.isHighSurrogate() && i + 1 < limit && input[i + 1].isLowSurrogate()) {
            return combine(c, input[i + 1])
        }
        return c.code
    }

    private fun advanceFrom(i: Int, limit: Int): Int {
        val c = input[i]
        return if (unicodeMode && c.isHighSurrogate() && i + 1 < limit && input[i + 1].isLowSurrogate()) i + 2 else i + 1
    }

    private fun retreatFrom(i: Int, floor: Int): Int {
        val c = input[i - 1]
        return if (unicodeMode && c.isLowSurrogate() && i - 2 >= floor && input[i - 2].isHighSurrogate()) i - 2 else i - 1
    }

    private fun isLineTerminator(cp: Int): Boolean =
        cp == 0x0A || cp == 0x0D || cp == 0x2028 || cp == 0x2029

    internal companion object {
        /** Instruction budget for one match attempt; bounds worst-case backtracking. */
        const val DEFAULT_MAX_STEPS: Long = 1_000_000
    }
}
