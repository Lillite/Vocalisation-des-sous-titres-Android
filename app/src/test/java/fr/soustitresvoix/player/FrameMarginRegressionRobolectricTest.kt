package fr.soustitresvoix.player

import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.BlackBars
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Corrected-state regressions on real application objects, without pixel assertions. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class FrameMarginRegressionRobolectricTest {
    @Before fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun withActivity(action: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { action(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }

    @Test fun protectionRestoresTheEntireImageAndRemovesOnlyItsMargins() = withActivity { activity ->
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
        assertTrue(activity.session.visual.detailedScaling)
    }

    @Test fun libraryClearsMarginsWithoutOverwritingTheSavedCinemaMode() = withActivity { activity ->
        activity.session.open(SavedMedia("android.resource://${activity.packageName}/${R.raw.demonstration}", "Synthetic framing fixture"))
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

    @Test fun aCinemaRebuildKeepsMarginsApprovedAfterTheEntireImageChoice() = withActivity { activity ->
        activity.setFullscreen(true)
        val previous = activity.findViewById<CinemaView>(R.id.cinema_root)
        val approved = BlackBars.Margins(top = .1f, bottom = .1f)
        assertTrue(previous.setFramingMode(0))
        activity.session.visual.crop(approved)
        previous.setFrameProtection(false)
        previous.setFrameProtection(true)
        assertEquals("Gesture protection discarded newly approved margins", approved, activity.session.visual.margins)

        // Invoke the actual rebuild callback; the instrumented counterpart also
        // exercises a real orientation change. No physical display is simulated here.
        activity.onConfigurationChanged(Configuration(activity.resources.configuration))

        val replacement = activity.findViewById<CinemaView>(R.id.cinema_root)
        assertNotSame(previous, replacement)
        assertEquals("A cinema reconstruction discarded a deliberate margin approval", approved, activity.session.visual.margins)
        assertEquals(0, activity.session.prefs.int("imageMode", -1, 0, 2))
        assertTrue(activity.session.prefs.protectFrame)
    }
}
