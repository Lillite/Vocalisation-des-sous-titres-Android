package fr.soustitresvoix.core

import java.math.BigDecimal

/** Decimal bounds belong to the control's public unit (%, ms, dB, Hz or Q).
 * Validation happens before conversion to a binary float, so a value just beyond
 * a bound cannot be rounded into an accepted value. */
data class AudioNumberSpec(
    val minimum: String,
    val maximum: String,
    val unit: String,
    val integerOnly: Boolean = false
) {
    internal val lower = BigDecimal(minimum)
    internal val upper = BigDecimal(maximum)

    init {
        require(lower <= upper)
        require(lower.toFloat().isFinite() && upper.toFloat().isFinite())
    }
}

enum class AudioNumberProblem { TOO_LONG, INVALID_NUMBER, INTEGER_REQUIRED, OUT_OF_RANGE }

sealed interface AudioNumberResult {
    data class Valid(val value: BigDecimal) : AudioNumberResult
    data class Invalid(val reason: AudioNumberProblem) : AudioNumberResult
}

/** Strict locale-independent grammar; accepts French comma or decimal point, but
 * never grouping, a unit suffix, exponent notation, NaN or infinity. */
object AudioNumericInput {
    const val MAX_LENGTH = 24
    val PERCENT = AudioNumberSpec("0", "100", "%")
    val MILLISECONDS = AudioNumberSpec("0", "2000", "ms", integerOnly = true)
    private val decimal = Regex("[+-]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)")
    private val integer = Regex("[+-]?[0-9]+")

    fun parse(raw: String, spec: AudioNumberSpec): AudioNumberResult {
        val text = raw.trim().replace('−', '-')
        if (text.length > MAX_LENGTH) return AudioNumberResult.Invalid(AudioNumberProblem.TOO_LONG)
        if (!decimal.matches(text)) return AudioNumberResult.Invalid(AudioNumberProblem.INVALID_NUMBER)
        if (spec.integerOnly && !integer.matches(text))
            return AudioNumberResult.Invalid(AudioNumberProblem.INTEGER_REQUIRED)
        val value = text.replace(',', '.').toBigDecimalOrNull()
            ?: return AudioNumberResult.Invalid(AudioNumberProblem.INVALID_NUMBER)
        if (value < spec.lower || value > spec.upper)
            return AudioNumberResult.Invalid(AudioNumberProblem.OUT_OF_RANGE)
        return AudioNumberResult.Valid(value)
    }

    /** The shortest decimal representation of the stored float, in French.
     * Moving the decimal point before display avoids an extra float multiplication. */
    fun percent(value: Float): String = format(BigDecimal(value.toString()).movePointRight(2))

    fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString().replace('.', ',')
}
