package fr.soustitresvoix.player

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Local verification only; permanent device regressions live in androidTest. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MissingEngineRestoreRegressionTest {
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    @Test fun restoredCinemaReportsTheMissingEngineAfterSessionConstruction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerPreferences(context).apply {
            enginePackage = "fr.soustitresvoix.regression.nonexistent.engine"
            narration = true
            putInt("orientation", 0)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.create(Bundle().apply { putBoolean("fullscreen", true) }).start().resume().visible()
            val activity = controller.get()
            assertNotNull(activity.session)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.session.speech.isReady)
            assertFalse(activity.session.machine.snapshot().wantsPlayback)
            assertNull(activity.findViewById<CinemaView>(R.id.cinema_root))
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains("n’est plus installé") })
        } finally { controller.pause().stop().destroy() }
    }
}
