package io.github.mgilbir.ecma262.number

import kotlin.math.ceil
import kotlin.math.log10

/**
 * `Number.prototype.toFixed` — ECMA-262 21.1.3.3.
 *
 * [fractionDigits] digits after the decimal point, always, with no exponent —
 * unless the value is at least 10^21, where the specification hands off to
 * `toString` and the result gains one anyway.
 *
 * ```kotlin
 * 1.005.toEcmaFixed(2)    // "1.00" - 1.005 is really 1.00499999999999989...
 * 1234.5678.toEcmaFixed(2) // "1234.57"
 * 0.000001.toEcmaFixed(2) // "0.00"
 * 1e21.toEcmaFixed(2)     // "1e+21"
 * ```
 *
 * Note the rounding: **ties go to the larger value**, not to the even one.
 * `toString` rounds half to even, and these two methods of the same object
 * disagree by design — the specification says "if there are two such n, pick
 * the larger n" here, and "choose the one that is even" there.
 *
 * @throws IllegalArgumentException if [fractionDigits] is outside 0..100, where
 *   JavaScript throws a RangeError.
 */
public fun Double.toEcmaFixed(fractionDigits: Int): String {
    require(fractionDigits in 0..100) {
        "toFixed() digits argument must be between 0 and 100, was $fractionDigits"
    }
    if (isNaN()) return "NaN"
    if (this < 0.0) return "-" + (-this).toEcmaFixed(fractionDigits)
    if (isInfinite()) return "Infinity"
    // 10^21 and above is handed to toString, exponent and all.
    if (this >= 1e21) return toEcmaString()
    if (this == 0.0) return withPoint("0", fractionDigits)

    // The count of digits before the point, taken *before* any rounding: for
    // 99.995 the rounded first digit is already 1e2 out of position, and using
    // it here produced "999.95" instead of "100.00".
    val exponent = decimalExponentOf(this)
    val wanted = exponent + fractionDigits
    val rounded = when {
        // Below a tenth of the last place asked for, so nothing survives.
        wanted < 0 -> "0"
        // Between a tenth and the last place: it is one or the other, and an
        // exact half rounds up.
        wanted == 0 -> if (atLeastHalfAtScale(this, fractionDigits)) "1" else "0"
        else -> {
            val (digits, pointPosition) = significantDigits(this, wanted)
            // Rounding can carry into a new column - 9999 becomes 10000 - and
            // the integer then has one digit more than was asked for.
            if (pointPosition > exponent) digits + "0" else digits
        }
    }
    return withPoint(rounded, fractionDigits)
}

/**
 * `Number.prototype.toExponential` — ECMA-262 21.1.3.2.
 *
 * ```kotlin
 * 1234.5678.toEcmaExponential(2)    // "1.23e+3"
 * 1234.5678.toEcmaExponential(null) // "1.2345678e+3" - as many digits as needed
 * 0.0.toEcmaExponential(2)          // "0.00e+0"
 * ```
 *
 * With [fractionDigits] null the digits are the shortest that identify the
 * value, exactly as `toString` chooses them — including its round-half-to-even
 * tie-break. With a count given, ties go to the larger value instead.
 *
 * @throws IllegalArgumentException if [fractionDigits] is outside 0..100.
 */
public fun Double.toEcmaExponential(fractionDigits: Int?): String {
    if (fractionDigits != null) {
        require(fractionDigits in 0..100) {
            "toExponential() argument must be between 0 and 100, was $fractionDigits"
        }
    }
    if (isNaN()) return "NaN"
    if (this < 0.0) return "-" + (-this).toEcmaExponential(fractionDigits)
    if (isInfinite()) return "Infinity"

    if (this == 0.0) {
        val zeros = fractionDigits ?: 0
        return exponentialForm("0".padEnd(zeros + 1, '0'), 0)
    }

    val (digits, pointPosition) = if (fractionDigits == null) {
        // Shortest, which is what toString would have produced.
        shortestDigits(this)
    } else {
        significantDigits(this, fractionDigits + 1)
    }
    return exponentialForm(digits, pointPosition - 1)
}

/**
 * `Number.prototype.toPrecision` — ECMA-262 21.1.3.5.
 *
 * [precision] significant digits, laid out positionally when the exponent is
 * within reach and in exponential form otherwise — the same shape of decision
 * `toString` makes, but keyed on the requested precision:
 *
 * ```kotlin
 * 1234.5678.toEcmaPrecision(6) // "1234.57"
 * 1234.5678.toEcmaPrecision(2) // "1.2e+3"
 * 0.000123.toEcmaPrecision(2)  // "0.00012"
 * 123.0.toEcmaPrecision(5)     // "123.00" - trailing zeros are kept
 * ```
 *
 * Passing null returns `toString`, as calling it with no argument does.
 *
 * @throws IllegalArgumentException if [precision] is outside 1..100.
 */
public fun Double.toEcmaPrecision(precision: Int?): String {
    if (precision == null) return toEcmaString()
    if (isNaN()) return "NaN"
    if (this < 0.0) return "-" + (-this).toEcmaPrecision(precision)
    if (isInfinite()) return "Infinity"
    require(precision in 1..100) {
        "toPrecision() argument must be between 1 and 100, was $precision"
    }

    if (this == 0.0) {
        return if (precision == 1) "0" else "0." + "0".repeat(precision - 1)
    }

    val (digits, pointPosition) = significantDigits(this, precision)
    val e = pointPosition - 1
    // The specification's own thresholds: too small or too large to write out.
    if (e < -6 || e >= precision) return exponentialForm(digits, e)

    return when {
        pointPosition <= 0 -> "0." + "0".repeat(-pointPosition) + digits
        pointPosition >= digits.length -> digits + "0".repeat(pointPosition - digits.length)
        else -> digits.substring(0, pointPosition) + "." + digits.substring(pointPosition)
    }
}

/** `d.ddde±x`, given the digits and the exponent of the first one. */
private fun exponentialForm(digits: String, e: Int): String {
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    val sign = if (e >= 0) "+" else "-"
    return mantissa + "e" + sign + (if (e >= 0) e else -e)
}

/** Places a decimal point [fractionDigits] from the right of an integer string. */
private fun withPoint(integer: String, fractionDigits: Int): String {
    if (fractionDigits == 0) return integer
    val padded = integer.padStart(fractionDigits + 1, '0')
    val split = padded.length - fractionDigits
    return padded.substring(0, split) + "." + padded.substring(split)
}

/**
 * Writes `value` as an exact fraction into [r] and [s], scaled so that
 * `0.1 <= r/s < 1`, and returns the power of ten taken out.
 *
 * That power is the specification's `n`: the value is `0.<digits> * 10^n`. It
 * is the position of the decimal point *before* any rounding, which is what
 * `toFixed` needs — rounding can move it, and asking a rounded result where the
 * point started gives the wrong answer for values like 99.995.
 */
private fun scaleToUnitInterval(value: Double, r: Big, s: Big): Int {
    val bits = value.toRawBits()
    val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
    val mantissa = bits and 0x000FFFFFFFFFFFFFL
    val significand = if (biasedExponent == 0) mantissa else mantissa or (1L shl 52)
    val exponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075

    r.setLong(significand)
    s.setLong(1)
    if (exponent >= 0) r.shiftLeft(exponent) else s.shiftLeft(-exponent)

    // The estimate does most of the work; the loops are the authority.
    var pointPosition = ceil(log10(value)).toInt()
    if (pointPosition > 0) s.mulPow10(pointPosition) else if (pointPosition < 0) r.mulPow10(-pointPosition)

    val scratch = Big(Big.PARSE_LIMBS)
    while (r.compareTo(s) >= 0) {
        s.mulSmall(10)
        pointPosition++
    }
    while (true) {
        scratch.copyFrom(r)
        scratch.mulSmall(10)
        if (scratch.compareTo(s) >= 0) break
        r.mulSmall(10)
        pointPosition--
    }
    return pointPosition
}

/** Where the decimal point falls, before rounding. */
internal fun decimalExponentOf(value: Double): Int =
    scaleToUnitInterval(value, Big(Big.PARSE_LIMBS), Big(Big.PARSE_LIMBS))

/**
 * Is `value * 10^scale` at least one half?
 *
 * Decides the one place `toFixed` cannot ask for a digit: when the result rounds
 * to either zero or a single unit in the last place. Compared exactly, so an
 * input sitting precisely on the half rounds up as the specification requires.
 */
private fun atLeastHalfAtScale(value: Double, scale: Int): Boolean {
    val bits = value.toRawBits()
    val biasedExponent = ((bits ushr 52) and 0x7FF).toInt()
    val mantissa = bits and 0x000FFFFFFFFFFFFFL
    val significand = if (biasedExponent == 0) mantissa else mantissa or (1L shl 52)
    val exponent = if (biasedExponent == 0) -1074 else biasedExponent - 1075

    val r = Big(Big.PARSE_LIMBS)
    val s = Big(Big.PARSE_LIMBS)
    r.setLong(significand)
    s.setLong(1)
    if (exponent >= 0) r.shiftLeft(exponent) else s.shiftLeft(-exponent)
    r.mulPow10(scale)
    r.mulSmall(2)
    return r.compareTo(s) >= 0
}

/**
 * The first [count] significant digits of [value], correctly rounded, with the
 * specification's `n`: the value is about `0.<digits> * 10^n`.
 *
 * Ties round **up**, which is what `toFixed`, `toExponential` and
 * `toPrecision` all ask for — and the opposite of what `toString` does.
 *
 * Exact throughout: the same scale-then-emit method the shortest-digits path
 * uses, without the neighbour boundaries, since here the caller has said how
 * many digits it wants rather than asking for the fewest that identify the
 * value.
 */
internal fun significantDigits(value: Double, count: Int): Pair<String, Int> {
    require(count >= 1)
    val r = Big(Big.PARSE_LIMBS)
    val s = Big(Big.PARSE_LIMBS)
    var pointPosition = scaleToUnitInterval(value, r, s)

    val digits = StringBuilder(count + 1)
    repeat(count) {
        r.mulSmall(10)
        var digit = 0
        while (r.compareTo(s) >= 0) {
            r.subFrom(s)
            digit++
        }
        digits.append('0' + digit)
    }

    // Round on what is left: a tie goes up.
    r.mulSmall(2)
    if (r.compareTo(s) >= 0) {
        var i = digits.length - 1
        while (i >= 0) {
            if (digits[i] != '9') {
                digits[i] = digits[i] + 1
                break
            }
            digits[i] = '0'
            i--
        }
        if (i < 0) {
            // Every digit carried: 999 becomes 1000, which is 0.100 one place up.
            digits.insert(0, '1')
            digits.setLength(count)
            pointPosition++
        }
    }
    return digits.toString() to pointPosition
}
