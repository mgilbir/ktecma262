package io.github.mgilbir.ecma262.number

import kotlin.math.ceil
import kotlin.math.log10

/**
 * `Number::toString(x, 10)` — ECMA-262 6.1.6.1.20.
 *
 * This is what JavaScript prints for a number: `String(x)`, string
 * interpolation, and the numbers `JSON.stringify` emits.
 *
 * This function returns the same string on every target. What differs is
 * Kotlin's own `Double.toString()`, which is why this exists: on JVM and Native
 * it prints `1.0`, `1.0E21` and `4.9E-324` where JavaScript prints `1`, `1e+21`
 * and `5e-324`. On Kotlin/JS it happens to agree, because there a `Double` is a
 * JavaScript number — but relying on that would make the answer depend on where
 * the code runs, which is the problem rather than the solution.
 *
 * The specification defines the result rather than an algorithm: pick integers
 * n, k and s where 10^(k-1) <= s < 10^k and s * 10^(n-k) is exactly this
 * double, with **k as small as possible**; among equally short candidates take
 * the one closest to the double, and on a tie the even one. Then lay the digits
 * out according to where the decimal point falls.
 *
 * "As small as possible" is the whole problem. This uses the exact rational
 * method of Steele & White, in the form Burger & Dybvig gave it: represent the
 * value and the gaps to its two neighbours as exact fractions and emit digits
 * until what remains is unambiguous. It needs big integers and no lookup
 * tables, so every step is checkable by reading it — which is the right trade
 * for a first implementation. Faster table-driven methods (Ryu, Schubfach) can
 * replace the digit generation later without touching the layout rules, and
 * `NumberToStringPropertyTest` is written to hold either of them to the
 * specification directly rather than to this algorithm.
 *
 * @return the digits JavaScript would print, exactly.
 */
public fun Double.toEcmaString(): String {
    if (isNaN()) return "NaN"
    // Catches -0.0 too: the specification prints "0" for both zeroes.
    if (this == 0.0) return "0"
    if (this < 0.0) return "-" + (-this).toEcmaString()
    if (isInfinite()) return "Infinity"

    val (digits, pointPosition) = shortestDigits(this)
    return layOut(digits, pointPosition)
}

/**
 * The shortest digit string that round-trips, and where the decimal point goes.
 *
 * Returns the significant digits with no leading or trailing zeros, paired with
 * the specification's `n`: the value is `0.<digits> * 10^n`.
 *
 * Grisu3 answers in 64-bit arithmetic for most values and declines when its
 * accumulated rounding error could change the result; those fall through to the
 * exact method below. Correctness therefore does not depend on the fast path
 * being right about *which* values are hard — only on its refusing to answer
 * when it is unsure.
 */
internal fun shortestDigits(value: Double): Pair<String, Int> =
    grisu3ShortestDigits(value) ?: exactShortestDigits(value)

/**
 * The exact algorithm: always right, and the arbiter whenever the fast path
 * declines to answer.
 */
internal fun exactShortestDigits(value: Double): Pair<String, Int> {
    val bits = value.toRawBits()
    val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
    val mantissa = bits and 0x000FFFFFFFFFFFFFL

    // A subnormal has no implicit leading one and a fixed exponent.
    val significand = if (biasedExponent == 0) mantissa else mantissa or (1L shl 52)
    val exponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075

    // Round-half-to-even: when the significand is even, a decimal sitting
    // exactly on the boundary still resolves to this double, so the interval
    // that round-trips is closed rather than open.
    val boundaryIncluded = (significand and 1L) == 0L

    // The gap to the neighbour below is half the usual one when this double is
    // a power of two, because the exponent decreases across that boundary. The
    // smallest normal is the exception: below it the spacing stays the same.
    val asymmetric = mantissa == 0L && biasedExponent > 1

    // r/s is the value; mPlus/s and mMinus/s are the distances to the midpoints
    // between it and its two neighbours.
    val r = Big()
    val s = Big()
    val mPlus = Big()
    val mMinus = Big()

    if (exponent >= 0) {
        r.setLong(significand)
        if (asymmetric) {
            r.shiftLeft(exponent + 2)
            s.setLong(4)
            mPlus.setLong(1); mPlus.shiftLeft(exponent + 1)
            mMinus.setLong(1); mMinus.shiftLeft(exponent)
        } else {
            r.shiftLeft(exponent + 1)
            s.setLong(2)
            mPlus.setLong(1); mPlus.shiftLeft(exponent)
            mMinus.setLong(1); mMinus.shiftLeft(exponent)
        }
    } else {
        r.setLong(significand)
        if (asymmetric) {
            r.shiftLeft(2)
            s.setLong(1); s.shiftLeft(2 - exponent)
            mPlus.setLong(2)
            mMinus.setLong(1)
        } else {
            r.shiftLeft(1)
            s.setLong(1); s.shiftLeft(1 - exponent)
            mPlus.setLong(1)
            mMinus.setLong(1)
        }
    }

    val scratch = Big()

    fun aboveOne(): Boolean {
        scratch.copyFrom(r)
        scratch.addTo(mPlus)
        val c = scratch.compareTo(s)
        return if (boundaryIncluded) c >= 0 else c > 0
    }

    // Scale so that 0.1 <= value < 1, counting the powers of ten taken out.
    //
    // Jump most of the way with an estimate first. ceil(log10(v)) is the number
    // of digits before the point, and it is only ever off by one or so, which
    // the loops below then correct — they are the authority, the estimate is
    // only there to save up to 330 passes over a thousand-bit number. Guarding
    // correctness with a cheap floating-point guess would be a bad trade; using
    // it as a starting point costs nothing.
    var pointPosition = 0
    val estimate = ceil(log10(value)).toInt()
    if (estimate > 0) {
        s.mulPow10(estimate)
        pointPosition = estimate
    } else if (estimate < 0) {
        r.mulPow10(-estimate)
        mPlus.mulPow10(-estimate)
        mMinus.mulPow10(-estimate)
        pointPosition = estimate
    }

    while (aboveOne()) {
        s.mulSmall(10)
        pointPosition++
    }
    while (true) {
        scratch.copyFrom(r)
        scratch.addTo(mPlus)
        scratch.mulSmall(10)
        val c = scratch.compareTo(s)
        val stillBelow = if (boundaryIncluded) c < 0 else c <= 0
        if (!stillBelow) break
        r.mulSmall(10)
        mPlus.mulSmall(10)
        mMinus.mulSmall(10)
        pointPosition--
    }

    // Emit digits until the remainder no longer distinguishes this double from
    // its neighbours — that is exactly the specification's "k as small as
    // possible".
    val digits = StringBuilder(24)
    while (true) {
        r.mulSmall(10)
        mPlus.mulSmall(10)
        mMinus.mulSmall(10)

        // The remainder is kept below the divisor, so the quotient is a single
        // digit and repeated subtraction finds it without any division.
        var digit = 0
        while (r.compareTo(s) >= 0) {
            r.subFrom(s)
            digit++
        }

        val cLow = r.compareTo(mMinus)
        val roundDownCloses = if (boundaryIncluded) cLow <= 0 else cLow < 0
        scratch.copyFrom(r)
        scratch.addTo(mPlus)
        val cHigh = scratch.compareTo(s)
        val roundUpCloses = if (boundaryIncluded) cHigh >= 0 else cHigh > 0

        if (!roundDownCloses && !roundUpCloses) {
            digits.append('0' + digit)
            continue
        }
        val finalDigit = when {
            roundDownCloses && !roundUpCloses -> digit
            roundUpCloses && !roundDownCloses -> digit + 1
            else -> {
                // Both neighbours are in reach, so take the closer one. On an
                // exact tie the specification asks for the even significand,
                // and since the two candidates are consecutive integers exactly
                // one of them is even.
                //
                // Rounding down on the tie instead costs 48 values in 231,948 —
                // and neither round-tripping nor shortestness can see the
                // difference, because both candidates satisfy both.
                scratch.copyFrom(r)
                scratch.mulSmall(2)
                when {
                    scratch.compareTo(s) < 0 -> digit
                    scratch.compareTo(s) > 0 -> digit + 1
                    digit % 2 == 0 -> digit
                    else -> digit + 1
                }
            }
        }
        // Rounding the last digit up to ten would need a carry into digits
        // already emitted. The scaling above makes that impossible, so treat it
        // as a bug rather than silently writing a ':'.
        check(finalDigit in 0..9) { "digit carry out of range: $finalDigit" }
        digits.append('0' + finalDigit)
        break
    }

    return digits.toString() to pointPosition
}

/**
 * Steps 5 to 10 of 6.1.6.1.20: where the decimal point goes.
 *
 * These thresholds are the visible difference from every other language's
 * default formatting. JavaScript stays positional out to 10^21 and in to
 * 10^-6; the JVM switches to scientific notation at 10^7 and 10^-3.
 */
private fun layOut(digits: String, n: Int): String {
    val k = digits.length
    return when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val e = n - 1
            val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
            val sign = if (e >= 0) "+" else "-"
            mantissa + "e" + sign + (if (e >= 0) e else -e)
        }
    }
}
