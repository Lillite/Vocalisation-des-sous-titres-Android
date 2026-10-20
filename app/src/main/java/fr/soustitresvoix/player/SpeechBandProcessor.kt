package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.exp

/** Experimental broad speech-band attenuation, not source separation. PCM16 only.
 * Applied to the MOVIE, never the narrated voice. No samples or timestamps removed. */
@UnstableApi
class SpeechBandProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
    @Volatile var speaking = false
    @Volatile var amount = .40f
    var processedSamples = 0L; private set
    private var low = FloatArray(0)
    private var high = FloatArray(0)
    private var aLow = 0f; private var aHigh = 0f
    private var gain = 0f
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        // configure() is only pending: the caller may still drain the old format
        // until flush(). In particular, do not resize its channel state here.
        return input
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 polls processors with the shared EMPTY_BUFFER, including before
        // the first audio frame. replaceOutputBuffer(0) can return that same
        // sentinel: copying it onto itself would throw even though it is empty.
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val frameBytes = channels * 2
        // Leave an incomplete frame with the caller; never drop its remaining bytes.
        val bytes = inputBuffer.remaining() / frameBytes * frameBytes
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val target=if (enabled && speaking) amount.coerceIn(0f,.65f) else 0f
        if (target == 0f && gain <= 0.000001f) {
            // The remaining correction is below 0.066 of one PCM16 step, even at
            // full scale. Finish the release before bypassing; never cut its ramp.
            gain = 0f; low.fill(0f); high.fill(0f)
            val limit = inputBuffer.limit()
            try {
                inputBuffer.limit(inputBuffer.position() + bytes)
                out.put(inputBuffer)
            } finally { inputBuffer.limit(limit) }
        } else {
            val end = inputBuffer.position() + bytes
            while (inputBuffer.position() < end) {
                gain += .001f*(target-gain)
                for (channel in 0 until channels) {
                    val sample=inputBuffer.short.toFloat()
                    low[channel]+=aLow*(sample-low[channel]); high[channel]+=aHigh*(sample-high[channel])
                    val filtered=sample-gain*(high[channel]-low[channel])
                    out.putShort(filtered.toInt().coerceIn(-32768,32767).toShort())
                }
            }
        }
        processedSamples += bytes / 2
        out.flip()
    }
    override fun onFlush() {
        val format = inputAudioFormat
        low=FloatArray(format.channelCount.coerceAtLeast(0)); high=FloatArray(low.size)
        if (format.sampleRate > 0) {
            aLow=(1-exp(-2*Math.PI*250/format.sampleRate)).toFloat()
            aHigh=(1-exp(-2*Math.PI*3200/format.sampleRate)).toFloat()
        }
        gain=0f
    }
}
