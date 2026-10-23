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
    private val indexingWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var indexer: SubtitleIndexer? = null
    private var indexingStatusEpoch = 0L
    var indexingStatus = "Indexation complète non demandée"
        private set(value) { field = value; indexingStatusEpoch = mediaEpoch }
    private var manualPreparation = false
    private var thermalPreparationStopped = false
    val isPreparing: Boolean get() = indexer != null || speech.bulkPreparation || manualPreparation
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
            if (machine.snapshot().wantsPlayback) {
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
        onDone = { if (!closed) {
            apply(machine.speechFinished(it))
            if (player.playbackState == Player.STATE_ENDED && machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0) pause()
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
                    apply(machine.cues(Long.MAX_VALUE, emptyList()))
                    onMessage("Fin de la vidéo.", false)
                    if (machine.snapshot().speakingId == null && machine.snapshot().pendingCount == 0) pause()
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
                onChanged()
            }
            override fun onPlayerError(error: PlaybackException) {
                finishPlaybackBenchmark("Erreur de lecture : ${error.errorCodeName}")
                apply(machine.fail("Lecture impossible (${error.errorCodeName}). Le fichier, son accès ou son codec n’est peut-être pas pris en charge. Aucun fichier n’a été modifié."))
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
    fun open(saved: SavedMedia) {
        finishPlaybackBenchmark("Changement de vidéo ou de fichier de sous-titres")
        savePosition(); cancelIndexing(); indexedSource = null; visual.reset()
        val epoch = ++mediaEpoch; lastRawCaption = ""; ahead.clear(); speech.cancelPreparation()
        val diagnosticToken = diagnosticObserver.beginMedia()
        apply(machine.reset(false)); lastCue = null; onCaption("")
        media = saved
        val item = MediaItem.Builder().setUri(Uri.parse(saved.uri)).setTag(diagnosticToken)
        val path = saved.subtitlePath
        hasAttachedSubtitle = path != null && saved.subtitleMime != null && File(path).isFile
        selectExternalWhenAvailable = hasAttachedSubtitle
        if (hasAttachedSubtitle) {
            checkNotNull(path)
            val sidecar = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(path)))
                .setMimeType(saved.subtitleMime).setLabel("Fichier de sous-titres ajouté")
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
        playback.setMediaSource(source, saved.positionMs)
        player.prepare(); prefs.save(saved); onChanged()
    }
    fun play() {
        if (media == null) { onMessage("Ouvre d’abord une vidéo, ou lance l’essai intégré.", false); return }
        interruptPreparation("Préparation arrêtée : reprise de la lecture. Le cache existant est conservé.")
        if (!requestFocus()) return
        if (player.playbackState == Player.STATE_ENDED) seek(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
        apply(machine.play())
    }
    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onMessage("L’accès au son est refusé pour le moment. Termine l’appel ou la lecture dans l’autre application.", true); return false
        }
        focusHeld = true; return true
    }
    fun pause() {
        finishPlaybackBenchmark("Pause demandée ou application quittée")
        interruptPreparation("Préparation arrêtée : lecture mise en pause. Le cache existant est conservé.")
        apply(machine.pause())
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        if (holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
        savePosition()
    }
    fun seek(positionMs: Long) {
        finishPlaybackBenchmark("Position de lecture modifiée : extrait non comparable")
        interruptPreparation("Préparation arrêtée : position de lecture modifiée. Le cache existant est conservé.")
        apply(machine.reset(keepPlaybackIntent = true)); lastCue = null; onCaption("")
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(position); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    fun resumeVoice() {
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
        val texts = machine.pendingTexts() + ahead.future(selectedTextId(), player.currentPosition, prefs.filters, prefs.lookAheadCount, 600)
        speech.prepareAhead(texts)
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
        if (stopPreparationForHeat()) return
        if (player.currentTracks.groups.none { group -> group.type == C.TRACK_TYPE_TEXT &&
                (0 until group.length).any { group.isTrackSelected(it) } }) {
            onMessage("Attends le chargement puis choisis une piste de sous-titres textuelle avant la préparation.", true); return
        }
        refreshSettings(); manualPreparation = true
        val start = if (seconds == null) 0L else player.currentPosition
        val selected = selectedTextId()
        // An attached sidecar does not replace the container when an embedded
        // track is selected. Its index must not be reused for the other source.
        val source = if (selected == "external-subtitle") saved else saved.copy(subtitlePath = null, subtitleMime = null)
        val origin = IndexSource(source.uri, source.subtitlePath, source.subtitleMime)
        val epoch = mediaEpoch
        fun prepare(index: CueAheadIndex) {
            if (stopPreparationForHeat()) return
            val text = index.selected(selected).asSequence()
                .filter { it.endUs / 1000 > start && (seconds == null || it.startUs / 1000 < start + seconds * 1000L) }
                .flatMap { SpeechTools.segments(SpeechTools.filter(it.raw, prefs.filters)).asSequence() }
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
        val wasPreparing = isPreparing
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
            prefs.bool("matchFrameRate",true).toString(), prefs.bool("preferSoftware").toString(),
            prefs.int("imageMode",0,0,2).toString(), visual.margins.toString(), visual.contrast.toString(),
            visual.brightness.toString(), visual.bypass.toString(), visual.detailedScaling.toString(),
            visual.scalingTarget, player.playbackParameters.toString(),
            prefs.showSubtitles.toString(), prefs.subtitleSp.toString()) + tracks + renderConfiguration())
    }
    fun startPlaybackBenchmark(seconds: Int) {
        if (media == null || player.playbackState != Player.STATE_READY) {
            onMessage("Ouvre une vidéo et attends qu’elle soit prête avant l’essai de lecture.", true); return
        }
        refreshSettings()
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
        val captions = group.cues.mapNotNull { it.text?.toString() }
        lastRawCaption = captions.joinToString("\n"); onCaption(lastRawCaption)
        val clean = captions.map { SpeechTools.filter(it, prefs.filters) }.filter { it.isNotBlank() }
        val occurrences = ahead.resolved(selectedTextId(), clean, group.presentationTimeUs, prefs.filters)
        apply(machine.cues(group.presentationTimeUs, clean, group.cues.any { it.bitmap != null },
            occurrences?.map { it.identity },
            occurrences?.map { it.endUs / 1000 + prefs.allowedLagMs } ?:
                clean.map { text -> ahead.windowEnd(selectedTextId(), text, player.currentPosition, prefs.filters)?.plus(prefs.allowedLagMs) }))
    }
    fun selectTrack(type: Int, group: Tracks.Group?, index: Int = 0, disabled: Boolean = false) {
        if (type == C.TRACK_TYPE_TEXT) { selectExternalWhenAvailable = false; cancelIndexing() }
        pause(); apply(machine.reset(false)); lastCue = null; onCaption(""); trackReset = true
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, disabled)
        if (group != null) params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        player.trackSelectionParameters = params.build()
        handler.post {
            if (!closed) {
                trackReset = false; player.seekTo(player.currentPosition)
                apply(machine.setMediaReady(player.playbackState == Player.STATE_READY)); onChanged()
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
        open(current.copy(positionMs = player.currentPosition, subtitlePath = path, subtitleMime = mime))
    }
    fun savePosition() {
        val current = media ?: return
        val updated = current.copy(positionMs = player.currentPosition.coerceAtLeast(0)); media = updated; prefs.save(updated)
    }
    private fun apply(effects: List<NarrationMachine.Effect>) {
        if (closed) return
        for (effect in effects) when (effect) {
            is NarrationMachine.Effect.Speak -> {
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
                            speech.durations.estimate(pending.text, prefs.speechRate),
                            deadlineMs = pending.deadlineMs?.let(::relative))
                    }
                    ahead.planningItems(selectedTextId(), now).forEach { cue ->
                        val text = SpeechTools.filter(cue.raw, prefs.filters)
                        if (text.isNotBlank()) jobs += fr.soustitresvoix.core.AdaptiveTiming.Window(
                            speech.durations.estimate(text, prefs.speechRate),
                            relative(cue.startUs / 1000), relative(cue.endUs / 1000 + prefs.allowedLagMs))
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
        pause(); cancelIndexing(); indexingWorker.shutdownNow(); closed = true; mediaEpoch++; handler.removeCallbacksAndMessages(null)
        diagnosticObserver.close()
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        visual.close(); speech.close(); playback.close()
    }
}
