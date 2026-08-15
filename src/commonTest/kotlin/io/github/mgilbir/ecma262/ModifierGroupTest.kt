package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Regexp modifiers: `(?i:…)`, `(?-i:…)`, `(?i-ms:…)`.
 *
 * They turn `i`, `m` and `s` on or off for one subexpression. Every expectation
 * here was taken from node.
 */
class ModifierGroupTest {

    private fun match(pattern: String, input: String, flags: String = ""): String? =
        RegExp.compile(pattern, flags).exec(input)?.value

    private fun rejects(pattern: String, flags: String = "") {
        assertFailsWith<RegExpSyntaxError>("/$pattern/$flags should be a SyntaxError") {
            RegExp.compile(pattern, flags)
        }
    }

    private fun accepts(pattern: String, flags: String = "") {
        try {
            RegExp.compile(pattern, flags)
        } catch (e: RegExpSyntaxError) {
            fail("/$pattern/$flags should compile: ${e.message}")
        }
    }

    @Test
    fun onlyIMSMayBeModified() {
        assertEquals("A", match("(?i:a)", "A"))
        assertEquals("b", match("(?m:^b)", "a\nb"))
        assertEquals("\n", match("(?s:.)", "\n"))
        for (f in listOf("u", "v", "g", "y", "d", "x")) rejects("(?$f:a)")
    }

    @Test
    fun modifiersCanBeRemoved() {
        assertNull(match("(?-i:a)", "A", "i"))
        assertEquals("a", match("(?-i:a)", "a", "i"))
        assertNull(match("(?-ims:a)", "A", "ims"))
        accepts("(?i-m:a)")
        accepts("(?im-s:a)")
    }

    @Test
    fun malformedModifierGroups() {
        rejects("(?-:a)") // no modifiers at all
        rejects("(?i-i:a)") // same flag added and removed
        rejects("(?ii:a)") // repeated
        rejects("(?mm:a)")
        rejects("(?s-ss:a)")
        // The ordinary non-capturing group is untouched.
        assertEquals("a", match("(?:a)", "a"))
    }

    /** A modifier applies to its own subexpression and no further. */
    @Test
    fun scopeIsLexical() {
        assertNull(match("(?i:a)b", "AB"))
        assertEquals("Ab", match("(?i:a)b", "Ab"))
        assertEquals("aBc", match("a(?i:b)c", "aBc"))
        assertNull(match("a(?i:b)c", "ABc"))
    }

    @Test
    fun modifiersNest() {
        assertEquals("aB", match("(?i:(?-i:a)b)", "aB"))
        assertNull(match("(?i:(?-i:a)b)", "AB"))
    }

    /**
     * A backreference's case sensitivity comes from where the *reference* sits,
     * not where the group was captured.
     */
    @Test
    fun backreferenceUsesItsOwnPosition() {
        assertNull(match("(?i:(a))\\1", "aA"))
        assertEquals("aa", match("(?i:(a))\\1", "aa"))
        assertEquals("aA", match("(?i:(a)\\1)", "aA"))
    }

    @Test
    fun affectsClassesAndEscapes() {
        assertEquals("A", match("(?i:[a-z])", "A"))
        assertEquals("ſ", match("(?i:\\w)", "ſ", "u"))
        assertEquals("a", match("(?i:\\p{Lu})", "a", "u"))
        // Without the modifier the same constructs are case-sensitive.
        assertNull(match("[a-z]", "A"))
        assertNull(match("\\w", "ſ", "u"))
    }

    @Test
    fun affectsAnchorsPerInstruction() {
        assertEquals("", match("(?m:$)", "a\nb"))
        assertEquals(1, RegExp.compile("(?m:$)").exec("a\nb")?.index)
        // `$` outside the group is still end-of-input only.
        assertEquals(3, RegExp.compile("$").exec("a\nb")?.index)
        // And `m` can be removed again.
        assertEquals(3, RegExp.compile("(?-m:$)", "m").exec("a\nb")?.index)
    }

    @Test
    fun composesWithOtherConstructs() {
        assertEquals("A", match("(?<x>(?i:a))", "A"))
        assertEquals("A", RegExp.compile("(?<x>(?i:a))").exec("A")?.get("x"))
        assertEquals("AA", match("(?i:a)*", "AA"))
        assertEquals("A", match("(?i:a)|b", "A"))
        assertEquals("Ab", match("(?i:(a))(b)", "Ab"))
        // Group numbering is unaffected.
        val m = RegExp.compile("(?i:(a))(b)").exec("Ab")!!
        assertEquals(listOf("Ab", "A", "b"), m.groupValues())
    }

    /**
     * `\w` outside a modifier group must not pick up the group's `i`.
     *
     * This diverges from node: V8 scopes an added `i` correctly for literals and
     * classes but not for the word-class escapes, so `/(?i:c)\w/u` matches
     * "c\u017F" there. The inconsistency is visible side by side —
     * `/(?i:c)d/u` correctly rejects "cD" — so it is a V8 defect, not a reading
     * of the grammar. Only an *added* `i` is affected; `(?m:…)`, `(?s:…)` and
     * `(?-i:…)` scope correctly in both engines.
     */
    @Test
    fun wordEscapesAreScopedToTheirModifierGroup() {
        val longS = "\u017F" // folds to "s", so /iu treats it as a word character

        // Inside the group, `i` applies.
        assertEquals(longS, match("(?i:\\w)", longS, "u"))
        // Outside it, it does not.
        assertNull(match("(?i:c)\\w", "c" + longS, "u"))
        assertEquals("cb", match("(?i:c)\\w", "cb", "u"))
        // \W is the complement of the same set, so it *should* match here.
        assertEquals("c" + longS, match("(?i:c)\\W", "c" + longS, "u"))
        // Literals and classes outside the group are unaffected either way.
        assertNull(match("(?i:c)d", "cD", "u"))
        assertNull(match("(?i:c)[a-z]", "cB", "u"))
    }

    /**
     * A modifier group is transparent to group naming.
     *
     * Found by the fuzzer: the duplicate-name check has to descend through one,
     * or `(?<g>(?i:(?<g>x)))` would slip past it.
     */
    @Test
    fun duplicateNamesAreDetectedThroughModifierGroups() {
        rejects("(?<g>(?i:(?<g>x)))")
        rejects("(?<g>x)(?i:(?<g>y))")
        rejects("(?i:(?<g>x)(?<g>y))")
        // Still allowed across alternatives, modifier group or not.
        accepts("(?i:(?<g>x))|(?<g>y)")
        accepts("(?i:(?<g>x)|(?<g>y))")
    }

    @Test
    fun worksInsideLookbehind() {
        assertEquals("c", match("(?<=(?i:ab))c", "ABc"))
        assertNull(match("(?<=(?-i:ab))c", "ABc", "i"))
    }

    /**
     * A modifier group must not change how word characters are built outside it.
     *
     * Under `i` with `u` or `v`, WordCharacters is extended with the characters
     * that case-fold into it — U+017F LATIN SMALL LETTER LONG S folds to "s",
     * so it is a word character and `[^\w]` must exclude it.
     *
     * V8 drops that extension for a *negated* class as soon as any modifier
     * group appears, even one that only removes flags, while leaving a bare
     * `\w` in the same pattern extended — so it contradicts itself. Both cases
     * below came from the nightly fuzzer; the expectations are what V8 itself
     * produces once the modifier group is removed.
     */
    @Test
    fun modifierGroupDoesNotDisturbWordCharactersOutsideIt() {
        val longS = "ſ"

        // Positive form: the long s is a word character under `iu`, with or
        // without a modifier group present.
        assertEquals(longS, match("(?s-i:^)\\w", longS, "imsu"))
        assertEquals(longS, match("\\w", longS, "iu"))

        // Negated form: it must therefore be excluded. V8 matches "ſΣ" here.
        assertNull(match("(?s-i:^)[^\\w]{2,}", "ſΣbσäc", "imsu"))
        // Same pattern without the modifier group — V8 agrees with us on this one.
        assertNull(match("(?:^)[^\\w]{2,}", "ſΣbσäc", "imsu"))

        // The `v`-flag case, where the group only removes `i`. V8 matches
        // "\uDE00ſ"; the long s belongs to `\w`, so only the lone
        // surrogate does.
        val input = "c\uDE00ſ_Käxσσ\nÄ"
        assertEquals("\uDE00", match("(?:(?-i:a*)){2}[^\\w]{1,2}", input, "vi"))
        assertEquals("\uDE00", match("(?:(?:a*)){2}[^\\w]{1,2}", input, "vi"))

        // Without `i` there is no case extension at all, so the long s is not a
        // word character and both characters match.
        assertEquals("\uDE00ſ", match("(?:(?-i:a*)){2}[^\\w]{1,2}", input, "v"))
    }
}
