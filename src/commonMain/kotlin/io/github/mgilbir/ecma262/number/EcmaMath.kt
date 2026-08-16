package io.github.mgilbir.ecma262.number

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * The `Math` functions ECMA-262 specifies exactly.
 *
 * Most of `Math` is *implementation-approximated* — `sin`, `exp`, `pow` and the
 * rest may differ between engines and there is nothing to be correct against.
 * These are the ones with a defined answer, and several of them are answers
 * Kotlin gives differently:
 *
 * ```kotlin
 * EcmaMath.round(0.5)   // 1.0   - kotlin.math.round gives 0.0, rounding to even
 * EcmaMath.round(2.5)   // 3.0   - and 2.0
 * EcmaMath.round(-0.5)  // -0.0
 * ```
 *
 * `kotlin.math.round` rounds halves to the nearest even integer; JavaScript
 * rounds them toward positive infinity. Nothing warns about the difference: the
 * answers are simply different, on every target.
 */
public object EcmaMath {

    /**
     * `Math.round` — ECMA-262 21.3.2.28. Halves go toward positive infinity, so
     * `round(-0.5)` is `-0` rather than `-1`.
     *
     * Not `floor(x + 0.5)`, which the specification is careful to avoid:
     * `0.49999999999999994 + 0.5` rounds up to exactly 1, so that formula
     * answers 1 where the correct answer is 0.
     */
    public fun round(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return x
        if (x > 0.0 && x < 0.5) return 0.0
        if (x < 0.0 && x >= -0.5) return -0.0
        // At and above 2^52 every double is already an integer, and adding a
        // half would land between representable values.
        if (x >= 4503599627370496.0 || x <= -4503599627370496.0) return x
        return floor(x + 0.5)
    }

    /** `Math.trunc` — ECMA-262 21.3.2.38. Keeps the sign of a zero. */
    public fun trunc(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return x
        return if (x < 0.0) ceil(x) else floor(x)
    }

    /** `Math.sign` — ECMA-262 21.3.2.29. `sign(-0)` is `-0`, not `0`. */
    public fun sign(x: Double): Double {
        if (x.isNaN() || x == 0.0) return x
        return if (x < 0.0) -1.0 else 1.0
    }

    /**
     * `Math.clz32` — ECMA-262 21.3.2.11: leading zeros of the value as a 32-bit
     * unsigned integer, so `clz32(0)` is 32 and `clz32(-1)` is 0.
     */
    public fun clz32(x: Double): Int = toUint32(x).countLeadingZeroBits()

    /**
     * `Math.imul` — ECMA-262 21.3.2.19: the two arguments as 32-bit signed
     * integers, multiplied with wraparound.
     */
    public fun imul(a: Double, b: Double): Int = toInt32(a) * toInt32(b)

    /**
     * `Math.fround` — ECMA-262 21.3.2.17: the nearest value representable as a
     * 32-bit float, back as a double.
     *
     * Not `x.toFloat().toDouble()`. On Kotlin/JS a `Float` is a JavaScript
     * number, so that conversion keeps all 53 bits and returns the input
     * unchanged — silently, and only on that target.
     *
     * Instead the significand is rounded explicitly to the 24 bits a float
     * carries. Dividing by the float's unit in the last place is exact, since
     * it is a power of two; `kotlin.math.round` then applies the
     * round-half-to-even that IEEE requires; multiplying back is exact again.
     */
    public fun fround(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return x
        val exponent = ((x.toRawBits() ushr 52) and 0x7FF).toInt() - 1023
        // Below the smallest normal float the spacing stops shrinking and stays
        // at 2^-149, which is what makes float subnormals subnormal.
        val ulpExponent = if (exponent < -126) -149 else exponent - 23
        val ulp = 2.0.pow(ulpExponent)
        val rounded = kotlin.math.round(x / ulp) * ulp
        // Anything a float cannot hold becomes an infinity; rounding can only
        // have carried it to 2^128, one step above the largest float.
        if (rounded > MAX_FLOAT) return Double.POSITIVE_INFINITY
        if (rounded < -MAX_FLOAT) return Double.NEGATIVE_INFINITY
        return rounded
    }

    /** The largest value a 32-bit float can hold, 2^128 - 2^104. */
    private const val MAX_FLOAT: Double = 3.4028234663852886e38

    /** `ToUint32` — ECMA-262 7.1.7. */
    private fun toUint32(x: Double): UInt {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return 0u
        // mod rather than %: the specification wants a non-negative remainder.
        return trunc(x).mod(4294967296.0).toLong().toUInt()
    }

    /** `ToInt32` — ECMA-262 7.1.6, which is ToUint32 reinterpreted as signed. */
    private fun toInt32(x: Double): Int = toUint32(x).toInt()
}
