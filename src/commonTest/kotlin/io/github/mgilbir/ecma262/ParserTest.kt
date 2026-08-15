package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parser acceptance and rejection, case for case against node.
 *
 * Every expectation here was taken from running the same pattern through
 * `new RegExp(source, flags)` on node 26 (V8), not from reading the grammar.
 */
class ParserTest {

    private fun parse(source: String, flags: String = ""): Pattern =
        Parser(source, Flags.parse(flags)).parse()

    private fun accepts(source: String, flags: String = "") {
        try {
            parse(source, flags)
        } catch (e: RegExpSyntaxError) {
            fail("/$source/$flags should parse, but failed: ${e.message}")
        }
    }

    private fun rejects(source: String, flags: String = "") {
        try {
            parse(source, flags)
            fail("/$source/$flags should be a SyntaxError, but parsed")
        } catch (_: RegExpSyntaxError) {
            // expected
        }
    }

    // ------------------------------------------------------- quantifier targets

    /** `^*`, `$*` and `\b*` are "Nothing to repeat" in both modes. */
    @Test
    fun assertionsCannotBeQuantified() {
        for (f in listOf("", "u")) {
            rejects("^*", f)
            rejects("$*", f)
            rejects("\\b*", f)
            rejects("\\B*", f)
            rejects("^?", f)
            rejects("^{1}", f)
            rejects("\\b{2}", f)
        }
        // A malformed brace after an anchor is just a literal in Annex B.
        accepts("^{")
    }

    /** Annex B makes lookahead quantifiable; lookbehind never is. */
    @Test
    fun lookaheadIsQuantifiableOnlyInAnnexB() {
        accepts("(?=a)*")
        accepts("(?!a)*")
        accepts("(?=a){2}")
        rejects("(?=a)*", "u")
        rejects("(?!a)*", "u")
        rejects("(?=a){2}", "u")

        for (f in listOf("", "u")) {
            rejects("(?<=a)*", f)
            rejects("(?<!a)*", f)
        }
    }

    @Test
    fun quantifiersCannotBeStacked() {
        for (f in listOf("", "u")) {
            rejects("a**", f)
            rejects("a?*", f)
            rejects("a*??", f)
            rejects("a{2}{3}", f)
            rejects("a{2}*", f)
            rejects("a*{2}", f)
        }
        accepts("a*?")
        accepts("a+?")
        accepts("a{2,3}?")
    }

    @Test
    fun quantifierWithNothingToRepeat() {
        for (f in listOf("", "u")) {
            rejects("*", f)
            rejects("+", f)
            rejects("?", f)
            rejects("{1}", f)
        }
    }

    @Test
    fun outOfOrderBracedQuantifierIsAlwaysAnError() {
        for (f in listOf("", "u")) rejects("a{2,1}", f)
        accepts("a{1,2}")
        accepts("a{1,}")
        accepts("a{1,}", "u")
        accepts("a{0}")
    }

    /** A malformed `{...}` is literal text in Annex B and an error under `u`. */
    @Test
    fun malformedBracedQuantifier() {
        for (p in listOf("a{2 x}", "a{", "a{}", "a{,3}")) {
            accepts(p)
            rejects(p, "u")
        }
    }

    // ------------------------------------------------------------- lone brackets

    @Test
    fun loneBracketsAreLiteralOnlyInAnnexB() {
        for (p in listOf("{", "}", "]")) {
            accepts(p)
            rejects(p, "u")
        }
    }

    @Test
    fun unbalancedDelimiters() {
        for (f in listOf("", "u")) {
            rejects("(", f)
            rejects(")", f)
            rejects("[", f)
            rejects("\\", f)
            rejects("(a", f)
            rejects("a)", f)
        }
    }

    // ------------------------------------------------------------ backreferences

    @Test
    fun numericBackreferencesResolveForward() {
        for (f in listOf("", "u")) {
            accepts("(a)\\1", f)
            accepts("\\1(a)", f)
        }
    }

    /** Out-of-range `\n` degrades to a legacy octal escape only in Annex B. */
    @Test
    fun outOfRangeNumericEscape() {
        for (p in listOf("\\5", "\\8", "\\58", "\\2(a)", "\\08")) {
            accepts(p)
            rejects(p, "u")
        }
        accepts("\\0")
        accepts("\\0", "u")
    }

    @Test
    fun legacyOctalDecodesLikeJavaScript() {
        // \5 -> U+0005
        assertEquals(listOf(0x05), literalsOf(parse("\\5").body))
        // \58 -> U+0005 then '8'
        assertEquals(listOf(0x05, '8'.code), literalsOf(parse("\\58").body))
        // \8 -> literal '8' (not an octal digit)
        assertEquals(listOf('8'.code), literalsOf(parse("\\8").body))
        // \08 -> NUL then '8'
        assertEquals(listOf(0x00, '8'.code), literalsOf(parse("\\08").body))
        // \377 -> U+00FF, the largest legacy octal escape
        assertEquals(listOf(0xFF), literalsOf(parse("\\377").body))
        // \400 -> U+0020 then '0', because only two digits fit when the first is > 3
        assertEquals(listOf(0x20, '0'.code), literalsOf(parse("\\400").body))
    }

    // -------------------------------------------------------------- named groups

    @Test
    fun duplicateNamesAllowedOnlyAcrossAlternatives() {
        for (f in listOf("", "u")) {
            accepts("(?<a>x)|(?<a>y)", f)
            rejects("(?<a>x)(?<a>y)", f)
            accepts("(?<a>x)\\k<a>", f)
            rejects("(?<1a>x)", f)
            accepts("(?<\$>x)", f)
            accepts("(?<_a1>x)", f)
        }
    }

    @Test
    fun duplicateNamedGroupsShareOneNumberingSpace() {
        val p = parse("(?<a>x)|(?<a>y)")
        assertEquals(2, p.numGroups)
        assertEquals(listOf(1, 2), p.groupNames["a"])
    }

    /** `\k` is only a backreference when the pattern has a named group at all. */
    @Test
    fun namedBackreferenceFallbackInAnnexB() {
        accepts("\\k<a>") // no named groups anywhere: identity escape for 'k'
        assertEquals(
            listOf('k'.code, '<'.code, 'a'.code, '>'.code),
            literalsOf(parse("\\k<a>").body),
        )
        rejects("\\k<a>", "u")
        rejects("\\k<a>(?<b>x)") // a named group exists, so \k must resolve
        rejects("\\k<a>(?<b>x)", "u")
    }

    @Test
    fun unicodeGroupNames() {
        accepts("(?<\\u0061>x)")
        accepts("(?<αβ>x)") // Greek letters
        assertEquals(listOf(1), parse("(?<\\u0061>x)").groupNames["a"])
    }

    // ----------------------------------------------------------------- escapes

    @Test
    fun identityEscapes() {
        accepts("\\/")
        accepts("\\/", "u")
        accepts("\\q")
        rejects("\\q", "u")
        // `\-` is a ClassEscape only; outside a class it is invalid under u.
        accepts("\\-")
        rejects("\\-", "u")
        accepts("[\\-]", "u")
    }

    @Test
    fun controlEscapes() {
        accepts("\\cA")
        accepts("\\cA", "u")
        accepts("\\c1")
        accepts("\\c")
        rejects("\\c1", "u")
        rejects("\\c", "u")
        assertEquals(listOf(0x01), literalsOf(parse("\\cA").body))
        // An invalid \c degrades to the two literal characters '\' and 'c'.
        assertEquals(listOf('\\'.code, 'c'.code, '1'.code), literalsOf(parse("\\c1").body))
        // Inside a class Annex B also admits digits and '_' as control letters.
        accepts("[\\c1]")
        accepts("[\\c_]")
        rejects("[\\c1]", "u")
    }

    @Test
    fun hexAndUnicodeEscapes() {
        accepts("\\x41")
        accepts("\\x41", "u")
        assertEquals(listOf(0x41), literalsOf(parse("\\x41").body))
        // Malformed \x and \u degrade to identity escapes in Annex B.
        accepts("\\xZZ")
        accepts("\\uZZ")
        rejects("\\xZZ", "u")
        rejects("\\uZZ", "u")
        assertEquals(listOf('x'.code, 'Z'.code, 'Z'.code), literalsOf(parse("\\xZZ").body))
    }

    @Test
    fun codePointEscapes() {
        accepts("\\u{41}", "u")
        assertEquals(listOf(0x41), literalsOf(parse("\\u{41}", "u").body))
        rejects("\\u{110000}", "u")
        rejects("\\u{}", "u")
        // Without u, `\u{41}` is the letter 'u' quantified 41 times.
        val body = parse("\\u{41}").body
        val q = body as? Quantifier ?: fail("expected a quantifier, got ${body::class.simpleName}")
        assertEquals(41, q.min)
        assertEquals(41, q.max)
        assertEquals('u'.code, (q.body as Literal).codePoint)
    }

    /** Surrogate pairs are one code point under `u`, two atoms without it. */
    @Test
    fun surrogatePairs() {
        assertEquals(listOf(0x1F600), literalsOf(parse("\\uD83D\\uDE00", "u").body))
        assertEquals(listOf(0xD83D, 0xDE00), literalsOf(parse("\\uD83D\\uDE00").body))
        assertEquals(listOf(0x1F600), literalsOf(parse("😀", "u").body))
        assertEquals(listOf(0xD83D, 0xDE00), literalsOf(parse("😀").body))
    }

    // -------------------------------------------------------- character classes

    @Test
    fun characterClassBasics() {
        for (f in listOf("", "u")) {
            accepts("[]", f)
            accepts("[^]", f)
            accepts("[-a]", f)
            accepts("[a-]", f)
            accepts("[\\b]", f)
            accepts("[a-z]", f)
            rejects("[z-a]", f)
        }
    }

    /** A class escape as a range endpoint: literal '-' in Annex B, error under u. */
    @Test
    fun rangeWithClassEscapeEndpoint() {
        for (p in listOf("[a-\\d]", "[\\d-z]")) {
            accepts(p)
            rejects(p, "u")
        }
    }

    @Test
    fun classEscapesAreNotBackreferences() {
        // Inside a class \1 is a legacy octal escape, never a backreference.
        val atoms = (parse("(a)[\\1]").let { p ->
            (p.body as Sequence).elements[1] as CharClass
        }).atoms
        assertEquals(1, atoms.size)
        assertEquals(0x01, (atoms[0] as ClassLiteral).codePoint)
        rejects("(a)[\\1]", "u")
    }

    @Test
    fun backspaceInClass() {
        val cc = parse("[\\b]").body as CharClass
        assertEquals(0x08, (cc.atoms.single() as ClassLiteral).codePoint)
    }

    // ------------------------------------------------------------- properties

    @Test
    fun unicodePropertyEscapes() {
        accepts("\\p{L}", "u")
        accepts("\\P{L}", "u")
        accepts("\\p{Script=Greek}", "u")
        accepts("\\p{gc=Lu}", "u")
        rejects("\\p{Foo}", "u")
        rejects("\\p{letter}", "u")
        // Without u, \p is the identity escape for 'p'.
        accepts("\\p{L}")
        assertTrue(parse("\\p{L}").body is Sequence)
    }

    // ----------------------------------------------------------------- limits

    @Test
    fun rejectsExcessiveNesting() {
        val deep = "(".repeat(300) + "a" + ")".repeat(300)
        assertFailsWith<RegExpSyntaxError> { parse(deep) }
        // Just under the limit still parses.
        val ok = "(".repeat(150) + "a" + ")".repeat(150)
        accepts(ok)
    }

    /**
     * Nested alternations must not be explored path-by-path; this pattern has
     * 2^30 paths and would hang a cartesian-product duplicate-name check.
     */
    @Test
    fun duplicateNameCheckIsLinear() {
        accepts("(a|b)".repeat(30))
    }

    // ------------------------------------------------------------------ v flag

    /**
     * `v` uses its own class grammar rather than `u`'s, so constructs that are
     * ordinary under `u` become errors and vice versa. Full coverage lives in
     * UnicodeSetsTest; this just confirms the parser switches grammars.
     */
    @Test
    fun unicodeSetsUsesADifferentClassGrammar() {
        accepts("[[a][b]]", "v")
        accepts("[a--b]", "v")
        accepts("[\\q{ab}]", "v")
        // A bare '(' is a literal under u but reserved under v.
        accepts("[(]", "u")
        rejects("[(]", "v")
        // Nested classes are literal brackets under u.
        rejects("[[a][b]]", "u")
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Flattens a tree of literals into their code points.
     *
     * A numeric escape that did not resolve to a group keeps its literal
     * expansion on the [Backreference] node, since the decision is only made
     * once the whole pattern has been parsed.
     */
    private fun literalsOf(e: Expr): List<Int> = when (e) {
        is Literal -> listOf(e.codePoint)
        is Sequence -> e.elements.flatMap { literalsOf(it) }
        is Backreference ->
            e.fallback?.toList()
                ?: fail("backreference \\${e.index} resolved to a group, not literals")
        else -> fail("expected only literals, found ${e::class.simpleName}")
    }
}
