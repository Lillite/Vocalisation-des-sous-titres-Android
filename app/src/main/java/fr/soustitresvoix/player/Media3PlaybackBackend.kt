package fr.soustitresvoix.player

import android.content.Context
import android.os.Handler
import android.os.Build
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import fr.soustitresvoix.core.CapabilityDiagnostic
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.DiagnosticSource
import java.util.concurrent.ConcurrentHashMap

/** Owns the historical film processor and separate expert DSP, once per session. */
@UnstableApi
class Media3MovieAudio : MovieAudio {
    private val processor = SpeechBandProcessor()
    private val expertProcessor = ExpertAudioProcessor()
    override val dsp: MovieDsp get() = expertProcessor

    override var enabled: Boolean
        get() = processor.enabled
        set(value) { processor.enabled = value }
    override var speaking: Boolean
        get() = processor.speaking
        set(value) { processor.speaking = value }
    override val processedSamples: Long get() = processor.processedSamples

    override fun createSink(context: Context): AudioSink =
        DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(processor, expertProcessor)).build()

    override fun diagnosticCapability() = CapabilityDiagnostic(
        "audio.spectral_ducking", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
        "Atténuation expérimentale du film en PCM16 ; le compteur inclut aussi le passage sans atténuation. Ce n’est pas une séparation des sources.")
}

/** Retains the existing renderer and its paused-effect handling unchanged. */
@UnstableApi
class CinemaVideoRendererFactory : VideoRendererFactory {
    override fun create(builder: MediaCodecVideoRenderer.Builder): Renderer =
        CinemaVideoRenderer(builder)
}

/**
 * Default backend, owned by one session. Construction preserves the previous
 * renderer, sink and codec selection configuration. Session listeners, audio
 * focus, track selection and playback decisions remain with PlaybackSession.
 */
@UnstableApi
class Media3PlaybackBackend(
    context: Context,
    prefs: PlayerPreferences,
    movieAudio: MovieAudio,
    videoRendererFactory: VideoRendererFactory
) : PlaybackBackend {
    // Filled only during the existing codec query, never by a diagnostic-triggered
    // inventory. Selection still returns exactly the same list and ordering.
    private val codecDeclarations = ConcurrentHashMap<String, DecoderClassification>()
    // Internal access preserves instrumentation that requires ExoPlayer's real
    // playback-thread message barrier; it is not part of the replacement contract.
    internal val exoPlayer: ExoPlayer = ExoPlayer.Builder(context,
        object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>
            ) {
                // This factory uses the default extension mode (OFF). The builder's
                // AV1 parsing and frame scheduling defaults match Media3 1.11.1's
                // DefaultRenderersFactory; retain its configured codec components.
                out.add(videoRendererFactory.create(MediaCodecVideoRenderer.Builder(context)
                    .setCodecAdapterFactory(getCodecAdapterFactory())
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)))
            }

            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink = movieAudio.createSink(context)
        }.setMediaCodecSelector { mime, secure, tunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            // Before API 29 these Media3 flags are heuristic, not platform declarations.
            if (Build.VERSION.SDK_INT >= 29) infos.forEach { info ->
                codecDeclarations[info.name] = DecoderClassification(info.hardwareAccelerated, info.softwareOnly)
            }
            if (prefs.bool("preferSoftware")) infos.sortedByDescending { it.softwareOnly } else infos
        }.setEnableDecoderFallback(true)).setUsePlatformDiagnostics(false).build()

    override val player: Player get() = exoPlayer

    override val video: VideoOutput = object : VideoOutput {
        override val format: Format? get() = exoPlayer.videoFormat
        override var frameRateStrategy: Int
            get() = exoPlayer.videoChangeFrameRateStrategy
            set(value) { exoPlayer.setVideoChangeFrameRateStrategy(value) }

        override fun setEffects(effects: List<Effect>) = exoPlayer.setVideoEffects(effects)
    }

    override val videoDecoderCounters: DecoderCounters? get() = exoPlayer.videoDecoderCounters

    override fun setMediaSource(source: MediaSource, startPositionMs: Long) =
        exoPlayer.setMediaSource(source, startPositionMs)

    override fun addAnalyticsListener(listener: AnalyticsListener) =
        exoPlayer.addAnalyticsListener(listener)

    override fun diagnosticCapabilities(): List<CapabilityDiagnostic> = listOf(
        CapabilityDiagnostic("video.decoder", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
            "Media3 privilégie les décodeurs logiciels si demandé, avec repli conservé. Un décodeur compatible doit être disponible ; la préférence seule ne prouve pas le choix effectif."),
        CapabilityDiagnostic("video.effects", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
            "Les corrections d’image sont soumises aux protections HDR et au mode comparaison. Une configuration transmise ne mesure pas les pixels affichés."),
        CapabilityDiagnostic("video.scaling", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
            "Le redimensionnement détaillé nécessite une surface mesurée et les corrections d’image autorisées."),
        CapabilityDiagnostic("video.frame_rate", CapabilityStatus.CONDITIONAL, DiagnosticSource.APPLICATION_POLICY,
            "La stratégie de fréquence est transmise à Media3. Le changement effectif de fréquence de l’écran n’est pas mesuré ici.")
    )

    override fun decoderClassification(name: String): DecoderClassification? = codecDeclarations[name]

    override fun close() = exoPlayer.release()
}
