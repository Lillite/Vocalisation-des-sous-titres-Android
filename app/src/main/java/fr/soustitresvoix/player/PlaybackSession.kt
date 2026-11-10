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
import androidx.media3.common.util.Util
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
    private var narrationStarts = LongArray(0)
    private var preparationOrder: List<TimedItem> = emptyList()
    private data class TimedItem(val item: CueAheadIndex.Item, val display: fr.soustitresvoix.core.SubtitleTimeline.Interval,
        val narration: fr.soustitresvoix.core.SubtitleTimeline.Interval)
    private var timedItems: List<TimedItem> = emptyList()
    private var endDrainCursorMs: Long? = null
    private var endDrainComplete = true
    private var endDrainCallbackToken = 0L
    private var endCoverageReported = false
    private var completionSequence = 0L
    private var phraseEndUtterance: String? = null
    private var rearmingCheckpoint = false
    private var deferredPlay: Boolean? = null
    private var progressivePassStartMs = 0L
    private var progressiveReconciledCount = -1
    private var networkSource = false
    private var progressiveNetworkSource = false
    private var adaptiveNetworkSource = false
    private var networkIndexer: SubtitleIndexer? = null
    private var networkIndexTrack: String? = null
    private var networkIndexThroughUs = 0L
    private var indexedTrack: String? = null
    val hasCompleteSubtitleIndex: Boolean get() = indexedTrack?.let {
        !ahead.truncated && it == selectedTextId() && ahead.hasTrack(it)
    } == true
    val waitingForNetworkSubtitles: Boolean get() = !closed && adaptiveNetworkSource &&
        networkIndexTrack != null && networkIndexTrack == selectedTextId() && !hasCompleteSubtitleIndex &&
        machine.snapshot().wantsPlayback && player.currentPosition >= maxOf(progressivePassStartMs, networkIndexThroughUs / 1000)
    private val indexingWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var indexer: SubtitleIndexer? = null
    private var indexingForPlayback = false
    private var indexingStatusEpoch = 0L
    var indexingStatus = "Indexation complète non demandée"
        private set(value) { field = value; indexingStatusEpoch = mediaEpoch }
    private var manualPreparation = false
    private var thermalPreparationStopped = false
    val isPreparing: Boolean get() = indexer != null || networkIndexer != null || speech.bulkPreparation || manualPreparation || translation.busy
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
            val indexed = usesIndexedNarration()
            val activePicture = player.playbackState != Player.STATE_ENDED
            if (indexed && activePicture) driveIndexedCues() else updateCaptionOverride()
            if (machine.snapshot().wantsPlayback) {
                // The indexed clock must visit the boundaries in order before
                // asking translation about a later picture position.
                if (!indexed && activePicture) {
                    val position = player.currentPosition
                    if (translation.onPlaybackPosition(features.timeline.sourceTimeMs(position, narration = true) * 1000))
                        features.onTimelinePosition(position)
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
    internal var supplementaryTextId: String? = null; private set
    var media: SavedMedia? = null; private set
    private var focusHeld = false
    val speech: SpeechService = components.createSpeech(context, prefs,
        onAvailability = { ready, msg ->
            if (!closed) {
                if (!ready && prefs.narration) {
                    translation.playbackInterrupted()
                    completionSequence++
                    finishPlaybackBenchmark("Voix devenue indisponible : essai interrompu")
                }
                apply(machine.setSpeechReady(ready)); onMessage(msg, !ready && prefs.narration); onChanged()
            }
        },
        onDone = { id -> if (!closed && machine.snapshot().speakingId == id) {
            val duration = if (phraseEndUtterance == id && machine.snapshot().wantsPlayback)
                prefs.int("comprehensionPauseMs", 0, 0, 3000) else 0
            phraseEndUtterance = null
            if (duration > 0) apply(machine.setComprehensionHold(true))
            // Acknowledge the actual voice end now. The delayed action owns
            // only the comprehension pause, never this completed segment.
            apply(machine.speechFinished(id))
            if (duration == 0) finishEndNarrationIfReady() else {
                val ticket = ++completionSequence
                val epoch = mediaEpoch
                handler.postDelayed({
                    if (!closed && ticket == completionSequence && epoch == mediaEpoch) {
                        apply(machine.setComprehensionHold(false))
                        finishEndNarrationIfReady()
                    }
                }, duration.toLong())
            }
        } },
        onFailure = { id, msg -> if (!closed && machine.snapshot().speakingId == id) {
            translation.playbackInterrupted()
            phraseEndUtterance = null
            completionSequence++
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
            override fun onCues(cueGroup: CueGroup) { lastCue = cueGroup; if (!trackReset && !rearmingCheckpoint) forwardCues(cueGroup) }
            override fun onPlaybackStateChanged(state: Int) {
                apply(machine.setMediaReady(state == Player.STATE_READY || state == Player.STATE_ENDED))
                if (state == Player.STATE_READY) deferredPlay?.let { resumeVoice ->
                    deferredPlay = null
                    playPreparedMedia(resumeVoice)
                }
                if (state == Player.STATE_ENDED) {
                    if (adaptiveNetworkSource && networkIndexTrack == selectedTextId() && !hasCompleteSubtitleIndex) {
                        if (machine.snapshot().wantsPlayback) driveIndexedCues()
                        onChanged()
                        return
                    }
                    if (playbackBenchmark.active && playbackBenchmark.tick(benchmarkCounters())) {
                        saveBenchmarkReport()
                        pause()
                    }
                    else finishPlaybackBenchmark("Fin du fichier avant la durée cible de l’essai")
                    if (!endCoverageReported) onMessage("Fin de l’image.", false)
                    if (usesIndexedNarration()) {
                        if (endDrainCursorMs == null) endDrainCursorMs = timedCursorMs ?: player.currentPosition.coerceAtLeast(0)
                        endDrainComplete = false
                        if (machine.snapshot().wantsPlayback) drainNarrationAfterVideoEnd()
                    } else {
                        apply(machine.cues(Long.MAX_VALUE, emptyList()))
                    }
                    finishEndNarrationIfReady()
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
                val previousInventoryTrack = indexedTrack
                if (!trackReset && previousInventoryTrack != null && previousInventoryTrack != selectedTextId()) {
                    // A renderer can change its selected track without the UI
                    // command. Stop the old queue before the journal is rebound.
                    translation.playbackInterrupted()
                    completionSequence++
                    indexedTrack = null; indexedSource = null; timedCursorMs = null
                    endDrainCursorMs = null; endDrainComplete = true; endCoverageReported = false
                    apply(machine.reset(false))
                    apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
                }
                translation.observeTrack()
                features.tracksAvailable(tracks)
                features.updateJournalContext()
                onChanged()
            }
            override fun onPlayerError(error: PlaybackException) {
                deferredPlay = null
                cancelIndexing()
                translation.playbackInterrupted(); completionSequence++
                progressiveReconciledCount = -1
                finishPlaybackBenchmark("Erreur de lecture : ${error.errorCodeName}")
                apply(machine.fail("Lecture impossible (${error.errorCodeName}). Le fichier, son accès ou son codec n’est peut-être pas pris en charge. Aucun fichier n’a été modifié."))
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
                    media?.let { onMediaAccessFailure?.invoke(it) }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT || trackReset) return
                finishPlaybackBenchmark("Discontinuité de lecture : essai interrompu")
                val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                    oldPosition.mediaItem?.mediaId?.let { it == newPosition.mediaItem?.mediaId } == true
                if (sameItem && newPosition.positionMs > oldPosition.positionMs) {
                    // Keep admitted speech and the verified cursor. The next
                    // tick covers the skipped boundaries when an index exists.
                    features.markUnobservedDiscontinuity(oldPosition.positionMs, newPosition.positionMs)
                    return
                }
                translation.playbackInterrupted(); completionSequence++
                progressivePassStartMs = newPosition.positionMs.coerceAtLeast(0)
                progressiveReconciledCount = -1
                timedCursorMs = null; endDrainCursorMs = null; endDrainComplete = true; endCoverageReported = false
                apply(machine.reset(keepPlaybackIntent = true))
                apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
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
        cancelIndexing(); indexedSource = null; indexedTrack = null; supplementaryTextId = null; visual.reset()
        networkIndexTrack = null; networkIndexThroughUs = 0L
        imageViewport = emptyMap()
        completionSequence++; phraseEndUtterance = null; timedCursorMs = null; timingTrack = null
        endDrainCursorMs = null; endDrainComplete = true; endCoverageReported = false; deferredPlay = null
        speech.clearOfflineSelection()
        val restored = features.opening(saved, restoreSession)
        val savedUri = Uri.parse(restored.uri)
        val sourceUri = if (savedUri.scheme == RemoteMediaUris.DIRECT_SCHEME)
            runCatching { Uri.parse(RemoteConnectionStore(context).directUrl(savedUri)) }.getOrNull() else savedUri
        networkSource = savedUri.scheme in setOf("http", "https", "rtsp", "rtsps", RemoteMediaUris.SERVER_SCHEME, RemoteMediaUris.DIRECT_SCHEME)
        progressiveNetworkSource = networkSource && sourceUri != null && Util.inferContentType(sourceUri) == C.CONTENT_TYPE_OTHER
        adaptiveNetworkSource = networkSource && sourceUri != null &&
            Util.inferContentType(sourceUri) in setOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH)
        progressivePassStartMs = restored.positionMs.coerceAtLeast(0)
        progressiveReconciledCount = -1
        val epoch = ++mediaEpoch; trackSelectionSequence++; trackReset = false
        lastRawCaption = ""; ahead.clear(); speech.cancelPreparation()
        val diagnosticToken = diagnosticObserver.beginMedia()
        apply(machine.reset(false)); lastCue = null; onCaption("")
        media = restored
        val item = MediaItem.Builder().setUri(Uri.parse(restored.uri)).setMediaId(restored.uri).setTag(diagnosticToken)
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
            if (!closed && epoch == mediaEpoch && !hasCompleteSubtitleIndex && !adaptiveNetworkSource) {
                val previousCount = ahead.count
                ahead.add(track, cues, origins)
                if (usesProgressiveSubtitleIndex()) {
                    if (player.playbackState != Player.STATE_ENDED) driveIndexedCues()
                    else if (ahead.count != previousCount) {
                        endDrainComplete = false; endCoverageReported = false
                        if (machine.snapshot().wantsPlayback) {
                            driveIndexedCues()
                            drainNarrationAfterVideoEnd()
                        } else {
                            features.register(ahead.selected(selectedTextId()))
                            onMessage("Des répliques supplémentaires ont été reçues en fin de vidéo. Appuie sur Lire pour les terminer.", false)
                        }
                    }
                }
            }
        } }
        playback.setMediaSource(source, restored.positionMs)
        player.prepare(); prefs.save(restored)
        speech.applyPreferences(); apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration))
        updateCaptionOverride(); onChanged()
    }
    fun play() {
        if (media == null) { onMessage("Ouvre d’abord une vidéo, ou lance l’essai intégré.", false); return }
        if (!features.recoverCheckpointBeforePlay()) return
        playPreparedMedia()
    }

    private fun playPreparedMedia(resumeVoice: Boolean = false) {
        if (trackReset || closed || features.hasPendingNarrationRestoration) return
        if (prefs.narration && prefs.bool("integralReading", true) &&
            player.playbackState == Player.STATE_BUFFERING && player.currentTracks.groups.isEmpty()) {
            deferredPlay = resumeVoice
            return
        }
        val narration = machine.snapshot()
        if (player.playbackState == Player.STATE_ENDED && (!endDrainComplete || narration.speakingId != null ||
                narration.pendingCount > 0 || narration.recoverableCount > 0)) {
            if (!translation.allowNarrationVoice()) return
            if (!requestFocus()) return
            apply(machine.setMediaReady(true)); apply(if (resumeVoice) machine.resumeVoice() else machine.play())
            if (usesProgressiveSubtitleIndex() && machine.snapshot().wantsPlayback) driveIndexedCues()
            if (!endDrainComplete && machine.snapshot().wantsPlayback) drainNarrationAfterVideoEnd()
            return
        }
        translation.onPlaybackPosition(features.timeline.sourceTimeMs(player.currentPosition, narration = true) * 1000)
        if (!translation.allowPlayback()) return
        val verify = requiresVerifiedInventory() && !usesNetworkSubtitlePlayback()
        if (!hasCompleteSubtitleIndex && (verify || supplementaryTextId != null || !features.timeline.identity)) {
            // The scan is owned by this Play request. Pause, seek, source or
            // track changes cancel it and invalidate its continuation.
            if (indexer == null || !indexingForPlayback) ensureCompleteIndex(forPlayback = true) { playPreparedMedia(resumeVoice) }
            return
        }
        if (!speech.offlinePreparationActive)
            interruptPreparation("Préparation arrêtée : reprise de la lecture. Le cache existant est conservé.")
        if (!requestFocus()) return
        if (player.playbackState == Player.STATE_ENDED) seek(0, userInitiated = false)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (requiresVerifiedInventory() && usesNetworkSubtitlePlayback() && adaptiveNetworkSource && !hasCompleteSubtitleIndex)
            startNetworkSubtitleIndex()
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        if (usesIndexedNarration() && !machine.snapshot().wantsPlayback) apply(machine.setScheduleHold(true))
        apply(if (resumeVoice) machine.resumeVoice() else machine.play())
        if (usesIndexedNarration() && machine.snapshot().wantsPlayback) driveIndexedCues()
    }

    private fun requiresVerifiedInventory(): Boolean =
        prefs.narration && prefs.bool("integralReading", true) && selectedTextId() != null &&
            player.duration > 0 && !player.isCurrentMediaItemLive && !player.isCurrentMediaItemDynamic &&
            selectedSubtitleFormat()?.let(::isBitmapSubtitle) != true

    /** Network demux already supplies timed source occurrences before rendering.
     * Use those boundaries as they arrive instead of downloading the whole movie
     * in a second scanner before Play. Full preparation remains an explicit scan. */
    private fun usesProgressiveSubtitleIndex(): Boolean =
        requiresVerifiedInventory() && !hasCompleteSubtitleIndex && usesNetworkSubtitlePlayback() &&
            (progressiveNetworkSource || adaptiveNetworkSource && networkIndexTrack == selectedTextId())

    private fun usesNetworkSubtitlePlayback(): Boolean =
        networkSource && !translation.required && supplementaryTextId == null && features.timeline.identity && !hasAttachedSubtitle

    private fun usesIndexedNarration(): Boolean = hasCompleteSubtitleIndex || usesProgressiveSubtitleIndex()

    private fun startNetworkSubtitleIndex() {
        if (networkIndexer != null) return
        val saved = media ?: return
        val track = selectedTextId() ?: return
        if (networkIndexTrack != track) {
            networkIndexTrack = track
            networkIndexThroughUs = 0L
        }
        val epoch = mediaEpoch
        val task = subtitleSource.createIndexer()
        networkIndexer = task
        indexingStatus = "Chargement progressif des segments de sous-titres"
        indexingWorker.execute {
            try {
                val index = task.scanTrackProgressively(saved.copy(subtitlePath = null, subtitleMime = null), track,
                    onPrefix = { items, throughUs -> handler.post {
                        if (!closed && epoch == mediaEpoch && networkIndexer === task && selectedTextId() == track) {
                            items.forEach(ahead::addOccurrence)
                            networkIndexThroughUs = maxOf(networkIndexThroughUs, throughUs)
                            indexingStatus = "Sous-titres vérifiés jusqu’à ${networkIndexThroughUs / 1_000_000} s"
                            features.register(items)
                            if (machine.snapshot().wantsPlayback) driveIndexedCues()
                            onChanged()
                        }
                    } }, progress = {})
                handler.post {
                    if (!closed && epoch == mediaEpoch && networkIndexer === task && selectedTextId() == track) {
                        networkIndexer = null
                        if (index.truncated || !index.hasTrack(track)) {
                            apply(machine.fail("L’inventaire réseau est incomplet. La vidéo attend pour conserver toutes les répliques."))
                            return@post
                        }
                        ahead = index; indexedTrack = track
                        indexedSource = IndexSource(saved.uri, null, null)
                        features.register(index.selected(track))
                        indexingStatus = "Index complet : ${index.selected(track).size} occurrences"
                        if (player.playbackState == Player.STATE_ENDED) {
                            endDrainCursorMs = timedCursorMs ?: player.currentPosition.coerceAtLeast(0)
                            endDrainComplete = false
                            if (machine.snapshot().wantsPlayback) drainNarrationAfterVideoEnd()
                        } else if (machine.snapshot().wantsPlayback) driveIndexedCues()
                        onChanged()
                    }
                }
            } catch (error: Exception) { handler.post {
                if (!closed && epoch == mediaEpoch && networkIndexer === task) {
                    networkIndexer = null
                    indexingStatus = subtitleSource.safeFailureMessage(error)
                    apply(machine.fail(indexingStatus))
                }
            } }
        }
    }
    internal fun rearmNarrationCheckpoint(positionMs: Long, restorePending: (() -> Boolean)? = null): Boolean {
        val track = selectedTextId() ?: return false
        rearmingCheckpoint = true
        try {
            if (restorePending == null) seek(positionMs, userInitiated = false)
            else if (positionMs != player.currentPosition || machine.snapshot().wantsPlayback) return false
            ensureTimedSchedule(track)
            // Restore an exact saved queue before accepting renderer or index
            // events. Older sessions recover from the verified source boundary.
            if (!(restorePending?.invoke() ?: dispatchTimedAt(positionMs))) return false
        } finally { rearmingCheckpoint = false }
        timedCursorMs = positionMs
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (player.playbackState == Player.STATE_ENDED || (duration != null && positionMs >= duration)) {
            endDrainCursorMs = positionMs
            endDrainComplete = false
            endCoverageReported = false
        }
        return true
    }
    internal fun resumeFromNarrationCheckpoint(positionMs: Long, restorePending: (() -> Boolean)? = null): Boolean {
        if (!rearmNarrationCheckpoint(positionMs, restorePending)) return false
        if (!endDrainComplete) {
            // The picture stays at EOF while a positive correction finishes speech.
            if (!translation.allowNarrationVoice() || !requestFocus()) return false
            apply(machine.setMediaReady(true)); apply(machine.play())
            drainNarrationAfterVideoEnd()
        } else playPreparedMedia()
        return true
    }
    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onMessage("L’accès au son est refusé pour le moment. Termine l’appel ou la lecture dans l’autre application.", true); return false
        }
        focusHeld = true; return true
    }

    internal fun requestNarrationReplayFocus(): Boolean = translation.allowNarrationVoice() && requestFocus()
    fun pause() {
        translation.playbackInterrupted()
        features.cancelCheckpointResume()
        pauseForIndexing()
    }
    /** Only the picture waits. Already accepted speech retains its audio focus
     * and can finish even if rewinding the picture temporarily buffers Media3. */
    internal fun pauseForTranslationBuffer(sourcePositionUs: Long, resumable: Boolean) {
        if (resumable && machine.snapshot().translationHeld) return
        if (resumable) apply(machine.setTranslationHold(true)) else pause()
        if (sourcePositionUs == Long.MAX_VALUE) return
        val sourceMs = (sourcePositionUs / 1000).coerceAtLeast(0)
        val boundary = features.timeline.mapInterval(sourceMs, sourceMs + 1, narration = true).startMs
        if (player.currentPosition > boundary) player.seekTo(boundary)
        savePosition()
    }

    internal fun resumeAfterTranslationBuffer() {
        val state = machine.snapshot()
        if (closed || !state.translationHeld || !state.wantsPlayback) return
        if (!translation.allowNarrationVoice() || !requestFocus()) { pause(); return }
        if (hasCompleteSubtitleIndex) apply(machine.setScheduleHold(true))
        apply(machine.setTranslationHold(false))
        if (player.playbackState == Player.STATE_ENDED && !endDrainComplete) drainNarrationAfterVideoEnd()
        else if (hasCompleteSubtitleIndex) driveIndexedCues()
    }
    private fun pauseForIndexing() {
        deferredPlay = null
        progressiveReconciledCount = -1
        completionSequence++
        finishPlaybackBenchmark("Pause demandée ou application quittée")
        interruptPreparation("Préparation arrêtée : lecture mise en pause. Le cache existant est conservé.")
        apply(machine.pause())
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        if (holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
        savePosition()
    }
    fun seek(positionMs: Long) = seek(positionMs, userInitiated = true)

    private fun seek(positionMs: Long, userInitiated: Boolean) {
        deferredPlay = null
        translation.playbackInterrupted()
        features.cancelCheckpointResume(abandon = true)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        progressivePassStartMs = position
        progressiveReconciledCount = -1
        val restart = !rearmingCheckpoint && !machine.snapshot().translationHeld
        if (userInitiated) features.markSeek(player.currentPosition.coerceAtLeast(0), position)
        completionSequence++; phraseEndUtterance = null; timedCursorMs = null
        endDrainCursorMs = null; endDrainComplete = true; endCoverageReported = false
        seekSequence++
        finishPlaybackBenchmark("Position de lecture modifiée : extrait non comparable")
        interruptPreparation("Préparation arrêtée : position de lecture modifiée. Le cache existant est conservé.")
        apply(machine.reset(keepPlaybackIntent = restart)); lastCue = null; onCaption("")
        if (usesIndexedNarration() && machine.snapshot().wantsPlayback) apply(machine.setScheduleHold(true))
        player.seekTo(position); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        if (machine.snapshot().wantsPlayback && adaptiveNetworkSource && usesNetworkSubtitlePlayback() && !hasCompleteSubtitleIndex)
            startNetworkSubtitleIndex()
        if (usesIndexedNarration() && machine.snapshot().wantsPlayback && player.playbackState != Player.STATE_ENDED)
            driveIndexedCues()
    }
    fun resumeVoice() {
        if (media != null && features.recoverCheckpointBeforePlay()) playPreparedMedia(resumeVoice = true)
    }
    private fun selectedSubtitleFormat(): Format? {
        for (group in player.currentTracks.groups) if (group.type == C.TRACK_TYPE_TEXT)
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        return null
    }
    internal fun primaryTextId(): String? = selectedSubtitleFormat()?.let(::subtitleIndexId)

    internal fun selectedTextId(): String? {
        val primary = primaryTextId() ?: return null
        val supplementary = supplementaryTextId ?: return primary
        return CueAheadIndex.combinedTrackId(primary, supplementary)
    }

    internal fun restoreSupplementarySubtitleTrack(track: String): Boolean {
        if (selectedSubtitleFormat()?.let(::isBitmapSubtitle) == true) return false
        val primary = primaryTextId() ?: return false
        if (primary == track || primary == "external-subtitle" || track == "external-subtitle") return false
        if (player.currentTracks.groups.none { group -> group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any {
                val format = group.getTrackFormat(it)
                group.isTrackSupported(it) && !isBitmapSubtitle(format) && subtitleIndexId(format) == track
            } }) return false
        supplementaryTextId = track
        return true
    }

    internal fun setSupplementarySubtitleTrack(track: String?) {
        if (closed || track == supplementaryTextId) return
        pause()
        if (track == null) supplementaryTextId = null
        else if (!restoreSupplementarySubtitleTrack(track)) {
            onMessage("Choisis deux pistes de sous-titres intégrées distinctes.", true); return
        }
        translation.trackChanged()
        indexedTrack = null; indexedSource = null
        invalidateTimedNarration()
        features.updateJournalContext()
        ensureCompleteIndex { onMessage("Sélection de sous-titres prête. Appuie sur Lire.", false) }
    }

    internal fun subtitleTrackPair(): Pair<String, String>? {
        val primary = primaryTextId() ?: return null
        val supplementary = supplementaryTextId ?: return null
        return primary to supplementary
    }

    internal fun currentSubtitleBitmaps(): List<android.graphics.Bitmap> =
        lastCue?.cues.orEmpty().mapNotNull { it.bitmap }
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
        // A mandatory index scan owns an explicit Play or recovery request.
        // Thermal throttling of speculative preparation must not cancel it.
        if ((indexer != null || networkIndexer != null) && !manualPreparation) return
        if (stopPreparationForHeat()) return
        if (manualPreparation) { if (indexer == null && !speech.bulkPreparation) manualPreparation=false; return }
        if (!prefs.narration) { cancelIndexing(); speech.cancelPreparation(); return }
        if (!machine.snapshot().wantsPlayback) return
        val position = player.currentPosition
        val future = if (hasCompleteSubtitleIndex) indexedPreparation(position) else
            ahead.future(selectedTextId(), features.timeline.sourceTimeMs(position, narration = true), prefs.filters,
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
        val selected = selectedTextId() ?: run {
            manualPreparation = false
            onMessage("La piste sélectionnée n’a pas d’identité exploitable.", true); return
        }
        val tracks = subtitleTrackPair()
        val scanTracks = tracks?.let { setOf(it.first, it.second) } ?: setOf(selected)
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
        if (indexedSource == origin && hasCompleteSubtitleIndex) { prepare(ahead); return }
        indexer?.cancel()
        val task = subtitleSource.createIndexer(); indexer = task
        indexingStatus="Indexation complète en cours ; aucun décodage vidéo pour cet index"
        indexingWorker.execute {
            try {
                val index = task.scanTracks(source, scanTracks) { percent -> handler.post {
                    if (!closed && epoch == mediaEpoch && indexer === task) indexingStatus = "Indexation ${if (percent >= 0) "$percent %" else "en cours"}"
                } }
                tracks?.let { index.combinedTrack(it.first, it.second) }
                handler.post {
                    if (closed || epoch != mediaEpoch || indexer !== task || selected != selectedTextId()) return@post
                    indexer = null
                    if (index.truncated || !index.hasTrack(selected)) {
                        manualPreparation = false
                        indexingStatus = "Aucun index textuel complet pour la piste sélectionnée."
                        onMessage(indexingStatus, true); return@post
                    }
                    ahead = index; indexedSource = origin; indexedTrack = selected; timedCursorMs = null
                    features.register(index.selected(selected))
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
    fun cancelIndexing() {
        indexer?.cancel(); indexer = null
        networkIndexer?.cancel(); networkIndexer = null
        manualPreparation = false; indexingForPlayback = false
    }
    fun cancelPreparation() { cancelIndexing(); speech.cancelPreparation(); indexingStatus="Préparation annulée ; lecture et cache existant conservés" }
    private fun interruptPreparation(message: String) {
        // Translation owns a separate worker and explicit cancellation control.
        val wasPreparing = indexer != null || networkIndexer != null || speech.bulkPreparation || manualPreparation
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
        if (requiresVerifiedInventory() && !hasCompleteSubtitleIndex) {
            if (indexer == null) ensureCompleteIndex { startPlaybackBenchmark(seconds) }
            return
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
        apply(machine.setMediaReady(true))
        if (hasCompleteSubtitleIndex) apply(machine.setScheduleHold(true))
        apply(machine.resumeVoice())
        if (hasCompleteSubtitleIndex && machine.snapshot().wantsPlayback) driveIndexedCues()
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
            "${narrationReadiness()}\n" +
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
    internal fun previewReviewedSubtitle(text: String) {
        if (closed || text.isBlank()) return
        pause()
        if (requestFocus()) apply(machine.preview(text))
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
        if (features.hasPendingNarrationRestoration) return
        if (usesIndexedNarration()) {
            if (player.playbackState != Player.STATE_ENDED) driveIndexedCues()
            return
        }
        // A combined selection is only interpretable after both tracks have
        // been indexed. The primary renderer alone cannot represent it.
        if (supplementaryTextId != null) return
        val captions = group.cues.mapNotNull { it.text?.toString() }
        if (!translation.required && requiresVerifiedInventory() && !usesNetworkSubtitlePlayback()) {
            // These are visible cues, not a verified source queue. Starting
            // with an indexed clock avoids replaying provisional local IDs.
            lastRawCaption = captions.joinToString("\n")
            onCaption(lastRawCaption)
            return
        }
        if (translation.required) {
            if (!translation.onPlaybackPosition(group.presentationTimeUs)) {
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
        val selectedSubtitle = selectedTextId()
        pause()
        features.cancelCheckpointResume(abandon = type == C.TRACK_TYPE_TEXT)
        if (type == C.TRACK_TYPE_TEXT) {
            supplementaryTextId = null
            translation.trackChanged(); selectExternalWhenAvailable = false
            indexedTrack = null; indexedSource = null; timedCursorMs = null
            endDrainCursorMs = null; endDrainComplete = true; endCoverageReported = false
            apply(machine.reset(false)); lastCue = null; onCaption("")
        }
        // An audio-only switch may pause, but it does not discard source
        // occurrences whose text has already disappeared from the screen.
        trackReset = true
        val ticket = ++trackSelectionSequence
        val epoch = mediaEpoch
        val positionTicket = seekSequence
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, disabled)
        if (group != null) params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        player.trackSelectionParameters = params.build()
        handler.post {
            if (!closed && epoch == mediaEpoch && ticket == trackSelectionSequence) {
                trackReset = false
                if (positionTicket != seekSequence) return@post
                if (type == C.TRACK_TYPE_AUDIO && selectedSubtitle != selectedTextId()) {
                    indexedTrack = null; indexedSource = null; timedCursorMs = null
                    apply(machine.reset(false)); lastCue = null; onCaption("")
                }
                if (type == C.TRACK_TYPE_TEXT) player.seekTo(player.currentPosition)
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

    internal fun renameMediaTitle(title: String) {
        val current = media ?: return
        val clean = title.trim().take(200)
        if (closed || clean.isBlank() || current.title == clean) return
        media = current.copy(title = clean)
        savePosition(); onChanged()
    }
    private fun apply(effects: List<NarrationMachine.Effect>) {
        if (closed) return
        for (effect in effects) when (effect) {
            is NarrationMachine.Effect.Speak -> {
                phraseEndUtterance = effect.id.takeIf { effect.remainingText.isBlank() && !effect.preview }
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
            NarrationMachine.Effect.StopSpeech -> { phraseEndUtterance = null; speech.stop() }
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
        val gap = nextNarrationStartMs(player.currentPosition)?.let {
            ((it - player.currentPosition) / player.playbackParameters.speed.toDouble()).toLong()
        }
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
        completionSequence++; phraseEndUtterance = null; timedCursorMs = null
        finishPlaybackBenchmark("Source de vocalisation modifiée")
        speech.cancelPreparation(); apply(machine.reset(false)); lastCue = null; lastRawCaption = ""; onCaption("")
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    internal fun installTranslationIndex(index: CueAheadIndex) {
        ahead = index
        indexedTrack = selectedTextId()?.takeIf { !index.truncated && index.hasTrack(it) }
        features.register(index.selected(selectedTextId()))
        updateCaptionOverride()
        rearmCurrentSubtitles()
    }
    internal fun rearmCurrentSubtitles() {
        // A seek to the same position does not reliably re-emit an already visible
        // cue. Queue its current occurrences while still paused; this emits no speech.
        val current = player.currentCues
        lastCue = current
        if (!trackReset && !rearmingCheckpoint) forwardCues(current)
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
    fun relocateSessionTo(uri: String, title: String, allowUnverifiedMetadata: Boolean = false): Boolean =
        features.relocate(uri, title, allowUnverifiedMetadata)
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
    internal fun ensureCompleteIndex(forPlayback: Boolean = false, ready: () -> Unit) {
        val saved = media ?: run { onMessage("Ouvre d’abord une vidéo.", false); return }
        val selected = selectedTextId() ?: run { onMessage("Choisis d’abord une piste de sous-titres textuelle.", true); return }
        val source = if (selected == "external-subtitle") saved else saved.copy(subtitlePath = null, subtitleMime = null)
        val origin = IndexSource(source.uri, source.subtitlePath, source.subtitleMime)
        if (hasCompleteSubtitleIndex) { ready(); return }
        pauseForIndexing(); cancelIndexing()
        val epoch = mediaEpoch
        val tracks = subtitleTrackPair()
        val scanTracks = tracks?.let { setOf(it.first, it.second) } ?: setOf(selected)
        val task = subtitleSource.createIndexer(); indexer = task
        indexingForPlayback = forPlayback
        indexingStatus = "Indexation complète de la piste choisie en cours"
        onMessage(if (forPlayback) "$indexingStatus. La lecture attend cette préparation." else indexingStatus, false)
        onChanged()
        indexingWorker.execute {
            try {
                val index = task.scanTracks(source, scanTracks) { percent -> handler.post {
                    if (!closed && epoch == mediaEpoch && indexer === task) {
                        indexingStatus = "Indexation ${if (percent >= 0) "$percent %" else "en cours"}"
                        onChanged()
                    }
                } }
                tracks?.let { index.combinedTrack(it.first, it.second) }
                handler.post {
                    if (closed || epoch != mediaEpoch || indexer !== task) return@post
                    indexer = null
                    if (selected != selectedTextId()) {
                        indexingStatus = "La piste a changé pendant l’indexation. Appuie sur Lire pour vérifier la nouvelle sélection."
                        onMessage(indexingStatus, true); return@post
                    }
                    if (index.truncated || !index.hasTrack(selected)) {
                        indexingStatus = "Index incomplet : la préparation intégrale ne peut pas être certifiée."
                        onMessage(indexingStatus, true); return@post
                    }
                    ahead = index; indexedSource = origin; indexedTrack = selected; timedCursorMs = null
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
        completionSequence++; phraseEndUtterance = null; timedCursorMs = null
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
        if (trackReset || rearmingCheckpoint || features.hasPendingNarrationRestoration || machine.snapshot().translationHeld) return
        if (machine.snapshot().wantsPlayback && progressiveNetworkSource && usesProgressiveSubtitleIndex() &&
            (ahead.truncated || subtitleSource.hasIncompleteCapture())) {
            apply(machine.fail("La capture des sous-titres est incomplète. La vidéo est arrêtée pour conserver les répliques déjà reçues et éviter une omission silencieuse."))
            return
        }
        val track = selectedTextId() ?: return
        val picture = player.currentPosition.coerceAtLeast(0)
        val needsPrefix = adaptiveNetworkSource && networkIndexTrack == track && !hasCompleteSubtitleIndex
        val frontier = maxOf(progressivePassStartMs, networkIndexThroughUs / 1000)
        val position = if (needsPrefix) minOf(picture, frontier) else picture
        if (timingTrack != track) { timingTrack = track; timedCursorMs = null; timedIndex = null }
        val previous = timedCursorMs
        ensureTimedSchedule(track)
        val playing = machine.snapshot().wantsPlayback
        val waitingForPrefix = needsPrefix && picture >= frontier
        if (playing && waitingForPrefix) {
            apply(machine.setScheduleHold(true))
            if (picture > frontier) player.seekTo(frontier)
        }
        if (playing && usesProgressiveSubtitleIndex() && progressiveReconciledCount != ahead.count) {
            // A sample can reach the UI after its entire interval was crossed.
            // Recover it by source identity, including during a later Play after
            // Pause, while excluding intervals preceding the last explicit seek.
            val late = timedItems.filter {
                it.narration.startMs <= (previous ?: position) && it.narration.endMs > progressivePassStartMs
            }
            features.register(late.map { it.item })
            val texts = late.map { it.item.identity to SpeechTools.filter(it.item.raw, prefs.filters) }
            machine.markFiltered(texts.filter { it.second.isBlank() }.map { it.first })
            apply(machine.recoverMissedOccurrences(texts.filter { it.second.isNotBlank() }))
            progressiveReconciledCount = ahead.count
        }
        if (previous == null) {
            if (!dispatchTimedAt(position)) return
            timedCursorMs = position
        } else if (position > previous) {
            val boundaries = timedSchedule?.crossed(previous, position, INDEX_BATCH + 1).orEmpty()
            if (boundaries.size > INDEX_BATCH && playing) apply(machine.setScheduleHold(true))
            for (boundary in boundaries.take(INDEX_BATCH)) {
                if (!dispatchTimedAt(boundary)) {
                    // The cursor remains on the last accepted boundary; a
                    // translated prefix arriving later retries this one.
                    updateCaptionOverride(); return
                }
                timedCursorMs = boundary
                if (playing && !machine.snapshot().wantsPlayback) return
            }
            if (boundaries.size > INDEX_BATCH) return
            timedCursorMs = position
        }
        if (playing) timedCursorMs?.let(features::onTimelinePosition)
        if (machine.snapshot().scheduleHeld && !waitingForPrefix) apply(machine.setScheduleHold(false))
        updateCaptionOverride()
    }
    private fun dispatchTimedAt(position: Long): Boolean {
        val sourceMs = features.timeline.sourceTimeMs(position, narration = true)
        if (!translation.onPlaybackPosition(sourceMs * 1000)) return false
        val items = (timedSchedule?.activeIndices(position) ?: IntArray(0)).map { timedItems[it] }
        features.register(items.map { it.item })
        val translated = items.map { translation.textFor(it.item) }
        if (translated.any { it == null }) {
            // A rounded video millisecond can precede the exact source start.
            // Treat a missing progressive occurrence as a hold, not a mismatch.
            val missing = items.zip(translated).first { it.second == null }.first.item
            if (translation.progressiveEnabled && missing.startUs >= translation.nextMissingSourceUs)
                translation.onPlaybackPosition(missing.startUs)
            else translation.rejectUnmapped()
            return false
        }
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
        preparationOrder = timedItems.sortedBy { it.narration.startMs }
        narrationStarts = preparationOrder.asSequence().map { it.narration.startMs }.distinct().toList().toLongArray()
    }

    private fun upcomingIndexed(positionMs: Long): Sequence<TimedItem> {
        ensureTimedSchedule(selectedTextId())
        var low = 0
        var high = preparationOrder.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (preparationOrder[middle].narration.startMs < positionMs) low = middle + 1 else high = middle
        }
        return preparationOrder.subList(low, preparationOrder.size).asSequence()
    }

    private fun indexedPreparation(positionMs: Long): List<String> {
        val upcoming = upcomingIndexed(positionMs)
        val demand = upcoming.takeWhile { it.narration.startMs < positionMs + 15_000 }.take(16).sumOf {
            translation.textFor(it.item)?.let { text -> measuredOrEstimated(SpeechTools.filter(text, prefs.filters)) } ?: 0L
        }
        val density = (demand / 15_000.0).coerceIn(1.0, 2.0)
        val horizon = positionMs + (prefs.lookAheadSeconds * 1000 * density * player.playbackParameters.speed).toLong()
        return upcoming.takeWhile { it.narration.startMs < horizon }.map { translation.textFor(it.item) }
            .takeWhile { it != null }.filterNotNull()
            .flatMap { SpeechTools.segments(SpeechTools.filter(it, prefs.filters), speech.language).asSequence() }
            .distinct().take(prefs.lookAheadCount).toList()
    }

    private fun narrationReadiness(): String {
        if (usesProgressiveSubtitleIndex()) return "Lecture réseau : les occurrences sont suivies au fil du chargement. L’inventaire complet du film n’est pas encore vérifié."
        if (!hasCompleteSubtitleIndex) return "Couverture vocale : inventaire complet indisponible pour cette sélection."
        val position = player.currentPosition.coerceAtLeast(0)
        val horizon = position + prefs.lookAheadSeconds * 1000L
        val upcoming = upcomingIndexed(position).takeWhile { it.narration.startMs < horizon }
        val unavailable = upcoming.firstOrNull { translation.textFor(it.item) == null }
        val unmeasured = upcoming.firstOrNull { item ->
            val text = translation.textFor(item.item) ?: return@firstOrNull false
            SpeechTools.segments(SpeechTools.filter(text, prefs.filters), speech.language)
                .any { speech.measuredDurationMs(it) == null }
        }
        val offline = speech.offlineStatus
        return buildString {
            append("Inventaire textuel complet : ${timedItems.size} occurrences.")
            unavailable?.let { append(" Première traduction en attente à ${it.narration.startMs / 1000.0} s.") }
            if (unavailable == null && translation.required) append(" Traductions disponibles pour les occurrences de la fenêtre d’anticipation.")
            unmeasured?.let { append(" Première durée vocale estimée à ${it.narration.startMs / 1000.0} s.") }
            if (offline.ready) append(" Audio hors ligne vérifié jusqu’à ${offline.readyThroughMs / 1000.0} s.")
            else append(" Mesures de durée et préparation en mémoire ne certifient pas l’audio hors ligne.")
        }
    }

    private fun nextNarrationStartMs(positionMs: Long): Long? {
        val track = selectedTextId() ?: return null
        if (features.timeline.identity) return ahead.nextStart(track, positionMs)
        if (!hasCompleteSubtitleIndex) return null
        ensureTimedSchedule(track)
        val found = narrationStarts.binarySearch(positionMs)
        return narrationStarts.getOrNull(if (found >= 0) found + 1 else -found - 1)
    }

    /** Timing corrections may place the final narration beyond the last video
     * frame. Finish those source occurrences with the picture held at its end. */
    private fun drainNarrationAfterVideoEnd(): Boolean {
        val token = ++endDrainCallbackToken
        val track = selectedTextId() ?: run { endDrainComplete = true; return true }
        ensureTimedSchedule(track)
        apply(machine.setScheduleHold(true))
        val from = endDrainCursorMs ?: player.currentPosition.coerceAtLeast(0)
        if (!dispatchTimedAt(from)) return false
        // Bound each turn of the UI loop. A long positive correction can place a
        // complete track after the final frame; source size must not freeze Pause.
        val boundaries = timedSchedule?.crossed(from, fr.soustitresvoix.core.SubtitleTimeline.MAX_TIME_MS, INDEX_BATCH + 1).orEmpty()
        for (boundary in boundaries.take(INDEX_BATCH)) {
            if (!dispatchTimedAt(boundary)) {
                onMessage("Fin de l’image : des répliques restent à vocaliser. Attends leur traduction puis appuie sur Reprendre.", false)
                return false
            }
            endDrainCursorMs = boundary
            if (!machine.snapshot().wantsPlayback) return false
        }
        if (boundaries.size > INDEX_BATCH) {
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
        finishEndNarrationIfReady()
        return true
    }

    private fun finishEndNarrationIfReady() {
        if (player.playbackState != Player.STATE_ENDED || !endDrainComplete || endCoverageReported ||
            features.hasPendingNarrationRestoration) return
        val state = machine.snapshot()
        if (state.speakingId != null || state.pendingCount != 0 || state.recoverableCount != 0) return
        endCoverageReported = true
        pause()
        features.verifyNarrationEnd()
    }

    private companion object { const val INDEX_BATCH = 128 }
}
