package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays every recorded node result and requires an exact match.
 *
 * This is the primary conformance check: the expectations are not hand-written
 * readings of the specification but observations of a real ECMA-262 engine.
 */
class DifferentialTest {

    private fun describe(s: String): String = buildString {
        for (ch in s) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 || ch.code > 0x7e ->
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> append(ch)
            }
        }
    }

    private fun render(groups: List<String?>): String =
        groups.joinToString(", ") { if (it == null) "null" else "\"${describe(it)}\"" }

    @Test
    fun reproducesNodeOnTheWholeCorpus() {
        val cases = DiffFixture.all()
        assertTrue(cases.size > 20_000, "fixture looks truncated: ${cases.size} cases")

        val failures = mutableListOf<String>()
        var syntaxChecked = 0
        var matchChecked = 0
        var noMatchChecked = 0

        for (c in cases) {
            if (c.index == DiffFixture.SYNTAX_ERROR) {
                syntaxChecked++
                val compiled = try {
                    RegExp.compile(c.pattern, c.flags)
                } catch (_: RegExpSyntaxError) {
                    null
                }
                if (compiled != null) {
                    failures += "$c: expected a SyntaxError, but it compiled"
                }
                continue
            }

            val re = try {
                RegExp.compile(c.pattern, c.flags)
            } catch (e: RegExpSyntaxError) {
                failures += "$c: unexpected SyntaxError: ${e.message}"
                continue
            }

            val actual = try {
                re.exec(c.input!!)
            } catch (e: RegExpStepLimitError) {
                failures += "$c: step limit hit (${e.message})"
                continue
            }

            if (c.index == DiffFixture.NO_MATCH) {
                noMatchChecked++
                if (actual != null) {
                    failures += "$c: expected no match, got \"${describe(actual.value)}\" at ${actual.index}"
                }
                continue
            }

            matchChecked++
            if (actual == null) {
                failures += "$c: expected a match at ${c.index} (${render(c.groups)}), got none"
                continue
            }
            if (actual.index != c.index) {
                failures += "$c: index ${actual.index}, expected ${c.index}"
                continue
            }
            val actualGroups = actual.groupValues()
            if (actualGroups != c.groups) {
                failures += "$c: groups [${render(actualGroups)}], expected [${render(c.groups)}]"
            }
        }

        assertTrue(syntaxChecked > 0 && matchChecked > 0 && noMatchChecked > 0, "corpus lost a category")
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} cases disagree with ${DiffFixture.ORACLE}:\n" +
                failures.take(40).joinToString("\n"),
        )
    }

    /** Group names must resolve the same way the oracle's `groups` object does. */
    @Test
    fun namedGroupAccess() {
        val re = RegExp.compile("(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})")
        val m = re.exec("Date: 2024-03-15")!!
        assertEquals("2024", m["year"])
        assertEquals("03", m["month"])
        assertEquals("15", m["day"])
        assertEquals(setOf("year", "month", "day"), re.groupNames)
        assertEquals(6 until 10, m.range("year"))
    }

    /** ES2022 duplicate names: the participating alternative wins. */
    @Test
    fun duplicateNamedGroupsResolveToTheParticipatingOne() {
        val re = RegExp.compile("(?<x>a)|(?<x>b)")
        assertEquals("b", re.exec("b")!!["x"])
        assertEquals("a", re.exec("a")!!["x"])
    }
}
