package io.github.mgilbir.ecma262.unicode

/**
 * Unicode queries the regex engine needs, backed by the generated [UnicodeTables].
 *
 * Deliberately free of `java.lang.Character` so the engine stays multiplatform,
 * and so the Unicode version is pinned by this library rather than by whichever
 * JDK happens to run it.
 */
internal object Unicode {

    /** The UCD version these tables were generated from. */
    const val VERSION: String = UnicodeTables.UNICODE_VERSION

    const val MAX_CODE_POINT: Int = 0x10FFFF

    // ---------------------------------------------------------- property lookup

    /**
     * Resolves the body of a `\p{...}` escape to the set of code points it matches,
     * or null when ECMA-262 does not recognise it (which is a SyntaxError).
     *
     * Accepts `Name=Value` for General_Category / Script / Script_Extensions, and
     * a lone name that is either a binary property or a General_Category value.
     * Matching is case-sensitive and alias-aware, as the spec requires: `\p{Lu}`
     * and `\p{Uppercase_Letter}` resolve, `\p{lu}` does not.
     */
    fun resolveProperty(spec: String): RangeSet? {
        val eq = spec.indexOf('=')
        if (eq >= 0) {
            val name = spec.substring(0, eq)
            val value = spec.substring(eq + 1)
            return when (name) {
                "General_Category", "gc" -> generalCategory(value)
                "Script", "sc" -> script(value)
                "Script_Extensions", "scx" -> scriptExtensions(value)
                else -> null
            }
        }
        // A lone name is a binary property, or shorthand for a General_Category value.
        return binaryProperty(spec) ?: generalCategory(spec)
    }

    private fun lookup(table: Map<String, String>, aliases: Map<String, String>, key: String): RangeSet? {
        val canonical = aliases[key] ?: key
        val encoded = table[canonical] ?: return null
        return VarintCodec.decodeRanges(encoded)
    }

    fun generalCategory(value: String): RangeSet? =
        lookup(UnicodeTables.generalCategory, UnicodeTables.generalCategoryAliases, value)

    fun script(value: String): RangeSet? =
        lookup(UnicodeTables.script, UnicodeTables.scriptAliases, value)

    fun scriptExtensions(value: String): RangeSet? =
        lookup(UnicodeTables.scriptExtensions, UnicodeTables.scriptAliases, value)

    fun binaryProperty(name: String): RangeSet? =
        lookup(UnicodeTables.binary, UnicodeTables.binaryAliases, name)

    /**
     * Resolves a *property of strings* — `\p{RGI_Emoji}` and its five
     * constituents — to the sequences it contains, or null if the name is not
     * one.
     *
     * These exist only under the `v` flag, and only unnegated: `\P{RGI_Emoji}`
     * and `[^\p{RGI_Emoji}]` are SyntaxErrors, because complementing a set of
     * strings is not meaningful.
     */
    fun resolvePropertyOfStrings(name: String): List<IntArray>? {
        val encoded = UnicodeTables.propertiesOfStrings[name] ?: return null
        return VarintCodec.decodeSequences(encoded)
    }

    // ------------------------------------------------------------ case mappings

    private val caseFold: Pair<IntArray, IntArray> by lazy {
        VarintCodec.decodeMapping(UnicodeTables.simpleCaseFolding)
    }

    private val upperMap: Pair<IntArray, IntArray> by lazy {
        VarintCodec.decodeMapping(UnicodeTables.simpleUppercase)
    }

    private fun mapped(m: Pair<IntArray, IntArray>, cp: Int): Int {
        val (keys, vals) = m
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = keys[mid]
            when {
                cp < k -> hi = mid - 1
                cp > k -> lo = mid + 1
                else -> return vals[mid]
            }
        }
        return cp
    }

    /**
     * Simple case folding (CaseFolding.txt status C+S).
     *
     * This is the canonicalization ECMA-262 uses for case-insensitive matching
     * under the `u` and `v` flags.
     */
    fun simpleCaseFold(cp: Int): Int {
        if (cp < 0x80) return if (cp in 'A'.code..'Z'.code) cp + 32 else cp
        return mapped(caseFold, cp)
    }

    /** Simple uppercase mapping, used by the legacy (non-`u`) Canonicalize. */
    fun simpleUppercase(cp: Int): Int {
        if (cp < 0x80) return if (cp in 'a'.code..'z'.code) cp - 32 else cp
        return mapped(upperMap, cp)
    }

    // ------------------------------------------------------ case-equivalence orbits

    private val foldOrbit: Pair<IntArray, IntArray> by lazy {
        VarintCodec.decodeMapping(UnicodeTables.foldOrbit)
    }

    private val legacyOrbit: Pair<IntArray, IntArray> by lazy {
        VarintCodec.decodeMapping(UnicodeTables.legacyOrbit)
    }

    /**
     * Every code point that has at least one case-equivalent partner.
     *
     * Building the case closure of a character set means asking, for each of
     * these, whether it is in the set — iterating the set itself is impossible
     * when it spans ranges as large as `\p{Any}`.
     */
    fun caseOrbitMembers(unicodeMode: Boolean): IntArray =
        if (unicodeMode) foldOrbit.first else legacyOrbit.first

    /**
     * The next code point in [codePoint]'s case-equivalence class, cycling back
     * to the start. Returns [codePoint] itself when it has no partners.
     */
    fun caseOrbitNext(codePoint: Int, unicodeMode: Boolean): Int =
        mapped(if (unicodeMode) foldOrbit else legacyOrbit, codePoint)

    /**
     * ECMA-262 Canonicalize: simple case folding under `u`/`v`, otherwise the
     * legacy uppercase mapping guarded so a non-ASCII character never
     * canonicalizes onto an ASCII one.
     */
    fun canonicalize(codePoint: Int, ignoreCase: Boolean, unicodeMode: Boolean): Int {
        if (!ignoreCase) return codePoint
        if (unicodeMode) return simpleCaseFold(codePoint)
        val upper = simpleUppercase(codePoint)
        return if (codePoint >= 128 && upper < 128) codePoint else upper
    }

    // -------------------------------------------------------------- identifiers

    private val idStart: RangeSet by lazy { binaryProperty("ID_Start") ?: RangeSet.EMPTY }
    private val idContinue: RangeSet by lazy { binaryProperty("ID_Continue") ?: RangeSet.EMPTY }

    /** ECMA-262 `IdentifierStartChar`: ID_Start, plus `$` and `_`. */
    fun isIdentifierStart(cp: Int): Boolean {
        if (cp < 0x80) {
            return cp == '$'.code || cp == '_'.code ||
                (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code)
        }
        return idStart.contains(cp)
    }

    /**
     * ECMA-262 `IdentifierPartChar`: ID_Continue, plus `$`, and the
     * zero-width non-joiner and joiner.
     */
    fun isIdentifierPart(cp: Int): Boolean {
        if (cp < 0x80) {
            return cp == '$'.code || cp == '_'.code ||
                (cp in 'a'.code..'z'.code) || (cp in 'A'.code..'Z'.code) ||
                (cp in '0'.code..'9'.code)
        }
        if (cp == 0x200C || cp == 0x200D) return true
        return idContinue.contains(cp)
    }
}
