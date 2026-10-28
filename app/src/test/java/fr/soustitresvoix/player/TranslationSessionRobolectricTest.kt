package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.RealCueTimeline
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.ExistingNarrationController
import fr.soustitresvoix.core.LatencyWindow
import fr.soustitresvoix.core.NarrationController
import fr.soustitresvoix.core.NarrationMachine
import fr.soustitresvoix.core.SpeechDurationModel
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Real session, Media3 source selection, ASS parser, complete index and narration
 * controller. Only the translation/voice boundaries are controlled. No paid API,
 * external TTS audio, device performance or TalkBack result is claimed here.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class TranslationSessionRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val french = mapOf("EXIT" to "SORTIE", "Hello." to "Bonjour.", "Yes." to "Oui.")

    @Before fun resetPreferencesAndOwnedTranslationCache() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", true)
            .putString("mode", NarrationMachine.Mode.ADAPTIVE.name)
            .putInt("duckAttack", 0).putInt("duckRelease", 0).putInt("duckHold", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
        File(context.filesDir, "translation-progress-v1").deleteRecursively()
        File(context.filesDir, "translations-retained-v1").deleteRecursively()
        context.getSharedPreferences("translation-display-v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    private inner class Fixture(private val subtitlesText: String = assText()) : AutoCloseable {
        val components = Components()
        val messages = mutableListOf<Pair<String, Boolean>>()
        val captions = mutableListOf<String>()
        val session = PlaybackSession(context, PlayerPreferences(context), {}, { text, error -> messages += text to error },
            { captions += it }, components)
        val file = File.createTempFile("translation-synthetic-", ".ass", context.cacheDir).apply {
            writeText(subtitlesText)
        }
        val media = SavedMedia("android.resource://${context.packageName}/${R.raw.demonstration}",
            "Synthetic translation fixture", subtitlePath = file.path, subtitleMime = MimeTypes.TEXT_SSA)
        init {
            components.speech.ready(true)
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            session.open(media)
            await("Media3 did not load the selected external ASS fixture") {
                session.player.playbackState == Player.STATE_READY && session.selectedTextId() == "external-subtitle"
            }
        }
        fun prepare(provider: String = "controlled"): SubtitleTranslationSession.Preview {
            var result: SubtitleTranslationSession.Preview? = null
            session.translation.prepare("en", provider) { result = it }
            await("Complete selected track was not prepared: ${session.translation.status}") { result != null }
            return result!!
        }
        fun translate(): SubtitleTranslationSession.Preview {
            val preview = prepare()
            session.translation.start(preview) { Engine() }
            await("Complete French translation was not activated: ${session.translation.status}") {
                session.translation.ready && session.player.playbackState == Player.STATE_READY
            }
            assertFalse("Translation completion cannot start playback", session.machine.snapshot().wantsPlayback)
            assertTrue("Translation completion cannot speak", components.speech.requests.isEmpty())
            return preview
        }
        fun playFrames() {
            session.play()
            for (frame in frames(file.readBytes())) {
                components.backend.deliver(frame)
                var guard = 0
                while (session.machine.snapshot().speakingId != null) {
                    assertTrue("Narration must make progress", ++guard < 50)
                    components.speech.complete(session.machine.snapshot().speakingId!!)
                }
            }
        }
        override fun close() { session.close(); file.delete() }
    }

    @Test fun completeFrenchNarrationRetainsPersistentPanelSimultaneousAndLaterRepeatedOccurrences() {
        Fixture().use { fixture ->
            val preview = fixture.translate()
            val items = fixture.session.ahead.selected("external-subtitle")
            assertEquals(5, items.size)
            assertEquals(5, items.map { it.identity }.distinct().size)
            assertEquals(preview.plan.cues.map { it.id }, items.map { it.identity })
            assertEquals(listOf("EXIT", "Hello.", "Yes.", "Yes.", "Hello."), preview.plan.texts)
            fixture.playFrames()
            assertEquals(listOf("SORTIE", "Bonjour.", "Oui.", "Oui.", "Bonjour."),
                fixture.components.speech.requests.map { it.second })
            assertTrue(fixture.components.speech.requests.none { it.second in french.keys })
            val published = fixture.components.narration.frames.filter { it.texts.isNotEmpty() }
            assertTrue(published.isNotEmpty())
            assertEquals(preview.plan.cues.map { it.id }.toSet(), published.flatMap { it.identities.orEmpty() }.toSet())
            assertTrue(published.flatMap { it.texts }.all { it in french.values })
            // The persistent panel appears in several display intervals but is spoken once.
            assertTrue(published.count { "SORTIE" in it.texts } > 1)
            assertEquals(1, fixture.components.speech.requests.count { it.second == "SORTIE" })
        }
    }

    @Test fun googleReviewAndEditorKeepAttributionEvenWhenVideoCaptionsUseOriginalOrHiddenMode() {
        Fixture().use { fixture ->
            val preview = fixture.prepare(TranslationProvider.GOOGLE_WEB.id)
            fixture.session.translation.start(preview) { Engine(id = TranslationProvider.GOOGLE_WEB.id) }
            await("Controlled Google translation did not complete") { fixture.session.translation.complete }
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val controls = SubtitleTranslationControls(controller.get(), fixture.session)
            fun descendants(view: View): List<View> = buildList {
                add(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
            }
            fun button(dialog: AlertDialog, title: String) = descendants(dialog.window!!.decorView)
                .filterIsInstance<Button>().single { it.text.toString() == title }
            fun assertBadge(dialog: AlertDialog) {
                val badge = descendants(dialog.window!!.decorView).filterIsInstance<ImageView>()
                    .single { it.contentDescription == "Traduction automatique fournie par Google Traduction" }
                assertEquals(View.VISIBLE, badge.visibility)
                assertNotNull(badge.drawable)
            }
            try {
                for (mode in listOf(TranslationDisplayMode.ORIGINAL, TranslationDisplayMode.HIDDEN)) {
                    fixture.session.translation.setDisplayMode(mode)
                    assertTrue(fixture.session.translation.googleTranslationActive)
                    assertFalse(fixture.session.translation.googleAttributionRequired)
                    controls.show()
                    val panel = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                    button(panel, "Vérifier les alertes linguistiques").performClick()
                    requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertBadge(it); it.dismiss() }
                    button(panel, "Corriger une réplique de la traduction complète").performClick()
                    requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    // AlertDialog dispatches its positive-button listener through the main Handler.
                    shadowOf(Looper.getMainLooper()).idle()
                    requireNotNull(ShadowAlertDialog.getLatestAlertDialog()).also { assertBadge(it); it.dismiss() }
                    controls.close()
                }
                for (mode in listOf(TranslationDisplayMode.TRANSLATED, TranslationDisplayMode.BILINGUAL)) {
                    fixture.session.translation.setDisplayMode(mode)
                    assertTrue(fixture.session.translation.googleAttributionRequired)
                }
                fixture.session.translation.useOriginal()
                assertFalse(fixture.session.translation.googleTranslationActive)
                assertFalse(fixture.session.translation.googleAttributionRequired)
                assertTrue(fixture.components.speech.requests.isEmpty())
            } finally { controls.close(); controller.pause().stop().destroy() }
        }
    }

    @Test fun unmappedDisplayedOccurrenceStopsPlaybackWithoutAnyOriginalLanguageFallback() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.play()
            fixture.components.backend.deliver(CueGroup(listOf(Cue.Builder().setText("Unindexed original phrase").build()), 2_000_000))
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.player.playWhenReady)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("aucune substitution") })
            assertTrue(fixture.session.translation.required)
        }
    }

    @Test fun frenchVoiceMustBeReadyAndExplicitOriginalCommandIsRequiredToLeaveTranslationMode() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.components.speech.voiceLanguage = "en"
            assertFalse(fixture.session.translation.allowNarrationVoice())
            fixture.session.play()
            fixture.session.resumeVoice()
            fixture.session.repeatLast()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("voix française") })
            fixture.components.speech.voiceLanguage = "fr"
            fixture.components.speech.ready(false)
            assertFalse(fixture.session.translation.allowPlayback())
            assertFalse(fixture.session.translation.allowNarrationVoice())
            fixture.components.speech.ready(true)
            assertTrue(fixture.session.translation.allowPlayback())
            assertTrue(fixture.session.translation.allowNarrationVoice())
            fixture.session.translation.useOriginal()
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertEquals("original", fixture.session.translation.fingerprint)
        }
    }

    @Test fun cancelledPreviewCannotConstructAnEngineOrFallBackToTheOriginalTrack() {
        Fixture().use { fixture ->
            val preview = fixture.prepare()
            fixture.session.translation.cancel()
            var engines = 0
            fixture.session.translation.start(preview) { engines++; Engine() }
            assertEquals(0, engines)
            assertTrue(fixture.session.translation.required)
            assertFalse(fixture.session.translation.ready)
            fixture.session.play()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun incompleteEngineResultCannotActivateOrSpeakAnySourceText() {
        Fixture().use { fixture ->
            val preview = fixture.prepare()
            fixture.session.translation.start(preview) { Engine { emptyList() } }
            await("Invalid translation worker did not finish") { !fixture.session.translation.busy }
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.session.translation.required)
            fixture.session.play()
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.messages.any { it.second && it.first.contains("incomplète") })
        }
    }

    @Test fun localPerformanceNeverStartsPlaybackAndLateMeasurementsCannotSurviveReset() {
        Fixture().use { fixture ->
            val preview = fixture.prepare("measured-local-fixture")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val observations = java.util.concurrent.atomic.AtomicReference<((LocalTranslationPerformance) -> Unit)?>(null)
            val service = object : TranslationEngine, LocalTranslationPerformanceSource {
                override val id = "measured-local-fixture"
                override fun setPerformanceListener(listener: ((LocalTranslationPerformance) -> Unit)?) { observations.set(listener) }
                override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                    cancelled: () -> Boolean): List<String> {
                    observations.get()?.invoke(LocalTranslationPerformance(backend = "CPU", generatedTokens = 4,
                        generationMs = 100, promptTokens = 20, promptEvaluatedTokens = 20))
                    entered.countDown()
                    assertTrue(release.await(15, TimeUnit.SECONDS))
                    return texts.map { french.getValue(it) }
                }
                override fun close() {
                    if (Looper.myLooper() != Looper.getMainLooper()) finished.countDown()
                }
            }
            try {
                fixture.session.translation.start(preview) { service }
                await("Current local measurement not displayed") {
                    entered.count == 0L && fixture.session.translation.performance?.contains("CPU") == true
                }
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertTrue(fixture.components.speech.requests.isEmpty())
                fixture.session.translation.useOriginal()
                assertNull(fixture.session.translation.performance)
                observations.get()?.invoke(LocalTranslationPerformance(backend = "Obsolete GPU"))
                shadowOf(Looper.getMainLooper()).idle()
                assertNull("An obsolete worker cannot restore former diagnostics", fixture.session.translation.performance)
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertTrue(fixture.components.speech.requests.isEmpty())
            } finally { release.countDown() }
            await("Measured worker did not close") { finished.count == 0L }
        }
    }

    @Test fun lateTranslationAfterCancelTrackChangeMediaReplacementOrCloseNeverActivatesOrResumes() {
        for (action in listOf("cancel", "track", "media", "close")) {
            Fixture().use { fixture ->
                File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
                val preview = fixture.prepare("blocking-$action")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(1)
                fixture.session.translation.start(preview) {
                    Engine(id = "blocking-$action", completed = finished) { texts ->
                        entered.countDown()
                        assertTrue("Controlled translation was not released", release.await(10, TimeUnit.SECONDS))
                        texts.map { french.getValue(it) }
                    }
                }
                await("Controlled translator was not entered") { entered.count == 0L }
                when (action) {
                    "cancel" -> fixture.session.translation.cancel()
                    "track" -> fixture.session.selectTrack(C.TRACK_TYPE_TEXT, null, disabled = true)
                    "media" -> fixture.session.open(fixture.media.copy(title = "New media generation"))
                    "close" -> fixture.session.close()
                }
                release.countDown()
                await("Obsolete translator did not close") { finished.count == 0L }
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(action, fixture.session.translation.ready)
                assertFalse(action, fixture.session.translation.busy)
                assertFalse(action, fixture.session.machine.snapshot().wantsPlayback)
                assertTrue(action, fixture.components.speech.requests.isEmpty())
                if (action == "cancel" || action == "track") assertTrue(action, fixture.session.translation.required)
            }
        }
    }

    @Test fun selectedExternalOrEmbeddedTrackDeterminesWhichCompleteSourceIsScanned() {
        Fixture().use { fixture ->
            val external = fixture.prepare()
            assertEquals("external-subtitle", external.track)
            assertEquals(fixture.media, fixture.components.subtitles.scans.last())
            val selected = fixture.session.player.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_TEXT }
                .first { group -> (0 until group.length).any { !fixture.session.isExternalSubtitle(group.getTrackFormat(it)) } }
            val index = (0 until selected.length).first { !fixture.session.isExternalSubtitle(selected.getTrackFormat(it)) }
            fixture.session.selectTrack(C.TRACK_TYPE_TEXT, selected, index)
            await("Embedded text track was not selected") { fixture.session.selectedTextId() != null && fixture.session.selectedTextId() != "external-subtitle" }
            val embedded = fixture.prepare()
            assertNotEquals("external-subtitle", embedded.track)
            assertNull(fixture.components.subtitles.scans.last().subtitlePath)
            assertNull(fixture.components.subtitles.scans.last().subtitleMime)
            assertEquals(fixture.media.uri, fixture.components.subtitles.scans.last().uri)
            var engines = 0
            fixture.session.translation.start(external) { engines++; Engine() }
            assertEquals("An old confirmation cannot send the former track", 0, engines)
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun completeVoicePreparationAndAnticipationUseOnlyTheFrenchTranslation() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.prepareRange(null)
            await("French speech batch was not prepared") { fixture.components.speech.batches.isNotEmpty() }
            assertEquals(listOf("SORTIE", "Bonjour.", "Oui."), fixture.components.speech.batches.last())
            fixture.session.play()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            fixture.session.prepareTick()
            assertTrue(fixture.components.speech.ahead.isNotEmpty())
            assertTrue(fixture.components.speech.ahead.flatten().all { it in french.values })
            assertTrue(fixture.components.speech.batches.flatten().none { it in french.keys })
        }
    }

    @Test fun currentlyDisplayedSubtitleIsRearmedInFrenchAndAfterExplicitReturnToOriginal() {
        Fixture().use { fixture ->
            fixture.session.seek(2500)
            await("The source cue at the current paused position was not rendered") {
                fixture.session.player.currentCues.cues.mapNotNull { it.text?.toString() }.contains("Hello.")
            }
            fixture.translate()
            await("Activation lost the currently displayed translated occurrence") {
                fixture.session.player.playbackState == Player.STATE_READY && fixture.session.machine.pendingTexts().contains("Bonjour.")
            }
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            fixture.session.play()
            var guard = 0
            while (fixture.session.machine.snapshot().speakingId != null) {
                assertTrue(++guard < 20)
                fixture.components.speech.complete(fixture.session.machine.snapshot().speakingId!!)
            }
            assertEquals(listOf("SORTIE", "Bonjour."), fixture.components.speech.requests.map { it.second })
            fixture.session.pause()
            fixture.session.translation.useOriginal()
            await("Explicit original mode lost the currently displayed source occurrence") {
                fixture.session.player.playbackState == Player.STATE_READY && fixture.session.machine.pendingTexts().contains("Hello.")
            }
            assertFalse(fixture.session.translation.required)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertEquals(2, fixture.components.speech.requests.size)
        }
    }

    @Test fun changingOnlyTheFilmAudioTrackRetainsTheCurrentlyDisplayedFrenchOccurrences() {
        Fixture().use { fixture ->
            fixture.session.seek(2500)
            await("The source cue at the current paused position was not rendered") {
                fixture.session.player.currentCues.cues.mapNotNull { it.text?.toString() }.contains("Hello.")
            }
            fixture.translate()
            await("The current French occurrence was not initially queued") {
                fixture.session.machine.pendingTexts().contains("Bonjour.")
            }
            val fingerprint = fixture.session.translation.fingerprint
            fixture.session.selectTrack(C.TRACK_TYPE_AUDIO, null)
            await("An audio-only track command lost the currently displayed French occurrence") {
                fixture.session.player.playbackState == Player.STATE_READY && fixture.session.machine.pendingTexts().contains("Bonjour.")
            }
            assertTrue(fixture.session.translation.ready)
            assertTrue(fixture.session.translation.required)
            assertEquals(fingerprint, fixture.session.translation.fingerprint)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
            fixture.session.play()
            var guard = 0
            while (fixture.session.machine.snapshot().speakingId != null) {
                assertTrue(++guard < 20)
                fixture.components.speech.complete(fixture.session.machine.snapshot().speakingId!!)
            }
            assertEquals(listOf("SORTIE", "Bonjour."), fixture.components.speech.requests.map { it.second })
            assertTrue(fixture.components.speech.requests.none { it.second in french.keys })
        }
    }

    @Test fun originalRendererCallbacksWhileWaitingForTranslationDoNotSpeakOrReportAFailedMapping() {
        Fixture().use { fixture ->
            fixture.session.seek(2500)
            await("The source cue at the current paused position was not rendered") {
                fixture.session.player.currentCues.cues.mapNotNull { it.text?.toString() }.contains("Hello.")
            }
            val current = fixture.session.player.currentCues
            val preview = fixture.prepare("waiting")
            fixture.messages.clear()
            fixture.components.backend.deliver(current)
            fixture.session.pause()
            assertTrue(fixture.session.translation.required)
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.session.machine.pendingTexts().isEmpty())
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertEquals("", fixture.session.lastRawCaption)
            assertFalse(fixture.messages.any { it.second || it.first.contains("Préparation arrêtée") })

            // A manual pause also leaves an independent translation worker
            // running; it must not announce that this preparation was stopped.
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.session.translation.start(preview) {
                Engine(id = "waiting") { texts ->
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    texts.map { french.getValue(it) }
                }
            }
            try {
                await("Controlled waiting translator was not entered") { entered.count == 0L }
                fixture.messages.clear()
                fixture.components.backend.deliver(current)
                fixture.session.pause()
                assertTrue(fixture.session.translation.busy)
                assertTrue(fixture.session.translation.required)
                assertFalse(fixture.session.translation.ready)
                assertTrue(fixture.session.machine.pendingTexts().isEmpty())
                assertTrue(fixture.components.speech.requests.isEmpty())
                assertFalse(fixture.messages.any { it.second || it.first.contains("Préparation arrêtée") })
            } finally { release.countDown() }
            await("The paused independent translation worker did not complete") { fixture.session.translation.ready }
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun translatedPlaybackWithoutNarrationDoesNotRequireAReadyFrenchVoice() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.prefs.narration = false
            fixture.session.refreshSettings()
            fixture.components.speech.voiceLanguage = "en"
            fixture.components.speech.ready(false)
            assertTrue(fixture.session.translation.allowPlayback())
            fixture.playFrames()
            assertTrue(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.machine.snapshot().enabled)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.components.narration.frames.flatMap { it.texts }.all { it in french.values })
            assertFalse(fixture.messages.any { it.second && it.first.contains("voix française") })
        }
    }

    @Test fun aDivergentCompleteRescanRefusesTheWholeSpeechPreparationInsteadOfSkippingMissingText() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.components.subtitles.addUntranslatedOccurrenceToNextScan = true
            fixture.session.prepareRange(null)
            await("The divergent voice preparation was not explicitly refused") {
                fixture.messages.any { it.second && it.first.contains("aucune substitution") }
            }
            assertTrue(fixture.components.speech.batches.isEmpty())
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.isPreparing)
        }
    }

    @Test fun truncatedCompleteIndexIsRefusedBeforeConfirmationOrEngineConstruction() {
        Fixture().use { fixture ->
            fixture.components.subtitles.truncateNextScan = true
            var confirmations = 0
            fixture.session.translation.prepare("en", "controlled") { confirmations++ }
            await("Truncated index worker did not finish") { !fixture.session.translation.busy }
            assertEquals(0, confirmations)
            assertFalse(fixture.session.translation.ready)
            assertTrue(fixture.session.translation.required)
            assertTrue(fixture.messages.any { it.second && it.first.contains("Index incomplet") })
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    private inner class Engine(
        override val id: String = "controlled",
        private val completed: CountDownLatch? = null,
        private val response: (List<String>) -> List<String> = { texts -> texts.map { french.getValue(it) } }
    ) : TranslationEngine {
        override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
            cancelled: () -> Boolean): List<String> {
            assertEquals("en", sourceLanguage)
            assertEquals("fr", targetLanguage)
            return response(texts)
        }
        override fun close() {
            // Cancellation also closes on the session thread. Wait for the
            // worker's final use/close to prove its late response has returned.
            if (Looper.myLooper() != Looper.getMainLooper()) completed?.countDown()
        }
    }

    private class RecordingNarration(private val delegate: NarrationController = ExistingNarrationController()) : NarrationController by delegate {
        data class Frame(val texts: List<String>, val identities: List<String>?)
        val frames = mutableListOf<Frame>()
        override fun cues(frameTimeUs: Long, texts: List<String>, hasImages: Boolean,
            identities: List<String>?, deadlinesMs: List<Long?>?): List<NarrationMachine.Effect> {
            frames += Frame(texts.toList(), identities?.toList())
            return delegate.cues(frameTimeUs, texts, hasImages, identities, deadlinesMs)
        }
    }

    /** Capture the real session listener at its public Player boundary. */
    private class Backend(private val delegate: PlaybackBackend) : PlaybackBackend by delegate {
        var positionOverrideMs: Long? = null
        private val listeners = mutableListOf<Player.Listener>()
        override val player: Player = object : ForwardingPlayer(delegate.player) {
            override fun getCurrentPosition(): Long = positionOverrideMs ?: super.getCurrentPosition()
            override fun seekTo(positionMs: Long) {
                if (positionOverrideMs != null) positionOverrideMs = positionMs else super.seekTo(positionMs)
            }
            override fun addListener(listener: Player.Listener) { listeners += listener; super.addListener(listener) }
            override fun removeListener(listener: Player.Listener) { listeners -= listener; super.removeListener(listener) }
        }
        fun deliver(group: CueGroup) { listeners.toList().forEach { it.onCues(group) } }
    }

    @Test fun editingOneOccurrenceChangesNarrationIdentityAndDurableSelectionRestoresWithoutNetwork() {
        Fixture().use { fixture ->
            fixture.translate()
            val occurrence = fixture.session.translation.editableCues()[1]
            val before = fixture.session.translation.fingerprint
            assertTrue(fixture.session.translation.editCue(occurrence.id, "Bonjour corrigé."))
            assertNotEquals(before, fixture.session.translation.fingerprint)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.session.translation.saveCurrentTranslation())
            val metadata = requireNotNull(fixture.session.translation.selectionMetadata())
            assertFalse(metadata.contains("Bonjour"))
            fixture.session.translation.useOriginal()
            assertTrue(fixture.session.translation.restoreSelection(metadata))
            await("Saved local translation not restored") { fixture.session.translation.complete && fixture.session.translation.ready }
            assertEquals("Bonjour corrigé.", fixture.session.translation.editableCues()[1].text)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun correctingOneCuePreparesOnlyItsExactVoiceSegmentsAndLeavesPlaybackPaused() {
        Fixture().use { fixture ->
            fixture.translate()
            fixture.session.prefs.putBool("cache", true)
            val before = fixture.session.translation.editableCues()
            val cue = before[1]
            val replacement = "M. Dupont arrive. Puis il repart !"
            fixture.components.speech.batches.clear()
            assertTrue(fixture.session.translation.editCueAndPrepareVoice(cue.id, replacement))
            val segments = fr.soustitresvoix.core.SpeechTools.segments(replacement, "fr")
            assertEquals(listOf(segments), fixture.components.speech.batches)
            assertEquals(before.map { if (it.id == cue.id) replacement else it.text }, fixture.session.translation.editableCues().map { it.text })
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertFalse(fixture.session.player.playWhenReady)
            assertTrue(fixture.components.speech.requests.isEmpty())
            assertTrue(fixture.session.translation.status.contains("autres répliques ne sont pas régénérées"))
        }
    }

    @Test fun isolatedCorrectionNeverPreparesWithMissingCacheVoiceOrInvalidText() {
        for (unavailable in listOf("cache", "voice", "language", "invalid")) {
            Fixture().use { fixture ->
                fixture.translate()
                val cue = fixture.session.translation.editableCues()[1]
                val fingerprint = fixture.session.translation.fingerprint
                fixture.session.prefs.putBool("cache", unavailable != "cache")
                if (unavailable == "voice") fixture.components.speech.ready(false)
                if (unavailable == "language") fixture.components.speech.voiceLanguage = "en"
                fixture.components.speech.batches.clear()
                assertEquals(unavailable != "invalid", fixture.session.translation.editCueAndPrepareVoice(cue.id,
                    if (unavailable == "invalid") "   " else "Bonjour corrigé."))
                assertTrue(fixture.components.speech.batches.isEmpty())
                assertTrue(fixture.components.speech.requests.isEmpty())
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertFalse(fixture.session.player.playWhenReady)
                if (unavailable == "invalid") assertEquals(fingerprint, fixture.session.translation.fingerprint)
                else {
                    assertEquals("Bonjour corrigé.", fixture.session.translation.editableCues()[1].text)
                    assertTrue(fixture.messages.any { it.second && it.first.contains("pour préparer uniquement sa voix") })
                }
            }
        }
    }

    @Test fun displayModeIsIndependentFromNarrationAndExportPreservesAllFiveOccurrences() {
        Fixture().use { fixture ->
            fixture.translate()
            val item = fixture.session.ahead.selected("external-subtitle")[1]
            fixture.session.translation.setDisplayMode(TranslationDisplayMode.ORIGINAL)
            assertEquals("Hello.", fr.soustitresvoix.core.SubtitleText.clean(fixture.session.translation.displayTextFor(item).orEmpty()))
            fixture.session.translation.setDisplayMode(TranslationDisplayMode.BILINGUAL)
            assertTrue(fixture.session.translation.displayTextFor(item)!!.contains("Bonjour."))
            fixture.session.translation.setDisplayMode(TranslationDisplayMode.HIDDEN)
            assertNull(fixture.session.translation.displayTextFor(item))
            assertEquals("Bonjour.", fixture.session.translation.textFor(item))
            val output = requireNotNull(fixture.session.translation.exportCurrent(fr.soustitresvoix.core.SubtitleExportFormat.SRT))
            assertEquals(5, Regex(" --> ").findAll(output).count())
        }
    }

    @Test fun progressiveValidatedPrefixNeverResumesAndGateStopsBeforeMissingOccurrence() {
        val header = assText().substringBefore("[Events]")
        fun time(seconds: Int): String = "0:%02d:%02d.00".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        val lines = (0 until 43).joinToString("\n") { i -> "Dialogue: 1,${time(i*5+1)},${time(i*5+3)},Dialogue,ALICE,0,0,0,,Text $i" }
        val subtitles = header + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" + lines + "\n"
        Fixture(subtitles).use { fixture ->
            val release = CountDownLatch(1); val enteredSecond = CountDownLatch(1)
            var prepared: SubtitleTranslationSession.Preview? = null
            fixture.session.translation.prepareWithOptions("en", "progressive", progressive = true) { prepared = it }
            await("Progressive source not indexed") { prepared != null }
            var calls = 0
            var partialMetadata: String? = null
            fixture.session.translation.start(prepared!!) {
                Engine(id = "progressive") { texts ->
                    calls++
                    if (calls == 2) { enteredSecond.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
                    texts.map { "Traduit $it" }
                }
            }
            try {
                await("First prefix not activated") { enteredSecond.count == 0L && fixture.session.translation.ready }
                assertFalse(fixture.session.translation.complete)
                assertNull(fixture.session.translation.selectionMetadata())
                val resumableSelection = requireNotNull(fixture.session.translation.checkpointSelectionMetadata())
                partialMetadata = resumableSelection
                val selectionJson = org.json.JSONObject(resumableSelection)
                assertEquals("progressive", selectionJson.getString("provider"))
                assertFalse(selectionJson.has("resultFingerprint"))
                fixture.session.savePosition()
                assertEquals(resumableSelection, fixture.session.savedSessionRecord()!!.translationIdentity)
                assertTrue(fixture.session.translation.allowPlayback())
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertTrue(fixture.components.speech.requests.isEmpty())
                var retained: Boolean? = null
                fixture.session.translation.retainCurrentForOffline { retained = it }
                assertEquals(false, retained)
                assertFalse(fixture.session.translation.onPlaybackPosition(101_000_000))
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertFalse(fixture.session.translation.ready)
                val known = fixture.session.ahead.selected("external-subtitle").first()
                assertEquals("Traduit Text 0", fixture.session.translation.textFor(known))
            } finally { release.countDown() }
            await("Progressive translation not completed") { fixture.session.translation.complete }
            assertEquals(43, fixture.session.translation.editableCues().size)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            fixture.session.translation.useOriginal()
            File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
            File(context.filesDir, "translations-retained-v1").deleteRecursively()
            val callbacks = mutableListOf<Boolean>()
            assertTrue(fixture.session.translation.restoreSelection(requireNotNull(partialMetadata)) { callbacks += it })
            await("Incomplete saved translation requirement was not restored") { callbacks.isNotEmpty() }
            assertEquals(listOf(false), callbacks)
            val stillRequiredMetadata = requireNotNull(fixture.session.translation.checkpointSelectionMetadata())
            fixture.session.savePosition()
            assertEquals(stillRequiredMetadata, fixture.session.savedSessionRecord()!!.translationIdentity)
            fixture.session.translation.useOriginal()
            val secondRestore = mutableListOf<Boolean>()
            assertTrue(fixture.session.translation.restoreSelection(stillRequiredMetadata) { secondRestore += it })
            await("A second reopen silently lost the incomplete translation requirement") { secondRestore.isNotEmpty() }
            assertEquals(listOf(false), secondRestore)
            assertNotNull(fixture.session.translation.checkpointSelectionMetadata())
            assertTrue(fixture.session.translation.required)
            assertFalse(fixture.session.translation.ready)
            assertFalse(fixture.session.translation.allowPlayback())
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
        }
    }

    private fun progressiveSubtitles(): String {
        fun time(seconds: Int): String = "0:%02d:%02d.00".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        val lines = (0 until 43).joinToString("\n") { i ->
            "Dialogue: 1,${time(i * 5 + 1)},${time(i * 5 + 3)},Dialogue,ALICE,0,0,0,,Text $i"
        }
        return assText().substringBefore("[Events]") + "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" + lines + "\n"
    }

    private fun Fixture.startPausedProgressive(release: CountDownLatch, entered: CountDownLatch,
        providerId: String = "progressive-recovery") {
        var preview: SubtitleTranslationSession.Preview? = null
        session.translation.prepareWithOptions("en", providerId, progressive = true, bufferSeconds = 10) { preview = it }
        await("Progressive source not indexed") { preview != null }
        var calls = 0
        session.translation.start(requireNotNull(preview)) {
            Engine(id = providerId) { texts ->
                if (++calls == 2) { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
                texts.map { "Traduit $it" }
            }
        }
        await("Validated prefix not available") { entered.count == 0L && session.translation.ready }
        assertFalse(session.translation.complete)
        assertFalse(session.machine.snapshot().wantsPlayback)
        session.play()
        assertTrue(session.machine.snapshot().wantsPlayback)
        components.backend.positionOverrideMs = 101_090
        assertFalse(session.translation.onPlaybackPosition(101_090_000))
        assertEquals("A short missing occurrence must remain replayable", 101_000L, session.player.currentPosition)
        assertTrue(session.translation.waitingForTranslation)
        assertFalse(session.machine.snapshot().wantsPlayback)
    }

    @Test fun progressiveHoldRewindsAndRecoversOnlyItsOwnPlaybackIntentWithoutLosingAnyOccurrence() {
        Fixture(progressiveSubtitles()).use { fixture ->
            val release = CountDownLatch(1); val entered = CountDownLatch(1)
            try {
                fixture.startPausedProgressive(release, entered)
                val source = fixture.session.ahead.selected("external-subtitle")
                val journalBefore = fixture.session.machine.journalSnapshot()
                val prefixFingerprint = fixture.session.translation.fingerprint
                assertEquals("Traduit Text 19", fixture.session.translation.textFor(source[19]))
                assertNull("No original-language substitution", fixture.session.translation.textFor(source[20]))
                release.countDown()
                await("Buffered playback intent did not recover") {
                    fixture.session.translation.complete && fixture.session.machine.snapshot().wantsPlayback
                }
                assertFalse(fixture.session.translation.waitingForTranslation)
                assertNotEquals(prefixFingerprint, fixture.session.translation.fingerprint)
                val journalAfter = fixture.session.machine.journalSnapshot()
                assertEquals("A growing translation cannot reset source occurrence tracking", journalBefore.contextHash, journalAfter.contextHash)
                assertEquals(journalBefore.generation, journalAfter.generation)
                fixture.playFrames()
                assertEquals((0 until 43).map { "Traduit Text $it" }, fixture.components.speech.requests.map { it.second })
            } finally { release.countDown() }
        }
    }

    @Test fun explicitPauseSeekCancellationAndUnavailableVoicePreventLateProgressiveAutoplay() {
        for (interruption in listOf("pause", "seek", "cancel", "voice")) {
            Fixture(progressiveSubtitles()).use { fixture ->
                val release = CountDownLatch(1); val entered = CountDownLatch(1)
                try {
                    // Each case must exercise a waiting worker, not the complete cache from the previous case.
                    fixture.startPausedProgressive(release, entered, "progressive-recovery-$interruption")
                    when (interruption) {
                        "pause" -> fixture.session.pause()
                        "seek" -> fixture.session.seek(10_000)
                        "cancel" -> fixture.session.translation.cancel()
                        "voice" -> fixture.components.speech.ready(false)
                    }
                    release.countDown()
                    await("Worker remained active after $interruption") { !fixture.session.translation.busy }
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                    assertFalse(interruption, fixture.session.machine.snapshot().wantsPlayback)
                    assertFalse(interruption, fixture.session.player.playWhenReady)
                    assertFalse(interruption, fixture.session.translation.waitingForTranslation)
                    if (interruption == "voice") {
                        fixture.components.speech.ready(true)
                        assertFalse("A voice callback cannot revive the consumed intent", fixture.session.machine.snapshot().wantsPlayback)
                    }
                } finally { release.countDown() }
            }
        }
    }

    @Test fun progressiveTerminalFailureKeepsPlayablePrefixAndStopsAtItsExactEnd() {
        Fixture(progressiveSubtitles()).use { fixture ->
            val release = CountDownLatch(1); val entered = CountDownLatch(1)
            var prepared: SubtitleTranslationSession.Preview? = null
            fixture.session.translation.prepareWithOptions("en", "progressive-error", progressive = true, bufferSeconds = 10) { prepared = it }
            await("Progressive source not indexed") { prepared != null }
            var calls = 0
            fixture.session.translation.start(requireNotNull(prepared)) {
                Engine(id = "progressive-error") { texts ->
                    if (++calls == 2) {
                        entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS))
                        throw TranslationFailure(TranslationFailure.Reason.NETWORK)
                    }
                    texts.map { "Traduit $it" }
                }
            }
            try {
                await("First prefix not available") { entered.count == 0L && fixture.session.translation.ready }
                fixture.session.play()
                release.countDown()
                await("Network failure not reported") { !fixture.session.translation.busy }
                assertTrue("Valid translated footage remains playable", fixture.session.machine.snapshot().wantsPlayback)
                fixture.components.backend.positionOverrideMs = 101_090
                assertFalse(fixture.session.translation.onPlaybackPosition(101_090_000))
                assertEquals(101_000L, fixture.session.player.currentPosition)
                assertFalse(fixture.session.translation.waitingForTranslation)
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                assertNull(fixture.session.translation.textFor(fixture.session.ahead.selected("external-subtitle")[20]))
            } finally { release.countDown() }
        }
    }

    @Test fun offlineRetentionCompletesOnMainThreadAndRestoresAfterTranslationCacheEviction() {
        Fixture().use { fixture ->
            fixture.translate()
            val callbacks = mutableListOf<Boolean>()
            fixture.session.translation.retainCurrentForOffline { saved ->
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                callbacks += saved
            }
            await("Complete translation not retained for offline") { callbacks.isNotEmpty() }
            assertEquals(listOf(true), callbacks)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            val metadata = requireNotNull(fixture.session.translation.selectionMetadata())
            File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
            fixture.session.translation.useOriginal()
            assertTrue(fixture.session.translation.restoreSelection(metadata))
            await("Retained offline translation not restored after cache eviction") { fixture.session.translation.ready && fixture.session.translation.complete }
            assertEquals(listOf("SORTIE", "Bonjour.", "Oui.", "Oui.", "Bonjour."), fixture.session.translation.editableCues().map { it.text })
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun invalidatedOfflineRetentionReportsFalseExactlyOnceAndCannotTriggerPlayback() {
        Fixture().use { fixture ->
            fixture.translate()
            val callbacks = mutableListOf<Boolean>()
            fixture.session.translation.retainCurrentForOffline { callbacks += it }
            fixture.session.translation.useOriginal()
            assertEquals(listOf(false), callbacks)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(listOf(false), callbacks)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            assertTrue(fixture.components.speech.requests.isEmpty())
        }
    }

    @Test fun invalidSavedTranslationNeverFallsBackToOriginalEvenAfterAnotherSaveAndRestore() {
        for (metadata in listOf("not-json", "{\"version\":999}")) {
            Fixture().use { fixture ->
                var indexed = false
                fixture.session.ensureCompleteIndex { indexed = true }
                await("Synthetic source not indexed for invalid-selection regression") { indexed }
                val source = fixture.session.ahead.selected("external-subtitle").first()
                var calls = 0
                assertFalse(fixture.session.translation.restoreSelection(metadata) { success -> assertFalse(success); calls++ })
                assertEquals(1, calls)
                assertTrue(fixture.session.translation.required)
                assertNull(fixture.session.translation.textFor(source))
                assertFalse(fixture.session.translation.allowPlayback())
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
                fixture.session.savePosition()
                val unavailable = requireNotNull(fixture.session.savedSessionRecord()!!.translationIdentity)
                assertEquals(true, org.json.JSONObject(unavailable).getBoolean("unavailable"))
                fixture.session.translation.useOriginal()
                assertFalse(fixture.session.translation.required)
                assertFalse(fixture.session.translation.restoreSelection(unavailable) { success -> assertFalse(success); calls++ })
                assertEquals(2, calls)
                assertTrue(fixture.session.translation.required)
                assertNull(fixture.session.translation.textFor(source))
                assertFalse(fixture.session.translation.allowPlayback())
                fixture.session.savePosition()
                assertEquals(unavailable, fixture.session.savedSessionRecord()!!.translationIdentity)
                assertTrue(fixture.components.speech.requests.isEmpty())
                fixture.session.translation.useOriginal()
                assertEquals(source.raw, fixture.session.translation.textFor(source))
            }
        }
    }

    @Test fun restorationCompletionIsUniqueForSuccessCancellationIndexFailureAndMissingLocalResult() {
        for (case in listOf("success", "cancel", "truncated", "missing")) {
            Fixture().use { fixture ->
                fixture.translate()
                assertTrue(fixture.session.translation.saveCurrentTranslation())
                val metadata = requireNotNull(fixture.session.translation.selectionMetadata())
                fixture.session.translation.useOriginal()
                if (case == "truncated") fixture.components.subtitles.truncateNextScan = true
                if (case == "missing") {
                    File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
                    File(context.filesDir, "translations-retained-v1").deleteRecursively()
                }
                val callbacks = mutableListOf<Boolean>()
                assertTrue(fixture.session.translation.restoreSelection(metadata) { callbacks += it })
                if (case == "cancel") fixture.session.translation.useOriginal()
                await("Restoration did not report completion for $case") { callbacks.isNotEmpty() }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                assertEquals(case, listOf(case == "success"), callbacks)
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            }
        }
    }

    private class Source(context: Context) : SubtitleSource {
        private val delegate = Media3SubtitleSource(context)
        val scans = Collections.synchronizedList(mutableListOf<SavedMedia>())
        @Volatile var truncateNextScan = false
        @Volatile var addUntranslatedOccurrenceToNextScan = false
        override fun createMediaSource(item: MediaItem, onCue: IndexedCueListener): MediaSource = delegate.createMediaSource(item, onCue)
        override fun createIndexer(): SubtitleIndexer {
            val scanner = delegate.createIndexer()
            return object : SubtitleIndexer by scanner {
                override fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
                    scans += media
                    if (truncateNextScan) {
                        truncateNextScan = false
                        return CueAheadIndex().apply {
                            add("external-subtitle", CuesWithTiming(listOf(Cue.Builder().setText("incomplete").build()), 0, 0))
                        }
                    }
                    return scanner.scan(media, progress).also { result ->
                        if (addUntranslatedOccurrenceToNextScan) {
                            addUntranslatedOccurrenceToNextScan = false
                            result.add("external-subtitle", CuesWithTiming(
                                listOf(Cue.Builder().setText("Untranslated new sign").build()), 10_000_000, 1_000_000))
                        }
                    }
                }
            }
        }
        override fun safeFailureMessage(error: Exception) = delegate.safeFailureMessage(error)
    }

    private class Components : PlaybackComponents by DefaultPlaybackComponents {
        val narration = RecordingNarration()
        lateinit var backend: Backend
        lateinit var subtitles: Source
        lateinit var speech: Voice
        override fun createNarration(): NarrationController = narration
        override fun createPlayback(context: Context, preferences: PlayerPreferences,
            movieAudio: MovieAudio, videoRenderer: VideoRendererFactory): PlaybackBackend =
            Backend(Media3PlaybackBackend(context, preferences, movieAudio, videoRenderer)).also { backend = it }
        override fun createSubtitles(context: Context): SubtitleSource = Source(context).also { subtitles = it }
        override fun createSpeech(context: Context, preferences: PlayerPreferences,
            onAvailability: (Boolean, String) -> Unit, onDone: (String) -> Unit,
            onFailure: (String, String) -> Unit): SpeechService = Voice(onAvailability, onDone).also { speech = it }
    }

    private class Voice(private val availability: (Boolean, String) -> Unit,
        private val done: (String) -> Unit) : SpeechService {
        val requests = mutableListOf<Pair<String, String>>()
        val batches = mutableListOf<List<String>>()
        val ahead = mutableListOf<List<String>>()
        var voiceLanguage = "fr"
        override val language get() = voiceLanguage
        override var isReady = false; private set
        override val durations = SpeechDurationModel()
        override val synthesisLatency = LatencyWindow()
        override val firstAudioLatency = LatencyWindow()
        override val cacheLatency = LatencyWindow()
        override val timingSource = "Controlled translation integration voice"
        override val bulkPreparation = false
        override val preparedAudioMs = 0L
        override val cacheConfigurationFingerprint = "translation-test-voice"
        override val engineDiagnosticIdentity = "controlled-translation-test-voice"
        override val choices = emptyList<SpeechVoiceChoice>()
        override val hits = 0
        override val misses = 0
        override val preparedCount get() = batches.sumOf { it.size }
        override val lastSynthesisMs = 0L
        override val lastAudioMs = 0L
        override val lastFactor = 1f
        override val cacheSize = 0L
        override val preparationStatus = "No external acoustic claim"
        fun ready(value: Boolean) { isReady = value; availability(value, "Controlled voice state") }
        fun complete(id: String) = done(id)
        override fun engines() = emptyList<SpeechEngineChoice>()
        override fun retry() = Unit
        override fun applyPreferences() = Unit
        override fun speak(id: String, text: String, availableMs: Long?, adaptive: Boolean,
            remaining: String, forecast: Float, budget: (() -> Long?)?, planning: ((Long) -> Float)?) { requests += id to text }
        override fun updateAdaptive() = Unit
        override fun prepareBatch(texts: List<String>) { batches += texts.toList() }
        override fun prepareAhead(texts: List<String>) { ahead += texts.toList() }
        override fun cancelPreparation(keepPending: Boolean) = Unit
        override fun stop() = Unit
        override fun clearCache() = Unit
        override fun close() = Unit
    }

    private fun assText(): String {
        val original = requireNotNull(javaClass.classLoader!!.getResourceAsStream("ssa/full_persistent_sign.ass"))
            .use { it.reader().readText() }
        return original.substringBefore("[Events]") + """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:09.00,Sign,,0,0,0,,{\an8\pos(320,30)}EXIT
            Dialogue: 1,0:00:02.00,0:00:03.00,Dialogue,ALICE,0,0,0,,Hello.
            Dialogue: 1,0:00:04.00,0:00:05.00,Dialogue,ALICE,0,0,0,,Yes.
            Dialogue: 1,0:00:04.00,0:00:05.00,Dialogue,BOB,0,0,0,,Yes.
            Dialogue: 1,0:00:06.00,0:00:07.00,Dialogue,ALICE,0,0,0,,Hello.
        """.trimIndent() + "\n"
    }

    private fun frames(bytes: ByteArray): List<CueGroup> {
        val groups = mutableListOf<CuesWithTiming>()
        CueAheadFactory { _, _ -> }.sidecarParsers()
            .create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
            .parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues(), Consumer { groups += it })
        return RealCueTimeline.frames(groups)
    }
}
