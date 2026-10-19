package fr.soustitresvoix.player;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Test-APK service runs in its own process. Use only platform/Java classes:
 * the test APK does not bundle the target app's Kotlin standard library.
 * Generates exactly 500 ms of silence; never ships in the user's player APK.
 */
public final class TestTtsService extends TextToSpeechService {
    private volatile boolean cancelled;
    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return "fr".equals(lang) || "fra".equals(lang) || "fre".equals(lang)
            ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }
    @Override protected String[] onGetLanguage() { return new String[] {"fra", "FRA", ""}; }
    @Override public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        voices.add(new Voice("test-fr", Locale.FRANCE, Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL, false, Collections.<String>emptySet()));
        return voices;
    }
    @Override public int onIsValidVoiceName(String name) {
        return "test-fr".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return "test-fr";
    }
    @Override protected void onStop() { cancelled = true; }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        cancelled = false;
        if (callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] bytes = new byte[24000];
        int offset = 0;
        while (offset < bytes.length && !cancelled) {
            int count = Math.min(callback.getMaxBufferSize(), bytes.length - offset);
            if (count <= 0 || callback.audioAvailable(bytes, offset, count) != TextToSpeech.SUCCESS) return;
            offset += count;
        }
        if (!cancelled) callback.done();
    }
}
