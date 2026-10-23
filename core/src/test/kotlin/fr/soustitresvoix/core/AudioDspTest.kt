package fr.soustitresvoix.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class AudioDspTest {
    @Test fun policyChecksEveryFieldEvenWhenDisabledAndAcceptsBothInclusiveBounds() {
        val defaults = AudioDspSettings()
        assertNull(AudioDspPolicy.validate(defaults))
        for (upper in listOf(false, true)) {
            var edge = defaults.copy(preampDb = if (upper) 12.0 else -24.0,
                balance = if (upper) 1.0 else -1.0, limiterCeilingDb = if (upper) 0.0 else -12.0,
                limiterReleaseMs = if (upper) 2000 else 10)
            for (index in 0 until 5) edge = edge.withBand(index, AudioEqBand(
                frequencyHz = if (upper) 20000.0 else 20.0, gainDb = if (upper) 12.0 else -12.0,
                q = if (upper) 10.0 else 0.1))
            assertNull(AudioDspPolicy.validate(edge))
        }
        val invalid = mutableListOf(
            defaults.copy(preampDb = Math.nextDown(-24.0)), defaults.copy(preampDb = Math.nextUp(12.0)),
            defaults.copy(balance = Math.nextDown(-1.0)), defaults.copy(balance = Math.nextUp(1.0)),
            defaults.copy(limiterCeilingDb = Math.nextDown(-12.0)), defaults.copy(limiterCeilingDb = Math.nextUp(0.0)),
            defaults.copy(limiterReleaseMs = 9), defaults.copy(limiterReleaseMs = 2001))
        for (index in 0 until 5) {
            val band = defaults.band(index)
            invalid += defaults.withBand(index, band.copy(frequencyHz = Math.nextDown(20.0)))
            invalid += defaults.withBand(index, band.copy(frequencyHz = Math.nextUp(20000.0)))
            invalid += defaults.withBand(index, band.copy(gainDb = Math.nextDown(-12.0)))
            invalid += defaults.withBand(index, band.copy(gainDb = Math.nextUp(12.0)))
            invalid += defaults.withBand(index, band.copy(q = Math.nextDown(0.1)))
            invalid += defaults.withBand(index, band.copy(q = Math.nextUp(10.0)))
        }
        invalid.forEach { assertEquals(AudioDspProblem.OUT_OF_RANGE, AudioDspPolicy.validate(it)) }
        assertEquals(AudioDspSettings(), defaults)
    }

    @Test fun nonFiniteValuesAreRejectedAndCanonicalizationOnlyNormalizesSignedZero() {
        val defaults = AudioDspSettings()
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val invalid = mutableListOf(defaults.copy(preampDb = number), defaults.copy(balance = number),
                defaults.copy(limiterCeilingDb = number))
            for (index in 0 until 5) {
                val band = defaults.band(index)
                invalid += defaults.withBand(index, band.copy(frequencyHz = number))
                invalid += defaults.withBand(index, band.copy(gainDb = number))
                invalid += defaults.withBand(index, band.copy(q = number))
            }
            invalid.forEach { assertEquals(AudioDspProblem.NON_FINITE, AudioDspPolicy.validate(it)) }
        }
        var signed = defaults.copy(preampDb = -0.0, balance = -0.0, limiterCeilingDb = -0.0)
        for (index in 0 until 5) signed = signed.withBand(index, signed.band(index).copy(gainDb = -0.0))
        val canonical = AudioDspPolicy.canonicalize(signed)
        assertEquals(0.0.toRawBits(), canonical.preampDb.toRawBits())
        assertEquals(0.0.toRawBits(), canonical.balance.toRawBits())
        assertEquals(0.0.toRawBits(), canonical.limiterCeilingDb.toRawBits())
        for (index in 0 until 5) assertEquals(0.0.toRawBits(), canonical.band(index).gainDb.toRawBits())
        assertEquals((-0.0).toRawBits(), signed.preampDb.toRawBits())
        assertEquals(100.0, AudioDspPolicy.canonicalize(defaults.copy(preampDb = 100.0)).preampDb, 0.0)
        assertTrue(AudioDspPolicy.canonicalize(defaults.copy(balance = Double.NaN)).balance.isNaN())
    }

    @Test fun bandReplacementUsesFixedIndependentFieldsWithoutMutatingTheOriginal() {
        val original = AudioDspSettings()
        for (selected in 0 until 5) {
            val replacement = AudioEqBand(true, 1375.0, -3.25, 2.5)
            val changed = original.withBand(selected, replacement)
            for (index in 0 until 5) assertEquals(if (index == selected) replacement else original.band(index), changed.band(index))
            assertEquals(AudioDspSettings(), original)
        }
        assertThrows(IndexOutOfBoundsException::class.java) { original.band(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { original.withBand(5, original.band1) }
    }

    @Test fun globallyDisabledAndEnabledNeutralChainsPreserveAllPcm16ValuesExactly() {
        val disabled = AudioDspEngine(48000, 2)
        val neutral = AudioDspEngine(48000, 2, AudioDspSettings(enabled = true, limiterEnabled = false,
            band3 = AudioEqBand(true, 1000.0, 0.0, 10.0)))
        for (value in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            val left = value / 32768.0
            val right = (-value - 1) / 32768.0
            for (engine in listOf(disabled, neutral)) {
                engine.processFrame(left, right)
                assertBits(left, engine.outputLeft)
                assertBits(right, engine.outputRight)
            }
        }
        assertTrue(disabled.isBypass)
        assertEquals(65536L, disabled.processedFrames)
        assertEquals(0L, disabled.limitedFrames)
        assertEquals(0L, neutral.limitedFrames)
    }

    @Test fun preampBalanceAndStereoMeanFollowTheirAnalyticGainsWhileMonoIgnoresBalance() {
        val settings = AudioDspSettings(enabled = true, limiterEnabled = false, preampDb = 6.0, balance = 0.25)
        val stereo = AudioDspEngine(48000, 2, settings)
        val multiplier = 10.0.pow(6.0 / 20.0)
        stereo.processFrame(0.1, -0.2)
        assertEquals(0.1 * multiplier * 0.75, stereo.outputLeft, 1e-15)
        assertEquals(-0.2 * multiplier, stereo.outputRight, 1e-15)
        val mean = AudioDspEngine(48000, 2, settings.copy(mono = true))
        mean.processFrame(0.1, -0.2)
        assertEquals(-0.05 * multiplier * 0.75, mean.outputLeft, 1e-15)
        assertEquals(-0.05 * multiplier, mean.outputRight, 1e-15)
        mean.processFrame(0.2, -0.2)
        assertBits(0.0, mean.outputLeft)
        assertBits(0.0, mean.outputRight)
        val mono = AudioDspEngine(48000, 1, settings.copy(balance = -1.0, mono = true))
        mono.processFrame(0.1, 0.9)
        assertEquals(0.1 * multiplier, mono.outputLeft, 1e-15)
        assertBits(mono.outputLeft, mono.outputRight)
        for (balance in listOf(-1.0, 1.0)) {
            val extreme = AudioDspEngine(48000, 2, settings.copy(preampDb = 0.0, balance = balance))
            extreme.processFrame(0.25, -0.5)
            assertBits(if (balance < 0.0) 0.25 else 0.0, extreme.outputLeft)
            assertEquals(if (balance > 0.0) -0.5 else 0.0, extreme.outputRight, 0.0)
        }
    }

    @Test fun eachParametricBandHasItsRequestedCenterGainAtCommonSampleRates() {
        val defaults = AudioDspSettings(enabled = true, limiterEnabled = false)
        for (sampleRate in listOf(44100, 48000, 96000)) for (index in 0 until 5) for (db in listOf(-12.0, 6.0)) {
            val band = defaults.band(index).copy(enabled = true, gainDb = db)
            val measured = responseDb(sampleRate, defaults.withBand(index, band), band.frequencyHz)
            assertEquals("sampleRate=$sampleRate band=$index gain=$db", db, measured, 0.002)
        }
    }

    @Test fun qualityFactorChangesSelectivityWithoutChangingTheCenterGain() {
        fun settings(q: Double) = AudioDspSettings(enabled = true, limiterEnabled = false,
            band3 = AudioEqBand(true, 1000.0, 12.0, q))
        val broad = settings(0.1)
        val narrow = settings(10.0)
        assertEquals(12.0, responseDb(48000, broad, 1000.0), 0.002)
        assertEquals(12.0, responseDb(48000, narrow, 1000.0), 0.002)
        assertTrue(responseDb(48000, broad, 2000.0) > 6.0)
        assertTrue(responseDb(48000, narrow, 2000.0) < 0.2)
    }

    @Test fun oppositeEqualizerBandsCancelWithoutCrossTalkOrAddedFrames() {
        val settings = AudioDspSettings(enabled = true, limiterEnabled = false,
            band1 = AudioEqBand(true, 1000.0, 12.0, 2.0),
            band2 = AudioEqBand(true, 1000.0, -12.0, 2.0))
        val engine = AudioDspEngine(48000, 2, settings)
        for (frame in 0 until 48000) {
            val left = 0.02 * sin(2.0 * PI * 137.0 * frame / 48000.0) +
                0.01 * sin(2.0 * PI * 5371.0 * frame / 48000.0)
            engine.processFrame(left, 0.0)
            assertEquals(left, engine.outputLeft, 1e-12)
            assertBits(0.0, engine.outputRight)
        }
        assertEquals(48000L, engine.processedFrames)
        assertEquals(0, engine.skippedBandMask)
    }

    @Test fun nyquistBandsAreOmittedWithoutMovingTheirFrequencyAndDisabledBandsRemainLatent() {
        val settings = AudioDspSettings(enabled = true, limiterEnabled = false,
            band1 = AudioEqBand(false, 20000.0, 12.0, 1.0),
            band2 = AudioEqBand(true, 4000.0, 6.0, 1.0),
            band3 = AudioEqBand(true, 12000.0, -6.0, 1.0),
            band4 = AudioEqBand(true, 20000.0, 0.0, 1.0))
        val engine = AudioDspEngine(8000, 2, settings)
        assertEquals((1 shl 1) or (1 shl 2), engine.skippedBandMask)
        for (frame in 0 until 8000) {
            val sample = sin(2.0 * PI * 3500.0 * frame / 8000.0) * 0.1
            engine.processFrame(sample, -sample)
            assertBits(sample, engine.outputLeft)
            assertBits(-sample, engine.outputRight)
        }
        assertEquals(settings, engine.requestedSettings)
        assertEquals(4000.0, engine.appliedSettings.band2.frequencyHz, 0.0)
    }

    @Test fun extremeValidatedFiltersStayFiniteAndKeepIndependentChannelState() {
        for (sampleRate in listOf(8000, 44100, 192000)) {
            val frequencies = listOf(20.0, minOf(20000.0, Math.nextDown(sampleRate * 0.5)))
            for (frequency in frequencies) for (q in listOf(0.1, 10.0)) for (gain in listOf(-12.0, 12.0)) {
                var settings = AudioDspSettings(enabled = true, limiterEnabled = false, preampDb = 12.0)
                for (index in 0 until 5) settings = settings.withBand(index, AudioEqBand(true, frequency, gain, q))
                val engine = AudioDspEngine(sampleRate, 2, settings)
                var energy = 0.0
                for (frame in 0 until 16000) {
                    engine.processFrame(if (frame == 0) 0.001 else 0.0, 0.0)
                    assertTrue(engine.outputLeft.isFinite())
                    assertTrue(abs(engine.outputLeft) < 100.0)
                    assertBits(0.0, engine.outputRight)
                    energy += engine.outputLeft * engine.outputLeft
                }
                assertTrue(energy > 0.0)
                assertEquals(16000L, engine.processedFrames)
            }
        }
    }

    @Test fun limiterContainsBothSignsAndUsesOneGainForTheStereoPairIncludingZeroDbCeiling() {
        for (ceiling in listOf(-12.0, -1.0, 0.0)) {
            val engine = AudioDspEngine(48000, 2, AudioDspSettings(enabled = true, preampDb = 12.0,
                limiterCeilingDb = ceiling))
            val bound = 10.0.pow(ceiling / 20.0)
            for (sign in listOf(-1.0, 1.0)) {
                engine.processFrame(sign, -sign / 4.0)
                assertTrue(engine.lastPeak <= bound)
                assertTrue(engine.lastPeak <= 32767.0 / 32768.0)
                assertEquals(-4.0, engine.outputLeft / engine.outputRight, 1e-12)
                assertTrue(abs(engine.outputLeft) > bound - 1.0 / 32768.0 - 1e-15)
            }
            assertEquals(2L, engine.limitedFrames)
        }
    }

    @Test fun limiterRecoveryUsesTheSpecifiedTimeConstantAndNeverAmplifiesTheSignal() {
        val preamp = 10.0.pow(12.0 / 20.0)
        val engine = AudioDspEngine(48000, 2, AudioDspSettings(enabled = true, preampDb = 12.0,
            limiterReleaseMs = 100))
        engine.processFrame(0.8, -0.2)
        val initialGain = engine.outputLeft / (0.8 * preamp)
        for (frame in 1..4800) {
            engine.processFrame(0.001, -0.002)
            val expectedGain = 1.0 - (1.0 - initialGain) * exp(-frame / 4800.0)
            assertEquals(0.001 * preamp * expectedGain, engine.outputLeft, 1e-13)
            assertEquals(-0.002 * preamp * expectedGain, engine.outputRight, 1e-13)
            assertTrue(engine.outputLeft <= 0.001 * preamp)
        }
    }

    @Test fun aNewActiveCeilingAppliesImmediatelyDuringCrossfadesAndRapidRequests() {
        var settings = AudioDspSettings(enabled = true, limiterEnabled = false)
        val engine = AudioDspEngine(48000, 2, settings)
        engine.processFrame(0.9, -0.3)
        for (ceiling in listOf(-12.0, -3.0, -9.0, -1.0, -6.0)) {
            settings = settings.copy(preampDb = if (settings.preampDb == 12.0) 6.0 else 12.0,
                limiterEnabled = true, limiterCeilingDb = ceiling,
                band3 = AudioEqBand(true, 1000.0, if (ceiling == -9.0) -12.0 else 12.0, 1.0))
            engine.update(settings)
            assertTrue(engine.isTransitioning)
            repeat(20) {
                engine.processFrame(0.9, -0.3)
                assertTrue("ceiling=$ceiling peak=${engine.lastPeak}", engine.lastPeak <= 10.0.pow(ceiling / 20.0))
                assertEquals(-3.0, engine.outputLeft / engine.outputRight, 1e-9)
            }
        }
    }

    @Test fun rapidRequestsAreBoundedAndGlobalDisableEventuallyRestoresExactBypass() {
        val active = AudioDspSettings(enabled = true, preampDb = 12.0,
            band3 = AudioEqBand(true, 1000.0, 12.0, 10.0))
        val engine = AudioDspEngine(48000, 2, active)
        repeat(100) { engine.processFrame(0.9, -0.8) }
        assertTrue(engine.limitedFrames > 0)
        for (index in 0 until 100) {
            engine.update(active.copy(preampDb = if (index % 2 == 0) 6.0 else -12.0,
                balance = (index % 3 - 1) * 0.5))
            engine.processFrame(0.9, -0.8)
        }
        val disabled = active.copy(enabled = false)
        engine.update(disabled)
        assertFalse(engine.isBypass)
        repeat(48000 * 60 / 1000 + 1) { engine.processFrame(0.9, -0.8) }
        assertFalse(engine.isTransitioning)
        assertTrue(engine.isBypass)
        assertEquals(disabled, engine.appliedSettings)
        assertEquals(disabled, engine.requestedSettings)
        for (index in -32768..32767) {
            val left = index / 32768.0
            val right = (-index - 1) / 32768.0
            engine.processFrame(left, right)
            assertBits(left, engine.outputLeft)
            assertBits(right, engine.outputRight)
        }
    }

    @Test fun limiterOnlyAndLatentChangesKeepTheLiveEqualizerStateAndDoNotStartCrossfades() {
        val settings = AudioDspSettings(enabled = true, band1 = AudioEqBand(true, 60.0, 12.0, 10.0))
        val baseline = AudioDspEngine(48000, 2, settings)
        val changed = AudioDspEngine(48000, 2, settings)
        for (frame in 0 until 10000) {
            if (frame == 125) changed.update(settings.copy(limiterCeilingDb = -0.5, limiterReleaseMs = 2000,
                band4 = settings.band4.copy(frequencyHz = 1370.0, gainDb = 6.0, q = 0.5)))
            val input = if (frame == 0) 0.01 else 0.0
            baseline.processFrame(input, -input / 2.0)
            changed.processFrame(input, -input / 2.0)
            assertFalse(changed.isTransitioning)
            assertBits(baseline.outputLeft, changed.outputLeft)
            assertBits(baseline.outputRight, changed.outputRight)
        }
        val disabled = AudioDspEngine(48000, 2)
        val latent = AudioDspSettings(preampDb = 12.0, balance = -1.0, mono = true,
            band1 = AudioEqBand(true, 20.0, 12.0, 10.0))
        disabled.update(latent)
        assertTrue(disabled.isBypass)
        assertFalse(disabled.isTransitioning)
        assertEquals(latent, disabled.appliedSettings)
        disabled.processFrame(0.25, -0.5)
        assertBits(0.25, disabled.outputLeft)
        assertBits(-0.5, disabled.outputRight)
    }

    @Test fun repeatedBlockBoundaryUpdatesAndSignedZerosDoNotChangeSamplesOrRestartRamps() {
        val initial = AudioDspSettings(enabled = true, preampDb = -6.0, limiterEnabled = false,
            band3 = AudioEqBand(true, 1000.0, 6.0, 0.7))
        val next = initial.copy(preampDb = -12.0, mono = true, balance = 0.25)
        val once = AudioDspEngine(48000, 2, initial)
        val repeated = AudioDspEngine(48000, 2, initial)
        for (frame in 0 until 8000) {
            if (frame == 1000) { once.update(next); repeated.update(next) }
            if (frame % 17 == 0) repeated.update(if (frame < 1000) initial else next)
            val left = 0.1 * sin(frame * 0.17)
            val right = -0.2 * sin(frame * 0.05)
            once.processFrame(left, right)
            repeated.processFrame(left, right)
            assertBits(once.outputLeft, repeated.outputLeft)
            assertBits(once.outputRight, repeated.outputRight)
            assertEquals(once.isTransitioning, repeated.isTransitioning)
        }
        assertFalse(once.isTransitioning)
        assertFalse(repeated.isTransitioning)
        val zero = AudioDspEngine(48000, 2, AudioDspSettings(enabled = true, limiterEnabled = false, limiterCeilingDb = 0.0))
        zero.update(zero.requestedSettings.copy(preampDb = -0.0, balance = -0.0, limiterCeilingDb = -0.0,
            band1 = zero.requestedSettings.band1.copy(gainDb = -0.0)))
        assertFalse(zero.isTransitioning)
    }

    @Test fun nonFiniteInputCannotPoisonTheFiltersOrFutureValidFrames() {
        val settings = AudioDspSettings(enabled = true, band3 = AudioEqBand(true, 1000.0, 12.0, 10.0))
        val sanitized = AudioDspEngine(48000, 2, settings)
        val reference = AudioDspEngine(48000, 2, settings)
        val numbers = listOf(0.1, Double.NaN, 0.2, Double.POSITIVE_INFINITY, -0.3, Double.NEGATIVE_INFINITY, 0.0)
        repeat(50) {
            for (sample in numbers) {
                sanitized.processFrame(sample, -sample)
                reference.processFrame(if (sample.isFinite()) sample else 0.0,
                    if (sample.isFinite()) -sample else 0.0)
                assertTrue(sanitized.outputLeft.isFinite())
                assertTrue(sanitized.outputRight.isFinite())
                assertBits(reference.outputLeft, sanitized.outputLeft)
                assertBits(reference.outputRight, sanitized.outputRight)
            }
        }
    }

    @Test fun unsupportedFormatsAndInvalidUpdatesFailBeforeChangingTheEngine() {
        assertThrows(IllegalArgumentException::class.java) { AudioDspEngine(0, 2) }
        assertThrows(IllegalArgumentException::class.java) { AudioDspEngine(-1, 2) }
        assertThrows(IllegalArgumentException::class.java) { AudioDspEngine(48000, 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioDspEngine(48000, 6) }
        assertThrows(IllegalArgumentException::class.java) { AudioDspEngine(48000, 2, AudioDspSettings(balance = Double.NaN)) }
        val engine = AudioDspEngine(48000, 2)
        assertThrows(IllegalArgumentException::class.java) { engine.update(AudioDspSettings(preampDb = 100.0)) }
        assertEquals(AudioDspSettings(), engine.requestedSettings)
        assertTrue(engine.isBypass)
        assertFalse(engine.isTransitioning)
        engine.processFrame(0.1, -0.2)
        assertBits(0.1, engine.outputLeft)
        assertBits(-0.2, engine.outputRight)
    }

    private fun responseDb(sampleRate: Int, settings: AudioDspSettings, frequency: Double): Double {
        val engine = AudioDspEngine(sampleRate, 1, settings)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        val warmup = sampleRate / 2
        val frames = warmup + sampleRate
        for (frame in 0 until frames) {
            val input = 0.001 * sin(2.0 * PI * frequency * frame / sampleRate)
            engine.processFrame(input)
            if (frame >= warmup) {
                inputEnergy += input * input
                outputEnergy += engine.outputLeft * engine.outputLeft
            }
        }
        return 10.0 * log10(outputEnergy / inputEnergy)
    }

    private fun assertBits(expected: Double, actual: Double) = assertEquals(expected.toRawBits(), actual.toRawBits())
}
