package fr.soustitresvoix.player

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufLoadControlsRobolectricTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun explicitAllocationAndVulkanChoicesPersistWithoutImportingOrStartingAModel() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE).edit().clear().commit()
        var actions = 0
        fun controls() = LocalGgufModelControls(activity, { false }, { actions++ }, { actions++ }, { actions++ })
        val first = controls()
        try {
            val views = descendants(first.view())
            fun input(prefix: String) = views.filterIsInstance<EditText>().single { it.contentDescription.toString().startsWith(prefix) }
            input("Couches GPU").setText("8")
            input("Lot logique").setText("128")
            input("Micro-lot").setText("32")
            views.filterIsInstance<CheckBox>().single().isChecked = true
            views.filterIsInstance<Spinner>().single { it.contentDescription == "Exécution du modèle GGUF" }.setSelection(3)
            assertEquals("VULKAN", first.execution)
            assertEquals(LocalGgufLoadSettings(8, 128, 32, true), first.loadSettings)
            first.saveChoices()
            val second = controls()
            try {
                assertEquals("VULKAN", second.execution)
                assertEquals(first.loadSettings, second.loadSettings)
                assertNull(second.model)
                assertEquals(0, actions)
            } finally { second.close() }
        } finally { first.close(); owner.pause().stop().destroy() }
    }

    @Test fun invalidVisibleAllocationIsRefusedInsteadOfSilentlyUsingDefaults() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE).edit().clear().commit()
        val controls = LocalGgufModelControls(activity, { false }, {}, {}, {})
        try {
            val fields = descendants(controls.view()).filterIsInstance<EditText>()
            fields.single { it.contentDescription.toString().startsWith("Micro-lot") }.setText("not a number")
            assertEquals(0, controls.loadSettings.microBatchTokens)
            try { controls.saveChoices(); fail("Invalid setting saved") }
            catch (failure: TranslationFailure) { assertEquals(TranslationFailure.Reason.CONFIGURATION, failure.reason) }
        } finally { controls.close(); owner.pause().stop().destroy() }
    }
}
