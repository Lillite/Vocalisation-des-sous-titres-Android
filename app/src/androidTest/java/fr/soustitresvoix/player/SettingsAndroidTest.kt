package fr.soustitresvoix.player

import android.widget.Spinner
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.NarrationMachine
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real widgets, search, SAF-independent settings and rollback on Android. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SettingsAndroidTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    @Before fun reset() {
        instrument.targetContext.getSharedPreferences("player", 0).edit().clear()
            .putBoolean("narration", false).putInt("orientation", 0).commit()
    }
    private fun open() { onView(withText("Réglages de la voix et du confort")).perform(scrollTo(), click()) }
    private fun search(value: String) {
        onView(withContentDescription("Rechercher dans les huit rubriques de réglages"))
            .inRoot(isDialog()).perform(replaceText(value), closeSoftKeyboard())
    }
    private fun preciseAcceleration(value: Int) {
        onView(withContentDescription("Accélération maximale du mode adaptatif : valeur précise en %, de 100 à 250"))
            .inRoot(isDialog()).perform(scrollTo(), replaceText(value.toString()), closeSoftKeyboard())
        onView(allOf(withText("Valider"), isDisplayed())).inRoot(isDialog()).perform(click())
    }
    @Test fun eightSectionsSearchWithoutAccentsAndCancelRestoresImmediateChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open()
            onView(withContentDescription("Choisir une rubrique de réglages")).inRoot(isDialog()).check { view, error ->
                if (error != null) throw error
                val names = (view as Spinner).adapter.let { adapter -> (0 until adapter.count).map { adapter.getItem(it).toString() } }
                assertEquals(listOf("Voix", "Synchronisation", "Traduction", "Sous-titres", "Image", "Son", "Stockage", "Diagnostic"), names)
            }
            search("acceleration maximale")
            preciseAcceleration(125)
            scenario.onActivity { assertEquals(1.25f, it.session.prefs.adaptiveMax, 0f); assertFalse(it.session.player.playWhenReady) }
            onView(withText("Fermer")).inRoot(isDialog()).check(matches(isCompletelyDisplayed())).perform(click())
            open(); search("ACCELERATION maximale"); preciseAcceleration(150)
            scenario.onActivity { assertEquals(1.5f, it.session.prefs.adaptiveMax, 0f) }
            onView(withText("Annuler modifications")).inRoot(isDialog()).check(matches(isCompletelyDisplayed())).perform(click())
            scenario.onActivity {
                assertEquals(1.25f, it.session.prefs.adaptiveMax, 0f)
                assertFalse(it.session.player.playWhenReady); assertFalse(it.session.machine.snapshot().wantsPlayback)
            }
        }
    }
    @Test fun allCompleteReadingPresetsKeepPanelsSongsAndRepeatedOccurrencesEligible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((label, mode) in listOf("Préréglage : lecture intégrale" to NarrationMachine.Mode.COMFORT,
                "Préréglage : compréhension confortable" to NarrationMachine.Mode.ADAPTIVE,
                "Préréglage : compréhension équilibrée" to NarrationMachine.Mode.ADAPTIVE,
                "Préréglage : pauses rares" to NarrationMachine.Mode.ADAPTIVE)) {
                scenario.onActivity { activity ->
                    activity.session.prefs.apply {
                        narration = false; putBool("integralReading", false); setMode(NarrationMachine.Mode.CONTINUOUS)
                        skipDescriptions = true
                        listOf("skipSongs", "skipSpeakers", "skipNotes").forEach { putBool(it, true) }
                    }
                }
                open(); search(label)
                onView(allOf(isAssignableFrom(android.widget.Button::class.java), withText(label)))
                    .inRoot(isDialog()).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    val prefs = activity.session.prefs
                    assertTrue(prefs.narration); assertTrue(prefs.bool("integralReading", true)); assertEquals(mode, prefs.mode)
                    if (mode == NarrationMachine.Mode.ADAPTIVE) {
                        val (maximum, pause) = when (label) {
                            "Préréglage : compréhension confortable" -> 140 to 350
                            "Préréglage : compréhension équilibrée" -> 180 to 0
                            else -> 220 to 0
                        }
                        assertEquals(1f, prefs.speechRate, 0f)
                        assertEquals(maximum / 100f, prefs.adaptiveMax, 0f)
                        assertEquals(pause, prefs.int("comprehensionPauseMs", 0, 0, 3000))
                    }
                    assertFalse(prefs.skipDescriptions)
                    listOf("skipSongs", "skipSpeakers", "skipNotes").forEach { assertFalse(prefs.bool(it)) }
                    assertFalse(activity.session.player.playWhenReady)
                }
                onView(withText("Fermer")).inRoot(isDialog()).check(matches(isCompletelyDisplayed())).perform(click())
            }
        }
    }
}
