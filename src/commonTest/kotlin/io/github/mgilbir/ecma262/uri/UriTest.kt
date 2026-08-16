package io.github.mgilbir.ecma262.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The four URI functions, checked against node.
 *
 * Encoding is verified over **every** code point rather than a sample: there
 * are only 1,114,112, the function is defined per code point, and escaping is
 * what callers rely on to keep untrusted text from changing a URI's structure.
 * A gap there is a security problem rather than a cosmetic one, which is the
 * same reason `RegExp.escape` is checked exhaustively.
 *
 * Decoding cannot be enumerated that way, so it gets the sequences a decoder
 * must reject — overlong forms, encoded surrogates, truncated escapes — and a
 * fuzzer for the rest.
 */
class UriTest {

    private fun stringOf(units: IntArray): String {
        val sb = StringBuilder(units.size)
        for (u in units) sb.append(u.toChar())
        return sb.toString()
    }

    private fun apply(function: String, input: String): String = when (function) {
        "encodeUriComponent" -> input.encodeUriComponent()
        "encodeUri" -> input.encodeUri()
        "decodeUriComponent" -> input.decodeUriComponent()
        else -> input.decodeUri()
    }

    private fun fnv1a(start: UInt, s: String): UInt {
        var h = start
        for (ch in s) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        h = h xor 0x7Cu
        h *= 16777619u
        return h
    }

    private fun codePointToString(cp: Int): String {
        if (cp <= 0xFFFF) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v ushr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar())
            .concatToString()
    }

    @Test
    fun encodingMatchesNodeForEveryCodePoint() {
        var component = 2166136261u
        var uri = 2166136261u
        var encodable = 0
        var rejected = 0
        var cp = 0
        while (cp <= 0x10FFFF) {
            val s = codePointToString(cp)
            val encodedComponent = try {
                s.encodeUriComponent()
            } catch (_: UriError) {
                null
            }
            if (encodedComponent == null) {
                rejected++
                component = fnv1a(component, "!")
                uri = fnv1a(uri, "!")
            } else {
                encodable++
                component = fnv1a(component, encodedComponent)
                uri = fnv1a(uri, s.encodeUri())
            }
            cp++
        }
        assertEquals(UriFixture.ENCODABLE, encodable)
        assertEquals(UriFixture.UNPAIRED_SURROGATES, rejected, "lone surrogates must not encode")
        assertEquals(UriFixture.COMPONENT_HASH, component, "encodeUriComponent differs from node somewhere")
        assertEquals(UriFixture.URI_HASH, uri, "encodeUri differs from node somewhere")
    }

    @Test
    fun explicitCasesMatchNode() {
        var threwCount = 0
        for (case in UriFixture.EXPLICIT) {
            val input = stringOf(case.units)
            val expected = case.expectedUnits?.let { stringOf(it) }
            if (expected == null) {
                threwCount++
                assertFailsWith<UriError>("${case.function} should reject ${describe(input)}") {
                    apply(case.function, input)
                }
            } else {
                assertEquals(
                    expected,
                    apply(case.function, input),
                    "${case.function}(${describe(input)})",
                )
            }
        }
        // The list is only useful while it still contains rejections.
        assertTrue(threwCount > 20, "only $threwCount rejection cases; the fixture lost its teeth")
    }

    /** Decoding what we encoded must give back exactly what we started with. */
    @Test
    fun encodeThenDecodeIsIdentity() {
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (cp !in 0xD800..0xDFFF) {
                val s = codePointToString(cp)
                assertEquals(s, s.encodeUriComponent().decodeUriComponent(), "round trip at $cp")
            }
            cp += if (cp < 0x3000) 1 else 97 // dense where the rules change, sparse above
        }
        for (s in listOf("a b?c=d&e#f", "http://x.com/p a/q?r=s", "ünïcødé 😀 ﬁ")) {
            assertEquals(s, s.encodeUriComponent().decodeUriComponent())
            assertEquals(s, s.encodeUri().decodeUri())
        }
    }

    /** `decodeURI` must leave reserved escapes alone, or the URI reparses differently. */
    @Test
    fun decodeUriPreservesReservedEscapes() {
        assertEquals("http://x.com/a b%2Fc", "http://x.com/a%20b%2Fc".decodeUri())
        assertEquals("a b/c", "a%20b%2Fc".decodeUriComponent())
        for (reserved in ";/?:@&=+$,#") {
            val escaped = "%" + reserved.code.toString(16).uppercase().padStart(2, '0')
            assertEquals(escaped, escaped.decodeUri(), "decodeUri must keep $escaped")
            assertEquals(reserved.toString(), escaped.decodeUriComponent())
        }
    }

    private fun describe(s: String): String =
        s.map { if (it.code in 32..126) it.toString() else "\\u" + it.code.toString(16).padStart(4, '0') }
            .joinToString("")
}
