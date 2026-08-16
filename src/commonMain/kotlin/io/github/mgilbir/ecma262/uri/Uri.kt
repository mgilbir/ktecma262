package io.github.mgilbir.ecma262.uri

/**
 * Thrown where JavaScript throws a `URIError`.
 *
 * A subclass of [IllegalArgumentException] so it can be caught either way.
 */
public class UriError(message: String) : IllegalArgumentException(message)

/**
 * `encodeURIComponent` — ECMA-262 19.2.6.4.
 *
 * Escapes everything except the unreserved set, so the result is safe to place
 * in any single part of a URI:
 *
 * ```kotlin
 * "a b+c/d?e=f&g".encodeUriComponent()  // "a%20b%2Bc%2Fd%3Fe%3Df%26g"
 * "~!*'()-_.".encodeUriComponent()      // "~!*'()-_." - all unreserved
 * ```
 *
 * This is **not** `java.net.URLEncoder.encode`, which implements
 * `application/x-www-form-urlencoded`: that writes a space as `+`, escapes `~`,
 * `!`, `*`, `'`, `(` and `)`, and is only correct for form bodies. Reaching for
 * it to build a URI is a standing source of subtly wrong links.
 *
 * @throws UriError if the string contains an unpaired surrogate, which has no
 *   UTF-8 encoding.
 */
public fun String.encodeUriComponent(): String = encode(this, UNESCAPED)

/**
 * `encodeURI` — ECMA-262 19.2.6.3.
 *
 * Leaves the characters that separate a URI's parts intact, so a whole URI
 * survives:
 *
 * ```kotlin
 * "http://x.com/a b?c=d&e#f".encodeUri()  // "http://x.com/a%20b?c=d&e#f"
 * ```
 *
 * @throws UriError if the string contains an unpaired surrogate.
 */
public fun String.encodeUri(): String = encode(this, UNESCAPED + RESERVED + "#")

/**
 * `decodeURIComponent` — ECMA-262 19.2.6.2.
 *
 * @throws UriError if an escape is malformed, or decodes to something that is
 *   not a well-formed UTF-8 sequence for a single code point.
 */
public fun String.decodeUriComponent(): String = decode(this, "")

/**
 * `decodeURI` — ECMA-262 19.2.6.1.
 *
 * The inverse of [encodeUri]: escapes that stand for reserved characters are
 * **left as escapes**, because decoding them would change how the URI parses.
 *
 * ```kotlin
 * "http://x.com/a%20b%2Fc".decodeUri()  // "http://x.com/a b%2Fc"
 * ```
 *
 * @throws UriError if an escape is malformed or decodes to an ill-formed
 *   sequence.
 */
public fun String.decodeUri(): String = decode(this, RESERVED + "#")

/** `uriReserved` — the characters that structure a URI. */
private const val RESERVED = ";/?:@&=+$,"

/** `uriUnescaped` — alphanumerics plus `uriMark`. */
private const val UNESCAPED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" + "-_.!~*'()"

private const val HEX = "0123456789ABCDEF"

private fun encode(text: String, keep: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.code < 0x80 && keep.indexOf(c) >= 0) {
            out.append(c)
            i++
            continue
        }
        // Escaping is defined over code points, so a surrogate pair is one
        // unit; a surrogate on its own has no UTF-8 form and is an error.
        val codePoint: Int
        if (c.isHighSurrogate()) {
            val next = if (i + 1 < text.length) text[i + 1] else null
            if (next == null || !next.isLowSurrogate()) {
                throw UriError("URI malformed: unpaired high surrogate at $i")
            }
            codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (next.code - 0xDC00)
            i += 2
        } else if (c.isLowSurrogate()) {
            throw UriError("URI malformed: unpaired low surrogate at $i")
        } else {
            codePoint = c.code
            i++
        }
        appendUtf8Escapes(out, codePoint)
    }
    return out.toString()
}

private fun appendUtf8Escapes(out: StringBuilder, codePoint: Int) {
    fun byte(value: Int) {
        out.append('%').append(HEX[(value ushr 4) and 0xF]).append(HEX[value and 0xF])
    }
    when {
        codePoint < 0x80 -> byte(codePoint)
        codePoint < 0x800 -> {
            byte(0xC0 or (codePoint ushr 6))
            byte(0x80 or (codePoint and 0x3F))
        }
        codePoint < 0x10000 -> {
            byte(0xE0 or (codePoint ushr 12))
            byte(0x80 or ((codePoint ushr 6) and 0x3F))
            byte(0x80 or (codePoint and 0x3F))
        }
        else -> {
            byte(0xF0 or (codePoint ushr 18))
            byte(0x80 or ((codePoint ushr 12) and 0x3F))
            byte(0x80 or ((codePoint ushr 6) and 0x3F))
            byte(0x80 or (codePoint and 0x3F))
        }
    }
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
}

private fun decode(text: String, preserve: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        val first = readByte(text, i)
        if (first < 0x80) {
            // A reserved character must stay escaped: decoding it here would
            // change where the URI's parts begin and end.
            if (preserve.indexOf(first.toChar()) >= 0) {
                out.append(text, i, i + 3)
            } else {
                out.append(first.toChar())
            }
            i += 3
            continue
        }

        // Multi-byte: the leading byte says how many continuation bytes follow.
        val extra = when {
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            else -> throw UriError("URI malformed: invalid UTF-8 lead byte at $i")
        }
        var codePoint = when (extra) {
            1 -> first and 0x1F
            2 -> first and 0x0F
            else -> first and 0x07
        }
        var at = i + 3
        repeat(extra) {
            val continuation = readByte(text, at)
            if (continuation and 0xC0 != 0x80) {
                throw UriError("URI malformed: invalid UTF-8 continuation at $at")
            }
            codePoint = (codePoint shl 6) or (continuation and 0x3F)
            at += 3
        }

        // Reject what UTF-8 forbids: an overlong form, a surrogate code point,
        // or anything past the last code point. Accepting these is how a
        // decoder becomes a way to smuggle characters past a filter.
        val minimum = when (extra) {
            1 -> 0x80
            2 -> 0x800
            else -> 0x10000
        }
        if (codePoint < minimum) throw UriError("URI malformed: overlong encoding at $i")
        if (codePoint in 0xD800..0xDFFF) {
            throw UriError("URI malformed: surrogate code point at $i")
        }
        if (codePoint > 0x10FFFF) throw UriError("URI malformed: code point out of range at $i")

        if (codePoint < 0x10000) {
            out.append(codePoint.toChar())
        } else {
            val v = codePoint - 0x10000
            out.append((0xD800 + (v ushr 10)).toChar())
            out.append((0xDC00 + (v and 0x3FF)).toChar())
        }
        i = at
    }
    return out.toString()
}

/** Reads the `%XX` at [at], or reports why it is not one. */
private fun readByte(text: String, at: Int): Int {
    if (at + 2 >= text.length) throw UriError("URI malformed: truncated escape at $at")
    if (text[at] != '%') throw UriError("URI malformed: expected an escape at $at")
    val high = hexValue(text[at + 1])
    val low = hexValue(text[at + 2])
    if (high < 0 || low < 0) throw UriError("URI malformed: bad hex digits at $at")
    return (high shl 4) or low
}
