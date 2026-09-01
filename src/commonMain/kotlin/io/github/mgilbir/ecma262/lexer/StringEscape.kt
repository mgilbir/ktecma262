package io.github.mgilbir.ecma262.lexer

import io.github.mgilbir.ecma262.text.isEcmaLineTerminator

/**
 * What one escape sequence produced, and where it ended.
 *
 * [text] is empty for a *LineContinuation*, where the backslash and the line
 * break both vanish; [end] is the index just past the escape.
 */
public class EscapeDecoding internal constructor(
    public val text: String,
    public val end: Int,
) {
    override fun toString(): String = "EscapeDecoding(text=$text, end=$end)"
}

/**
 * Decodes one `EscapeSequence` or `LineContinuation` - ECMA-262 12.9.4.
 *
 * Offset-based rather than whole-string unescaping, because a lexer is already
 * walking the source and needs to know where the escape ended.
 *
 * Three rules are easy to get wrong from memory, and each is why this is worth
 * having rather than obvious:
 *
 * - a zero escape is NUL only when no digit follows it, so a zero followed by
 *   a one is not "NUL then 1" but a legacy octal escape.
 * - a braced unicode escape may denote a supplementary code point, which has
 *   to come back as a surrogate pair.
 * - a *LineContinuation* consumes CR LF as **one** line break, not two, and
 *   produces nothing at all.
 *
 * Anything not otherwise special is an identity escape, the `NonEscapeCharacter`
 * production. That is why a table is needed at all: it distinguishes the `v`
 * escape, which is U+000B, from an `a` escape, which is just the letter.
 *
 * Legacy octal escapes are **not** decoded. They are Annex B, permitted only in
 * sloppy mode and a SyntaxError in strict code and modules, so this reports
 * them as invalid rather than guessing the caller's dialect.
 *
 * @param source the text being lexed.
 * @param backslashAt the index of the backslash.
 * @return the decoding, or null when [backslashAt] is not a backslash, the
 *   escape is truncated, or it is not a valid escape.
 */
public fun decodeEscapeSequence(source: String, backslashAt: Int): EscapeDecoding? {
    if (backslashAt < 0 || backslashAt >= source.length || source[backslashAt] != '\\') return null
    val at = backslashAt + 1
    if (at >= source.length) return null

    val c = source[at]

    // LineContinuation: both characters disappear, and CR LF counts as one.
    if (isEcmaLineTerminator(c)) {
        val isCrLf = c == '\u000D' && at + 1 < source.length && source[at + 1] == '\u000A'
        return EscapeDecoding("", at + if (isCrLf) 2 else 1)
    }

    return when (c) {
        'b' -> EscapeDecoding("\u0008", at + 1)
        'f' -> EscapeDecoding("\u000C", at + 1)
        'n' -> EscapeDecoding("\u000A", at + 1)
        'r' -> EscapeDecoding("\u000D", at + 1)
        't' -> EscapeDecoding("\u0009", at + 1)
        'v' -> EscapeDecoding("\u000B", at + 1)

        // NUL, but only when it does not begin a longer digit sequence: a zero
        // followed by a digit is a legacy octal escape, not decoded here.
        '0' -> if (at + 1 < source.length && source[at + 1] in '0'..'9') {
            null
        } else {
            EscapeDecoding("\u0000", at + 1)
        }

        // The remaining digits are legacy octal, or plainly invalid for 8 and 9.
        in '1'..'9' -> null

        'x' -> {
            val value = hexValue(source, at + 1, 2) ?: return null
            EscapeDecoding(value.toChar().toString(), at + 3)
        }

        'u' -> decodeUnicodeEscape(source, at)

        // NonEscapeCharacter: anything else stands for itself.
        else -> EscapeDecoding(c.toString(), at + 1)
    }
}

/** Four hex digits, or a braced code point. */
private fun decodeUnicodeEscape(source: String, uAt: Int): EscapeDecoding? {
    val after = uAt + 1
    if (after < source.length && source[after] == '{') {
        var i = after + 1
        var value = 0
        var digits = 0
        while (i < source.length && source[i] != '}') {
            val d = hexDigit(source[i]) ?: return null
            value = value * 16 + d
            // Stop as soon as it cannot be a code point, so a long run of
            // digits cannot overflow on the way to being rejected.
            if (value > 0x10FFFF) return null
            digits++
            i++
        }
        if (digits == 0 || i >= source.length) return null
        return EscapeDecoding(codePointToString(value), i + 1)
    }
    val value = hexValue(source, after, 4) ?: return null
    return EscapeDecoding(value.toChar().toString(), after + 4)
}

private fun hexDigit(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}

private fun hexValue(source: String, from: Int, count: Int): Int? {
    if (from + count > source.length) return null
    var value = 0
    for (i in from until from + count) {
        value = value * 16 + (hexDigit(source[i]) ?: return null)
    }
    return value
}

private fun codePointToString(codePoint: Int): String {
    if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
    val v = codePoint - 0x10000
    return charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
        .concatToString()
}
