package fr.soustitresvoix.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlin.math.roundToLong

/** Platform TTS boundary. Engine callbacks are always marshalled to the UI thread. */
class AndroidSpeech(
    context: Context,
    private val preferences: PlayerPreferences,
    private val onAvailability: (Boolean, String) -> Unit,
    private val onDone: (String) -> Unit,
    private val onFailure: (String, String) -> Unit
) {
    data class Choice(val name: String, val label: String, val languageTag: String)
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var closed = false
    private var activeId: String? = null
    private var watchdog: Runnable? = null
    var choices: List<Choice> = emptyList(); private set
    var language: String? = null; private set
    var isReady = false; private set

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            main.post { if (!closed) initialize(status) }
        }
    }
    private fun initialize(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            onAvailability(false, "La synthèse vocale Android n’a pas démarré. Vérifie son installation dans les réglages.")
            return
        }
        initialized = true
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { dispatch(utteranceId, null) }
            @Deprecated("Required for older TTS engines")
            override fun onError(utteranceId: String?) { dispatch(utteranceId, "erreur du moteur vocal") }
            override fun onError(utteranceId: String?, errorCode: Int) { dispatch(utteranceId, "erreur du moteur vocal ($errorCode)") }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                dispatch(utteranceId, "la voix a été interrompue par le moteur")
            }
        })
        applyPreferences()
    }
    fun applyPreferences() {
        if (!initialized || closed) return
        val tts = engine ?: return
        try {
            val installed = (tts.voices ?: emptySet()).filter {
                !it.isNetworkConnectionRequired && !(it.features ?: emptySet()).contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            choices = installed.sortedWith(compareBy<Voice> { it.locale.getDisplayLanguage(Locale.FRENCH) }
                .thenByDescending { it.quality }.thenBy { it.name })
                .map { Choice(it.name, "${it.locale.getDisplayName(Locale.FRENCH)} — ${it.name}", it.locale.toLanguageTag()) }
            val voice = installed.firstOrNull { it.name == preferences.voiceName }
                ?: installed.filter { it.locale.language == "fr" }.maxByOrNull { it.quality }
                ?: installed.firstOrNull { it.name == tts.defaultVoice?.name }
                ?: installed.firstOrNull()
            if (voice == null || tts.setVoice(voice) == TextToSpeech.ERROR) {
                isReady = false
                onAvailability(false, "Aucune voix hors ligne utilisable. Installe une voix dans les réglages vocaux Android, puis relance le lecteur.")
                return
            }
            preferences.voiceName = voice.name
            language = voice.locale.language
            if (tts.setSpeechRate(preferences.speechRate) == TextToSpeech.ERROR) {
                isReady = false; onAvailability(false, "Le moteur vocal refuse la vitesse choisie."); return
            }
            isReady = true
            onAvailability(true, "Voix : ${voice.locale.getDisplayName(Locale.FRENCH)}. Traitement local demandé.")
        } catch (e: RuntimeException) {
            isReady = false; onAvailability(false, "Le moteur vocal n’a pas fourni ses voix. Ouvre les réglages vocaux Android.")
        }
    }
    fun speak(id: String, text: String) {
        stop()
        val tts = engine
        if (!isReady || tts == null || text.length > TextToSpeech.getMaxSpeechInputLength()) {
            main.post { if (!closed) onFailure(id, "voix indisponible ou phrase trop longue") }
            return
        }
        activeId = id
        // A missing/buggy onDone callback must never freeze comfort mode forever.
        val timeoutMs = (text.length * 180.0 / preferences.speechRate + 15_000)
            .roundToLong().coerceIn(30_000L, 180_000L)
        watchdog = Runnable { dispatch(id, "le moteur vocal n’a pas répondu dans le délai de sécurité") }
            .also { main.postDelayed(it, timeoutMs) }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.voiceVolume) }
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR) dispatch(id, "le moteur vocal a refusé la phrase")
        } catch (e: RuntimeException) { dispatch(id, "impossible de transmettre la phrase au moteur vocal") }
    }
    private fun dispatch(id: String?, error: String?) {
        main.post {
            if (closed || id == null || id != activeId) return@post
            activeId = null
            watchdog?.let { main.removeCallbacks(it) }; watchdog = null
            if (error == null) onDone(id) else { engine?.stop(); onFailure(id, error) }
        }
    }
    fun stop() {
        activeId = null // invalidate BEFORE calling into another process
        watchdog?.let { main.removeCallbacks(it) }; watchdog = null
        if (initialized) try { engine?.stop() } catch (_: RuntimeException) { }
    }
    fun close() {
        closed = true; stop(); main.removeCallbacksAndMessages(null)
        try { engine?.shutdown() } catch (_: RuntimeException) { }
        engine = null
    }
}
