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
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechTools
import fr.soustitresvoix.core.PlaybackBenchmark
import fr.soustitresvoix.core.PlaybackDiagnosticSnapshot
import fr.soustitresvoix.core.ImageCommandPort
import fr.soustitresvoix.core.AudioCommandContext
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandPolicy
import fr.soustitresvoix.core.AudioCommandPort
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioCommandSnapshot
import fr.soustitresvoix.core.CapabilityStatus
import java.io.File

@UnstableApi
class PlaybackSession(
    private val context: Context, val prefs: PlayerPreferences,
    private val onChanged: () -> Unit, private val onMessage: (String, Boolean) -> Unit,
    private val onCaption: (String) -> Unit,
    components: PlaybackComponents = DefaultPlaybackComponents
) {
    private val handler = Handler(Looper.getMainLooper())
    val machine: NarrationController = components.createNarration()
    var ahead = CueAheadIndex(); private set
    val visual = VisualTools(this)
    val imageCommands: ImageCommandPort get() = visual
    val movieAudio: MovieAudio = components.createMovieAudio()
    /** The player volume and the installed PCM processor share this session-owned boundary. */
    val audioCommands: AudioCommandPort = object : AudioCommandPort {
        override fun snapshot(): AudioCommandSnapshot {
            val state = audioCommandContext()
            // Never consult a released player or processor; preferences remain available.
            return if (state.closed) AudioCommandSnapshot(state, null, null, null, null)
            else AudioCommandSnapshot(state, player.volume, movieAudio.enabled,
                movieAudio.speaking, movieAudio.processedSamples, movieAudio.dsp?.snapshot)
        }

        override fun submit(request: AudioCommandRequest): AudioCommandDecision {
            val before = audioCommandContext()
            val decision = AudioCommandPolicy.validate(request, before)
            if (decision is AudioCommandDecision.Accepted && decision.changed) {
                val settings = decision.requested
                finishPlaybackBenchmark("Mélange sonore modifié : essai interrompu")
                prefs.saveMovieAudioSettings(settings)
                if (settings.spectralDucking != before.requested.spectralDucking)
                    movieAudio.enabled = settings.spectralDucking
                if (settings.dsp != before.requested.dsp)
                    movieAudio.dsp?.submit(settings.dsp)
                if (settings.movieVolume != before.requested.movieVolume || settings.duckVolume != before.requested.duckVolume)
                    applyMixImmediately()
                // Timing changes are consumed by the next existing setDucked call.
                // No pause, speech method, narration command, focus change or sink recreation.
                // Do not call onChanged: MainActivity's general refresh also prepares speech.
                // Audio controls refresh their own values; the existing UI ticker remains.
            }
            return decision
        }
    }
    private fun audioCommandContext(): AudioCommandContext {
        check(Looper.myLooper() == handler.looper) { "Audio commands require the session thread." }
        return AudioCommandContext(mediaEpoch, closed,
            if (closed) CapabilityStatus.UNKNOWN else movieAudio.diagnosticCapability().status,
            prefs.movieAudioSettings(),
            if (closed) CapabilityStatus.UNKNOWN else movieAudio.dsp?.capability?.status ?: CapabilityStatus.UNKNOWN)
    }
    private val subtitleSource: SubtitleSource = components.createSubtitles(context)
    val translation = SubtitleTranslationSession(this, context, subtitleSource, onChanged, onMessage)
    private val features = PlaybackFeatures(context, this, onChanged, onMessage)
    var captionSink: ((List<Cue>?, Boolean) -> Unit)? = null
    var displayedCues: List<Cue> = emptyList(); private set
    var usesCaptionOverride = false; private set
    internal var imageViewport: Map<String, Any> = emptyMap()
    var onMediaAccessFailure: ((SavedMedia) -> Unit)? = null
    var onSessionRestored: (() -> Unit)? = null
    private var timedCursorMs: Long? = null
    private var timingTrack: String? = null
    private var timedIndex: CueAheadIndex? = null
    private var timedIndexCount = -1
    private var timedMap: fr.soustitresvoix.core.SubtitleTimeline? = null
    private var timedSchedule: fr.soustitresvoix.core.SubtitleBoundarySchedule? = null
    private var displaySchedule: fr.soustitresvoix.core.SubtitleBoundarySchedule? = null
    private data class TimedItem(val item: CueAheadIndex.Item, val display: fr.soustitresvoix.core.SubtitleTimeline.Interval,
        val narration: fr.soustitresvoix.core.SubtitleTimeline.Interval)
    private var timedItems: List<TimedItem> = emptyList()
    private var endDrainCursorMs: Long? = null
    private var endDrainComplete = true
    private var endDrainCallbackToken = 0L
    private var completionSequence = 0L
    private var phraseEndByUtterance = mutableMapOf<String, Boolean>()
    val hasCompleteSubtitleIndex: Boolean get() = indexedSource != null || translation.sourceCues.isNotEmpty()
    private val indexingWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var indexer: SubtitleIndexer? = null
    private var indexingStatusEpoch = 0L
    var indexingStatus = "Indexation complète non demandée"
        private set(value) { field = value; indexingStatusEpoch = mediaEpoch }
    private var manualPreparation = false
    private var thermalPreparationStopped = false
    val isPreparing: Boolean get() = indexer != null || speech.bulkPreparation || manualPreparation || translation.busy
    private data class IndexSource(val uri: String, val subtitlePath: String?, val subtitleMime: String?)
    private var indexedSource: IndexSource? = null
    private var holdStarted = 0L
    val holdLatency = fr.soustitresvoix.core.LatencyWindow(2048)
    val playbackBenchmark = PlaybackBenchmark()
    // Read the actual view only when starting a measurement. Preferences alone
    // cannot describe the library preview, its zoom, or its translated surface.
    internal var renderConfiguration: () -> List<String> = { listOf("surface non attachée") }
    private val benchmarkStorage = context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE)
    private val playbackTick = object : Runnable {
        override fun run() {
            if (closed) return
            if (!features.timeline.identity && hasCompleteSubtitleIndex &&
                !(player.playbackState == Player.STATE_ENDED && !endDrainComplete)) driveIndexedCues()
            else updateCaptionOverride()
            if (machine.snapshot().wantsPlayback) {
                if (!translation.onPlaybackPosition(features.timeline.sourceTimeMs(player.currentPosition, narration = true) * 1000)) {
                    handler.postDelayed(this, 100); return
                }
                apply(machine.advance(player.currentPosition)); speech.updateAdaptive()
                val waiting = machine.snapshot().waitingForSpeech
                if (waiting && holdStarted == 0L) holdStarted = SystemClock.elapsedRealtime()
                if (!waiting && holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
            }
            if (playbackBenchmark.active && playbackBenchmark.tick(benchmarkCounters())) {
                // tick ends the measurement; pausing cannot resume narration.
                saveBenchmarkReport()
                pause()
                onMessage("Essai de lecture terminé. Les résultats sont conservés dans Banc d’essai de lecture.", false)
            }
            handler.postDelayed(this, 100)
        }
    }
    private var mediaEpoch = 0L
    internal val generation: Long get() = mediaEpoch
    var lastRawCaption = ""; private set
    var droppedFrames = 0; private set
    var videoDecoder = "Non initialisé"; private set
    private val startedAt = SystemClock.elapsedRealtime()
    private val startedCpu = android.os.Process.getElapsedCpuTime()
    private var lastPreparationTick = 0L
    private var appliedFilters = prefs.filters
    internal val playback: PlaybackBackend = components.createPlayback(context, prefs,
        movieAudio, components.createVideoRenderer())
    private val diagnosticObserver = PlaybackDiagnosticsObserver(this)
    // The same Player instance is shared by both views for this session's lifetime.
    val player: Player get() = playback.player
    val video: VideoOutput get() = playback.video
    private val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
    private var closed = false
    private var ducked = false
    private var fade: Runnable? = null
    private var lastCue: CueGroup? = null
    private var trackReset = false
    private var trackSelectionSequence = 0L
    private var seekSequence = 0L
    private var hasAttachedSubtitle = false
    private var selectExternalWhenAvailable = false
    var media: SavedMedia? = null; private set
    private var focusHeld = false
    val speech: SpeechService = components.createSpeech(context, prefs,
        onAvailability = { ready, msg ->
            if (!closed) {
                if (!ready && prefs.narration) finishPlaybackBenchmark("Voix devenue indisponible : essai interrompu")
                apply(machine.setSpeechReady(ready)); onMessage(msg, !ready && prefs.narration); onChanged()
            }
        },
        onDone = { id -> if (!closed) {
            val duration = if (phraseEndByUtterance.remove(id) == true && machine.snapshot().wantsPlayback)
                prefs.int("comprehensionPauseMs", 0, 0, 3000) else 0
            val ticket = completionSequence
            val epoch = mediaEpoch
            val done = Runnable {
                if (!closed && ticket == completionSequence && epoch == mediaEpoch) {
                    apply(machine.speechFinished(id))
                    apply(machine.setComprehensionHold(false))
                    if (player.playbackState == Player.STATE_ENDED && endDrainComplete && machine.snapshot().speakingId == null &&
                        machine.snapshot().pendingCount == 0 && machine.snapshot().recoverableCount == 0) pause()
                }
            }
            if (duration == 0) done.run() else {
                // Keep the current request pending during the chosen comprehension
                // pause. Any explicit pause/seek invalidates this callback.
                apply(machine.setComprehensionHold(true))
                handler.postDelayed(done, duration.toLong())
            }
        } },
        onFailure = { id, msg -> if (!closed) {
            finishPlaybackBenchmark("Échec vocal : $msg")
            apply(machine.speechFailed(id, msg))
        } })
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
        video.setEffects(emptyList())
        movieAudio.enabled = prefs.bool("spectralDucking")
        movieAudio.dsp?.submit(prefs.movieAudioSettings().dsp)
        speech.firstAudioLatency.setObserver { playbackBenchmark.record(PlaybackBenchmark.Metric.FIRST_AUDIO, it) }
        speech.synthesisLatency.setObserver { playbackBenchmark.record(PlaybackBenchmark.Metric.SYNTHESIS, it) }
        speech.cacheLatency.setObserver { playbackBenchmark.record(PlaybackBenchmark.Metric.CACHE, it) }
        holdLatency.setObserver { playbackBenchmark.record(PlaybackBenchmark.Metric.HOLD, it) }
        video.frameRateStrategy = if (prefs.bool("matchFrameRate", true)) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        handler.post(playbackTick)
        playback.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) { droppedFrames += dropped }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestampMs: Long, durationMs: Long) { videoDecoder = name }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                if (!closed && diagnosticObserver.isCurrentMediaEvent(eventTime)) visual.onInputFormatChanged(format)
            }
        })
        playback.addAnalyticsListener(diagnosticObserver)
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { lastCue = cueGroup; if (!trackReset) forwardCues(cueGroup) }
            override fun onPlaybackStateChanged(state: Int) {
                apply(machine.setMediaReady(state == Player.STATE_READY || state == Player.STATE_ENDED))
                if (state == Player.STATE_ENDED) {
                    if (playbackBenchmark.active && playbackBenchmark.tick(benchmarkCounters())) {
                        saveBenchmarkReport()
                        pause()
                    }
                    else finishPlaybackBenchmark("Fin du fichier avant la durée cible de l’essai")
                    if (!features.timeline.identity && hasCompleteSubtitleIndex) {
                        if (endDrainCursorMs == null) endDrainCursorMs = timedCursorMs ?: player.currentPosition.coerceAtLeast(0)
                        endDrainComplete = false
                        drainNarrationAfterVideoEnd()
                    }
                    if (endDrainComplete) apply(machine.cues(Long.MAX_VALUE, emptyList()))
                    onMessage("Fin de la vidéo.", false)
                    if (endDrainComplete && machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0 && machine.snapshot().recoverableCount == 0) pause()
                }
                onChanged()
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (visual.hdr) visual.apply()
                if (selectExternalWhenAvailable) {
                    for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until group.length) {
                            if (isExternalSubtitle(group.getTrackFormat(i)) && group.isTrackSupported(i)) {
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
                translation.observeTrack()
                features.tracksAvailable(tracks)
                features.updateJournalContext()
                onChanged()
            }
            override fun onPlayerError(error: PlaybackException) {
                finishPlaybackBenchmark("Erreur de lecture : ${error.errorCodeName}")
                apply(machine.fail("Lecture impossible (${error.errorCodeName}). Le fichier, son accès ou son codec n’est peut-être pas pris en charge. Aucun fichier n’a été modifié."))
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
                    media?.let { onMediaAccessFailure?.invoke(it) }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                    finishPlaybackBenchmark("Discontinuité de lecture : essai interrompu")
                    apply(machine.reset(keepPlaybackIntent = true))
                    apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
                }
            }
        })
        apply(machine.setEnabled(prefs.narration)); apply(machine.setMode(prefs.mode))
        apply(machine.setResumePolicy(prefs.resumePolicy)); setDucked(false)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
    fun open(saved: SavedMedia, restoreSession: Boolean = false) {
        finishPlaybackBenchmark("Changement de vidéo ou de fichier de sous-titres")
        savePosition()
        translation.reset()
        cancelIndexing(); indexedSource = null; visual.reset()
        imageViewport = emptyMap()
        completionSequence++; phraseEndByUtterance.clear(); timedCursorMs = null; timingTrack = null
        endDrainCursorMs = null; endDrainComplete = true
        speech.clearOfflineSelection()
        val restored = features.opening(saved, restoreSession)
        val epoch = ++mediaEpoch; trackSelectionSequence++; trackReset = false
        lastRawCaption = ""; ahead.clear(); speech.cancelPreparation()
        val diagnosticToken = diagnosticObserver.beginMedia()
        apply(machine.reset(false)); lastCue = null; onCaption("")
        media = restored
        val item = MediaItem.Builder().setUri(Uri.parse(restored.uri)).setTag(diagnosticToken)
        val path = restored.subtitlePath
        hasAttachedSubtitle = path != null && restored.subtitleMime != null && File(path).isFile
        selectExternalWhenAvailable = hasAttachedSubtitle
        if (hasAttachedSubtitle) {
            checkNotNull(path)
            val sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(path)))
                .setMimeType(restored.subtitleMime).setLabel("Fichier de sous-titres ajouté")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setId("external-subtitle").build()
            item.setSubtitleConfigurations(listOf(sidecar))
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        val source = subtitleSource.createMediaSource(item.build()) { track, cues, origins -> handler.post {
            if (!closed && epoch == mediaEpoch) ahead.add(track, cues, origins)
        } }
        playback.setMediaSource(source, restored.positionMs)
        player.prepare(); prefs.save(restored)
        speech.applyPreferences(); apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration))
        updateCaptionOverride(); onChanged()
    }
    fun play() {
        if (media == null) { onMessage("Ouvre d’abord une vidéo, ou lance l’essai intégré.", false); return }
        if (!features.recoverCheckpointBeforePlay()) return
        if (player.playbackState == Player.STATE_ENDED && (!endDrainComplete || machine.snapshot().pendingCount > 0 || machine.snapshot().recoverableCount > 0)) {
            if (!translation.allowNarrationVoice()) return
            if (!requestFocus()) return
            apply(machine.setMediaReady(true)); apply(machine.play())
            if (!endDrainComplete && machine.snapshot().wantsPlayback) drainNarrationAfterVideoEnd()
            return
        }
        translation.onPlaybackPosition(features.timeline.sourceTimeMs(player.currentPosition, narration = true) * 1000)
        if (!translation.allowPlayback()) return
        if (!features.timeline.identity && !hasCompleteSubtitleIndex) {
            ensureCompleteIndex { onMessage("Synchronisation prête. Appuie sur Lire pour reprendre.", false) }; return
        }
        if (!speech.offlinePreparationActive)
            interruptPreparation("Préparation arrêtée : reprise de la lecture. Le cache existant est conservé.")
        if (!requestFocus()) return
        if (player.playbackState == Player.STATE_ENDED) seek(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        apply(machine.play())
    }
    internal fun rearmNarrationCheckpoint(positionMs: Long): Boolean {
        val track = selectedTextId() ?: return false
        seek(positionMs)
        ensureTimedSchedule(track)
        // A seek may still expose the old renderer cues until its next callback.
        // Re-arm from the verified source index instead of speaking those old cues.
        if (!dispatchTimedAt(positionMs, track)) return false
        timedCursorMs = positionMs
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (duration != null && positionMs >= duration) {
            endDrainCursorMs = positionMs
            endDrainComplete = false
        }
        return true
    }
    internal fun resumeFromNarrationCheckpoint(positionMs: Long): Boolean {
        if (!rearmNarrationCheckpoint(positionMs)) return false
        if (!endDrainComplete) {
            // The picture stays at EOF while a positive correction finishes speech.
            if (!translation.allowNarrationVoice() || !requestFocus()) return false
            apply(machine.setMediaReady(true)); apply(machine.play())
            drainNarrationAfterVideoEnd()
        } else play()
        return true
    }
    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onMessage("L’accès au son est refusé pour le moment. Termine l’appel ou la lecture dans l’autre application.", true); return false
        }
        focusHeld = true; return true
    }
    fun pause() {
        translation.playbackInterrupted()
        features.cancelCheckpointResume()
        pauseForIndexing()
    }
    /** Internal buffering hold preserves only the translation-owned playback intent. */
    internal fun pauseForTranslationBuffer(sourcePositionUs: Long) {
        pauseForIndexing()
        if (sourcePositionUs == Long.MAX_VALUE) return
        val sourceMs = (sourcePositionUs / 1000).coerceAtLeast(0)
        val boundary = features.timeline.mapInterval(sourceMs, sourceMs + 1, narration = true).startMs
        if (player.currentPosition > boundary) player.seekTo(boundary)
        timedCursorMs = null
        savePosition()
    }
    private fun pauseForIndexing() {
        completionSequence++
        finishPlaybackBenchmark("Pause demandée ou application quittée")
        interruptPreparation("Préparation arrêtée : lecture mise en pause. Le cache existant est conservé.")
        apply(machine.pause())
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        if (holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
        savePosition()
    }
    fun seek(positionMs: Long) {
        translation.playbackInterrupted()
        features.cancelCheckpointResume(abandon = true)
        completionSequence++; phraseEndByUtterance.clear(); timedCursorMs = null
        endDrainCursorMs = null; endDrainComplete = true
        seekSequence++
        finishPlaybackBenchmark("Position de lecture modifiée : extrait non comparable")
        interruptPreparation("Préparation arrêtée : position de lecture modifiée. Le cache existant est conservé.")
        apply(machine.reset(keepPlaybackIntent = true)); lastCue = null; onCaption("")
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(position); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    fun resumeVoice() {
        translation.onPlaybackPosition(features.timeline.sourceTimeMs(player.currentPosition, narration = true) * 1000)
        if (!translation.allowPlayback()) return
        if (media != null && requestFocus()) {
            interruptPreparation("Préparation arrêtée : reprise de la voix. Le cache existant est conservé.")
            apply(machine.resumeVoice())
        }
    }
    internal fun selectedTextId(): String? {
        for (group in player.currentTracks.groups) if (group.type == C.TRACK_TYPE_TEXT)
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return subtitleIndexId(group.getTrackFormat(i))
        return null
    }
    /** IDs in the extractor index precede the outer sidecar merge performed by Media3. */
    internal fun subtitleIndexId(format: Format): String? {
        val id = format.id ?: return null
        if (!hasAttachedSubtitle) return id
        // Media3 1.11.1 MergingMediaPeriod prefixes Format.id as "childIndex:id".
        // Our DefaultMediaSourceFactory has the container at 0 and one sidecar at 1.
        // Strip only that known outer layer; a colon inside a container ID is data.
        return when {
            isExternalSubtitle(format) -> "external-subtitle"
            id.startsWith("0:") -> id.substring(2).ifEmpty { null }
            else -> id
        }
    }
    internal fun isExternalSubtitle(format: Format): Boolean =
        hasAttachedSubtitle && format.id == "1:external-subtitle"
    fun prepareTick() {
        if (closed || SystemClock.elapsedRealtime() - lastPreparationTick < 1000) return
        lastPreparationTick = SystemClock.elapsedRealtime()
        if (stopPreparationForHeat()) return
        if (manualPreparation) { if (indexer == null && !speech.bulkPreparation) manualPreparation=false; return }
        if (!prefs.narration) { cancelIndexing(); speech.cancelPreparation(); return }
        if (!machine.snapshot().wantsPlayback) return
        if (translation.required && !translation.ready) return
        val future = ahead.future(selectedTextId(), features.timeline.sourceTimeMs(player.currentPosition, narration = true), prefs.filters,
            prefs.lookAheadCount, prefs.lookAheadSeconds, speech.language) { translation.textFor(it) }
        val texts = machine.pendingTexts() + future
        speech.prepareAhead(texts)
        features.register(ahead.activeItems(selectedTextId(), features.timeline.sourceTimeMs(player.currentPosition, narration = true) * 1000))
    }
    private fun stopPreparationForHeat(): Boolean {
        val hot = Build.VERSION.SDK_INT >= 29 &&
            context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (!hot) {
            if (thermalPreparationStopped) {
                thermalPreparationStopped = false
                indexingStatus = "État thermique redevenu compatible. L’anticipation peut reprendre ; relance la préparation complète si nécessaire."
                onChanged()
            }
            return false
        }
        if (!thermalPreparationStopped || isPreparing) {
            cancelIndexing(); speech.cancelPreparation()
            indexingStatus = "Indexation et préparation arrêtées : Android signale un état thermique sévère. Le cache existant est conservé."
        }
        if (!thermalPreparationStopped) {
            thermalPreparationStopped = true
            onMessage(indexingStatus, false); onChanged()
        }
        return true
    }
    fun prepareRange(seconds: Int?) {
        val saved = media ?: return
        if (translation.required && !translation.allowPlayback()) return
        if (stopPreparationForHeat()) return
        if (player.currentTracks.groups.none { group -> group.type == C.TRACK_TYPE_TEXT &&
                (0 until group.length).any { group.isTrackSelected(it) } }) {
            onMessage("Attends le chargement puis choisis une piste de sous-titres textuelle avant la préparation.", true); return
        }
        refreshSettings()
        if (translation.required && !translation.allowPlayback()) return
        manualPreparation = true
        val start = if (seconds == null) 0L else player.currentPosition
        val selected = selectedTextId()
        // An attached sidecar does not replace the container when an embedded
        // track is selected. Its index must not be reused for the other source.
        val source = if (selected == "external-subtitle") saved else saved.copy(subtitlePath = null, subtitleMime = null)
        val origin = IndexSource(source.uri, source.subtitlePath, source.subtitleMime)
        val epoch = mediaEpoch
        fun prepare(index: CueAheadIndex) {
            if (stopPreparationForHeat()) return
            val items = index.selected(selected).asSequence()
                .filter {
                    val begin = it.startUs / 1000
                    val interval = features.timeline.mapInterval(begin, (it.endUs / 1000).coerceAtLeast(begin + 1), narration = true)
                    interval.endMs > start && (seconds == null || interval.startMs < start + seconds * 1000L)
                }
                .toList()
            val translated = items.map { translation.textFor(it) }
            if (translated.any { it == null }) { manualPreparation = false; translation.rejectUnmapped(); return }
            val text = translated.filterNotNull().asSequence()
                .flatMap { SpeechTools.segments(SpeechTools.filter(it, prefs.filters), speech.language).asSequence() }
                .distinct().take(100_001).toList()
            if (text.size > 100_000) {
                manualPreparation = false
                indexingStatus = "Préparation refusée : plus de 100 000 segments distincts. Choisis une plage plus courte."
                onMessage(indexingStatus, true); return
            }
            if (text.isEmpty()) { manualPreparation=false; onMessage("Aucune réplique trouvée pour la piste sélectionnée. Vérifie qu’elle est textuelle.", true); return }
            speech.prepareBatch(text)
            onMessage("Préparation de ${text.size} segments demandée. Elle reste annulable et limitée par le cache.", false)
        }
        if (indexedSource == origin) { prepare(ahead); return }
        indexer?.cancel()
        val task = subtitleSource.createIndexer(); indexer = task
        indexingStatus="Indexation complète en cours ; aucun décodage vidéo pour cet index"
        indexingWorker.execute {
            try {
                val index = task.scan(source) { percent -> handler.post {
                    if (!closed && epoch == mediaEpoch && indexer === task) indexingStatus = "Indexation ${if (percent >= 0) "$percent %" else "en cours"}"
                } }
                handler.post {
                    if (closed || epoch != mediaEpoch || indexer !== task) return@post
                    indexer=null; ahead=index; indexedSource=origin
                    indexingStatus="Indexation terminée : ${index.count} entrées ; préparation sur la piste choisie"
                    prepare(index)
                }
            } catch (e: Exception) { handler.post {
                if (!closed && epoch == mediaEpoch && indexer === task) {
                    indexer=null; manualPreparation=false; indexingStatus=subtitleSource.safeFailureMessage(e)
                    onMessage(indexingStatus, true)
                }
            } }
        }
    }
    fun cancelIndexing() { indexer?.cancel(); indexer=null; manualPreparation=false }
    fun cancelPreparation() { cancelIndexing(); speech.cancelPreparation(); indexingStatus="Préparation annulée ; lecture et cache existant conservés" }
    private fun interruptPreparation(message: String) {
        // Translation owns a separate worker and explicit cancellation control.
        val wasPreparing = indexer != null || speech.bulkPreparation || manualPreparation
        cancelIndexing(); speech.cancelPreparation()
        if (wasPreparing) {
            indexingStatus = message
            onMessage(message, false); onChanged()
        }
    }
    /** Called before a preference is mutated; applying it still happens after the edit. */
    fun beforeSettingsChange() {
        finishPlaybackBenchmark("Réglages modifiés : essai interrompu avant la modification")
        interruptPreparation("Préparation arrêtée : réglages modifiés. Applique les réglages puis relance la préparation ; le cache existant est conservé.")
    }
    private fun benchmarkCounters(): PlaybackBenchmark.Counters {
        val videoCounters = playback.videoDecoderCounters
        videoCounters?.ensureUpdated()
        return PlaybackBenchmark.Counters(
            SystemClock.elapsedRealtime(), android.os.Process.getElapsedCpuTime(), player.currentPosition.coerceAtLeast(0),
            (videoCounters?.droppedBufferCount ?: droppedFrames).toLong(), speech.hits.toLong(), speech.misses.toLong())
    }
    private fun benchmarkMediaFingerprint(): String = PlaybackBenchmark.fingerprint(listOf(
        media?.uri.orEmpty(), media?.subtitlePath.orEmpty(), media?.subtitleMime.orEmpty(), selectedTextId().orEmpty()))
    private fun benchmarkSettingsFingerprint(): String {
        val tracks = player.currentTracks.groups.flatMap { group -> (0 until group.length).mapNotNull { i ->
            if (!group.isTrackSelected(i)) null else group.getTrackFormat(i).let { f ->
                "${group.type}:${f.id}:${f.language}:${f.sampleMimeType}:${f.codecs}:${f.width}:${f.height}:${f.frameRate}"
            }
        } }
        return PlaybackBenchmark.fingerprint(listOf(
            speech.cacheConfigurationFingerprint, prefs.narration.toString(), prefs.mode.name,
            prefs.speechRate.toString(), prefs.adaptiveMax.toString(), prefs.allowedLagMs.toString(),
            prefs.filters.toString(), prefs.cacheEnabled.toString(), prefs.cacheBytes.toString(),
            prefs.lookAheadSeconds.toString(), prefs.lookAheadCount.toString(), prefs.resumePolicy.name,
            prefs.movieVolume.toString(), prefs.duckVolume.toString(), prefs.voiceVolume.toString(),
            prefs.int("duckAttack",160,0,2000).toString(), prefs.int("duckRelease",500,0,2000).toString(),
            prefs.int("duckHold",350,0,2000).toString(), prefs.bool("spectralDucking").toString(),
            "expertAudio.v1=${prefs.movieAudioSettings().dsp}",
            "translation.v1=${translation.fingerprint}",
            prefs.bool("matchFrameRate",true).toString(), prefs.bool("preferSoftware").toString(),
            prefs.int("imageMode",0,0,2).toString(), visual.margins.toString(), visual.contrast.toString(),
            visual.brightness.toString(), visual.bypass.toString(), visual.detailedScaling.toString(),
            visual.scalingTarget, player.playbackParameters.toString(),
            prefs.showSubtitles.toString(), prefs.subtitleSp.toString()) + tracks + renderConfiguration())
    }
    fun startPlaybackBenchmark(seconds: Int) {
        if (!translation.allowPlayback()) return
        if (media == null || player.playbackState != Player.STATE_READY) {
            onMessage("Ouvre une vidéo et attends qu’elle soit prête avant l’essai de lecture.", true); return
        }
        refreshSettings()
        if (!translation.allowPlayback()) return
        if (prefs.narration && !speech.isReady) {
            onMessage("Choisis d’abord une voix prête, ou désactive la vocalisation pour mesurer seulement la vidéo.", true); return
        }
        if (!requestFocus()) return
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName,0).versionName }.getOrNull() ?: "inconnue"
        val identity = PlaybackBenchmark.Identity(benchmarkMediaFingerprint(), benchmarkSettingsFingerprint(),
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}", speech.engineDiagnosticIdentity,
            version, speech.cacheSize)
        playbackBenchmark.start(seconds.coerceIn(1,3600) * 1000L, identity, benchmarkCounters())
        apply(machine.setMediaReady(true)); apply(machine.resumeVoice())
        if (!machine.snapshot().wantsPlayback) {
            finishPlaybackBenchmark("Lecture non démarrée : voix ou reprise explicite en attente"); return
        }
        onMessage("Essai de ${seconds.coerceIn(1,3600)} secondes de vidéo lancé à la position actuelle. Les pauses vocales comptent dans le temps réel. Le lecteur s’arrêtera à la fin de l’extrait.", false)
    }
    fun replayPlaybackBenchmark() {
        val previous = playbackBenchmark.snapshot()
        if (previous == null || previous.active || previous.identity.mediaFingerprint != benchmarkMediaFingerprint()) {
            onMessage("Pour rejouer le même extrait, conserve la même vidéo et la même piste, puis termine l’essai en cours.", true); return
        }
        seek(previous.initialPositionMs)
        // ExoPlayer may buffer after the seek. Start only after it is ready;
        // never turn a delayed callback into an unsolicited playback command.
        onMessage("Retour à la position initiale effectué. Relance l’essai après chargement, avec la même durée (${previous.requestedMs / 1000} secondes).", false)
    }
    fun stopPlaybackBenchmark() {
        finishPlaybackBenchmark("Essai terminé explicitement par l’utilisateur")
        pause()
    }
    fun finishPlaybackBenchmark(reason: String) {
        if (!playbackBenchmark.active) return
        if (holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
        playbackBenchmark.finish(benchmarkCounters(), reason)
        saveBenchmarkReport()
    }
    private fun saveBenchmarkReport() {
        benchmarkStorage.edit().putString("latest", playbackBenchmark.report()).apply()
    }
    fun playbackBenchmarkReport(): String {
        val current = if (playbackBenchmark.snapshot() != null) playbackBenchmark.report()
            else benchmarkStorage.getString("latest", null) ?: "Aucun essai de lecture chronométré lancé"
        return "$current\n\nLes fichiers, positions et réglages sont identifiés pour comparer un même extrait. Seuls les événements terminés pendant l’essai alimentent ses distributions. Le CPU du moteur vocal externe et la charge GPU/NPU ne sont pas mesurés par le lecteur."
    }
    fun benchmark() {
        if (stopPreparationForHeat()) return
        refreshSettings(); speech.synthesisLatency.clear(); speech.firstAudioLatency.clear(); speech.cacheLatency.clear(); holdLatency.clear()
        manualPreparation=true
        speech.prepareBatch(listOf("Bonjour. Ceci est le même extrait pour comparer les moteurs.",
            "M. Dupont arrive à 15 h 30 ; il repart demain, après le déjeuner.",
            "Pourquoi ? Parce que chaque pause, chaque virgule et chaque mot comptent.",
            "Élodie répond : « Oui, bien sûr ! » Puis elle se tait...",
            "La vidéo continue pendant que les prochains sous-titres sont préparés.",
            "Cette dernière phrase permet de vérifier la fin de la préparation."))
        onMessage("Banc d’essai de six extraits lancé. Consulte Mesures. Une seconde exécution teste le cache chaud ; vider le cache fournit une comparaison à froid.", false)
    }
    fun diagnosticSnapshot(): PlaybackDiagnosticSnapshot = diagnosticObserver.snapshot()
    internal fun diagnosticIndexingStatus(): String = if (indexingStatusEpoch == mediaEpoch)
        indexingStatus else "Indexation complète non demandée pour ce média"
    fun diagnostics(): String {
        val snapshot = diagnosticSnapshot()
        val capabilities = PlaybackDiagnosticText.render(snapshot)
        if (snapshot.closed) return capabilities
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        val cpu = (android.os.Process.getElapsedCpuTime() - startedCpu) * 100.0 / elapsed
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val heat = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus.toString() else "indisponible"
        val pss = context.getSystemService(android.app.ActivityManager::class.java).getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
        return "$capabilities\n\nAppareil : ${Build.MANUFACTURER} ${Build.MODEL} ; Android ${Build.VERSION.RELEASE}\n" +
            "Images vidéo abandonnées (cumul de session) : $droppedFrames\n" +
            "CPU du lecteur (hors moteur vocal externe) : ${"%.1f".format(cpu)} % d’un cœur, moyenne de session\n" +
            "Mémoire PSS du lecteur : ${pss / 1024} Mio (moteur vocal externe non inclus)\n" +
            "Température BATTERIE : ${if (temp != null && temp != Int.MIN_VALUE) "${temp / 10f} °C" else "indisponible"} ; pas la température CPU/GPU\n" +
            "État thermique Android : $heat (0 normal ; 3 sévère)\n" +
            "Prélecture : ${ahead.count} entrées temporelles en mémoire\n${speech.preparationStatus}\n" +
            "Cache : ${speech.cacheSize / 1048576} Mio ; lectures depuis le cache : ${speech.hits} ; replis directs : ${speech.misses}\n" +
            "Dernière synthèse mesurée : ${speech.lastSynthesisMs} ms pour ${speech.lastAudioMs} ms d’audio\n" +
            "Dernier facteur adaptatif : ${"%.2f".format(speech.lastFactor)}\n" +
            "Origine du dernier calcul : ${speech.timingSource} ; ${speech.durations.observations} observations de durée\n" +
            "Délais demande → début audio : ${speech.firstAudioLatency.summary()}\n" +
            "Délais synthèse en fichier : ${speech.synthesisLatency.summary()}\n" +
            "Délais lecture du cache : ${speech.cacheLatency.summary()}\n" +
            "Pauses de confort terminées : ${holdLatency.summary()}\n" +
            "Préparation : anticipation ${prefs.lookAheadSeconds} s estimées, ${prefs.lookAheadCount} segments max, cache ${prefs.cacheBytes / 1048576} Mio\n" +
            "GPU/NPU du moteur vocal : non exposés par l’API TTS Android. Aucun benchmark OnePlus n’est simulé.\n\n${playbackBenchmarkReport()}"
    }
    fun repeatLast() {
        if (!translation.allowPlayback()) return
        finishPlaybackBenchmark("Répétition d’une réplique : essai interrompu")
        if (requestFocus()) {
            interruptPreparation("Préparation arrêtée : répétition de la réplique. Le cache existant est conservé.")
            apply(machine.preview())
        }
    }
    fun previewVoice() {
        finishPlaybackBenchmark("Essai de voix : lecture comparative interrompue")
        if (requestFocus()) {
            interruptPreparation("Préparation arrêtée : essai de la voix. Le cache existant est conservé.")
            apply(machine.preview("Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film."))
        }
    }
    fun refreshSettings() {
        pause(); speech.applyPreferences()
        machine.setNarrationLanguage(speech.language)
        if (appliedFilters != prefs.filters) {
            appliedFilters = prefs.filters
            apply(machine.reset(false)); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
            lastCue?.let { forwardCues(it) }
        }
        apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration))
        apply(machine.setResumePolicy(prefs.resumePolicy))
        movieAudio.enabled = prefs.bool("spectralDucking")
        video.frameRateStrategy = if (prefs.bool("matchFrameRate", true)) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        setDucked(ducked); onChanged()
    }
    private fun forwardCues(group: CueGroup) {
        if (!features.timeline.identity && hasCompleteSubtitleIndex) { driveIndexedCues(); return }
        val captions = group.cues.mapNotNull { it.text?.toString() }
        if (translation.required) {
            val wasPlaying = machine.snapshot().wantsPlayback
            val missing = translation.nextMissingSourceUs
            if (!translation.onPlaybackPosition(group.presentationTimeUs)) {
                if (wasPlaying && translation.progressiveEnabled && group.presentationTimeUs >= missing &&
                    group.presentationTimeUs >= 0 && group.presentationTimeUs / 1000 < player.currentPosition)
                    player.seekTo(group.presentationTimeUs / 1000)
                lastRawCaption = ""; onCaption(""); return
            }
            // Renderer callbacks may still arrive after the pause used to prepare
            // a translation. Waiting for completion is expected, not a mapping error.
            if (!translation.ready) {
                lastRawCaption = ""; onCaption("")
                if (machine.snapshot().wantsPlayback || machine.snapshot().speakingId != null) pause()
                return
            }
            if (captions.isEmpty() && group.cues.none { it.bitmap != null }) {
                lastRawCaption = ""; onCaption(""); apply(machine.cues(group.presentationTimeUs, emptyList())); return
            }
            val originals = captions.map { fr.soustitresvoix.core.SubtitleText.clean(it) }.filter { it.isNotBlank() }
            val occurrences = resolveSourceOccurrences(originals, group.presentationTimeUs, SpeechTools.Filters())
            occurrences?.let(features::register)
            val translated = occurrences?.map { translation.textFor(it) }
            if (occurrences == null || translated == null || translated.any { it == null } || group.cues.any { it.bitmap != null }) {
                translation.rejectUnmapped(); return
            }
            lastRawCaption = translated.filterNotNull().joinToString("\n"); onCaption(lastRawCaption)
            updateCaptionOverride()
            // Filtering the translated text never changes the correspondence with its source identity.
            val pairs = occurrences.zip(translated.filterNotNull()).map { (item, text) -> item to SpeechTools.filter(text, prefs.filters) }
                .filter { it.second.isNotBlank() }
            apply(machine.cues(group.presentationTimeUs, pairs.map { it.second }, false,
                pairs.map { it.first.identity }, pairs.map { it.first.endUs / 1000 + prefs.allowedLagMs }))
            return
        }
        lastRawCaption = captions.joinToString("\n"); onCaption(lastRawCaption)
        val clean = captions.map { SpeechTools.filter(it, prefs.filters) }.filter { it.isNotBlank() }
        val occurrences = resolveSourceOccurrences(clean, group.presentationTimeUs, prefs.filters)
        occurrences?.let(features::register)
        val spoken = occurrences?.map { SpeechTools.filter(it.raw, prefs.filters) } ?: clean
        apply(machine.cues(group.presentationTimeUs, spoken, group.cues.any { it.bitmap != null },
            occurrences?.map { it.identity },
            occurrences?.map { it.endUs / 1000 + prefs.allowedLagMs } ?:
                clean.map { text -> ahead.windowEnd(selectedTextId(), text, player.currentPosition, prefs.filters)?.plus(prefs.allowedLagMs) }))
    }
    /** A renderer can visually coalesce identical simultaneous lines. Source
     * events retain their multiplicity and must each reach the narrator. */
    private fun resolveSourceOccurrences(texts: List<String>, timeUs: Long, filters: SpeechTools.Filters): List<CueAheadIndex.Item>? {
        fun canonical(value: String) = value.trim().replace(Regex("\\s+"), " ")
        val expected = texts.map(::canonical).toSet()
        val exact = ahead.activeItems(selectedTextId(), timeUs).filter { canonical(SpeechTools.filter(it.raw, filters)) in expected }
        val available = exact.map { canonical(SpeechTools.filter(it.raw, filters)) }.toMutableList()
        if (texts.all { available.remove(canonical(it)) }) return exact
        val matched = ahead.resolved(selectedTextId(), texts, timeUs, filters) ?: return null
        val all = ahead.activeItems(selectedTextId(), timeUs + 1000).filter { canonical(SpeechTools.filter(it.raw, filters)) in expected }
        return if (all.size >= matched.size) all else matched
    }
    fun selectTrack(type: Int, group: Tracks.Group?, index: Int = 0, disabled: Boolean = false) {
        savePosition(); features.cancelCheckpointResume(abandon = type == C.TRACK_TYPE_TEXT)
        completionSequence++; phraseEndByUtterance.clear(); timedCursorMs = null
        if (type == C.TRACK_TYPE_TEXT) { translation.trackChanged(); selectExternalWhenAvailable = false; cancelIndexing() }
        pause(); apply(machine.reset(false)); lastCue = null; onCaption(""); trackReset = true
        val ticket = ++trackSelectionSequence
        val epoch = mediaEpoch
        val selectedSubtitle = selectedTextId()
        val positionTicket = seekSequence
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, disabled)
        if (group != null) params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        player.trackSelectionParameters = params.build()
        handler.post {
            if (!closed && epoch == mediaEpoch && ticket == trackSelectionSequence) {
                trackReset = false; player.seekTo(player.currentPosition)
                apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
                if (type == C.TRACK_TYPE_AUDIO && selectedSubtitle == selectedTextId() && positionTicket == seekSequence)
                    rearmCurrentSubtitles()
                onChanged()
            }
        }
    }
    fun retryDecoder(software: Boolean) {
        val saved=media ?: return
        pause(); prefs.putBool("preferSoftware",software)
        val position=player.currentPosition
        player.stop(); open(saved.copy(positionMs=position))
        onMessage("Sélection ${if(software) "logicielle prioritaire" else "normale"} demandée. Appuie sur Lire ; un décodeur absent n’est pas ajouté.",false)
    }
    fun attachSubtitle(path: String?, mime: String?) {
        val current = media ?: return
        open(current.copy(positionMs = player.currentPosition, subtitlePath = path, subtitleMime = mime), restoreSession = false)
    }
    fun savePosition() {
        val current = media ?: return
        val updated = current.copy(positionMs = player.currentPosition.coerceAtLeast(0)); media = updated; prefs.save(updated)
        features.save(updated)
    }
    private fun apply(effects: List<NarrationMachine.Effect>) {
        if (closed) return
        for (effect in effects) when (effect) {
            is NarrationMachine.Effect.Speak -> {
                phraseEndByUtterance[effect.id] = effect.remainingText.isBlank() && !effect.preview
                val adaptive = prefs.mode == NarrationMachine.Mode.ADAPTIVE && !effect.preview
                val deadline = effect.deadlineMs ?: ahead.windowEnd(selectedTextId(), effect.text, player.currentPosition, prefs.filters)?.let { it + if (adaptive) prefs.allowedLagMs else 0 }
                speech.speak(effect.id, effect.text, adaptive = adaptive, remaining = effect.remainingText, budget = {
                    deadline?.let { ((it - player.currentPosition) / player.playbackParameters.speed).toLong() }
                }, planning = if (!adaptive) null else { audioLeft ->
                    val now = player.currentPosition
                    val speed = player.playbackParameters.speed
                    fun relative(time: Long) = ((time - now) / speed).toLong()
                    val jobs = mutableListOf(fr.soustitresvoix.core.AdaptiveTiming.Window(
                        audioLeft, deadlineMs = deadline?.let(::relative)))
                    machine.pendingPlan().forEach { pending ->
                        jobs += fr.soustitresvoix.core.AdaptiveTiming.Window(
                            measuredOrEstimated(pending.text),
                            deadlineMs = pending.deadlineMs?.let(::relative))
                    }
                    ahead.planningItems(selectedTextId(), features.timeline.sourceTimeMs(now, narration = true)).forEach { cue ->
                        val text = SpeechTools.filter(translation.textFor(cue) ?: "", prefs.filters)
                        if (text.isNotBlank()) {
                            val start = cue.startUs / 1000
                            val interval = features.timeline.mapInterval(start, (cue.endUs / 1000).coerceAtLeast(start + 1), narration = true)
                            jobs += fr.soustitresvoix.core.AdaptiveTiming.Window(measuredOrEstimated(text),
                                relative(interval.startMs), relative(interval.endMs + prefs.allowedLagMs))
                        }
                    }
                    fr.soustitresvoix.core.AdaptiveTiming.planFactor(jobs, prefs.adaptiveMax)
                })
            }
            NarrationMachine.Effect.StopSpeech -> speech.stop()
            is NarrationMachine.Effect.Video -> player.playWhenReady = effect.playing
            is NarrationMachine.Effect.Duck -> setDucked(effect.enabled)
            is NarrationMachine.Effect.Message -> {
                if (effect.error && !machine.snapshot().wantsPlayback) finishPlaybackBenchmark(effect.text)
                onMessage(effect.text, effect.error)
            }
        }
        onChanged()
    }
    internal fun updateMix() {
        if (closed) return
        finishPlaybackBenchmark("Mélange sonore modifié : essai interrompu")
        applyMixImmediately()
    }
    private fun applyMixImmediately() {
        fade?.let { handler.removeCallbacks(it) }
        fade = null
        player.volume = (prefs.movieVolume * if (ducked) prefs.duckVolume else 1f).coerceIn(0f, 1f)
    }
    private fun setDucked(value: Boolean) {
        ducked = value; movieAudio.speaking = value
        fade?.let { handler.removeCallbacks(it) }
        val from = player.volume
        val target = prefs.movieVolume * if (value) prefs.duckVolume else 1f
        val duration = prefs.int(if (value) "duckAttack" else "duckRelease", if (value) 160 else 500, 0, 2000)
        val gap = ahead.nextStart(selectedTextId(), player.currentPosition)?.minus(player.currentPosition)
        val anticipation = if (!value && machine.snapshot().wantsPlayback && gap != null && gap in 0..1200) gap.toInt()+150 else 0
        val hold = if (value) 0 else maxOf(prefs.int("duckHold", 350, 0, 2000), anticipation)
        var began = 0L
        val animation = object : Runnable {
            override fun run() {
                if (closed) return
                if (began == 0L) began = SystemClock.elapsedRealtime()
                val progress = if (duration == 0) 1f else ((SystemClock.elapsedRealtime() - began) / duration.toFloat()).coerceIn(0f, 1f)
                player.volume = (from + (target - from) * progress).coerceIn(0f, 1f)
                if (progress < 1) handler.postDelayed(this, 20)
            }
        }
        fade = animation; handler.postDelayed(animation, hold.toLong())
    }
    fun close() {
        if (closed) return
        // Preserve the selected complete translation before its worker state is cleared.
        pause(); translation.close()
        cancelIndexing(); indexingWorker.shutdownNow(); closed = true; mediaEpoch++; handler.removeCallbacksAndMessages(null)
        diagnosticObserver.close()
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        features.close(); captionSink = null; visual.close(); speech.close(); playback.close()
    }
    internal fun resetNarrationForTranslation() {
        completionSequence++; phraseEndByUtterance.clear(); timedCursorMs = null
        finishPlaybackBenchmark("Source de vocalisation modifiée")
        speech.cancelPreparation(); apply(machine.reset(false)); lastCue = null; lastRawCaption = ""; onCaption("")
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    internal fun installTranslationIndex(index: CueAheadIndex) {
        ahead = index
        features.register(index.selected(selectedTextId()))
        updateCaptionOverride()
        rearmCurrentSubtitles()
    }
    internal fun rearmCurrentSubtitles() {
        // A seek to the same position does not reliably re-emit an already visible
        // cue. Queue its current occurrences while still paused; this emits no speech.
        val current = player.currentCues
        lastCue = current
        if (!trackReset) forwardCues(current)
    }

    internal fun applyNarration(effects: List<NarrationMachine.Effect>) = apply(effects)
    fun restoreSettings(snapshot: Map<String, Any>) {
        pause(); prefs.restoreSettings(snapshot)
        movieAudio.enabled = prefs.movieAudioSettings().spectralDucking
        movieAudio.dsp?.submit(prefs.movieAudioSettings().dsp)
        refreshSettings(); applyMixImmediately(); updateCaptionOverride()
    }
    fun showNarrationReport(activity: android.app.Activity) = features.showNarrationReport(activity)
    fun showResumeSession(activity: android.app.Activity): android.app.AlertDialog = features.showResumeSession(activity)
    fun showOfflineStorage(activity: android.app.Activity) = features.showOfflineStorage(activity)
    fun showSynchronization(activity: android.app.Activity) = features.showSynchronization(activity)
    internal fun setTimeline(value: fr.soustitresvoix.core.SubtitleTimeline) = features.applyTimeline(value)
    fun savedSessionRecord(): MediaSessionSnapshot? = media?.let { features.sessionRecord(it.uri) }
    fun relocateSessionTo(uri: String, title: String) = features.relocate(uri, title)
    fun forgetSession() {
        pause(); media?.let { features.forget(it.uri) }; prefs.clearMedia()
        media = null; player.stop(); completionSequence++; timedCursorMs = null
        apply(machine.reset(false)); displayedCues = emptyList(); usesCaptionOverride = false
        captionSink?.invoke(emptyList(), false); onChanged()
    }

    private fun measuredOrEstimated(text: String): Long =
        SpeechTools.segments(text, speech.language).sumOf { segment ->
            speech.measuredDurationMs(segment) ?: speech.durations.estimate(segment, prefs.speechRate)
        }.coerceAtLeast(1)

    /** Full source index used by synchronization/replay/offline; never starts playback. */
    internal fun ensureCompleteIndex(ready: () -> Unit) {
        val saved = media ?: run { onMessage("Ouvre d’abord une vidéo.", false); return }
        val selected = selectedTextId() ?: run { onMessage("Choisis d’abord une piste de sous-titres textuelle.", true); return }
        val source = if (selected == "external-subtitle") saved else saved.copy(subtitlePath = null, subtitleMime = null)
        val origin = IndexSource(source.uri, source.subtitlePath, source.subtitleMime)
        if (indexedSource == origin && !ahead.truncated) { ready(); return }
        if (translation.sourceCues.isNotEmpty() && !ahead.truncated && ahead.selected(selected).size == translation.sourceCues.size) { ready(); return }
        pauseForIndexing(); cancelIndexing()
        val epoch = mediaEpoch
        val task = subtitleSource.createIndexer(); indexer = task
        indexingStatus = "Indexation complète de la piste choisie en cours"
        indexingWorker.execute {
            try {
                val index = task.scan(source) { percent -> handler.post {
                    if (!closed && epoch == mediaEpoch && indexer === task) {
                        indexingStatus = "Indexation ${if (percent >= 0) "$percent %" else "en cours"}"
                        onChanged()
                    }
                } }
                handler.post {
                    if (closed || epoch != mediaEpoch || selected != selectedTextId() || indexer !== task) return@post
                    indexer = null
                    if (index.truncated) {
                        indexingStatus = "Index incomplet : la préparation intégrale ne peut pas être certifiée."
                        onMessage(indexingStatus, true); return@post
                    }
                    ahead = index; indexedSource = origin; timedCursorMs = null
                    features.register(index.selected(selected)); indexingStatus = "Index complet : ${index.selected(selected).size} occurrences"
                    ready(); onChanged()
                }
            } catch (error: Exception) { handler.post {
                if (!closed && epoch == mediaEpoch && indexer === task) {
                    indexer = null; indexingStatus = subtitleSource.safeFailureMessage(error)
                    onMessage(indexingStatus, true)
                }
            } }
        }
    }

    internal fun invalidateTimedNarration() {
        completionSequence++; phraseEndByUtterance.clear(); timedCursorMs = null
        apply(machine.reset(false)); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        updateCaptionOverride(); rearmCurrentSubtitles()
    }
    internal fun previewSynchronizedCaption() {
        updateCaptionOverride()
        val sourceMs = features.timeline.sourceTimeMs(player.currentPosition, narration = true)
        val item = ahead.activeItems(selectedTextId(), sourceMs * 1000).firstOrNull()
            ?: ahead.planningItems(selectedTextId(), sourceMs).firstOrNull()
        if (item == null) { onMessage("Aucune réplique à cet instant. Déplace la vidéo vers un sous-titre.", false); return }
        val corrected = features.timeline.mapInterval(item.startUs / 1000, item.endUs / 1000, narration = true)
        onMessage("Réplique corrigée : ${corrected.startMs} à ${corrected.endMs} ms. La vidéo reste en pause.", false)
    }

    private fun updateCaptionOverride() {
        val override = !features.timeline.identity || translation.displayMode != TranslationDisplayMode.ORIGINAL
        val next = if (!override) emptyList() else {
            val position = player.currentPosition.coerceAtLeast(0)
            val sourceTime = features.timeline.sourceTimeMs(position)
            val items = if (features.timeline.identity) ahead.activeItems(selectedTextId(), sourceTime * 1000)
                else { ensureTimedSchedule(selectedTextId()); (displaySchedule?.activeIndices(position) ?: IntArray(0)).map { timedItems[it].item } }
            items.mapNotNull { item -> translation.displayTextFor(item)?.takeIf { it.isNotBlank() } }
                .map { Cue.Builder().setText(it).build() }
        }
        if (override != usesCaptionOverride || next.map { it.text.toString() } != displayedCues.map { it.text.toString() }) {
            usesCaptionOverride = override; displayedCues = next
            captionSink?.invoke(next, override)
        }
    }

    /** Dispatch every crossed start/end boundary, including cues shorter than a tick. */
    private fun driveIndexedCues() {
        val track = selectedTextId() ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        if (timingTrack != track) { timingTrack = track; timedCursorMs = null; timedIndex = null }
        val previous = timedCursorMs
        ensureTimedSchedule(track)
        if (previous == null || position < previous) {
            timedCursorMs = position
            if (!dispatchTimedAt(position, track)) timedCursorMs = null
        } else if (position > previous) {
            val boundaries = timedSchedule?.crossed(previous, position).orEmpty()
            for (boundary in boundaries) {
                if (!dispatchTimedAt(boundary, track)) {
                    // The renderer may have advanced a fraction beyond the first
                    // missing translation. Keep its exact boundary for replay.
                    if (player.currentPosition > boundary) player.seekTo(boundary)
                    timedCursorMs = null; updateCaptionOverride(); return
                }
            }
            timedCursorMs = position
        }
        updateCaptionOverride()
    }
    private fun dispatchTimedAt(position: Long, track: String): Boolean {
        val sourceMs = features.timeline.sourceTimeMs(position, narration = true)
        if (!translation.onPlaybackPosition(sourceMs * 1000)) return false
        val items = (timedSchedule?.activeIndices(position) ?: IntArray(0)).map { timedItems[it] }
        features.register(items.map { it.item })
        val translated = items.map { translation.textFor(it.item) }
        if (translated.any { it == null }) { pause(); onMessage("Lecture en attente de la traduction de cette réplique.", false); return false }
        val pairs = items.zip(translated.filterNotNull()).map { (item, text) -> item to SpeechTools.filter(text, prefs.filters) }
        machine.markFiltered(pairs.filter { it.second.isBlank() }.map { it.first.item.identity })
        val audible = pairs.filter { it.second.isNotBlank() }
        lastRawCaption = translated.filterNotNull().joinToString("\n"); onCaption(lastRawCaption)
        apply(machine.cues(position * 1000, audible.map { it.second }, false,
            audible.map { it.first.item.identity }, audible.map { it.first.narration.endMs + prefs.allowedLagMs }))
        return true
    }

    private fun ensureTimedSchedule(track: String?) {
        if (timedIndex === ahead && timedIndexCount == ahead.count && timedMap == features.timeline && timingTrack == track) return
        timedIndex = ahead; timedIndexCount = ahead.count; timedMap = features.timeline; timingTrack = track
        timedItems = ahead.selected(track).map { item ->
            val start = item.startUs / 1000
            val end = (item.endUs / 1000).coerceAtLeast(start + 1)
            TimedItem(item, features.timeline.mapInterval(start, end), features.timeline.mapInterval(start, end, narration = true))
        }
        timedSchedule = fr.soustitresvoix.core.SubtitleBoundarySchedule(timedItems.map { it.narration })
        displaySchedule = fr.soustitresvoix.core.SubtitleBoundarySchedule(timedItems.map { it.display })
    }

    /** Timing corrections may place the final narration beyond the last video
     * frame. Finish those source occurrences with the picture held at its end. */
    private fun drainNarrationAfterVideoEnd(): Boolean {
        val token = ++endDrainCallbackToken
        val track = selectedTextId() ?: run { endDrainComplete = true; return true }
        ensureTimedSchedule(track)
        val from = endDrainCursorMs ?: player.currentPosition.coerceAtLeast(0)
        if (!dispatchTimedAt(from, track)) return false
        // Bound each turn of the UI loop. A long positive correction can place a
        // complete track after the final frame; source size must not freeze Pause.
        val boundaries = timedSchedule?.crossed(from, fr.soustitresvoix.core.SubtitleTimeline.MAX_TIME_MS, 129).orEmpty()
        for (boundary in boundaries.take(128)) {
            if (!dispatchTimedAt(boundary, track)) {
                endDrainCursorMs = boundary - 1
                onMessage("Fin de l’image : des répliques restent à vocaliser. Attends leur traduction puis appuie sur Reprendre.", false)
                return false
            }
            endDrainCursorMs = boundary
        }
        if (boundaries.size > 128) {
            val epoch = mediaEpoch
            val seekTicket = seekSequence
            val completionTicket = completionSequence
            val timeline = features.timeline
            if (machine.snapshot().wantsPlayback) handler.post {
                if (!closed && token == endDrainCallbackToken && epoch == mediaEpoch && seekTicket == seekSequence &&
                    completionTicket == completionSequence && timeline == features.timeline && track == selectedTextId() &&
                    player.playbackState == Player.STATE_ENDED && machine.snapshot().wantsPlayback && !endDrainComplete)
                    drainNarrationAfterVideoEnd()
            }
            return true
        }
        endDrainComplete = true
        apply(machine.cues(Long.MAX_VALUE, emptyList()))
        if (machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0 && machine.snapshot().recoverableCount == 0) pause()
        return true
    }
}
