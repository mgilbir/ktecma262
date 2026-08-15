package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ReDoS bound.
 *
 * A backtracking engine can take exponential time on patterns like `(a+)+b`.
 * JavaScript engines have no bound and simply hang; this engine stops at
 * [RegExp.maxSteps] and reports it, the way PCRE's backtrack limit does.
 *
 * These tests exist to show two things: that the limit really fires, and that
 * the engine is not merely *wrong* on these patterns — given a large enough
 * budget it produces exactly the answer node produces.
 */
class StepLimitTest {

    /**
     * Patterns whose cost is exponential in the input length. Verified against
     * node, which takes ~200-450ms on a 24-character input for each of these and
     * would keep doubling from there; it returns no match in every case.
     */
    private val catastrophic = listOf("(a+)+b", "(a|a)*b", "(a*)*b", "(a+)*b")

    @Test
    fun exponentialPatternsHitTheLimitInsteadOfHanging() {
        val input = "a".repeat(24) + "X"
        for (p in catastrophic) {
            val re = RegExp.compile(p)
            assertFailsWith<RegExpStepLimitError>("/$p/ should exhaust its budget on $input") {
                re.exec(input)
            }
        }
    }

    /**
     * With a budget large enough to finish, the answer matches node: these
     * patterns do not match, they are only expensive to refute.
     */
    @Test
    fun exponentialPatternsAgreeWithNodeGivenEnoughBudget() {
        val input = "a".repeat(16) + "X"
        for (p in catastrophic) {
            val re = RegExp.compile(p)
            re.maxSteps = 200_000_000
            assertNull(re.exec(input), "/$p/ should not match $input")
        }
    }

    @Test
    fun theLimitIsConfigurable() {
        val re = RegExp.compile("(a+)+b")
        re.maxSteps = 1000
        assertFailsWith<RegExpStepLimitError> { re.exec("a".repeat(30) + "X") }

        // A cheap match must still succeed under a small budget.
        val cheap = RegExp.compile("abc")
        cheap.maxSteps = 1000
        assertEquals("abc", cheap.exec("xxabc")?.value)
    }

    /**
     * The budget covers a whole scan, not each start position, so a failing
     * search over a long input stays O(budget) rather than O(length x budget).
     */
    @Test
    fun budgetSpansTheWholeScan() {
        val re = RegExp.compile("(a+)+b")
        re.maxSteps = 50_000
        assertFailsWith<RegExpStepLimitError> { re.exec("a".repeat(5000)) }
    }

    /**
     * A repeated operation must not renew its budget per match, or total cost
     * would scale with the number of matches — which the input length controls.
     */
    @Test
    fun repeatedOperationsShareOneBudget() {
        val many = "ab".repeat(20_000)

        val findAll = RegExp.compile("(a|a)(b|b)", "g")
        findAll.maxSteps = 5_000
        assertFailsWith<RegExpStepLimitError> { findAll.findAll(many) }

        val replace = RegExp.compile("(a|a)(b|b)", "g")
        replace.maxSteps = 5_000
        assertFailsWith<RegExpStepLimitError> { replace.replace(many, "x") }

        val split = RegExp.compile("(a|a)(b|b)", "g")
        split.maxSteps = 5_000
        assertFailsWith<RegExpStepLimitError> { split.split(many) }

        // With a budget that comfortably covers the whole input, it completes.
        val ok = RegExp.compile("(a|a)(b|b)", "g")
        ok.maxSteps = 50_000_000
        assertEquals(20_000, ok.findAll(many).size)
    }

    /** Ordinary patterns must be nowhere near the limit. */
    @Test
    fun realisticPatternsStayWellUnderBudget() {
        val cases = listOf(
            Triple("^\\w+@\\w+\\.\\w{2,}$", "someone@example.com", true),
            Triple("(\\d{4})-(\\d{2})-(\\d{2})", "on 2024-03-15 sharp", true),
            Triple("\\b\\w+\\b", "the quick brown fox", true),
            Triple("(?<=\\$)\\d+(?:\\.\\d+)?", "costs \$1234.56", true),
            Triple("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "a.b+c@d-e.f.g", true),
        )
        for ((pattern, input, shouldMatch) in cases) {
            val re = RegExp.compile(pattern)
            re.maxSteps = 20_000 // far below the default
            val m = re.exec(input)
            assertEquals(shouldMatch, m != null, "/$pattern/ on \"$input\"")
        }
    }

    /**
     * Deep backtracking must not overflow the call stack. A recursive matcher
     * would recurse once per repetition here; this engine backtracks on an
     * explicit stack, so only lookaround nesting uses the call stack.
     */
    @Test
    fun deepBacktrackingDoesNotOverflowTheStack() {
        val re = RegExp.compile("(?:a|aa)*$")
        re.maxSteps = 50_000_000
        val input = "a".repeat(20_000)
        assertTrue(re.exec(input) != null)
    }

    @Test
    fun longInputLinearScanIsCheap() {
        val re = RegExp.compile("needle")
        val hay = "haystack ".repeat(20_000) + "needle"
        assertEquals(hay.length - 6, re.exec(hay)?.index)
    }
}
