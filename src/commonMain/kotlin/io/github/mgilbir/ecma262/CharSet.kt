package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.unicode.RangeSet
import io.github.mgilbir.ecma262.unicode.Unicode

/**
 * A compiled set of code points, ready for matching.
 *
 * Case-insensitivity is resolved when the set is *built*, not when it is matched:
 * the set is expanded to its case closure so matching is a plain membership test.
 * This is both faster and more correct than folding at match time — folding a
 * range's endpoints breaks `[Y-b]/i`, which must match `y` and `a` alike.
 *
 * [negated] is applied after that closure, which is what makes `[^a]/i` reject
 * `A`: `A` is in the closure of `{a}`, so the negated set excludes it.
 */
internal class CharSet(
    internal val starts: IntArray,
    internal val ends: IntArray,
    private val negated: Boolean,
) {
    val rangeCount: Int get() = starts.size

    /** True when membership is inverted, i.e. this came from `[^…]`. */
    val isNegated: Boolean get() = negated

    fun matches(codePoint: Int): Boolean = contains(codePoint) != negated

    private fun contains(cp: Int): Boolean {
        val n = starts.size
        // Most sets are tiny (a literal, a couple of ranges); a scan beats the
        // branch-heavy binary search there.
        if (n <= 8) {
            for (i in 0 until n) {
                if (cp < starts[i]) return false
                if (cp <= ends[i]) return true
            }
            return false
        }
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < starts[mid] -> hi = mid - 1
                cp > ends[mid] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /** The single code point this set accepts, or -1 if it is not a singleton. */
    fun singleCodePointOrNull(): Int =
        if (!negated && starts.size == 1 && starts[0] == ends[0]) starts[0] else -1

    /** The two code points this set accepts, or null if it is not a pair. */
    fun twoCodePointsOrNull(): IntArray? {
        if (negated) return null
        return when {
            starts.size == 2 && starts[0] == ends[0] && starts[1] == ends[1] ->
                intArrayOf(starts[0], starts[1])
            else -> null
        }
    }

    internal companion object {
        const val MAX_CODE_POINT: Int = Unicode.MAX_CODE_POINT
    }
}

/** Sorted, disjoint, non-adjacent code point ranges as parallel start/end arrays. */
internal typealias Ranges = Pair<IntArray, IntArray>

/**
 * Set algebra on [Ranges], for the `v` flag's `&&` and `--` operators.
 *
 * All three operate by merging two already-sorted range lists in one pass.
 */
internal object RangeAlgebra {

    val EMPTY: Ranges = IntArray(0) to IntArray(0)

    fun intersect(a: Ranges, b: Ranges): Ranges {
        val (alo, ahi) = a
        val (blo, bhi) = b
        val lo = ArrayList<Int>()
        val hi = ArrayList<Int>()
        var i = 0
        var j = 0
        while (i < alo.size && j < blo.size) {
            val s = maxOf(alo[i], blo[j])
            val e = minOf(ahi[i], bhi[j])
            if (s <= e) {
                lo += s
                hi += e
            }
            if (ahi[i] < bhi[j]) i++ else j++
        }
        return lo.toIntArray() to hi.toIntArray()
    }

    fun complement(a: Ranges): Ranges {
        val (alo, ahi) = a
        val lo = ArrayList<Int>()
        val hi = ArrayList<Int>()
        var next = 0
        for (i in alo.indices) {
            if (alo[i] > next) {
                lo += next
                hi += alo[i] - 1
            }
            next = maxOf(next, ahi[i] + 1)
        }
        if (next <= Unicode.MAX_CODE_POINT) {
            lo += next
            hi += Unicode.MAX_CODE_POINT
        }
        return lo.toIntArray() to hi.toIntArray()
    }

    fun subtract(a: Ranges, b: Ranges): Ranges = intersect(a, complement(b))

    fun union(a: Ranges, b: Ranges): Ranges =
        CharSetBuilder().addRanges(a).addRanges(b).buildRanges()
}

/**
 * Accumulates code point ranges, then resolves them into a [CharSet].
 *
 * Ranges may be added in any order and may overlap; [build] sorts and merges
 * them before applying the case closure.
 */
internal class CharSetBuilder {
    private var lo = IntArray(8)
    private var hi = IntArray(8)
    private var n = 0

    fun add(codePoint: Int): CharSetBuilder = addRange(codePoint, codePoint)

    fun addRange(start: Int, endInclusive: Int): CharSetBuilder {
        if (start > endInclusive) return this
        if (n == lo.size) grow()
        lo[n] = start
        hi[n] = endInclusive
        n++
        return this
    }

    fun addAll(set: RangeSet): CharSetBuilder {
        for (i in 0 until set.size) addRange(set.startAt(i), set.endAt(i))
        return this
    }

    /** Adds every code point *not* in [set] — how `\D`, `\W`, `\S`, `\P{…}` are built. */
    fun addComplement(set: RangeSet): CharSetBuilder {
        var next = 0
        for (i in 0 until set.size) {
            val s = set.startAt(i)
            if (s > next) addRange(next, s - 1)
            next = maxOf(next, set.endAt(i) + 1)
        }
        if (next <= Unicode.MAX_CODE_POINT) addRange(next, Unicode.MAX_CODE_POINT)
        return this
    }

    fun addAllOf(other: CharSetBuilder): CharSetBuilder {
        for (i in 0 until other.n) addRange(other.lo[i], other.hi[i])
        return this
    }

    /**
     * Adds every code point outside [set]'s ranges.
     *
     * Used for `\W`, whose CharSet is the complement of `WordCharacters` — and
     * under `/iu` that word set already includes its case extensions (`ſ`, `K`),
     * so the complement must be taken from the *closed* set. Complementing first
     * and closing afterwards would wrongly let `\W` match `ſ`.
     */
    fun addComplementOf(set: CharSet): CharSetBuilder {
        var next = 0
        for (i in set.starts.indices) {
            val s = set.starts[i]
            if (s > next) addRange(next, s - 1)
            next = maxOf(next, set.ends[i] + 1)
        }
        if (next <= Unicode.MAX_CODE_POINT) addRange(next, Unicode.MAX_CODE_POINT)
        return this
    }

    private fun grow() {
        lo = lo.copyOf(lo.size * 2)
        hi = hi.copyOf(hi.size * 2)
    }

    fun isEmpty(): Boolean = n == 0

    fun build(ignoreCase: Boolean, unicodeMode: Boolean, negated: Boolean = false): CharSet {
        var merged = normalize()
        if (ignoreCase) merged = applyCaseClosure(merged, unicodeMode)
        return CharSet(merged.first, merged.second, negated)
    }

    /** Sorted, merged ranges with no case closure applied. */
    fun buildRanges(): Ranges = normalize()

    fun addRanges(r: Ranges): CharSetBuilder {
        val (lo2, hi2) = r
        for (i in lo2.indices) addRange(lo2[i], hi2[i])
        return this
    }

    /** Sorts by start and merges overlapping or adjacent ranges. */
    private fun normalize(): Pair<IntArray, IntArray> {
        if (n == 0) return IntArray(0) to IntArray(0)

        val order = (0 until n).sortedBy { lo[it] }
        val outLo = IntArray(n)
        val outHi = IntArray(n)
        var m = 0
        for (idx in order) {
            val s = lo[idx]
            val e = hi[idx]
            if (m > 0 && s <= outHi[m - 1] + 1) {
                if (e > outHi[m - 1]) outHi[m - 1] = e
            } else {
                outLo[m] = s
                outHi[m] = e
                m++
            }
        }
        return outLo.copyOf(m) to outHi.copyOf(m)
    }

    /**
     * Adds every case-equivalent of every member.
     *
     * Only code points that actually have a case partner can contribute, and
     * those are a sorted table of a few thousand entries. Rather than test every
     * one of them for membership — which made a trivial `[a-z]/i` cost hundreds
     * of microseconds to compile — this binary-searches into that table once per
     * range and walks only the members the range really covers.
     */
    private fun applyCaseClosure(
        ranges: Pair<IntArray, IntArray>,
        unicodeMode: Boolean,
    ): Pair<IntArray, IntArray> {
        val (rlo, rhi) = ranges
        if (rlo.isEmpty()) return ranges

        val members = Unicode.caseOrbitMembers(unicodeMode)
        val extra = CharSetBuilder()

        for (i in rlo.indices) {
            var idx = lowerBound(members, rlo[i])
            val hi = rhi[i]
            while (idx < members.size && members[idx] <= hi) {
                val member = members[idx]
                // Adding partners already inside the range is harmless; the union
                // below merges them away.
                var partner = Unicode.caseOrbitNext(member, unicodeMode)
                while (partner != member) {
                    extra.add(partner)
                    partner = Unicode.caseOrbitNext(partner, unicodeMode)
                }
                idx++
            }
        }

        if (extra.isEmpty()) return ranges

        val combined = CharSetBuilder()
        for (i in rlo.indices) combined.addRange(rlo[i], rhi[i])
        combined.addAllOf(extra)
        return combined.normalize()
    }

    /** Index of the first element of the ascending [a] that is >= [target]. */
    private fun lowerBound(a: IntArray, target: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
