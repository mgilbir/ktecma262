package io.github.mgilbir.ecma262.text

/**
 * `WhiteSpace` - ECMA-262 12.2.
 *
 * TAB, VT, FF, ZWNBSP and every Space_Separator: 21 characters. **Line
 * terminators are not among them** - those are a separate production, and
 * [isEcmaLineTerminator] answers for those.
 *
 * The two are kept apart because a tokenizer has to tell them apart rather than
 * merely skip both. A line terminator may not appear in the body of a regular
 * expression literal, and it ends a single-line comment; whitespace does
 * neither. Code that wants both, as trimming does, can ask for both.
 *
 * This is not `Char.isWhitespace()`, which differs in both directions: it
 * accepts U+001C to U+001F, the file and record separators, which JavaScript
 * does not, and it rejects U+FEFF, which JavaScript accepts.
 *
 * Written as escapes: all of these are invisible, and U+FEFF is a byte order
 * mark that tooling likes to eat. All are in the BMP.
 */
public fun isEcmaWhiteSpace(c: Char): Boolean = when (c) {
    '\u0009', '\u000B', '\u000C', '\uFEFF' -> true
    // <USP>, the Space_Separator category.
    '\u0020', '\u00A0', '\u1680', '\u202F', '\u205F', '\u3000' -> true
    in '\u2000'..'\u200A' -> true
    else -> false
}

/**
 * `LineTerminator` - ECMA-262 12.3: LF, CR, LS and PS. Four characters, and
 * disjoint from [isEcmaWhiteSpace].
 *
 * Worth having separately because several rules turn on it alone. One may not
 * appear in the body of a regular expression literal, which is what stops an
 * unterminated `/…/` swallowing the rest of a file; one ends a single-line
 * comment; and automatic semicolon insertion is defined in terms of it.
 *
 * Note that U+0085 NEXT LINE is *not* one, however much it looks like it.
 */
public fun isEcmaLineTerminator(c: Char): Boolean =
    c == '\u000A' || c == '\u000D' || c == '\u2028' || c == '\u2029'

/**
 * The union, which is what trimming and a numeric literal's padding both use.
 *
 * Kept in one place so those two cannot drift apart, and so the split above
 * cannot silently narrow either of them.
 */
internal fun isEcmaTrimmable(c: Char): Boolean = isEcmaWhiteSpace(c) || isEcmaLineTerminator(c)

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
    while (start < end && isEcmaTrimmable(this[start])) start++
    while (end > start && isEcmaTrimmable(this[end - 1])) end--
    return if (start == 0 && end == length) this else substring(start, end)
}

/** `String.prototype.trimStart` - ECMA-262 22.1.3.34. See [ecmaTrim]. */
public fun String.ecmaTrimStart(): String {
    var start = 0
    while (start < length && isEcmaTrimmable(this[start])) start++
    return if (start == 0) this else substring(start)
}

/** `String.prototype.trimEnd` - ECMA-262 22.1.3.33. See [ecmaTrim]. */
public fun String.ecmaTrimEnd(): String {
    var end = length
    while (end > 0 && isEcmaTrimmable(this[end - 1])) end--
    return if (end == length) this else substring(0, end)
}
