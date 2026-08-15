package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.unicode.Unicode

/** Which dialect of the pattern grammar to accept. */
public enum class Syntax {
    /**
     * ECMA-262 Annex B web-compatibility syntax: legacy octal escapes,
     * out-of-range numeric backreferences, invalid `\c`, and malformed `{...}`
     * quantifiers are accepted as literals instead of being SyntaxErrors.
     *
     * This is what browsers do, so a pattern that works in JavaScript works
     * here, and it is the default. The `u` and `v` flags disable it regardless,
     * because Annex B does not apply in Unicode mode.
     */
    ANNEX_B,

    /** Strict ECMA-262, which rejects all of the above as SyntaxErrors. */
    STRICT,
}

/**
 * Recursive-descent parser for ECMA-262 patterns.
 *
 * Reads the pattern directly rather than through a token layer: the same
 * character means different things in different contexts (`{` is a quantifier
 * opener or a literal, `-` is a range operator only inside a class, `]` closes a
 * class or is a literal), which a context-free tokenizer cannot express.
 *
 * Positions are UTF-16 code unit offsets. Outside Unicode mode an atom is a
 * single code unit — so a surrogate pair is two atoms, exactly as in JavaScript;
 * under `u`/`v` surrogate pairs are combined into one code point atom.
 */
internal class Parser(
    private val source: String,
    private val flags: Flags,
    syntax: Syntax = Syntax.ANNEX_B,
) {
    private val unicodeMode = flags.isUnicodeMode

    /** Annex B leniencies never apply in Unicode mode. */
    private val annexB = syntax == Syntax.ANNEX_B && !unicodeMode

    private var pos = 0
    private var depth = 0

    private var groupCount = 0
    private val groupNames = LinkedHashMap<String, MutableList<Int>>()

    private var patternHasNamedGroups = false

    private class PendingBackref(val node: Backreference, val name: String, val at: Int)

    /**
     * A `\n` escape awaiting resolution.
     *
     * Numeric backreferences are resolved after the whole pattern is parsed,
     * against the *actual* group count. Deciding earlier would mean trusting the
     * pre-scan's count, and any disagreement between it and the parser would let
     * a backreference index past the end of the capture array at match time.
     */
    private class PendingNumeric(
        val node: Backreference,
        val value: Long,
        val digits: String,
        val at: Int,
    )

    private val pendingNamed = mutableListOf<PendingBackref>()
    private val pendingNumeric = mutableListOf<PendingNumeric>()

    // ------------------------------------------------------------------ scanning

    private val end get() = source.length
    private val atEnd get() = pos >= end

    private fun peek(): Char = source[pos]
    private fun peekOrNull(): Char? = if (pos < end) source[pos] else null
    private fun peekAt(offset: Int): Char? =
        if (pos + offset < end) source[pos + offset] else null

    private fun advance() {
        pos++
    }

    private fun eat(c: Char): Boolean {
        if (pos < end && source[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun fail(message: String, at: Int = pos): Nothing =
        throw RegExpSyntaxError(message, at)

    /**
     * Reads one atom's worth of input: a code point under `u`/`v`, otherwise a
     * single UTF-16 code unit (so a lone surrogate is matchable on its own).
     */
    private fun readAtomCodePoint(): Int {
        val c = source[pos++]
        if (unicodeMode && c.isHighSurrogate() && pos < end && source[pos].isLowSurrogate()) {
            val low = source[pos++]
            return combineSurrogates(c, low)
        }
        return c.code
    }

    private fun combineSurrogates(high: Char, low: Char): Int =
        0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)

    // ------------------------------------------------------------------- entry

    fun parse(): Pattern {
        precount()

        val body = parseDisjunction()
        if (!atEnd) {
            // parseAlternative only stops early on '|' or ')', and '|' is consumed
            // by parseDisjunction, so anything left is an unbalanced ')'.
            fail("unmatched ')'")
        }

        resolveNumericBackreferences()
        resolveNamedBackreferences()
        checkDuplicateNamesOnEachPath(body)

        return Pattern(
            body = body,
            numGroups = groupCount,
            flags = flags,
            groupNames = groupNames.mapValues { (_, v) -> v.toList() },
        )
    }

    /**
     * Detects whether the pattern declares any named group, before parsing.
     *
     * This is needed while parsing escapes: in Annex B `\k` is a named
     * backreference only when the pattern contains a named group at all, and
     * otherwise it is the identity escape for "k".
     *
     * Group *counting* is deliberately not done here — see [PendingNumeric].
     */
    private fun precount() {
        var i = 0
        var inClass = false
        while (i < end) {
            when (source[i]) {
                '\\' -> i++ // whatever follows is escaped, never structural
                '[' -> inClass = true
                ']' -> inClass = false
                '(' -> if (!inClass && source.getOrNull(i + 1) == '?') {
                    val after = source.getOrNull(i + 3)
                    if (source.getOrNull(i + 2) == '<' && after != '=' && after != '!') {
                        patternHasNamedGroups = true
                    }
                }
            }
            i++
        }
    }

    private fun enterNesting() {
        depth++
        if (depth > MAX_NESTING_DEPTH) {
            fail("pattern too deeply nested (limit: $MAX_NESTING_DEPTH)")
        }
    }

    private fun leaveNesting() {
        depth--
    }

    // ------------------------------------------------------------- disjunction

    private fun parseDisjunction(): Expr {
        val first = parseAlternative()
        if (peekOrNull() != '|') return first

        val alts = mutableListOf(first)
        while (eat('|')) alts += parseAlternative()
        return Disjunction(alts)
    }

    private fun parseAlternative(): Expr {
        val elements = mutableListOf<Expr>()
        while (!atEnd && peek() != '|' && peek() != ')') {
            elements += parseTerm()
        }
        return when (elements.size) {
            0 -> Sequence(emptyList())
            1 -> elements[0]
            else -> Sequence(elements)
        }
    }

    // -------------------------------------------------------------------- term

    private fun parseTerm(): Expr {
        when (peek()) {
            '^' -> {
                advance()
                rejectQuantifier("an anchor")
                return Anchor(AnchorKind.START)
            }
            '$' -> {
                advance()
                rejectQuantifier("an anchor")
                return Anchor(AnchorKind.END)
            }
            '(' -> return parseGroup()
            '*', '+', '?' -> fail("nothing to repeat")
            '{' -> {
                // A well-formed `{n,m}` here has no atom to apply to. A malformed
                // one is a literal '{' in Annex B, and an error in strict mode.
                if (bracedQuantifierAhead()) fail("nothing to repeat")
            }
        }

        if (peek() == '\\') {
            val kind = when (peekAt(1)) {
                'b' -> AnchorKind.WORD_BOUNDARY
                'B' -> AnchorKind.NON_WORD_BOUNDARY
                else -> null
            }
            if (kind != null) {
                pos += 2
                rejectQuantifier("a word boundary")
                return Anchor(kind)
            }
        }

        val atom = parseAtom()
        return applyQuantifier(atom)
    }

    /** Rejects a quantifier applied to something ECMA-262 forbids quantifying. */
    private fun rejectQuantifier(what: String) {
        if (quantifierAhead()) fail("nothing to repeat: a quantifier cannot follow $what")
    }

    private fun quantifierAhead(): Boolean {
        val c = peekOrNull() ?: return false
        if (c == '*' || c == '+' || c == '?') return true
        return c == '{' && bracedQuantifierAhead()
    }

    /** True if a syntactically valid `{n}`/`{n,}`/`{n,m}` starts at [pos]. */
    private fun bracedQuantifierAhead(): Boolean {
        val save = pos
        val parsed = tryParseBracedQuantifier() != null
        pos = save
        return parsed
    }

    /**
     * Parses `{n}`, `{n,}` or `{n,m}`, or returns null leaving [pos] unchanged.
     *
     * Bounds are read as Long so an absurd `{99999999999}` neither overflows nor
     * parses as something smaller; the compiler enforces the real limit.
     */
    private fun tryParseBracedQuantifier(): LongArray? {
        val save = pos
        if (!eat('{')) return null

        val min = readDecimal() ?: run { pos = save; return null }

        var max = min
        if (eat(',')) {
            max = if (peekOrNull()?.isAsciiDigit() == true) {
                readDecimal() ?: run { pos = save; return null }
            } else {
                -1L // unbounded
            }
        }

        if (!eat('}')) {
            pos = save
            return null
        }
        return longArrayOf(min, max)
    }

    private fun readDecimal(): Long? {
        if (pos >= end || !source[pos].isAsciiDigit()) return null
        var v = 0L
        while (pos < end && source[pos].isAsciiDigit()) {
            if (v < CLAMP) v = v * 10 + (source[pos].code - '0'.code)
            pos++
        }
        return v
    }

    private fun applyQuantifier(atom: Expr): Expr {
        val c = peekOrNull() ?: return atom

        val min: Long
        val max: Long
        when (c) {
            '*' -> { advance(); min = 0; max = -1 }
            '+' -> { advance(); min = 1; max = -1 }
            '?' -> { advance(); min = 0; max = 1 }
            '{' -> {
                val bounds = tryParseBracedQuantifier()
                if (bounds == null) {
                    // Not a quantifier after all.
                    if (!annexB) fail("incomplete quantifier")
                    return atom
                }
                min = bounds[0]
                max = bounds[1]
                // Out-of-order bounds are an early error in every mode, unlike a
                // merely malformed `{...}` which Annex B re-reads as literal text.
                if (max != -1L && max < min) fail("numbers out of order in {} quantifier")
            }
            else -> return atom
        }

        val greedy = !eat('?')

        // `a**`, `a{2}{3}`, `a*??` — a quantifier may not be quantified.
        if (quantifierAhead()) fail("nothing to repeat: a quantifier cannot follow a quantifier")

        return Quantifier(
            min = min.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            max = if (max == -1L) -1 else max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            greedy = greedy,
            body = atom,
        )
    }

    // -------------------------------------------------------------------- atom

    private fun parseAtom(): Expr {
        return when (val c = peek()) {
            '.' -> { advance(); Dot }
            '\\' -> parseAtomEscape()
            '[' -> if (flags.unicodeSets) parseClassSetClass() else parseCharacterClass()
            ')' -> fail("unmatched ')'")
            ']', '}' -> {
                // Annex B allows a lone closing bracket as a literal.
                if (!annexB) fail("lone quantifier bracket '$c'")
                advance()
                Literal(c.code)
            }
            '{' -> {
                if (!annexB) fail("lone quantifier bracket '{'")
                advance()
                Literal('{'.code)
            }
            else -> Literal(readAtomCodePoint())
        }
    }

    // ------------------------------------------------------------------ groups

    private fun parseGroup(): Expr {
        enterNesting()
        try {
            val open = pos
            advance() // '('

            if (peekOrNull() != '?') {
                // Capturing group; its number is fixed at the opening paren, before
                // the body (which may contain further, higher-numbered groups).
                val index = ++groupCount
                val body = parseDisjunction()
                expectGroupClose(open)
                return applyQuantifier(Group(index, null, body))
            }

            advance() // '?'

            // `(?i:`, `(?-i:`, `(?i-ms:` — a modifier group. Checked before the
            // other `(?` forms because it shares their opening.
            parseModifierGroup(open)?.let { return it }

            return when (peekOrNull()) {
                ':' -> {
                    advance()
                    val body = parseDisjunction()
                    expectGroupClose(open)
                    applyQuantifier(NonCapturingGroup(body))
                }
                '=' -> { advance(); finishLookaround(open, behind = false, negated = false) }
                '!' -> { advance(); finishLookaround(open, behind = false, negated = true) }
                '<' -> {
                    advance()
                    when (peekOrNull()) {
                        '=' -> { advance(); finishLookaround(open, behind = true, negated = false) }
                        '!' -> { advance(); finishLookaround(open, behind = true, negated = true) }
                        else -> parseNamedGroup(open)
                    }
                }
                else -> fail("invalid group")
            }
        } finally {
            leaveNesting()
        }
    }

    /**
     * `(?<modifiers>:...)` / `(?<add>-<remove>:...)`, or null if this is not one.
     *
     * Only `i`, `m` and `s` may be modified — the Unicode, global, sticky and
     * indices flags change how input is read or how a match is reported, not how
     * a subexpression matches, so the grammar excludes them. A flag may not be
     * repeated, nor appear in both the added and removed sets, and at least one
     * flag must appear somewhere.
     */
    private fun parseModifierGroup(open: Int): Expr? {
        val save = pos
        val add = readModifiers()
        var remove = Flags.NONE
        var sawDash = false
        if (peekOrNull() == '-') {
            advance()
            sawDash = true
            remove = readModifiers()
        }
        if (peekOrNull() != ':' || (add == Flags.NONE && !sawDash)) {
            // Not a modifier group: could be `(?:`, `(?=`, a named group, or an
            // error the normal dispatch will report.
            pos = save
            return null
        }

        if (add == Flags.NONE && remove == Flags.NONE) {
            fail("invalid flag group: no modifiers given", open)
        }
        if (Flags(add.bits and remove.bits) != Flags.NONE) {
            fail("repeated flag in flag group", open)
        }

        advance() // ':'
        val body = parseDisjunction()
        expectGroupClose(open)
        return applyQuantifier(ModifierGroup(add, remove, body))
    }

    /**
     * Reads a run of `i`/`m`/`s`, rejecting a repeat.
     *
     * Returns [Flags.NONE] without consuming anything when the next character is
     * not a modifier, so the caller can rewind.
     */
    private fun readModifiers(): Flags {
        var seen = Flags.NONE
        while (true) {
            val flag = when (peekOrNull()) {
                'i' -> Flags.IGNORE_CASE
                'm' -> Flags.MULTILINE
                's' -> Flags.DOT_ALL
                else -> return seen
            }
            if (flag in seen) fail("repeated flag in flag group")
            seen += flag
            advance()
        }
    }

    private fun expectGroupClose(open: Int) {
        if (!eat(')')) fail("unterminated group", open)
    }

    private fun finishLookaround(open: Int, behind: Boolean, negated: Boolean): Expr {
        val body = parseDisjunction()
        expectGroupClose(open)
        val node = Lookaround(body, behind = behind, negated = negated)

        // Annex B makes lookahead — and only lookahead — quantifiable.
        if (!behind && annexB) return applyQuantifier(node)

        if (quantifierAhead()) {
            fail("invalid quantifier: a quantifier cannot follow a ${if (behind) "lookbehind" else "lookahead"}")
        }
        return node
    }

    private fun parseNamedGroup(open: Int): Expr {
        val name = parseGroupName()
        val index = ++groupCount
        groupNames.getOrPut(name) { mutableListOf() }.add(index)

        val body = parseDisjunction()
        expectGroupClose(open)
        return applyQuantifier(Group(index, name, body))
    }

    /**
     * Parses a `RegExpIdentifierName` up to the closing `>`.
     *
     * Names always read surrogate pairs as one code point, and accept `\u` escapes,
     * regardless of the Unicode flag.
     */
    private fun parseGroupName(): String {
        val sb = StringBuilder()
        var first = true

        while (true) {
            if (atEnd) fail("unterminated group name")
            if (peek() == '>') {
                advance()
                break
            }

            val cp = if (peek() == '\\') {
                if (peekAt(1) != 'u') fail("invalid escape in group name")
                pos += 2
                readUnicodeEscapeValue(combineSurrogatePairs = true)
                    ?: fail("invalid unicode escape in group name")
            } else {
                val c = source[pos++]
                if (c.isHighSurrogate() && pos < end && source[pos].isLowSurrogate()) {
                    combineSurrogates(c, source[pos++])
                } else {
                    c.code
                }
            }

            val ok = if (first) Unicode.isIdentifierStart(cp) else Unicode.isIdentifierPart(cp)
            if (!ok) fail("invalid capture group name")
            sb.appendCodePoint(cp)
            first = false
        }

        if (sb.isEmpty()) fail("empty capture group name")
        return sb.toString()
    }

    // ----------------------------------------------------------------- escapes

    private fun parseAtomEscape(): Expr {
        val backslash = pos
        advance() // '\'
        if (atEnd) fail("\\ at end of pattern", backslash)

        return when (val c = peek()) {
            'd', 'D' -> { advance(); EscapeClass(EscapeKind.DIGIT, c == 'D') }
            'w', 'W' -> { advance(); EscapeClass(EscapeKind.WORD, c == 'W') }
            's', 'S' -> { advance(); EscapeClass(EscapeKind.SPACE, c == 'S') }
            'p', 'P' -> parsePropertyOrStringsEscape(negated = c == 'P', backslash)
            'k' -> parseNamedBackreference(backslash)
            '0' -> parseZeroEscape(backslash)
            in '1'..'9' -> parseDecimalEscape(backslash)
            'c' -> parseControlEscape(backslash, inClass = false)
            else -> Literal(parseCharacterEscape(inClass = false))
        }
    }

    /**
     * `\p{...}` as a standalone atom, allowing a property of strings under `v`.
     *
     * A property of strings matches whole sequences, so it lowers to the same
     * machinery as `\q{...}` rather than to a character set.
     */
    private fun parsePropertyOrStringsEscape(negated: Boolean, backslash: Int): Expr {
        if (flags.unicodeSets) {
            val strings = tryParsePropertyOfStrings(negated, backslash)
            if (strings != null) return ClassSetExpr(strings, negated = false)
        }
        return parsePropertyEscape(negated)
    }

    /**
     * Reads `\p{Name}` when Name is a property of strings, or rewinds and
     * returns null so the caller can treat it as a character property.
     */
    private fun tryParsePropertyOfStrings(negated: Boolean, backslash: Int): ClassSetStrings? {
        val save = pos
        advance() // 'p' or 'P'
        if (!eat('{')) {
            pos = save
            return null
        }
        val start = pos
        while (pos < end && source[pos] != '}') pos++
        if (atEnd) {
            pos = save
            return null
        }
        val name = source.substring(start, pos)
        val sequences = Unicode.resolvePropertyOfStrings(name)
        if (sequences == null) {
            pos = save
            return null
        }
        advance() // '}'
        // Complementing a set of strings is not meaningful, so `\P` is rejected.
        if (negated) fail("invalid property name: $name cannot be negated", backslash)
        return ClassSetStrings(sequences)
    }

    /** `\p{...}` / `\P{...}`, or an identity escape outside Unicode mode. */
    private fun parsePropertyEscape(negated: Boolean): Expr {
        val letter = peek()
        if (!unicodeMode) {
            // Without u/v, `\p` is just the character 'p'.
            advance()
            return Literal(letter.code)
        }
        advance() // 'p' or 'P'

        if (!eat('{')) fail("expected '{' after \\$letter")
        val start = pos
        while (pos < end && source[pos] != '}') pos++
        if (atEnd) fail("unterminated \\$letter{...}")
        val spec = source.substring(start, pos)
        advance() // '}'

        if (Unicode.resolveProperty(spec) == null) fail("invalid property name: $spec", start)
        return EscapeClass(EscapeKind.UNICODE_PROPERTY, negated, spec)
    }

    private fun parseNamedBackreference(backslash: Int): Expr {
        // In Annex B, `\k` is only a backreference when the pattern actually has a
        // named group; otherwise it is the identity escape for 'k'.
        if (!unicodeMode && !patternHasNamedGroups) {
            advance() // 'k'
            return Literal('k'.code)
        }
        advance() // 'k'
        if (!eat('<')) fail("expected '<' after \\k", backslash)

        val name = parseGroupName() // consumes through '>'
        val node = Backreference()
        pendingNamed += PendingBackref(node, name, backslash)
        return node
    }

    /** `\0`: NUL, or a legacy octal escape under Annex B. */
    private fun parseZeroEscape(backslash: Int): Expr {
        val nextIsDigit = peekAt(1)?.isAsciiDigit() == true
        if (!nextIsDigit) {
            advance() // '0'
            return Literal(0)
        }
        if (!annexB) fail("invalid decimal escape", backslash)
        return Literal(readLegacyOctal())
    }

    /**
     * `\1`..`\9`: a backreference when the group exists, otherwise (Annex B) a
     * legacy octal escape followed by literal digits.
     *
     * Which of the two it is cannot be decided yet — the group may be declared
     * later, as in `\1(a)` — so the digits are consumed and the decision is
     * deferred to [resolveNumericBackreferences].
     */
    private fun parseDecimalEscape(backslash: Int): Expr {
        val start = pos
        var value = 0L
        while (pos < end && source[pos].isAsciiDigit()) {
            if (value < CLAMP) value = value * 10 + (source[pos].code - '0'.code)
            pos++
        }

        val node = Backreference()
        pendingNumeric += PendingNumeric(node, value, source.substring(start, pos), backslash)
        return node
    }

    /** Reads up to three octal digits, capped so the value stays <= 0o377. */
    private fun readLegacyOctal(): Int {
        val maxDigits = if (source[pos] <= '3') 3 else 2
        var value = 0
        var taken = 0
        while (taken < maxDigits && pos < end && source[pos] in '0'..'7') {
            value = value * 8 + (source[pos].code - '0'.code)
            pos++
            taken++
        }
        return value
    }

    private fun legacyOctalSequence(): Expr {
        val parts = mutableListOf<Expr>()
        if (source[pos] in '0'..'7') {
            parts += Literal(readLegacyOctal())
        }
        while (pos < end && source[pos].isAsciiDigit()) {
            parts += Literal(source[pos].code)
            pos++
        }
        return if (parts.size == 1) parts[0] else Sequence(parts)
    }

    /**
     * `\cX`. Under Annex B a character class additionally admits digits and `_`,
     * and an invalid control escape degrades to the literal text `\c`.
     */
    private fun parseControlEscape(backslash: Int, inClass: Boolean): Expr {
        val letter = peekAt(1)
        val valid = letter != null && (
            letter.isAsciiLetter() ||
                (annexB && inClass && (letter.isAsciiDigit() || letter == '_'))
            )

        if (valid) {
            pos += 2
            return Literal(letter!!.code % 32)
        }

        if (!annexB) fail("invalid control escape", backslash)

        // Not a control escape at all: the backslash and the 'c' are both literal.
        advance() // 'c'
        return Sequence(listOf(Literal('\\'.code), Literal('c'.code)))
    }

    /**
     * A `CharacterEscape` that denotes exactly one code point.
     *
     * [pos] is on the character after the backslash.
     */
    private fun parseCharacterEscape(inClass: Boolean): Int {
        val backslash = pos - 1
        val c = source[pos]

        when (c) {
            'f' -> { advance(); return 0x0C }
            'n' -> { advance(); return 0x0A }
            'r' -> { advance(); return 0x0D }
            't' -> { advance(); return 0x09 }
            'v' -> { advance(); return 0x0B }
            'x' -> {
                advance()
                val v = readFixedHex(2)
                if (v != null) return v
                if (!annexB) fail("invalid hexadecimal escape", backslash)
                return 'x'.code // identity escape
            }
            'u' -> {
                advance()
                val v = readUnicodeEscapeValue(combineSurrogatePairs = unicodeMode)
                if (v != null) return v
                if (!annexB) fail("invalid unicode escape", backslash)
                return 'u'.code // identity escape
            }
        }

        // Identity escapes.
        if (c.isSyntaxCharacter() || c == '/') {
            advance()
            return c.code
        }
        if (c == '-' && inClass) {
            // Valid ClassEscape in every mode; outside a class `\-` is an error under u.
            advance()
            return '-'.code
        }
        if (unicodeMode) fail("invalid escape: \\$c", backslash)

        advance()
        return readIdentityEscapeRest(c)
    }

    /** In Annex B any other character escapes to itself, astral chars included. */
    private fun readIdentityEscapeRest(c: Char): Int {
        if (c.isHighSurrogate() && pos < end && source[pos].isLowSurrogate()) {
            return combineSurrogates(c, source[pos++])
        }
        return c.code
    }

    /** Reads exactly [n] hex digits, or returns null leaving [pos] unchanged. */
    private fun readFixedHex(n: Int): Int? {
        if (pos + n > end) return null
        var v = 0
        for (i in 0 until n) {
            val d = source[pos + i].hexValue() ?: return null
            v = v * 16 + d
        }
        pos += n
        return v
    }

    /**
     * Reads the body of a `\u` escape: `HHHH`, or `{H+}` under Unicode mode.
     * Returns null (leaving [pos] unchanged) when it is not well formed.
     */
    private fun readUnicodeEscapeValue(combineSurrogatePairs: Boolean): Int? {
        val save = pos

        if (pos < end && source[pos] == '{') {
            // `\u{...}` is only Unicode-mode syntax; in Annex B the '{' is left for
            // the quantifier/literal path, making `\u{2}` mean "the letter u, twice".
            if (!unicodeMode && !combineSurrogatePairs) return null
            advance()
            val start = pos
            var v = 0L
            while (pos < end && source[pos] != '}') {
                val d = source[pos].hexValue() ?: run { pos = save; return null }
                v = v * 16 + d
                if (v > Unicode.MAX_CODE_POINT) {
                    fail("unicode code point escape out of range", start)
                }
                pos++
            }
            if (pos >= end || pos == start) { pos = save; return null }
            advance() // '}'
            return v.toInt()
        }

        val first = readFixedHex(4) ?: run { pos = save; return null }

        if (combineSurrogatePairs && first in 0xD800..0xDBFF &&
            pos + 1 < end && source[pos] == '\\' && source[pos + 1] == 'u'
        ) {
            val mark = pos
            pos += 2
            val low = readFixedHex(4)
            if (low != null && low in 0xDC00..0xDFFF) {
                return 0x10000 + ((first - 0xD800) shl 10) + (low - 0xDC00)
            }
            pos = mark
        }
        return first
    }

    // -------------------------------------------------------- character classes

    private fun parseCharacterClass(): Expr {
        val open = pos
        advance() // '['
        val negated = eat('^')

        val atoms = mutableListOf<ClassAtom>()
        while (true) {
            if (atEnd) fail("unterminated character class", open)
            if (peek() == ']') {
                advance()
                break
            }

            val first = parseClassAtoms()

            // A '-' is a range operator only when a real atom follows it.
            if (first.size == 1 && peekOrNull() == '-' && peekAt(1) != ']' && pos + 1 < end) {
                advance() // '-'
                val second = parseClassAtoms()
                val lo = first[0]
                val hi = second.firstOrNull()

                if (lo is ClassLiteral && hi is ClassLiteral && second.size == 1) {
                    if (lo.codePoint > hi.codePoint) {
                        fail("range out of order in character class")
                    }
                    atoms += ClassRange(lo.codePoint, hi.codePoint)
                } else {
                    // e.g. [a-\d]: not a range. Annex B keeps the '-' as a literal;
                    // Unicode mode rejects it.
                    if (unicodeMode) fail("invalid character class")
                    atoms += first
                    atoms += ClassLiteral('-'.code)
                    atoms += second
                }
            } else {
                atoms += first
            }
        }

        return CharClass(negated, atoms)
    }

    /**
     * Parses one class member. Returns several atoms only for the Annex B `\c`
     * degradation, which yields the two literals `\` and `c`.
     */
    private fun parseClassAtoms(): List<ClassAtom> {
        if (peek() != '\\') {
            return listOf(ClassLiteral(readAtomCodePoint()))
        }

        val backslash = pos
        advance() // '\'
        if (atEnd) fail("\\ at end of pattern", backslash)

        return when (val c = peek()) {
            'd', 'D' -> { advance(); listOf(ClassEscape(EscapeKind.DIGIT, c == 'D')) }
            'w', 'W' -> { advance(); listOf(ClassEscape(EscapeKind.WORD, c == 'W')) }
            's', 'S' -> { advance(); listOf(ClassEscape(EscapeKind.SPACE, c == 'S')) }

            'b' -> { advance(); listOf(ClassLiteral(0x08)) } // backspace, not a boundary

            'p', 'P' -> {
                if (!unicodeMode) {
                    advance()
                    listOf(ClassLiteral(c.code))
                } else {
                    val e = parsePropertyEscape(negated = c == 'P') as EscapeClass
                    listOf(ClassEscape(e.kind, e.negated, e.property))
                }
            }

            'c' -> when (val node = parseControlEscape(backslash, inClass = true)) {
                is Literal -> listOf(ClassLiteral(node.codePoint))
                is Sequence -> node.elements.map { ClassLiteral((it as Literal).codePoint) }
                else -> error("unreachable control escape node")
            }

            // Inside a class there are no backreferences: `\1` is a legacy octal
            // escape under Annex B, and an error under Unicode mode.
            in '0'..'9' -> {
                if (!annexB) {
                    // `\0` is still the NUL escape, as long as no digit follows
                    // it; every other digit escape is invalid here.
                    if (c == '0' && peekAt(1)?.isAsciiDigit() != true) {
                        advance()
                        return listOf(ClassLiteral(0))
                    }
                    fail("invalid decimal escape", backslash)
                }
                when (val node = legacyOctalSequence()) {
                    is Literal -> listOf(ClassLiteral(node.codePoint))
                    is Sequence -> node.elements.map { ClassLiteral((it as Literal).codePoint) }
                    else -> error("unreachable octal node")
                }
            }

            else -> listOf(ClassLiteral(parseCharacterEscape(inClass = true)))
        }
    }

    // ------------------------------------------- character classes (`v` flag)

    /**
     * `v`-mode `[...]`, a `ClassSetExpression`.
     *
     * Unlike the `u` grammar this is a set algebra: operands may be nested
     * classes or `\q{…}` string literals, joined by implicit union, `&&`
     * (intersection) or `--` (difference). Mixing two different operators at one
     * level is a SyntaxError, as is a bare `(`, `)`, `[`, `{`, `}`, `/`, `-` or
     * `|`, which must be escaped.
     */
    private fun parseClassSetClass(): Expr {
        val open = pos
        advance() // '['
        val negated = eat('^')
        val body = parseClassSetContents(open)
        if (!eat(']')) fail("unterminated character class", open)
        return ClassSetExpr(body, negated)
    }

    private fun parseClassSetContents(open: Int): ClassSetNode {
        if (peekOrNull() == ']') return ClassSetOperation(SetOpKind.UNION, emptyList())

        val first = parseClassSetOperandOrRange(open)

        if (doubleAhead('&')) {
            val items = mutableListOf(first)
            while (doubleAhead('&')) {
                pos += 2
                items += parseClassSetOperandOrRange(open)
            }
            if (peekOrNull() != ']') fail("invalid set operation in character class")
            return ClassSetOperation(SetOpKind.INTERSECTION, items)
        }

        if (doubleAhead('-')) {
            val items = mutableListOf(first)
            while (doubleAhead('-')) {
                pos += 2
                items += parseClassSetOperandOrRange(open)
            }
            if (peekOrNull() != ']') fail("invalid set operation in character class")
            return ClassSetOperation(SetOpKind.DIFFERENCE, items)
        }

        val items = mutableListOf(first)
        while (!atEnd && peek() != ']') {
            if (doubleAhead('&') || doubleAhead('-')) fail("invalid set operation in character class")
            items += parseClassSetOperandOrRange(open)
        }
        if (atEnd) fail("unterminated character class", open)
        return ClassSetOperation(SetOpKind.UNION, items)
    }

    private fun doubleAhead(c: Char): Boolean = peekOrNull() == c && peekAt(1) == c

    private fun parseClassSetOperandOrRange(open: Int): ClassSetNode {
        val operand = parseClassSetOperand(open)
        if (operand is ClassSetLiteral && peekOrNull() == '-' && peekAt(1) != '-' && peekAt(1) != ']') {
            advance() // '-'
            val hi = parseClassSetOperand(open)
            if (hi !is ClassSetLiteral) fail("invalid character class range")
            if (operand.codePoint > hi.codePoint) fail("range out of order in character class")
            return ClassSetRange(operand.codePoint, hi.codePoint)
        }
        return operand
    }

    private fun parseClassSetOperand(open: Int): ClassSetNode {
        if (atEnd) fail("unterminated character class", open)
        val c = peek()

        if (c == '[') {
            val nestedOpen = pos
            advance()
            val negated = eat('^')
            val body = parseClassSetContents(nestedOpen)
            if (!eat(']')) fail("unterminated character class", nestedOpen)
            return ClassSetNested(body, negated)
        }
        if (c == '\\') return parseClassSetEscape()

        if (isClassSetSyntaxCharacter(c)) fail("invalid character in character class")
        if (doubleAhead(c) && isReservedDoublePunctuator(c)) {
            fail("invalid set operation in character class")
        }
        return ClassSetLiteral(readAtomCodePoint())
    }

    private fun parseClassSetEscape(): ClassSetNode {
        val backslash = pos
        advance() // '\'
        if (atEnd) fail("\\ at end of pattern", backslash)

        return when (val c = peek()) {
            'd', 'D' -> { advance(); ClassSetEscape(EscapeKind.DIGIT, c == 'D') }
            'w', 'W' -> { advance(); ClassSetEscape(EscapeKind.WORD, c == 'W') }
            's', 'S' -> { advance(); ClassSetEscape(EscapeKind.SPACE, c == 'S') }
            'p', 'P' -> {
                val strings = tryParsePropertyOfStrings(negated = c == 'P', backslash)
                if (strings != null) {
                    strings
                } else {
                    val e = parsePropertyEscape(negated = c == 'P') as EscapeClass
                    ClassSetEscape(e.kind, e.negated, e.property)
                }
            }
            'q' -> parseStringDisjunction(backslash)
            'b' -> { advance(); ClassSetLiteral(0x08) } // backspace, as in `u` mode

            // `\0` is the NUL escape when no digit follows; any other digit
            // escape is invalid, since a class holds no backreferences.
            in '0'..'9' -> {
                if (c == '0' && peekAt(1)?.isAsciiDigit() != true) {
                    advance()
                    ClassSetLiteral(0)
                } else {
                    fail("invalid decimal escape", backslash)
                }
            }
            else -> {
                if (isClassSetReservedPunctuator(c)) {
                    advance()
                    ClassSetLiteral(c.code)
                } else {
                    ClassSetLiteral(parseCharacterEscape(inClass = true))
                }
            }
        }
    }

    /** `\q{ab|cd}` — a disjunction of literal strings, which may be empty. */
    private fun parseStringDisjunction(backslash: Int): ClassSetNode {
        advance() // 'q'
        if (!eat('{')) fail("expected '{' after \\q", backslash)

        val strings = mutableListOf<IntArray>()
        var current = mutableListOf<Int>()

        while (true) {
            if (atEnd) fail("unterminated \\q{...}", backslash)
            when (val c = peek()) {
                '}' -> {
                    advance()
                    strings += current.toIntArray()
                    return ClassSetStrings(strings)
                }
                '|' -> {
                    advance()
                    strings += current.toIntArray()
                    current = mutableListOf()
                }
                '\\' -> {
                    advance()
                    if (atEnd) fail("\\ at end of pattern", backslash)
                    val e = peek()
                    current += when {
                        isClassSetReservedPunctuator(e) -> { advance(); e.code }
                        e == 'b' -> { advance(); 0x08 }
                        else -> parseCharacterEscape(inClass = true)
                    }
                }
                else -> {
                    if (isClassSetSyntaxCharacter(c)) fail("invalid character in character class")
                    if (doubleAhead(c) && isReservedDoublePunctuator(c)) {
                        fail("invalid set operation in character class")
                    }
                    current += readAtomCodePoint()
                }
            }
        }
    }

    // ------------------------------------------------------------- resolution

    /**
     * Decides each `\n` now that the real group count is known.
     *
     * Out of range means a legacy octal escape plus literal digits under Annex B,
     * and a SyntaxError under strict ECMA-262 or a Unicode flag.
     */
    private fun resolveNumericBackreferences() {
        for (p in pendingNumeric) {
            if (p.value in 1..groupCount.toLong()) {
                p.node.index = p.value.toInt()
                continue
            }
            if (!annexB) {
                fail("invalid escape: backreference to non-existent group \\${p.digits}", p.at)
            }
            p.node.fallback = legacyOctalCodePoints(p.digits)
        }
    }

    /**
     * Decodes a run of digits per Annex B: a leading octal run (at most three
     * digits when the first is 0-3, otherwise two, so the value stays <= 0o377)
     * becomes one character, and every remaining digit is literal.
     */
    private fun legacyOctalCodePoints(digits: String): IntArray {
        val out = mutableListOf<Int>()
        var i = 0
        if (digits.isNotEmpty() && digits[0] in '0'..'7') {
            val maxDigits = if (digits[0] <= '3') 3 else 2
            var value = 0
            while (i < digits.length && i < maxDigits && digits[i] in '0'..'7') {
                value = value * 8 + (digits[i].code - '0'.code)
                i++
            }
            out += value
        }
        while (i < digits.length) {
            out += digits[i].code
            i++
        }
        return out.toIntArray()
    }

    private fun resolveNamedBackreferences() {
        for (p in pendingNamed) {
            val indices = groupNames[p.name]
                ?: fail("invalid named capture referenced: ${p.name}", p.at)
            p.node.name = p.name
            p.node.index = indices[0]
            if (indices.size > 1) p.node.altIndices = indices.drop(1)
        }
    }

    /**
     * ES2022 allows the same group name in different alternatives of a
     * disjunction, but never twice on one match path.
     *
     * Linear time: a disjunction unions its alternatives' names without conflict,
     * while a sequence reports a conflict if two elements share one. (Enumerating
     * paths instead would be exponential — 30 nested `(a|b)` groups would hang.)
     */
    private fun checkDuplicateNamesOnEachPath(node: Expr): Set<String> = when (node) {
        is Disjunction -> {
            val all = mutableSetOf<String>()
            for (alt in node.alternatives) all += checkDuplicateNamesOnEachPath(alt)
            all
        }
        is Sequence -> {
            val seen = mutableSetOf<String>()
            for (e in node.elements) {
                for (n in checkDuplicateNamesOnEachPath(e)) {
                    if (!seen.add(n)) fail("duplicate capture group name: $n")
                }
            }
            seen
        }
        is Group -> {
            val inner = checkDuplicateNamesOnEachPath(node.body)
            if (node.name == null) {
                inner
            } else {
                if (node.name in inner) fail("duplicate capture group name: ${node.name}")
                inner + node.name
            }
        }
        is NonCapturingGroup -> checkDuplicateNamesOnEachPath(node.body)
        // A modifier group is transparent to group naming, so names inside it
        // still collide with names outside.
        is ModifierGroup -> checkDuplicateNamesOnEachPath(node.body)
        is Lookaround -> checkDuplicateNamesOnEachPath(node.body)
        is Quantifier -> checkDuplicateNamesOnEachPath(node.body)
        else -> emptySet()
    }

    internal companion object {
        const val MAX_NESTING_DEPTH: Int = 200

        /** Stops decimal accumulation well before Long overflow. */
        private const val CLAMP = 1L shl 40
    }
}

// ------------------------------------------------------------------ char utils

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.hexValue(): Int? = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
}

private fun Char.isSyntaxCharacter(): Boolean = when (this) {
    '^', '$', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|' -> true
    else -> false
}

/** `ClassSetSyntaxCharacter`: never a literal inside a `v`-mode class. */
private fun isClassSetSyntaxCharacter(c: Char): Boolean = when (c) {
    '(', ')', '[', ']', '{', '}', '/', '-', '\\', '|' -> true
    else -> false
}

/** `ClassSetReservedPunctuator`: a literal on its own, and escapable. */
private fun isClassSetReservedPunctuator(c: Char): Boolean = when (c) {
    '&', '-', '!', '#', '%', ',', ':', ';', '<', '=', '>', '@', '`', '~' -> true
    else -> false
}

/**
 * Characters that form a `ClassSetReservedDoublePunctuator` when doubled.
 *
 * Reserved so future syntax can use them as operators; `[a&&b]` is intersection
 * while `[a&b]` is a plain union containing `&`.
 */
private fun isReservedDoublePunctuator(c: Char): Boolean = when (c) {
    '&', '!', '#', '$', '%', '*', '+', ',', '.', ':', ';', '<', '=', '>',
    '?', '@', '^', '`', '~',
    -> true
    else -> false
}

private fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 + (v shr 10)).toChar())
        append((0xDC00 + (v and 0x3FF)).toChar())
    }
}
