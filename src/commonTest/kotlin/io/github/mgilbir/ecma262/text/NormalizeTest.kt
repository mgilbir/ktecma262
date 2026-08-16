package io.github.mgilbir.ecma262.text

import io.github.mgilbir.ecma262.unicode.UnicodeTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `normalize`, against Unicode's own conformance suite and against node.
 *
 * `NormalizationTest.txt` is the authority for UAX #15: its expectations are
 * Unicode's, produced by no implementation, which makes it the counterpart of
 * the Test262 fixture used for numbers. Generation refuses to write the fixture
 * if node disagrees with any row, so the two sources are known to agree before
 * either is trusted.
 *
 * The file also states invariants stronger than "source maps to this": each of
 * the five columns must map to the same place under each form. Checking those
 * is what catches an implementation that is right about the examples and wrong
 * about idempotence.
 */
class NormalizeTest {

    private class Reader(private val encoded: String) {
        private var at = 0
        fun hasMore() = at < encoded.length
        fun next(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val d = UnicodeTables.ALPHABET.indexOf(encoded[at++])
                check(d >= 0) { "corrupt fixture" }
                value = value or ((d and 31) shl shift)
                shift += 5
                if (d < 32) return value
            }
        }
        fun sequence(): String {
            val length = next()
            val sb = StringBuilder(length + 2)
            repeat(length) {
                val cp = next()
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
    }

    @Test
    fun unicodeConformanceSuite() {
        val reader = Reader(NormalizationFixture.ROW_PARTS.joinToString(""))
        var rows = 0
        while (reader.hasMore()) {
            val source = reader.sequence()
            val nfc = reader.sequence()
            val nfd = reader.sequence()
            val nfkc = reader.sequence()
            val nfkd = reader.sequence()
            rows++

            // The invariants NormalizationTest.txt states for every row.
            for (column in listOf(source, nfc, nfd)) {
                assertEquals(nfc, column.normalize(NormalizationForm.NFC), "NFC of column in row $rows")
                assertEquals(nfd, column.normalize(NormalizationForm.NFD), "NFD of column in row $rows")
            }
            for (column in listOf(nfkc, nfkd)) {
                assertEquals(nfkc, column.normalize(NormalizationForm.NFC), "NFC of K column in row $rows")
                assertEquals(nfkd, column.normalize(NormalizationForm.NFD), "NFD of K column in row $rows")
            }
            for (column in listOf(source, nfc, nfd, nfkc, nfkd)) {
                assertEquals(nfkc, column.normalize(NormalizationForm.NFKC), "NFKC in row $rows")
                assertEquals(nfkd, column.normalize(NormalizationForm.NFKD), "NFKD in row $rows")
            }
        }
        assertEquals(NormalizationFixture.ROW_COUNT, rows)
        assertTrue(rows > 19_000, "the conformance suite shrank unexpectedly: $rows rows")
    }

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        h = h xor 0x7Cu
        h *= 16777619u
        return h
    }

    private fun codePointToString(cp: Int): String {
        if (cp <= 0xFFFF) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
            .concatToString()
    }

    /**
     * Every code point, in every form.
     *
     * The conformance file covers the characters with interesting mappings; this
     * covers the rest, where the answer is usually "itself" and an
     * implementation that quietly mangles one would otherwise go unnoticed.
     */
    @Test
    fun everyCodePointMatchesNode() {
        var nfc = 2166136261u
        var nfd = 2166136261u
        var nfkc = 2166136261u
        var nfkd = 2166136261u
        var counted = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp in 0xD800..0xDFFF) {
                cp++
                continue
            }
            val s = codePointToString(cp)
            nfc = fnv1a(nfc, s.normalize(NormalizationForm.NFC))
            nfd = fnv1a(nfd, s.normalize(NormalizationForm.NFD))
            nfkc = fnv1a(nfkc, s.normalize(NormalizationForm.NFKC))
            nfkd = fnv1a(nfkd, s.normalize(NormalizationForm.NFKD))
            counted++
            cp++
        }
        assertEquals(NormalizationFixture.CODE_POINTS, counted)
        assertEquals(NormalizationFixture.NFC_HASH, nfc, "NFC differs from node somewhere")
        assertEquals(NormalizationFixture.NFD_HASH, nfd, "NFD differs from node somewhere")
        assertEquals(NormalizationFixture.NFKC_HASH, nfkc, "NFKC differs from node somewhere")
        assertEquals(NormalizationFixture.NFKD_HASH, nfkd, "NFKD differs from node somewhere")
    }

    /** Normalising twice must change nothing the second time. */
    @Test
    fun normalisationIsIdempotent() {
        val reader = Reader(NormalizationFixture.ROW_PARTS.joinToString(""))
        var checked = 0
        while (reader.hasMore() && checked < 40_000) {
            val column = reader.sequence()
            for (form in NormalizationForm.entries) {
                val once = column.normalize(form)
                assertEquals(once, once.normalize(form), "$form is not idempotent")
            }
            checked++
        }
        assertTrue(checked > 1_000)
    }

    /**
     * Readable cases, so a failure is not only a hash.
     *
     * Written with escapes rather than literal characters: the difference
     * between a composed and a decomposed form is invisible on screen, and an
     * editor or a copy-paste can silently convert one into the other — which
     * would turn these into assertions that a string equals itself.
     */
    @Test
    fun readableCases() {
        val composedE = "\u00E9" // é
        val decomposedE = "e\u0301" // e + combining acute
        assertEquals(composedE, composedE.normalize(NormalizationForm.NFC))
        assertEquals(composedE, decomposedE.normalize(NormalizationForm.NFC))
        assertEquals(decomposedE, composedE.normalize(NormalizationForm.NFD))
        assertEquals(decomposedE, decomposedE.normalize(NormalizationForm.NFD))
        // NFC is the default form, as in JavaScript.
        assertEquals(composedE, decomposedE.normalize())

        // Compatibility unpicks the ligature; canonical leaves it alone.
        assertEquals("fi", "\uFB01".normalize(NormalizationForm.NFKC))
        assertEquals("\uFB01", "\uFB01".normalize(NormalizationForm.NFC))

        // Marks are reordered by combining class: U+0323 (220) before U+0307 (230),
        // whichever order they arrive in.
        assertEquals("q\u0323\u0307", "q\u0307\u0323".normalize(NormalizationForm.NFD))
        assertEquals("q\u0323\u0307", "q\u0323\u0307".normalize(NormalizationForm.NFD))

        // Hangul, which is arithmetic rather than tabulated.
        assertEquals("\u1100\u1161\u11A8", "\uAC01".normalize(NormalizationForm.NFD))
        assertEquals("\uAC01", "\u1100\u1161\u11A8".normalize(NormalizationForm.NFC))
        assertEquals("\uAC00", "\u1100\u1161".normalize(NormalizationForm.NFC))

        // Untouched.
        assertEquals("", "".normalize())
        assertEquals("abc", "abc".normalize(NormalizationForm.NFKD))

        // A lone surrogate survives, as it does in JavaScript.
        assertEquals("\uD800", "\uD800".normalize())
        assertEquals("a\uDC00b", "a\uDC00b".normalize(NormalizationForm.NFKC))
    }
}
