package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.roundToInt

/** Does not pause the video. Current live TTS volume changes at the next segment. */
@UnstableApi
object QuickMixDialog {
    fun show(activity: Activity, session: PlaybackSession, prefs: PlayerPreferences): AlertDialog {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val generation = session.audioCommands.snapshot().context.generation
        column.addView(AudioDspControls.openButton(activity, column, session.audioCommands, generation))
        val voiceColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun level(label: String, minimum: Int, get: () -> Float, set: (Float) -> Unit) {
            val title = TextView(activity).apply { textSize = 18f; setTextColor(Color.WHITE) }; voiceColumn.addView(title)
            fun update() { title.text = activity.getString(R.string.audio_level_caption, label, (get() * 100).roundToInt()) }
            val row = LinearLayout(activity)
            for (delta in listOf(-5, 5)) row.addView(Button(activity).apply {
                text = if (delta < 0) "−5 points" else "+5 points"; isAllCaps = false; minHeight = dp(56)
                contentDescription = "$label : ${if (delta < 0) "diminuer" else "augmenter"} de cinq points de pourcentage"
                setOnClickListener {
                    val state = session.audioCommands.snapshot().context
                    if (!activity.isFinishing && !activity.isDestroyed && !state.closed && state.generation == generation)
                        set(((get() * 100).roundToInt() + delta).coerceIn(minimum, 100) / 100f)
                    update()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            voiceColumn.addView(row); update()
        }
        level("Volume de la voix — prochain segment", 10, { prefs.voiceVolume }) {
            session.finishPlaybackBenchmark("Volume vocal modifié : essai interrompu"); prefs.voiceVolume = it
        }
        voiceColumn.addView(TextView(activity).apply {
            setText(R.string.quick_mix_voice_help)
            textSize = 16f; setTextColor(Color.WHITE)
        })
        column.addView(AudioSettingsControls(activity, session.audioCommands, generation, afterLevels = voiceColumn).view)
        column.addView(TextView(activity).apply { setText(R.string.quick_mix_running_hint); textSize = 16f; setTextColor(Color.WHITE) })
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
