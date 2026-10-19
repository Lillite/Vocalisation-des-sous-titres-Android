package fr.soustitresvoix.player

import android.media.AudioFormat
import android.speech.tts.*
import java.util.Locale

/** Produces deterministic silence for integration tests, not a natural speech model. */
class TestTtsService : TextToSpeechService() {
    @Volatile private var cancelled = false
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?) =
        if (lang in listOf("fr", "fra", "fre")) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?) = onIsLanguageAvailable(lang, country, variant)
    override fun onGetLanguage() = arrayOf("fra", "FRA", "")
    override fun onGetVoices() = mutableListOf(Voice("test-fr", Locale.FRANCE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
    override fun onIsValidVoiceName(name: String?) = if (name == "test-fr") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(name: String?) = onIsValidVoiceName(name)
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?) = "test-fr"
    override fun onStop() { cancelled = true }
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        cancelled = false
        if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        val bytes = ByteArray(24000) // exactly half a second of mono PCM16
        var index = 0
        while (index < bytes.size && !cancelled) {
            val n = minOf(callback.maxBufferSize, bytes.size - index)
            if (callback.audioAvailable(bytes, index, n) != TextToSpeech.SUCCESS) return
            index += n
        }
        if (!cancelled) callback.done()
    }
}
