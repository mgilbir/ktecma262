package io.github.mgilbir.ecma262

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Full case folding is not applied, matching JavaScript. */
class SharpSTest {
    @Test
    fun sharpSDoesNotFoldToDoubleS() {
        // Verified against node: JavaScript uses simple folding only.
        assertNull(RegExp.compile("ß", "i").exec("SS"))
        assertNull(RegExp.compile("SS", "i").exec("ß"))
        assertNull(RegExp.compile("ß", "iu").exec("SS"))
        assertEquals("ß", RegExp.compile("ß", "i").exec("ß")?.value)
        // U+1E9E LATIN CAPITAL LETTER SHARP S does fold to ß under simple folding.
        assertEquals("ẞ", RegExp.compile("ß", "iu").exec("ẞ")?.value)
    }
}
