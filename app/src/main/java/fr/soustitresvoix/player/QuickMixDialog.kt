package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Does not pause the video. Current live TTS volume changes at the next segment. */
@UnstableApi
object QuickMixDialog {
    fun show(activity: Activity, session: PlaybackSession, prefs: PlayerPreferences): AlertDialog {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        fun level(label: String, minimum: Int, get: () -> Float, set: (Float) -> Unit) {
            val title = TextView(activity).apply { textSize = 18f; setTextColor(Color.WHITE) }; column.addView(title)
            fun update() { title.text = "$label : ${(get() * 100).toInt()} %" }
            val row = LinearLayout(activity)
            for (delta in listOf(-5, 5)) row.addView(Button(activity).apply {
                text = if (delta < 0) "−5 %" else "+5 %"; isAllCaps = false; minHeight = dp(56)
                contentDescription = "$label : ${if (delta < 0) "diminuer" else "augmenter"} de cinq pour cent"
                setOnClickListener { set(((get() * 100).toInt() + delta).coerceIn(minimum, 100) / 100f); update() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(row); update()
        }
        level("Volume du film", 0, { prefs.movieVolume }) { prefs.movieVolume = it; session.updateMix() }
        level("Son du film pendant la voix", 0, { prefs.duckVolume }) { prefs.duckVolume = it; session.updateMix() }
        level("Volume de la voix — prochain segment", 10, { prefs.voiceVolume }) { prefs.voiceVolume = it }
        column.addView(TextView(activity).apply { text = "Ces réglages ne mettent pas le film en pause."; textSize = 16f; setTextColor(Color.WHITE) })
        val dialog = AlertDialog.Builder(activity).setTitle("Volumes rapides")
            .setView(ScrollView(activity).apply { addView(column) }).setPositiveButton("Fermer", null).create()
        val owner = activity as? LifecycleOwner
        if (owner != null) {
            if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return dialog
            lateinit var observer: LifecycleEventObserver
            observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
            }
            owner.lifecycle.addObserver(observer)
            dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        }
        if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
        return dialog
    }
}
