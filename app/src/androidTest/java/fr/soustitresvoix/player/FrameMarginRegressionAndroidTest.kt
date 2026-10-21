package fr.soustitresvoix.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.BlackBars
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameMarginRegressionAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", 0).edit().clear().commit()
    }

    private fun waitFor(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            var ready = false
            instrument.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(80)
        }
        fail(message)
    }

    @Test fun enablingProtectionRestoresTheEntireImageIncludingItsValidatedMargins() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setFullscreen(true)
                val cinema = activity.findViewById<CinemaView>(R.id.cinema_root)
                val video = cinema.getChildAt(0) as PlayerView
                activity.session.visual.crop(BlackBars.Margins(top = .1f, bottom = .1f))
                activity.session.visual.adjust(.1f, .05f)
                activity.session.visual.setDetailedScaling(true)
                assertTrue(cinema.setFramingMode(1, authorizeCrop = true))
                cinema.setImageZoom(2f)

                cinema.setFrameProtection(true)

                assertTrue(activity.session.prefs.protectFrame)
                assertEquals(0, activity.session.prefs.int("imageMode", -1, 0, 2))
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, video.resizeMode)
                assertEquals(1f, video.videoSurfaceView!!.scaleX, 0f)
                assertEquals(1f, video.videoSurfaceView!!.scaleY, 0f)
                assertFalse("The protected entire-image mode retained a crop", activity.session.visual.margins.nonzero())
                assertEquals(.1f, activity.session.visual.contrast, 0f)
                assertEquals(.05f, activity.session.visual.brightness, 0f)
                assertTrue("Restoring the frame must not reset other image settings", activity.session.visual.detailedScaling)
            }
        }
    }

    @Test fun returningToLibraryClearsMarginsAndKeepsTheSavedCinemaFramingChoice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.session.open(SavedMedia("android.resource://${activity.packageName}/${R.raw.demonstration}", "Mire de cadrage"))
                activity.setFullscreen(true)
                val cinema = activity.findViewById<CinemaView>(R.id.cinema_root)
                val video = cinema.getChildAt(0) as PlayerView
                assertTrue(cinema.setFramingMode(1, authorizeCrop = true))
                activity.session.visual.crop(BlackBars.Margins(top = .1f, bottom = .1f))
                activity.session.visual.adjust(.1f, .05f)
                activity.session.visual.setDetailedScaling(true)

                activity.setFullscreen(false)

                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, video.resizeMode)
                assertFalse("The library preview inherited the cinema margin crop", activity.session.visual.margins.nonzero())
                assertEquals(.1f, activity.session.visual.contrast, 0f)
                assertEquals(.05f, activity.session.visual.brightness, 0f)
                assertTrue(activity.session.visual.detailedScaling)

                activity.setFullscreen(true)
                val restored = activity.findViewById<CinemaView>(R.id.cinema_root)
                assertEquals("The explicit per-video framing choice was replaced", 1, activity.session.prefs.int("imageMode", -1, 0, 2))
                assertFalse(activity.session.prefs.protectFrame)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, (restored.getChildAt(0) as PlayerView).resizeMode)
                assertFalse(activity.session.visual.margins.nonzero())
            }
        }
    }

    @Test fun rebuildingCinemaKeepsMarginsApprovedAfterChoosingAnEntireImageMode() {
        val approved = BlackBars.Margins(top = .1f, bottom = .1f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var current: MainActivity? = null
            var previous: CinemaView? = null
            scenario.onActivity { activity ->
                current = activity
                activity.setFullscreen(true)
                previous = activity.findViewById(R.id.cinema_root)
                assertTrue(previous!!.setFramingMode(0))
                activity.session.visual.crop(approved)
                previous!!.setFrameProtection(false)
                previous!!.setFrameProtection(true)
                assertEquals("Newly approved margins must survive a gesture-protection toggle", approved, activity.session.visual.margins)
                val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                activity.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitFor("The orientation change did not rebuild the cinema view") {
                current!!.findViewById<CinemaView>(R.id.cinema_root) !== previous
            }
            scenario.onActivity { activity ->
                assertEquals("A cinema reconstruction discarded a deliberate margin approval", approved, activity.session.visual.margins)
                assertEquals(0, activity.session.prefs.int("imageMode", -1, 0, 2))
                assertTrue(activity.session.prefs.protectFrame)
            }
        }
    }
}
