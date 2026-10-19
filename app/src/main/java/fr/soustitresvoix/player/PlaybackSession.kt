package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SubtitleText
import java.io.File

@UnstableApi
class PlaybackSession(
    private val context: Context,
    val prefs: PlayerPreferences,
    private val onChanged: () -> Unit,
    private val onMessage: (String, Boolean) -> Unit,
    private val onCaption: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    val machine = NarrationMachine()
    val player: ExoPlayer = ExoPlayer.Builder(context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
    private val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private var closed = false
    private var ducked = false
    private var fade: Runnable? = null
    private var lastCue: CueGroup? = null
    private var trackReset = false
    private var selectExternalWhenAvailable = false
    var media: SavedMedia? = null; private set
    private var focusHeld = false
    val speech = AndroidSpeech(context, prefs,
        onAvailability = { ready, msg ->
            if (!closed) { apply(machine.setSpeechReady(ready)); onMessage(msg, !ready); onChanged() }
        },
        onDone = { if (!closed) {
            apply(machine.speechFinished(it))
            if (player.playbackState == Player.STATE_ENDED && machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0) pause()
        } },
        onFailure = { id, msg -> if (!closed) apply(machine.speechFailed(id, msg)) })
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                pause(); onMessage("Lecture en pause : une autre application utilise le son.", false)
            }
        }, handler).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause(); onMessage("Lecture en pause : le périphérique audio a été déconnecté.", false)
            }
        }
    }
    init {
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                lastCue = cueGroup
                if (!trackReset) forwardCues(cueGroup)
            }
            override fun onPlaybackStateChanged(state: Int) {
                apply(machine.setMediaReady(state == Player.STATE_READY || state == Player.STATE_ENDED))
                if (state == Player.STATE_ENDED) {
                    // Expire the final caption so comfort mode can finish it.
                    apply(machine.cues(Long.MAX_VALUE, emptyList()))
                    onMessage("Fin de la vidéo.", false)
                    if (machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0) pause()
                }
                onChanged()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (selectExternalWhenAvailable) {
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until group.length) {
                            if (group.getTrackFormat(i).id == "external-subtitle" && group.isTrackSupported(i)) {
                                selectExternalWhenAvailable = false
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                                break
                            }
                        }
                        if (!selectExternalWhenAvailable) break
                    }
                }
                onChanged()
            }
            override fun onPlayerError(error: PlaybackException) {
                apply(machine.fail("Lecture impossible (${error.errorCodeName}). Le fichier, son accès ou son codec n’est peut-être pas pris en charge. Aucun fichier n’a été modifié."))
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Our own seeks reset before player.seekTo(). A discontinuity from
                // the engine itself must also invalidate old subtitle callbacks.
                if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                    apply(machine.reset(keepPlaybackIntent = true))
                    apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
                }
            }
        })
        apply(machine.setEnabled(prefs.narration)); apply(machine.setMode(prefs.mode)); setDucked(false)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    fun open(saved: SavedMedia) {
        savePosition()
        apply(machine.reset(false)); lastCue = null; onCaption("")
        media = saved
        val item = MediaItem.Builder().setUri(Uri.parse(saved.uri))
        val path = saved.subtitlePath
        selectExternalWhenAvailable = path != null
        if (path != null && saved.subtitleMime != null && File(path).isFile) {
            val sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(path)))
                .setMimeType(saved.subtitleMime).setLabel("Fichier de sous-titres ajouté")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setId("external-subtitle").build()
            item.setSubtitleConfigurations(listOf(sidecar))
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        player.setMediaItem(item.build(), saved.positionMs)
        player.prepare()
        prefs.save(saved)
        onChanged()
    }
    fun play() {
        if (media == null) { onMessage("Ouvre d’abord une vidéo, ou lance l’essai intégré.", false); return }
        if (!requestFocus()) return
        if (player.playbackState == Player.STATE_ENDED) seek(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        apply(machine.play())
    }
    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onMessage("L’accès au son est refusé pour le moment. Termine l’appel ou la lecture dans l’autre application.", true)
            return false
        }
        focusHeld = true
        return true
    }
    fun pause() {
        apply(machine.pause())
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        savePosition()
    }
    fun seek(positionMs: Long) {
        apply(machine.reset(keepPlaybackIntent = true)); lastCue = null; onCaption("")
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(position)
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    fun repeatLast() { if (requestFocus()) apply(machine.preview()) }
    fun previewVoice() { if (requestFocus()) apply(machine.preview("Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film.")) }
    fun refreshSettings() {
        pause(); speech.applyPreferences()
        apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration)); setDucked(ducked)
        onChanged()
    }
    private fun forwardCues(group: CueGroup) {
        val captions = group.cues.mapNotNull { it.text?.toString() }
        onCaption(captions.joinToString("\n"))
        apply(machine.cues(group.presentationTimeUs,
            captions.map { SubtitleText.clean(it, prefs.skipDescriptions) }, group.cues.any { it.bitmap != null }))
    }
    fun selectTrack(type: Int, group: Tracks.Group?, index: Int = 0, disabled: Boolean = false) {
        // Explicit pause prevents old-track narration from leaking into the new one.
        if (type == C.TRACK_TYPE_TEXT) selectExternalWhenAvailable = false
        pause(); apply(machine.reset(false)); lastCue = null; onCaption("")
        trackReset = true
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            .setTrackTypeDisabled(type, disabled)
        if (group != null) params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        player.trackSelectionParameters = params.build()
        handler.post {
            if (!closed) {
                trackReset = false
                // Reload the current position to make Media3 emit the newly
                // selected cues; do not re-use cached cues from the old track.
                player.seekTo(player.currentPosition)
                apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
                onChanged()
            }
        }
    }
    fun attachSubtitle(path: String?, mime: String?) {
        val current = media ?: return
        val updated = current.copy(positionMs = player.currentPosition, subtitlePath = path, subtitleMime = mime)
        open(updated)
    }
    fun savePosition() {
        val current = media ?: return
        val updated = current.copy(positionMs = player.currentPosition.coerceAtLeast(0))
        media = updated; prefs.save(updated)
    }
    private fun apply(effects: List<NarrationMachine.Effect>) {
        if (closed) return
        // All calls occur on the main looper. AndroidSpeech posts all callbacks,
        // avoiding re-entry while this effect list is being applied.
        for (effect in effects) when (effect) {
            is NarrationMachine.Effect.Speak -> speech.speak(effect.id, effect.text)
            NarrationMachine.Effect.StopSpeech -> speech.stop()
            is NarrationMachine.Effect.Video -> player.playWhenReady = effect.playing
            is NarrationMachine.Effect.Duck -> setDucked(effect.enabled)
            is NarrationMachine.Effect.Message -> onMessage(effect.text, effect.error)
        }
        onChanged()
    }
    private fun setDucked(value: Boolean) {
        ducked = value
        fade?.let { handler.removeCallbacks(it) }
        val from = player.volume
        val target = prefs.movieVolume * if (value) prefs.duckVolume else 1f
        var step = 0
        val animation = object : Runnable {
            override fun run() {
                if (closed) return
                step++
                player.volume = (from + (target - from) * step / 8f).coerceIn(0f, 1f)
                if (step < 8) handler.postDelayed(this, 20)
            }
        }
        fade = animation; handler.post(animation)
    }
    fun close() {
        if (closed) return
        pause(); closed = true
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        speech.close(); player.release()
    }
}
