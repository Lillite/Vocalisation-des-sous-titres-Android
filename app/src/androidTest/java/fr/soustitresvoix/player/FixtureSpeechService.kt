package fr.soustitresvoix.player

import android.media.AudioFormat
import android.speech.tts.*
import java.util.Locale

/** Instrumentation APK only. Deterministic silent PCM, not a production voice. */
class FixtureSpeechService : TextToSpeechService() {
    @Volatile private var cancelled = false
    override fun onIsLanguageAvailable(lang: String, country: String, variant: String) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onGetLanguage() = arrayOf("fra", "FRA", "")
    override fun onLoadLanguage(lang: String, country: String, variant: String) = TextToSpeech.LANG_COUNTRY_AVAILABLE
    override fun onStop() { cancelled = true }
    override fun onGetVoices(): MutableList<Voice> = mutableListOf(Voice("fixture-fr", Locale.FRANCE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String) = "fixture-fr"
    override fun onIsValidVoiceName(voiceName: String) = if (voiceName == "fixture-fr") TextToSpeech.SUCCESS else TextToSpeech.ERROR
    override fun onLoadVoice(voiceName: String) = onIsValidVoiceName(voiceName)
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        cancelled = false
        if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        val silence = ByteArray(callback.maxBufferSize.coerceAtLeast(2))
        var remaining = 32_000
        while (remaining > 0 && !cancelled) {
            val n = remaining.coerceAtMost(silence.size)
            if (callback.audioAvailable(silence, 0, n) != TextToSpeech.SUCCESS) return
            remaining -= n
        }
        if (!cancelled) callback.done()
    }
}
