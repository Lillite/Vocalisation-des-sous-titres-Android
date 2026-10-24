package fr.soustitresvoix.player

import android.content.Context
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.SpeechDurationModel

data class SpeechVoiceChoice(val name: String, val label: String, val languageTag: String)
data class SpeechEngineChoice(val name: String, val label: String)

/**
 * Voice output and preparation used by the session and its settings UI.
 * Commands run on the session's main thread. Implementations must deliver callbacks
 * on that thread, never from inside their constructor or reentrantly from speak().
 * An utterance id belongs to its request, including failures and stale callbacks.
 * Duration models and latency windows are stable instances for this service's life.
 */
interface SpeechService {
    val durations: SpeechDurationModel
    val synthesisLatency: LatencyWindow
    val firstAudioLatency: LatencyWindow
    val cacheLatency: LatencyWindow
    val timingSource: String
    val bulkPreparation: Boolean
    val preparedAudioMs: Long
    val cacheConfigurationFingerprint: String
    val engineDiagnosticIdentity: String
    val choices: List<SpeechVoiceChoice>
    val language: String?
    val isReady: Boolean
    val hits: Int
    val misses: Int
    val preparedCount: Int
    val lastSynthesisMs: Long
    val lastAudioMs: Long
    val lastFactor: Float
    val cacheSize: Long
    val preparationStatus: String
    fun engines(): List<SpeechEngineChoice>
    fun retry()
    fun applyPreferences()
    fun speak(id: String, text: String, availableMs: Long? = null, adaptive: Boolean = false,
        remaining: String = "", forecast: Float = 1f, budget: (() -> Long?)? = null,
        planning: ((Long) -> Float)? = null)
    fun updateAdaptive()
    fun prepareBatch(texts: List<String>)
    fun prepareAhead(texts: List<String>)
    fun cancelPreparation(keepPending: Boolean = false)
    fun stop()
    fun clearCache()
    fun close()
}

/** Keeps AndroidSpeech, its cache, callback guards and adaptive timing unchanged. */
class AndroidSpeechService(
    context: Context, preferences: PlayerPreferences,
    onAvailability: (Boolean, String) -> Unit,
    onDone: (String) -> Unit, onFailure: (String, String) -> Unit
) : SpeechService {
    private val speech = AndroidSpeech(context, preferences, onAvailability, onDone, onFailure)
    override val durations get() = speech.durations
    override val synthesisLatency get() = speech.synthesisLatency
    override val firstAudioLatency get() = speech.firstAudioLatency
    override val cacheLatency get() = speech.cacheLatency
    override val timingSource get() = speech.timingSource
    override val bulkPreparation get() = speech.bulkPreparation
    override val preparedAudioMs get() = speech.preparedAudioMs
    override val cacheConfigurationFingerprint get() = speech.cacheConfigurationFingerprint
    override val engineDiagnosticIdentity get() = speech.engineDiagnosticIdentity
    override val choices get() = speech.choices.map { SpeechVoiceChoice(it.name, it.label, it.languageTag) }
    override val language get() = speech.language
    override val isReady get() = speech.isReady
    override val hits get() = speech.hits
    override val misses get() = speech.misses
    override val preparedCount get() = speech.preparedCount
    override val lastSynthesisMs get() = speech.lastSynthesisMs
    override val lastAudioMs get() = speech.lastAudioMs
    override val lastFactor get() = speech.lastFactor
    override val cacheSize get() = speech.cacheSize
    override val preparationStatus get() = speech.preparationStatus
    override fun engines() = speech.engines().map { SpeechEngineChoice(it.name, it.label) }
    override fun retry() = speech.retry()
    override fun applyPreferences() = speech.applyPreferences()
    override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
        remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) =
        speech.speak(id, text, availableMs, adaptive, remaining, forecast, budget, planning)
    override fun updateAdaptive() = speech.updateAdaptive()
    override fun prepareBatch(texts: List<String>) = speech.prepareBatch(texts)
    override fun prepareAhead(texts: List<String>) = speech.prepareAhead(texts)
    override fun cancelPreparation(keepPending: Boolean) = speech.cancelPreparation(keepPending)
    override fun stop() = speech.stop()
    override fun clearCache() = speech.clearCache()
    override fun close() = speech.close()
}
