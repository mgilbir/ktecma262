package io.github.mgilbir.ecma262.text

import io.github.mgilbir.ecma262.unicode.Unicode

/**
 * Is this a valid `IdentifierName` — ECMA-262 12.7?
 *
 * That is the lexical question, and the one that decides whether an object key
 * needs quoting or a property can be reached with a dot:
 *
 * ```kotlin
 * "foo".isEcmaIdentifierName()      // true  -> obj.foo
 * "foo-bar".isEcmaIdentifierName()  // false -> obj["foo-bar"]
 * "日本語".isEcmaIdentifierName()     // true
 * "1a".isEcmaIdentifierName()       // false
 * ```
 *
 * Keywords **are** IdentifierNames: `obj.if` is legal even though `var if` is
 * not. Use [isEcmaIdentifier] for the binding question.
 *
 * The rules are `ID_Start` plus `$` and `_` to begin, then `ID_Continue` plus
 * `$`, U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH JOINER — the last two
 * because some scripts need them inside a word.
 *
 * Escape sequences are not considered: this asks about characters, not about
 * the `A` spelling that source text may also use.
 */
public fun String.isEcmaIdentifierName(): Boolean {
    if (isEmpty()) return false
    var i = 0
    var first = true
    while (i < length) {
        val c = this[i]
        val codePoint: Int
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            i += 2
        } else {
            // A lone surrogate falls through as its own code unit value, which
            // is in neither ID_Start nor ID_Continue, so it is rejected below.
            // An explicit branch here changed nothing when removed.
            codePoint = c.code
            i++
        }
        val ok = if (first) isIdentifierStart(codePoint) else isIdentifierPart(codePoint)
        if (!ok) return false
        first = false
    }
    return true
}

/**
 * Is this a `ReservedWord` — ECMA-262 12.7.2?
 *
 * `await` and `yield` are included because the production lists them, but they
 * are only reserved in some contexts: `var await` is legal in a sloppy script
 * and not in a module. [isEcmaIdentifier] takes that as a parameter rather than
 * guessing.
 */
public fun String.isEcmaReservedWord(): Boolean = this in RESERVED

/**
 * Is this usable as a binding name — an `IdentifierName` that is not reserved?
 *
 * ```kotlin
 * "foo".isEcmaIdentifier()   // true
 * "if".isEcmaIdentifier()    // false - reserved everywhere
 * "let".isEcmaIdentifier()   // true, but false in strict mode
 * "await".isEcmaIdentifier() // true, but false inside a module
 * ```
 *
 * @param strict also rejects the words reserved only in strict mode: `let`,
 *   `static`, `implements`, `interface`, `package`, `private`, `protected` and
 *   `public`.
 * @param module also rejects `await`, which a module reserves.
 * @param generator also rejects `yield`, which a generator body reserves.
 */
public fun String.isEcmaIdentifier(
    strict: Boolean = false,
    module: Boolean = false,
    generator: Boolean = false,
): Boolean {
    if (!isEcmaIdentifierName()) return false
    if (this in ALWAYS_RESERVED) return false
    if (strict && this in STRICT_RESERVED) return false
    if (module && this == "await") return false
    if (generator && this == "yield") return false
    return true
}

private fun isIdentifierStart(codePoint: Int): Boolean =
    codePoint == '$'.code || codePoint == '_'.code || ID_START.contains(codePoint)

private fun isIdentifierPart(codePoint: Int): Boolean =
    codePoint == '$'.code ||
        codePoint == 0x200C ||
        codePoint == 0x200D ||
        ID_CONTINUE.contains(codePoint)

private val ID_START = requireNotNull(Unicode.binaryProperty("ID_Start")) {
    "the Unicode tables are missing ID_Start"
}

private val ID_CONTINUE = requireNotNull(Unicode.binaryProperty("ID_Continue")) {
    "the Unicode tables are missing ID_Continue"
}

/** Reserved in every context. */
private val ALWAYS_RESERVED = setOf(
    "break", "case", "catch", "class", "const", "continue", "debugger", "default",
    "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
    "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
    "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with",
)

/** Reserved only when the code is strict. */
private val STRICT_RESERVED = setOf(
    "let", "static", "implements", "interface", "package", "private", "protected", "public",
)

/** The `ReservedWord` production, which lists `await` and `yield` as well. */
private val RESERVED = ALWAYS_RESERVED + setOf("await", "yield")
