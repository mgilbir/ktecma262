package io.github.mgilbir.ecma262.number

/**
 * `ToInt32` - ECMA-262 7.1.6.
 *
 * What JavaScript applies to both operands of every bitwise operator, so a
 * consumer implementing `&`, `|`, `^`, `<<`, `>>` or `>>>` needs it before it
 * can implement any of them.
 *
 * Not a cast. NaN and both infinities become 0, the value is truncated toward
 * zero rather than rounded, and what is left wraps modulo 2^32:
 *
 * ```kotlin
 * (-3.7).toEcmaInt32()        // -3   truncated, not floored
 * Double.NaN.toEcmaInt32()    //  0
 * 2147483648.0.toEcmaInt32()  // -2147483648   wrapped
 * 1e21.toEcmaInt32()          // -559939584
 * ```
 */
public fun Double.toEcmaInt32(): Int = toEcmaUint32().toInt()

/**
 * `ToUint32` - ECMA-262 7.1.7, which is [toEcmaInt32] read as unsigned.
 *
 * This is the coercion behind the unsigned right shift, which is why
 * `-1 >>> 0` is 4294967295 in JavaScript.
 */
public fun Double.toEcmaUint32(): UInt {
    if (isNaN() || isInfinite() || this == 0.0) return 0u
    // mod rather than %: the specification wants a non-negative remainder,
    // and truncation toward zero happens first.
    val truncated = if (this < 0.0) kotlin.math.ceil(this) else kotlin.math.floor(this)
    return truncated.mod(4294967296.0).toLong().toUInt()
}
