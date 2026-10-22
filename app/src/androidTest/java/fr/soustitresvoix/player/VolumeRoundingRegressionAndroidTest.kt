package fr.soustitresvoix.player

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class VolumeRoundingRegressionAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    @Before fun resetPreferences() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
        instrument.targetContext.getSharedPreferences("playback-measurements", 0).edit().clear().commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun quickMixPreservesTheDisplayedPercentAndAppliesExactlyFivePoints() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val prefs = activity.session.prefs
                for (percent in listOf(53, 59)) for (delta in listOf(-5, 5)) {
                    prefs.movieVolume = percent / 100f
                    prefs.duckVolume = percent / 100f
                    prefs.voiceVolume = percent / 100f
                    val dialog = QuickMixDialog.show(activity, activity.session, prefs)
                    try {
                        val views = descendants(requireNotNull(dialog.window).decorView).toList()
                        val labels = listOf("Volume du film", "Son du film pendant la voix", "Volume de la voix — prochain segment")
                        for (label in labels) {
                            val caption = views.filterIsInstance<TextView>().single {
                                it.text.toString() == "$label : $percent %"
                            }
                            val action = if (delta > 0) "augmenter" else "diminuer"
                            views.filterIsInstance<Button>().single {
                                it.contentDescription?.toString() == "$label : $action de cinq pour cent"
                            }.performClick()
                            assertEquals("$label : ${percent + delta} %", caption.text.toString())
                        }
                        val expected = (percent + delta) / 100f
                        assertEquals(expected, prefs.movieVolume, 0f)
                        assertEquals(expected, prefs.duckVolume, 0f)
                        assertEquals(expected, prefs.voiceVolume, 0f)
                    } finally { dialog.dismiss() }
                }
            }
        }
    }

    @Test fun reopeningSettingsKeepsWholePercentValuesForVolumeAndSpeechRate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (rate in listOf(105, 106, 117, 118)) {
                scenario.onActivity { activity ->
                    activity.session.prefs.apply {
                        speechRate = rate / 100f
                        voiceVolume = 53 / 100f
                        movieVolume = 59 / 100f
                        duckVolume = 53 / 100f
                    }
                    descendants(activity.window.decorView).filterIsInstance<Button>().single {
                        it.text.toString() == "Réglages de la voix et du confort"
                    }.performClick()
                }
                for ((label, minimum, percent) in listOf(
                    Triple("Vitesse de la voix", 60, rate),
                    Triple("Volume de la voix", 10, 53),
                    Triple("Volume habituel du film", 0, 59),
                    Triple("Son du film conservé pendant la voix", 0, 53)
                )) {
                    onView(withText("$label : $percent %")).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        assertEquals("$label : $percent %", (view as TextView).text.toString())
                    }
                    onView(withContentDescription(label)).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        assertEquals(percent - minimum, (view as SeekBar).progress)
                    }
                }
                onView(withText("Appliquer et fermer")).inRoot(isDialog()).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(rate / 100f, activity.session.prefs.speechRate, 0f)
                    assertEquals(53 / 100f, activity.session.prefs.voiceVolume, 0f)
                    assertEquals(59 / 100f, activity.session.prefs.movieVolume, 0f)
                    assertEquals(53 / 100f, activity.session.prefs.duckVolume, 0f)
                }
            }
        }
    }
}
