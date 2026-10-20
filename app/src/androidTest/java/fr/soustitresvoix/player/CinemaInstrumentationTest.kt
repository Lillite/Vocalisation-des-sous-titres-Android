package fr.soustitresvoix.player

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CinemaInstrumentationTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            var done = false; instrument.runOnMainSync { done = predicate() }; if (done) return
            Thread.sleep(80)
        }
        fail(label)
    }
    private fun screenshot(name: String) {
        val output = java.io.File(instrument.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun fullscreenReallyFillsContentAndControlsAreOverlays() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> a.session.prefs.narration = false; a.session.prefs.putInt("orientation", 0); a.session.refreshSettings(); a.setFullscreen(true) }
            instrument.waitForIdleSync()
            screenshot("fullscreen-controls")
            scenario.onActivity { a ->
                val root = a.findViewById<ViewGroup>(R.id.cinema_root)
                val content = a.findViewById<ViewGroup>(android.R.id.content)
                assertEquals(content.width, root.width); assertEquals(content.height, root.height)
                val video = root.getChildAt(0)
                assertEquals(root.width, video.width); assertEquals(root.height, video.height)
                assertNotNull(a.findViewById<Button>(R.id.cinema_play))
                (root as CinemaView).showControls(false)
                assertEquals(View.GONE, a.findViewById<View>(R.id.cinema_controls).visibility)
                assertEquals(root.height, video.height)
            }
            screenshot("fullscreen-hidden")
        }
    }
    @Test fun fullscreenTransitionKeepsSamePlayerAndPauseIntent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val player = a.session.player
                a.session.prefs.putInt("orientation", 0); a.session.pause()
                a.setFullscreen(true); a.setFullscreen(false); a.setFullscreen(true)
                assertSame(player, a.session.player)
                assertFalse(a.session.machine.snapshot().wantsPlayback)
                a.setFullscreen(false)
                assertNotNull(a.findViewById<Button>(R.id.open_video))
            }
        }
    }
    @Test fun mkvProvidesLookAheadBeforeTheFirstCaptionIsPlayed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var current: MainActivity? = null
            scenario.onActivity { a -> current = a; a.session.prefs.narration = false; a.session.refreshSettings()
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Essai")) }
            waitFor("No look-ahead text was observed in the actual MKV extractor") { (current?.session?.ahead?.count ?: 0) >= 2 }
            scenario.onActivity { a ->
                assertFalse(a.session.player.isPlaying); assertTrue(a.session.ahead.count >= 2)
                assertTrue("Selected track has no future text", a.session.ahead.future(a.session.selectedTextId(), 0, a.session.prefs.filters, 6).isNotEmpty())
            }
        }
    }
    @Test fun filtersAndAdaptiveRulesRunOnAndroidIcu() {
        val tools = fr.soustitresvoix.core.SpeechTools
        assertEquals("Bonjour", tools.filter("ALICE: Bonjour", fr.soustitresvoix.core.SpeechTools.Filters(speakers = true)))
        assertEquals("Bonjour", tools.filter("NDT: Note\nBonjour", fr.soustitresvoix.core.SpeechTools.Filters(notes = true)))
        assertEquals(1.25f, tools.adaptiveFactor(3000, 1000, 1.25f), 0.001f)
        assertEquals(listOf("Bonjour.", "Au revoir."), tools.segments("Bonjour. Au revoir."))
    }
    @Test fun actualTtsServiceSynthesizesCacheThenPlaysMeasuredAudio() {
        val done = CountDownLatch(1); val failure = AtomicReference<String?>(null)
        var speech: AndroidSpeech? = null
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext)
            prefs.enginePackage = instrument.context.packageName
            prefs.putBool("cache", true); prefs.putInt("adaptiveMax", 125)
            speech = AndroidSpeech(instrument.targetContext, prefs, { _, _ -> }, { done.countDown() },
                { _, message -> failure.set(message); done.countDown() })
        }
        try {
            waitFor("Fixture TTS service did not initialize") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.clearCache(); speech!!.prepareAhead(listOf("Bonjour. Ceci est un test.")) }
            waitFor("synthesizeToFile did not produce a validated WAV") { speech!!.preparedCount >= 1 }
            instrument.runOnMainSync { speech!!.speak("fixture-play", "Bonjour. Ceci est un test.", 100, true) }
            assertTrue("Cached audio did not complete", done.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            instrument.runOnMainSync { assertEquals(1, speech!!.hits); assertEquals(1.25f, speech!!.lastFactor, 0.001f) }
        } finally { instrument.runOnMainSync { speech?.close(); PlayerPreferences(instrument.targetContext).enginePackage = "" } }
    }
    @Test fun landscapeFullscreenHidesSystemBarsAndBackExitsOnlyCinema() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var current: MainActivity? = null
            scenario.onActivity { a ->
                current = a
                a.session.prefs.narration = false; a.session.refreshSettings()
                a.session.prefs.putInt("orientation", 1)
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Démonstration cinéma"))
                a.setFullscreen(true)
            }
            waitFor("Landscape was not applied") {
                val root = current?.findViewById<ViewGroup>(R.id.cinema_root)
                root != null && root.width > root.height
            }
            waitFor("System bars were not hidden") {
                val insets = current?.window?.decorView?.rootWindowInsets
                insets != null && !insets.isVisible(android.view.WindowInsets.Type.systemBars())
            }
            waitFor("Demo did not become ready") { current?.session?.player?.playbackState == androidx.media3.common.Player.STATE_READY }
            screenshot("landscape-controls")
            scenario.onActivity { a -> (a.findViewById<View>(R.id.cinema_root) as CinemaView).showControls(false) }
            Thread.sleep(800)
            scenario.onActivity { a -> assertEquals(View.GONE, a.findViewById<View>(R.id.cinema_controls).visibility) }
            screenshot("landscape-hidden")
            scenario.onActivity { a ->
                val player = a.session.player
                a.onBackPressedDispatcher.onBackPressed()
                assertFalse(a.isFinishing); assertSame(player, a.session.player)
                assertNotNull(a.findViewById<Button>(R.id.open_video))
            }
        }
    }
    @Test fun quickMixDoesNotPausePlayback() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.session.prefs.narration = false; a.session.refreshSettings()
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Essai"))
                a.session.play(); assertTrue(a.session.machine.snapshot().wantsPlayback)
                QuickMixDialog.show(a, a.session, a.session.prefs)
                assertTrue(a.session.machine.snapshot().wantsPlayback)
            }
        }
    }
    @Test fun cancelledCachedLookupCannotFinishANewerUtterance() {
        val done = CountDownLatch(1)
        val completed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<String?>(null)
        var speech: AndroidSpeech? = null
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext)
            prefs.enginePackage = instrument.context.packageName; prefs.putBool("cache", true)
            speech = AndroidSpeech(instrument.targetContext, prefs, { _, _ -> },
                { id -> completed.add(id); done.countDown() }, { _, message -> failure.set(message); done.countDown() })
        }
        try {
            waitFor("Fixture did not initialize") { speech!!.isReady }
            instrument.runOnMainSync { speech!!.clearCache(); speech!!.prepareAhead(listOf("Bonjour.")) }
            waitFor("Fixture did not produce cached audio") { speech!!.preparedCount > 0 }
            instrument.runOnMainSync {
                speech!!.speak("old", "Bonjour."); speech!!.stop(); speech!!.speak("new", "Bonjour.")
            }
            assertTrue(done.await(15, TimeUnit.SECONDS)); assertNull(failure.get())
            assertEquals(listOf("new"), completed.toList())
        } finally { instrument.runOnMainSync { speech?.close(); PlayerPreferences(instrument.targetContext).enginePackage = "" } }
    }
}
