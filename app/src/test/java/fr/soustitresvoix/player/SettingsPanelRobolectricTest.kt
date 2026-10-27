package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.Cue
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import fr.soustitresvoix.core.NarrationMachine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@UnstableApi
class SettingsPanelRobolectricTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    @Test fun largeFontsKeepAScrollableSettingsAreaInAShortWindow() {
        val previous = ApplicationProvider.getApplicationContext<Context>().resources.configuration.fontScale
        RuntimeEnvironment.setFontScale(2f)
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val panel = SettingsPanel(owner.get())
            panel.add(SettingsSection.VOICE, "Commande", Button(owner.get()).apply { text = "Commande" })
            panel.installActions({}, {})
            for ((width, height) in listOf(900 to 700, 1600 to 500)) {
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                val scroll = descendants(panel).filterIsInstance<android.widget.ScrollView>().single()
                assertTrue("The weighted scroll area collapsed in a short window", scroll.measuredHeight > 0)
                assertTrue(panel.measuredHeight <= height)
                val actions = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE).map { requireNotNull(panel.actionButton(it)) }
                for (control in actions) {
                    val touch = android.graphics.Rect()
                    control.getDrawingRect(touch); panel.offsetDescendantRectToMyCoords(control, touch)
                    assertTrue("A settings action is clipped horizontally: $touch", touch.left >= 0 && touch.right <= panel.width)
                    assertTrue("A settings action is clipped vertically: $touch", touch.top >= 0 && touch.bottom <= panel.height)
                    assertTrue(control.height >= (56 * panel.resources.displayMetrics.density).toInt())
                }
                assertTrue(actions.first().bottom <= actions.last().top)
            }
        } finally { owner.pause().stop().destroy(); RuntimeEnvironment.setFontScale(previous) }
    }
    @Test fun searchFindsTheExactCommandAcrossSectionsWithoutActivatingIt() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = owner.get()
            val panel = SettingsPanel(activity)
            activity.setContentView(panel)
            var activations = 0
            val voice = Button(activity).apply { text = "Choisir la voix"; setOnClickListener { activations++ } }
            val translation = Button(activity).apply { text = "Traduire"; setOnClickListener { activations++ } }
            val cache = Button(activity).apply { text = "Limite du cache"; setOnClickListener { activations++ } }
            panel.add(SettingsSection.VOICE, "Choisir la voix", voice)
            panel.add(SettingsSection.TRANSLATION, "Traduire les sous-titres", translation, "français fournisseur")
            panel.add(SettingsSection.STORAGE, "Limite du cache", cache, "mémoire espace")
            assertEquals(View.VISIBLE, voice.visibility)
            assertEquals(View.GONE, translation.visibility)
            assertEquals(8, descendants(panel).filterIsInstance<Spinner>().single().adapter.count)
            panel.search.setText("francais")
            assertEquals(View.GONE, voice.visibility)
            assertEquals(View.VISIBLE, translation.visibility)
            assertEquals(View.GONE, cache.visibility)
            panel.search.setText("MEMOIRE cache")
            assertEquals(View.VISIBLE, cache.visibility)
            assertEquals(View.GONE, translation.visibility)
            panel.select(SettingsSection.TRANSLATION)
            assertTrue(panel.search.text.isBlank())
            assertEquals(View.VISIBLE, translation.visibility)
            assertEquals(0, activations)
            translation.performClick()
            assertEquals(1, activations)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun touchingAScrolledSettingReleasesEarlierTextFocusWithoutRebuildingControls() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = owner.get()
            val panel = SettingsPanel(activity)
            repeat(10) { index ->
                panel.add(SettingsSection.VOICE, "Commande $index", Button(activity).apply {
                    text = "Commande $index"; minimumHeight = 100
                })
            }
            var value = 50
            val number = SettingsNumberControl(activity, "Volume", 0, 100, 5, "%", { value }, { value = it })
            panel.add(SettingsSection.VOICE, "Volume", number)
            panel.onRefresh(number::refresh)
            activity.setContentView(panel)
            fun layout() {
                panel.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, 600, 700)
            }
            layout()
            val scroll = descendants(panel).filterIsInstance<android.widget.ScrollView>().single()
            assertTrue(scroll.isFocusableInTouchMode)
            assertTrue(panel.search.requestFocus())
            val slider = requireNotNull(number.seekBar)
            val bounds = android.graphics.Rect(0, 0, slider.width, slider.height)
            scroll.offsetDescendantRectToMyCoords(slider, bounds)
            scroll.scrollTo(0, bounds.top - 100)
            val before = scroll.scrollY
            assertTrue(before > 0)
            val scrollLocation = IntArray(2).also(scroll::getLocationOnScreen)
            val sliderLocation = IntArray(2).also(slider::getLocationOnScreen)
            val x = sliderLocation[0] - scrollLocation[0] + slider.width * .75f
            val y = sliderLocation[1] - scrollLocation[1] + slider.height / 2f
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                android.view.MotionEvent.obtain(now, now, action, x, y, 0).let { event ->
                    try { scroll.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            panel.refresh(); layout()
            assertFalse("An earlier text input must not reclaim the viewport", panel.search.hasFocus())
            assertEquals(before, scroll.scrollY)
            assertSame(slider, number.seekBar)
            assertTrue("Explicit keyboard editing must remain available", number.field.requestFocus())
            assertTrue(number.field.hasFocus())
        } finally { owner.pause().stop().destroy() }
    }

    @Test fun numericValidationRejectsOutOfRangeAndOverflowAndAllInputsShareOneValue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var stored = 100
        var writes = 0
        val control = SettingsNumberControl(context, "Vitesse de la voix", 60, 200, 5, "%",
            { stored }, { stored = it; writes++ }, listOf(80, 100, 125))
        control.field.setText("201")
        assertFalse(control.commitField()); assertEquals(100, stored); assertEquals(0, writes)
        control.field.setText("999999999999999999999999")
        assertFalse(control.commitField()); assertEquals(0, writes)
        control.field.setText("117")
        assertTrue(control.commitField()); assertEquals(117, stored); assertEquals(57, control.seekBar!!.progress)
        descendants(control).filterIsInstance<Button>().single { it.text.toString() == "+ 5 %" }.performClick()
        assertEquals(122, stored)
        assertTrue(descendants(control).filterIsInstance<Button>().single { it.text.toString() == "+ 5 %" }.contentDescription.toString().contains("Vitesse de la voix"))
        descendants(control).filterIsInstance<Button>().single { it.text.toString() == "Préréglage : 80 %" }.performClick()
        assertEquals(80, stored); assertEquals("80", control.field.text.toString())
        val before = writes
        stored = 125; control.refresh()
        assertEquals(before, writes)
        assertEquals(65, control.seekBar!!.progress)
        assertTrue(control.seekBar!!.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(stored > 125); assertEquals(stored.toString(), control.field.text.toString())
    }
    @Test fun closingKeepsImmediateSettingsAndCancelRestoresTheOpeningSnapshotWithoutResuming() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().putBoolean("narration", false).commit()
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = owner.get()
            fun open(): AlertDialog {
                descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text.toString() == "Réglages de la voix et du confort"
                }.performClick()
                return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            }
            fun increaseRate(dialog: AlertDialog) {
                descendants(requireNotNull(dialog.window).decorView).filterIsInstance<Button>().single {
                    it.contentDescription?.toString()?.startsWith("Vitesse de la voix : augmenter de 5") == true
                }.performClick()
            }
            var dialog = open()
            try {
                increaseRate(dialog)
                assertEquals(1.05f, activity.session.prefs.speechRate, 0f)
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                assertEquals(1.05f, activity.session.prefs.speechRate, 0f)
            } finally { dialog.dismiss() }
            dialog = open()
            try {
                val originalDisplay = activity.session.translation.displayMode
                increaseRate(dialog)
                assertEquals(1.10f, activity.session.prefs.speechRate, 0f)
                activity.session.translation.setDisplayMode(TranslationDisplayMode.HIDDEN)
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                assertEquals(1.05f, activity.session.prefs.speechRate, 0f)
                assertEquals(originalDisplay, activity.session.translation.displayMode)
                assertFalse(activity.session.player.playWhenReady)
                assertFalse(activity.session.machine.snapshot().wantsPlayback)
            } finally { dialog.dismiss() }
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun translatedOverlayMovesWithTheVideoAndNeverDuplicatesVisibleOriginalSubtitles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().putBoolean("narration", false).commit()
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = owner.get()
            val overlay = requireNotNull(activity.window.decorView.findViewWithTag<SubtitleView>("narration-subtitle-overlay"))
            val playerView = overlay.parent as PlayerView
            val cue = Cue.Builder().setText("Texte traduit synthétique").build()
            requireNotNull(activity.session.captionSink).invoke(listOf(cue), true)
            assertEquals(View.VISIBLE, overlay.visibility)
            assertEquals(View.INVISIBLE, playerView.subtitleView!!.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, overlay.importantForAccessibility)
            activity.setFullscreen(true)
            assertSame(overlay, activity.window.decorView.findViewWithTag<SubtitleView>("narration-subtitle-overlay"))
            requireNotNull(activity.session.captionSink).invoke(listOf(cue), true)
            assertEquals(View.INVISIBLE, playerView.subtitleView!!.visibility)
            activity.session.prefs.showSubtitles = false
            requireNotNull(activity.session.captionSink).invoke(listOf(cue), true)
            assertEquals(View.GONE, overlay.visibility)
            assertEquals(View.INVISIBLE, playerView.subtitleView!!.visibility)
            activity.session.prefs.showSubtitles = true
            requireNotNull(activity.session.captionSink).invoke(null, false)
            assertEquals(View.GONE, overlay.visibility)
            assertEquals(View.VISIBLE, playerView.subtitleView!!.visibility)
            assertFalse(activity.session.player.playWhenReady)
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun comprehensionAndIntegralPresetsRemoveLossyFiltersAndKeepCompleteReading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().putBoolean("narration", false).commit()
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = owner.get()
            val prefs = activity.session.prefs
            for ((label, expected) in listOf("Préréglage : lecture intégrale" to NarrationMachine.Mode.COMFORT,
                "Préréglage : compréhension confortable" to NarrationMachine.Mode.ADAPTIVE,
                "Préréglage : compréhension équilibrée" to NarrationMachine.Mode.ADAPTIVE,
                "Préréglage : pauses rares" to NarrationMachine.Mode.ADAPTIVE)) {
                prefs.putBool("integralReading", false); prefs.setMode(NarrationMachine.Mode.CONTINUOUS)
                prefs.skipDescriptions = true
                listOf("skipSpeakers", "skipSongs", "skipNotes").forEach { prefs.putBool(it, true) }
                descendants(activity.window.decorView).filterIsInstance<Button>().single {
                    it.text.toString() == "Réglages de la voix et du confort"
                }.performClick()
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                try {
                    val panel = descendants(requireNotNull(dialog.window).decorView).filterIsInstance<SettingsPanel>().single()
                    panel.select(SettingsSection.SYNCHRONIZATION)
                    descendants(panel).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()
                    assertTrue(prefs.bool("integralReading", true)); assertTrue(prefs.narration)
                    assertEquals(expected, prefs.mode); assertFalse(prefs.skipDescriptions)
                    if (expected == NarrationMachine.Mode.ADAPTIVE) {
                        val (maximum, pause) = when (label) {
                            "Préréglage : compréhension confortable" -> 140 to 350
                            "Préréglage : compréhension équilibrée" -> 180 to 0
                            else -> 220 to 0
                        }
                        assertEquals(1f, prefs.speechRate, 0f)
                        assertEquals(maximum / 100f, prefs.adaptiveMax, 0f)
                        assertEquals(pause, prefs.int("comprehensionPauseMs", 0, 0, 3000))
                        val field = descendants(panel).filterIsInstance<android.widget.EditText>().single {
                            it.contentDescription?.toString()?.startsWith("Pause de compréhension après une phrase : valeur précise") == true
                        }
                        assertEquals(pause.toString(), field.text.toString())
                    }
                    listOf("skipSpeakers", "skipSongs", "skipNotes").forEach { assertFalse(prefs.bool(it)) }
                    assertFalse(activity.session.player.playWhenReady)
                    assertFalse(activity.session.machine.snapshot().wantsPlayback)
                } finally { dialog.dismiss() }
            }
        } finally { owner.pause().stop().destroy() }
    }
    @Test fun integralReadingDisablesEveryExclusionAndRefreshesThemImmediatelyWhenExplicitlyDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().putBoolean("narration", false)
            .putBoolean("skipDescriptions", true).putBoolean("skipSpeakers", true).commit()
        val owner = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = owner.get()
            descendants(activity.window.decorView).filterIsInstance<Button>().single {
                it.text.toString() == "Réglages de la voix et du confort"
            }.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            try {
                val panel = descendants(requireNotNull(dialog.window).decorView).filterIsInstance<SettingsPanel>().single()
                val checks = descendants(panel).filterIsInstance<android.widget.CheckBox>().toList()
                val integral = checks.single { it.text.toString().startsWith("Lecture intégrale :") }
                val exclusions = checks.filter { it.text.toString().startsWith("Ignorer les ") ||
                    it.text.toString().startsWith("Ne pas lire les indications") }
                assertEquals(4, exclusions.size)
                assertTrue(integral.isChecked)
                assertTrue(exclusions.all { !it.isEnabled })
                assertEquals("[musique]\nALICE: Bonjour", fr.soustitresvoix.core.SpeechTools.filter("[musique]\nALICE: Bonjour", activity.session.prefs.filters))
                integral.performClick()
                assertFalse(activity.session.prefs.bool("integralReading", true))
                assertTrue(exclusions.all { it.isEnabled })
                assertEquals("Bonjour", fr.soustitresvoix.core.SpeechTools.filter("[musique]\nALICE: Bonjour", activity.session.prefs.filters))
                integral.performClick()
                assertTrue(exclusions.all { !it.isEnabled })
                assertEquals("[musique]\nALICE: Bonjour", fr.soustitresvoix.core.SpeechTools.filter("[musique]\nALICE: Bonjour", activity.session.prefs.filters))
                assertFalse(activity.session.machine.snapshot().wantsPlayback)
            } finally { dialog.dismiss() }
        } finally { owner.pause().stop().destroy() }
    }

}
