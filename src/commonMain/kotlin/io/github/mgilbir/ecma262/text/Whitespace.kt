package io.github.mgilbir.ecma262.text

/**
 * `WhiteSpace` and `LineTerminator` from ECMA-262's lexical grammar.
 *
 * Shared, because two separate parts of the specification are defined in terms
 * of it - trimming, and the whitespace a numeric literal may be padded with -
 * and letting them drift apart would be a bug that only shows up in one of
 * them.
 *
 * It is not `Char.isWhitespace()`, which differs in both directions: it strips
 * U+001C to U+001F, the file and record separators, which JavaScript keeps, and
 * it leaves U+FEFF, which JavaScript strips. Five characters, all invisible.
 *
 * Written as escapes: several of these are invisible, and U+FEFF is a byte
 * order mark that tooling likes to eat. All are in the BMP.
 */
internal fun isEcmaWhiteSpace(c: Char): Boolean = when (c) {
    '\u0009', '\u000B', '\u000C', '\u0020', '\u00A0', '\uFEFF' -> true // WhiteSpace
    '\u000A', '\u000D', '\u2028', '\u2029' -> true // LineTerminator
    '\u1680', '\u202F', '\u205F', '\u3000' -> true // remaining Space_Separator
    in '\u2000'..'\u200A' -> true
    else -> false
}

/**
 * `String.prototype.trim` - ECMA-262 22.1.3.32.
 *
 * ```kotlin
 * "x\uFEFF".ecmaTrim()   // "x"
 * "x\uFEFF".trim()       // "x\uFEFF" - Kotlin keeps the byte order mark
 * ```
 *
 * Kotlin's own `trim()` disagrees on five characters, on every target including
 * Kotlin/JS: it keeps U+FEFF where JavaScript strips it, and strips U+001C to
 * U+001F where JavaScript keeps them. Nothing warns; the string is simply a
 * different string.
 *
 * U+200B ZERO WIDTH SPACE and U+0085 NEXT LINE are not whitespace here, however
 * much they look like it.
 */
public fun String.ecmaTrim(): String {
    var start = 0
    var end = length
    while (start < end && isEcmaWhiteSpace(this[start])) start++
    while (end > start && isEcmaWhiteSpace(this[end - 1])) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

/** `String.prototype.trimStart` - ECMA-262 22.1.3.34. See [ecmaTrim]. */
public fun String.ecmaTrimStart(): String {
    var start = 0
    while (start < length && isEcmaWhiteSpace(this[start])) start++
    return if (start == 0) this else substring(start)
}

/** `String.prototype.trimEnd` - ECMA-262 22.1.3.33. See [ecmaTrim]. */
public fun String.ecmaTrimEnd(): String {
    var end = length
    while (end > 0 && isEcmaWhiteSpace(this[end - 1])) end--
    return if (end == length) this else substring(0, end)
}
