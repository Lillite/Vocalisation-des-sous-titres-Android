package fr.soustitresvoix.player

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SpeechInitializationAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val missingPackage = "fr.soustitresvoix.regression.nonexistent.engine"

    @Before fun clearPreferences() {
        instrument.targetContext.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 20_000
        while (android.os.SystemClock.elapsedRealtime() < end) {
            var ready = false
            instrument.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(40)
        }
        fail(message)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Test fun missingEngineReportsAfterConstructionAndOnlyForTheLatestConnection() {
        val delivered = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val callbackLooper = AtomicReference<Looper?>()
        val availability = AtomicReference<Boolean?>()
        val detail = AtomicReference<String?>()
        var speech: AndroidSpeech? = null
        try {
            instrument.runOnMainSync {
                val prefs = PlayerPreferences(instrument.targetContext).apply { enginePackage = missingPackage }
                speech = AndroidSpeech(instrument.targetContext, prefs, { ready, message ->
                    callbacks.incrementAndGet(); callbackLooper.set(Looper.myLooper())
                    availability.set(ready); detail.set(message); delivered.countDown()
                }, {}, { _, _ -> })
                assertEquals("The constructor must not call its owner before assignment", 0, callbacks.get())
                speech!!.retry()
                assertEquals("Both connection results must be deferred", 0, callbacks.get())
            }
            assertTrue("The unavailable engine was not reported", delivered.await(5, TimeUnit.SECONDS))
            instrument.waitForIdleSync()
            assertEquals("The superseded connection must not report a second error", 1, callbacks.get())
            assertSame(Looper.getMainLooper(), callbackLooper.get())
            assertEquals(false, availability.get())
            assertTrue(detail.get().orEmpty().contains("n’est plus installé"))
        } finally { instrument.runOnMainSync { speech?.close() } }
    }

    @Test fun closingBeforeMissingEngineNotificationSuppressesTheStaleCallback() {
        val callbacks = AtomicInteger()
        instrument.runOnMainSync {
            val prefs = PlayerPreferences(instrument.targetContext).apply { enginePackage = missingPackage }
            val speech = AndroidSpeech(instrument.targetContext, prefs,
                { _, _ -> callbacks.incrementAndGet() }, {}, { _, _ -> })
            speech.close()
        }
        instrument.waitForIdleSync()
        assertEquals("An already closed owner must receive no engine notification", 0, callbacks.get())
    }

    @Test fun cinemaRecreationWithAnUninstalledEngineShowsTheErrorWithoutCrashing() {
        val prefs = PlayerPreferences(instrument.targetContext).apply {
            enginePackage = instrument.context.packageName
            narration = true
            putInt("orientation", 0)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var current: MainActivity? = null
                var previous: MainActivity? = null
                scenario.onActivity { current = it }
                waitFor("The installed test voice was not ready before the recreation") { current!!.session.speech.isReady }
                scenario.onActivity { activity ->
                    previous = activity
                    activity.setFullscreen(true)
                    assertNotNull(activity.findViewById<CinemaView>(R.id.cinema_root))
                    // Persist the unavailable selection without reconnecting the old
                    // session: it will be read by the new Activity's constructor.
                    prefs.enginePackage = missingPackage
                }
                scenario.recreate()
                scenario.onActivity { current = it; assertNotSame(previous, it) }
                waitFor("The restored Activity did not show the missing-engine error") {
                    descendants(current!!.window.decorView).filterIsInstance<TextView>()
                        .any { it.text.toString().contains("n’est plus installé") }
                }
                scenario.onActivity { activity ->
                    assertFalse(activity.session.speech.isReady)
                    assertFalse(activity.session.machine.snapshot().wantsPlayback)
                    assertFalse(activity.session.player.playWhenReady)
                    assertNull("The visible error should return to the library controls",
                        activity.findViewById<CinemaView>(R.id.cinema_root))
                }
            }
        } finally { prefs.enginePackage = "" }
    }
}
