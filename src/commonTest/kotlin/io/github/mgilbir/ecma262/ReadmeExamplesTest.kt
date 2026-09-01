package io.github.mgilbir.ecma262

import io.github.mgilbir.ecma262.date.EcmaTimeZone
import io.github.mgilbir.ecma262.date.makeDay
import io.github.mgilbir.ecma262.date.makeFullYear
import io.github.mgilbir.ecma262.date.makeTime
import io.github.mgilbir.ecma262.date.parseDateTimeString
import io.github.mgilbir.ecma262.date.timeClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executable versions of every example in README.md.
 *
 * Documentation that has never been run is a claim, not a fact; this keeps the
 * two from drifting apart.
 */
class ReadmeExamplesTest {

    @Test
    fun dates() {
        // Month is zero based, day of month is one based, and every field rolls.
        assertEquals(makeDay(2013.0, 0.0, 1.0), makeDay(2012.0, 12.0, 1.0))
        assertEquals(makeDay(2012.0, 2.0, 1.0), makeDay(2012.0, 1.0, 30.0))
        assertEquals(makeDay(2023.0, 11.0, 31.0), makeDay(2024.0, 0.0, 0.0))
        assertEquals(86400000.0, makeTime(24.0, 0.0, 0.0, 0.0))

        // Two-digit years are the twentieth century, and the rule stops dead at 99.
        assertEquals(1999.0, makeFullYear(99.0))
        assertEquals(100.0, makeFullYear(100.0))

        // Out of range is an Invalid Date, not an error.
        assertEquals(8.64e15, timeClip(8.64e15))
        assertTrue(timeClip(8.64e15 + 1.0).isNaN())

        // A date-only string is UTC in every zone; a bare date-time string is local.
        val utcMidnight = parseDateTimeString("2024-07-01T00:00:00Z")
        assertEquals(utcMidnight, parseDateTimeString("2024-07-01"))
        assertEquals(utcMidnight, parseDateTimeString("2024-07-01", EcmaTimeZone.fixed(330)))
        assertEquals(utcMidnight, parseDateTimeString("2024-07-01T00:00:00"))
        assertEquals(
            utcMidnight - 330 * 60000.0,
            parseDateTimeString("2024-07-01T00:00:00", EcmaTimeZone.fixed(330)),
        )
    }

    @Test
    fun quickStart() {
        val re = RegExp.compile("""(\d{4})-(\d{2})-(\d{2})""")
        val m = re.exec("Date: 2024-03-15")!!
        assertEquals("2024-03-15", m.value)
        assertEquals("2024", m[1])
        assertEquals(6, m.index)
    }

    @Test
    fun namedGroups() {
        val named = RegExp.compile("""(?<year>\d{4})-(?<month>\d{2})""")
        assertEquals("03", named.exec("2024-03")!!["month"])
    }

    @Test
    fun replacement() {
        assertEquals(
            "Lovelace, Ada",
            RegExp.compile("""(\w+) (\w+)""").replace("Ada Lovelace", "$2, $1"),
        )
    }

    @Test
    fun globalIterationMirrorsLastIndex() {
        val g = RegExp.compile("a", "g")
        assertEquals(0, g.exec("aXa")?.index)
        assertEquals(1, g.lastIndex)
        assertEquals(2, g.exec("aXa")?.index)
        assertEquals(3, g.lastIndex)
        assertNull(g.exec("aXa"))
        assertEquals(0, g.lastIndex)
    }

    @Test
    fun findAllIgnoresTheCursor() {
        assertEquals(
            listOf("1", "22", "333"),
            RegExp.compile("""\d+""").findAll("a1b22c333").map { it.value },
        )
    }

    @Test
    fun stepLimitIsDistinguishableFromNoMatch() {
        val re = RegExp.compile("(a+)+b")
        assertFailsWith<RegExpStepLimitError> { re.exec("a".repeat(30) + "X") }
    }

    @Test
    fun utf16OffsetsMatchJavaScript() {
        assertEquals(2, "😀".length)
        // Without `u`, `.` matches a single code unit — half of the pair.
        assertEquals(1, RegExp.compile(".").exec("😀")?.value?.length)
        assertEquals(2, RegExp.compile(".", "u").exec("😀")?.value?.length)
    }

    @Test
    fun unicodeVersionIsPinned() {
        assertEquals("17.0.0", io.github.mgilbir.ecma262.unicode.Unicode.VERSION)
    }

    @Test
    fun documentedV8DeviationHolds() {
        // README: node reports 2 here; the specification requires 3.
        assertEquals(3, RegExp.compile("\\B", "u").exec("b😀")?.index)
    }

    @Test
    fun strictSyntaxIsAvailable() {
        // Annex B by default: \5 with no group 5 is a legacy octal escape.
        assertEquals("\u0005", RegExp.compile("\\5").exec("\u0005")?.value)
        assertFailsWith<RegExpSyntaxError> {
            RegExp.compile("\\5", Flags.NONE, Syntax.STRICT)
        }
    }

    @Test
    fun documentedCompileLimits() {
        assertFailsWith<RegExpSyntaxError> { RegExp.compile("a{10001}") }
        assertFailsWith<RegExpSyntaxError> { RegExp.compile("(".repeat(300) + "a" + ")".repeat(300)) }
    }

    @Test
    fun vFlagFeaturesFromTheFeatureList() {
        assertEquals("b", RegExp.compile("[[a][b]]", "v").exec("b")?.value)
        assertEquals("z", RegExp.compile("[[a-z]--[aeiou]]", "v").exec("z")?.value)
        assertEquals("c", RegExp.compile("[[a-z]&&[b-d]]", "v").exec("c")?.value)
        assertEquals("abc", RegExp.compile("[\\q{abc}]", "v").exec("abc")?.value)
    }

    @Test
    fun featureListClaims() {
        // Variable-length lookbehind
        assertEquals("99", RegExp.compile("""(?<=\$\s*)\d+""").exec("total: $ 99")?.value)
        // Duplicate named groups across alternatives
        assertEquals("b", RegExp.compile("(?<x>a)|(?<x>b)").exec("b")?.get("x"))
        // Forward backreference
        assertTrue(RegExp.compile("""\1(a)""").test("a"))
        // Script and property escapes
        assertTrue(RegExp.compile("""^\p{Script=Greek}+$""", "u").test("αβγ"))
        assertTrue(RegExp.compile("""\p{Nd}+""", "u").test("৪"))
    }

    @Test
    fun splitInterleavesCapturesAsDocumented() {
        assertEquals(
            listOf("a", "1", "b", "2", "c"),
            RegExp.compile("""(\d)""").split("a1b2c"),
        )
    }

    @Test
    fun searchReturnsMinusOneWhenAbsent() {
        assertEquals(1, RegExp.compile("b").search("abc"))
        assertEquals(-1, RegExp.compile("z").search("abc"))
    }
}
