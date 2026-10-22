package fr.soustitresvoix.core

import java.util.Collections

/** Availability of a capability, independent of whether it was requested or used. */
enum class CapabilityStatus { SUPPORTED, CONDITIONAL, UNAVAILABLE, UNKNOWN }

/** Where a fact or capability assessment came from, not a confidence score. */
enum class DiagnosticSource {
    USER_PREFERENCE,
    PLAYER_CONFIGURATION,
    PLAYER_STATE,
    DECODER_EVENT,
    PLATFORM_DECLARATION,
    MEDIA_FORMAT,
    APPLICATION_POLICY,
    AUDIO_PROCESSOR,
    NOT_EXPOSED
}

/** Platform-free values. Their meaning and display labels belong to the adapter. */
sealed interface DiagnosticValue {
    data class Flag(val value: Boolean) : DiagnosticValue
    data class Count(val value: Long) : DiagnosticValue
    data class Text(val value: String) : DiagnosticValue
    data class Decimal(val value: Double) : DiagnosticValue {
        init { require(value.isFinite()) { "Diagnostic decimals must be finite" } }
    }
}

/**
 * A fact establishes only what its source can report. For example, a cumulative
 * sample count does not prove that attenuation is active at the current instant.
 */
data class DiagnosticFact(
    val value: DiagnosticValue,
    val source: DiagnosticSource,
    val detail: String? = null
)

/**
 * Three separate stages of evidence. A missing fact means there is no report for
 * that stage; it never means false, disabled or unsupported. Neither the status
 * nor a requested/configured value is promoted to an observed result.
 *
 * [id] is a stable adapter key. [reason] and fact details are supplied by the
 * adapter; this model does not format, infer or change playback behaviour.
 */
data class CapabilityDiagnostic(
    val id: String,
    val status: CapabilityStatus,
    val assessmentSource: DiagnosticSource,
    val reason: String,
    val requested: DiagnosticFact? = null,
    val configured: DiagnosticFact? = null,
    val observed: DiagnosticFact? = null
)

/** An immutable point-in-time report; it makes no claim to remain current. */
class PlaybackDiagnosticSnapshot(
    val generation: Long,
    val closed: Boolean,
    capabilities: List<CapabilityDiagnostic>
) {
    val capabilities: List<CapabilityDiagnostic> =
        Collections.unmodifiableList(ArrayList(capabilities))
}

/**
 * Session-thread storage only: no polling, callbacks or playback decisions.
 * The owner invalidates reports when their media/context changes and republishes
 * facts it can establish for the new generation.
 *
 * An asynchronous producer must retain its ORIGINAL generation ticket. Reading
 * the current generation only when an old callback arrives would incorrectly
 * relabel obsolete evidence as current. Closing is permanent.
 */
class PlaybackDiagnosticStore {
    var generation: Long = 0L
        private set
    private var closed = false
    private var capabilities: List<CapabilityDiagnostic> = emptyList()

    fun invalidate(): Long {
        if (!closed) {
            generation++
            capabilities = emptyList()
        }
        return generation
    }

    /** Reject stale/closed reports before examining their contents. */
    fun publish(generation: Long, capabilities: List<CapabilityDiagnostic>): Boolean {
        if (closed || generation != this.generation) return false
        val copy = capabilities.toList()
        require(copy.map { it.id }.toSet().size == copy.size) {
            "A diagnostic report must not contain duplicate capability IDs"
        }
        this.capabilities = copy
        return true
    }

    fun snapshot(): PlaybackDiagnosticSnapshot =
        PlaybackDiagnosticSnapshot(generation, closed, capabilities)

    fun close() {
        if (closed) return
        generation++
        capabilities = emptyList()
        closed = true
    }
}
