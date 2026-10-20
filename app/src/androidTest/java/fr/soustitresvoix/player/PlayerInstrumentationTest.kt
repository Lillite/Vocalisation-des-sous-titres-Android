package fr.soustitresvoix.player

import android.widget.Button
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Integration tests: require a real Android device or an emulator. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerInstrumentationTest {
    @Before fun clearPreferences() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("player", 0).edit().clear().commit()
    }
    @Test fun labelledOpenControlIsVisibleAndLarge() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                onView(withId(R.id.open_video)).perform(scrollTo()).check(matches(isDisplayed()))
            } catch (failure: Throwable) {
                // Collect the actual focused window before ActivityScenario closes.
                // This does not retry the interaction or change its assertions.
                val instrument = InstrumentationRegistry.getInstrumentation()
                runCatching {
                    val command = "dumpsys window windows"
                    val dump = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
                    dump.lineSequence().filter { it.contains("Focus", true) || it.contains("Window #") ||
                        it.contains("isOnScreen") || it.contains("mViewVisibility") }.forEach {
                        android.util.Log.e("WindowFocusDiagnostic", it)
                    }
                }
                throw failure
            }
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(R.id.open_video)
                assertEquals("Ouvrir une vidéo", button.text.toString())
                assertTrue(button.height >= 48 * activity.resources.displayMetrics.density)
            }
        }
    }
    @Test fun actualMkvPlaybackEmitsEmbeddedFrenchText() {
        val caption = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Do not depend on emulator voice installation for this media test.
                activity.session.prefs.narration = false
                activity.session.refreshSettings()
                activity.session.player.addListener(object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) {
                        if (cueGroup.cues.any { it.text?.contains("Bonjour") == true }) caption.countDown()
                    }
                })
                activity.session.open(SavedMedia(
                    "android.resource://${activity.packageName}/${R.raw.demonstration}", "Essai"))
                activity.session.play()
            }
            assertTrue("Media3 did not emit the embedded subtitle in time", caption.await(20, TimeUnit.SECONDS))
            scenario.onActivity { it.session.pause() }
        }
    }
}
