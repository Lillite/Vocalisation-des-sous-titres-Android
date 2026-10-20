package fr.soustitresvoix.player;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Instrumentation APK only: deterministic PCM, never a production voice.
 * Plain Java is intentional: this service runs in the test APK process, whose
 * class path does not include the target APK's deduplicated Kotlin runtime.
 */
public final class FixtureSpeechService extends TextToSpeechService {
    private volatile boolean cancelled;
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return "fr".equals(lang) || "fra".equals(lang)
            ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected String[] onGetLanguage() { return new String[]{"fra", "FRA", ""}; }
    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }
    @Override protected void onStop() { cancelled = true; }
    @Override public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice("fixture-fr", Locale.FRANCE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }
    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) { return "fixture-fr"; }
    @Override public int onIsValidVoiceName(String name) {
        return "fixture-fr".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        cancelled = false;
        // The 0.3 direct-path regression requires the rate to reach the actual service,
        // not merely a UI field. This check exists only in the instrumentation APK.
        if (request.getCharSequenceText().toString().startsWith("Cette longue phrase doit accélérer sans cache")
                && request.getSpeechRate() < 175) { callback.error(); return; }
        if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] silence = new byte[Math.max(2, callback.getMaxBufferSize())];
        int remaining = 32000;
        while (remaining > 0 && !cancelled) {
            int n = Math.min(remaining, silence.length);
            if (callback.audioAvailable(silence, 0, n) != TextToSpeech.SUCCESS) return;
            remaining -= n;
        }
        if (!cancelled) callback.done();
    }
}
