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
    private val cache = SpeechDiskCache(File(context.cacheDir, "speech-v2")) { preferences.cacheBytes }
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var generation = 0L
    private var configuredEngine = ""
    private var configuration = ""
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
    fun retry() { if (!closed) connect() }
    private fun connect() {
        stop(); generation++; initialized = false; isReady = false
        choices = emptyList(); configuration = ""; preparationDisabled = false
        runCatching { engine?.shutdown() }; engine = null
        configuredEngine = preferences.enginePackage
        val ticket = generation
        if (configuredEngine.isNotEmpty() && engines().none { it.name == configuredEngine }) {
            onAvailability(false, "Ce moteur vocal n’est plus installé. Choisis un autre moteur."); return
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
            override fun onStart(utteranceId: String?) = Unit
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
            if (tts.setSpeechRate(preferences.speechRate) == TextToSpeech.ERROR) {
                isReady = false; onAvailability(false, "Le moteur refuse cette vitesse."); return
            }
            val pkg = configuredEngine.ifEmpty { tts.defaultEngine ?: "default" }
            val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
            val signature = "v2|$pkg|$version|${voice.name}|${preferences.speechRate}|${preferences.filters}"
            if (signature != configuration) {
                stop(); configuration = signature; generation++; preparationDisabled = false
                io.execute { cache.clear(); cacheSize = 0 }
            }
            isReady = true; onAvailability(true, "Voix : ${voice.locale.getDisplayName(Locale.FRENCH)}. Moteur demandé : $pkg.")
        } catch (_: RuntimeException) { isReady = false; onAvailability(false, "Le moteur vocal n’a pas fourni ses voix.") }
    }
    fun speak(id: String, text: String, availableMs: Long? = null, adaptive: Boolean = false) {
        stopForeground()
        if (!isReady || text.length > TextToSpeech.getMaxSpeechInputLength()) {
            main.post { if (!closed) onFailure(id, "voix indisponible ou texte trop long") }; return
        }
        activeId = id
        val ticket = generation; val key = SpeechTools.key(configuration, text)
        if (!preferences.cacheEnabled) { direct(id, text, availableMs, adaptive); return }
        arm(id, 15_000)
        io.execute {
            val entry = runCatching { cache.get(key) }.getOrNull()
            main.post {
                if (closed || generation != ticket || activeId != id) return@post
                if (entry == null) { misses++; direct(id, text, availableMs, adaptive) }
                else { hits++; playFile(id, text, entry, availableMs, adaptive) }
            }
        }
    }
    private fun playFile(id: String, text: String, entry: SpeechDiskCache.Entry, availableMs: Long?, adaptive: Boolean) {
        lastFactor = if (adaptive) SpeechTools.adaptiveFactor(entry.durationMs, availableMs, preferences.adaptiveMax) else 1f
        val mp = MediaPlayer(); audio = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(entry.file.absolutePath)
            mp.setOnPreparedListener {
                if (closed || activeId != id || audio !== mp) return@setOnPreparedListener
                try {
                    mp.setVolume(preferences.voiceVolume, preferences.voiceVolume)
                    mp.playbackParams = PlaybackParams().setSpeed(lastFactor).setPitch(1f)
                    mp.start(); arm(id, (entry.durationMs / lastFactor).toLong() + 15_000)
                    pump()
                } catch (_: RuntimeException) { releaseAudio(); direct(id, text, availableMs, adaptive) }
            }
            mp.setOnCompletionListener { finish(id, null) }
            mp.setOnErrorListener { _, _, _ ->
                if (activeId == id) { releaseAudio(); direct(id, text, availableMs, adaptive) }; true
            }
            arm(id, 15_000); mp.prepareAsync()
        } catch (_: Exception) { releaseAudio(); direct(id, text, availableMs, adaptive) }
    }
    private fun direct(id: String, text: String, availableMs: Long?, adaptive: Boolean) {
        if (activeId != id || closed) return
        cancelPreparation()
        val tts = engine ?: return finish(id, "moteur indisponible")
        lastFactor = 1f; live = true
        try {
            tts.setSpeechRate(preferences.speechRate)
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.voiceVolume) }
            arm(id, (text.length * 180.0 / preferences.speechRate + 15_000).toLong())
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) finish(id, "phrase refusée")
        } catch (_: RuntimeException) { finish(id, "transmission au moteur impossible") }
    }
    fun prepareAhead(texts: List<String>) {
        if (closed || !isReady || !preferences.cacheEnabled || preparationDisabled) return
        val requested = texts.filter { it.isNotBlank() && it.length <= 1000 }.distinct().take(preferences.lookAheadCount)
        if (requested == lastRequested) return
        lastRequested = requested
        pending.clear(); pending.addAll(requested)
        pump()
    }
    private var synthKey = ""
    private var synthStarted = 0L
    private fun pump() {
        if (closed || !isReady || live || (activeId != null && audio == null) || synthId != null || lookup ||
            preparationDisabled || !preferences.cacheEnabled || pending.isEmpty()) return
        val text = pending.removeFirst(); val ticket = generation; val epoch = preparationEpoch; val key = SpeechTools.key(configuration, text)
        lookup = true
        io.execute {
            val exists = runCatching { cache.get(key) != null }.getOrDefault(false)
            val file = if (exists) null else runCatching { cache.temporary() }.getOrNull()
            main.post {
                lookup = false
                if (closed || ticket != generation || epoch != preparationEpoch || live || (activeId != null && audio == null)) {
                    if (!io.isShutdown) file?.let { io.execute { it.delete() } }; return@post
                }
                if (exists) { pump(); return@post }
                val id = "cache-${++synthSequence}"
                synthId = id; synthFile = file; synthKey = key; synthStarted = SystemClock.elapsedRealtime()
                preparationStatus = "Préparation locale en cours"
                try {
                    engine?.setSpeechRate(preferences.speechRate)
                    val result = engine?.synthesizeToFile(text, Bundle(), requireNotNull(file), id)
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
            val key = synthKey; val ticket = generation
            synthId = null; synthFile = null
            lastSynthesisMs = SystemClock.elapsedRealtime() - synthStarted
            if (error != null) {
                io.execute { file.delete() }; preparationDisabled = true
                preparationStatus = "Préparation indisponible avec ce moteur ; voix directe conservée"
            } else {
                io.execute {
                    val result = runCatching { cache.put(key, file) }.getOrNull(); cacheSize = cache.size()
                    main.post { if (!closed && ticket == generation) {
                        if (result != null) { preparedCount++; lastAudioMs = result.durationMs
                            preparationStatus = "$preparedCount segments préparés localement" }
                        else { preparationDisabled = true; preparationStatus = "Format audio du moteur non exploitable ; voix directe conservée" }
                        pump()
                    } }
                }
            }
        } else if (id == activeId) finish(id, error)
    } }
    private fun finish(id: String, error: String?) {
        if (closed || activeId != id) return
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
        watchdog?.let { main.removeCallbacks(it) }; watchdog = null
        if (live) runCatching { engine?.stop() }
        live = false
    }
    fun cancelPreparation() {
        preparationEpoch++; lastRequested = emptyList()
        pending.clear()
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
        runCatching { engine?.shutdown() }; engine = null
        io.shutdown()
    }
}
