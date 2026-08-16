package io.github.mgilbir.ecma262.text

import io.github.mgilbir.ecma262.unicode.NormalizationTables

/** The four normalisation forms `String.prototype.normalize` accepts. */
public enum class NormalizationForm {
    /** Canonical decomposition, then canonical composition. The default. */
    NFC,

    /** Canonical decomposition. */
    NFD,

    /** Compatibility decomposition, then canonical composition. */
    NFKC,

    /** Compatibility decomposition. */
    NFKD,
}

/**
 * `String.prototype.normalize` — ECMA-262 22.1.3.15, which defers to UAX #15.
 *
 * ```kotlin
 * "é".normalize()                        // "é" - combining acute composed
 * "é".normalize(NormalizationForm.NFD)         // "é" - and taken apart again
 * "ﬁ".normalize(NormalizationForm.NFKC)        // "fi" - the ligature unpicked
 * ```
 *
 * Common Kotlin has nothing equivalent: `java.text.Normalizer` is JVM-only, and
 * Kotlin/Native has no normalisation at all, so anything comparing user-entered
 * text across platforms has been comparing sequences that look identical and
 * are not.
 *
 * Two characters that a reader cannot tell apart should usually compare equal,
 * which is what this is for — and why it matters for anything security-adjacent:
 * a username, a filename or a domain compared without normalising can be
 * spoofed by a different encoding of the same glyphs.
 *
 * Unpaired surrogates pass through unchanged, as they do in JavaScript.
 */
public fun String.normalize(form: NormalizationForm = NormalizationForm.NFC): String {
    if (isEmpty()) return this

    val compatibility = form == NormalizationForm.NFKC || form == NormalizationForm.NFKD
    val compose = form == NormalizationForm.NFC || form == NormalizationForm.NFKC

    val decomposed = canonicalOrder(decompose(this, compatibility))
    val result = if (compose) composeAll(decomposed) else decomposed
    return fromCodePoints(result)
}

// ------------------------------------------------------------------ Hangul

private const val HANGUL_S_BASE = 0xAC00
private const val HANGUL_L_BASE = 0x1100
private const val HANGUL_V_BASE = 0x1161
private const val HANGUL_T_BASE = 0x11A7
private const val HANGUL_L_COUNT = 19
private const val HANGUL_V_COUNT = 21
private const val HANGUL_T_COUNT = 28
private const val HANGUL_N_COUNT = HANGUL_V_COUNT * HANGUL_T_COUNT
private const val HANGUL_S_COUNT = HANGUL_L_COUNT * HANGUL_N_COUNT

private fun isHangulSyllable(cp: Int) = cp >= HANGUL_S_BASE && cp < HANGUL_S_BASE + HANGUL_S_COUNT

// ---------------------------------------------------------------- decompose

private fun decompose(text: String, compatibility: Boolean): IntArray {
    val out = IntArrayBuilder(text.length + 8)
    var i = 0
    while (i < text.length) {
        val cp = codePointAt(text, i)
        i += if (cp > 0xFFFF) 2 else 1
        appendDecomposed(out, cp, compatibility)
    }
    return out.toIntArray()
}

private fun appendDecomposed(out: IntArrayBuilder, cp: Int, compatibility: Boolean) {
    if (isHangulSyllable(cp)) {
        // Arithmetic rather than tabulated: 11,172 syllables would dwarf
        // everything else in the tables.
        val index = cp - HANGUL_S_BASE
        out.add(HANGUL_L_BASE + index / HANGUL_N_COUNT)
        out.add(HANGUL_V_BASE + (index % HANGUL_N_COUNT) / HANGUL_T_COUNT)
        val trailing = index % HANGUL_T_COUNT
        if (trailing != 0) out.add(HANGUL_T_BASE + trailing)
        return
    }
    val mapping = Tables.decomposition(cp, compatibility)
    if (mapping == null) {
        out.add(cp)
    } else {
        // Stored fully expanded, so there is nothing to recurse into.
        for (part in mapping) out.add(part)
    }
}

/**
 * Sorts each run of combining marks by combining class, keeping the original
 * order within a class.
 *
 * Insertion sort because the runs are short — almost always one or two marks —
 * and because it is stable without effort, which the standard requires.
 */
private fun canonicalOrder(cps: IntArray): IntArray {
    var i = 1
    while (i < cps.size) {
        val cc = Tables.combiningClass(cps[i])
        if (cc != 0) {
            var j = i
            while (j > 0) {
                val previous = Tables.combiningClass(cps[j - 1])
                if (previous <= cc) break
                val swap = cps[j]
                cps[j] = cps[j - 1]
                cps[j - 1] = swap
                j--
            }
        }
        i++
    }
    return cps
}

// ------------------------------------------------------------------ compose

private fun composePair(a: Int, b: Int): Int {
    // Hangul, again arithmetically.
    val lIndex = a - HANGUL_L_BASE
    if (lIndex in 0 until HANGUL_L_COUNT) {
        val vIndex = b - HANGUL_V_BASE
        if (vIndex in 0 until HANGUL_V_COUNT) {
            return HANGUL_S_BASE + (lIndex * HANGUL_V_COUNT + vIndex) * HANGUL_T_COUNT
        }
    }
    if (isHangulSyllable(a) && (a - HANGUL_S_BASE) % HANGUL_T_COUNT == 0) {
        val tIndex = b - HANGUL_T_BASE
        if (tIndex in 1 until HANGUL_T_COUNT) return a + tIndex
    }
    return Tables.composite(a, b)
}

private fun composeAll(cps: IntArray): IntArray {
    if (cps.isEmpty()) return cps
    val out = IntArray(cps.size)
    var length = 0
    var starter = -1
    var lastClass = 0

    for (cp in cps) {
        val cc = Tables.combiningClass(cp)
        // Blocked when something between the starter and here has a combining
        // class that is zero, or no lower than this one.
        if (starter >= 0 && (lastClass == 0 || lastClass < cc)) {
            val composite = composePair(out[starter], cp)
            if (composite != -1) {
                out[starter] = composite
                continue
            }
        }
        if (cc == 0) starter = length
        lastClass = cc
        out[length++] = cp
    }
    return if (length == out.size) out else out.copyOf(length)
}

// ------------------------------------------------------------------- tables

/**
 * The generated tables, decoded once on first use.
 *
 * Decoding costs a few milliseconds and only happens for programs that
 * normalise; leaving the tables as encoded strings keeps them out of a static
 * initialiser, which the JVM caps at 64 KB of bytecode.
 */
private object Tables {
    private val classKeys: IntArray
    private val classValues: IntArray
    private val canonicalKeys: IntArray
    private val canonicalOffsets: IntArray
    private val canonicalData: IntArray
    private val compatibilityKeys: IntArray
    private val compatibilityOffsets: IntArray
    private val compatibilityData: IntArray

    /** Starter and mark packed into one Long, sorted, for a binary search. */
    private val compositionKeys: LongArray
    private val compositionValues: IntArray

    init {
        val classes = decodePairs(NormalizationTables.COMBINING_CLASS)
        classKeys = classes.first
        classValues = classes.second

        val canonical = decodeSequences(NormalizationTables.CANONICAL)
        canonicalKeys = canonical.keys
        canonicalOffsets = canonical.offsets
        canonicalData = canonical.data

        val compatibility = decodeSequences(NormalizationTables.COMPATIBILITY)
        compatibilityKeys = compatibility.keys
        compatibilityOffsets = compatibility.offsets
        compatibilityData = compatibility.data

        val composition = decodeComposition(NormalizationTables.COMPOSITION)
        compositionKeys = composition.first
        compositionValues = composition.second
    }

    fun combiningClass(cp: Int): Int {
        val index = search(classKeys, cp)
        return if (index >= 0) classValues[index] else 0
    }

    fun decomposition(cp: Int, compatibility: Boolean): IntArray? {
        val keys = if (compatibility) compatibilityKeys else canonicalKeys
        val index = search(keys, cp)
        if (index < 0) return null
        val offsets = if (compatibility) compatibilityOffsets else canonicalOffsets
        val data = if (compatibility) compatibilityData else canonicalData
        return data.copyOfRange(offsets[index], offsets[index + 1])
    }

    fun composite(a: Int, b: Int): Int {
        val key = (a.toLong() shl 21) or b.toLong()
        val index = search(compositionKeys, key)
        return if (index >= 0) compositionValues[index] else -1
    }

    // Common Kotlin has no binarySearch for primitive arrays; that lives in
    // java.util.Arrays, which is not available here.
    private fun search(sorted: IntArray, target: Int): Int {
        var low = 0
        var high = sorted.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = sorted[mid]
            when {
                value < target -> low = mid + 1
                value > target -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun search(sorted: LongArray, target: Long): Int {
        var low = 0
        var high = sorted.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = sorted[mid]
            when {
                value < target -> low = mid + 1
                value > target -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    // --- decoding -----------------------------------------------------------

    private fun digit(c: Char): Int {
        val alphabet = io.github.mgilbir.ecma262.unicode.UnicodeTables.ALPHABET
        val index = alphabet.indexOf(c)
        check(index >= 0) { "corrupt normalisation table: '$c'" }
        return index
    }

    private class Reader(private val encoded: String) {
        private var at = 0
        fun hasMore() = at < encoded.length
        fun next(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val d = digit(encoded[at++])
                value = value or ((d and 31) shl shift)
                shift += 5
                if (d < 32) return value
            }
        }
    }

    private fun decodePairs(encoded: String): Pair<IntArray, IntArray> {
        val keys = ArrayList<Int>(1024)
        val values = ArrayList<Int>(1024)
        val reader = Reader(encoded)
        var previous = 0
        while (reader.hasMore()) {
            val cp = previous + reader.next()
            keys.add(cp)
            values.add(reader.next())
            previous = cp
        }
        return keys.toIntArray() to values.toIntArray()
    }

    private class Sequences(val keys: IntArray, val offsets: IntArray, val data: IntArray)

    private fun decodeSequences(encoded: String): Sequences {
        val keys = ArrayList<Int>(4096)
        val offsets = ArrayList<Int>(4096)
        val data = ArrayList<Int>(16384)
        val reader = Reader(encoded)
        var previous = 0
        while (reader.hasMore()) {
            val cp = previous + reader.next()
            keys.add(cp)
            offsets.add(data.size)
            val length = reader.next()
            repeat(length) { data.add(reader.next()) }
            previous = cp
        }
        offsets.add(data.size)
        return Sequences(keys.toIntArray(), offsets.toIntArray(), data.toIntArray())
    }

    private fun decodeComposition(encoded: String): Pair<LongArray, IntArray> {
        val keys = ArrayList<Long>(1024)
        val values = ArrayList<Int>(1024)
        val reader = Reader(encoded)
        var previous = 0
        while (reader.hasMore()) {
            val a = previous + reader.next()
            val b = reader.next()
            keys.add((a.toLong() shl 21) or b.toLong())
            values.add(reader.next())
            previous = a
        }
        return keys.toLongArray() to values.toIntArray()
    }
}

// ------------------------------------------------------------------ plumbing

private class IntArrayBuilder(capacity: Int) {
    private var array = IntArray(capacity)
    private var size = 0
    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }
    fun toIntArray(): IntArray = array.copyOf(size)
}

private fun codePointAt(text: String, index: Int): Int {
    val c = text[index]
    if (c.isHighSurrogate() && index + 1 < text.length) {
        val next = text[index + 1]
        // An unpaired surrogate is left as it is, which is what JavaScript does.
        if (next.isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (next.code - 0xDC00)
        }
    }
    return c.code
}

private fun fromCodePoints(cps: IntArray): String {
    val sb = StringBuilder(cps.size + 4)
    for (cp in cps) {
        if (cp <= 0xFFFF) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v ushr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
    return sb.toString()
}
