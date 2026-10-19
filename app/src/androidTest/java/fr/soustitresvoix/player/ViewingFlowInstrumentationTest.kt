package fr.soustitresvoix.player

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ViewingFlowInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun reset() {
        instrumentation.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putString("enginePackage", instrumentation.context.packageName)
            .putString("voiceName", "test-fr").putInt("cinemaOrientation", 0).commit()
    }
    private fun waitUntil(scenario: ActivityScenario<MainActivity>, predicate: (MainActivity) -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            var result = false
            scenario.onActivity { result = predicate(it) }
            if (result) return true
            SystemClock.sleep(50)
        }
        return false
    }
    @Test fun realSystemBackUnlocksThenExitsCinemaWithoutFinishingActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(waitUntil(scenario) { it.session.speech.isReady })
            scenario.onActivity { a -> a.setFullscreen(true); a.findViewById<CinemaControls>(R.id.cinema_controls).setLocked(true) }
            SystemClock.sleep(700)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            assertTrue(waitUntil(scenario) { !it.findViewById<CinemaControls>(R.id.cinema_controls).locked })
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.cinema_controls).isShown) }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            assertTrue(waitUntil(scenario) { !it.findViewById<View>(R.id.cinema_controls).isShown })
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
    @Test fun actualNarrationSurvivesFullscreenAndItsIndexMatchesTheSelectedTrack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(waitUntil(scenario) { it.session.speech.isReady })
            scenario.onActivity { a ->
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Synthetic media"))
                a.session.play()
            }
            assertTrue("Real subtitle was not sent to the Android TTS engine", waitUntil(scenario) { it.session.speech.currentText.contains("Bonjour") })
            scenario.onActivity { a ->
                val player = a.session.player
                val utterance = a.session.machine.snapshot().speakingId
                assertNotNull(utterance)
                a.setFullscreen(true)
                assertSame(player, a.session.player)
                assertEquals(utterance, a.session.machine.snapshot().speakingId)
                a.setFullscreen(false)
                assertEquals(utterance, a.session.machine.snapshot().speakingId)
            }
            assertTrue("Optional local MKV index did not finish", waitUntil(scenario) { it.session.metrics.indexCount == 8 })
            scenario.onActivity { a ->
                a.session.pause()
                val id = a.session.machine.snapshot().speakingId
                assertNull(id)
                assertFalse(a.session.player.playWhenReady)
            }
        }
    }
}
