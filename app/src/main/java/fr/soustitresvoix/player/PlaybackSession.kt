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
import fr.soustitresvoix.core.SpeechPlanning
import fr.soustitresvoix.core.SubtitleTimeline
import fr.soustitresvoix.core.TimedCaption
import fr.soustitresvoix.core.MkvTextIndex
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import androidx.media3.exoplayer.analytics.AnalyticsListener

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
    val metrics = PlaybackMetrics(context)
    var lastCaption: String = ""; private set
    private val indexIo = Executors.newSingleThreadExecutor()
    private val indexEpoch = AtomicLong()
    private var indexedTracks: Map<String, List<TimedCaption>> = emptyMap()
    private var externalIndex: List<TimedCaption> = emptyList()
    private var lastWarm = 0L
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
        onFailure = { id, msg -> if (!closed) apply(machine.speechFailed(id, msg)) },
        onProgress = { id, offset -> if (!closed) apply(machine.speechProgress(id, offset)) })
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
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { metrics.decoder = decoderName }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { metrics.droppedFrames += droppedFrames }
        })
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (metrics.firstFrameMs == null && metrics.openedAt > 0) metrics.firstFrameMs = SystemClock.elapsedRealtime() - metrics.openedAt
            }
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
        apply(machine.setEnabled(prefs.narration)); apply(machine.setMode(prefs.mode)); apply(machine.setResumePolicy(prefs.resumePolicy)); setDucked(false)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    fun open(saved: SavedMedia) {
        savePosition()
        speech.cancelPreparation(); metrics.openedAt = SystemClock.elapsedRealtime(); metrics.firstFrameMs = null
        indexedTracks = emptyMap(); externalIndex = emptyList(); lastCaption = ""
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
        startIndex(saved)
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
        speech.cancelPreparation()
        apply(machine.pause())
        setDucked(false, true)
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        savePosition()
    }
    fun seek(positionMs: Long) {
        speech.cancelPreparation()
        apply(machine.reset(keepPlaybackIntent = true)); lastCue = null; onCaption("")
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(position)
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    fun repeatLast() { if (requestFocus()) apply(machine.preview()) }
    fun previewVoice() { if (requestFocus()) apply(machine.preview("Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film.")) }
    fun resumeVoice() {
        if (media == null) { onMessage("Ouvre d’abord une vidéo.", false); return }
        if (requestFocus()) apply(machine.resumeVoice())
    }
    fun refreshSettings() {
        pause(); speech.applyPreferences()
        apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration)); apply(machine.setResumePolicy(prefs.resumePolicy)); setDucked(ducked)
        // Re-emit the current frame under the new filters, with no automatic play.
        if (media != null) {
            apply(machine.reset(false)); lastCue = null
            player.seekTo(player.currentPosition)
            apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        }
        onChanged()
    }
    private fun forwardCues(group: CueGroup) {
        val captions = group.cues.mapNotNull { it.text?.toString() }
        lastCaption = captions.joinToString("\n"); onCaption(lastCaption)
        apply(machine.cues(group.presentationTimeUs,
            captions.map { SpeechPlanning.filter(it, prefs.filters) }, group.cues.any { it.bitmap != null }))
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
            is NarrationMachine.Effect.Speak -> speech.speak(effect.id, effect.text, availableTime(effect.text))
            NarrationMachine.Effect.StopSpeech -> speech.stop()
            is NarrationMachine.Effect.Video -> player.playWhenReady = effect.playing
            is NarrationMachine.Effect.Duck -> setDucked(effect.enabled)
            is NarrationMachine.Effect.Message -> onMessage(effect.text, effect.error)
        }
        onChanged()
    }
    fun updateVolumes() { setDucked(ducked, true) }
    private fun setDucked(value: Boolean, immediate: Boolean = false) {
        ducked = value
        fade?.let { handler.removeCallbacks(it) }
        val from = player.volume
        val target = prefs.movieVolume * if (value) prefs.duckVolume else 1f
        val duration = if (value) prefs.attackMs else prefs.releaseMs
        val delay = if (!value && !immediate && machine.snapshot().wantsPlayback) prefs.duckHoldMs.toLong() else 0L
        var started = 0L
        val animation = object : Runnable {
            override fun run() {
                if (closed) return
                val now = SystemClock.elapsedRealtime(); if (started == 0L) started = now
                val fraction = if (duration == 0) 1f else ((now - started).toFloat() / duration).coerceIn(0f, 1f)
                player.volume = (from + (target - from) * fraction).coerceIn(0f, 1f)
                if (fraction < 1f) handler.postDelayed(this, 20)
            }
        }
        fade = animation; handler.postDelayed(animation, delay)
    }
    private fun selectedTimeline(): List<TimedCaption> {
        for (g in player.currentTracks.groups) if (g.type == C.TRACK_TYPE_TEXT) {
            for (i in 0 until g.length) if (g.isTrackSelected(i)) {
                val id = g.getTrackFormat(i).id ?: continue
                return if (id == "external-subtitle") externalIndex else indexedTracks[id] ?: emptyList()
            }
        }
        return emptyList()
    }
    private fun availableTime(text: String): Long? {
        if (!prefs.adaptive || !machine.snapshot().wantsPlayback || machine.snapshot().waitingForSpeech) return null
        val position = player.currentPosition
        val candidates = selectedTimeline().filter { it.startMs <= position + 150 && it.endMs > position }
        val matching = candidates.filter { text in SpeechPlanning.filter(it.text, prefs.filters) }
        val end = matching.minOfOrNull { it.endMs } ?: return null
        return ((end - position) / player.playbackParameters.speed).toLong().coerceAtLeast(1)
    }
    fun tickPreparation() {
        if (closed || !prefs.cacheEnabled || !prefs.narration || !metrics.allowPreparation()) { speech.cancelPreparation(); return }
        val now = SystemClock.elapsedRealtime(); if (now - lastWarm < 1200) return
        lastWarm = now
        val pos = player.currentPosition
        val upcoming = selectedTimeline().asSequence().filter { it.endMs > pos && it.startMs <= pos + prefs.lookAheadSeconds * 1000L }.take(12)
            .map { SpeechPlanning.filter(it.text, prefs.filters) }.toList()
        speech.prefetch(upcoming)
    }
    private fun startIndex(saved: SavedMedia) {
        val token = indexEpoch.incrementAndGet(); metrics.indexState = "Indexation locale"; metrics.indexCount = 0
        indexIo.execute {
            val started = SystemClock.elapsedRealtime()
            var external = emptyList<TimedCaption>(); var embedded = emptyMap<String, List<TimedCaption>>()
            var state = "Aucune piste texte indexable ; vocalisation directe conservée"
            try {
                saved.subtitlePath?.let { path ->
                    val f = File(path)
                    if (f.isFile && f.length() <= 8L * 1024 * 1024) external = SubtitleTimeline.parse(f.readText())
                }
                try {
                    context.contentResolver.openAssetFileDescriptor(Uri.parse(saved.uri), "r")?.use { afd ->
                        java.io.FileInputStream(afd.fileDescriptor).use { input ->
                            val size = if (afd.length >= 0) afd.length else input.channel.size() - afd.startOffset
                            if (size > 0) embedded = MkvTextIndex(input.channel, afd.startOffset, size) { indexEpoch.get() != token }.read()
                        }
                    }
                } catch (_: Exception) { /* Index is optional; Media3 still reads the original. */ }
                if (external.isNotEmpty() || embedded.isNotEmpty()) state = "Prête (pistes texte prises en charge)"
            } catch (_: Exception) { state = "Index indisponible ; vocalisation directe conservée" }
            handler.post {
                if (closed || indexEpoch.get() != token) return@post
                indexedTracks = embedded; externalIndex = external
                metrics.indexState = state; metrics.indexCount = external.size + embedded.values.sumOf { it.size }
                metrics.indexMs = SystemClock.elapsedRealtime() - started
                onChanged()
            }
        }
    }
    fun close() {
        if (closed) return
        pause(); closed = true; indexEpoch.incrementAndGet(); indexIo.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        speech.close(); player.release()
    }
}
