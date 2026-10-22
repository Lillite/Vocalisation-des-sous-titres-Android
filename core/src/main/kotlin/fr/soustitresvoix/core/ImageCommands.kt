package fr.soustitresvoix.core

/** The existing image corrections; image brightness is not screen brightness. */
sealed interface ImageCommand {
    /** Both values are validated together, without clamping or partial changes. */
    data class SetAdjustments(val contrast: Float, val imageBrightness: Float) : ImageCommand
    data class SetOriginalComparison(val enabled: Boolean) : ImageCommand
}

/** Requested settings, which can be retained while their corrections are masked. */
data class ImageSettings(
    val contrast: Float = 0f,
    val imageBrightness: Float = 0f,
    val originalComparison: Boolean = false
)

/** Keep the generation captured by the producer, including for delayed requests. */
data class ImageCommandRequest(val generation: Long, val command: ImageCommand)

/**
 * Current session-owned policy inputs. The adapter supplies these values; callers
 * cannot use a request to lift a protection. A false [hdrProtected] means that the
 * existing HDR guard is not active, not that an unknown format was proved SDR.
 */
data class ImageCommandContext(
    val generation: Long,
    val closed: Boolean,
    val hdrProtected: Boolean,
    val availability: CapabilityStatus,
    val requested: ImageSettings
)

enum class ImageCommandRejection {
    CLOSED,
    STALE_MEDIA,
    NON_FINITE_VALUE,
    OUT_OF_RANGE,
    CAPABILITY_UNAVAILABLE,
    CAPABILITY_UNKNOWN,
    HDR_PROTECTED
}

/** Policy at acceptance time, not a promise of submission or rendered pixels. */
enum class ImageCommandDisposition {
    ELIGIBLE_FOR_SUBMISSION,
    MASKED_BY_ORIGINAL_COMPARISON,
    MASKED_BY_HDR
}

sealed interface ImageCommandDecision {
    /** The generation always identifies the original request, even when stale. */
    data class Rejected(val generation: Long, val reason: ImageCommandRejection) : ImageCommandDecision

    /** Acceptance changes no state by itself and is not a rendering acknowledgement. */
    data class Accepted(
        val generation: Long,
        val requested: ImageSettings,
        val disposition: ImageCommandDisposition,
        val changed: Boolean
    ) : ImageCommandDecision
}

/**
 * Pure validation only. VisualTools remains responsible for the existing effect
 * plan, coalescing and submission. Its HDR/bypass guard must be checked again when
 * building that plan, because conditions can change after acceptance.
 */
object ImageCommandPolicy {
    fun validate(request: ImageCommandRequest, context: ImageCommandContext): ImageCommandDecision {
        fun reject(reason: ImageCommandRejection) = ImageCommandDecision.Rejected(request.generation, reason)
        if (context.closed) return reject(ImageCommandRejection.CLOSED)
        if (request.generation != context.generation) return reject(ImageCommandRejection.STALE_MEDIA)

        val command = request.command
        if (command is ImageCommand.SetAdjustments) {
            if (!command.contrast.isFinite() || !command.imageBrightness.isFinite())
                return reject(ImageCommandRejection.NON_FINITE_VALUE)
            if (command.contrast !in -0.3f..0.3f || command.imageBrightness !in -0.2f..0.2f)
                return reject(ImageCommandRejection.OUT_OF_RANGE)
        }
        when (context.availability) {
            CapabilityStatus.UNAVAILABLE -> return reject(ImageCommandRejection.CAPABILITY_UNAVAILABLE)
            CapabilityStatus.UNKNOWN -> return reject(ImageCommandRejection.CAPABILITY_UNKNOWN)
            CapabilityStatus.SUPPORTED, CapabilityStatus.CONDITIONAL -> Unit
        }
        if (command is ImageCommand.SetAdjustments && context.hdrProtected)
            return reject(ImageCommandRejection.HDR_PROTECTED)

        val requested = when (command) {
            is ImageCommand.SetAdjustments -> context.requested.copy(
                contrast = command.contrast, imageBrightness = command.imageBrightness)
            is ImageCommand.SetOriginalComparison -> context.requested.copy(originalComparison = command.enabled)
        }
        val disposition = when {
            context.hdrProtected -> ImageCommandDisposition.MASKED_BY_HDR
            requested.originalComparison -> ImageCommandDisposition.MASKED_BY_ORIGINAL_COMPARISON
            else -> ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION
        }
        // Compare numerical settings, so signed zero does not restart an effect
        // update or interrupt a benchmark for an unchanged correction.
        val changed = requested.contrast != context.requested.contrast ||
            requested.imageBrightness != context.requested.imageBrightness ||
            requested.originalComparison != context.requested.originalComparison
        return ImageCommandDecision.Accepted(request.generation, requested, disposition, changed)
    }
}

/**
 * Actual values from a successfully submitted plan, never a pixel measurement.
 * The adapter increments [sequence] only after its existing setEffects call
 * succeeds. Acceptance, coalescing or an unchanged plan do not create this fact.
 */
data class SubmittedImageAdjustments(
    val generation: Long,
    val sequence: Long,
    val contrast: Float,
    val imageBrightness: Float
)

/**
 * Immutable report, with no secondary state or scheduler. The session owner
 * invalidates [lastSubmitted] on media replacement (even for the same URI) and
 * closure; an old plan must not be relabelled with a new generation.
 */
data class ImageCommandSnapshot(
    val context: ImageCommandContext,
    val lastSubmitted: SubmittedImageAdjustments?
)

/** Session-owned command boundary; a snapshot must never submit a command. */
interface ImageCommandPort {
    fun snapshot(): ImageCommandSnapshot
    fun submit(request: ImageCommandRequest): ImageCommandDecision
}
