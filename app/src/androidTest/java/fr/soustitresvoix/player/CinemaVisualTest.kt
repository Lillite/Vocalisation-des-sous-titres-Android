package fr.soustitresvoix.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Screenshots are synthetic emulator UI only, stored in existing CI test logs. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CinemaVisualTest {
    @Test fun landscapeRotationKeepsThePlayerAndOverlayCanDisappearCompletely() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("cinemaOrientation", 1)
            .putString("enginePackage", instrumentation.context.packageName)
            .putString("voiceName", "test-fr").commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original: Any? = null
            scenario.onActivity { a ->
                original = a.session.player
                a.session.open(SavedMedia("android.resource://${a.packageName}/${R.raw.demonstration}", "Démonstration"))
                a.setFullscreen(true)
            }
            var landscape = false
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!landscape && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && it.findViewById<View>(R.id.video_host).width > it.findViewById<View>(R.id.video_host).height }
                SystemClock.sleep(100)
            }
            assertTrue("Landscape configuration was not applied", landscape)
            instrumentation.waitForIdleSync(); SystemClock.sleep(800)
            scenario.onActivity { a ->
                assertSame("Rotation reconstructed the playback engine", original, a.session.player)
                a.findViewById<CinemaControls>(R.id.cinema_controls).setVisible(true)
            }
            capture("landscape-controls")
            scenario.onActivity { a ->
                a.findViewById<CinemaControls>(R.id.cinema_controls).setVisible(false)
                assertFalse(a.findViewById<View>(R.id.cinema_play).isShown)
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            capture("landscape-video-only")
            scenario.onActivity { a -> a.setFullscreen(false); assertSame(original, a.session.player) }
        }
    }
    private fun capture(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("No screenshot returned by the emulator", image)
        val out = ByteArrayOutputStream()
        image!!.compress(Bitmap.CompressFormat.JPEG, 85, out); image.recycle()
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val chunks = encoded.chunked(2400)
        assertTrue("Unexpectedly huge test screenshot", chunks.size < 600)
        chunks.forEachIndexed { index, chunk -> Log.i("CinemaScreenshot", "SHOT|$name|$index|${chunks.size}|$chunk") }
    }
}
