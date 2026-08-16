package io.github.mgilbir.ecma262.number

/**
 * A fixed-capacity unsigned big integer, with only the operations the shortest
 * round-trip algorithm needs.
 *
 * Converting a `Double` to its shortest decimal exactly needs arithmetic well
 * beyond 64 bits: the smallest subnormal is 2^-1074, so representing it as a
 * fraction takes over a thousand bits. This carries exactly the operations
 * required — add, subtract, compare, multiply by a single digit and shift —
 * which is why there is no division here at all. The digit loop keeps its
 * remainder below the divisor, so each quotient digit is 0..9 and repeated
 * subtraction finds it in at most nine steps.
 *
 * The capacity is fixed and checked. Growing on demand would turn a bug in the
 * scaling loops into unbounded allocation; overflowing a fixed buffer throws
 * instead, which is a failure that gets noticed.
 */
internal class Big(private val capacity: Int = FORMAT_LIMBS) {

    /** Little-endian 32-bit limbs, each held in the low half of an `Int`. */
    private val w = IntArray(capacity)

    /** Number of significant limbs; zero means the value is zero. */
    private var n = 0

    fun setLong(value: Long) {
        require(value >= 0) { "Big is unsigned" }
        w.fill(0)
        w[0] = value.toInt()
        w[1] = (value ushr 32).toInt()
        n = if (w[1] != 0) 2 else if (w[0] != 0) 1 else 0
    }

    fun copyFrom(other: Big) {
        other.w.copyInto(w, 0, 0, other.n)
        if (n > other.n) w.fill(0, other.n, n)
        n = other.n
    }

    fun isZero(): Boolean = n == 0

    /** this <<= bits */
    fun shiftLeft(bits: Int) {
        if (n == 0 || bits == 0) return
        val words = bits ushr 5
        val rest = bits and 31
        if (rest == 0) {
            checkRoom(n + words)
            for (i in n - 1 downTo 0) w[i + words] = w[i]
        } else {
            val top = (w[n - 1].toLong() and MASK) ushr (32 - rest)
            checkRoom(n + words + if (top != 0L) 1 else 0)
            if (top != 0L) w[n + words] = top.toInt()
            for (i in n - 1 downTo 1) {
                val hi = (w[i].toLong() and MASK) shl rest
                val lo = (w[i - 1].toLong() and MASK) ushr (32 - rest)
                w[i + words] = (hi or lo).toInt()
            }
            w[words] = ((w[0].toLong() and MASK) shl rest).toInt()
        }
        w.fill(0, 0, words)
        n += words + 1
        trim()
    }

    /** this *= m, for a small multiplier (this algorithm only ever uses 2, 4 and 10). */
    fun mulSmall(m: Int) {
        if (n == 0 || m == 1) return
        var carry = 0L
        for (i in 0 until n) {
            val p = (w[i].toLong() and MASK) * m + carry
            w[i] = p.toInt()
            carry = p ushr 32
        }
        if (carry != 0L) {
            checkRoom(n + 1)
            w[n] = carry.toInt()
            n++
        }
    }

    /**
     * this *= 10^n.
     *
     * Nine digits at a time, because 10^9 still fits in an `Int` and the
     * per-limb product stays inside a `Long`. Stepping one power at a time
     * costs up to 330 passes over the whole number when scaling a value at the
     * end of the exponent range.
     */
    fun mulPow10(n: Int) {
        require(n >= 0) { "mulPow10 needs a non-negative exponent" }
        var left = n
        while (left >= 9) {
            mulSmall(1_000_000_000)
            left -= 9
        }
        if (left > 0) {
            var m = 1
            repeat(left) { m *= 10 }
            mulSmall(m)
        }
    }

    /** this += other */
    fun addTo(other: Big) {
        if (other.n == 0) return
        val len = maxOf(n, other.n)
        checkRoom(len)
        var carry = 0L
        for (i in 0 until len) {
            val sum = (w[i].toLong() and MASK) + (other.limb(i).toLong() and MASK) + carry
            w[i] = sum.toInt()
            carry = sum ushr 32
        }
        n = len
        if (carry != 0L) {
            checkRoom(n + 1)
            w[n] = carry.toInt()
            n++
        }
    }

    /** this -= other, which must not be greater than this. */
    fun subFrom(other: Big) {
        var borrow = 0L
        for (i in 0 until n) {
            var diff = (w[i].toLong() and MASK) - (other.limb(i).toLong() and MASK) - borrow
            if (diff < 0) {
                diff += 1L shl 32
                borrow = 1
            } else {
                borrow = 0
            }
            w[i] = diff.toInt()
        }
        check(borrow == 0L) { "Big.subFrom underflow" }
        trim()
    }

    fun compareTo(other: Big): Int {
        if (n != other.n) return if (n < other.n) -1 else 1
        for (i in n - 1 downTo 0) {
            val a = w[i].toLong() and MASK
            val b = other.w[i].toLong() and MASK
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    private fun limb(i: Int): Int = if (i < n) w[i] else 0

    private fun trim() {
        while (n > 0 && w[n - 1] == 0) n--
    }

    private fun checkRoom(limbs: Int) {
        check(limbs <= capacity) {
            "Big overflow: needed $limbs limbs of $capacity. " +
                "Every loop here is bounded by the double exponent range, so this is a bug."
        }
    }

    /** Number of significant bits; zero for zero. */
    fun bitLength(): Int {
        if (n == 0) return 0
        var top = w[n - 1].toLong() and MASK
        var bits = 0
        while (top != 0L) {
            bits++
            top = top ushr 1
        }
        return (n - 1) * 32 + bits
    }

    /** this >>= bits */
    fun shiftRight(bits: Int) {
        if (n == 0 || bits == 0) return
        val words = bits ushr 5
        val rest = bits and 31
        if (words >= n) {
            w.fill(0, 0, n)
            n = 0
            return
        }
        if (rest == 0) {
            for (i in 0 until n - words) w[i] = w[i + words]
        } else {
            for (i in 0 until n - words - 1) {
                val lo = (w[i + words].toLong() and MASK) ushr rest
                val hi = (w[i + words + 1].toLong() and MASK) shl (32 - rest)
                w[i] = (lo or hi).toInt()
            }
            w[n - words - 1] = ((w[n - 1].toLong() and MASK) ushr rest).toInt()
        }
        w.fill(0, n - words, n)
        n -= words
        trim()
    }

    /** this += v, for a small non-negative addend. */
    fun addSmall(v: Int) {
        require(v >= 0)
        if (v == 0) return
        var carry = v.toLong() and MASK
        var i = 0
        while (carry != 0L) {
            checkRoom(i + 1)
            val sum = (limb(i).toLong() and MASK) + carry
            w[i] = sum.toInt()
            carry = sum ushr 32
            i++
            if (i > n) n = i
        }
        if (i > n) n = i
        trim()
    }

    /** Is the low bit set? */
    fun isOdd(): Boolean = n != 0 && (w[0] and 1) == 1

    companion object {
        private const val MASK = 0xFFFFFFFFL

        /**
         * 2048 bits, enough for formatting.
         *
         * The widest intermediate is the numerator for the smallest subnormals:
         * it starts around 2^55, is multiplied by ten up to 324 times while
         * scaling (~2^1077) and up to 17 more times while emitting digits, then
         * has the upper gap added. That peaks near 1200 bits, so this leaves
         * room to spare while still being a hard ceiling.
         */
        const val FORMAT_LIMBS = 64

        /**
         * 6144 bits, for parsing.
         *
         * A correctly rounded decimal needs up to ~770 significant digits to
         * decide, which with an exponent of 10^308 and a shift of up to 2^1074
         * lands near 4700 bits. Parsing allocates a wider buffer rather than
         * making every formatting call pay for one.
         */
        const val PARSE_LIMBS = 192
    }
}
