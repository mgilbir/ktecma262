package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Annex B's fallback for an invalid `\c`.
 *
 * `ExtendedAtom :: \ [lookahead = c]` and `ClassAtomNoDash :: \ [lookahead = c]`
 * both denote the backslash **alone** — the `c` is left for the next atom
 * rather than swallowed into a two-character unit. Nothing distinguishes the
 * two readings until something binds to the `c`, which is why this survived
 * until a fuzz run happened to quantify one.
 *
 * Every expectation here is node's actual output.
 */
class ControlEscapeTest {

    private fun matches(source: String, flags: String, input: String): List<Pair<Int, String>> =
        RegExp.compile(source, flags).findAll(input).map { it.index to (it[0] ?: "") }

    /**
     * The case the nightly fuzzer found, verbatim.
     *
     * Reading `\c` as one atom makes `*` optional over both characters, so a
     * bare "a" matched. `a` `\` `c*` requires a literal backslash, so it does
     * not match at all.
     */
    @Test
    fun quantifierBindsToTheCNotToBothCharacters() {
        // "⃣\ud83dabya" — the fuzzer's input, lone surrogate included.
        val input = "⃣" + "\ud83d" + "abya"
        assertEquals(emptyList(), matches("a\\c*{?", "ig", input))

        assertEquals(emptyList(), matches("a\\c*", "g", "abya"))
        assertEquals(listOf(0 to "a\\ccc"), matches("a\\c*", "g", "a\\cccb"))
        assertEquals(listOf(1 to "\\ccc"), matches("\\c*", "g", "a\\ccc"))
        assertEquals(listOf(1 to "\\cc"), matches("\\c{2}", "g", "a\\ccb"))
    }

    /** With nothing bound to it, `\c` still spells the two literal characters. */
    @Test
    fun bareInvalidControlEscapeIsBackslashThenC() {
        assertEquals(listOf(1 to "\\c"), matches("\\c", "g", "x\\cy"))
        assertEquals(listOf(1 to "\\c1"), matches("\\c1", "g", "x\\c1y"))
        assertEquals(listOf(0 to "\\c-"), matches("\\c-", "g", "\\c-"))
    }

    /**
     * In a class the `c` is a class atom in its own right, so it can open a
     * range: `[\c-z]` is the backslash plus `c-z`, and does not contain `-`.
     */
    @Test
    fun inClassTheCCanOpenARange() {
        assertEquals(listOf(0 to "\\", 1 to "c"), matches("[\\c]", "g", "\\c-z"))
        assertEquals(
            listOf(0 to "\\", 1 to "c", 2 to "d", 3 to "z"),
            matches("[\\c-z]", "g", "\\cdz-"),
        )
    }

    /** A valid control escape is unaffected. */
    @Test
    fun validControlEscapesStillFold() {
        assertEquals(listOf(1 to "\u0001"), matches("\\cA", "g", "x\u0001y"))
        assertEquals(listOf(0 to "\u001A"), matches("\\cz", "g", "\u001A"))
        // Annex B admits digits and `_` as the control letter, but only in a class.
        assertEquals(listOf(0 to "\u0011"), matches("[\\c1]", "g", "\u0011"))
    }

    /**
     * A valid `\cX` works inside a class in every mode, including `v`.
     *
     * The `v` class-set path had no `c` branch at all, so `[\cf_]` was rejected
     * as an invalid escape while node accepted it. That is the second bug of
     * this exact shape - `\0` was the first, in 0.1.1 - so the two class paths
     * are now checked against each other rather than only against examples.
     */
    @Test
    fun controlEscapesWorkInsideClassesInEveryMode() {
        for (flags in listOf("", "u", "v", "iu", "iv")) {
            assertEquals(
                listOf(0 to "\u0006"),
                matches("[\\cf_]", flags + "g", "\u0006"),
                "[\\cf_] must match U+0006 under /$flags",
            )
            assertEquals(
                listOf(0 to "_"),
                matches("[\\cf_]", flags + "g", "_"),
                "[\\cf_] must still match _ under /$flags",
            )
            assertEquals(listOf(0 to "\u0001"), matches("[\\cA]", flags + "g", "\u0001"))
        }
        // The whole failing case from the fuzzer, kept as it was generated.
        assertEquals(listOf("."), RegExp.compile("=XwXd[\\cf_]bX", "vi").split(".", 5))
    }

    /** Unicode mode has no Annex B fallback: an invalid `\c` is a SyntaxError. */
    @Test
    fun unicodeModeRejectsInvalidControlEscapes() {
        for (flags in listOf("u", "v")) {
            assertFailsWith<RegExpSyntaxError>("/\\c*/$flags must not parse") {
                RegExp.compile("a\\c*", flags)
            }
            assertFailsWith<RegExpSyntaxError>("/[\\c]/$flags must not parse") {
                RegExp.compile("[\\c]", flags)
            }
        }
        assertFailsWith<RegExpSyntaxError> {
            RegExp.compile("a\\c*", Flags.parse(""), Syntax.STRICT)
        }
    }
}
