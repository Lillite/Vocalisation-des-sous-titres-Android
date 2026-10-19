package fr.soustitresvoix.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import fr.soustitresvoix.core.SpeechPlanning
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/** One TTS client: audible requests always preempt speculative synthesis.
 * Callbacks are generation-checked on the main thread. Cached WAVs are private,
 * bounded and disposable. No text/model is transmitted by this application.
 */
class AndroidSpeech(private val context: Context, private val preferences: PlayerPreferences,
    private val onAvailability: (Boolean, String) -> Unit, private val onDone: (String) -> Unit,
    private val onFailure: (String, String) -> Unit,
    private val onProgress: (String, Int) -> Unit = { _, _ -> }) {
    data class Choice(val name: String, val label: String, val languageTag: String)
    data class EngineChoice(val name: String, val label: String)
    private data class Cached(val file: File, val ms: Long, val bytes: Long)
    private data class Pending(val id: String, val key: String, val file: File, val epoch: Long, val started: Long)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val directory = File(context.cacheDir, "voice-v2").apply { mkdirs() }
    private val cache = LinkedHashMap<String, Cached>(32, 0.75f, true)
    private var engine: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var packageName = ""
    private var engineVersion = 0L
    private var initialized = false
    private var cacheInitialized = false
    private var closed = false
    private var generation = 0L
    private var epoch = 0L
    private var seq = 0L
    private var activeId: String? = null
    private var activeToken: String? = null
    private var parts = emptyList<SpeechPlanning.Segment>()
    private var part = 0
    private var ratio = 1f
    private var direct = false
    private var pending: Pending? = null
    private var processingKey: String? = null
    private val warmQueue = ArrayDeque<String>()
    private val failedKeys = mutableSetOf<String>()
    private var audibleWatchdog: Runnable? = null
    private var cacheWatchdog: Runnable? = null
    private var profile = ""
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    var choices: List<Choice> = emptyList(); private set
    var engines: List<EngineChoice> = emptyList(); private set
    var language: String? = null; private set
    var isReady = false; private set
    var cacheHits = 0; private set
    var cacheMisses = 0; private set
    var synthCount = 0; private set
    var synthesisMs = 0L; private set
    var generatedAudioMs = 0L; private set
    var lastRateFactor = 1f; private set
    var currentText = ""; private set
    val cacheBytes get() = cache.values.sumOf { it.bytes }
    val cacheEntries get() = cache.size

    init {
        // No persistence contract: remove leftovers before accepting cache work.
        io.execute { directory.listFiles()?.forEach { it.delete() }; main.post { if (!closed) { cacheInitialized = true; pump() } } }
        connect(preferences.enginePackage)
    }
    private fun connect(requested: String) {
        generation++; val g = generation
        stop(); cancelPreparation(); initialized = false; isReady = false; choices = emptyList()
        try { engine?.shutdown() } catch (_: RuntimeException) { }
        engine = null
        val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(intent, 0)
        engines = services.map { EngineChoice(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }.distinctBy { it.name }
        if (requested.isNotEmpty() && engines.none { it.name == requested }) {
            onAvailability(false, "Le moteur choisi n’est plus installé. Choisis un autre moteur vocal."); return
        }
        packageName = requested
        val callback = TextToSpeech.OnInitListener { status -> main.post {
            if (closed || g != generation) return@post
            val tts = engine ?: return@post
            if (status != TextToSpeech.SUCCESS) { onAvailability(false, "Le moteur vocal n’a pas démarré. Choisis un autre moteur ou vérifie son installation."); return@post }
            if (packageName.isEmpty()) packageName = tts.defaultEngine ?: ""
            @Suppress("DEPRECATION")
            engineVersion = try { context.packageManager.getPackageInfo(packageName, 0).versionCode.toLong() } catch (_: Exception) { 0 }
            initialized = true
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { main.post { if (!closed && g == generation) complete(id, null) } }
                @Deprecated("Required by the platform abstract listener")
                override fun onError(id: String?) { main.post { if (!closed && g == generation) complete(id, "erreur du moteur") } }
                override fun onError(id: String?, code: Int) { main.post { if (!closed && g == generation) complete(id, "erreur du moteur ($code)") } }
                override fun onStop(id: String?, interrupted: Boolean) { main.post { if (!closed && g == generation) complete(id, "synthèse interrompue") } }
            })
            applyPreferences()
        } }
        engine = if (requested.isBlank()) TextToSpeech(context.applicationContext, callback)
            else TextToSpeech(context.applicationContext, callback, requested)
    }
    fun applyPreferences() {
        val requested = preferences.enginePackage
        if (initialized && requested.isNotBlank() && requested != packageName) {
            onAvailability(false, "Changement de moteur vocal…"); connect(requested); return
        }
        if (!initialized || closed) return
        val tts = engine ?: return
        try {
            val installed = (tts.voices ?: emptySet()).filter {
                !it.isNetworkConnectionRequired && !(it.features ?: emptySet()).contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            choices = installed.sortedWith(compareBy<Voice> { it.locale.getDisplayLanguage(Locale.FRENCH) }
                .thenByDescending { it.quality }.thenBy { it.name }).map {
                Choice(it.name, "${it.locale.getDisplayName(Locale.FRENCH)} — ${it.name}", it.locale.toLanguageTag()) }
            val voice = installed.firstOrNull { it.name == preferences.voiceName }
                ?: installed.filter { it.locale.language == "fr" }.maxByOrNull { it.quality }
                ?: installed.firstOrNull { it.name == tts.defaultVoice?.name } ?: installed.firstOrNull()
            if (voice == null || tts.setVoice(voice) == TextToSpeech.ERROR || tts.setSpeechRate(preferences.speechRate) == TextToSpeech.ERROR) {
                isReady = false; onAvailability(false, "Aucune voix hors ligne utilisable. Télécharge une voix dans l’application du moteur, puis réessaie."); return
            }
            preferences.voiceName = voice.name; language = voice.locale.language
            val newProfile = "$packageName|$engineVersion|${voice.name}|${preferences.speechRate}|${preferences.filters}"
            if (newProfile != profile) { cancelPreparation(); profile = newProfile; failedKeys.clear() }
            if (!preferences.cacheEnabled) cancelPreparation()
            isReady = true
            onAvailability(true, "Voix : ${voice.locale.getDisplayName(Locale.FRENCH)} — ${engines.firstOrNull { it.name == packageName }?.label ?: packageName}.")
            prune()
        } catch (_: RuntimeException) { isReady = false; onAvailability(false, "Impossible d’obtenir les voix du moteur. Choisis un autre moteur vocal.") }
    }
    fun switchEngine(name: String) {
        preferences.enginePackage = name; preferences.voiceName = ""
        onAvailability(false, "Changement de moteur vocal…"); connect(name)
    }
    private fun key(text: String) = SpeechPlanning.cacheKey(packageName, engineVersion, preferences.voiceName, preferences.speechRate, text)
    fun speak(id: String, text: String, availableMs: Long? = null) {
        stop()
        if (!isReady || engine == null || text.length > TextToSpeech.getMaxSpeechInputLength()) {
            main.post { if (!closed) onFailure(id, "voix indisponible ou phrase trop longue") }; return
        }
        activeId = id; currentText = text; parts = SpeechPlanning.segments(text); part = 0
        val ms = parts.sumOf { cache[key(it.text)]?.ms ?: SpeechPlanning.estimatedMs(it.text, preferences.speechRate) }
        ratio = if (preferences.adaptive) SpeechPlanning.factor(ms, availableMs, preferences.adaptivePercent / 100f) else 1f
        lastRateFactor = ratio
        nextPart()
    }
    private fun nextPart() {
        val id = activeId ?: return
        if (part >= parts.size) {
            activeId = null; activeToken = null; direct = false; currentText = ""
            main.post { if (!closed) onDone(id) }; pump(); return
        }
        val segment = parts[part]; val token = "play-${++seq}"; activeToken = token
        main.post { if (activeId == id && activeToken == token) onProgress(id, segment.start) }
        val entry = if (preferences.cacheEnabled) cache[key(segment.text)] else null
        if (entry == null || !entry.file.isFile) { cacheMisses++; live(token, segment.text); return }
        cacheHits++; direct = false
        val mp = MediaPlayer(); player = mp; var started = false
        try {
            mp.setAudioAttributes(attrs); mp.setVolume(preferences.voiceVolume, preferences.voiceVolume)
            mp.setDataSource(entry.file.absolutePath)
            mp.setOnPreparedListener {
                if (activeToken != token || player !== mp || closed) {
                    try { mp.release() } catch (_: RuntimeException) { }
                    return@setOnPreparedListener
                }
                try {
                    mp.playbackParams = PlaybackParams().setSpeed(ratio).setPitch(1f)
                    started = true; mp.start(); watch(token, segment.text); pump()
                } catch (_: RuntimeException) { releasePlayer(); live(token, segment.text) }
            }
            mp.setOnCompletionListener { if (activeToken == token) { releasePlayer(); finishPart(token) } }
            mp.setOnErrorListener { _, _, _ ->
                if (activeToken == token) {
                    releasePlayer(); cache.remove(key(segment.text))?.let { io.execute { it.file.delete() } }
                    if (started) fail(id, "lecture du cache vocal interrompue") else live(token, segment.text)
                }
                true
            }
            watch(token, segment.text); mp.prepareAsync()
        } catch (_: RuntimeException) { releasePlayer(); live(token, segment.text) }
    }
    private fun live(token: String, text: String) {
        if (activeToken != token) return
        cancelInFlight()
        direct = true
        try {
            val tts = engine ?: return
            if (tts.setSpeechRate(preferences.speechRate * ratio) == TextToSpeech.ERROR) { complete(token, "vitesse vocale refusée"); return }
            watch(token, text)
            val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.voiceVolume) }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, token) == TextToSpeech.ERROR) complete(token, "phrase refusée")
        } catch (_: RuntimeException) { complete(token, "transmission de la phrase impossible") }
    }
    private fun watch(token: String, text: String) {
        audibleWatchdog?.let(main::removeCallbacks)
        audibleWatchdog = Runnable { if (activeToken == token) complete(token, "délai de sécurité vocal dépassé") }.also {
            main.postDelayed(it, (text.length * 180.0 / preferences.speechRate + 15_000).roundToLong().coerceIn(30_000, 180_000)) }
    }
    private fun complete(token: String?, error: String?) {
        if (token == null) return
        val p = pending
        if (p != null && token == p.id) {
            pending = null; cacheWatchdog?.let(main::removeCallbacks); cacheWatchdog = null
            if (error != null) { failedKeys.add(p.key); io.execute { p.file.delete() }; pump(); return }
            processingKey = p.key
            io.execute {
                var duration = 0L; val size = p.file.length()
                if (size in 44..8L * 1024 * 1024) {
                    val metadata = MediaMetadataRetriever()
                    try { metadata.setDataSource(p.file.absolutePath); duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                    catch (_: RuntimeException) { } finally { metadata.release() }
                }
                val valid = duration in 1..180_000
                val target = File(directory, "${p.key}-${p.id}.wav")
                val moved = valid && p.file.renameTo(target)
                if (!moved) p.file.delete()
                main.post {
                    if (processingKey == p.key) processingKey = null
                    if (closed || epoch != p.epoch) { target.delete(); return@post }
                    if (moved) {
                        cache[p.key] = Cached(target, duration, size)
                        synthCount++; synthesisMs += SystemClock.elapsedRealtime() - p.started; generatedAudioMs += duration
                        prune()
                    } else failedKeys.add(p.key)
                    pump()
                }
            }
            return
        }
        if (token != activeToken) return
        if (error != null) { activeId?.let { fail(it, error) }; return }
        finishPart(token)
    }
    private fun finishPart(token: String) {
        if (activeToken != token) return
        audibleWatchdog?.let(main::removeCallbacks); audibleWatchdog = null
        activeToken = null; direct = false; part++; nextPart()
    }
    private fun fail(id: String, detail: String) {
        stop(); main.post { if (!closed) onFailure(id, detail) }
    }
    /** Replaces the speculative horizon; it never queues audible speech. */
    fun prefetch(texts: List<String>) {
        if (closed || !isReady || !preferences.cacheEnabled) return
        warmQueue.clear()
        texts.asSequence().flatMap { SpeechPlanning.segments(it).asSequence() }.map { it.text }
            .distinct().filter { val k = key(it); !cache.containsKey(k) && !failedKeys.contains(k) && k != pending?.key && k != processingKey }
            .take(24).forEach(warmQueue::addLast)
        pump()
    }
    private fun pump() {
        if (closed || !cacheInitialized || !isReady || !preferences.cacheEnabled || direct || pending != null || processingKey != null || warmQueue.isEmpty()) return
        val text = warmQueue.removeFirst(); val k = key(text)
        if (cache.containsKey(k) || failedKeys.contains(k)) { pump(); return }
        val id = "cache-${++seq}"; val file = File(directory, "$id.tmp")
        val p = Pending(id, k, file, epoch, SystemClock.elapsedRealtime()); pending = p
        cacheWatchdog = object : Runnable {
            override fun run() {
                if (pending?.id != id) return
                if (file.length() > 8L * 1024 * 1024 || SystemClock.elapsedRealtime() - p.started > 45_000) {
                    failedKeys.add(k); cancelInFlight(); pump()
                } else main.postDelayed(this, 500)
            }
        }.also { main.postDelayed(it, 500) }
        try {
            engine?.setSpeechRate(preferences.speechRate)
            if (engine?.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) complete(id, "préparation refusée")
        } catch (_: RuntimeException) { complete(id, "préparation indisponible") }
    }
    private fun cancelInFlight() {
        val p = pending; pending = null
        cacheWatchdog?.let(main::removeCallbacks); cacheWatchdog = null
        if (p != null) {
            try { engine?.stop() } catch (_: RuntimeException) { }
            io.execute { p.file.delete() }
        }
    }
    fun cancelPreparation() {
        epoch++; warmQueue.clear(); cancelInFlight()
        // Work already dispatched to the I/O executor validates epoch on return.
    }
    private fun prune() {
        val maximum = preferences.cacheMb * 1024L * 1024L
        val victims = mutableListOf<File>()
        val iterator = cache.entries.iterator(); var bytes = cacheBytes
        val pinned = parts.getOrNull(part)?.text?.let(::key)
        while (bytes > maximum && iterator.hasNext()) {
            val e = iterator.next(); if (e.key == pinned) continue
            bytes -= e.value.bytes; victims += e.value.file; iterator.remove()
        }
        if (victims.isNotEmpty()) io.execute { victims.forEach { it.delete() } }
    }
    fun clearCache() {
        stop(); cancelPreparation(); val files = cache.values.map { it.file }; cache.clear(); failedKeys.clear()
        io.execute { files.forEach { it.delete() } }
    }
    private fun releasePlayer() { val p = player; player = null; try { p?.release() } catch (_: RuntimeException) { } }
    fun stop() {
        activeId = null; activeToken = null; currentText = ""; direct = false; parts = emptyList(); part = 0
        audibleWatchdog?.let(main::removeCallbacks); audibleWatchdog = null
        releasePlayer(); cancelInFlight()
        if (initialized) try { engine?.stop() } catch (_: RuntimeException) { }
    }
    fun close() {
        closed = true; generation++; stop(); cancelPreparation(); main.removeCallbacksAndMessages(null)
        try { engine?.shutdown() } catch (_: RuntimeException) { }; engine = null
        io.shutdown()
    }
}
