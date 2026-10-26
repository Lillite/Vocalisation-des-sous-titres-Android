package fr.soustitresvoix.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual Matroska demux, two distinct language tracks, asynchronous Media3
 * selection and complete indexer. Only translation output is a local fixture. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionTranslationRestoreRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetOwnedData() {
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("cache", false).putBoolean("narration", false).commit()
        File(context.filesDir, "media-sessions-v1").deleteRecursively()
        File(context.filesDir, "narration-journals-v1").deleteRecursively()
        File(context.cacheDir, "translated-subtitles-v1").deleteRecursively()
        File(context.filesDir, "translation-progress-v1").deleteRecursively()
        File(context.filesDir, "translations-retained-v1").deleteRecursively()
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

    private class Components : PlaybackComponents by DefaultPlaybackComponents {
        private val selectedLanguage = AtomicReference("")
        val scanLanguages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val block = AtomicBoolean(false)
        @Volatile var scanEntered = CountDownLatch(0)
        @Volatile var releaseScan = CountDownLatch(0)
        fun blockNextScan() {
            scanEntered = CountDownLatch(1); releaseScan = CountDownLatch(1); block.set(true)
        }
        override fun createPlayback(context: Context, preferences: PlayerPreferences, movieAudio: MovieAudio,
            videoRenderer: VideoRendererFactory): PlaybackBackend =
            DefaultPlaybackComponents.createPlayback(context, preferences, movieAudio, videoRenderer).also { backend ->
                backend.player.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        val format = tracks.groups.firstNotNullOfOrNull { group ->
                            if (group.type != C.TRACK_TYPE_TEXT) null else (0 until group.length)
                                .firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }
                        }
                        selectedLanguage.set(format?.language.orEmpty())
                    }
                })
            }
        override fun createSubtitles(context: Context): SubtitleSource {
            val delegate = Media3SubtitleSource(context)
            return object : SubtitleSource by delegate {
                override fun createIndexer(): SubtitleIndexer {
                    val scanner = delegate.createIndexer()
                    return object : SubtitleIndexer {
                        override fun scan(media: SavedMedia, progress: (Int) -> Unit): CueAheadIndex {
                            scanLanguages += selectedLanguage.get()
                            if (block.getAndSet(false)) {
                                scanEntered.countDown()
                                check(releaseScan.await(15, TimeUnit.SECONDS)) { "Controlled scan was not released" }
                            }
                            return scanner.scan(media, progress)
                        }
                        override fun cancel() { releaseScan.countDown(); scanner.cancel() }
                    }
                }
            }
        }
    }

    private inner class Fixture : AutoCloseable {
        val components = Components()
        val messages = mutableListOf<String>()
        val file = File(context.cacheDir, "restore-two-languages.mkv").apply {
            requireNotNull(SessionTranslationRestoreRobolectricTest::class.java.classLoader!!.getResourceAsStream("ssa/session_restore_two_languages.mkv")).use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        val media = SavedMedia(Uri.fromFile(file).toString(), "Synthetic bilingual restoration")
        val session = PlaybackSession(context, PlayerPreferences(context), {}, { value, _ -> messages += value }, {}, components)
        init {
            session.player.trackSelectionParameters = session.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            session.open(media)
            await("Real bilingual fixture did not auto-select French") {
                session.player.playbackState == Player.STATE_READY && selectedLanguage().startsWith("fr")
            }
        }
        fun selectedLanguage() = session.player.currentTracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_TEXT) null else (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language }
        }.orEmpty()
        fun translateEnglish(): String {
            val option = session.player.currentTracks.groups.flatMap { group ->
                if (group.type != C.TRACK_TYPE_TEXT) emptyList() else (0 until group.length).map { group to it }
            }.single { (group, index) -> group.getTrackFormat(index).language.orEmpty().startsWith("en") }
            session.selectTrack(C.TRACK_TYPE_TEXT, option.first, option.second)
            await("English selection did not become effective") { selectedLanguage().startsWith("en") }
            var preview: SubtitleTranslationSession.Preview? = null
            session.translation.prepare("en", "fixture-restore") { preview = it }
            await("Actual English source not indexed") { preview != null }
            assertTrue(preview!!.plan.texts.any { it.contains("English") })
            session.translation.start(preview!!) { object : TranslationEngine {
                override val id = "fixture-restore"
                override fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String,
                    cancelled: () -> Boolean) = texts.map { "Français traduit : $it" }
                override fun close() = Unit
            } }
            await("Controlled translation did not activate") { session.translation.complete && session.translation.ready }
            assertTrue(session.translation.saveCurrentTranslation())
            val metadata = requireNotNull(session.translation.selectionMetadata())
            session.savePosition()
            assertEquals(metadata, session.savedSessionRecord()?.translationIdentity)
            return metadata
        }
        override fun close() { components.releaseScan.countDown(); session.close(); file.delete() }
    }

    @Test fun savedEnglishTranslationRestoresOnlyAfterEnglishOverrideActuallyBecomesSelected() {
        Fixture().use { fixture ->
            val metadata = fixture.translateEnglish()
            fixture.components.scanLanguages.clear()
            fixture.session.open(fixture.media, restoreSession = true)
            await("Saved English translation was not restored after asynchronous track selection: ${fixture.messages}") {
                fixture.selectedLanguage().startsWith("en") && fixture.session.translation.complete && fixture.session.translation.ready
            }
            assertTrue(fixture.components.scanLanguages.isNotEmpty())
            assertTrue("An old auto-selected French track was indexed before the saved override took effect",
                fixture.components.scanLanguages.all { it.startsWith("en") })
            assertEquals(metadata, fixture.session.savedSessionRecord()?.translationIdentity)
            assertTrue(fixture.session.translation.editableCues().any { it.text.contains("Hello in English") })
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)

            // Simulate a slow old Activity writer. Recreating the owner must
            // receive the last captured choices/position without waiting on I/O.
            val features = PlaybackSession::class.java.getDeclaredField("features")
                .apply { isAccessible = true }.get(fixture.session)
            val writer = PlaybackFeatures::class.java.getDeclaredField("writer")
                .apply { isAccessible = true }.get(features) as java.util.concurrent.ExecutorService
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            writer.execute { entered.countDown(); check(release.await(25, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val recreated = PlaybackSession(context, PlayerPreferences(context), {}, { _, _ -> }, {}, Components())
            val repairedFile = File(context.cacheDir, "repaired-synthetic-bilingual.mkv")
            try {
                fixture.session.player.seekTo(2000)
                fixture.session.close()
                recreated.player.trackSelectionParameters = recreated.player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                recreated.open(fixture.media, restoreSession = true)
                await("Close/recreated owner lost the complete local English translation") {
                    recreated.translation.ready && recreated.translation.complete
                }
                assertEquals(2000L, recreated.player.currentPosition)
                assertEquals(metadata, recreated.savedSessionRecord()?.translationIdentity)
                assertTrue(recreated.translation.editableCues().any { it.text.contains("Hello in English") })
                assertFalse(recreated.machine.snapshot().wantsPlayback)
                assertFalse(recreated.player.playWhenReady)

                val correction = fr.soustitresvoix.core.SubtitleTimeline(500, 75)
                recreated.setTimeline(correction)
                recreated.visual.adjust(.12f, .05f)
                recreated.savePosition()
                fixture.file.copyTo(repairedFile, overwrite = true)
                repairedFile.setLastModified(fixture.file.lastModified())
                val repairedUri = Uri.fromFile(repairedFile).toString()
                recreated.relocateSessionTo(repairedUri, "Same synthetic edition relocated")
                await("Confirmed relocation did not restore the selected complete translation") {
                    recreated.media?.uri == repairedUri && recreated.translation.ready && recreated.translation.complete
                }
                val repairedFeatures = PlaybackSession::class.java.getDeclaredField("features")
                    .apply { isAccessible = true }.get(recreated) as PlaybackFeatures
                assertEquals(correction, repairedFeatures.timeline)
                assertEquals(.12f, recreated.visual.contrast, 0f)
                assertEquals(.05f, recreated.visual.brightness, 0f)
                assertEquals(metadata, recreated.savedSessionRecord()?.translationIdentity)
                assertEquals(2000L, recreated.player.currentPosition)
                assertFalse(recreated.machine.snapshot().wantsPlayback)
            } finally { release.countDown(); recreated.close(); repairedFile.delete() }
        }
    }

    @Test fun pauseAndSaveDuringAsynchronousRestorePreserveTranslationAssociationUntilFinalActivation() {
        Fixture().use { fixture ->
            val metadata = fixture.translateEnglish()
            fixture.components.blockNextScan()
            try {
                fixture.session.open(fixture.media, restoreSession = true)
                await("Restoration did not reach the controlled indexer") { fixture.components.scanEntered.count == 0L }
                assertTrue(fixture.session.translation.busy)
                assertFalse(fixture.session.translation.complete)
                fixture.session.pause()
                fixture.session.savePosition()
                val pending = requireNotNull(fixture.session.savedSessionRecord())
                assertEquals(metadata, pending.translationIdentity)
                assertFalse(pending.translationReady)
                await("The pending association did not reach durable session storage") {
                    MediaSessionStore(context).get(fixture.media.uri)?.updatedAtMs == pending.updatedAtMs
                }
                assertEquals(metadata, MediaSessionStore(context).get(fixture.media.uri)?.translationIdentity)
            } finally { fixture.components.releaseScan.countDown() }
            await("Final local translation was not restored") { fixture.session.translation.complete && fixture.session.translation.ready }
            assertEquals(metadata, fixture.session.savedSessionRecord()?.translationIdentity)
            assertFalse(fixture.session.machine.snapshot().wantsPlayback)
        }
    }
    @Test fun disabledAudioChoiceIsSavedAndRestoredAlongsideTheActualSubtitleTracks() {
        Fixture().use { fixture ->
            fixture.session.savePosition()
            assertEquals("__disabled__", fixture.session.savedSessionRecord()?.audioTrackId)
            await("Disabled audio choice did not reach durable session storage") {
                MediaSessionStore(context).get(fixture.media.uri)?.audioTrackId == "__disabled__"
            }
            val features = PlaybackFeatures(context, fixture.session, {}, { _, _ -> })
            try {
                features.opening(fixture.media, restore = true)
                fixture.session.player.trackSelectionParameters = fixture.session.player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                features.tracksAvailable(fixture.session.player.currentTracks)
                assertTrue(fixture.session.player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                assertFalse(fixture.session.machine.snapshot().wantsPlayback)
            } finally { features.close() }
        }
    }

}
