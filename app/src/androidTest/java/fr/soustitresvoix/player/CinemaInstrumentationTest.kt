package fr.soustitresvoix.player

import android.view.View
import android.view.WindowInsets
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.MkvTextIndex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import android.os.SystemClock

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CinemaInstrumentationTest {
    @Before fun reset() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("player", 0).edit().clear().putBoolean("narration", false).putInt("cinemaOrientation", 0).putString("enginePackage", InstrumentationRegistry.getInstrumentation().context.packageName).putString("voiceName", "test-fr").commit()
    }
    @Test fun fullscreenUsesWholeContentWindowAndKeepsSamePlayer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original: Any? = null
            scenario.onActivity { original = it.session.player; it.setFullscreen(true) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(600)
            scenario.onActivity { a ->
                val content = a.findViewById<android.view.ViewGroup>(android.R.id.content)
                val host = a.findViewById<View>(R.id.video_host)
                assertEquals(content.width, host.width); assertEquals(content.height, host.height)
                assertSame(original, a.session.player)
                val controls = a.findViewById<CinemaControls>(R.id.cinema_controls)
                controls.setVisible(false)
                assertFalse(a.findViewById<View>(R.id.cinema_play).isShown)
                controls.setVisible(true); controls.setLocked(true)
                assertTrue(a.handleCinemaBack()); assertFalse(controls.locked)
                assertTrue(a.handleCinemaBack()); assertFalse(a.handleCinemaBack())
                assertSame(original, a.session.player)
            }
        }
    }
    @Test fun immersiveBarsAndImageModesAreControllable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setFullscreen(true) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(1200)
            scenario.onActivity { a ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val insets = a.window.decorView.rootWindowInsets
                    assertFalse("Status bar still visible", insets.isVisible(WindowInsets.Type.statusBars()))
                    assertFalse("Navigation bar still visible", insets.isVisible(WindowInsets.Type.navigationBars()))
                }
                val c = a.findViewById<CinemaControls>(R.id.cinema_controls)
                c.setImageMode(1); c.setZoom(2f); assertEquals(2f, c.zoom, 0f)
                c.setImageMode(0); assertEquals(1f, c.zoom, 0f)
                c.setZoom(100f); assertEquals(3f, c.zoom, 0f)
                a.setFullscreen(false)
            }
        }
    }
    @Test fun packagedMkvCanBeIndexedReadOnlyWithEightSubtitles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.resources.openRawResourceFd(R.raw.demonstration).use { afd ->
            FileInputStream(afd.fileDescriptor).use { input ->
                val index = MkvTextIndex(input.channel, afd.startOffset, afd.length).read()
                assertEquals(8, index.values.sumOf { it.size })
                assertTrue(index.values.flatten().first().text.startsWith("Bonjour"))
            }
        }
    }
    @Test fun androidIcuAcceptsPlanningAndFiltersAndDiagnosticOmitsMediaNames() {
        fr.soustitresvoix.core.SpeechPlanning.filter("MARIE: [musique] Bonjour ♪", fr.soustitresvoix.core.SpeechPlanning.Filters(true, true, false, true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val report = a.session.metrics.report(a.session.speech)
                assertTrue(report.contains("non mesuré")); assertTrue(report.contains("BATTERIE"))
                assertFalse(report.contains("android.resource://"))
            }
        }
    }
}
