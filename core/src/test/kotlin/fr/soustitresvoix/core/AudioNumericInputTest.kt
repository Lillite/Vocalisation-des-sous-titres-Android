package fr.soustitresvoix.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class AudioNumericInputTest {
    private fun valid(text: String, spec: AudioNumberSpec, expected: String): BigDecimal {
        val result = AudioNumericInput.parse(text, spec)
        assertTrue("Expected $text to be valid for $spec, got $result", result is AudioNumberResult.Valid)
        val value = (result as AudioNumberResult.Valid).value
        assertEquals(0, value.compareTo(BigDecimal(expected)))
        return value
    }

    private fun invalid(text: String, spec: AudioNumberSpec, problem: AudioNumberProblem) {
        assertEquals("Unexpected validation for <$text>", AudioNumberResult.Invalid(problem), AudioNumericInput.parse(text, spec))
    }

    @Test fun frenchAndPointNotationRetainTheRequestedDecimalBeforeLevelConversion() {
        for (text in listOf("37,5", "37.5", "+37,50", "  37,5  ")) {
            assertEquals(.375f, valid(text, AudioNumericInput.PERCENT, "37.5").movePointLeft(2).toFloat(), 0f)
        }
        valid(",5", AudioNumericInput.PERCENT, ".5")
        valid(".5", AudioNumericInput.PERCENT, ".5")
        valid("100,", AudioNumericInput.PERCENT, "100")
        valid("−0", AudioNumericInput.PERCENT, "0")
        valid("000,125", AudioNumericInput.PERCENT, ".125")
    }

    @Test fun exactRangeChecksRejectValuesThatWouldRoundOntoAFloatBoundary() {
        val percent = AudioNumericInput.PERCENT
        valid("0", percent, "0")
        valid("100", percent, "100")
        invalid("100.000000000000000001", percent, AudioNumberProblem.OUT_OF_RANGE)
        invalid("-0.000000000000000001", percent, AudioNumberProblem.OUT_OF_RANGE)
        val q = AudioNumberSpec("0.1", "10", "Q")
        valid("0,1", q, ".1")
        valid("10", q, "10")
        invalid("0.099999999999999999", q, AudioNumberProblem.OUT_OF_RANGE)
        invalid("10.000000000000000001", q, AudioNumberProblem.OUT_OF_RANGE)
    }

    @Test fun ambiguousSuffixesGroupingNonFiniteAndScientificFormsNeverBecomeNumbers() {
        val values = listOf("", " ", "NaN", "Infinity", "∞", "1e2", "1E-3", "0x10", "37,5 %",
            "37%", "1 000", "1\u00a0000", "1\u202f000", "1,000.5", "1.000,5", "1,2,3", "1.2.3", "--1", "+-1", ".", ",", "١٢")
        for (value in values) invalid(value, AudioNumericInput.PERCENT, AudioNumberProblem.INVALID_NUMBER)
    }

    @Test fun boundedInputIsRejectedWholeInsteadOfSilentlyTruncated() {
        val spec = AudioNumericInput.PERCENT
        valid("0." + "0".repeat(21) + "1", spec, "0.0000000000000000000001")
        invalid("0." + "0".repeat(22) + "1", spec, AudioNumberProblem.TOO_LONG)
        invalid("9".repeat(25), spec, AudioNumberProblem.TOO_LONG)
        invalid("1" + "0".repeat(10_000), spec, AudioNumberProblem.TOO_LONG)
    }

    @Test fun millisecondEntriesRequireIntegersAndKeepInclusiveBounds() {
        val spec = AudioNumericInput.MILLISECONDS
        for (value in listOf("0", "275", "875", "2000", "+00275"))
            valid(value, spec, value.removePrefix("+")).intValueExact()
        for (value in listOf("275,0", "275.0", ",5", "1."))
            invalid(value, spec, AudioNumberProblem.INTEGER_REQUIRED)
        for (value in listOf("-1", "2001", "999999999999999999999999"))
            invalid(value, spec, AudioNumberProblem.OUT_OF_RANGE)
    }

    @Test fun genericSignedUnitsAndStoredPercentageFormattingPreservePrecision() {
        val gain = AudioNumberSpec("-24", "12", "dB")
        valid("−3,125", gain, "-3.125")
        valid("-24", gain, "-24")
        valid("+12", gain, "12")
        invalid("12.0000000000000001", gain, AudioNumberProblem.OUT_OF_RANGE)
        assertEquals("37,5", AudioNumericInput.percent(.375f))
        assertEquals("19,125", AudioNumericInput.percent(.19125f))
        assertEquals("53", AudioNumericInput.percent(.53f))
        assertEquals("0", AudioNumericInput.percent(-0f))
        assertEquals("100", AudioNumericInput.percent(1f))
        assertEquals("0,00012345", AudioNumericInput.percent(.0000012345f))
        for (stored in listOf(.375f, .19125f, .004f, .996f, .53f, .59f, 0f, 1f)) {
            val parsed = AudioNumericInput.parse(AudioNumericInput.percent(stored), AudioNumericInput.PERCENT)
                as AudioNumberResult.Valid
            assertEquals(stored, parsed.value.movePointLeft(2).toFloat(), 0f)
        }
    }
}
