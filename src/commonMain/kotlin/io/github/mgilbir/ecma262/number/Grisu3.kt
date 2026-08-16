package io.github.mgilbir.ecma262.number

/**
 * Grisu3 — the fast path for [shortestDigits], after Loitsch.
 *
 * The exact algorithm carries thousand-bit integers so it is always right. This
 * does the same job in 64-bit arithmetic against a table of cached powers of
 * ten, which is roughly two orders of magnitude cheaper, but it is only an
 * approximation: it tracks how much uncertainty its rounding has accumulated
 * and **reports failure** when that uncertainty could change the answer.
 *
 * So this is a fast path with a proof obligation attached rather than a
 * replacement. When it returns null the caller falls back to the exact
 * algorithm, which is why a bug here that produces a *doubtful* answer is
 * merely slow. A bug that produces a confident wrong answer is a real bug — and
 * that is what the differential fixture over 231,948 values, and the
 * round-trip and shortestness properties, are there to catch.
 *
 * Everything is unsigned. `ULong` rather than `Long` so the comparisons and
 * shifts carry the right meaning without hand-written helpers.
 */

/** A significand and a binary exponent: the value is `f * 2^e`. */
private class DiyFp(var f: ULong, var e: Int)

private const val SIGNIFICAND_BITS = 64

/**
 * The window the scaled exponent must land in.
 *
 * Wide enough that a cached power always exists, narrow enough that the
 * integral part of the scaled value fits in 32 bits.
 */
private const val MIN_TARGET_EXPONENT = -60
private const val MAX_TARGET_EXPONENT = -32

private const val HIDDEN_BIT = 0x0010000000000000uL
private const val SIGNIFICAND_MASK = 0x000FFFFFFFFFFFFFuL
private const val EXPONENT_BIAS = 0x3FF + 52
private const val DENORMAL_EXPONENT = -EXPONENT_BIAS + 1

/** Product of two `DiyFp`s, keeping the top 64 bits and rounding. */
private fun multiply(a: DiyFp, b: DiyFp): DiyFp {
    val mask32 = 0xFFFFFFFFuL
    val aHigh = a.f shr 32
    val aLow = a.f and mask32
    val bHigh = b.f shr 32
    val bLow = b.f and mask32
    val highHigh = aHigh * bHigh
    val highLow = aHigh * bLow
    val lowHigh = aLow * bHigh
    val lowLow = aLow * bLow
    // Round the discarded half rather than truncating it: this is where the
    // accumulated error the caller checks against comes from.
    var mid = (lowLow shr 32) + (highLow and mask32) + (lowHigh and mask32)
    mid += 1uL shl 31
    val f = highHigh + (highLow shr 32) + (lowHigh shr 32) + (mid shr 32)
    return DiyFp(f, a.e + b.e + SIGNIFICAND_BITS)
}

private fun normalize(fp: DiyFp): DiyFp {
    var f = fp.f
    var e = fp.e
    while ((f and (1uL shl 63)) == 0uL) {
        f = f shl 1
        e--
    }
    return DiyFp(f, e)
}

/**
 * The midpoints between [value] and each of its neighbours, scaled to a common
 * exponent.
 *
 * A double that is a power of two sits closer to its predecessor than to its
 * successor, because the exponent decreases across that boundary. The smallest
 * normal is the exception, since the spacing does not change below it.
 */
private fun normalizedBoundaries(value: Double): Pair<DiyFp, DiyFp> {
    val bits = value.toRawBits().toULong()
    val biasedExponent = ((bits shr 52) and 0x7FFuL).toInt()
    val significandBits = bits and SIGNIFICAND_MASK
    val significand: ULong
    val exponent: Int
    if (biasedExponent != 0) {
        significand = significandBits + HIDDEN_BIT
        exponent = biasedExponent - EXPONENT_BIAS
    } else {
        significand = significandBits
        exponent = DENORMAL_EXPONENT
    }

    val plus = normalize(DiyFp((significand shl 1) + 1uL, exponent - 1))
    val lowerIsCloser = significandBits == 0uL && exponent != DENORMAL_EXPONENT
    val minus = if (lowerIsCloser) {
        DiyFp((significand shl 2) - 1uL, exponent - 2)
    } else {
        DiyFp((significand shl 1) - 1uL, exponent - 1)
    }
    minus.f = minus.f shl (minus.e - plus.e)
    minus.e = plus.e
    return minus to plus
}

private fun asNormalizedDiyFp(value: Double): DiyFp {
    val bits = value.toRawBits().toULong()
    val biasedExponent = ((bits shr 52) and 0x7FFuL).toInt()
    val significandBits = bits and SIGNIFICAND_MASK
    return if (biasedExponent != 0) {
        normalize(DiyFp(significandBits + HIDDEN_BIT, biasedExponent - EXPONENT_BIAS))
    } else {
        normalize(DiyFp(significandBits, DENORMAL_EXPONENT))
    }
}

/** Index into the cached powers whose binary exponent lands inside the window. */
private fun cachedPowerIndex(minExponent: Int, maxExponent: Int): Int {
    // e_k is about k*log2(10) - 63, so invert that for a starting guess and
    // walk; the table step is one, so this moves by at most a couple of places.
    var k = ((minExponent + 63) / 3.321928094887362).toInt() - 1
    if (k < Pow10Table.MIN_K) k = Pow10Table.MIN_K
    if (k > Pow10Table.MAX_K) k = Pow10Table.MAX_K
    var index = k - Pow10Table.MIN_K
    while (index > 0 && Pow10Table.EXPONENTS[index] > maxExponent) index--
    while (index < Pow10Table.EXPONENTS.size - 1 && Pow10Table.EXPONENTS[index] < minExponent) index++
    return index
}

private val POWERS_OF_TEN = uintArrayOf(
    1u, 10u, 100u, 1_000u, 10_000u, 100_000u,
    1_000_000u, 10_000_000u, 100_000_000u, 1_000_000_000u,
)

/** The largest power of ten not exceeding [number], with its exponent plus one. */
private fun biggestPowerTen(number: UInt): Pair<UInt, Int> {
    var i = POWERS_OF_TEN.size - 1
    while (i > 0 && POWERS_OF_TEN[i] > number) i--
    return POWERS_OF_TEN[i] to (i + 1)
}

/**
 * Nudges the last digit toward the exact value, and decides whether the result
 * can be trusted.
 *
 * [rest] is how far the digits produced fall short of the high boundary, and
 * [unit] is the accumulated rounding error. The final test asks whether the
 * answer is far enough inside the interval that the error could not have moved
 * it across either edge; when it is not, the caller must do the exact work.
 */
private fun roundWeed(
    digits: StringBuilder,
    distanceTooHighToW: ULong,
    unsafeInterval: ULong,
    initialRest: ULong,
    tenKappa: ULong,
    unit: ULong,
): Boolean {
    var rest = initialRest
    val smallDistance = distanceTooHighToW - unit
    val bigDistance = distanceTooHighToW + unit

    while (rest < smallDistance &&
        unsafeInterval - rest >= tenKappa &&
        (rest + tenKappa < smallDistance || smallDistance - rest >= rest + tenKappa - smallDistance)
    ) {
        digits[digits.length - 1] = digits[digits.length - 1] - 1
        rest += tenKappa
    }

    // Rounding down again would land nearer still, so which of the two is
    // correct depends on detail this arithmetic has already lost.
    if (rest < bigDistance &&
        unsafeInterval - rest >= tenKappa &&
        (rest + tenKappa < bigDistance || bigDistance - rest > rest + tenKappa - bigDistance)
    ) {
        return false
    }

    // Far enough from both edges of the interval that the accumulated error
    // cannot have carried the answer over either.
    return 2uL * unit <= rest && rest <= unsafeInterval - 4uL * unit
}

/**
 * The shortest digits for [value], or null when the approximation cannot say.
 *
 * Returns the digits and the specification's `n`: the value is
 * `0.<digits> * 10^n`.
 */
internal fun grisu3ShortestDigits(value: Double): Pair<String, Int>? {
    val w = asNormalizedDiyFp(value)
    val (boundaryMinus, boundaryPlus) = normalizedBoundaries(value)

    val index = cachedPowerIndex(
        MIN_TARGET_EXPONENT - (w.e + SIGNIFICAND_BITS),
        MAX_TARGET_EXPONENT - (w.e + SIGNIFICAND_BITS),
    )
    val cached = DiyFp(Pow10Table.SIGNIFICANDS[index], Pow10Table.EXPONENTS[index])
    val cachedK = index + Pow10Table.MIN_K

    val scaledW = multiply(w, cached)
    val scaledLow = multiply(boundaryMinus, cached)
    val scaledHigh = multiply(boundaryPlus, cached)

    // The scaled exponent must be in the window, or the splits below are wrong.
    if (scaledW.e < MIN_TARGET_EXPONENT || scaledW.e > MAX_TARGET_EXPONENT) return null

    var unit = 1uL
    val tooLow = DiyFp(scaledLow.f - unit, scaledLow.e)
    val tooHigh = DiyFp(scaledHigh.f + unit, scaledHigh.e)
    var unsafeInterval = tooHigh.f - tooLow.f

    val oneE = tooHigh.e
    val oneF = 1uL shl -oneE
    var integrals = (tooHigh.f shr -oneE).toUInt()
    var fractionals = tooHigh.f and (oneF - 1uL)

    var (divisor, kappa) = biggestPowerTen(integrals)
    val digits = StringBuilder(24)
    val distanceTooHighToW = tooHigh.f - scaledW.f

    while (kappa > 0) {
        val digit = integrals / divisor
        digits.append('0' + digit.toInt())
        integrals %= divisor
        kappa--
        val rest = (integrals.toULong() shl -oneE) + fractionals
        if (rest < unsafeInterval) {
            val ok = roundWeed(
                digits,
                distanceTooHighToW,
                unsafeInterval,
                rest,
                divisor.toULong() shl -oneE,
                unit,
            )
            return if (ok) finish(digits, cachedK, kappa) else null
        }
        divisor /= 10u
    }

    while (true) {
        // Each step consumes one more decimal digit of the fraction, and the
        // uncertainty grows with it.
        fractionals *= 10uL
        unit *= 10uL
        unsafeInterval *= 10uL
        val digit = (fractionals shr -oneE).toInt()
        digits.append('0' + digit)
        fractionals = fractionals and (oneF - 1uL)
        kappa--
        if (fractionals < unsafeInterval) {
            val ok = roundWeed(
                digits,
                distanceTooHighToW * unit,
                unsafeInterval,
                fractionals,
                oneF,
                unit,
            )
            return if (ok) finish(digits, cachedK, kappa) else null
        }
        // A double never needs more than 17 significant digits; more than that
        // means something has gone wrong rather than that the value is hard.
        if (digits.length > 20) return null
    }
}

/**
 * Turns the digit buffer into the contract [shortestDigits] promises: no
 * trailing zeros, paired with `n` such that the value is `0.<digits> * 10^n`.
 *
 * Dropping trailing zeros does not move the point, so `n` is unaffected.
 */
private fun finish(digits: StringBuilder, cachedK: Int, kappa: Int): Pair<String, Int>? {
    val decimalExponent = -cachedK + kappa
    val n = digits.length + decimalExponent
    var end = digits.length
    while (end > 1 && digits[end - 1] == '0') end--
    digits.setLength(end)
    // Weeding can carry the leading digit below '1', which would break the
    // no-leading-zero contract; that is rare enough to hand to the exact path.
    if (digits[0] == '0') return null
    return digits.toString() to n
}
