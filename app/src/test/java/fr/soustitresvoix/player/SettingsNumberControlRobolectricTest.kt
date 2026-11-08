package fr.soustitresvoix.player

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsNumberControlRobolectricTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun exactBoundsRecoverAnInvalidDraftAndApplyTheExistingCommandOnce() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var current = 25
            val writes = mutableListOf<Int>()
            val control = SettingsNumberControl(owner.get(), "Valeur synthétique", -7, 42, 10, "ms",
                { current }, { current = it; writes += it })
            owner.get().setContentView(control)
            val buttons = descendants(control).filterIsInstance<Button>().toList()
            val minimum = buttons.single { it.text == "Minimum : -7 ms" }
            val maximum = buttons.single { it.text == "Maximum : 42 ms" }
            control.field.setText("999")
            assertFalse(control.commitField())
            assertEquals(25, current)
            maximum.performClick()
            assertEquals(42, current)
            assertEquals("42", control.field.text.toString())
            assertNull(control.field.error)
            assertEquals(49, control.seekBar!!.progress)
            assertFalse(maximum.isEnabled)
            maximum.performClick()
            assertEquals(listOf(42), writes)
            minimum.performClick()
            assertEquals(-7, current)
            assertEquals(0, control.seekBar!!.progress)
            assertFalse(minimum.isEnabled)
            assertEquals(listOf(42, -7), writes)
            assertTrue(minimum.contentDescription.toString().contains("Valeur synthétique"))
            assertTrue(maximum.contentDescription.toString().contains("42 ms"))
        } finally { owner.pause().stop().destroy() }
    }
}
