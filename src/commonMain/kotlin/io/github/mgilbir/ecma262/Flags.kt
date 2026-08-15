package io.github.mgilbir.ecma262

import kotlin.jvm.JvmInline

/**
 * The set of ECMA-262 regular expression flags.
 *
 * Bit positions follow the canonical order the spec uses for `RegExp.prototype.flags`
 * (`d`, `g`, `i`, `m`, `s`, `u`, `v`, `y`), which lets [toString] emit the canonical
 * string by scanning bits in order.
 */
@JvmInline
public value class Flags internal constructor(internal val bits: Int) {

    /** True when every flag in [other] is present in this set. */
    public operator fun contains(other: Flags): Boolean = bits and other.bits == other.bits

    /** Union of two flag sets. */
    public operator fun plus(other: Flags): Flags = Flags(bits or other.bits)

    /** This set with every flag in [other] removed. */
    public operator fun minus(other: Flags): Flags = Flags(bits and other.bits.inv())

    public val hasIndices: Boolean get() = bits and HAS_INDICES.bits != 0
    public val global: Boolean get() = bits and GLOBAL.bits != 0
    public val ignoreCase: Boolean get() = bits and IGNORE_CASE.bits != 0
    public val multiline: Boolean get() = bits and MULTILINE.bits != 0
    public val dotAll: Boolean get() = bits and DOT_ALL.bits != 0
    public val unicode: Boolean get() = bits and UNICODE.bits != 0
    public val unicodeSets: Boolean get() = bits and UNICODE_SETS.bits != 0
    public val sticky: Boolean get() = bits and STICKY.bits != 0

    /**
     * True in "Unicode mode" — under either `u` or `v`.
     *
     * This is the condition the spec keys most Unicode behaviour on: matching by
     * code point rather than code unit, `\p{...}` support, simple case folding,
     * and the withdrawal of every Annex B leniency.
     */
    public val isUnicodeMode: Boolean get() = unicode || unicodeSets

    /** The canonical flag string, e.g. `"gimsu"`. Empty when no flags are set. */
    override fun toString(): String {
        if (bits == 0) return ""
        val sb = StringBuilder(ORDER.size)
        for ((bit, ch) in ORDER) if (bits and bit != 0) sb.append(ch)
        return sb.toString()
    }

    public companion object {
        public val NONE: Flags = Flags(0)
        public val HAS_INDICES: Flags = Flags(1 shl 0)
        public val GLOBAL: Flags = Flags(1 shl 1)
        public val IGNORE_CASE: Flags = Flags(1 shl 2)
        public val MULTILINE: Flags = Flags(1 shl 3)
        public val DOT_ALL: Flags = Flags(1 shl 4)
        public val UNICODE: Flags = Flags(1 shl 5)
        public val UNICODE_SETS: Flags = Flags(1 shl 6)
        public val STICKY: Flags = Flags(1 shl 7)

        private val ORDER: Array<Pair<Int, Char>> = arrayOf(
            HAS_INDICES.bits to 'd',
            GLOBAL.bits to 'g',
            IGNORE_CASE.bits to 'i',
            MULTILINE.bits to 'm',
            DOT_ALL.bits to 's',
            UNICODE.bits to 'u',
            UNICODE_SETS.bits to 'v',
            STICKY.bits to 'y',
        )

        private fun bitFor(c: Char): Int = when (c) {
            'd' -> HAS_INDICES.bits
            'g' -> GLOBAL.bits
            'i' -> IGNORE_CASE.bits
            'm' -> MULTILINE.bits
            's' -> DOT_ALL.bits
            'u' -> UNICODE.bits
            'v' -> UNICODE_SETS.bits
            'y' -> STICKY.bits
            else -> 0
        }

        /**
         * Parses a flag string such as `"gimu"`.
         *
         * @throws RegExpSyntaxError on an unknown flag character, a repeated flag,
         * or the mutually exclusive `u` and `v` together — the three cases
         * JavaScript rejects when constructing a `RegExp`.
         */
        public fun parse(source: String): Flags {
            var bits = 0
            for (i in source.indices) {
                val c = source[i]
                val bit = bitFor(c)
                if (bit == 0) {
                    throw RegExpSyntaxError("invalid regular expression flag '$c'", i)
                }
                if (bits and bit != 0) {
                    throw RegExpSyntaxError("duplicate regular expression flag '$c'", i)
                }
                bits = bits or bit
            }
            if (bits and UNICODE.bits != 0 && bits and UNICODE_SETS.bits != 0) {
                throw RegExpSyntaxError("flags 'u' and 'v' are mutually exclusive")
            }
            return Flags(bits)
        }

        /** Like [parse], but returns null instead of throwing. */
        public fun parseOrNull(source: String): Flags? =
            try {
                parse(source)
            } catch (_: RegExpSyntaxError) {
                null
            }
    }
}
