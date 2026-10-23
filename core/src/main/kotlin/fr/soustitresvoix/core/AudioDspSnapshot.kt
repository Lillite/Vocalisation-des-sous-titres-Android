package fr.soustitresvoix.core

/** One immutable publication from the audio thread. Counters restart at flush/reset.
 * A compatible format alone is not proof that a frame reached the processor.
 * Output peak is the last complete PCM block, after quantization, not acoustic loudness.
 * Clipped samples count invalid values or rounded PCM values outside the Short range.
 */
data class AudioDspObservation(
    val sampleRate: Int? = null,
    val channelCount: Int? = null,
    val compatible: Boolean? = null,
    val consumedRevision: Long? = null,
    val consumedSettings: AudioDspSettings? = null,
    val appliedSettings: AudioDspSettings? = null,
    val transitioning: Boolean = false,
    val skippedBandMask: Int = 0,
    val processedFrames: Long = 0,
    val limitedFrames: Long = 0,
    val clippedSamples: Long = 0,
    val outputPeak: Double? = null
)

/** Submission is separate from consumption: a paused or bypassed sink may not consume it yet. */
data class AudioDspSnapshot(
    val submittedRevision: Long,
    val submittedSettings: AudioDspSettings,
    val observation: AudioDspObservation
)
