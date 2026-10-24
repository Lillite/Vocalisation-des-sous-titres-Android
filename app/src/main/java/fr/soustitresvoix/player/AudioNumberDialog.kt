package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.text.InputType
import android.text.method.TextKeyListener
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.soustitresvoix.core.AudioNumberProblem
import fr.soustitresvoix.core.AudioNumberResult
import fr.soustitresvoix.core.AudioNumberSpec
import fr.soustitresvoix.core.AudioNumericInput
import java.math.BigDecimal

/** A presentation-only editor. Its caller retains the panel's original media
 * ticket and returns a readable refusal, or null after an accepted command. */
internal object AudioNumberDialog {
    fun show(
        activity: Activity,
        owner: View,
        title: String,
        spec: AudioNumberSpec,
        initialValue: String,
        description: String,
        alive: () -> Boolean = { true },
        submit: (BigDecimal) -> String?
    ): AlertDialog? {
        fun available() = !activity.isFinishing && !activity.isDestroyed && owner.isAttachedToWindow && alive()
        if (!available()) return null
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        fun label(value: String) = TextView(activity).apply {
            text = value; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
        }
        val input = EditText(activity).apply {
            id = R.id.audio_numeric_input
            textSize = 20f; setTextColor(Color.WHITE)
            // Do not let a locale-specific DigitsKeyListener silently strip a
            // comma, suffix or invalid pasted character before validation.
            keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                (if (spec.integerOnly) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            // Single-line mode resets TextView's height constraint to one
            // line. Apply the tactile minimum afterwards, in pixels.
            minHeight = dp(56)
            setText(initialValue)
            selectAll()
        }
        val error = label("").apply {
            id = R.id.audio_numeric_error
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val bounds = activity.getString(R.string.audio_numeric_bounds, title, spec.unit,
            spec.minimum.replace('.', ','), spec.maximum.replace('.', ','))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(label(bounds).apply { id = R.id.audio_numeric_label; labelFor = input.id })
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(error)
            addView(label(description))
            addView(label(activity.getString(if (spec.integerOnly) R.string.audio_numeric_integer_help
                else R.string.audio_numeric_decimal_help)))
        }
        val dialog = AudioDialogOwner.show(activity, owner, AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.audio_numeric_title, title))
            .setView(ScrollView(activity).apply { addView(column) })
            .setPositiveButton(R.string.audio_numeric_apply, null)
            .setNegativeButton(R.string.audio_numeric_cancel, null), alive) ?: return null
        fun apply() {
            if (!available() || !dialog.isShowing) return
            val parsed = AudioNumericInput.parse(input.text.toString(), spec)
            val problem = when (parsed) {
                is AudioNumberResult.Valid -> submit(parsed.value)
                is AudioNumberResult.Invalid -> when (parsed.reason) {
                    AudioNumberProblem.TOO_LONG -> activity.getString(R.string.audio_numeric_too_long)
                    AudioNumberProblem.INVALID_NUMBER -> activity.getString(if (spec.integerOnly)
                        R.string.audio_numeric_invalid_integer else R.string.audio_numeric_invalid)
                    AudioNumberProblem.INTEGER_REQUIRED -> activity.getString(R.string.audio_numeric_integer_required)
                    AudioNumberProblem.OUT_OF_RANGE -> activity.getString(R.string.audio_numeric_out_of_range,
                        spec.minimum.replace('.', ','), spec.maximum.replace('.', ','), spec.unit)
                }
            }
            if (problem == null) dialog.dismiss() else {
                error.text = problem
                error.visibility = View.VISIBLE
                error.post {
                    if (dialog.isShowing && available())
                        error.requestRectangleOnScreen(Rect(0, 0, error.width, error.height), true)
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { apply() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { apply(); true } else false
        }
        return dialog
    }
}
