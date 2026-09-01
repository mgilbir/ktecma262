package io.github.mgilbir.ecma262.lexer

import io.github.mgilbir.ecma262.text.isEcmaLineTerminator

/**
 * Where a `RegularExpressionLiteral` ends, and what was inside it.
 *
 * [source] and [flags] are what `RegExp.compile` takes; [end] is the index just
 * past the closing flags, so a lexer can carry on from there.
 */
public class RegExpLiteralScan internal constructor(
    public val source: String,
    public val flags: String,
    public val end: Int,
) {
    override fun toString(): String = "RegExpLiteralScan(/$source/$flags, end=$end)"
}

/**
 * Finds the end of a `RegularExpressionLiteral` — ECMA-262 12.9.5.
 *
 * ```kotlin
 * scanRegExpLiteral("x.replace(/ #\\d+$/, '')", 10)  // source=" #\\d+$", flags="", end=18
 * ```
 *
 * Finding the closing delimiter is not a search for the next `/`, which is why
 * this is worth having rather than obvious. Three rules decide it:
 *
 * - a backslash escapes whatever follows, so `/a\/b/` has one literal slash in
 *   the middle and its delimiters at each end;
 * - `[…]` is a character class and a `/` inside one is an ordinary character,
 *   so `/[/]/` is a complete literal matching a slash;
 * - a *LineTerminator* may not appear anywhere in the body, which is what stops
 *   an unterminated literal from swallowing the rest of a file.
 *
 * The body may not be empty and may not begin with a star: two slashes open a
 * line comment and slash-star opens a block one, so the grammar excludes both
 * here rather than leaving it ambiguous. Kotlin will not let this sentence
 * spell them, since a nested comment opener inside KDoc never closes.
 *
 * What this deliberately does **not** decide is whether a `/` at [from] starts a
 * literal at all. That depends on the preceding token — `a / b` is division —
 * and therefore on the host language's grammar rather than on ECMA-262's
 * regular expressions. The caller owns that question.
 *
 * Flags are collected as `IdentifierPartChar*` without being checked; pass them
 * to `RegExp.compile`, which reports an unknown flag properly.
 *
 * @param text the source being lexed.
 * @param from the index of the opening `/`.
 * @return the scan, or null when [from] is not a `/` or no literal ends before
 *   a line terminator or the end of the text.
 */
public fun scanRegExpLiteral(text: String, from: Int): RegExpLiteralScan? {
    if (from < 0 || from >= text.length || text[from] != '/') return null

    var i = from + 1
    var inClass = false
    var first = true

    while (true) {
        if (i >= text.length) return null
        val c = text[i]

        // Nothing in the body may be a line terminator, including the character
        // a backslash escapes and anything inside a class.
        if (isEcmaLineTerminator(c)) return null

        when {
            c == '\\' -> {
                // RegularExpressionBackslashSequence: the escaped character has
                // to exist and has to be a non-terminator.
                if (i + 1 >= text.length) return null
                if (isEcmaLineTerminator(text[i + 1])) return null
                i += 2
            }

            c == '[' && !inClass -> {
                inClass = true
                i++
            }

            c == ']' && inClass -> {
                inClass = false
                i++
            }

            c == '/' && !inClass -> {
                // An empty body is `//`, which is a comment.
                if (first) return null
                val source = text.substring(from + 1, i)
                val flagsStart = i + 1
                var j = flagsStart
                while (j < text.length && isFlagChar(text[j])) j++
                return RegExpLiteralScan(source, text.substring(flagsStart, j), j)
            }

            // A star here would open a block comment, so it cannot open a
            // literal either.
            first && c == '*' -> return null

            else -> i++
        }
        first = false
    }
}

/**
 * `IdentifierPartChar`, restricted to what a flag can be.
 *
 * The grammar allows any identifier part here, so `/x/gq` scans and then fails
 * to compile — which is the right division of labour, since this is about
 * delimiters and `RegExp.compile` is about validity.
 */
private fun isFlagChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '$' || c == '_'
