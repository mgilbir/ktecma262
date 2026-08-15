package io.github.mgilbir.ecma262.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnicodeTest {

    /** FNV-1a over "start-end,start-end,…" — must match the JS side in verify-against-node.mjs. */
    private fun fingerprint(rs: RangeSet): UInt {
        val sb = StringBuilder()
        for (i in 0 until rs.size) {
            if (i > 0) sb.append(',')
            sb.append(rs.startAt(i)).append('-').append(rs.endAt(i))
        }
        var h = 2166136261u
        for (c in sb) {
            h = h xor c.code.toUInt()
            h *= 16777619u
        }
        return h
    }

    private fun tableLookup(qualified: String): RangeSet? {
        val slash = qualified.indexOf('/')
        val table = qualified.substring(0, slash)
        val key = qualified.substring(slash + 1)
        return when (table) {
            "generalCategory" -> Unicode.generalCategory(key)
            "script" -> Unicode.script(key)
            "scriptExtensions" -> Unicode.scriptExtensions(key)
            "binary" -> Unicode.binaryProperty(key)
            else -> throw IllegalArgumentException("unknown table $table")
        }
    }

    /**
     * Every property decoded from the shipped tables must reproduce the exact
     * range list node reported when the fixture was generated. This is what
     * keeps a table or codec change from silently altering \p{...} semantics.
     */
    @Test
    fun decodedTablesMatchOracleFingerprints() {
        assertTrue(UnicodePropertyFixture.expected.size > 400, "fixture looks truncated")
        val failures = mutableListOf<String>()
        for ((name, expected) in UnicodePropertyFixture.expected) {
            val rs = tableLookup(name)
            if (rs == null) {
                failures += "$name: not resolvable"
                continue
            }
            val (expectedCount, expectedHash) = expected
            if (rs.size != expectedCount) {
                failures += "$name: ${rs.size} ranges, expected $expectedCount"
                continue
            }
            val actual = fingerprint(rs)
            if (actual != expectedHash) failures += "$name: fingerprint $actual != $expectedHash"
        }
        assertTrue(failures.isEmpty(), "table mismatches:\n" + failures.take(20).joinToString("\n"))
    }

    @Test
    fun tablesAreBuiltFromTheOraclesUnicodeVersion() {
        assertEquals(
            UnicodePropertyFixture.ORACLE_UNICODE_VERSION.substringBefore('.'),
            Unicode.VERSION.substringBefore('.'),
            "UCD major version must match the oracle engine's",
        )
    }

    @Test
    fun resolvesLonePropertyNamesAndValues() {
        assertNotNull(Unicode.resolveProperty("Lu"))
        assertNotNull(Unicode.resolveProperty("Letter"))
        assertNotNull(Unicode.resolveProperty("White_Space"))
        assertNotNull(Unicode.resolveProperty("Any"))
        assertNotNull(Unicode.resolveProperty("ASCII"))
        assertNotNull(Unicode.resolveProperty("Assigned"))
    }

    @Test
    fun resolvesQualifiedForms() {
        assertNotNull(Unicode.resolveProperty("General_Category=Lu"))
        assertNotNull(Unicode.resolveProperty("gc=Lu"))
        assertNotNull(Unicode.resolveProperty("Script=Greek"))
        assertNotNull(Unicode.resolveProperty("sc=Grek"))
        assertNotNull(Unicode.resolveProperty("Script_Extensions=Greek"))
        assertNotNull(Unicode.resolveProperty("scx=Grek"))
    }

    /** ECMA-262 matches property names case-sensitively; `\p{letter}` is a SyntaxError. */
    @Test
    fun propertyMatchingIsCaseSensitive() {
        assertNull(Unicode.resolveProperty("letter"))
        assertNull(Unicode.resolveProperty("lu"))
        assertNull(Unicode.resolveProperty("white_space"))
        assertNull(Unicode.resolveProperty("Script=greek"))
    }

    @Test
    fun rejectsUnknownProperties() {
        assertNull(Unicode.resolveProperty("Foo"))
        assertNull(Unicode.resolveProperty("Bogus=Thing"))
        assertNull(Unicode.resolveProperty("Script=Nonesuch"))
        // A property name is not valid as a General_Category value and vice versa.
        assertNull(Unicode.resolveProperty("gc=White_Space"))
    }

    @Test
    fun generalCategoryGroupsAreUnionsOfTheirMembers() {
        val l = Unicode.generalCategory("L")!!
        for (cp in listOf('a'.code, 'A'.code, 0x01C5, 0x02B0, 0x00AA)) {
            assertTrue(l.contains(cp), "L should contain U+${cp.toString(16)}")
        }
        assertFalse(l.contains('1'.code))
        assertTrue(Unicode.generalCategory("N")!!.contains('7'.code))
    }

    @Test
    fun simpleCaseFoldingMatchesKnownValues() {
        assertEquals('a'.code, Unicode.simpleCaseFold('A'.code))
        assertEquals('a'.code, Unicode.simpleCaseFold('a'.code))
        assertEquals(0x3C3, Unicode.simpleCaseFold(0x3A3)) // Σ -> σ
        assertEquals(0x3C3, Unicode.simpleCaseFold(0x3C2)) // ς -> σ (final sigma folds)
        assertEquals(0x69, Unicode.simpleCaseFold(0x49))   // I -> i
        assertEquals(0x3B9, Unicode.simpleCaseFold(0x345)) // combining iota subscript
        assertEquals('1'.code, Unicode.simpleCaseFold('1'.code))
    }

    @Test
    fun simpleUppercaseMatchesKnownValues() {
        assertEquals('A'.code, Unicode.simpleUppercase('a'.code))
        assertEquals(0x3A3, Unicode.simpleUppercase(0x3C3)) // σ -> Σ
        assertEquals(0x1E60, Unicode.simpleUppercase(0x1E61))
        assertEquals('Z'.code, Unicode.simpleUppercase('Z'.code))
        // ß has no single-character uppercase, so it maps to itself.
        assertEquals(0xDF, Unicode.simpleUppercase(0xDF))
    }

    @Test
    fun identifierClassification() {
        assertTrue(Unicode.isIdentifierStart('$'.code))
        assertTrue(Unicode.isIdentifierStart('_'.code))
        assertTrue(Unicode.isIdentifierStart('A'.code))
        assertTrue(Unicode.isIdentifierStart(0x3B1)) // α
        assertFalse(Unicode.isIdentifierStart('1'.code))
        assertFalse(Unicode.isIdentifierStart('-'.code))

        assertTrue(Unicode.isIdentifierPart('1'.code))
        assertTrue(Unicode.isIdentifierPart('$'.code))
        assertTrue(Unicode.isIdentifierPart(0x200C)) // ZWNJ
        assertTrue(Unicode.isIdentifierPart(0x200D)) // ZWJ
        assertFalse(Unicode.isIdentifierPart('-'.code))
        assertFalse(Unicode.isIdentifierPart(' '.code))
    }

    @Test
    fun astralPropertiesResolve() {
        val deseret = Unicode.script("Deseret")!!
        assertTrue(deseret.contains(0x10400))
        assertFalse(deseret.contains('a'.code))
        // Emoji properties: absent from the Go implementation, which reported them as errors.
        assertTrue(Unicode.binaryProperty("Extended_Pictographic")!!.contains(0x1F600))
        assertTrue(Unicode.binaryProperty("Emoji_Presentation")!!.contains(0x1F600))
    }
}
