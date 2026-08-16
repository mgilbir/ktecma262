package io.github.mgilbir.ecma262.number

import io.github.mgilbir.ecma262.text.isEcmaWhiteSpace

/**
 * `StringToNumber` — ECMA-262 7.1.4.1.1, the conversion `Number("…")` performs.
 *
 * The inverse of [toEcmaString], and stricter than it looks. This is not
 * `parseFloat`: the whole string has to be a numeric literal, so `"12abc"` is
 * `NaN` rather than `12`. Leading and trailing whitespace is allowed, an empty
 * or all-whitespace string is `+0`, and `0x`/`0o`/`0b` integer literals are
 * accepted but may not carry a sign.
 *
 * The result is correctly rounded: the returned double is the one nearest the
 * exact decimal value, with ties going to the even significand. Getting that
 * right needs exact arithmetic, because the decision can turn on the 767th
 * significant digit.
 *
 * ### Bounds
 *
 * Correctly rounded decimal parsing has a history of hangs — a decimal that sits
 * exactly on a rounding boundary is the classic denial-of-service input, and
 * both Java and PHP have shipped versions that looped forever on one. Every
 * loop here is bounded before any large arithmetic starts:
 *
 * - significant digits are capped, with anything beyond the cap folded into a
 *   sticky flag that can only affect an exact tie;
 * - the exponent is clamped while it is being parsed, so a string of a million
 *   exponent digits cannot overflow or drive the scaling;
 * - values obviously outside the double range short-circuit to infinity or zero
 *   before any big integer is built.
 *
 * @return the double `Number(this)` produces, including `NaN` for anything that
 *   is not a numeric literal.
 */
public fun String.toEcmaDouble(): Double {
    var start = 0
    var end = length
    while (start < end && isEcmaWhiteSpace(this[start])) start++
    while (end > start && isEcmaWhiteSpace(this[end - 1])) end--

    // StringNumericLiteral ::: StrWhiteSpace_opt — an empty literal is +0.
    if (start == end) return 0.0

    // NonDecimalIntegerLiteral takes no sign, so this is checked before one.
    if (end - start > 2 && this[start] == '0') {
        val radix = when (this[start + 1]) {
            'x', 'X' -> 16
            'o', 'O' -> 8
            'b', 'B' -> 2
            else -> 0
        }
        if (radix != 0) return parseRadix(this, start + 2, end, radix)
    }

    var i = start
    var negative = false
    if (i < end && (this[i] == '+' || this[i] == '-')) {
        negative = this[i] == '-'
        i++
    }

    if (matchesAt(this, i, end, "Infinity")) {
        return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }

    return parseDecimal(this, i, end, negative)
}

private fun matchesAt(s: String, from: Int, end: Int, word: String): Boolean {
    if (end - from != word.length) return false
    for (k in word.indices) if (s[from + k] != word[k]) return false
    return true
}

private fun digitValue(c: Char, radix: Int): Int {
    val v = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> return -1
    }
    return if (v < radix) v else -1
}

/** `0x…`, `0o…` and `0b…`, which are always non-negative integers. */
private fun parseRadix(s: String, from: Int, end: Int, radix: Int): Double {
    if (from >= end) return Double.NaN
    val magnitude = Big(Big.PARSE_LIMBS)
    var significantSeen = 0
    var sticky = false
    for (k in from until end) {
        val d = digitValue(s[k], radix)
        if (d < 0) return Double.NaN
        if (significantSeen == 0 && d == 0) continue // leading zeros
        // A literal far wider than a double can hold only needs enough leading
        // digits to round; the rest becomes sticky.
        if (significantSeen < MAX_SIGNIFICANT_BITS) {
            magnitude.mulSmall(radix)
            magnitude.addSmall(d)
            significantSeen++
        } else {
            if (d != 0) sticky = true
            // Every further digit scales the value by the radix.
            magnitude.mulSmall(radix)
            significantSeen++
        }
        if (significantSeen > MAX_RADIX_DIGITS) return Double.POSITIVE_INFINITY
    }
    if (magnitude.isZero()) return 0.0
    val one = Big(Big.PARSE_LIMBS)
    one.setLong(1)
    return roundToDouble(magnitude, one, sticky)
}

private fun parseDecimal(s: String, from: Int, end: Int, negative: Boolean): Double {
    val digits = StringBuilder(32)
    var exponent = 0
    var sticky = false
    var sawDigit = false
    var i = from

    fun push(c: Char, fraction: Boolean) {
        if (digits.length < MAX_SIGNIFICANT_DIGITS) {
            if (digits.isEmpty() && c == '0') {
                // A leading zero contributes nothing but position.
                if (fraction) exponent--
            } else {
                digits.append(c)
                if (fraction) exponent--
            }
        } else {
            // Past the cap the digit cannot change which double is nearest,
            // except by breaking an exact tie — which is what sticky records.
            if (c != '0') sticky = true
            if (!fraction) exponent++
        }
    }

    while (i < end && s[i] in '0'..'9') {
        sawDigit = true
        push(s[i], fraction = false)
        i++
    }
    if (i < end && s[i] == '.') {
        i++
        while (i < end && s[i] in '0'..'9') {
            sawDigit = true
            push(s[i], fraction = true)
            i++
        }
    }
    if (!sawDigit) return Double.NaN

    if (i < end && (s[i] == 'e' || s[i] == 'E')) {
        i++
        var expNegative = false
        if (i < end && (s[i] == '+' || s[i] == '-')) {
            expNegative = s[i] == '-'
            i++
        }
        if (i >= end || s[i] !in '0'..'9') return Double.NaN
        var value = 0
        while (i < end && s[i] in '0'..'9') {
            // Clamped as it is read, so a million exponent digits cannot
            // overflow or reach the scaling below.
            if (value < EXPONENT_CLAMP) value = value * 10 + (s[i] - '0')
            i++
        }
        exponent += if (expNegative) -value else value
    }

    // Anything left over means this was not a numeric literal.
    if (i != end) return Double.NaN

    // Trailing zeros are magnitude, not precision; moving them into the exponent
    // keeps the big integer smaller.
    var length = digits.length
    while (length > 0 && digits[length - 1] == '0') {
        length--
        exponent++
    }
    if (length == 0) return if (negative) -0.0 else 0.0
    digits.setLength(length)

    // Short-circuit anything that cannot land inside the double range, before
    // building a big integer for it. The margins are far wider than the real
    // limits (about 10^308 and 10^-324), so no borderline value is decided here.
    val magnitudeExponent = exponent + length
    if (magnitudeExponent > 400) {
        return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }
    if (magnitudeExponent < -400) return if (negative) -0.0 else 0.0

    val numerator = Big(Big.PARSE_LIMBS)
    val denominator = Big(Big.PARSE_LIMBS)
    setFromDigits(numerator, digits)
    denominator.setLong(1)
    if (exponent >= 0) numerator.mulPow10(exponent) else denominator.mulPow10(-exponent)

    val magnitude = roundToDouble(numerator, denominator, sticky)
    return if (negative) -magnitude else magnitude
}

private fun setFromDigits(target: Big, digits: CharSequence) {
    target.setLong(0)
    var i = 0
    while (i < digits.length) {
        val take = minOf(9, digits.length - i)
        var chunk = 0
        var scale = 1
        repeat(take) {
            chunk = chunk * 10 + (digits[i + it] - '0')
            scale *= 10
        }
        target.mulSmall(scale)
        target.addSmall(chunk)
        i += take
    }
}

/**
 * The double nearest `numerator / denominator`, rounding ties to even.
 *
 * [sticky] means the true value is a little larger than the fraction given —
 * digits were dropped past the cap. It can only matter on an exact tie, where
 * it breaks upward.
 */
private fun roundToDouble(numerator: Big, denominator: Big, sticky: Boolean): Double {
    if (numerator.isZero()) return 0.0

    // Locate the binary exponent: the e with 2^e <= value < 2^(e+1).
    var e = numerator.bitLength() - denominator.bitLength()
    val probeNumerator = Big(Big.PARSE_LIMBS)
    val probeDenominator = Big(Big.PARSE_LIMBS)
    probeNumerator.copyFrom(numerator)
    probeDenominator.copyFrom(denominator)
    if (e >= 0) probeDenominator.shiftLeft(e) else probeNumerator.shiftLeft(-e)
    if (probeNumerator.compareTo(probeDenominator) < 0) e--

    if (e > 1024) return Double.POSITIVE_INFINITY
    if (e < -1200) return 0.0

    // A normal keeps 53 significant bits; a subnormal is a fixed number of
    // halvings below 2^-1022, so it is scaled to whole units of 2^-1074.
    val shift = if (e >= -1022) 52 - e else 1074

    val scaledNumerator = Big(Big.PARSE_LIMBS)
    val scaledDenominator = Big(Big.PARSE_LIMBS)
    scaledNumerator.copyFrom(numerator)
    scaledDenominator.copyFrom(denominator)
    if (shift >= 0) scaledNumerator.shiftLeft(shift) else scaledDenominator.shiftLeft(-shift)

    val remainder = Big(Big.PARSE_LIMBS)
    var significand = divideBelow2Pow54(scaledNumerator, scaledDenominator, remainder)

    // Round: compare twice the remainder with the divisor.
    remainder.mulSmall(2)
    val comparison = remainder.compareTo(scaledDenominator)
    val roundUp = when {
        comparison > 0 -> true
        comparison < 0 -> false
        // An exact tie: the dropped digits, if any, put the true value above it.
        sticky -> true
        else -> (significand and 1L) == 1L
    }
    if (roundUp) significand++

    var exponent = e
    if (significand == 1L shl 53) {
        // Rounding carried into a new binary exponent.
        significand = significand shr 1
        exponent++
    }

    if (exponent < -1022) {
        // Subnormal: the significand is already in units of 2^-1074, and a
        // value that rounded up to 2^52 is exactly the smallest normal, which
        // this bit pattern spells correctly.
        return Double.fromBits(significand)
    }
    if (exponent > 1023) return Double.POSITIVE_INFINITY
    val biased = (exponent + 1023).toLong()
    return Double.fromBits((biased shl 52) or (significand and 0x000FFFFFFFFFFFFFL))
}

/**
 * Quotient of two big integers, where the caller has arranged for it to fit in
 * 54 bits, with the remainder left in [remainder].
 *
 * Shift-and-subtract from the top: 54 passes, no general division needed.
 */
private fun divideBelow2Pow54(numerator: Big, denominator: Big, remainder: Big): Long {
    remainder.copyFrom(numerator)
    val step = Big(Big.PARSE_LIMBS)
    step.copyFrom(denominator)
    step.shiftLeft(53)
    var quotient = 0L
    for (bit in 53 downTo 0) {
        if (remainder.compareTo(step) >= 0) {
            remainder.subFrom(step)
            quotient = quotient or (1L shl bit)
        }
        step.shiftRight(1)
    }
    return quotient
}

/**
 * Enough significant digits to decide any rounding: the worst case for a double
 * needs 767, and past that only an exact tie is still in question, which the
 * sticky flag settles.
 */
private const val MAX_SIGNIFICANT_DIGITS = 800

/** The same bound for radix literals, counted in digits rather than decimals. */
private const val MAX_SIGNIFICANT_BITS = 800

/** A radix literal wider than this cannot be finite. */
private const val MAX_RADIX_DIGITS = 400_000

/** Any exponent past this is already out of range; clamped so it cannot overflow. */
private const val EXPONENT_CLAMP = 100_000_000
