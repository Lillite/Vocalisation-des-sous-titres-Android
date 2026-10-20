package fr.soustitresvoix.player

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl

/** Keeps effect changes from waiting for frames that cannot drain while paused. */
@UnstableApi
internal class CinemaVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    private var playbackVideoGraph: PlaybackVideoGraphWrapper? = null
    private var appliedEffects: List<Effect>? = null

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper =
        // Preserve Media3's clock, playlist mode, frame dropping and cache defaults.
        super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).also {
            playbackVideoGraph = it
        }

    override fun setVideoEffects(effects: List<Effect>) {
        // REDRAW is a command, not an effect list to register or deduplicate.
        if (effects == VideoFrameProcessor.REDRAW) {
            super.setVideoEffects(effects)
            return
        }
        if (effects == appliedEffects) return

        val sink = playbackVideoGraph?.getSink(0)
        if (sink?.isInitialized == true) {
            // Media3 1.11.1 registers a new input stream for a changed effect list.
            // Flush its queued output before registering: while paused those frames
            // otherwise prevent the previous stream from completing. This is only
            // the video pipeline; it does not seek or change playback intent. A fresh
            // decoded frame may render even when the player is paused.
            sink.flush(true)
            sink.allowReleaseFirstFrameBeforeStarted()
        }
        val immutableEffects = effects.toList()
        super.setVideoEffects(immutableEffects)
        appliedEffects = immutableEffects
    }
}
