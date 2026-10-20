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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechTools
import java.io.File

@UnstableApi
class PlaybackSession(
    private val context: Context, val prefs: PlayerPreferences,
    private val onChanged: () -> Unit, private val onMessage: (String, Boolean) -> Unit,
    private val onCaption: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    val machine = NarrationMachine()
    var ahead = CueAheadIndex(); private set
    val visual = VisualTools(this)
    val bandProcessor = SpeechBandProcessor()
    private val indexingWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var indexer: FullSubtitleIndexer? = null
    var indexingStatus = "Indexation complète non demandée"; private set
    private var manualPreparation = false
    val isPreparing: Boolean get() = indexer != null || speech.bulkPreparation || manualPreparation
    private var indexedUri: String? = null
    private var lastAdaptiveTick = 0L
    private var holdStarted = 0L
    val holdLatency = fr.soustitresvoix.core.LatencyWindow(2048)
    private val benchmarkStarted = SystemClock.elapsedRealtime()
    private val playbackTick = object : Runnable {
        override fun run() {
            if (closed) return
            if (machine.snapshot().wantsPlayback) {
                apply(machine.advance(player.currentPosition)); speech.updateAdaptive()
                val waiting = machine.snapshot().waitingForSpeech
                if (waiting && holdStarted == 0L) holdStarted = SystemClock.elapsedRealtime()
                if (!waiting && holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
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
    val player: ExoPlayer = ExoPlayer.Builder(context,
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(bandProcessor)).build()
        }.setMediaCodecSelector { mime, secure, tunneling ->
            val infos=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling)
            if(prefs.bool("preferSoftware")) infos.sortedByDescending { it.softwareOnly } else infos
        }.setEnableDecoderFallback(true)).build()
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
            if (!closed) { apply(machine.setSpeechReady(ready)); onMessage(msg, !ready && prefs.narration); onChanged() }
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
        player.setVideoEffects(emptyList())
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        handler.post(playbackTick)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) { droppedFrames += dropped }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestampMs: Long, durationMs: Long) { videoDecoder = name }
        })
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages("fr", "fra").setSelectUndeterminedTextLanguage(true).build()
        player.addListener(object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { lastCue = cueGroup; if (!trackReset) forwardCues(cueGroup) }
            override fun onPlaybackStateChanged(state: Int) {
                apply(machine.setMediaReady(state == Player.STATE_READY || state == Player.STATE_ENDED))
                if (state == Player.STATE_ENDED) {
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
                if (reason != Player.DISCONTINUITY_REASON_SEEK) {
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
        savePosition(); cancelIndexing(); indexedUri = null; visual.reset()
        val epoch = ++mediaEpoch; lastRawCaption = ""; ahead.clear(); speech.cancelPreparation()
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
        val tap = CueAheadFactory { track, cues -> handler.post {
            if (!closed && epoch == mediaEpoch) ahead.add(track, cues)
        } }
        val factory = DefaultMediaSourceFactory(context, tap).setSubtitleParserFactory(tap.sidecarParsers())
        player.setMediaSource(factory.createMediaSource(item.build()), saved.positionMs)
        player.prepare(); prefs.save(saved); onChanged()
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
            onMessage("L’accès au son est refusé pour le moment. Termine l’appel ou la lecture dans l’autre application.", true); return false
        }
        focusHeld = true; return true
    }
    fun pause() {
        apply(machine.pause()); speech.cancelPreparation()
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        if (holdStarted != 0L) { holdLatency.add(SystemClock.elapsedRealtime()-holdStarted); holdStarted=0 }
        savePosition()
    }
    fun seek(positionMs: Long) {
        speech.cancelPreparation()
        apply(machine.reset(keepPlaybackIntent = true)); lastCue = null; onCaption("")
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val position = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(position); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
    }
    fun resumeVoice() { if (media != null && requestFocus()) apply(machine.resumeVoice()) }
    internal fun selectedTextId(): String? {
        for (group in player.currentTracks.groups) if (group.type == C.TRACK_TYPE_TEXT)
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i).id
        return null
    }
    fun prepareTick() {
        if (closed || SystemClock.elapsedRealtime() - lastPreparationTick < 1000) return
        lastPreparationTick = SystemClock.elapsedRealtime()
        val power = context.getSystemService(PowerManager::class.java)
        val hot = Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (hot || !prefs.narration) { speech.cancelPreparation(); manualPreparation=false; return }
        if (manualPreparation) { if (indexer == null && !speech.bulkPreparation) manualPreparation=false; return }
        if (!machine.snapshot().wantsPlayback) return
        val texts = machine.pendingTexts() + ahead.future(selectedTextId(), player.currentPosition, prefs.filters, prefs.lookAheadCount, 600)
        speech.prepareAhead(texts)
    }
    fun prepareRange(seconds: Int?) {
        val saved = media ?: return
        refreshSettings(); manualPreparation = true
        val start = if (seconds == null) 0L else player.currentPosition
        val selected = selectedTextId()
        val epoch = mediaEpoch
        fun prepare(index: CueAheadIndex) {
            val text = index.selected(selected).asSequence()
                .filter { it.endUs / 1000 > start && (seconds == null || it.startUs / 1000 < start + seconds * 1000L) }
                .flatMap { SpeechTools.segments(SpeechTools.filter(it.raw, prefs.filters)).asSequence() }.toList()
            if (text.isEmpty()) { manualPreparation=false; onMessage("Aucune réplique trouvée pour la piste sélectionnée. Vérifie qu’elle est textuelle.", true); return }
            speech.prepareBatch(text)
            onMessage("Préparation de ${text.size} segments demandée. Elle reste annulable et limitée par le cache.", false)
        }
        if (indexedUri == saved.uri) { prepare(ahead); return }
        indexer?.cancel()
        val task = FullSubtitleIndexer(context); indexer = task
        indexingStatus="Indexation complète en cours ; aucun décodage vidéo pour cet index"
        indexingWorker.execute {
            try {
                val index = task.scan(saved) { percent -> handler.post {
                    if (!closed && epoch == mediaEpoch && indexer === task) indexingStatus = "Indexation ${if (percent >= 0) "$percent %" else "en cours"}"
                } }
                handler.post {
                    if (closed || epoch != mediaEpoch || indexer !== task) return@post
                    indexer=null; ahead=index; indexedUri=saved.uri
                    indexingStatus="Indexation terminée : ${index.count} entrées ; préparation sur la piste choisie"
                    prepare(index)
                }
            } catch (e: Exception) { handler.post {
                if (!closed && epoch == mediaEpoch && indexer === task) {
                    indexer=null; manualPreparation=false; indexingStatus=e.message ?: "Indexation impossible"
                    onMessage(indexingStatus, true)
                }
            } }
        }
    }
    fun cancelIndexing() { indexer?.cancel(); indexer=null; manualPreparation=false }
    fun cancelPreparation() { cancelIndexing(); speech.cancelPreparation(); indexingStatus="Préparation annulée ; lecture et cache existant conservés" }
    fun benchmark() {
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
    fun diagnostics(): String {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        val cpu = (android.os.Process.getElapsedCpuTime() - startedCpu) * 100.0 / elapsed
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val heat = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus.toString() else "indisponible"
        val pss = context.getSystemService(android.app.ActivityManager::class.java).getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
        return "Appareil : ${Build.MANUFACTURER} ${Build.MODEL} ; Android ${Build.VERSION.RELEASE}\n" +
            "Décodeur vidéo : $videoDecoder\nImages vidéo abandonnées : $droppedFrames\n" +
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
            "$indexingStatus\nProfil : ${prefs.int("performanceProfile", 1, 0, 2)} ; anticipation ${prefs.lookAheadSeconds} secondes estimées\n" +
            "Audio expérimental : ${bandProcessor.enabled}; ${bandProcessor.processedSamples} échantillons PCM16 traités (pas de séparation des sources)\n" +
            "Effets GPU : contraste ${visual.contrast}, luminosité ${visual.brightness}, marges validées ${visual.margins.nonzero()}, HDR ${visual.hdr}, comparaison original ${visual.bypass}\n" +
            "GPU/NPU du moteur vocal : non exposés par l’API TTS Android. Aucun benchmark OnePlus n’est simulé."
    }
    fun repeatLast() { if (requestFocus()) apply(machine.preview()) }
    fun previewVoice() { if (requestFocus()) apply(machine.preview("Bonjour. Cette voix lira les sous-titres de votre vidéo. Vous pouvez régler sa vitesse et le volume du film.")) }
    fun refreshSettings() {
        pause(); speech.applyPreferences()
        if (appliedFilters != prefs.filters) {
            appliedFilters = prefs.filters
            apply(machine.reset(false)); apply(machine.setMediaReady(player.playbackState == Player.STATE_READY))
            lastCue?.let { forwardCues(it) }
        }
        apply(machine.setMode(prefs.mode)); apply(machine.setEnabled(prefs.narration))
        apply(machine.setResumePolicy(prefs.resumePolicy))
        bandProcessor.enabled = prefs.bool("spectralDucking")
        player.setVideoChangeFrameRateStrategy(if (prefs.bool("matchFrameRate", true)) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        setDucked(ducked); onChanged()
    }
    private fun forwardCues(group: CueGroup) {
        val captions = group.cues.mapNotNull { it.text?.toString() }
        lastRawCaption = captions.joinToString("\n"); onCaption(lastRawCaption)
        val clean = captions.map { SpeechTools.filter(it, prefs.filters) }.filter { it.isNotBlank() }
        apply(machine.cues(group.presentationTimeUs, clean, group.cues.any { it.bitmap != null },
            ahead.identities(selectedTextId(), clean, group.presentationTimeUs, prefs.filters),
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
                val now = player.currentPosition
                val speed = player.playbackParameters.speed
                val future = if (adaptive) ahead.planningWindow(selectedTextId(), now, prefs.filters) else emptyList()
                val currentDuration = speech.durations.estimate(effect.text, prefs.speechRate) +
                    if (effect.remainingText.isBlank()) 0 else speech.durations.estimate(effect.remainingText, prefs.speechRate)
                val forecastJobs = mutableListOf<Pair<Long, Long>>()
                if (deadline != null) forecastJobs += currentDuration to ((deadline - now) / speed).toLong()
                future.forEach { forecastJobs += speech.durations.estimate(it.first, prefs.speechRate) to
                    ((it.second + prefs.allowedLagMs - now) / speed).toLong() }
                val forecast = fr.soustitresvoix.core.AdaptiveTiming.forecastFactor(forecastJobs, prefs.adaptiveMax)
                speech.speak(effect.id, effect.text, adaptive = adaptive, remaining = effect.remainingText, forecast = forecast, budget = {
                    deadline?.let { ((it - player.currentPosition) / player.playbackParameters.speed).toLong() }
                })
            }
            NarrationMachine.Effect.StopSpeech -> speech.stop()
            is NarrationMachine.Effect.Video -> player.playWhenReady = effect.playing
            is NarrationMachine.Effect.Duck -> setDucked(effect.enabled)
            is NarrationMachine.Effect.Message -> onMessage(effect.text, effect.error)
        }
        onChanged()
    }
    fun updateMix() {
        fade?.let { handler.removeCallbacks(it) }
        player.volume = (prefs.movieVolume * if (ducked) prefs.duckVolume else 1f).coerceIn(0f, 1f)
    }
    private fun setDucked(value: Boolean) {
        ducked = value; bandProcessor.speaking = value
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
        try { context.unregisterReceiver(noisy) } catch (_: IllegalArgumentException) { }
        speech.close(); player.release()
    }
}
