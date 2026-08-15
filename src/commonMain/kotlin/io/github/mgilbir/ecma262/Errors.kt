package io.github.mgilbir.ecma262

/**
 * Thrown for any pattern or flag string that ECMA-262 rejects.
 *
 * JavaScript reports every such failure as a `SyntaxError`, so this is a single
 * type rather than a hierarchy; [message] carries the specific reason.
 */
public class RegExpSyntaxError(
    message: String,
    /** Offset into the offending pattern, in UTF-16 code units, or -1 when not positional. */
    public val position: Int = -1,
) : IllegalArgumentException(message)

/**
 * Thrown when a match exceeds the engine's step budget.
 *
 * The engine is a backtracker, so a pathological pattern/input pair can take
 * exponential time. Rather than hang, execution is bounded by
 * [RegExp.maxSteps] and aborted with this error.
 */
public class RegExpStepLimitError(
    public val steps: Long,
) : RuntimeException("regexp execution step limit exceeded ($steps steps)")
