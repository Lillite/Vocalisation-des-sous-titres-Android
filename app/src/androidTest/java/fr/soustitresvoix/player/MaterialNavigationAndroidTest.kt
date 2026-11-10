package fr.soustitresvoix.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import fr.soustitresvoix.core.NarrationMachine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native navigation and numeric commands on the APK under test. Captures contain
 * only the empty player and synthetic preferences, never a user's film or server. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MaterialNavigationAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun reset() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).putFloat("voiceVolume", .65f).commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun screenshot(name: String) {
        require(name.matches(Regex("[a-z-]+")))
        // The existing CI collector retrieves this shell-owned synthetic-evidence directory.
        for (command in listOf("mkdir -p /sdcard/Download/cinema03-tests",
            "screencap -p /sdcard/Download/cinema03-tests/$name.png")) {
            ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
    }

    private fun orientation(scenario: ActivityScenario<MainActivity>, value: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = value }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity {
                ready = it.resources.configuration.orientation == expected &&
                    it.findViewById<View>(R.id.bottom_navigation).getGlobalVisibleRect(Rect())
            }
            if (ready) {
                instrument.waitForIdleSync()
                // The system may still cover the new layout with its rotation
                // layer. InputDispatcher drops taps through that layer even
                // after Resources and Espresso report the new layout ready.
                val input = ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("dumpsys -t 1 input"))
                    .bufferedReader().use { it.readText() }
                    .substringAfter("Input Dispatcher State:", "").substringBefore("RecentQueue:")
                if ("DispatchFrozen: false" in input && "RotationLayer" !in input) return
            }
            Thread.sleep(40)
        }
        fail("The requested orientation did not become visible and ready for touch")
    }

    private fun assertTabTouchBounds(activity: MainActivity) {
        val root = activity.findViewById<View>(R.id.library_root)
        val safe = Rect().also { assertTrue(root.getGlobalVisibleRect(it)) }.apply {
            left += root.paddingLeft; right -= root.paddingRight; bottom -= root.paddingBottom
        }
        for (id in listOf(R.id.navigation_home, R.id.navigation_explore, R.id.navigation_translation, R.id.navigation_settings)) {
            val item = activity.findViewById<View>(id)
            val bounds = Rect().also { assertTrue(item.getGlobalVisibleRect(it)) }
            assertTrue("A navigation action must retain a 48 dp touch target", item.height >= (48 * item.resources.displayMetrics.density).toInt())
            assertTrue("A navigation action extends outside the usable display", bounds.left >= safe.left && bounds.right <= safe.right && bounds.bottom <= safe.bottom)
        }
    }

    @Test fun tabsKeepThePlayerAndExplicitPauseThenNumericBoundsUseTheExistingSettingsTransaction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            orientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            lateinit var player: androidx.media3.common.Player
            lateinit var video: PlayerView
            lateinit var narration: NarrationMachine.Snapshot
            var generation = 0L
            scenario.onActivity { activity ->
                player = activity.session.player
                video = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().single()
                narration = activity.session.machine.snapshot()
                generation = activity.session.generation
                val surface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, android.graphics.Color.WHITE)
                val text = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK)
                assertTrue(androidx.core.graphics.ColorUtils.calculateLuminance(surface) < .1)
                assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(text, surface) >= 4.5)
            }
            for ((tab, page, capture) in listOf(
                Triple(R.id.navigation_home, R.id.page_home, "material-home"),
                Triple(R.id.navigation_explore, R.id.page_explore, "material-explore"),
                Triple(R.id.navigation_translation, R.id.page_translation, "material-translation"),
                Triple(R.id.navigation_settings, R.id.page_settings, "material-settings"))) {
                onView(withId(tab)).perform(click())
                onView(withId(page)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    assertSame(player, activity.session.player)
                    assertSame(video, descendants(activity.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().single())
                    assertEquals(generation, activity.session.generation)
                    assertEquals(narration, activity.session.machine.snapshot())
                    assertFalse(player.playWhenReady)
                    assertTabTouchBounds(activity)
                }
                instrument.waitForIdleSync()
                screenshot(capture)
            }
            orientation(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            for ((tab, page, capture) in listOf(
                Triple(R.id.navigation_home, R.id.page_home, "material-home-landscape"),
                Triple(R.id.navigation_explore, R.id.page_explore, "material-explore-landscape"),
                Triple(R.id.navigation_translation, R.id.page_translation, "material-translation-landscape"),
                Triple(R.id.navigation_settings, R.id.page_settings, "material-settings-landscape"))) {
                onView(withId(tab)).perform(click())
                onView(withId(page)).check(matches(isDisplayed()))
                scenario.onActivity { activity ->
                    assertSame(player, activity.session.player)
                    assertSame(video, descendants(activity.findViewById(android.R.id.content)).filterIsInstance<PlayerView>().single())
                    assertFalse(player.playWhenReady)
                    assertTabTouchBounds(activity)
                }
                instrument.waitForIdleSync()
                screenshot(capture)
            }
            orientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
            onView(withId(R.id.navigation_settings)).perform(click())
            onView(withText("Réglages de la voix et du confort")).perform(scrollTo(), click())
            onView(withContentDescription("Rechercher dans les huit rubriques de réglages"))
                .inRoot(isDialog()).perform(replaceText("Volume de la voix"), closeSoftKeyboard())
            onView(withContentDescription("Volume de la voix : régler au minimum, 10 %"))
                .inRoot(isDialog()).perform(scrollTo(), click())
            scenario.onActivity { assertEquals(.1f, it.session.prefs.voiceVolume, 0f); assertFalse(player.playWhenReady) }
            screenshot("material-settings-minimum")
            onView(withContentDescription("Volume de la voix : régler au maximum, 100 %"))
                .inRoot(isDialog()).perform(scrollTo(), click())
            scenario.onActivity { assertEquals(1f, it.session.prefs.voiceVolume, 0f); assertFalse(player.playWhenReady) }
            onView(withText("Annuler modifications")).inRoot(isDialog()).perform(click())
            scenario.onActivity {
                assertEquals(.65f, it.session.prefs.voiceVolume, 0f)
                assertFalse(player.playWhenReady)
                assertFalse(it.session.machine.snapshot().wantsPlayback)
            }
        }
    }
}
