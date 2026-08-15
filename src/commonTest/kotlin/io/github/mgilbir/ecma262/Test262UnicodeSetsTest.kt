package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test262's conformance cases for the `v` flag's class grammar.
 *
 * An independent check on the differential corpus: those expectations come from
 * one implementation, while these are hand-curated by the specification's
 * authors. They matter most for `v`, which is the part of this engine written
 * from the grammar rather than ported.
 *
 * Each case is a class expression wrapped as `^[…]+$`, with strings that must
 * match and strings that must not.
 */
class Test262UnicodeSetsTest {

    private fun describe(s: String): String = buildString {
        for (ch in s) {
            if (ch.code in 0x20..0x7e) append(ch)
            else append("\\u").append(ch.code.toString(16).padStart(4, '0'))
        }
    }

    @Test
    fun matchesTest262Expectations() {
        val cases = Test262UnicodeSetsFixture.all()
        assertTrue(cases.size > 100, "fixture looks truncated: ${cases.size}")

        val failures = mutableListOf<String>()
        var checkedMatch = 0
        var checkedNonMatch = 0

        for (c in cases) {
            val re = try {
                RegExp.compile(c.pattern, c.flags)
            } catch (e: RegExpSyntaxError) {
                failures += "$c: failed to compile: ${e.message}"
                continue
            }

            for (s in c.matchStrings) {
                checkedMatch++
                val ok = try {
                    re.test(s)
                } catch (e: RegExpStepLimitError) {
                    failures += "$c: step limit on \"${describe(s)}\""
                    continue
                }
                if (!ok) failures += "$c: should match \"${describe(s)}\""
            }

            for (s in c.nonMatchStrings) {
                checkedNonMatch++
                val ok = try {
                    re.test(s)
                } catch (e: RegExpStepLimitError) {
                    failures += "$c: step limit on \"${describe(s)}\""
                    continue
                }
                if (ok) failures += "$c: should not match \"${describe(s)}\""
            }
        }

        assertTrue(checkedMatch > 0 && checkedNonMatch > 0, "fixture lost a category")
        assertTrue(
            failures.isEmpty(),
            "${failures.size} Test262 expectations unmet " +
                "($checkedMatch match / $checkedNonMatch non-match checked):\n" +
                failures.take(30).joinToString("\n"),
        )
    }

    /**
     * Properties of strings — `\p{RGI_Emoji}` and its five constituents.
     *
     * These are `v`-only, cannot be negated, and make a class "may contain
     * strings", so a negated class holding one is a SyntaxError.
     */
    @Test
    fun propertiesOfStrings() {
        val names = listOf(
            "RGI_Emoji",
            "Basic_Emoji",
            "Emoji_Keycap_Sequence",
            "RGI_Emoji_Flag_Sequence",
            "RGI_Emoji_Modifier_Sequence",
            "RGI_Emoji_Tag_Sequence",
            "RGI_Emoji_ZWJ_Sequence",
        )
        for (name in names) {
            assertTrue(RegExp.compileOrNull("\\p{$name}", "v") != null, "\\p{$name} should compile under v")
            // Only under `v`, never negated, and never inside a negated class.
            assertTrue(RegExp.compileOrNull("\\p{$name}", "u") == null, "\\p{$name} must be v-only")
            assertTrue(RegExp.compileOrNull("\\P{$name}", "v") == null, "\\P{$name} must be rejected")
            assertTrue(RegExp.compileOrNull("[^\\p{$name}]", "v") == null, "[^\\p{$name}] must be rejected")
        }
    }

    @Test
    fun rgiEmojiMatchesWholeSequences() {
        val re = RegExp.compile("^\\p{RGI_Emoji}$", "v")
        // A keycap sequence, a flag, a ZWJ family, and a plain emoji.
        assertTrue(re.test("#\uFE0F\u20E3"))
        assertTrue(re.test("\uD83C\uDDEC\uD83C\uDDE7"))
        assertTrue(re.test("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"))
        assertTrue(re.test("\uD83D\uDE00"))
        // Not emoji.
        assertTrue(!re.test("a"))
        assertTrue(!re.test("#"))

        // Set operations compose with them.
        assertTrue(RegExp.compile("^[\\p{RGI_Emoji}--\\q{\uD83D\uDE00}]$", "v").test("\uD83D\uDE01"))
        assertTrue(!RegExp.compile("^[\\p{RGI_Emoji}--\\q{\uD83D\uDE00}]$", "v").test("\uD83D\uDE00"))
    }
}
