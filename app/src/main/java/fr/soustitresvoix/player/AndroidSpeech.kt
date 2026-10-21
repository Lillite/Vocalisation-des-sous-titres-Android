package fr.soustitresvoix.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import fr.soustitresvoix.core.SpeechTools
import fr.soustitresvoix.core.SpeechDiskCache
import fr.soustitresvoix.core.AdaptiveTiming
import fr.soustitresvoix.core.SpeechDurationModel
import fr.soustitresvoix.core.LatencyWindow
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** One synthesis client, priority to live speech, bounded disk I/O off the UI thread. */
class AndroidSpeech(
    private val context: Context, private val preferences: PlayerPreferences,
    private val onAvailability: (Boolean, String) -> Unit,
    private val onDone: (String) -> Unit, private val onFailure: (String, String) -> Unit
) {
    data class Choice(val name: String, val label: String, val languageTag: String)
    data class EngineChoice(val name: String, val label: String)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache = SpeechDiskCache(File(context.cacheDir, "speech-v3")) { preferences.cacheBytes }
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var generation = 0L
    val durations = SpeechDurationModel()
    val synthesisLatency = LatencyWindow()
    val firstAudioLatency = LatencyWindow()
    val cacheLatency = LatencyWindow()
    var timingSource = "aucun calcul"; private set
    var bulkPreparation = false; private set
    var preparedAudioMs = 0L; private set
    private var requestStarted = 0L
    private var audioStarted = 0L
    private var activeText = ""
    private var remainingText = ""
    private var activeBudget: (() -> Long?)? = null
    private var activeAdaptive = false
    private var planningFloor = 1f
    private var activePlanning: ((Long) -> Float)? = null
    private var fileRateAdjustmentRejected = false
    private var synthText = ""
    private var activeBaseRate = 1f
    private var synthBaseRate = 1f
    private data class VoiceSettings(
        val engine: String, val voice: String, val rate: Float,
        val filters: SpeechTools.Filters, val modelRevision: Int
    )
    private fun voiceSettings() = VoiceSettings(preferences.enginePackage, preferences.voiceName,
        preferences.speechRate, preferences.filters, preferences.int("modelRevision", 0, 0, 100000))
    private var configuredSettings: VoiceSettings? = null
    private var configuredEngine = ""
    private var configuration = ""
    private var engineIdentity = "Voix non configurée"
    val cacheConfigurationFingerprint: String get() = SpeechTools.key("benchmark-configuration", configuration)
    val engineDiagnosticIdentity: String get() = engineIdentity
    private var activeId: String? = null
    private var live = false
    private var audio: MediaPlayer? = null
    private var watchdog: Runnable? = null
    private var synthId: String? = null
    private var synthFile: File? = null
    private var synthSequence = 0L
    private var preparationDisabled = false
    private var pending = ArrayDeque<String>()
    private var lookup = false
    private var lookupText: String? = null
    private var publishing = false
    private var preparationEpoch = 0L
    private var lastRequested: List<String> = emptyList()
    var choices: List<Choice> = emptyList(); private set
    var language: String? = null; private set
    var isReady = false; private set
    var hits = 0; private set
    var misses = 0; private set
    var preparedCount = 0; private set
    var lastSynthesisMs = 0L; private set
    var lastAudioMs = 0L; private set
    var lastFactor = 1f; private set
    @Volatile var cacheSize = 0L; private set
    var preparationStatus = "En attente de sous-titres chargés à l’avance"; private set
    init { connect() }
    fun engines(): List<EngineChoice> = context.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).map {
        EngineChoice(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString())
    }.distinctBy { it.name }.sortedBy { it.label }
    private fun engineVersion(pkg: String): String = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        "${info.versionName}:${info.lastUpdateTime}:${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()}"
    }.getOrDefault("version indisponible")
    fun retry() { if (!closed) connect() }
    private fun connect() {
        stop(); generation++; initialized = false; isReady = false
        choices = emptyList(); configuration = ""; configuredSettings = null; preparationDisabled = false
        engineIdentity = "Voix non configurée"
        runCatching { engine?.shutdown() }; engine = null
        configuredEngine = preferences.enginePackage
        val ticket = generation
        if (configuredEngine.isNotEmpty() && engines().none { it.name == configuredEngine }) {
            // connect() also runs inside the constructor. Notify only after the
            // owner has assigned this instance, and ignore a closed/retried connection.
            main.post {
                if (!closed && ticket == generation)
                    onAvailability(false, "Ce moteur vocal n’est plus installé. Choisis un autre moteur.")
            }
            return
        }
        engine = TextToSpeech(context.applicationContext, { status -> main.post {
            if (!closed && ticket == generation) initialize(status)
        } }, configuredEngine.takeIf { it.isNotEmpty() })
        main.postDelayed({ if (!closed && ticket == generation && !initialized)
            onAvailability(false, "Le moteur vocal ne répond pas. Choisis un autre moteur dans Voix et confort.") }, 15_000)
    }
    private fun initialize(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            onAvailability(false, "Le moteur vocal n’a pas démarré. Vérifie ses données vocales."); return
        }
        initialized = true
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post {
                if (utteranceId == activeId && live) {
                    audioStarted = SystemClock.elapsedRealtime()
                    firstAudioLatency.add(audioStarted - requestStarted)
                }
            } }
            override fun onDone(utteranceId: String?) { complete(utteranceId, null) }
            @Deprecated("Required by legacy engines")
            override fun onError(utteranceId: String?) { complete(utteranceId, "erreur du moteur vocal") }
            override fun onError(utteranceId: String?, errorCode: Int) { complete(utteranceId, "erreur vocale ($errorCode)") }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { complete(utteranceId, "voix interrompue") }
        })
        applyPreferences()
    }
    fun applyPreferences() {
        if (closed) return
        if (configuredEngine != preferences.enginePackage) { connect(); return }
        if (!initialized) return
        val tts = engine ?: return
        try {
            val installed = (tts.voices ?: emptySet()).filter { !it.isNetworkConnectionRequired &&
                !(it.features ?: emptySet()).contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            choices = installed.sortedWith(compareBy<Voice> { it.locale.getDisplayLanguage(Locale.FRENCH) }
                .thenByDescending { it.quality }.thenBy { it.name }).map {
                Choice(it.name, "${it.locale.getDisplayName(Locale.FRENCH)} — ${it.name}", it.locale.toLanguageTag()) }
            val voice = installed.firstOrNull { it.name == preferences.voiceName }
                ?: installed.filter { it.locale.language == "fr" }.maxByOrNull { it.quality }
                ?: installed.firstOrNull { it.name == tts.defaultVoice?.name } ?: installed.firstOrNull()
            if (voice == null || tts.setVoice(voice) == TextToSpeech.ERROR) {
                isReady = false; onAvailability(false, "Aucune voix locale prête. Ouvre le moteur choisi et télécharge une voix, puis actualise ici."); return
            }
            preferences.voiceName = voice.name; language = voice.locale.language
            val settings = voiceSettings()
            if (tts.setSpeechRate(settings.rate) == TextToSpeech.ERROR) {
                isReady = false; onAvailability(false, "Le moteur refuse cette vitesse."); return
            }
            val declaredDefault = tts.defaultEngine.orEmpty()
            val pkg = configuredEngine.ifEmpty { declaredDefault.ifEmpty { "default" } }
            val version = engineVersion(pkg)
            // Android may fall back from the requested engine without exposing
            // its effective package through the public API. Conservatively
            // invalidate if any possible installed fallback changes version.
            val inventory = engines().map { it.name }.sorted().joinToString(";") { "$it:${engineVersion(it)}" }
            val signature = "v3|$pkg|$version|${voice.name}|${voice.locale}|${voice.quality}|${voice.features?.sorted()}|${settings.rate}|${settings.filters}|${fr.soustitresvoix.core.LinguisticSegments.VERSION}|${settings.modelRevision}|default=$declaredDefault|engines=$inventory"
            if (signature != configuration) {
                stop(); configuration = signature; generation++; preparationDisabled = false
                durations.clear()
                // Namespaced keys preserve valid audio across launches. No unconditional purge.
                io.execute { cache.initialize(); cacheSize = cache.size() }
            }
            configuredSettings = settings
            engineIdentity = "Moteur demandé : $pkg ; version : $version ; défaut Android déclaré : ${declaredDefault.ifEmpty { "inconnu" }} ; " +
                "voix exposée : ${voice.name} (${voice.locale}). Un éventuel moteur de secours Android n’est pas attestable par l’API publique."
            isReady = true; onAvailability(true, "Voix : ${voice.locale.getDisplayName(Locale.FRENCH)}. Moteur demandé : $pkg.")
        } catch (_: RuntimeException) { isReady = false; onAvailability(false, "Le moteur vocal n’a pas fourni ses voix.") }
    }
    fun speak(id: String, text: String, availableMs: Long? = null, adaptive: Boolean = false, remaining: String = "", forecast: Float = 1f, budget: (() -> Long?)? = null, planning: ((Long) -> Float)? = null) {
        stopForeground()
        if (!isReady || text.length > TextToSpeech.getMaxSpeechInputLength()) {
            main.post { if (!closed) onFailure(id, "voix indisponible ou texte trop long") }; return
        }
        if (configuredSettings != voiceSettings()) {
            main.post { if (!closed) onFailure(id, "réglages vocaux modifiés ; applique les réglages puis reprends cette réplique") }; return
        }
        activeId = id; activeText = text; remainingText = remaining
        activeBaseRate = requireNotNull(configuredSettings).rate
        activeBudget = budget ?: { availableMs }; activeAdaptive = adaptive; planningFloor = forecast
        activePlanning = planning; fileRateAdjustmentRejected = false
        requestStarted = SystemClock.elapsedRealtime(); audioStarted = 0
        val ticket = generation; val key = SpeechTools.key(configuration, text)
        if (!preferences.cacheEnabled) { direct(id, text); return }
        arm(id, 15_000)
        val lookupStarted = SystemClock.elapsedRealtime()
        io.execute {
            val entry = runCatching { cache.get(key) }.getOrNull()
            main.post {
                if (closed || generation != ticket || activeId != id) return@post
                if (configuredSettings != voiceSettings()) {
                    finish(id, "réglages vocaux modifiés ; applique les réglages puis reprends cette réplique"); return@post
                }
                cacheLatency.add(SystemClock.elapsedRealtime() - lookupStarted)
                if (entry == null) { misses++; direct(id, text) }
                else { hits++; playFile(id, text, entry, availableMs, adaptive) }
            }
        }
    }
    private fun playFile(id: String, text: String, entry: SpeechDiskCache.Entry, availableMs: Long?, adaptive: Boolean) {
        timingSource = "durée audio mesurée"
        lastFactor = plannedFactor(entry.durationMs)
        val mp = MediaPlayer(); audio = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(entry.file.absolutePath)
            mp.setOnPreparedListener {
                if (closed || activeId != id || audio !== mp) return@setOnPreparedListener
                try {
                    // Include actual file preparation latency, not an old budget snapshot.
                    lastFactor = plannedFactor(entry.durationMs)
                    mp.setVolume(preferences.voiceVolume, preferences.voiceVolume)
                    mp.playbackParams = PlaybackParams().setSpeed(lastFactor).setPitch(1f)
                    mp.start(); audioStarted = SystemClock.elapsedRealtime(); firstAudioLatency.add(audioStarted - requestStarted); arm(id, (entry.durationMs / lastFactor).toLong() + 15_000); pump()
                } catch (_: RuntimeException) { releaseAudio(); direct(id, text) }
            }
            mp.setOnCompletionListener {
                if (activeId == id && audio === mp) finish(id, null, mp)
            }
            mp.setOnErrorListener { _, _, _ ->
                if (activeId == id && audio === mp) { releaseAudio(); direct(id, text) }; true
            }
            arm(id, 15_000); mp.prepareAsync()
        } catch (_: Exception) { releaseAudio(); direct(id, text) }
    }
    private fun direct(id: String, text: String) {
        if (activeId != id || closed) return
        if (configuredSettings != voiceSettings()) {
            finish(id, "réglages vocaux modifiés ; applique les réglages puis reprends cette réplique"); return
        }
        cancelPreparation(keepPending = true)
        val tts = engine ?: return finish(id, "moteur indisponible")
        timingSource = "estimation calibrée (lecture directe)"
        lastFactor = plannedFactor(durations.estimate(text, activeBaseRate)); live = true
        try {
            val resultRate = tts.setSpeechRate((activeBaseRate * lastFactor).coerceAtMost(5f))
            if (resultRate == TextToSpeech.ERROR) {
                lastFactor = 1f
                if (tts.setSpeechRate(activeBaseRate) == TextToSpeech.ERROR) {
                    finish(id, "le moteur refuse également la vitesse de base"); return
                }
                preparationStatus = "Accélération refusée par le moteur ; mode confort conservé"
            }
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.voiceVolume) }
            arm(id, (text.length * 180.0 / activeBaseRate + 15_000).toLong())
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) finish(id, "phrase refusée")
        } catch (_: RuntimeException) { finish(id, "transmission au moteur impossible") }
    }
    private fun plannedFactor(audioMs: Long): Float {
        if (!activeAdaptive) return 1f
        val remainingMs = if (remainingText.isBlank()) 0L else durations.estimate(remainingText, activeBaseRate)
        val forecast = activePlanning?.invoke(audioMs) ?: planningFloor
        return maxOf(AdaptiveTiming.factor(audioMs, remainingMs, activeBudget?.invoke(), preferences.adaptiveMax), forecast)
            .coerceIn(1f, preferences.adaptiveMax)
    }
    fun updateAdaptive() {
        val mp = audio ?: return
        if (!activeAdaptive || activeId == null || fileRateAdjustmentRejected) return
        runCatching {
            val left = (mp.duration.toLong() - mp.currentPosition).coerceAtLeast(0)
            val target = plannedFactor(left)
            // Increase only within a sentence; preserve its punctuation and breath pauses.
            if (target > lastFactor + 0.04f) {
                runCatching { mp.playbackParams = PlaybackParams().setSpeed(target).setPitch(1f) }
                    .onSuccess { lastFactor = target }
                    .onFailure {
                        fileRateAdjustmentRejected = true
                        timingSource = "durée audio mesurée ; changement de vitesse refusé, facteur précédent conservé"
                    }
            }
        }
    }
    fun prepareBatch(texts: List<String>) {
        cancelPreparation()
        if (closed || !isReady || !preferences.cacheEnabled || preparationDisabled) { preparationStatus = "Choisis une voix et active le cache avant la préparation"; return }
        if (!preparationSettingsCurrent()) return
        if (texts.any { it.length > 1000 }) {
            preparationStatus = "Préparation refusée : un segment dépasse la limite vocale. Aucun segment n’a été supprimé silencieusement."; return
        }
        val valid = texts.asSequence().filter { it.isNotBlank() }.distinct().take(100_001).toList()
        if (valid.size > 100_000) {
            preparationStatus = "Préparation refusée : plus de 100 000 segments distincts. Choisis une plage plus courte."; return
        }
        bulkPreparation = true; preparedAudioMs = 0
        pending.addAll(valid); lastRequested = valid
        preparationStatus = "${valid.size} segments demandés ; la limite du cache reste applicable"
        pump()
    }
    fun prepareAhead(texts: List<String>) {
        if (!preparationSettingsCurrent()) return
        if (bulkPreparation) return
        if (closed || !isReady || !preferences.cacheEnabled || preparationDisabled) return
        var total = 0L
        val requested = texts.asSequence().filter { it.isNotBlank() && it.length <= 1000 }.distinct()
            .take(preferences.lookAheadCount).takeWhile {
                val keep = total < preferences.lookAheadSeconds * 1000L
                total += durations.estimate(it, requireNotNull(configuredSettings).rate); keep
            }.toList()
        if (requested == lastRequested) return
        lastRequested = requested; pending.clear(); pending.addAll(requested); pump()
    }
    private var synthKey = ""
    private var synthStarted = 0L
    private fun preparationSettingsCurrent(): Boolean {
        if (closed) return false
        if (!preferences.cacheEnabled || (isReady && configuredSettings != voiceSettings())) {
            cancelPreparation()
            preparationStatus = if (!preferences.cacheEnabled) "Préparation arrêtée : cache vocal désactivé"
                else "Préparation arrêtée : réglages vocaux modifiés. Applique les réglages et relance la préparation."
            return false
        }
        return true
    }
    private fun pump() {
        if (!preparationSettingsCurrent()) return
        if (bulkPreparation && pending.isEmpty() && synthId == null && !lookup && !publishing) {
            bulkPreparation = false
            preparationStatus = "Préparation parcourue ; ${preparedAudioMs / 1000} s nouvellement synthétisées. Cache borné : les plus anciennes peuvent être évincées."
        }
        if (closed || !isReady || live || (activeId != null && audio == null) || synthId != null || lookup || publishing ||
            preparationDisabled || !preferences.cacheEnabled || pending.isEmpty()) return
        val text = pending.removeFirst(); val ticket = generation; val epoch = preparationEpoch
        val key = SpeechTools.key(configuration, text); lookup = true; lookupText = text
        val lookupStarted = SystemClock.elapsedRealtime()
        io.execute {
            val exists = runCatching { cache.get(key) != null }.getOrDefault(false)
            val file = if (exists) null else runCatching { cache.temporary() }.getOrNull()
            main.post {
                lookup = false
                val ownedText = lookupText; lookupText = null
                if (!closed && ticket == generation && epoch == preparationEpoch)
                    cacheLatency.add(SystemClock.elapsedRealtime() - lookupStarted)
                val settingsCurrent = preparationSettingsCurrent()
                if (closed || ticket != generation || epoch != preparationEpoch || !settingsCurrent || live || (activeId != null && audio == null)) {
                    // A foreground request can arrive before its cache lookup completes.
                    // Preserve this pending item; explicit cancellation never restores it.
                    if (!closed && ticket == generation && epoch == preparationEpoch && ownedText != null)
                        pending.addFirst(ownedText)
                    if (!io.isShutdown) file?.let { io.execute { it.delete() } }
                    // A new configuration may already have queued its own lot
                    // while this old disk lookup was finishing.
                    if (!closed) pump()
                    return@post
                }
                if (exists) { pump(); return@post }
                if (file == null) {
                    bulkPreparation = false; preparationDisabled = true; preparationStatus = "Cache indisponible ; lecture vocale directe conservée"
                    return@post
                }
                val id = "cache-${++synthSequence}"
                synthId = id; synthFile = file; synthKey = key; synthText = text
                synthBaseRate = requireNotNull(configuredSettings).rate; synthStarted = SystemClock.elapsedRealtime()
                preparationStatus = "Préparation locale en cours"
                try {
                    if (engine?.setSpeechRate(synthBaseRate) != TextToSpeech.SUCCESS) {
                        complete(id, "vitesse de préparation refusée"); return@post
                    }
                    val result = engine?.synthesizeToFile(text, Bundle(), file, id)
                    if (result != TextToSpeech.SUCCESS) complete(id, "synthèse en fichier refusée")
                    main.postDelayed({ if (synthId == id) {
                        cancelPreparation(); preparationDisabled = true
                        preparationStatus = "Préparation trop lente ; lecture vocale directe conservée"
                    } }, 30_000)
                } catch (_: RuntimeException) { complete(id, "synthèse en fichier indisponible") }
            }
        }
    }
    private fun complete(id: String?, error: String?) { main.post {
        if (id == null || closed) return@post
        if (id == synthId) {
            val file = synthFile ?: return@post
            val key = synthKey; val ticket = generation; val epoch = preparationEpoch
            val preparedText = synthText; val preparedRate = synthBaseRate
            synthId = null; synthFile = null
            lastSynthesisMs = SystemClock.elapsedRealtime() - synthStarted; lastAudioMs = 0; synthesisLatency.add(lastSynthesisMs)
            if (error != null) {
                io.execute { file.delete() }; bulkPreparation = false; preparationDisabled = true
                preparationStatus = "Préparation indisponible avec ce moteur ; voix directe conservée"
            } else {
                publishing = true
                io.execute {
                    val result = runCatching { cache.put(key, file) }.getOrNull(); cacheSize = cache.size()
                    main.post {
                        publishing = false
                        if (!closed && ticket == generation && epoch == preparationEpoch) {
                            if (result != null) {
                                preparedCount++; lastAudioMs = result.durationMs; preparedAudioMs += result.durationMs
                                durations.observe(preparedText, result.durationMs, preparedRate)
                                preparationStatus = "$preparedCount segments préparés localement"
                            } else {
                                bulkPreparation = false; preparationDisabled = true
                                preparationStatus = "Format audio du moteur non exploitable ; voix directe conservée"
                            }
                        }
                        if (!closed) pump()
                    }
                }
            }
        } else if (id == activeId) finish(id, error)
    } }
    private fun finish(id: String, error: String?, expectedAudio: MediaPlayer? = null) {
        // Engine errors can be synchronous while playback effects are applied.
        // Deliver completion on a later loop turn to avoid state-machine reentry.
        main.post {
            // A released file player can still have a completion already queued.
            // It must not complete the direct TTS fallback of the same utterance.
            if (expectedAudio == null || audio === expectedAudio) finishOnMain(id, error)
        }
    }
    private fun finishOnMain(id: String, error: String?) {
        if (closed || activeId != id) return
        if (error == null && live && audioStarted > 0) {
            durations.observe(activeText, ((SystemClock.elapsedRealtime() - audioStarted) * lastFactor).toLong(), activeBaseRate)
        }
        activeId = null; live = false; releaseAudio()
        watchdog?.let { main.removeCallbacks(it) }; watchdog = null
        if (error == null) onDone(id) else { runCatching { engine?.stop() }; onFailure(id, error) }
        pump()
    }
    private fun arm(id: String, ms: Long) {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = Runnable { finish(id, "délai de sécurité dépassé") }.also { main.postDelayed(it, ms.coerceIn(15_000, 180_000)) }
    }
    private fun releaseAudio() { val mp = audio; audio = null; runCatching { mp?.release() } }
    private fun stopForeground() {
        activeId = null; releaseAudio()
        activeBudget = null; activePlanning = null
        watchdog?.let { main.removeCallbacks(it) }; watchdog = null
        if (live) runCatching { engine?.stop() }
        live = false
    }
    fun cancelPreparation(keepPending: Boolean = false) {
        preparationEpoch++; lastRequested = emptyList()
        if (!keepPending) { pending.clear(); bulkPreparation = false }
        else {
            if (synthId != null && synthText.isNotBlank()) pending.addFirst(synthText)
            lookupText?.let { pending.addFirst(it) }
        }
        // The in-flight callback still clears lookup, but cannot reinsert this item twice.
        lookupText = null
        if (synthId != null) {
            synthId = null; runCatching { engine?.stop() }
            synthFile?.let { f -> io.execute { f.delete() } }; synthFile = null
        }
    }
    fun stop() { stopForeground(); cancelPreparation() }
    fun clearCache() { stop(); generation++; io.execute { cache.clear(); cacheSize = 0 }; preparationDisabled = false }
    fun close() {
        if (closed) return
        stop(); closed = true; generation++; main.removeCallbacksAndMessages(null)
        runCatching { engine?.shutdown() }; engine = null; io.shutdown()
    }
}
