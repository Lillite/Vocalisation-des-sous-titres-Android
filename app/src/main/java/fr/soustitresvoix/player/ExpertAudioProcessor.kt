package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.AudioDspEngine
import fr.soustitresvoix.core.AudioDspObservation
import fr.soustitresvoix.core.AudioDspPolicy
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioDspSnapshot
import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.DiagnosticSource
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Film-only PCM16 mono/stereo adapter. The processor remains installed when its
 * controls are disabled, so a setting change never needs a sink flush/rebuild.
 * All DSP state belongs to the playback thread; submissions and observations
 * cross the boundary only as immutable volatile publications.
 */
@UnstableApi
class ExpertAudioProcessor : BaseAudioProcessor(), MovieDsp {
    private data class Submission(val revision: Long, val settings: AudioDspSettings)

    @Volatile private var submission = Submission(0, AudioDspSettings())
    @Volatile private var observation = AudioDspObservation()
    private var engine: AudioDspEngine? = null
    private var consumed: Submission? = null
    private var clippedSamples = 0L

    override val capability = CapabilityDiagnostic(
        "audio.expert_dsp", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
        "Traitements du film en PCM16 mono ou stéréo. Les autres formats, le multicanal, le passthrough et l’offload ne sont pas traités ; une préférence acceptée ne prouve pas qu’un échantillon a traversé ce processeur.")

    override val snapshot: AudioDspSnapshot
        get() {
            // Read the observation first: a later submission may be pending, but
            // an observed revision must never be newer than the returned request.
            val observed = observation
            val submitted = submission
            return AudioDspSnapshot(submitted.revision, submitted.settings, observed)
        }

    @Synchronized
    override fun submit(settings: AudioDspSettings) {
        require(AudioDspPolicy.validate(settings) == null) { "Invalid audio DSP configuration" }
        val canonical = AudioDspPolicy.canonicalize(settings)
        val before = submission
        if (canonical != before.settings) submission = Submission(Math.incrementExact(before.revision), canonical)
    }

    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (compatible(input)) input else AudioProcessor.AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        val supported = compatible(format)
        engine = if (supported) AudioDspEngine(format.sampleRate, format.channelCount, submission.settings) else null
        consumed = null
        clippedSamples = 0
        observation = AudioDspObservation(
            sampleRate = format.sampleRate.takeIf { it > 0 },
            channelCount = format.channelCount.takeIf { it > 0 },
            compatible = if (format == AudioProcessor.AudioFormat.NOT_SET) null else supported)
    }

    override fun onReset() {
        // BaseAudioProcessor.reset() in Media3 1.11.1 does not call onFlush().
        // Keep the requested preference while dropping every stream observation.
        engine = null
        consumed = null
        clippedSamples = 0
        observation = AudioDspObservation()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 can poll a processor with the shared empty sentinel, including
        // before any format exists. Never copy that sentinel onto itself.
        if (!inputBuffer.hasRemaining()) return
        val processor = engine ?: return
        val channels = inputAudioFormat.channelCount
        val frameBytes = channels * 2
        val bytes = inputBuffer.remaining() / frameBytes * frameBytes
        // Retain an incomplete frame with its caller; consume no configuration
        // or samples until a full frame can actually be processed.
        if (bytes == 0) return
        val blockSubmission = submission
        if (consumed?.revision != blockSubmission.revision) processor.update(blockSubmission.settings)
        consumed = blockSubmission
        val output = replaceOutputBuffer(bytes)
        val end = inputBuffer.position() + bytes
        val exactBypass = processor.isBypass
        var blockPeak = 0.0
        while (inputBuffer.position() < end) {
            val left = inputBuffer.short
            val right = if (channels == 2) inputBuffer.short else left
            processor.processFrame(left / PCM_SCALE, right / PCM_SCALE)
            // A stable bypass is a direct integer copy, with no requantization.
            val resultLeft = if (exactBypass) left else quantize(processor.outputLeft)
            output.putShort(resultLeft)
            blockPeak = max(blockPeak, abs(resultLeft.toInt()) / PCM_SCALE)
            if (channels == 2) {
                val resultRight = if (exactBypass) right else quantize(processor.outputRight)
                output.putShort(resultRight)
                blockPeak = max(blockPeak, abs(resultRight.toInt()) / PCM_SCALE)
            }
        }
        output.flip()
        observation = AudioDspObservation(
            sampleRate = inputAudioFormat.sampleRate,
            channelCount = channels,
            compatible = true,
            consumedRevision = blockSubmission.revision,
            consumedSettings = blockSubmission.settings,
            appliedSettings = processor.appliedSettings,
            transitioning = processor.isTransitioning,
            skippedBandMask = processor.skippedBandMask,
            processedFrames = processor.processedFrames,
            limitedFrames = processor.limitedFrames,
            clippedSamples = clippedSamples,
            outputPeak = blockPeak)
    }

    private fun quantize(value: Double): Short {
        val scaled = value * PCM_SCALE
        if (!scaled.isFinite()) {
            clippedSamples++
            return 0
        }
        // Count saturation of the actual rounded PCM value. A floating-point
        // result one ULP above 32767 can still round to that exact sample and
        // must not be reported as clipping. roundToInt also safely saturates
        // finite values outside the Int range before the final Short bound.
        val rounded = scaled.roundToInt()
        if (rounded < Short.MIN_VALUE || rounded > Short.MAX_VALUE) clippedSamples++
        return rounded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun compatible(format: AudioProcessor.AudioFormat): Boolean =
        format.encoding == C.ENCODING_PCM_16BIT && format.sampleRate > 0 && format.channelCount in 1..2

    private companion object { const val PCM_SCALE = 32768.0 }
}
