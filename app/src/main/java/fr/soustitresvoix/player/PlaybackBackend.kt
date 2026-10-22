package fr.soustitresvoix.player

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.DiagnosticSource

/** Platform declarations for a named codec; never a CPU/GPU utilization measure. */
data class DecoderClassification(val hardwareAccelerated: Boolean, val softwareOnly: Boolean)

/**
 * Session-owned playback implementation. The session closes it after stopping its
 * callbacks; views borrow [player] and must not release it independently.
 *
 * This first boundary deliberately retains Media3's [Player], source and analytics
 * types. It isolates ExoPlayer construction and its extra operations, without
 * claiming that another engine can already implement a Media3-free contract.
 */
@UnstableApi
interface PlaybackBackend {
    val player: Player
    val video: VideoOutput
    val videoDecoderCounters: DecoderCounters?
    fun setMediaSource(source: MediaSource, startPositionMs: Long)
    fun addAnalyticsListener(listener: AnalyticsListener)
    /** Read-only declarations. An unreported capability remains unknown. */
    fun diagnosticCapabilities(): List<CapabilityDiagnostic> = emptyList()
    fun decoderClassification(name: String): DecoderClassification? = null
    fun close()
}

/** Video controls belonging to the same player and lifecycle as the backend. */
@UnstableApi
interface VideoOutput {
    val format: Format?
    var frameRateStrategy: Int
    fun setEffects(effects: List<Effect>)
}

/**
 * Film audio processing for one session. Controls and diagnostics must refer to
 * the processor installed in [createSink], never to a second processor instance.
 * The backend owns and releases the sink through its player.
 */
@UnstableApi
interface MovieAudio {
    var enabled: Boolean
    var speaking: Boolean
    val processedSamples: Long
    fun createSink(context: Context): AudioSink
    fun diagnosticCapability(): CapabilityDiagnostic = CapabilityDiagnostic(
        "audio.spectral_ducking", CapabilityStatus.UNKNOWN, DiagnosticSource.NOT_EXPOSED,
        "Ce composant ne déclare pas les conditions de son traitement audio.")
}

/** Creates a fresh renderer from the backend's configured Media3 builder. */
@UnstableApi
interface VideoRendererFactory {
    fun create(builder: MediaCodecVideoRenderer.Builder): Renderer
}
