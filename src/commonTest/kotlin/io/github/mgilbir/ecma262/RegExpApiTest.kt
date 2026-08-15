package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The public API surface, checked against node's behaviour for the same
 * operations (`exec`, `test`, `String.prototype.replace`/`split`/`search`).
 */
class RegExpApiTest {

    // ------------------------------------------------------------- lastIndex

    @Test
    fun globalExecAdvancesLastIndexAndResetsOnFailure() {
        val re = RegExp.compile("a", "g")
        val seen = mutableListOf<Pair<Int, Int>>()
        while (true) {
            val m = re.exec("aXa") ?: break
            seen += m.index to re.lastIndex
        }
        assertEquals(listOf(0 to 1, 2 to 3), seen)
        assertEquals(0, re.lastIndex, "a failed match resets lastIndex")
    }

    @Test
    fun stickyMatchesOnlyAtLastIndex() {
        val re = RegExp.compile("a", "y")
        val seen = mutableListOf<Triple<Int, Int?, Int>>()
        for (li in 0..3) {
            re.lastIndex = li
            val m = re.exec("aXa")
            seen += Triple(li, m?.index, re.lastIndex)
        }
        assertEquals(
            listOf(
                Triple(0, 0, 1),
                Triple(1, null, 0),
                Triple(2, 2, 3),
                Triple(3, null, 0),
            ),
            seen,
        )
    }

    @Test
    fun nonGlobalIgnoresLastIndex() {
        val re = RegExp.compile("a")
        re.lastIndex = 2
        assertEquals(0, re.exec("aXa")?.index)
        assertEquals(2, re.lastIndex, "a non-global regexp must not touch lastIndex")
    }

    @Test
    fun testAdvancesLastIndexWhenGlobal() {
        val re = RegExp.compile("a", "g")
        assertTrue(re.test("aa")); assertEquals(1, re.lastIndex)
        assertTrue(re.test("aa")); assertEquals(2, re.lastIndex)
        assertFalse(re.test("aa")); assertEquals(0, re.lastIndex)
    }

    // --------------------------------------------------------------- replace

    private fun replace(pattern: String, flags: String, input: String, replacement: String) =
        RegExp.compile(pattern, flags).replace(input, replacement)

    @Test
    fun replaceSubstitutions() {
        assertEquals("Lovelace, Ada", replace("(\\w+) (\\w+)", "", "Ada Lovelace", "$2, $1"))
        assertEquals(
            "Lovelace, Ada",
            replace("(?<first>\\w+) (?<last>\\w+)", "", "Ada Lovelace", "\$<last>, \$<first>"),
        )
        assertEquals("a#b#c#", replace("\\d+", "g", "a1b22c333", "#"))
        assertEquals("a#b22c333", replace("\\d+", "", "a1b22c333", "#"))
        assertEquals("a[a|b|c]c", replace("b", "g", "abc", "[$`|$&|$']"))
        assertEquals("\$\$\$", replace("a", "g", "aaa", "$$"))
        assertEquals("<a><b>", replace("(a)|(b)", "g", "ab", "<$1$2>"))
        assertEquals("abc", replace("x", "g", "abc", "y"))
        assertEquals("-a-b-", replace("", "g", "ab", "-"))
        assertEquals("[a|]", replace("(a)(b)?", "g", "a", "[$1|$2]"))
        assertEquals("aaaa", replace("(a)", "g", "aa", "$1$1"))
    }

    /** An unusable `$` reference is emitted literally, as in JavaScript. */
    @Test
    fun invalidReplacementReferencesStayLiteral() {
        assertEquals("\$0", replace("(a)", "g", "a", "$0"))
        assertEquals("\$99", replace("(a)", "g", "a", "$99"))
        assertEquals("\$<nope>", replace("(a)", "g", "a", "\$<nope>"))
        assertEquals("\$<x>", replace("a", "g", "a", "\$<x>"))
    }

    @Test
    fun replaceWithFunction() {
        val out = RegExp.compile("\\d+", "g").replace("a1b22c") { m -> "<${m.value.length}>" }
        assertEquals("a<1>b<2>c", out)
    }

    // ----------------------------------------------------------------- split

    private fun split(pattern: String, input: String, limit: Int = -1) =
        RegExp.compile(pattern).split(input, limit)

    @Test
    fun splitBasics() {
        assertEquals(listOf("a", "b", "c"), split("\\d", "a1b2c"))
        assertEquals(listOf("a", "b", "c"), split("", "abc"))
        assertEquals(listOf("abc"), split("x", "abc"))
        assertEquals(listOf("a", "b"), split("\\s*", "ab"))
        assertEquals(listOf("", ""), split("b", "b"))
    }

    /** Separator captures are interleaved into the result. */
    @Test
    fun splitInterleavesCaptures() {
        assertEquals(listOf("a", "1", "b", "2", "c"), split("(\\d)", "a1b2c"))
        assertEquals(
            listOf("1", "a", null, "2", null, "b", "3"),
            split("(a)|(b)", "1a2b3"),
        )
    }

    @Test
    fun splitLimits() {
        assertEquals(listOf("a", "b"), split(",", "a,b,c", 2))
        assertEquals(emptyList(), split("-", "a-b-c", 0))
        assertEquals(listOf("a", "-"), split("(-)", "a-b", 2))
    }

    /** The empty subject is a special case in the specification. */
    @Test
    fun splitOfTheEmptyString() {
        assertEquals(emptyList(), split("", ""))
        assertEquals(listOf(""), split("a", ""))
    }

    // ---------------------------------------------------------------- search

    @Test
    fun search() {
        assertEquals(1, RegExp.compile("b").search("abc"))
        assertEquals(-1, RegExp.compile("z").search("abc"))
    }

    // --------------------------------------------------------------- findAll

    @Test
    fun findAllIgnoresLastIndex() {
        val re = RegExp.compile("a", "g")
        re.lastIndex = 2
        assertEquals(listOf(0, 2), re.findAll("aXa").map { it.index })
        assertEquals(2, re.lastIndex, "findAll must not disturb the cursor")
    }

    @Test
    fun findAllStepsPastEmptyMatches() {
        assertEquals(listOf(0, 1, 2), RegExp.compile("", "g").findAll("ab").map { it.index })
    }

    // ------------------------------------------------------------- accessors

    @Test
    fun accessorsReflectTheCompiledPattern() {
        val re = RegExp.compile("(?<a>x)(y)", "gimsu")
        assertEquals("(?<a>x)(y)", re.source)
        assertEquals("gimsu", re.flags.toString())
        assertTrue(re.global && re.ignoreCase && re.multiline && re.dotAll && re.unicode)
        assertFalse(re.sticky || re.hasIndices || re.unicodeSets)
        assertEquals(setOf("a"), re.groupNames)
        assertEquals(2, re.groupCount)
        assertEquals("/(?<a>x)(y)/gimsu", re.toString())
    }

    @Test
    fun matchResultAccessors() {
        val m = RegExp.compile("(?<num>\\d+)(x)?").exec("ab123")!!
        assertEquals("123", m.value)
        assertEquals(2, m.index)
        assertEquals(3, m.size)
        assertEquals("123", m[1])
        assertEquals("123", m["num"])
        assertNull(m[2])
        assertNull(m["missing"])
        assertEquals(2 until 5, m.range(1))
        assertNull(m.range(2))
        assertEquals(listOf("123", "123", null), m.groupValues())
        assertEquals(mapOf("num" to "123"), m.groups)
        assertEquals("ab123", m.input)
    }

    @Test
    fun compileOrNullSwallowsSyntaxErrors() {
        assertNull(RegExp.compileOrNull("("))
        assertNull(RegExp.compileOrNull("a", "zz"))
        assertEquals("a", RegExp.compileOrNull("a")?.source)
    }

    /** Strict syntax rejects the Annex B leniencies the default accepts. */
    @Test
    fun strictSyntaxMode() {
        assertEquals("\u0005", RegExp.compile("\\5").exec("\u0005")?.value)
        assertNull(RegExp.compileOrNullStrict("\\5"))
    }

    private fun RegExp.Companion.compileOrNullStrict(source: String): RegExp? =
        try {
            compile(source, Flags.NONE, Syntax.STRICT)
        } catch (_: RegExpSyntaxError) {
            null
        }
}
