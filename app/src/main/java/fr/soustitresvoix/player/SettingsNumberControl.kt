package fr.soustitresvoix.player

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*

/** One command shared by slider, keyboard, bounded numeric field and presets. */
internal class SettingsNumberControl(
    context: Context, private val label: String, private val minimum: Int, private val maximum: Int,
    private val step: Int, private val unit: String, private val read: () -> Int,
    private val write: (Int) -> Unit, presets: List<Int> = emptyList(), slider: Boolean = true
) : LinearLayout(context) {
    private val caption = TextView(context).apply { textSize = 18f; setTextColor(Color.WHITE) }
    val field = EditText(context).apply {
        id = View.generateViewId(); textSize = 18f; minHeight = dp(56)
        inputType = InputType.TYPE_CLASS_NUMBER or if (minimum < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
        imeOptions = EditorInfo.IME_ACTION_DONE; setSingleLine(true)
        contentDescription = "$label : valeur précise en $unit, de $minimum à $maximum"
    }
    private val errorView = TextView(context).apply {
        textSize = 17f; setTextColor(Color.rgb(255, 205, 110)); visibility = GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val seekBar: SeekBar? = if (slider) SeekBar(context).apply {
        id = View.generateViewId(); minimumHeight = dp(48); max = maximum - minimum
        contentDescription = label; caption.labelFor = id
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) applyValue(value + minimum, announce = false)
            }
        })
    } else null
    private val minus = button("− $step $unit") { applyValue((read() - step).coerceAtLeast(minimum)) }
    private val plus = button("+ $step $unit") { applyValue((read() + step).coerceAtMost(maximum)) }
    init {
        require(minimum <= maximum && step > 0)
        orientation = VERTICAL; setPadding(0, dp(8), 0, dp(8))
        addView(caption); seekBar?.let { addView(it) }
        addView(LinearLayout(context).apply {
            addView(field, LayoutParams(0, -2, 1f))
            addView(button("Valider") { commitField() })
        })
        field.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_UP } == true) {
                commitField(); true
            } else false
        }
        addView(errorView)
        addView(LinearLayout(context).apply {
            addView(minus, LayoutParams(0, -2, 1f)); addView(plus, LayoutParams(0, -2, 1f))
        })
        if (presets.isNotEmpty()) addView(LinearLayout(context).apply {
            orientation = VERTICAL
            presets.filter { it in minimum..maximum }.distinct().forEach { value ->
                addView(button("Préréglage : $value $unit") { applyValue(value) }.apply {
                    contentDescription = "$label : choisir le préréglage $value $unit"
                })
            }
        })
        refresh()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(value: String, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 17f; isAllCaps = false; minHeight = dp(56); setOnClickListener { action() }
    }
    fun commitField(): Boolean {
        val value = field.text.toString().trim().toIntOrNull()
        if (value == null || value !in minimum..maximum) {
            val error = "$label : indique un entier de $minimum à $maximum $unit. Aucune modification appliquée."
            errorView.text = error; errorView.visibility = VISIBLE; field.error = error
            return false
        }
        applyValue(value); return true
    }
    private fun applyValue(value: Int, announce: Boolean = true) {
        if (value !in minimum..maximum) return
        if (value != read()) write(value)
        errorView.visibility = GONE; field.error = null; refresh()
        seekBar?.contentDescription = caption.text
        if (announce) caption.announceForAccessibility(caption.text)
    }
    fun refresh() {
        val value = read().coerceIn(minimum, maximum)
        caption.text = "$label : $value $unit"
        if (field.text.toString() != value.toString()) field.setText(value.toString())
        seekBar?.let {
            if (it.progress != value - minimum) it.progress = value - minimum
        }
        minus.contentDescription = "$label : diminuer de $step $unit, valeur actuelle $value $unit"
        plus.contentDescription = "$label : augmenter de $step $unit, valeur actuelle $value $unit"
        minus.isEnabled = value > minimum; plus.isEnabled = value < maximum
    }
}
