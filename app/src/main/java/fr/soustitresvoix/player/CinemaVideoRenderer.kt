package fr.soustitresvoix.player

import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoSink

/** Keeps effect changes from waiting for frames that cannot drain while paused. */
@UnstableApi
internal class CinemaVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
    private var inputSink: VideoSink? = null
    private var appliedEffects: List<Effect>? = null

    override fun changeVideoSinkInputStream(
        videoSink: VideoSink, inputType: Int, format: Format,
        firstFrameReleaseInstruction: Int
    ) {
        // This public VideoSink is supplied through Media3's supported subclass
        // hook. The renderer retains it across resets and owns its release.
        inputSink = videoSink
        super.changeVideoSinkInputStream(videoSink, inputType, format, firstFrameReleaseInstruction)
    }

    override fun setVideoEffects(effects: List<Effect>) {
        // REDRAW is a command, not an effect list to register or deduplicate.
        if (effects == VideoFrameProcessor.REDRAW) {
            super.setVideoEffects(effects)
            return
        }
        if (effects == appliedEffects) return

        val sink = inputSink
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

    override fun onRelease() {
        try {
            super.onRelease()
        } finally {
            inputSink = null
        }
    }
}
