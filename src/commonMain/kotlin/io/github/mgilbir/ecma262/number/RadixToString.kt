package io.github.mgilbir.ecma262.number

/**
 * `Number::toString(x, radix)` for a radix other than 10.
 *
 * ### This one is compatibility, not conformance
 *
 * Every other function here can be defended against the specification text.
 * This one cannot. ECMA-262 6.1.6.1.20 says only that the result for a radix
 * other than 10 is *implementation-approximated*: it must be "a String
 * representation of x in that radix", and nothing more. It does not say how
 * many digits to produce, where to stop, or how to round — so there is no
 * correct answer to be right about, only a choice to be compatible with.
 *
 * The choice made here is V8's, because matching what JavaScript actually
 * prints is the only useful target:
 *
 * ```kotlin
 * 255.0.toEcmaString(16)   // "ff"
 * 0.5.toEcmaString(2)      // "0.1"
 * 0.1.toEcmaString(3)      // "0.0022002200220022002200220022002201"
 * (-0.1).toEcmaString(7)   // "-0.04620462046204620463"
 * ```
 *
 * That last pair are the interesting ones: the fraction is emitted until the
 * remaining error exceeds half the spacing of doubles around the input, which
 * is why the digits stop where they do and why the final digit is what it is.
 * A different engine may legitimately print something else, and a future V8
 * could too — so treat this as tracking an implementation, and expect the
 * differential test to be the thing that notices if it moves.
 *
 * Radix 10 is delegated to [toEcmaString], which *is* specified exactly.
 *
 * @throws IllegalArgumentException if [radix] is outside 2..36, where
 *   JavaScript throws a RangeError.
 */
public fun Double.toEcmaString(radix: Int): String {
    require(radix in 2..36) { "toString() radix must be between 2 and 36, was $radix" }
    if (radix == 10) return toEcmaString()
    if (isNaN()) return "NaN"
    if (this < 0.0) return "-" + (-this).toEcmaString(radix)
    if (isInfinite()) return "Infinity"
    if (this == 0.0) return "0"

    var integerPart = kotlin.math.floor(this)
    var fraction = this - integerPart

    // How much of the fraction is real rather than an artefact of the binary
    // representation: half the gap to the next double, and never zero.
    var delta = 0.5 * (nextUp(this) - this)
    if (delta < Double.MIN_VALUE) delta = Double.MIN_VALUE

    val fractionDigits = StringBuilder()
    var carryIntoInteger = false
    if (fraction >= delta) {
        while (true) {
            fraction *= radix
            delta *= radix
            var digit = fraction.toInt()
            fractionDigits.append(DIGITS[digit])
            fraction -= digit

            // Round half to even, but only when doing so cannot be undone by
            // the uncertainty already accumulated.
            if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                if (fraction + delta > 1.0) {
                    // Carry back through the digits already written.
                    var index = fractionDigits.length - 1
                    while (true) {
                        if (index < 0) {
                            carryIntoInteger = true
                            break
                        }
                        digit = digitValueOf(fractionDigits[index])
                        if (digit + 1 < radix) {
                            fractionDigits[index] = DIGITS[digit + 1]
                            fractionDigits.setLength(index + 1)
                            break
                        }
                        // This digit wrapped; drop it and carry further left.
                        fractionDigits.setLength(index)
                        index--
                    }
                    break
                }
            }
            if (fraction < delta) break
        }
    }
    if (carryIntoInteger) integerPart += 1.0

    val integerDigits = StringBuilder()
    // Above 2^53 a double cannot represent consecutive integers, so the low
    // digits are not information; V8 writes zeros for them rather than noise.
    while (unbiasedExponentOf(integerPart / radix) > 0) {
        integerPart /= radix
        integerDigits.append('0')
    }
    do {
        val remainder = integerPart % radix
        integerDigits.append(DIGITS[remainder.toInt()])
        integerPart = (integerPart - remainder) / radix
    } while (integerPart > 0)
    integerDigits.reverse()

    return if (fractionDigits.isEmpty()) {
        integerDigits.toString()
    } else {
        "$integerDigits.$fractionDigits"
    }
}

private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

private fun digitValueOf(c: Char): Int = if (c > '9') c - 'a' + 10 else c - '0'

/** The next double above [value]; [value] is finite and non-negative here. */
private fun nextUp(value: Double): Double = Double.fromBits(value.toRawBits() + 1)

/**
 * The exponent as V8's `Double::Exponent()` reports it — biased so that a
 * result above zero means the value's spacing exceeds one, i.e. it is at least
 * 2^53 and consecutive integers are no longer distinguishable.
 */
private fun unbiasedExponentOf(value: Double): Int {
    val bits = value.toRawBits()
    val biased = ((bits ushr 52) and 0x7FF).toInt()
    if (biased == 0) return -1074
    return biased - 1075
}
