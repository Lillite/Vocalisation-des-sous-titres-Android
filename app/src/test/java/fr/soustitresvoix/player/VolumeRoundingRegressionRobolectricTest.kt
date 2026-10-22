package fr.soustitresvoix.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Corrected-state regressions. They must fail on the uncorrected baseline. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class VolumeRoundingRegressionRobolectricTest {
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

    private fun withActivity(action: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { action(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }

    @Test fun quickMixDisplaysWholePercentsAndAppliesFivePointsToEveryVolume() = withActivity { activity ->
        val prefs = activity.session.prefs
        for (percent in listOf(53, 59)) for (delta in listOf(-5, 5)) {
            prefs.movieVolume = percent / 100f
            prefs.duckVolume = percent / 100f
            prefs.voiceVolume = percent / 100f
            val dialog = QuickMixDialog.show(activity, activity.session, prefs)
            try {
                val views = descendants(requireNotNull(dialog.window).decorView).toList()
                for (label in listOf("Volume du film", "Son du film pendant la voix", "Volume de la voix — prochain segment")) {
                    val caption = views.filterIsInstance<TextView>().singleOrNull {
                        it.text.toString() == "$label : $percent %"
                    }
                    assertNotNull("The dialog did not display the stored $percent percent for $label", caption)
                    val action = if (delta > 0) "augmenter" else "diminuer"
                    views.filterIsInstance<Button>().single {
                        it.contentDescription?.toString() == "$label : $action de cinq pour cent"
                    }.performClick()
                    assertEquals("$label : ${percent + delta} %", requireNotNull(caption).text.toString())
                }
                val expected = (percent + delta) / 100f
                assertEquals(expected, prefs.movieVolume, 0f)
                assertEquals(expected, prefs.duckVolume, 0f)
                assertEquals(expected, prefs.voiceVolume, 0f)
            } finally { dialog.dismiss() }
        }
    }

    @Test fun reopeningSettingsDisplaysTheStoredWholePercentAndSliderPosition() = withActivity { activity ->
        for (rate in listOf(105, 106, 117, 118)) {
            activity.session.prefs.apply {
                speechRate = rate / 100f
                voiceVolume = 53 / 100f
                movieVolume = 59 / 100f
                duckVolume = 53 / 100f
            }
            descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == "Réglages de la voix et du confort"
            }.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            try {
                val views = descendants(requireNotNull(dialog.window).decorView).toList()
                for ((label, minimum, percent) in listOf(
                    Triple("Vitesse de la voix", 60, rate),
                    Triple("Volume de la voix", 10, 53),
                    Triple("Volume habituel du film", 0, 59),
                    Triple("Son du film conservé pendant la voix", 0, 53)
                )) {
                    assertTrue("The panel did not display the stored $percent percent for $label", views.filterIsInstance<TextView>().any {
                        it.text.toString() == "$label : $percent %"
                    })
                    val slider = views.filterIsInstance<SeekBar>().single { it.contentDescription?.toString() == label }
                    assertEquals("Wrong slider position for $label", percent - minimum, slider.progress)
                }
            } finally { dialog.dismiss() }
            assertEquals(rate / 100f, activity.session.prefs.speechRate, 0f)
            assertEquals(53 / 100f, activity.session.prefs.voiceVolume, 0f)
            assertEquals(59 / 100f, activity.session.prefs.movieVolume, 0f)
            assertEquals(53 / 100f, activity.session.prefs.duckVolume, 0f)
        }
    }
}
