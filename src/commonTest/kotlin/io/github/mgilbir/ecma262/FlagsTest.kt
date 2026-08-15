package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagsTest {

    @Test
    fun parsesEachFlag() {
        assertTrue(Flags.parse("d").hasIndices)
        assertTrue(Flags.parse("g").global)
        assertTrue(Flags.parse("i").ignoreCase)
        assertTrue(Flags.parse("m").multiline)
        assertTrue(Flags.parse("s").dotAll)
        assertTrue(Flags.parse("u").unicode)
        assertTrue(Flags.parse("v").unicodeSets)
        assertTrue(Flags.parse("y").sticky)
    }

    @Test
    fun emptyStringIsNoFlags() {
        val f = Flags.parse("")
        assertEquals(Flags.NONE, f)
        assertEquals("", f.toString())
        assertFalse(f.global)
    }

    /**
     * `toString` must emit the spec's canonical order regardless of input order.
     * Verified against node: `new RegExp("a", "yvmigd").flags === "dgimvy"`.
     */
    @Test
    fun toStringUsesCanonicalOrder() {
        assertEquals("dgimvy", Flags.parse("yvmigd").toString())
        assertEquals("dgimsuy", Flags.parse("yusmigd").toString())
        assertEquals("gi", Flags.parse("ig").toString())
    }

    @Test
    fun roundTripsThroughCanonicalString() {
        for (s in listOf("", "g", "gi", "dgimsuy", "dgimsvy", "y", "su")) {
            val f = Flags.parse(s)
            assertEquals(f, Flags.parse(f.toString()), "round trip of '$s'")
        }
    }

    @Test
    fun rejectsUnknownFlag() {
        val e = assertFailsWith<RegExpSyntaxError> { Flags.parse("gq") }
        assertEquals(1, e.position)
        assertTrue("'q'" in e.message!!, "message should name the flag: ${e.message}")
    }

    @Test
    fun rejectsDuplicateFlag() {
        val e = assertFailsWith<RegExpSyntaxError> { Flags.parse("gg") }
        assertEquals(1, e.position)
    }

    /** `u` and `v` together is a SyntaxError in JavaScript. */
    @Test
    fun rejectsUnicodeAndUnicodeSetsTogether() {
        assertFailsWith<RegExpSyntaxError> { Flags.parse("uv") }
        assertFailsWith<RegExpSyntaxError> { Flags.parse("vu") }
        assertFailsWith<RegExpSyntaxError> { Flags.parse("guvi") }
    }

    @Test
    fun unicodeModeCoversBothUAndV() {
        assertTrue(Flags.parse("u").isUnicodeMode)
        assertTrue(Flags.parse("v").isUnicodeMode)
        assertFalse(Flags.parse("gimsy").isUnicodeMode)
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertNull(Flags.parseOrNull("uv"))
        assertNull(Flags.parseOrNull("q"))
        assertEquals(Flags.parse("gi"), Flags.parseOrNull("gi"))
    }

    @Test
    fun setOperations() {
        val gi = Flags.GLOBAL + Flags.IGNORE_CASE
        assertTrue(Flags.GLOBAL in gi)
        assertTrue(Flags.IGNORE_CASE in gi)
        assertFalse(Flags.STICKY in gi)
        assertTrue(gi in gi)
        assertEquals(Flags.GLOBAL, gi - Flags.IGNORE_CASE)
        assertEquals("gi", gi.toString())
    }
}
