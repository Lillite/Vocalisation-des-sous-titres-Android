package fr.soustitresvoix.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** A peaking equalizer, using the Q convention of the W3C/RBJ Audio EQ Cookbook. */
data class AudioEqBand(
    val enabled: Boolean = false,
    val frequencyHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 1.0
)

/**
 * Fixed immutable fields deliberately avoid sharing a mutable List with the audio thread.
 * The global switch is off by default: even the preselected limiter has no effect then.
 * These settings concern the film, never the separate narrated voice.
 */
data class AudioDspSettings(
    val enabled: Boolean = false,
    val preampDb: Double = 0.0,
    val balance: Double = 0.0,
    val mono: Boolean = false,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Double = -1.0,
    val limiterReleaseMs: Int = 100,
    val band1: AudioEqBand = AudioEqBand(frequencyHz = 60.0),
    val band2: AudioEqBand = AudioEqBand(frequencyHz = 250.0),
    val band3: AudioEqBand = AudioEqBand(frequencyHz = 1000.0),
    val band4: AudioEqBand = AudioEqBand(frequencyHz = 4000.0),
    val band5: AudioEqBand = AudioEqBand(frequencyHz = 12000.0)
) {
    fun band(index: Int): AudioEqBand = when (index) {
        0 -> band1; 1 -> band2; 2 -> band3; 3 -> band4; 4 -> band5
        else -> throw IndexOutOfBoundsException("Equalizer band index must be 0..4")
    }

    fun withBand(index: Int, value: AudioEqBand): AudioDspSettings = when (index) {
        0 -> copy(band1 = value); 1 -> copy(band2 = value); 2 -> copy(band3 = value)
        3 -> copy(band4 = value); 4 -> copy(band5 = value)
        else -> throw IndexOutOfBoundsException("Equalizer band index must be 0..4")
    }
}

enum class AudioDspProblem { NON_FINITE, OUT_OF_RANGE }

/** Validates every stored value, including currently disabled controls. Never clamps. */
object AudioDspPolicy {
    fun validate(settings: AudioDspSettings): AudioDspProblem? {
        if (!settings.preampDb.isFinite() || !settings.balance.isFinite() ||
            !settings.limiterCeilingDb.isFinite()) return AudioDspProblem.NON_FINITE
        for (index in 0 until 5) {
            val band = settings.band(index)
            if (!band.frequencyHz.isFinite() || !band.gainDb.isFinite() || !band.q.isFinite())
                return AudioDspProblem.NON_FINITE
        }
        if (settings.preampDb !in -24.0..12.0 || settings.balance !in -1.0..1.0 ||
            settings.limiterCeilingDb !in -12.0..0.0 || settings.limiterReleaseMs !in 10..2000)
            return AudioDspProblem.OUT_OF_RANGE
        for (index in 0 until 5) {
            val band = settings.band(index)
            if (band.frequencyHz !in 20.0..20000.0 || band.gainDb !in -12.0..12.0 || band.q !in 0.1..10.0)
                return AudioDspProblem.OUT_OF_RANGE
        }
        return null
    }

    /** Only normalizes signed zero. Call validate separately; no invalid value is repaired here. */
    fun canonicalize(settings: AudioDspSettings): AudioDspSettings {
        fun zero(value: Double) = if (value == 0.0) 0.0 else value
        fun band(value: AudioEqBand) = value.copy(gainDb = zero(value.gainDb))
        return settings.copy(preampDb = zero(settings.preampDb), balance = zero(settings.balance),
            limiterCeilingDb = zero(settings.limiterCeilingDb),
            band1 = band(settings.band1), band2 = band(settings.band2), band3 = band(settings.band3),
            band4 = band(settings.band4), band5 = band(settings.band5))
    }
}

/**
 * One audio-thread-owned, mono/stereo floating-point film processor. Inputs and outputs use
 * normalized PCM amplitude (one PCM16 step = 1 / 32768). It consumes and produces exactly
 * one frame per call, with no look-ahead, resampling, frame removal or interleaving changes.
 *
 * Order: preamp -> five peaking biquads -> optional stereo mean -> balance -> linked limiter.
 * On mono input, mono and balance have no effect. Balance only attenuates the opposite
 * channel. The mean is (L + R) / 2; antiphase signals can cancel. No source separation.
 *
 * Signal-chain changes crossfade two stable chains over 20 ms. Rapid changes are coalesced
 * to the last requested chain after the current crossfade; coefficients are never morphed.
 * The last requested active limiter ceiling applies immediately, even during crossfades.
 * A global disable retains the old limiter until the dry chain settles, then releases its
 * remaining gain over 20 ms. Disabling only the limiter starts that release immediately.
 *
 * The limiter is a zero-look-ahead SAMPLE-peak limiter, not a true-peak or loudness meter.
 * Its ceiling rounds downward to a representable positive PCM16 amplitude, so final nearest-
 * value quantization cannot overshoot it (at most one step below the requested ceiling).
 * Instantaneous gain reduction may distort transients. Its exponential recovery has the
 * requested time constant; this is not the time to reach exactly unity. Quantization and
 * final PCM16 saturation belong to the adapter and may clip when limiting is disabled.
 *
 * Allocation occurs only on construction and update (including queued coefficient/state
 * sets). processFrame performs no object/array allocation. It is not thread-safe; publish
 * immutable settings to the audio owner, then call update between input blocks.
 */
class AudioDspEngine(
    val sampleRate: Int,
    val channelCount: Int,
    initial: AudioDspSettings = AudioDspSettings()
) {
    init {
        require(sampleRate > 0) { "A positive sample rate is required" }
        require(channelCount in 1..2) { "The advanced film processor supports mono or stereo only" }
        require(AudioDspPolicy.validate(initial) == null) { "Invalid audio DSP settings" }
    }

    private val rampFrames = max(1, (sampleRate.toLong() * 20L / 1000L).toInt())
    var requestedSettings: AudioDspSettings = AudioDspPolicy.canonicalize(initial)
        private set
    private var current = SignalChain(requestedSettings, sampleRate, channelCount)
    private var incoming: SignalChain? = null
    private var pending: SignalChain? = null
    private var rampPosition = 0
    private var limiterActive = requestedSettings.enabled && requestedSettings.limiterEnabled
    private var limiterCeiling = pcmCeiling(requestedSettings.limiterCeilingDb)
    private var limiterRecovery = recovery(requestedSettings.limiterReleaseMs)
    private var limiterGain = 1.0
    private var limiterReleaseRemaining = 0

    var outputLeft: Double = 0.0; private set
    var outputRight: Double = 0.0; private set
    var processedFrames: Long = 0; private set
    var limitedFrames: Long = 0; private set
    /** Absolute peak of the last output frame before adapter quantization, not an acoustic measure. */
    var lastPeak: Double = 0.0; private set

    /**
     * Target of the preamp/EQ/mono/balance chain; isTransitioning qualifies its effective state.
     * Limiter changes instead use requestedSettings immediately, with the documented release.
     */
    val appliedSettings: AudioDspSettings get() = incoming?.settings ?: current.settings
    /** Bit i identifies a nonzero enabled band omitted in that target, never frequency-clamped. */
    val skippedBandMask: Int get() = incoming?.skippedBandMask ?: current.skippedBandMask
    val isTransitioning: Boolean get() = incoming != null || pending != null || limiterReleaseRemaining > 0
    /** True only after all transitions and limiter release settle to global-off exact bypass. */
    val isBypass: Boolean get() = !current.settings.enabled && incoming == null && pending == null &&
        !limiterActive && limiterGain == 1.0

    fun update(settings: AudioDspSettings) {
        require(AudioDspPolicy.validate(settings) == null) { "Invalid audio DSP settings" }
        val next = AudioDspPolicy.canonicalize(settings)
        if (next == requestedSettings) return
        requestedSettings = next
        // Keep at most two chains evaluating and one last pending immutable configuration.
        val arriving = incoming
        if (arriving != null) {
            if (arriving.sameSignalAs(next)) {
                arriving.adoptMetadata(next)
                pending = null
            } else if (pending?.sameSignalAs(next) == true) {
                pending!!.adoptMetadata(next)
            } else {
                pending = SignalChain(next, sampleRate, channelCount)
            }
        } else if (!current.sameSignalAs(next)) {
            incoming = SignalChain(next, sampleRate, channelCount)
            pending = null
            rampPosition = 0
        } else {
            current.adoptMetadata(next)
            pending = null
        }
        if (next.enabled && next.limiterEnabled) {
            limiterActive = true
            limiterCeiling = pcmCeiling(next.limiterCeilingDb)
            limiterRecovery = recovery(next.limiterReleaseMs)
            limiterReleaseRemaining = 0
        } else if (next.enabled) {
            beginLimiterRelease()
        }
        // Global disable waits for its dry signal chain to settle before retiring the limiter.
    }

    fun processFrame(left: Double, right: Double = left) {
        val inputLeft = if (left.isFinite()) left else 0.0
        val inputRight = if (channelCount == 1) inputLeft else if (right.isFinite()) right else 0.0
        if (incoming == null && pending != null) {
            incoming = pending
            pending = null
            rampPosition = 0
        }
        current.process(inputLeft, inputRight)
        var outLeft = current.left
        var outRight = current.right
        val next = incoming
        if (next != null) {
            next.process(inputLeft, inputRight)
            val amount = (rampPosition + 1).toDouble() / rampFrames
            outLeft += (next.left - outLeft) * amount
            outRight += (next.right - outRight) * amount
            rampPosition++
            if (rampPosition == rampFrames) {
                current = next
                incoming = null
            }
        }
        if (!requestedSettings.enabled && !current.settings.enabled && incoming == null && pending == null)
            beginLimiterRelease()

        // A non-finite input is sanitized before entering state. The validated double-precision
        // coefficients are stable; this final containment prevents an arithmetic failure from
        // escaping to PCM conversion without allowing NaN to poison the limiter's future gain.
        if (!outLeft.isFinite()) outLeft = 0.0
        if (!outRight.isFinite()) outRight = 0.0
        val peak = max(abs(outLeft), abs(outRight))
        if (limiterActive) {
            val required = if (peak > limiterCeiling) limiterCeiling / peak else 1.0
            limiterGain = if (required < limiterGain) required
                else min(required, limiterGain + limiterRecovery * (1.0 - limiterGain))
        } else if (limiterReleaseRemaining > 0) {
            limiterGain += (1.0 - limiterGain) / limiterReleaseRemaining
            limiterReleaseRemaining--
            if (limiterReleaseRemaining == 0) limiterGain = 1.0
        }
        if (limiterGain < 1.0 && peak > 0.0) limitedFrames++
        outputLeft = outLeft * limiterGain
        outputRight = if (channelCount == 1) outputLeft else outRight * limiterGain
        lastPeak = max(abs(outputLeft), abs(outputRight))
        processedFrames++
    }

    private fun beginLimiterRelease() {
        if (limiterActive) {
            limiterActive = false
            limiterReleaseRemaining = if (limiterGain < 1.0) rampFrames else 0
        }
    }

    private fun recovery(milliseconds: Int) = 1.0 - exp(-1.0 / (sampleRate * (milliseconds / 1000.0)))

    private class SignalChain(settings: AudioDspSettings, sampleRate: Int, private val channels: Int) {
        var settings: AudioDspSettings = settings; private set
        private val preamp = if (settings.enabled) gain(settings.preampDb) else 1.0
        private val leftGain = if (settings.enabled && channels == 2 && settings.balance > 0.0) 1.0 - settings.balance else 1.0
        private val rightGain = if (settings.enabled && channels == 2 && settings.balance < 0.0) 1.0 + settings.balance else 1.0
        private val coefficients = arrayOfNulls<Biquad>(5)
        private val state1 = DoubleArray(5 * channels)
        private val state2 = DoubleArray(5 * channels)
        var skippedBandMask: Int = 0; private set
        var left = 0.0; private set
        var right = 0.0; private set

        init {
            if (settings.enabled) for (index in 0 until 5) {
                val band = settings.band(index)
                if (band.enabled && band.gainDb != 0.0) {
                    coefficients[index] = Biquad.peaking(band, sampleRate)
                    if (coefficients[index] == null) skippedBandMask = skippedBandMask or (1 shl index)
                }
            }
        }

        /** Limiter-only and latent-control edits must retain the live filter state. */
        fun sameSignalAs(next: AudioDspSettings): Boolean {
            if (settings.enabled != next.enabled) return false
            if (!settings.enabled) return true
            if (settings.preampDb != next.preampDb) return false
            if (channels == 2 && (settings.mono != next.mono || settings.balance != next.balance)) return false
            for (index in 0 until 5) {
                val before = settings.band(index)
                val after = next.band(index)
                val beforeActive = before.enabled && before.gainDb != 0.0
                val afterActive = after.enabled && after.gainDb != 0.0
                if (beforeActive != afterActive || (beforeActive && before != after)) return false
            }
            return true
        }

        fun adoptMetadata(next: AudioDspSettings) { settings = next }

        fun process(inputLeft: Double, inputRight: Double) {
            if (!settings.enabled) {
                left = inputLeft; right = inputRight
                return
            }
            left = channel(inputLeft * preamp, 0)
            right = if (channels == 1) left else channel(inputRight * preamp, 1)
            if (settings.mono && channels == 2) {
                val mean = left * 0.5 + right * 0.5
                left = mean; right = mean
            }
            left *= leftGain
            right *= rightGain
        }

        private fun channel(input: Double, channel: Int): Double {
            var value = input
            var band = 0
            while (band < 5) {
                val coefficients = coefficients[band]
                if (coefficients != null) {
                    val index = band * channels + channel
                    val output = coefficients.b0 * value + state1[index]
                    val next1 = coefficients.b1 * value - coefficients.a1 * output + state2[index]
                    val next2 = coefficients.b2 * value - coefficients.a2 * output
                    // Flush inaudible denormal state, far below one PCM16 quantization step.
                    state1[index] = if (abs(next1) < 1e-30) 0.0 else next1
                    state2[index] = if (abs(next2) < 1e-30) 0.0 else next2
                    value = output
                }
                band++
            }
            return value
        }
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        companion object {
            /** Peaking coefficients: https://www.w3.org/TR/audio-eq-cookbook/#formulae */
            fun peaking(band: AudioEqBand, sampleRate: Int): Biquad? {
                if (band.frequencyHz >= sampleRate * 0.5) return null
                val omega = 2.0 * PI * band.frequencyHz / sampleRate
                val amplitude = 10.0.pow(band.gainDb / 40.0)
                val alpha = sin(omega) / (2.0 * band.q)
                val a0 = 1.0 + alpha / amplitude
                val a1 = -2.0 * cos(omega) / a0
                val a2 = (1.0 - alpha / amplitude) / a0
                // Strict second-order Jury conditions also reject a numerically degenerate
                // pole on the unit circle, possible extremely close to a format's Nyquist.
                if (abs(a2) >= 1.0 || 1.0 + a1 + a2 <= 0.0 || 1.0 - a1 + a2 <= 0.0) return null
                return Biquad((1.0 + alpha * amplitude) / a0, -2.0 * cos(omega) / a0,
                    (1.0 - alpha * amplitude) / a0, a1, a2)
            }
        }
    }

    companion object {
        private fun gain(decibels: Double): Double = 10.0.pow(decibels / 20.0)
        private fun pcmCeiling(decibels: Double): Double = min(32767.0, floor(gain(decibels) * 32768.0)) / 32768.0
    }
}
