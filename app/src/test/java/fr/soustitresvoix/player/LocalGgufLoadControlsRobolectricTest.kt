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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
            views.filterIsInstance<CheckBox>().single {
                it.text.toString().startsWith("Cache d’attention locale compact")
            }.isChecked = true
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

    @Test fun quantizedCacheIsOptInAndPersistsWithoutStartingModelWork() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get()
        val preferences = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
        preferences.edit().clear().putInt("local-gpu-layers", 8).putInt("local-batch-tokens", 128)
            .putInt("local-micro-batch-tokens", 32).putBoolean("local-compact-swa", true).commit()
        var actions = 0
        fun controls() = LocalGgufModelControls(activity, { false }, { actions++ }, { actions++ }, { actions++ })
        fun choice(root: View) = descendants(root).filterIsInstance<CheckBox>().single {
            it.text.toString().startsWith("Cache d’attention K/V en 8 bits")
        }
        val first = controls()
        try {
            val firstView = first.view()
            val option = choice(firstView)
            assertFalse(option.isChecked)
            assertEquals(LocalGgufLoadSettings(8, 128, 32, true), first.loadSettings)
            option.isChecked = true
            assertEquals("Q8_0", first.loadSettings.kvCacheType)
            first.saveChoices()
            val second = controls()
            val hidden = controls()
            try {
                val restored = choice(second.view())
                assertTrue(restored.isChecked)
                assertEquals(first.loadSettings, second.loadSettings)
                hidden.view(showLoadChoices = false)
                assertEquals(first.loadSettings, hidden.loadSettings)
                second.setActive(false)
                assertFalse(restored.isEnabled)
                second.setActive(true)
                assertTrue(restored.isEnabled)
                restored.isChecked = false
                second.saveChoices()
                assertEquals("F16", preferences.getString("local-kv-cache-type", null))
                assertEquals(0, actions)
                assertNull(first.model)
            } finally { second.close(); hidden.close() }
        } finally { first.close(); owner.pause().stop().destroy() }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun experimentalCacheChoiceIsReadableAtDoubleFontScale() {
        val oldQualifiers = RuntimeEnvironment.getQualifiers()
        val oldScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setQualifiers("w320dp-h480dp")
            RuntimeEnvironment.setFontScale(2f)
            val owner = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = owner.get()
            activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE).edit().clear().commit()
            val controls = LocalGgufModelControls(activity, { false }, {}, {}, {})
            try {
                val root = controls.view()
                val width = (320 * activity.resources.displayMetrics.density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                root.layout(0, 0, width, root.measuredHeight)
                val option = descendants(root).filterIsInstance<CheckBox>().single {
                    it.text.toString().startsWith("Cache d’attention K/V en 8 bits")
                }
                assertEquals(2f, option.resources.configuration.fontScale, 0f)
                assertNull(option.ellipsize)
                assertTrue(option.maxLines > 1)
                assertTrue(option.minHeight >= (56 * activity.resources.displayMetrics.density).toInt())
                val layout = requireNotNull(option.layout)
                assertTrue(layout.lineCount > 1)
                for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                assertFalse(option.isChecked)
            } finally { controls.close(); owner.pause().stop().destroy() }
        } finally {
            RuntimeEnvironment.setFontScale(oldScale)
            RuntimeEnvironment.setQualifiers(oldQualifiers)
        }
    }
}
