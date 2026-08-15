package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The `v` (UnicodeSets) flag.
 *
 * Every expectation was taken from node, not from reading the grammar. `v` is a
 * set algebra rather than `u`'s flat class syntax: classes nest, `&&` and `--`
 * combine them, `\q{…}` introduces multi-character strings, and the punctuators
 * reserved for future syntax must be escaped.
 */
class UnicodeSetsTest {

    private fun match(pattern: String, input: String, flags: String = "v"): String? =
        RegExp.compile(pattern, flags).exec(input)?.value

    private fun rejects(pattern: String, flags: String = "v") {
        assertFailsWith<RegExpSyntaxError>("/$pattern/$flags should be a SyntaxError") {
            RegExp.compile(pattern, flags)
        }
    }

    private fun accepts(pattern: String, flags: String = "v") {
        try {
            RegExp.compile(pattern, flags)
        } catch (e: RegExpSyntaxError) {
            fail("/$pattern/$flags should compile, but failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ nesting

    @Test
    fun nestedClassesUnion() {
        assertEquals("b", match("[[a][b]]", "b"))
        assertEquals("5", match("[[a-z][0-9]]", "5"))
        assertEquals("c", match("[^[a][b]]", "c"))
    }

    @Test
    fun difference() {
        assertEquals("a", match("[a--b]", "a"))
        assertNull(match("[a--b]", "b"))
        assertNull(match("[[a-z]--[aeiou]]", "e"))
        assertEquals("z", match("[[a-z]--[aeiou]]", "z"))
        assertNull(match("[[a-c]--b]", "b"))
        assertNull(match("[\\p{L}--\\p{Lu}]", "A"))
        assertEquals("a", match("[\\p{L}--\\p{Lu}]", "a"))
    }

    @Test
    fun intersection() {
        assertNull(match("[a&&b]", "a"))
        assertEquals("c", match("[[a-z]&&[b-d]]", "c"))
        assertNull(match("[[a-z]&&[b-d]]", "z"))
    }

    @Test
    fun operatorsMayChainButNotMix() {
        accepts("[a&&b&&c]")
        accepts("[a--b--c]")
        rejects("[a&&b--c]")
        rejects("[a--b&&c]")
    }

    // ------------------------------------------------------------------ strings

    @Test
    fun stringDisjunction() {
        assertEquals("abc", match("[\\q{abc}]", "abc"))
        assertNull(match("[\\q{abc}]", "ab"))
        assertEquals("bc", match("[\\q{a|bc}]", "bc"))
        assertEquals("a", match("[\\q{a|bc}]", "a"))
        assertEquals("ab", match("[\\q{ab|a}]", "ab"))
        assertEquals("ab", match("[a\\q{ab}]", "ab"))
        assertEquals("b", match("[\\q{a}\\q{b}]", "b"))
        assertEquals("abc", match("^[\\q{ab|abc}]$", "abc"))
    }

    /** The empty string is a legitimate element and matches zero-width. */
    @Test
    fun emptyStringElement() {
        assertEquals("", match("[\\q{}]", ""))
        assertEquals("", match("[\\q{}]", "x"))
        assertEquals("a", match("^[a\\q{}]$", "a"))
    }

    /**
     * Elements are tried longest-first but the matcher still backtracks, so a
     * longer element that strands the rest of the pattern gives way.
     */
    @Test
    fun stringElementsBacktrack() {
        assertEquals("ab", match("^[\\q{ab|a}]b$", "ab"))
        assertEquals("ab", match("^[a\\q{ab}]b$", "ab"))
        assertEquals("abc", match("^[\\q{abc|ab|a}]bc$", "abc"))
    }

    @Test
    fun stringSetOperations() {
        assertNull(match("[\\q{ab}--\\q{ab}]", "ab"))
        assertEquals("ab", match("[[\\q{ab}]&&[\\q{ab}]]", "ab"))
        assertNull(match("[[\\q{ab}]--[\\q{ab}]]", "ab"))
        accepts("[\\q{ab}&&\\q{cd}]")
    }

    /** A negated class cannot contain a string of length other than one. */
    @Test
    fun negationRejectsStrings() {
        rejects("[^\\q{ab}]")
        rejects("[^\\q{}]")
        rejects("[^[\\q{ab}]]")
        rejects("[^\\q{ab}--\\q{ab}]")
        // A one-character string is just a character, so this is fine.
        accepts("[^\\q{a}]")
        assertEquals("b", match("[^\\q{a}]", "b"))
    }

    // -------------------------------------------------------------- punctuators

    @Test
    fun syntaxCharactersMustBeEscaped() {
        for (p in listOf("[(]", "[)]", "[{]", "[}]", "[/]", "[|]", "[-]")) rejects(p)
        assertEquals("(", match("[\\(]", "("))
        assertEquals("-", match("[\\-]", "-"))
        assertEquals("m", match("[a-z]", "m"))
    }

    @Test
    fun reservedDoublePunctuatorsAreRejected() {
        for (p in listOf("[&&]", "[!!]", "[##]", "[~~]", "[::]")) rejects(p)
        // Singly they are ordinary characters.
        for (p in listOf("[&]", "[!]", "[#]", "[^^]")) accepts(p)
        assertEquals("&", match("[\\&]", "&"))
        assertEquals("&", match("[a&b]", "&"))
    }

    // ------------------------------------------------------------------ escapes

    @Test
    fun classEscapesStillWork() {
        assertEquals("5", match("[\\d]", "5"))
        assertEquals("x", match("[\\D]", "x"))
        assertEquals("_", match("[\\w]", "_"))
        assertEquals(" ", match("[\\s]", " "))
        assertEquals("a", match("[\\p{L}]", "a"))
        assertEquals("1", match("[\\P{L}]", "1"))
        assertEquals("1", match("[^\\p{L}]", "1"))
        assertEquals("", match("[\\b]", ""))
    }

    // --------------------------------------------------------- case insensitive

    @Test
    fun caseInsensitiveUnicodeSets() {
        assertEquals("A", match("[a]", "A", "vi"))
        assertNull(match("[^a]", "A", "vi"))
        assertEquals("AB", match("[\\q{ab}]", "AB", "vi"))
        assertNull(match("[[a-z]--[aeiou]]", "E", "vi"))
        assertEquals("a", match("[\\p{Lu}]", "a", "vi"))
    }

    /**
     * U+212A KELVIN SIGN folds to ASCII "k", so under `/i` all three spellings
     * match one another. Beware when editing: `kelvin` below holds the literal
     * Kelvin sign, which is indistinguishable on screen from the ASCII "K" on
     * the line above it.
     */
    @Test
    fun kelvinSignFoldsToAsciiK() {
        val kelvin = "K"
        assertEquals("k", match("[\\u212A]", "k", "vi"))
        assertEquals("K", match("[\\u212A]", "K", "vi"))
        assertEquals(kelvin, match("[\\u212A]", kelvin, "vi"))
        assertEquals("k", match(kelvin, "k", "vi"))
        assertEquals(kelvin, match("k", kelvin, "vi"))
        // The same holds under `u`.
        assertEquals("k", match("[\\u212A]", "k", "iu"))
        // Not without a Unicode flag: the legacy canonicalization refuses to map
        // a non-ASCII character onto an ASCII one.
        assertNull(match("[\\u212A]", "k", "i"))
    }

    /**
     * A single-character `\q{…}` element is a character, and folds like one.
     *
     * This diverges from node, which folds the pattern side but not the input,
     * so `[\q{a}]/vi` fails to match "A" there while `[a]/vi` matches it. The
     * inconsistency is visible inside one class — `/[a\q{b}]/vi` matches "A"
     * but not "B" — so it is a V8 defect rather than a reading of the grammar.
     * The specification treats a length-1 string as a character, which is why
     * `[^\q{a}]` is legal at all.
     */
    @Test
    fun singleCharacterQuotedStringsFoldLikeCharacters() {
        assertEquals("A", match("[\\q{a}]", "A", "vi"))
        assertEquals("a", match("[\\q{A}]", "a", "vi"))
        assertEquals("A", match("[\\q{A}]", "A", "vi"))
        // Both spellings behave identically within one class.
        assertEquals("A", match("[a\\q{b}]", "A", "vi"))
        assertEquals("B", match("[a\\q{b}]", "B", "vi"))
        // Longer strings fold too, which node also does.
        assertEquals("AB", match("[\\q{ab}]", "AB", "vi"))
        // Without `i` nothing folds.
        assertNull(match("[\\q{a}]", "A", "v"))
    }

    @Test
    fun mayContainStringsIsDecidedSyntactically() {
        // The difference is empty, but the rule looks only at the first operand.
        rejects("[^\\q{ab}--\\q{ab}]")
        rejects("[^\\q{ab}&&\\q{cd}]")
        rejects("[^[\\q{ab}][a]]")
        // An intersection may contain strings only if every operand does.
        accepts("[^[\\q{ab}]&&[a]]")
        // A negated nested class never contains strings.
        accepts("[^[^\\q{a}]]")
    }

    // ------------------------------------------------------------------- flags

    @Test
    fun vAndUAreMutuallyExclusive() {
        assertFailsWith<RegExpSyntaxError> { RegExp.compile("a", "uv") }
    }

    @Test
    fun lookbehindOverStringElements() {
        // The lowered alternation must be reversed with everything else.
        assertEquals("c", match("(?<=[\\q{ab}])c", "abc"))
        assertNull(match("(?<=[\\q{ab}])c", "xbc"))
    }
}
