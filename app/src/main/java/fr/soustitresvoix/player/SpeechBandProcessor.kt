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
        low=FloatArray(input.channelCount); high=FloatArray(input.channelCount)
        aLow=(1-exp(-2*Math.PI*250/input.sampleRate)).toFloat()
        aHigh=(1-exp(-2*Math.PI*3200/input.sampleRate)).toFloat()
        return input
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val channels = inputAudioFormat.channelCount
        var channel=0
        val target=if (enabled && speaking) amount.coerceIn(0f,.65f) else 0f
        while (inputBuffer.remaining() >= 2) {
            val sample=inputBuffer.short.toFloat()
            low[channel]+=aLow*(sample-low[channel]); high[channel]+=aHigh*(sample-high[channel])
            if (channel == 0) gain += .001f*(target-gain)
            val filtered=sample-gain*(high[channel]-low[channel])
            out.putShort(filtered.toInt().coerceIn(-32768,32767).toShort())
            channel=(channel+1)%channels; processedSamples++
        }
        out.flip()
    }
    override fun onFlush() { low.fill(0f); high.fill(0f); gain=0f }
}
