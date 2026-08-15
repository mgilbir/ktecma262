package io.github.mgilbir.ecma262.unicode

/**
 * An immutable, sorted set of code point ranges with O(log n) membership.
 *
 * Ranges are disjoint, non-adjacent and ascending, which is what lets [contains]
 * binary-search [starts] alone.
 */
internal class RangeSet(
    private val starts: IntArray,
    private val ends: IntArray,
) {
    init {
        require(starts.size == ends.size) { "starts/ends length mismatch" }
    }

    val size: Int get() = starts.size

    fun contains(codePoint: Int): Boolean {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                codePoint < starts[mid] -> hi = mid - 1
                codePoint > ends[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** Start of range [i], for iteration in tests and for class construction. */
    fun startAt(i: Int): Int = starts[i]

    /** End of range [i], inclusive. */
    fun endAt(i: Int): Int = ends[i]

    companion object {
        val EMPTY: RangeSet = RangeSet(IntArray(0), IntArray(0))
    }
}

/**
 * Decoder for the varint format produced by `tools/genunicode/gen.mjs`.
 *
 * Each value is little-endian, 5 payload bits per character, with bit 5 set on
 * every character except the last of a value.
 */
internal object VarintCodec {

    /** Character code to payload value; -1 for anything outside the alphabet. */
    private val LOOKUP: IntArray = IntArray(128) { -1 }.also { table ->
        val alphabet = UnicodeTables.ALPHABET
        for (i in alphabet.indices) table[alphabet[i].code] = i
    }

    private fun digit(c: Char): Int {
        val v = if (c.code < 128) LOOKUP[c.code] else -1
        check(v >= 0) { "corrupt Unicode table: character '$c' is not in the alphabet" }
        return v
    }

    /** Decodes a (gap, length) range table. */
    fun decodeRanges(encoded: String): RangeSet {
        if (encoded.isEmpty()) return RangeSet.EMPTY

        // Every value ends with a character that has the continuation bit clear,
        // so counting those gives the value count without decoding twice.
        var values = 0
        for (c in encoded) if (digit(c) < 32) values++
        check(values % 2 == 0) { "corrupt Unicode table: odd value count $values" }

        val n = values / 2
        val starts = IntArray(n)
        val ends = IntArray(n)

        var i = 0
        var prev = 0
        for (r in 0 until n) {
            var gap = 0
            var shift = 0
            while (true) {
                val d = digit(encoded[i++])
                gap = gap or ((d and 31) shl shift)
                shift += 5
                if (d < 32) break
            }
            var len = 0
            shift = 0
            while (true) {
                val d = digit(encoded[i++])
                len = len or ((d and 31) shl shift)
                shift += 5
                if (d < 32) break
            }
            val start = prev + gap
            starts[r] = start
            ends[r] = start + len
            prev = start + len + 1
        }
        return RangeSet(starts, ends)
    }

    /**
     * Decodes a property of strings: varint(length) followed by one varint per
     * code point, repeated.
     */
    fun decodeSequences(encoded: String): List<IntArray> {
        if (encoded.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>()
        var i = 0

        fun readVarint(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val d = digit(encoded[i++])
                v = v or ((d and 31) shl shift)
                shift += 5
                if (d < 32) return v
            }
        }

        while (i < encoded.length) {
            val len = readVarint()
            check(len > 0) { "corrupt property of strings: zero-length sequence" }
            val seq = IntArray(len)
            for (k in 0 until len) seq[k] = readVarint()
            out.add(seq)
        }
        return out
    }

    /**
     * Decodes a code point to code point mapping into parallel sorted arrays.
     * Returns keys and values; look up by binary search on keys.
     */
    fun decodeMapping(encoded: String): Pair<IntArray, IntArray> {
        if (encoded.isEmpty()) return IntArray(0) to IntArray(0)

        var values = 0
        for (c in encoded) if (digit(c) < 32) values++
        check(values % 2 == 0) { "corrupt Unicode mapping: odd value count $values" }

        val n = values / 2
        val keys = IntArray(n)
        val vals = IntArray(n)

        var i = 0
        var prev = 0
        for (r in 0 until n) {
            var gap = 0
            var shift = 0
            while (true) {
                val d = digit(encoded[i++])
                gap = gap or ((d and 31) shl shift)
                shift += 5
                if (d < 32) break
            }
            var zig = 0
            shift = 0
            while (true) {
                val d = digit(encoded[i++])
                zig = zig or ((d and 31) shl shift)
                shift += 5
                if (d < 32) break
            }
            val cp = prev + gap
            keys[r] = cp
            vals[r] = cp + ((zig ushr 1) xor -(zig and 1)) // undo zigzag
            prev = cp
        }
        return keys to vals
    }
}
