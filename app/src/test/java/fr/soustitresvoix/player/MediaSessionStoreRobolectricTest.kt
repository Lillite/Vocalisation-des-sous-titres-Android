package fr.soustitresvoix.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.SubtitleTimeline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaSessionStoreRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val folder get() = File(context.filesDir, "media-sessions-v1")
    @Before fun clear() { folder.deleteRecursively() }
    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid metadata must be refused") } catch (_: IllegalArgumentException) { }
    }
    private fun snapshot(uri: String = "content://fixture/film") = MediaSessionSnapshot(uri, "Synthetic film", positionMs = 23_000,
        updatedAtMs = 1000, fingerprint = "synthetic-content-v1", audioTrackId = "a:0", textTrackId = "s:2", audioLanguage = "en",
        textLanguage = "fr", subtitlePath = "/private/fixture.srt", subtitleMime = "application/x-subrip",
        translationIdentity = "fixture-translation", translationReady = true, enginePackage = "fixture.tts", voiceName = "fr-voice",
        narrationMode = "ADAPTIVE", settings = mapOf("speechRate" to 1.1f, "adaptiveMax" to 160, "narration" to true,
            "longValue" to 2_000_000_000_000L, "doubleValue" to 1.25, "language" to "fr"), image = mapOf("contrast" to .12f, "frameMode" to 2),
        timeline = SubtitleTimeline(1500, -200, listOf(SubtitleTimeline.Anchor(0, 0), SubtitleTimeline.Anchor(10_000, 10_100)),
            listOf(SubtitleTimeline.Portion(30_000, 90_000, -5000))),
        lastCompleted = OccurrenceResume("cue-complete", 10_000, 11_000), lastInterrupted = OccurrenceResume("cue-interrupted", 12_000, 14_000),
        timelines = mapOf("s:2" to SubtitleTimeline(1500), "s:3" to SubtitleTimeline(-200, 50,
            portions = listOf(SubtitleTimeline.Portion(5000, 10_000, 1200)))))

    @Test fun completeSessionRoundTripsWithExactPreferenceTypesAndSeparateMediaState() {
        val store = MediaSessionStore(context)
        val original = snapshot()
        store.save(original)
        assertEquals(original, MediaSessionStore(context).get(original.uri))
        val changed = snapshot("content://fixture/second").copy(positionMs = 55_000, updatedAtMs = 2000)
        store.save(changed)
        assertEquals(listOf(changed, original), store.list())
        assertEquals(original, store.get(original.uri))
        assertNull(store.lastWarning)
        assertTrue(folder.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) })

        val concurrent = java.util.concurrent.Executors.newFixedThreadPool(3)
        val start = java.util.concurrent.CountDownLatch(1)
        val replacement = original.copy(positionMs = 42_000, updatedAtMs = 2000)
        try {
            val tasks = listOf(
                concurrent.submit { start.await(); repeat(12) { MediaSessionStore(context).save(original) } },
                concurrent.submit { start.await(); repeat(12) { MediaSessionStore(context).save(replacement) } },
                concurrent.submit { start.await(); repeat(24) {
                    val observed = MediaSessionStore(context).get(original.uri)
                    assertTrue("Concurrent owners exposed a missing or partial session", observed == original || observed == replacement)
                } }
            )
            start.countDown()
            tasks.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { concurrent.shutdownNow() }

    }

    @Test fun optionalStateRestoresWithoutInventingAReadyTranslationOrOccurrence() {
        val minimal = MediaSessionSnapshot("content://fixture/minimal", "Minimal")
        val store = MediaSessionStore(context)
        store.save(minimal)
        assertEquals(minimal, store.get(minimal.uri))
        assertFalse(store.get(minimal.uri)!!.translationReady)
        assertNull(store.get(minimal.uri)!!.lastInterrupted)
        assertNull(store.get("content://fixture/absent"))
    }

    @Test fun malformedAndUnknownSchemaRecordsFailExplicitlyWithoutTouchingMedia() {
        val store = MediaSessionStore(context)
        val value = snapshot()
        store.save(value)
        val record = folder.listFiles()!!.single()
        record.writeText("{not-json}")
        assertNull(store.get(value.uri)); assertNotNull(store.lastWarning)
        store.save(value)
        record.writeText(record.readText().replace("\"schema\":1", "\"schema\":99"))
        assertNull(store.get(value.uri)); assertNotNull(store.lastWarning)
        assertTrue(record.exists())
    }

    @Test fun interruptedAtomicWriteRestoresThePreviousCompleteSession() {
        val store = MediaSessionStore(context)
        val value = snapshot()
        store.save(value)
        val record = folder.listFiles()!!.single()
        val backup = File(record.path + ".bak")
        assertTrue(record.renameTo(backup))
        record.writeText("partial")
        assertEquals(value, store.get(value.uri))
        assertNull(store.lastWarning)
    }

    @Test fun repairRelocatesOnlyAfterNewRecordWasSavedAndRemovalIsPerMedia() {
        val store = MediaSessionStore(context)
        val old = snapshot()
        val other = snapshot("content://fixture/other")
        store.save(old); store.save(other)
        val moved = store.relocate(old.uri, "content://fixture/repaired")!!
        assertEquals(old.positionMs, moved.positionMs)
        assertEquals(old.timeline, moved.timeline)
        assertNull(store.get(old.uri))
        assertEquals(moved, store.get(moved.uri))
        store.remove(moved.uri)
        assertEquals(listOf(other), store.list())
    }

    @Test fun secretsInvalidNumericStateAndOversizedRecordsAreRejectedBeforeReplacement() {
        val store = MediaSessionStore(context)
        val valid = snapshot()
        store.save(valid)
        reject { store.save(valid.copy(settings = mapOf("apiKey" to "private"))) }
        reject { store.save(valid.copy(settings = mapOf("refresh_token" to "private"))) }
        reject { store.save(valid.copy(settings = mapOf("speechRate" to Float.NaN))) }
        reject { store.save(valid.copy(positionMs = -1)) }
        reject { store.save(valid.copy(lastInterrupted = OccurrenceResume("bad", 10, 10))) }
        reject { store.save(valid.copy(timelines = (0..128).associate { "track-$it" to SubtitleTimeline() })) }
        reject { store.save(valid.copy(settings = (0..255).associate { "field-$it" to "x".repeat(8192) })) }
        assertEquals(valid, store.get(valid.uri))
        val stored = folder.listFiles()!!.single().readText()
        assertFalse(stored.contains("apiKey"))
        assertFalse(stored.contains("refresh_token"))
    }

    @Test fun snapshotIdentityMismatchCannotRestoreAnotherMedia() {
        val store = MediaSessionStore(context)
        val value = snapshot()
        store.save(value)
        val record = folder.listFiles()!!.single()
        val altered = org.json.JSONObject(record.readText()).put("uri", "content://fixture/wrong")
        record.writeText(altered.toString())
        assertNull(store.get(value.uri))
        assertNotNull(store.lastWarning)
    }

    @Test fun existingSchemaOneSessionsWithoutPerTrackTimelinesRemainReadable() {
        val value = snapshot()
        val store = MediaSessionStore(context)
        store.save(value)
        val file = folder.listFiles()!!.single()
        val original = org.json.JSONObject(file.readText())
        original.remove("timelines")
        original.getJSONObject("timeline").remove("portions")
        file.writeText(original.toString())
        assertEquals(value.copy(timeline = value.timeline.copy(portions = emptyList()), timelines = emptyMap()), store.get(value.uri))
        assertNull(store.lastWarning)
    }

    @Test fun malformedOrOverlappingPortionsCannotSilentlyRestoreWithoutTheirCorrection() {
        val value = snapshot()
        val store = MediaSessionStore(context)
        for (corrupt in listOf<Any>("wrong-shape", org.json.JSONArray().put(org.json.JSONObject()
            .put("startMs", 20).put("endMs", 30).put("offsetMs", 100)).put(org.json.JSONObject()
            .put("startMs", 25).put("endMs", 40).put("offsetMs", 200)))) {
            store.save(value)
            val file = folder.listFiles()!!.single()
            val json = org.json.JSONObject(file.readText())
            json.getJSONObject("timeline").put("portions", corrupt)
            file.writeText(json.toString())
            assertNull(store.get(value.uri))
            assertNotNull(store.lastWarning)
        }
    }
}
