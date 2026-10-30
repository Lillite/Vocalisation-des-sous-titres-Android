package fr.soustitresvoix.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Point
import android.net.Uri
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.NarrationMachine
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android/Media3/indexing/narration/TTS IPC with a controlled translator.
 * No provider, account, key, personal video or network request is used. The TTS
 * fixture returns deterministic silence; no acoustic quality claim follows.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SubtitleTranslationAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val sourceTexts = listOf("EXIT — authorised staff only.", "Alice is arriving.",
        "Bob is answering.", "Alice is leaving.", "Once again.", "Once again.")
    private val frenchTexts = listOf("Non-perte — traduction du panneau ; accès réservé.",
        "Non-perte — traduction un ; Alice arrive.", "Non-perte — traduction deux ; Bob répond.",
        "Non-perte — traduction trois ; Alice repart.", "Non-perte — traduction répétée ; encore une fois.",
        "Non-perte — traduction répétée ; encore une fois.")
    private val translations get() = sourceTexts.zip(frenchTexts).toMap()

    @Before fun resetOnlyTestOwnedPreferencesAndCache() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true).putInt("orientation", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("translation-choices-v1", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
        PlayerPreferences(context).apply {
            enginePackage = instrument.context.packageName
            voiceName = "fixture-fr"
            setMode(NarrationMachine.Mode.COMFORT)
        }
    }

    private fun await(message: String, timeoutMs: Long = 25_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    private class Observation : BroadcastReceiver() {
        private val requests = Collections.synchronizedList(mutableListOf<String>())
        private val completions = Collections.synchronizedList(mutableListOf<String>())
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            if (intent.action == FixtureSpeechService.ALL_REQUESTS_ACTION) requests += text
            else if (intent.action == FixtureSpeechService.OBSERVATION_ACTION &&
                intent.getStringExtra("event") == "completed") completions += text
        }
        fun requests() = synchronized(requests) { requests.toList() }
        fun completions() = synchronized(completions) { completions.toList() }
    }

    private inner class MediaFixture : AutoCloseable {
        val video = File.createTempFile("translation-ipc-", ".mkv", context.cacheDir).apply {
            instrument.context.assets.open("ssa_nonloss/ass_nonloss_video.mkv").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        val subtitle = File.createTempFile("translation-ipc-", ".ass", context.cacheDir).apply {
            // Keep the versioned timings/styles, replace the six literal source
            // texts with English. Expectations never come from parser output.
            val old = listOf("Non-perte — panneau externe ; accès réservé.",
                "Non-perte — dialogue externe un ; Alice arrive.",
                "Non-perte — dialogue externe deux ; Bob répond.",
                "Non-perte — dialogue externe trois ; Alice repart.",
                "Non-perte — reprise externe ; encore une fois.")
            var ass = instrument.context.assets.open("ssa_nonloss/ass_nonloss_external.ass")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            for ((original, replacement) in old.zip(sourceTexts.take(5))) {
                check(ass.contains(original))
                ass = ass.replace(original, replacement)
            }
            writeText(ass, Charsets.UTF_8)
        }
        val saved = SavedMedia(Uri.fromFile(video).toString(), "Synthetic English translation IPC",
            subtitlePath = subtitle.path, subtitleMime = MimeTypes.TEXT_SSA)
        override fun close() { video.delete(); subtitle.delete() }
    }

    private fun awaitReady(activity: AtomicReference<MainActivity>) = await("Real player, selected ASS track or fixture voice not ready") {
        val session = activity.get().session
        session.player.playbackState == Player.STATE_READY && session.speech.isReady &&
            session.speech.language == "fr" && session.selectedTextId() == "external-subtitle"
    }

    @Test fun progressivePlaybackStartsBeforeCompletionBuffersAndStillVocalizesEveryOccurrence() {
        val observation = Observation()
        ContextCompat.registerReceiver(context, observation, IntentFilter().apply {
            addAction(FixtureSpeechService.ALL_REQUESTS_ACTION)
            addAction(FixtureSpeechService.OBSERVATION_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        val activity = AtomicReference<MainActivity>()
        val releaseLast = CountDownLatch(1)
        val enteredLast = CountDownLatch(1)
        try {
            MediaFixture().use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { current -> activity.set(current); current.session.open(fixture.saved) }
                    awaitReady(activity)
                    val preview = AtomicReference<SubtitleTranslationSession.Preview>()
                    scenario.onActivity { current ->
                        current.session.translation.prepareWithOptions("en", "android-progressive-nonloss",
                            progressive = true, bufferSeconds = 10) { preview.set(it) }
                    }
                    await("Progressive preview did not finish") { preview.get() != null }
                    scenario.onActivity { current ->
                        current.session.translation.start(requireNotNull(preview.get())) {
                            object : TranslationEngine {
                                override val id = "android-progressive-nonloss"
                                override val maxBatchItems = 5
                                private var calls = 0
                                override fun translate(texts: List<String>, sourceLanguage: String,
                                    targetLanguage: String, cancelled: () -> Boolean): List<String> {
                                    if (++calls == 2) { enteredLast.countDown(); check(releaseLast.await(90, TimeUnit.SECONDS)) }
                                    check(!cancelled())
                                    return texts.map { translations.getValue(it) }
                                }
                                override fun close() = Unit
                            }
                        }
                    }
                    await("A validated partial track did not become playable") {
                        enteredLast.count == 0L && activity.get().session.translation.ready
                    }
                    scenario.onActivity { current ->
                        assertFalse(current.session.translation.complete)
                        assertTrue(current.session.translation.busy)
                        assertTrue(observation.requests().isEmpty())
                        current.session.play()
                        assertTrue(current.session.machine.snapshot().wantsPlayback)
                    }
                    await("Actual playback did not hold at the untranslated occurrence", 55_000) {
                        activity.get().session.translation.waitingForTranslation
                    }
                    scenario.onActivity { current ->
                        assertFalse(current.session.translation.complete)
                        assertFalse(current.session.machine.snapshot().wantsPlayback)
                        assertFalse(current.session.player.playWhenReady)
                        assertEquals(frenchTexts.take(5), observation.requests())
                    }
                    releaseLast.countDown()
                    await("Translation did not complete") { activity.get().session.translation.complete }
                    await("Buffered playback did not finish all six translated occurrences", 45_000) {
                        val session = activity.get().session
                        session.player.playbackState == Player.STATE_ENDED &&
                            session.machine.snapshot().speakingId == null && !session.machine.snapshot().wantsPlayback &&
                            observation.completions().size >= 6
                    }
                    assertEquals(frenchTexts, observation.requests())
                    assertEquals(frenchTexts, observation.completions())
                }
            }
        } finally { releaseLast.countDown(); context.unregisterReceiver(observation) }
    }

    private fun prepare(scenario: ActivityScenario<MainActivity>, activity: AtomicReference<MainActivity>,
        id: String): SubtitleTranslationSession.Preview {
        val result = AtomicReference<SubtitleTranslationSession.Preview>()
        scenario.onActivity { current -> current.session.translation.prepare("en", id) { result.set(it) } }
        await("Complete translation preview did not finish: ${activity.get().session.translation.status}") { result.get() != null }
        return requireNotNull(result.get())
    }

    @Test fun completeFrenchTranslationReachesActualTtsOncePerPanelAndEveryRepeatedOccurrence() {
        val observation = Observation()
        ContextCompat.registerReceiver(context, observation, IntentFilter().apply {
            addAction(FixtureSpeechService.ALL_REQUESTS_ACTION)
            addAction(FixtureSpeechService.OBSERVATION_ACTION)
        }, ContextCompat.RECEIVER_EXPORTED)
        val activity = AtomicReference<MainActivity>()
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val displays = Collections.synchronizedList(mutableListOf<List<String>>())
        val calls = Collections.synchronizedList(mutableListOf<List<String>>())
        val engines = AtomicInteger()
        try {
            MediaFixture().use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { current ->
                        activity.set(current)
                        current.session.player.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { errors += error.errorCodeName }
                            override fun onCues(cueGroup: CueGroup) { displays += cueGroup.cues.mapNotNull { it.text?.toString() } }
                        })
                        current.session.open(fixture.saved)
                    }
                    awaitReady(activity)
                    val preview = prepare(scenario, activity, "android-translation-complete")
                    assertEquals(sourceTexts, preview.plan.texts)
                    assertEquals(listOf(1L, 2L, 4L, 6L, 10L, 12L).map { it * 1_000_000 }, preview.plan.cues.map { it.startUs })
                    assertEquals(6, preview.plan.cues.map { it.id }.distinct().size)
                    assertEquals(0, engines.get())
                    scenario.onActivity { current ->
                        current.session.translation.start(preview) {
                            engines.incrementAndGet()
                            object : TranslationEngine {
                                override val id = "android-translation-complete"
                                override fun translate(texts: List<String>, sourceLanguage: String,
                                    targetLanguage: String, cancelled: () -> Boolean): List<String> {
                                    check(sourceLanguage == "en" && targetLanguage == "fr")
                                    check(!cancelled())
                                    calls += texts.toList()
                                    return texts.map { translations.getValue(it) }
                                }
                                override fun close() { }
                            }
                        }
                    }
                    await("Complete French track did not activate") { activity.get().session.translation.ready }
                    instrument.waitForIdleSync()
                    scenario.onActivity { current ->
                        assertFalse(current.session.machine.snapshot().wantsPlayback)
                        assertFalse(current.session.player.playWhenReady)
                        assertTrue("Neither indexing nor activation can invoke TTS", observation.requests().isEmpty())
                        assertEquals(1, engines.get())
                        assertEquals(listOf(sourceTexts), synchronized(calls) { calls.toList() })
                        current.session.play()
                    }
                    await("Real translated playback did not end with all narration drained", 45_000) {
                        val session = activity.get().session
                        val state = session.machine.snapshot()
                        session.player.playbackState == Player.STATE_ENDED && state.speakingId == null &&
                            state.pendingCount == 0 && !state.wantsPlayback
                    }
                    await("Actual TTS service did not complete the six French requests") { observation.completions().size >= 6 }
                    instrument.waitForIdleSync()
                    assertTrue("Real player failure: $errors", errors.isEmpty())
                    assertEquals("Every request, including an unexpected original-language fallback, is observed",
                        frenchTexts, observation.requests())
                    assertEquals(frenchTexts, observation.completions())
                    val frames = synchronized(displays) { displays.toList() }
                    assertTrue("Persistent source panel must traverse several actual renderer updates",
                        frames.count { sourceTexts.first() in it } >= 4)
                    assertTrue(frames.any { sourceTexts.first() in it && sourceTexts[1] in it })
                    assertEquals(1, observation.requests().count { it == frenchTexts.first() })
                    assertEquals(2, observation.requests().count { it == frenchTexts.last() })
                }
            }
        } finally {
            context.unregisterReceiver(observation)
            PlayerPreferences(context).enginePackage = ""
        }
    }

    @Test fun cancelledTranslationAndItsOldConfirmationCannotActivateAfterRealMediaReopen() {
        val observation = Observation()
        ContextCompat.registerReceiver(context, observation, IntentFilter(FixtureSpeechService.ALL_REQUESTS_ACTION),
            ContextCompat.RECEIVER_EXPORTED)
        val activity = AtomicReference<MainActivity>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val factories = AtomicInteger()
        try {
            MediaFixture().use { fixture ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { current -> activity.set(current); current.session.open(fixture.saved) }
                    awaitReady(activity)
                    val preview = prepare(scenario, activity, "android-translation-late")
                    scenario.onActivity { current ->
                        current.session.translation.start(preview) {
                            factories.incrementAndGet()
                            object : TranslationEngine {
                                override val id = "android-translation-late"
                                override fun translate(texts: List<String>, sourceLanguage: String,
                                    targetLanguage: String, cancelled: () -> Boolean): List<String> {
                                    entered.countDown()
                                    check(release.await(15, TimeUnit.SECONDS))
                                    try { return texts.map { translations.getValue(it) } }
                                    finally { finished.countDown() }
                                }
                                // Deliberately emulate a response already in flight:
                                // cancel must still reject it after it returns.
                                override fun close() { }
                            }
                        }
                    }
                    assertTrue("Controlled translator was not entered", entered.await(20, TimeUnit.SECONDS))
                    scenario.onActivity { current ->
                        current.session.translation.cancel()
                        current.session.open(fixture.saved.copy(title = "New generation at identical URI"))
                    }
                    awaitReady(activity)
                    val nextPreview = AtomicReference<SubtitleTranslationSession.Preview>()
                    scenario.onActivity { current ->
                        // This scan queues behind the blocked translator on the
                        // same production worker. Its callback is an observable
                        // fence for every former result, without timing sleeps.
                        current.session.translation.prepare("en", "android-translation-next") { nextPreview.set(it) }
                    }
                    release.countDown()
                    assertTrue("Controlled translator was not released", finished.await(20, TimeUnit.SECONDS))
                    await("The new generation was not indexed after the obsolete response") { nextPreview.get() != null }
                    instrument.waitForIdleSync()
                    scenario.onActivity { current ->
                        current.session.translation.start(preview) { factories.incrementAndGet(); error("Obsolete factory invoked") }
                        assertEquals(1, factories.get())
                        assertFalse(current.session.translation.ready)
                        assertFalse(current.session.translation.busy)
                        assertFalse(current.session.machine.snapshot().wantsPlayback)
                        assertFalse(current.session.player.playWhenReady)
                        assertTrue(observation.requests().isEmpty())
                        assertTrue(current.session.translation.required)
                        assertEquals("translation-required", current.session.translation.fingerprint)
                    }
                }
            }
        } finally {
            release.countDown()
            context.unregisterReceiver(observation)
            PlayerPreferences(context).enginePackage = ""
        }
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }

    /** Large labels can exceed the viewport: both ends must remain reachable,
     * without requiring the entire tall view to fit at once. */
    private fun assertReachableInForm(scroll: ScrollView, view: View) {
        assertTrue("Control is outside the actual translation form", descendants(scroll).contains(view))
        assertTrue("Control has no readable layout", view.width > 0 && view.height > 0 && scroll.height > 0)
        val height = minOf(view.height, scroll.height / 2).coerceAtLeast(1)
        try {
            for (top in listOf(0, (view.height - height).coerceAtLeast(0)).distinct()) {
                val requested = Rect(0, top, view.width, top + height)
                view.requestRectangleOnScreen(Rect(requested), true)
                // Both visible rectangles use the root view's coordinates. The
                // dialog's device-screen origin is nonzero; getLocationOnScreen
                // cannot be mixed with getGlobalVisibleRect.
                val visible = Rect()
                val globalOffset = Point()
                val controlVisible = view.getGlobalVisibleRect(visible, globalOffset)
                requested.offset(globalOffset.x, globalOffset.y)
                val viewport = Rect()
                val formVisible = scroll.getGlobalVisibleRect(viewport)
                val rootOnScreen = IntArray(2); view.rootView.getLocationOnScreen(rootOnScreen)
                val details = "requestedRoot=$requested; controlVisibleRoot=$visible; formVisibleRoot=$viewport; " +
                    "viewToRoot=$globalOffset; rootOnScreen=(${rootOnScreen[0]},${rootOnScreen[1]}); " +
                    "scrollY=${scroll.scrollY}; endpointTop=$top; control=${if (view is TextView) view.text else view}"
                assertTrue("Form viewport is not visible: $details", formVisible)
                assertTrue("Control is not visible: $details", controlVisible)
                assertTrue("Requested control rectangle is clipped: $details", visible.contains(requested))
                assertTrue("Control cannot be reached by scrolling: $details", viewport.contains(requested))
            }
        } catch (failure: AssertionError) {
            // Best-effort evidence for this disposable synthetic-test emulator;
            // a capture failure never replaces or suppresses the assertion.
            runCatching {
                for (command in listOf("mkdir -p /sdcard/Download/cinema03-tests",
                    "screencap -p /sdcard/Download/cinema03-tests/translation-form-scroll-failure.png")) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command))
                        .use { it.readBytes() }
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    @Test fun actualTranslationPanelKeepsLabelsDescriptionsAndScrollableHelpAtDoubleFontScale() {
        val activity = AtomicReference<MainActivity>()
        val controls = AtomicReference<SubtitleTranslationControls>()
        val panel = AtomicReference<View>()
        val original = AtomicReference<Configuration>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { current ->
                        activity.set(current)
                        original.set(Configuration(current.resources.configuration))
                        val large = Configuration(current.resources.configuration).apply { fontScale = 2f }
                        current.resources.updateConfiguration(large, current.resources.displayMetrics)
                        controls.set(SubtitleTranslationControls(current, current.session).also { it.show() })
                    }
                    await("Actual translation dialog was not laid out") {
                        val root = WindowInspector.getGlobalWindowViews().firstOrNull { window ->
                            val views = descendants(window)
                            views.filterIsInstance<Spinner>().any { it.contentDescription == "Moteur de traduction" } &&
                                views.filterIsInstance<TextView>().any { it.text.toString() == "Traduire les sous-titres en français" }
                        }
                        if (root != null && root.width > 0) panel.set(root)
                        panel.get() != null
                    }
                    val expectedFieldLabels = listOf(
                        "Langue originale — code explicite, par exemple en, es, de, it, sr-Latn ou sr-Cyrl",
                        "Avance de traduction avant lecture ou reprise — 10 à 120 secondes, défaut 30",
                        "Google en ligne : délai minimal entre requêtes — 500 à 10 000 ms, défaut 1 000",
                        "Plafond de nouveaux caractères UTF-16 pour cette tâche — vide : aucune limite locale supplémentaire",
                        "DeepL : contexte de l’œuvre ou de la scène, facultatif — envoyé avec chaque lot, 24 000 caractères maximum",
                        "DeepL : identifiant d’un glossaire déjà créé dans TON compte, facultatif",
                        "DeepL : révision du glossaire — change-la après une modification du glossaire distant",
                        "DeepL : modèle facultatif — quality_optimized, prefer_quality_optimized ou latency_optimized",
                        "DeepL : registre — default, more, less, prefer_more ou prefer_less",
                        "Clé API personnelle — masquée ; laisse vide pour tenter une piste déjà en cache",
                        "Région Azure — code de la ressource, par exemple francecentral ; vide uniquement si la clé est globale",
                        "Adresse de base du serveur LibreTranslate — HTTPS, sans identifiant, paramètres ou fragment",
                        "Couches GPU : -1 pour toutes, sinon 1 à 1024 (le moteur borne au modèle)",
                        "Lot logique : 16 à 512 tokens",
                        "Micro-lot : 16 à 128 tokens, inférieur ou égal au lot logique",
                        "GGUF local : threads de calcul — 1 à 8, défaut 4",
                        "GGUF local : contexte de l’œuvre ou de la scène, facultatif — reste sur le téléphone"
                    )
                    val nestedMemoryLabels = expectedFieldLabels.subList(12, 15).toSet()
                    val compactCacheLabel = "Cache d’attention locale compact — expérimental pour les modèles à attention locale"
                    val memoryPresetLabels = listOf("Lots standard : 512 et 128 tokens",
                        "Réduire la mémoire temporaire : 128 et 32 tokens")
                    lateinit var fieldsByLabel: Map<String, EditText>
                    scenario.onActivity {
                        val views = descendants(panel.get())
                        val fields = views.filterIsInstance<EditText>()
                        val labels = fields.map { field ->
                            views.filterIsInstance<TextView>().single { it.labelFor == field.id }.text.toString()
                        }
                        // Assert every old and new field, not merely a revised total.
                        assertEquals(expectedFieldLabels, labels)
                        fieldsByLabel = labels.zip(fields).toMap()
                        assertEquals(1, fields.count { it.transformationMethod is PasswordTransformationMethod })
                        assertTrue(fieldsByLabel.getValue(expectedFieldLabels[9]).transformationMethod is PasswordTransformationMethod)
                        val progressive = views.filterIsInstance<CheckBox>().single {
                            it.text.toString() == "Traduction progressive : regarder pendant la traduction"
                        }
                        assertFalse("Progressive translation must remain an explicit choice", progressive.isChecked)
                    }
                    val providers = TranslationProvider.values()
                    assertEquals(listOf("azure", "deepl-free", "deepl-pro", "libre", "google-web-v1", "google-local-v1", "local-gguf-v1"), providers.map { it.id })
                    for ((index, provider) in providers.withIndex()) {
                        scenario.onActivity {
                            descendants(panel.get()).filterIsInstance<Spinner>().single { it.contentDescription == "Moteur de traduction" }.setSelection(index)
                        }
                        await("Specific provider help was not displayed for ${provider.id}") {
                            descendants(panel.get()).filterIsInstance<TextView>()
                                .any { it.text.toString() == SubtitleTranslationControls.description(provider) && it.layout != null }
                        }
                        instrument.waitForIdleSync()
                        if (provider == TranslationProvider.LOCAL_GGUF) {
                            await("New GGUF memory controls were not laid out after selecting the provider") {
                                val memoryControls = descendants(panel.get()).filterIsInstance<TextView>().filter {
                                    it.text.toString() in memoryPresetLabels || it.text.toString() == compactCacheLabel ||
                                        it.text.toString() in nestedMemoryLabels
                                }
                                memoryControls.size == 6 && memoryControls.all {
                                    it.isShown && it.layout != null && it.width > 0 && it.height > 0
                                }
                            }
                        }
                        scenario.onActivity { current ->
                            assertEquals(2f, current.resources.configuration.fontScale, .01f)
                            val views = descendants(panel.get())
                            val help = views.filterIsInstance<TextView>()
                                .single { it.text.toString() == SubtitleTranslationControls.description(provider) }
                            assertTrue("Provider help must span several readable lines", help.layout.lineCount > 1)
                            for (line in 0 until help.layout.lineCount) assertEquals(0, help.layout.getEllipsisCount(line))
                            val visibleLabels = setOf(expectedFieldLabels[0], expectedFieldLabels[1], expectedFieldLabels[3]) +
                                (if (provider.isGoogle || provider.isLocal) emptySet() else setOf(expectedFieldLabels[9])) + when (provider) {
                                    TranslationProvider.AZURE -> setOf(expectedFieldLabels[10])
                                    TranslationProvider.DEEPL_FREE, TranslationProvider.DEEPL_PRO -> expectedFieldLabels.subList(4, 9).toSet()
                                    TranslationProvider.LIBRE -> setOf(expectedFieldLabels[11])
                                    TranslationProvider.GOOGLE_WEB -> setOf(expectedFieldLabels[2])
                                    TranslationProvider.GOOGLE_LOCAL -> emptySet()
                                    TranslationProvider.LOCAL_GGUF -> expectedFieldLabels.subList(12, 17).toSet()
                                }
                            val remember = views.filterIsInstance<CheckBox>().single {
                                it.text.toString() == "Mémoriser cette clé, chiffrée avec Android Keystore sur ce téléphone"
                            }
                            assertEquals(if (provider.isGoogle || provider.isLocal) View.GONE else View.VISIBLE, remember.visibility)
                            val mobileModels = views.filterIsInstance<CheckBox>().single {
                                it.text.toString() == "Google local : autoriser les données mobiles pour télécharger les modèles"
                            }
                            assertEquals(if (provider == TranslationProvider.GOOGLE_LOCAL) View.VISIBLE else View.GONE, mobileModels.visibility)
                            assertFalse("Model downloads on mobile data remain opt-in", mobileModels.isChecked)
                            val compactCache = views.filterIsInstance<CheckBox>().single { it.text.toString() == compactCacheLabel }
                            assertFalse("Compact attention cache must remain an explicit experimental choice", compactCache.isChecked)
                            assertEquals("Compact-cache visibility must follow the entire GGUF group",
                                provider == TranslationProvider.LOCAL_GGUF, compactCache.isShown)
                            val memoryPresets = memoryPresetLabels.map { title ->
                                views.filterIsInstance<Button>().single { it.text.toString() == title }
                            }
                            for (preset in memoryPresets) assertEquals("Memory presets must follow the GGUF group",
                                provider == TranslationProvider.LOCAL_GGUF, preset.isShown)
                            assertEquals("-1", fieldsByLabel.getValue(expectedFieldLabels[12]).text.toString())
                            assertEquals("512", fieldsByLabel.getValue(expectedFieldLabels[13]).text.toString())
                            assertEquals("128", fieldsByLabel.getValue(expectedFieldLabels[14]).text.toString())
                            assertEquals("30", fieldsByLabel.getValue(expectedFieldLabels[1]).text.toString())
                            assertEquals("1000", fieldsByLabel.getValue(expectedFieldLabels[2]).text.toString())
                            val scroll = requireNotNull(panel.get().findViewById<ScrollView>(R.id.translation_controls_scroll))
                            assertReachableInForm(scroll, help)
                            for ((labelText, field) in fieldsByLabel) {
                                val label = views.filterIsInstance<TextView>().single { it.labelFor == field.id }
                                assertEquals(labelText, label.text.toString())
                                val expectedVisibility = if (labelText in visibleLabels) View.VISIBLE else View.GONE
                                if (labelText in nestedMemoryLabels) {
                                    // These controls are nested inside the GGUF group. That group
                                    // hides them together; their own visibility need not be GONE.
                                    assertEquals("Nested field visibility for ${provider.id}: $labelText",
                                        expectedVisibility == View.VISIBLE, field.isShown)
                                    assertEquals("Nested label visibility for ${provider.id}: $labelText",
                                        expectedVisibility == View.VISIBLE, label.isShown)
                                } else {
                                    assertEquals("Field visibility for ${provider.id}: $labelText", expectedVisibility, field.visibility)
                                    assertEquals("Label visibility for ${provider.id}: $labelText", expectedVisibility, label.visibility)
                                }
                                if (expectedVisibility == View.VISIBLE) {
                                    assertNotNull(label.layout)
                                    for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
                                    assertReachableInForm(scroll, label)
                                    assertReachableInForm(scroll, field)
                                }
                            }
                            if (provider == TranslationProvider.LOCAL_GGUF) {
                                for (control in listOf<TextView>(compactCache) + memoryPresets) {
                                    assertTrue("Memory controls need a readable multiline layout", control.layout != null)
                                    for (line in 0 until control.layout.lineCount) assertEquals(0, control.layout.getEllipsisCount(line))
                                    assertReachableInForm(scroll, control)
                                }
                            }
                            assertReachableInForm(scroll, views.filterIsInstance<CheckBox>().single {
                                it.text.toString() == "Traduction progressive : regarder pendant la traduction"
                            })
                        }
                    }
                    scenario.onActivity { current ->
                        val fields = fieldsByLabel.values
                        // AlertDialog also owns an internal message ScrollView on
                        // Android 36. Check the actual form, identified explicitly.
                        val scroll = requireNotNull(panel.get().findViewById<ScrollView>(R.id.translation_controls_scroll))
                        assertTrue(descendants(scroll).containsAll(fields))
                        assertTrue(scroll.getChildAt(0).height > scroll.height)
                        scroll.fullScroll(View.FOCUS_DOWN)
                        assertFalse(current.session.translation.busy)
                        assertFalse(current.session.translation.required)
                        assertFalse(current.session.player.playWhenReady)
                    }
                    await("Help at the bottom cannot be reached by scrolling") {
                        panel.get().findViewById<ScrollView>(R.id.translation_controls_scroll)?.scrollY?.let { it > 0 } == true
                    }
                } finally {
                    scenario.onActivity { current ->
                        controls.get()?.close()
                        original.get()?.let { current.resources.updateConfiguration(it, current.resources.displayMetrics) }
                    }
                }
            }
        } finally { PlayerPreferences(context).enginePackage = "" }
    }
}
