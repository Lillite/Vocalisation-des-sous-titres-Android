package fr.soustitresvoix.core

/** Film settings only. Voice gain, voice timing and system volume are separate. */
data class AudioSettings(
    val movieVolume: Float = 1f,
    val duckVolume: Float = .25f,
    val attackMs: Int = 160,
    val releaseMs: Int = 500,
    val holdMs: Int = 350,
    val spectralDucking: Boolean = false,
    val dsp: AudioDspSettings = AudioDspSettings()
)

/** Each group is validated atomically; a rejected request is never clamped or partly saved. */
sealed interface AudioCommand {
    /** duckVolume is a multiplier of movieVolume, not an independent absolute level. */
    data class SetLevels(val movieVolume: Float, val duckVolume: Float) : AudioCommand
    /** These durations apply to the next transition; an existing transition retains its timing. */
    data class SetDuckingTiming(val attackMs: Int, val releaseMs: Int, val holdMs: Int) : AudioCommand
    data class SetSpectralDucking(val enabled: Boolean) : AudioCommand
    /** Full immutable DSP group; fields are validated together before any persistence or submission. */
    data class SetDsp(val settings: AudioDspSettings) : AudioCommand
}

/** Retain this generation from the original producer, including delayed dialogs and gestures. */
data class AudioCommandRequest(val generation: Long, val command: AudioCommand)

data class AudioCommandContext(
    val generation: Long,
    val closed: Boolean,
    val spectralAvailability: CapabilityStatus,
    val requested: AudioSettings,
    val dspAvailability: CapabilityStatus = CapabilityStatus.UNKNOWN
)

enum class AudioCommandRejection {
    CLOSED, STALE_MEDIA, NON_FINITE_VALUE, OUT_OF_RANGE,
    CAPABILITY_UNAVAILABLE, CAPABILITY_UNKNOWN,
    DSP_CAPABILITY_UNAVAILABLE, DSP_CAPABILITY_UNKNOWN
}

sealed interface AudioCommandDecision {
    data class Rejected(val generation: Long, val reason: AudioCommandRejection) : AudioCommandDecision
    /** Acceptance describes the requested configuration, never a measured acoustic result. */
    data class Accepted(val generation: Long, val requested: AudioSettings, val changed: Boolean) : AudioCommandDecision
}

/** Pure policy. Only the session adapter may persist settings or operate its existing audio components. */
object AudioCommandPolicy {
    fun validate(request: AudioCommandRequest, context: AudioCommandContext): AudioCommandDecision {
        fun reject(reason: AudioCommandRejection) = AudioCommandDecision.Rejected(request.generation, reason)
        if (context.closed) return reject(AudioCommandRejection.CLOSED)
        if (request.generation != context.generation) return reject(AudioCommandRejection.STALE_MEDIA)
        val requested = when (val command = request.command) {
            is AudioCommand.SetLevels -> {
                if (!command.movieVolume.isFinite() || !command.duckVolume.isFinite())
                    return reject(AudioCommandRejection.NON_FINITE_VALUE)
                if (command.movieVolume !in 0f..1f || command.duckVolume !in 0f..1f)
                    return reject(AudioCommandRejection.OUT_OF_RANGE)
                context.requested.copy(movieVolume = command.movieVolume, duckVolume = command.duckVolume)
            }
            is AudioCommand.SetDuckingTiming -> {
                if (command.attackMs !in 0..2000 || command.releaseMs !in 0..2000 || command.holdMs !in 0..2000)
                    return reject(AudioCommandRejection.OUT_OF_RANGE)
                context.requested.copy(attackMs = command.attackMs, releaseMs = command.releaseMs, holdMs = command.holdMs)
            }
            is AudioCommand.SetSpectralDucking -> {
                // Disabling remains possible even if the component cannot declare support.
                // An unchanged request does not (re)enable anything or assert availability.
                if (command.enabled && !context.requested.spectralDucking) when (context.spectralAvailability) {
                    CapabilityStatus.UNAVAILABLE -> return reject(AudioCommandRejection.CAPABILITY_UNAVAILABLE)
                    CapabilityStatus.UNKNOWN -> return reject(AudioCommandRejection.CAPABILITY_UNKNOWN)
                    CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL -> Unit
                }
                context.requested.copy(spectralDucking = command.enabled)
            }
            is AudioCommand.SetDsp -> {
                when (AudioDspPolicy.validate(command.settings)) {
                    AudioDspProblem.NON_FINITE -> return reject(AudioCommandRejection.NON_FINITE_VALUE)
                    AudioDspProblem.OUT_OF_RANGE -> return reject(AudioCommandRejection.OUT_OF_RANGE)
                    null -> Unit
                }
                val dsp = AudioDspPolicy.canonicalize(command.settings)
                // Disabled configurations may be prepared even without an installed processor.
                // Any changed enabled configuration requires a declared compatible implementation.
                if (dsp.enabled && dsp != context.requested.dsp) when (context.dspAvailability) {
                    CapabilityStatus.UNAVAILABLE -> return reject(AudioCommandRejection.DSP_CAPABILITY_UNAVAILABLE)
                    CapabilityStatus.UNKNOWN -> return reject(AudioCommandRejection.DSP_CAPABILITY_UNKNOWN)
                    CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL -> Unit
                }
                context.requested.copy(dsp = dsp)
            }
        }
        // Numerical equality deliberately treats the two signed zeros as the same level.
        val before = context.requested
        val changed = requested.movieVolume != before.movieVolume || requested.duckVolume != before.duckVolume ||
            requested.attackMs != before.attackMs || requested.releaseMs != before.releaseMs ||
            requested.holdMs != before.holdMs || requested.spectralDucking != before.spectralDucking ||
            requested.dsp != AudioDspPolicy.canonicalize(before.dsp)
        return AudioCommandDecision.Accepted(request.generation, requested, changed)
    }
}

/**
 * Read-only facts at consultation time. The configured volume may be between the
 * requested targets during a fade. The speaking flag belongs to narration.
 * processedSamples counts channel samples for this session, including bypass;
 * none of these facts proves audible attenuation. After closure observations are null.
 */
data class AudioCommandSnapshot(
    val context: AudioCommandContext,
    val configuredVolume: Float?,
    val spectralEnabled: Boolean?,
    val speaking: Boolean?,
    val processedSamples: Long?,
    val dsp: AudioDspSnapshot? = null
)

/** Session-thread contract; opening another medium invalidates tickets, not persistent preferences. */
interface AudioCommandPort {
    fun snapshot(): AudioCommandSnapshot
    fun submit(request: AudioCommandRequest): AudioCommandDecision
}
