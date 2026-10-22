package fr.soustitresvoix.player

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual MotionEvent -> surface listener -> GestureDetector -> CinemaView feedback.
 * These assertions concern view state and stored values, not pixels or audible gain.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class GestureLevelRoundingRobolectricTest {
    @Before fun resetPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        context.getSharedPreferences("playback-measurements", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun withCinema(action: (MainActivity, CinemaView) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setFullscreen(true)
            val cinema = requireNotNull(activity.findViewById<CinemaView>(R.id.cinema_root))
            cinema.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            cinema.layout(0, 0, 1000, 1000)
            assertEquals(1000, cinema.height)
            action(activity, cinema)
        } finally { controller.pause().stop().destroy() }
    }

    private fun raiseByEightPoints(cinema: CinemaView, x: Float) {
        val surface = descendants(cinema).single {
            it.contentDescription?.toString() == "Afficher ou masquer les commandes du lecteur"
        }
        val downTime = SystemClock.uptimeMillis()
        for ((action, elapsed, y) in listOf(
            Triple(MotionEvent.ACTION_DOWN, 0L, 800f),
            Triple(MotionEvent.ACTION_MOVE, 32L, 720f),
            Triple(MotionEvent.ACTION_UP, 48L, 720f)
        )) {
            val event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, y, 0)
            try { assertTrue("The real gesture surface did not handle the event", surface.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }

    @Test fun volumeGestureAgreesWithTheStoredWholePercentAndQuickMix() = withCinema { activity, cinema ->
        val prefs = activity.session.prefs
        prefs.movieVolume = .45f
        raiseByEightPoints(cinema, 750f)
        assertEquals("The real onScroll did not apply the intended volume change", .53f, prefs.movieVolume, .000001f)
        assertEquals(53, (prefs.movieVolume * 100).roundToInt())
        val feedback = descendants(cinema).filterIsInstance<TextView>().single {
            it.text.toString().startsWith("Volume film ")
        }.text.toString()
        val dialog = QuickMixDialog.show(activity, activity.session, prefs)
        try {
            assertTrue(descendants(requireNotNull(dialog.window).decorView).filterIsInstance<TextView>().any {
                it.text.toString() == "Volume du film : 53 %"
            })
        } finally { dialog.dismiss() }
        assertEquals("The gesture and QuickMix must describe the same stored setting", "Volume film 53 %", feedback)
    }

    @Test fun brightnessGestureDisplaysTheWholePercentActuallyStoredInTheWindow() = withCinema { activity, cinema ->
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = .45f }
        raiseByEightPoints(cinema, 250f)
        assertEquals("The real onScroll did not apply the intended brightness change", .53f,
            activity.window.attributes.screenBrightness, .000001f)
        assertEquals(53, (activity.window.attributes.screenBrightness * 100).roundToInt())
        val feedback = descendants(cinema).filterIsInstance<TextView>().single {
            it.text.toString().startsWith("Luminosité ")
        }.text.toString()
        assertEquals("Luminosité 53 %", feedback)
    }
}
