package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.unicode.Unicode

/**
 * Lowers a parsed [Pattern] to [Program] bytecode.
 *
 * Case-insensitivity is resolved here rather than in the VM: every literal and
 * class is expanded to its case closure at compile time, so the matcher never
 * folds a character. Likewise `\p{…}` is resolved to concrete ranges here.
 */
internal class Compiler(private val pattern: Pattern) {

    private val flags = pattern.flags
    private val unicodeMode = flags.isUnicodeMode

    /**
     * The flags in force at the point currently being compiled.
     *
     * A modifier group — `(?i:…)`, `(?-s:…)` — changes `i`, `m` and `s` for its
     * subexpression only, so case folding, `.`, `^`/`$` and `\b` must all be
     * resolved against this rather than the pattern's own flags. `u`/`v` are not
     * modifiable and stay in [unicodeMode].
     */
    private var effective: Flags = flags

    private val ignoreCase get() = effective.ignoreCase

    private var op = IntArray(64)
    private var argA = IntArray(64)
    private var argB = IntArray(64)
    private var n = 0

    private val sets = mutableListOf<CharSet>()
    private val backrefAlts = mutableListOf<IntArray>()
    private var markCount = 0
    private var depth = 0

    // These depend on the effective ignoreCase, which a modifier group can change
    // part-way through a pattern, so they are cached per setting rather than once.
    private val wordCharsCache = HashMap<Boolean, CharSet>()
    private val digitCharsCache = HashMap<Boolean, CharSet>()
    private val spaceCharsCache = HashMap<Boolean, CharSet>()

    /** `WordCharacters`, case-closed, shared by `\w`, `\W`, `\b` and `\B`. */
    private fun wordChars(ic: Boolean = ignoreCase): CharSet = wordCharsCache.getOrPut(ic) {
        CharSetBuilder()
            .addRange('0'.code, '9'.code)
            .addRange('A'.code, 'Z'.code)
            .addRange('a'.code, 'z'.code)
            .add('_'.code)
            .build(ic, unicodeMode)
    }

    private fun digitChars(): CharSet = digitCharsCache.getOrPut(ignoreCase) {
        CharSetBuilder().addRange('0'.code, '9'.code).build(ignoreCase, unicodeMode)
    }

    private fun spaceChars(): CharSet = spaceCharsCache.getOrPut(ignoreCase) {
        whitespaceBuilder().build(ignoreCase, unicodeMode)
    }

    fun compile(): Program {
        // `v`-mode classes are resolved to plain AST first, so that lookbehind
        // reversal (which rewrites the AST) sees the expanded string
        // alternatives and emits them right-to-left like any other sequence.
        val body = lower(pattern.body)

        emit(Op.SAVE_START, 0)
        compileExpr(body)
        emit(Op.SAVE_END, 0)
        emit(Op.MATCH)

        val analysis = analyseStart()
        val firstChars = analysis.first
        // A lone surrogate or astral start would make index arithmetic subtle for
        // no real gain, so the indexOf fast path is limited to plain BMP.
        val single = firstChars?.singleCodePointOrNull() ?: -1
        val singleBmp = if (single in 0..0xFFFF && single !in 0xD800..0xDFFF) single else -1

        return Program(
            op = op.copyOf(n),
            a = argA.copyOf(n),
            b = argB.copyOf(n),
            sets = sets.toTypedArray(),
            backrefAlternatives = backrefAlts.toTypedArray(),
            numGroups = pattern.numGroups,
            numMarks = markCount,
            flags = flags,
            groupNames = pattern.groupNames,
            firstChars = firstChars,
            firstCharSingle = singleBmp,
            // Multiline is handled per instruction inside analyseStart, so the
            // pattern-level flag need not be consulted again here.
            anchoredAtStart = analysis.second,
        )
    }

    /**
     * Works out what a match can start with.
     *
     * Walks every path from the entry point through zero-width instructions to
     * the first one that consumes input, collecting the characters it accepts
     * and noting whether the path passed a `^` first. The result is a prefilter,
     * so it errs towards "unknown" (null): a wrong *narrowing* would lose
     * matches, while a wrong widening only costs a little speed.
     */
    private fun analyseStart(): Pair<CharSet?, Boolean> {
        val first = CharSetBuilder()
        var allAnchored = true
        var known = true

        // Visited is keyed by pc and whether `^` was already seen on this path,
        // which bounds the walk over loops and alternations.
        val visited = BooleanArray(n * 2)
        val stack = ArrayDeque<Int>()
        stack.addLast(0) // encoded as pc * 2 + (sawStart ? 1 : 0)

        while (stack.isNotEmpty()) {
            val state = stack.removeLast()
            val pc = state ushr 1
            val sawStart = state and 1 == 1
            if (pc >= n || visited[state]) continue
            visited[state] = true

            when (op[pc]) {
                // Zero-width: step over and keep looking.
                Op.SAVE_START, Op.SAVE_END, Op.RESET_GROUPS, Op.SET_MARK,
                Op.EMPTY_CHECK, Op.ASSERT_END, Op.WORD_BOUNDARY, Op.NON_WORD_BOUNDARY,
                Op.LOOKAHEAD, Op.NEG_LOOKAHEAD, Op.LOOKBEHIND, Op.NEG_LOOKBEHIND,
                -> stack.addLast((pc + 1) * 2 + (if (sawStart) 1 else 0))

                // Only a non-multiline `^` anchors a match to index 0. Under
                // multiline — which a modifier group can switch on for this
                // instruction alone — it also matches after a line terminator, so
                // the scan must still consider later positions.
                Op.ASSERT_START ->
                    stack.addLast((pc + 1) * 2 + (if (argA[pc] == 1) (if (sawStart) 1 else 0) else 1))

                Op.JMP -> stack.addLast(argA[pc] * 2 + (if (sawStart) 1 else 0))

                Op.SPLIT -> {
                    val tag = if (sawStart) 1 else 0
                    stack.addLast(argA[pc] * 2 + tag)
                    stack.addLast(argB[pc] * 2 + tag)
                }

                Op.CHAR -> {
                    first.add(argA[pc])
                    if (!sawStart) allAnchored = false
                }
                Op.CHAR2 -> {
                    first.add(argA[pc])
                    first.add(argB[pc])
                    if (!sawStart) allAnchored = false
                }
                Op.SET -> {
                    val s = sets[argA[pc]]
                    // A negated set spans nearly everything, so a prefilter built
                    // from it would not pay for itself.
                    if (s.isNegated) known = false else first.addRanges(s)
                    if (!sawStart) allAnchored = false
                }

                // The pattern can match the empty string, so every position is a
                // candidate and nothing is anchored.
                Op.MATCH -> {
                    known = false
                    allAnchored = false
                }

                // `.` accepts almost anything, a backreference's text is not
                // known until match time, and anything added later is unknown by
                // default — silently ignoring an opcode here would narrow the
                // prefilter and lose matches.
                else -> {
                    known = false
                    if (!sawStart) allAnchored = false
                }
            }
        }

        // The walk continues past an "unknown" so the anchoring answer stays
        // usable even when the prefilter is not.
        val prefilter = if (known && !first.isEmpty()) {
            first.build(ignoreCase = false, unicodeMode = unicodeMode)
        } else {
            null
        }
        return prefilter to allAnchored
    }

    // ------------------------------------------------------------------ emitting

    private fun emit(o: Int, a: Int = 0, b: Int = 0): Int {
        if (n == op.size) {
            op = op.copyOf(n * 2)
            argA = argA.copyOf(n * 2)
            argB = argB.copyOf(n * 2)
        }
        val idx = n
        op[idx] = o
        argA[idx] = a
        argB[idx] = b
        n++
        if (n > MAX_PROGRAM_SIZE) {
            throw RegExpSyntaxError("pattern compiles to more than $MAX_PROGRAM_SIZE instructions")
        }
        return idx
    }

    private fun addSet(set: CharSet): Int {
        sets += set
        return sets.size - 1
    }

    // ------------------------------------------------------------------ dispatch

    private fun compileExpr(e: Expr) {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            throw RegExpSyntaxError("pattern too deeply nested (limit: $MAX_NESTING_DEPTH)")
        }
        try {
            when (e) {
                is Sequence -> for (el in e.elements) compileExpr(el)
                is Disjunction -> compileDisjunction(e)
                is Literal -> emitCodePoint(e.codePoint)
                is Dot -> emit(if (effective.dotAll) Op.ANY_ALL else Op.ANY)
                is CharClass -> compileCharClass(e)
                is EscapeClass -> emit(Op.SET, addSet(escapeClassSet(e.kind, e.negated, e.property)))
                is Anchor -> compileAnchor(e)
                is Quantifier -> compileQuantifier(e)
                is Group -> {
                    emit(Op.SAVE_START, e.index)
                    compileExpr(e.body)
                    emit(Op.SAVE_END, e.index)
                }
                is NonCapturingGroup -> compileExpr(e.body)
                is ModifierGroup -> {
                    val saved = effective
                    effective = (effective + e.add) - e.remove
                    try {
                        compileExpr(e.body)
                    } finally {
                        effective = saved
                    }
                }
                is Lookaround -> compileLookaround(e)
                is Backreference -> compileBackreference(e)
                is ResolvedCharClass -> emit(Op.SET, addSet(e.set))
                is ClassSetExpr -> error("class set should have been lowered before compilation")
            }
        } finally {
            depth--
        }
    }

    private fun compileAnchor(anchor: Anchor) {
        // The operand carries the state a modifier group may have changed: whether
        // multiline applies here, and which word set `\b` should use.
        when (anchor.kind) {
            AnchorKind.START -> emit(Op.ASSERT_START, if (effective.multiline) 1 else 0)
            AnchorKind.END -> emit(Op.ASSERT_END, if (effective.multiline) 1 else 0)
            AnchorKind.WORD_BOUNDARY -> emit(Op.WORD_BOUNDARY, addSet(wordChars()))
            AnchorKind.NON_WORD_BOUNDARY -> emit(Op.NON_WORD_BOUNDARY, addSet(wordChars()))
        }
    }

    /**
     * `a|b|c` becomes a chain of splits, each falling through to the next
     * alternative, with every successful branch jumping to a common end.
     */
    private fun compileDisjunction(d: Disjunction) {
        if (d.alternatives.size == 1) {
            compileExpr(d.alternatives[0])
            return
        }

        val jumps = IntArray(d.alternatives.size - 1)
        for (i in 0 until d.alternatives.size - 1) {
            val split = emit(Op.SPLIT)
            argA[split] = n // primary: this alternative, which follows immediately
            compileExpr(d.alternatives[i])
            jumps[i] = emit(Op.JMP)
            argB[split] = n // fallback: whatever comes next (the following split)
        }
        compileExpr(d.alternatives.last())

        val end = n
        for (j in jumps) argA[j] = end
    }

    // --------------------------------------------------------------- quantifiers

    private fun compileQuantifier(q: Quantifier) {
        if (q.min > MAX_QUANTIFIER_REPEAT) {
            throw RegExpSyntaxError("quantifier minimum ${q.min} exceeds limit $MAX_QUANTIFIER_REPEAT")
        }
        if (q.max != -1 && q.max > MAX_QUANTIFIER_REPEAT) {
            throw RegExpSyntaxError("quantifier maximum ${q.max} exceeds limit $MAX_QUANTIFIER_REPEAT")
        }

        val range = groupRange(q.body)

        // The mandatory prefix. ECMA-262 resets the body's captures before every
        // iteration, including the first, so the reset is emitted here too.
        repeat(q.min) {
            emitReset(range)
            compileExpr(q.body)
        }

        when {
            q.max == -1 -> compileUnboundedTail(q, range)
            q.max > q.min -> compileOptionalRepeats(q, range, q.max - q.min)
            // max == min: fully covered by the mandatory prefix above.
        }
    }

    /** `*`, `+`, `{n,}` — a loop guarded against empty-matching iterations. */
    private fun compileUnboundedTail(q: Quantifier, range: IntArray?) {
        // The guard only ever fires when an iteration consumed nothing, so a body
        // that always consumes does not need it — and skipping it removes two
        // instructions and an undo-log entry from every iteration of the most
        // common loops (`\w+`, `[a-z]*`, `\d{2,}`).
        val guard = canMatchEmpty(q.body)
        val mark = if (guard) markCount++ else -1

        val split = emit(Op.SPLIT)
        val entry = n
        if (guard) emit(Op.SET_MARK, mark)
        emitReset(range)
        compileExpr(q.body)
        if (guard) emit(Op.EMPTY_CHECK, mark)
        emit(Op.JMP, split)
        val exit = n
        wireSplit(split, entry, exit, q.greedy)
    }

    /** `{n,m}` — the optional tail as a chain of skippable repetitions. */
    private fun compileOptionalRepeats(q: Quantifier, range: IntArray?, count: Int) {
        val guard = canMatchEmpty(q.body)
        val mark = if (guard) markCount++ else -1

        val splits = IntArray(count)
        for (i in 0 until count) {
            val split = emit(Op.SPLIT)
            splits[i] = split
            val entry = n
            if (guard) emit(Op.SET_MARK, mark)
            emitReset(range)
            compileExpr(q.body)
            if (guard) emit(Op.EMPTY_CHECK, mark)
            if (q.greedy) argA[split] = entry else argB[split] = entry
        }
        val exit = n
        // Skipping any repetition skips all the later ones too.
        for (s in splits) if (q.greedy) argB[s] = exit else argA[s] = exit
    }

    /**
     * Whether [e] can match the empty string.
     *
     * Conservative in the safe direction: anything uncertain reports true, which
     * only means the loop keeps its guard.
     */
    private fun canMatchEmpty(e: Expr): Boolean = when (e) {
        is Literal, is Dot, is CharClass, is EscapeClass, is ResolvedCharClass -> false
        is Sequence -> e.elements.all { canMatchEmpty(it) }
        is Disjunction -> e.alternatives.any { canMatchEmpty(it) }
        is Quantifier -> e.min == 0 || canMatchEmpty(e.body)
        is Group -> canMatchEmpty(e.body)
        is NonCapturingGroup -> canMatchEmpty(e.body)
        is ModifierGroup -> canMatchEmpty(e.body)
        // Assertions consume nothing, and a backreference to an unset or empty
        // group matches the empty string.
        is Anchor, is Lookaround, is Backreference -> true
        is ClassSetExpr -> true // lowered before this runs; assume the worst
    }

    private fun wireSplit(split: Int, body: Int, exit: Int, greedy: Boolean) {
        if (greedy) {
            argA[split] = body
            argB[split] = exit
        } else {
            argA[split] = exit
            argB[split] = body
        }
    }

    private fun emitReset(range: IntArray?) {
        if (range != null) emit(Op.RESET_GROUPS, range[0], range[1])
    }

    /**
     * The inclusive span of capture-group indices inside [node], or null if it
     * holds none.
     *
     * Indices are assigned in source order and a subtree covers a contiguous
     * source range, so its groups always form a contiguous index range — one
     * reset instruction therefore clears exactly the body's captures.
     */
    private fun groupRange(node: Expr): IntArray? {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE

        fun walk(e: Expr) {
            when (e) {
                is Group -> {
                    if (e.index < lo) lo = e.index
                    if (e.index > hi) hi = e.index
                    walk(e.body)
                }
                is NonCapturingGroup -> walk(e.body)
                is ModifierGroup -> walk(e.body)
                is Sequence -> for (x in e.elements) walk(x)
                is Disjunction -> for (x in e.alternatives) walk(x)
                is Quantifier -> walk(e.body)
                is Lookaround -> walk(e.body)
                else -> Unit
            }
        }
        walk(node)
        return if (hi < lo) null else intArrayOf(lo, hi)
    }

    // -------------------------------------------------------------- lookarounds

    /**
     * The body is emitted inline, jumped over, and terminated with its own
     * [Op.MATCH] so the sub-execution knows where to stop.
     *
     * A lookbehind's body is emitted in reverse so that running it right-to-left
     * reproduces ECMA-262 lookbehind evaluation, including capture order inside
     * quantified and backreferencing lookbehinds.
     */
    private fun compileLookaround(l: Lookaround) {
        val jump = emit(Op.JMP)
        val bodyStart = n
        compileExpr(if (l.behind) reverse(l.body) else l.body)
        emit(Op.MATCH)
        argA[jump] = n

        emit(
            when {
                !l.behind && !l.negated -> Op.LOOKAHEAD
                !l.behind -> Op.NEG_LOOKAHEAD
                !l.negated -> Op.LOOKBEHIND
                else -> Op.NEG_LOOKBEHIND
            },
            bodyStart,
        )
    }

    /**
     * Structurally reverses an expression for right-to-left matching.
     *
     * Sequences are emitted last-element-first and group bodies are reversed,
     * while capture indices are preserved. Single-width atoms are unchanged, and
     * nested lookarounds are left alone: each carries its own direction and
     * reverses its own body when compiled.
     */
    private fun reverse(e: Expr): Expr = when (e) {
        is Sequence -> Sequence(e.elements.asReversed().map { reverse(it) })
        is Disjunction -> Disjunction(e.alternatives.map { reverse(it) })
        is Quantifier -> Quantifier(e.min, e.max, e.greedy, reverse(e.body))
        is Group -> Group(e.index, e.name, reverse(e.body))
        is NonCapturingGroup -> NonCapturingGroup(reverse(e.body))
        is ModifierGroup -> ModifierGroup(e.add, e.remove, reverse(e.body))
        else -> e
    }

    // ------------------------------------------------------------ backreferences

    private fun compileBackreference(b: Backreference) {
        // An Annex B numeric escape that did not name a real group is literal
        // text, not a backreference.
        val fallback = b.fallback
        if (fallback != null) {
            for (cp in fallback) emitCodePoint(cp)
            return
        }

        // The parser resolves indices against the real group count, so this
        // should be unreachable; assert it rather than let a bad index reach the
        // capture array at match time.
        check(b.index in 1..pattern.numGroups) {
            "backreference \\${b.index} outside 1..${pattern.numGroups}"
        }

        val altIndex = if (b.altIndices.isEmpty()) {
            -1
        } else {
            backrefAlts += b.altIndices.toIntArray()
            backrefAlts.size - 1
        }
        emit(if (ignoreCase) Op.BACKREF_IGNORE_CASE else Op.BACKREF, b.index, altIndex)
    }

    // ------------------------------------------------- `v` class-set lowering

    /** Rewrites every [ClassSetExpr] in the tree into plain AST. */
    private fun lower(e: Expr): Expr = when (e) {
        is ClassSetExpr -> lowerClassSet(e)
        is Sequence -> Sequence(e.elements.map { lower(it) })
        is Disjunction -> Disjunction(e.alternatives.map { lower(it) })
        is Quantifier -> Quantifier(e.min, e.max, e.greedy, lower(e.body))
        is Group -> Group(e.index, e.name, lower(e.body))
        is NonCapturingGroup -> NonCapturingGroup(lower(e.body))
        is Lookaround -> Lookaround(lower(e.body), e.behind, e.negated)
        // A class set resolves its case closure eagerly, so lowering has to run
        // under the same modifiers the body will be compiled with.
        is ModifierGroup -> {
            val saved = effective
            effective = (effective + e.add) - e.remove
            try {
                ModifierGroup(e.add, e.remove, lower(e.body))
            } finally {
                effective = saved
            }
        }
        else -> e
    }

    /** A resolved class set: code points, plus any multi-character strings. */
    private class SetValue(val chars: Ranges, val strings: Set<List<Int>>)

    /**
     * Turns a class set into either a plain class or, when it holds strings, an
     * alternation.
     *
     * ECMA-262 matches the elements of such a class longest-first with normal
     * backtracking, which is exactly what an ordered alternation gives — so
     * `[\q{ab|a}]` compiles to `(?:ab|[a])` and `^[\q{ab|a}]b$` can still match
     * "ab" by falling back to the shorter alternative.
     */
    private fun lowerClassSet(e: ClassSetExpr): Expr {
        val value = evalClassSet(e.body)

        if (e.negated) {
            // A negated class may not contain strings: there is no meaningful
            // complement of "every string except these".
            if (mayContainStrings(e.body)) {
                throw RegExpSyntaxError("negated character class may contain strings")
            }
            return ResolvedCharClass(
                CharSetBuilder().addRanges(RangeAlgebra.complement(value.chars)).build(false, unicodeMode),
            )
        }

        val charClass = ResolvedCharClass(CharSetBuilder().addRanges(value.chars).build(false, unicodeMode))
        if (value.strings.isEmpty()) return charClass

        val alternatives = mutableListOf<Expr>()
        // Longest first; single-code-point strings were folded into `chars`.
        for (s in value.strings.filter { it.size >= 2 }.sortedByDescending { it.size }) {
            alternatives += Sequence(s.map { Literal(it) })
        }
        alternatives += charClass
        // The empty string is the shortest element, so it is tried last.
        if (value.strings.any { it.isEmpty() }) alternatives += Sequence(emptyList())

        return Disjunction(alternatives)
    }

    /**
     * Evaluates a class set, returning an already case-closed result.
     *
     * The closure is applied at the leaves rather than at the end because
     * complement and intersection do not commute with it: `[^a]` under `/vi`
     * must reject `A`, which means complementing the *closed* set `{a, A}`
     * rather than closing the complement of `{a}`.
     */
    private fun evalClassSet(node: ClassSetNode): SetValue = when (node) {
        is ClassSetLiteral -> SetValue(closed(CharSetBuilder().add(node.codePoint)), emptySet())

        is ClassSetRange -> SetValue(closed(CharSetBuilder().addRange(node.start, node.end)), emptySet())

        is ClassSetEscape -> {
            val set = escapeClassSet(node.kind, node.negated, node.property)
            SetValue(set.starts to set.ends, emptySet())
        }

        is ClassSetStrings -> {
            val chars = CharSetBuilder()
            val multi = mutableSetOf<List<Int>>()
            for (s in node.strings) {
                // A one-code-point string is indistinguishable from that character.
                if (s.size == 1) {
                    chars.add(s[0])
                } else {
                    multi += s.map { canonicalIfIgnoringCase(it) }
                }
            }
            SetValue(closed(chars), multi)
        }

        is ClassSetNested -> {
            if (node.negated && mayContainStrings(node.body)) {
                throw RegExpSyntaxError("negated character class may contain strings")
            }
            val inner = evalClassSet(node.body)
            if (node.negated) {
                SetValue(RangeAlgebra.complement(inner.chars), emptySet())
            } else {
                inner
            }
        }

        is ClassSetOperation -> evalOperation(node)
    }

    /**
     * ECMA-262 `MayContainStrings`, decided from the syntax rather than the
     * evaluated set.
     *
     * `[^\q{ab}--\q{ab}]` is a SyntaxError even though the difference is empty,
     * because the rule looks only at the first operand of a subtraction. A
     * union may contain strings if *any* operand does; an intersection only if
     * *all* of them do.
     */
    private fun mayContainStrings(node: ClassSetNode): Boolean = when (node) {
        is ClassSetStrings -> node.strings.any { it.size != 1 }
        // A negated nested class can never contain strings — it is rejected above
        // if its body could.
        is ClassSetNested -> !node.negated && mayContainStrings(node.body)
        is ClassSetOperation -> when (node.kind) {
            SetOpKind.UNION -> node.items.any { mayContainStrings(it) }
            SetOpKind.INTERSECTION -> node.items.isNotEmpty() && node.items.all { mayContainStrings(it) }
            SetOpKind.DIFFERENCE -> node.items.isNotEmpty() && mayContainStrings(node.items[0])
        }
        else -> false
    }

    private fun evalOperation(node: ClassSetOperation): SetValue {
        if (node.items.isEmpty()) return SetValue(RangeAlgebra.EMPTY, emptySet())

        val values = node.items.map { evalClassSet(it) }
        var chars = values[0].chars
        var strings = values[0].strings

        for (i in 1 until values.size) {
            val v = values[i]
            when (node.kind) {
                SetOpKind.UNION -> {
                    chars = RangeAlgebra.union(chars, v.chars)
                    strings = strings + v.strings
                }
                SetOpKind.INTERSECTION -> {
                    chars = RangeAlgebra.intersect(chars, v.chars)
                    strings = strings intersect v.strings
                }
                SetOpKind.DIFFERENCE -> {
                    chars = RangeAlgebra.subtract(chars, v.chars)
                    strings = strings - v.strings
                }
            }
        }
        return SetValue(chars, strings)
    }

    /** Builds a leaf's ranges with the case closure already applied. */
    private fun closed(b: CharSetBuilder): Ranges {
        val set = b.build(ignoreCase, unicodeMode)
        return set.starts to set.ends
    }

    private fun canonicalIfIgnoringCase(cp: Int): Int =
        if (ignoreCase) Unicode.canonicalize(cp, true, unicodeMode) else cp

    // ------------------------------------------------------------- character sets

    /**
     * Emits a literal, choosing the cheapest instruction for its case closure:
     * one code point, the usual two-element pair, or a general set.
     */
    private fun emitCodePoint(cp: Int) {
        if (!ignoreCase) {
            emit(Op.CHAR, cp)
            return
        }
        val set = CharSetBuilder().add(cp).build(ignoreCase = true, unicodeMode = unicodeMode)
        val single = set.singleCodePointOrNull()
        if (single >= 0) {
            emit(Op.CHAR, single)
            return
        }
        val pair = set.twoCodePointsOrNull()
        if (pair != null) {
            emit(Op.CHAR2, pair[0], pair[1])
            return
        }
        emit(Op.SET, addSet(set))
    }

    private fun compileCharClass(cc: CharClass) {
        val builder = CharSetBuilder()
        for (atom in cc.atoms) {
            when (atom) {
                is ClassLiteral -> builder.add(atom.codePoint)
                is ClassRange -> builder.addRange(atom.start, atom.end)
                is ClassEscape -> {
                    val set = escapeClassSet(atom.kind, atom.negated, atom.property)
                    builder.addRanges(set)
                }
            }
        }
        emit(Op.SET, addSet(builder.build(ignoreCase, unicodeMode, negated = cc.negated)))
    }

    /**
     * The CharSet for `\d`, `\w`, `\s`, `\p{…}` and their negated forms.
     *
     * Negation is resolved by complementing the *closed* positive set, which is
     * what keeps `\W` from matching `ſ` under `/iu`.
     */
    private fun escapeClassSet(kind: EscapeKind, negated: Boolean, property: String?): CharSet {
        val positive = when (kind) {
            EscapeKind.DIGIT -> digitChars()
            EscapeKind.WORD -> wordChars()
            EscapeKind.SPACE -> spaceChars()
            EscapeKind.UNICODE_PROPERTY -> {
                val name = property ?: throw RegExpSyntaxError("missing property name")
                val ranges = Unicode.resolveProperty(name)
                    ?: throw RegExpSyntaxError("invalid property name: $name")
                CharSetBuilder().addAll(ranges).build(ignoreCase, unicodeMode)
            }
        }
        if (!negated) return positive
        return CharSetBuilder().addComplementOf(positive).build(ignoreCase, unicodeMode)
    }

    /**
     * ECMA-262 `\s`: `WhiteSpace` plus `LineTerminator` — the `Zs` category plus
     * tab, LF, VT, FF, CR, LS, PS and the zero-width no-break space.
     */
    private fun whitespaceBuilder(): CharSetBuilder {
        val b = CharSetBuilder()
        b.add(0x09).add(0x0A).add(0x0B).add(0x0C).add(0x0D) // tab, LF, VT, FF, CR
        b.add(0x20) // space
        b.add(0xA0) // no-break space
        b.add(0x1680)
        b.addRange(0x2000, 0x200A)
        b.add(0x2028).add(0x2029) // line separator, paragraph separator
        b.add(0x202F).add(0x205F).add(0x3000)
        b.add(0xFEFF) // zero width no-break space
        return b
    }

    internal companion object {
        const val MAX_QUANTIFIER_REPEAT: Int = 10_000
        const val MAX_NESTING_DEPTH: Int = 200
        const val MAX_PROGRAM_SIZE: Int = 200_000
    }
}

/** Adds every range of an already-built [CharSet]. */
private fun CharSetBuilder.addRanges(set: CharSet): CharSetBuilder {
    for (i in set.starts.indices) addRange(set.starts[i], set.ends[i])
    return this
}
