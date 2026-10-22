package fr.soustitresvoix.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.NarrationController

/**
 * Composition root. Factories must create session-owned instances, never share a
 * player, processor, narration controller or speech service between sessions.
 * Construction must not call session callbacks synchronously. Replacing a component
 * changes its implementation, not the session's ordering, focus or resume policies.
 */
@UnstableApi
interface PlaybackComponents {
    fun createNarration(): NarrationController
    fun createMovieAudio(): MovieAudio
    fun createVideoRenderer(): VideoRendererFactory
    fun createPlayback(context: Context, preferences: PlayerPreferences,
        movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend
    fun createSubtitles(context: Context): SubtitleSource
    fun createSpeech(context: Context, preferences: PlayerPreferences,
        onAvailability: (Boolean, String) -> Unit,
        onDone: (String) -> Unit, onFailure: (String, String) -> Unit): SpeechService
}

/** Stateless factory; Media3 and the existing narration remain the only defaults. */
@UnstableApi
object DefaultPlaybackComponents : PlaybackComponents {
    override fun createNarration() = ExistingNarrationController()
    override fun createMovieAudio() = Media3MovieAudio()
    override fun createVideoRenderer() = CinemaVideoRendererFactory()
    override fun createPlayback(context: Context, preferences: PlayerPreferences,
        movieAudio: MovieAudio, videoRenderer: VideoRendererFactory) =
        Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer)
    override fun createSubtitles(context: Context) = Media3SubtitleSource(context)
    override fun createSpeech(context: Context, preferences: PlayerPreferences,
        onAvailability: (Boolean, String) -> Unit,
        onDone: (String) -> Unit, onFailure: (String, String) -> Unit) =
        AndroidSpeechService(context, preferences, onAvailability, onDone, onFailure)
}
