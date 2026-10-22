package fr.soustitresvoix.player;

import android.content.Intent;
import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** Instrumentation APK only: deterministic PCM, never a production voice.
 * Plain Java is intentional: this service runs in the test APK process, whose
 * class path does not include the target APK's deduplicated Kotlin runtime.
 */
public final class FixtureSpeechService extends TextToSpeechService {
    public static final String OBSERVATION_ACTION = "fr.soustitresvoix.fixture.SYNTHESIS_OBSERVED";
    public static final String ALL_REQUESTS_ACTION = "fr.soustitresvoix.fixture.ALL_SYNTHESIS_REQUESTS";
    public static final String NONLOSS_PREFIX = "Non-perte — ";
    public static final String ASYNC_ERROR_PREFIX = "Non-perte — erreur asynchrone : ";
    private final Set<String> failedOnce = new HashSet<>();
    private volatile boolean cancelled;

    // Independent observation for read-only diagnostic tests: a request for any
    // text must be visible, including an accidental preview or benchmark. Keep
    // the existing prefix-filtered events and synthesis behaviour unchanged.
    private void reportAnyRequest(SynthesisRequest request) {
        String[] packages = getPackageManager().getPackagesForUid(request.getCallerUid());
        if (packages == null) return;
        for (String target : packages) {
            if ("fr.soustitresvoix.player.cinema3".equals(target)
                    || "fr.soustitresvoix.player.stable".equals(target)) {
                sendBroadcast(new Intent(ALL_REQUESTS_ACTION).setPackage(target)
                    .putExtra("text", request.getCharSequenceText().toString()));
            }
        }
    }

    // The engine is a separate test-APK process. Observe actual SynthesisRequest
    // text through explicit IPC, not through a static field in the test process.
    // Android's public SynthesisRequest does not expose the modern utterance ID;
    // the client test checks that independently in AndroidSpeech's completion.
    private void report(SynthesisRequest request, String event) {
        String text = request.getCharSequenceText().toString();
        if (!text.startsWith(NONLOSS_PREFIX)) return;
        String[] packages = getPackageManager().getPackagesForUid(request.getCallerUid());
        if (packages == null) return;
        for (String target : packages) {
            if ("fr.soustitresvoix.player.cinema3".equals(target)
                    || "fr.soustitresvoix.player.stable".equals(target)) {
                sendBroadcast(new Intent(OBSERVATION_ACTION).setPackage(target)
                    .putExtra("text", text).putExtra("event", event));
            }
        }
    }
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
        reportAnyRequest(request);
        report(request, "received");
        String requestedText = request.getCharSequenceText().toString();
        if (requestedText.startsWith(ASYNC_ERROR_PREFIX) && failedOnce.add(requestedText)) {
            // A real asynchronous engine callback, after the synthesis request
            // has reached the service. The next request for this exact text is
            // allowed, so the test can verify the direct fallback separately.
            if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                byte[] partial = new byte[32];
                callback.audioAvailable(partial, 0, partial.length);
            }
            report(request, "injected_error");
            callback.error(TextToSpeech.ERROR_SYNTHESIS);
            return;
        }
        // The 0.3 direct-path regression requires the rate to reach the actual service,
        // not merely a UI field. This check exists only in the instrumentation APK.
        if (request.getCharSequenceText().toString().startsWith("Cette longue phrase doit accélérer sans cache")
                && request.getSpeechRate() < 175) { callback.error(); return; }
        if (request.getCharSequenceText().toString().startsWith("Réglage vérifié à cent cinquante pour cent")
                && request.getSpeechRate() != 150) { callback.error(); return; }
        // Simulate an actual engine taking time to prepare one explicitly
        // requested subtitle; the player's periodic maintenance must not cancel it.
        if (request.getCharSequenceText().toString().startsWith("Préparation explicite avec narration désactivée")) {
            android.os.SystemClock.sleep(1500);
            if (cancelled) return;
        }
        if (callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] silence = new byte[Math.max(2, callback.getMaxBufferSize())];
        int remaining = 32000;
        while (remaining > 0 && !cancelled) {
            int n = Math.min(remaining, silence.length);
            if (callback.audioAvailable(silence, 0, n) != TextToSpeech.SUCCESS) return;
            remaining -= n;
        }
        if (!cancelled && callback.done() == TextToSpeech.SUCCESS) report(request, "completed");
    }
}
