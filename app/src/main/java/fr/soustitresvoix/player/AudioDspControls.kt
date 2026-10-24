package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandPort
import fr.soustitresvoix.core.AudioCommandRejection
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioNumberSpec
import fr.soustitresvoix.core.AudioNumericInput
import java.math.BigDecimal

/** Presentation-only film DSP panels. Every nested panel keeps the ticket of the
 * original producer, and every edit reads companion fields again at submission. */
internal object AudioDspControls {
    fun openButton(activity: Activity, owner: View, port: AudioCommandPort, generation: Long): Button =
        Button(activity).apply {
            id = R.id.audio_dsp_open
            setText(R.string.audio_dsp_open)
            textSize = 17f; isAllCaps = false
            minHeight = (56 * activity.resources.displayMetrics.density).toInt()
            setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            setOnClickListener { show(activity, owner, port, generation) }
        }

    fun show(activity: Activity, owner: View, port: AudioCommandPort, generation: Long): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed || !owner.isAttachedToWindow) return null
        return Panel(activity, port, generation, band = null).show(owner)
    }

    private class Panel(
        private val activity: Activity,
        private val port: AudioCommandPort,
        private val generation: Long,
        private val band: Int?
    ) {
        private val view = LinearLayout(activity).apply {
            id = if (band == null) R.id.audio_dsp_controls else R.id.audio_dsp_band_controls
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        private val refreshers = mutableListOf<(AudioDspSettings) -> Unit>()
        private var restoring = false
        private val result = label("Aucune modification demandée depuis ce panneau.").apply {
            id = R.id.audio_dsp_result
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val state = label("").apply { id = R.id.audio_dsp_state }
        private val defaults = AudioDspSettings()

        init {
            if (band == null) mainControls() else bandControls(band)
            view.addView(result)
            view.addView(label(AudioDspDescriptions.OBSERVATION))
            view.addView(button(activity.getString(R.string.audio_dsp_refresh), R.id.audio_dsp_refresh) { refresh() })
            view.addView(state)
            refresh()
        }

        fun show(owner: View, onDismiss: () -> Unit = {}): AlertDialog? = AudioDialogOwner.show(activity, owner,
            AlertDialog.Builder(activity)
                .setTitle(if (band == null) activity.getString(R.string.audio_dsp_open)
                    else activity.getString(R.string.audio_dsp_band_title, band + 1))
                .setView(ScrollView(activity).apply { addView(view) })
                .setPositiveButton(R.string.audio_dsp_close, null), onDismiss = onDismiss)

        private fun mainControls() {
            view.addView(label(AudioDspDescriptions.INTRO))
            toggle(R.string.audio_dsp_global_toggle, R.id.audio_dsp_enabled, { it.enabled }) { s, v -> s.copy(enabled = v) }
            view.addView(label("Égaliseur paramétrique — cinq bandes indépendantes", heading = true))
            val ids = intArrayOf(R.id.audio_dsp_band_1, R.id.audio_dsp_band_2, R.id.audio_dsp_band_3,
                R.id.audio_dsp_band_4, R.id.audio_dsp_band_5)
            for (index in 0 until 5) {
                val control = button("", ids[index]) {
                    Panel(activity, port, generation, index).show(view) {
                        if (alive()) refresh()
                    }
                }
                view.addView(control)
                refreshers += { settings ->
                    val value = settings.band(index)
                    control.text = activity.getString(R.string.audio_dsp_band_button, index + 1,
                        number(value.frequencyHz), number(value.gainDb), number(value.q),
                        if (value.enabled) "sélectionnée" else "désactivée")
                }
            }
            number("Préampli du film", AudioNumberSpec("-24", "12", "dB"),
                R.id.audio_dsp_preamp_edit, R.id.audio_dsp_preamp_reset, "0", AudioDspDescriptions.PREAMP,
                { BigDecimal.valueOf(it.preampDb) }) { s, v -> s.copy(preampDb = v.toDouble()) }
            number("Balance stéréo", AudioNumberSpec("-100", "100", "%"),
                R.id.audio_dsp_balance_edit, R.id.audio_dsp_balance_reset, "0", AudioDspDescriptions.BALANCE,
                { BigDecimal.valueOf(it.balance).movePointRight(2) }) { s, v -> s.copy(balance = v.movePointLeft(2).toDouble()) }
            toggle(R.string.audio_dsp_mono_toggle, R.id.audio_dsp_mono, { it.mono }) { s, v -> s.copy(mono = v) }
            view.addView(label(AudioDspDescriptions.MONO))
            toggle(R.string.audio_dsp_limiter_toggle, R.id.audio_dsp_limiter_enabled, { it.limiterEnabled }) { s, v -> s.copy(limiterEnabled = v) }
            view.addView(label(AudioDspDescriptions.LIMITER))
            number("Plafond du limiteur", AudioNumberSpec("-12", "0", "dBFS"),
                R.id.audio_dsp_ceiling_edit, R.id.audio_dsp_ceiling_reset, "-1", AudioDspDescriptions.CEILING,
                { BigDecimal.valueOf(it.limiterCeilingDb) }) { s, v -> s.copy(limiterCeilingDb = v.toDouble()) }
            number("Relâchement du limiteur", AudioNumberSpec("10", "2000", "ms", integerOnly = true),
                R.id.audio_dsp_release_edit, R.id.audio_dsp_release_reset, "100", AudioDspDescriptions.RELEASE,
                { BigDecimal.valueOf(it.limiterReleaseMs.toLong()) }) { s, v -> s.copy(limiterReleaseMs = v.intValueExact()) }
            view.addView(label(AudioDspDescriptions.TRANSITIONS))
            view.addView(label("Rétablir tous les traitements avancés désactive leur interrupteur général, rétablit préampli et balance à zéro, " +
                "désactive le mono et les cinq bandes, et présélectionne le limiteur à −1 dBFS avec 100 ms de relâchement. " +
                "Les volumes du film et de la voix, les fondus de narration et l’atténuation spectrale conservent leurs réglages."))
            view.addView(button(activity.getString(R.string.audio_dsp_reset), R.id.audio_dsp_reset) { submit(defaults) })
        }

        private fun bandControls(index: Int) {
            val original = defaults.band(index)
            view.addView(label(AudioDspDescriptions.BAND))
            toggle(R.string.audio_dsp_band_toggle, R.id.audio_dsp_band_enabled, { it.band(index).enabled }) { s, v ->
                s.withBand(index, s.band(index).copy(enabled = v))
            }
            number("Fréquence centrale", AudioNumberSpec("20", "20000", "Hz"),
                R.id.audio_dsp_frequency_edit, R.id.audio_dsp_frequency_reset, original.frequencyHz.toString(), AudioDspDescriptions.FREQUENCY,
                { BigDecimal.valueOf(it.band(index).frequencyHz) }) { s, v -> s.withBand(index, s.band(index).copy(frequencyHz = v.toDouble())) }
            number("Gain de la bande", AudioNumberSpec("-12", "12", "dB"),
                R.id.audio_dsp_gain_edit, R.id.audio_dsp_gain_reset, "0", AudioDspDescriptions.GAIN,
                { BigDecimal.valueOf(it.band(index).gainDb) }) { s, v -> s.withBand(index, s.band(index).copy(gainDb = v.toDouble())) }
            number("Facteur Q", AudioNumberSpec("0.1", "10", "sans unité"),
                R.id.audio_dsp_q_edit, R.id.audio_dsp_q_reset, "1", AudioDspDescriptions.Q,
                { BigDecimal.valueOf(it.band(index).q) }) { s, v -> s.withBand(index, s.band(index).copy(q = v.toDouble())) }
            view.addView(button(activity.getString(R.string.audio_dsp_band_reset), R.id.audio_dsp_band_reset) {
                val latest = port.snapshot().context.requested.dsp
                submit(latest.withBand(index, original))
            })
        }

        private fun number(
            title: String, spec: AudioNumberSpec, editId: Int, resetId: Int, default: String, description: String,
            read: (AudioDspSettings) -> BigDecimal, change: (AudioDspSettings, BigDecimal) -> AudioDspSettings
        ) {
            val caption = label("", heading = true)
            view.addView(caption)
            fun edit(value: BigDecimal): String? = submit(change(port.snapshot().context.requested.dsp, value))
            view.addView(button(activity.getString(R.string.audio_numeric_title, title), editId) {
                AudioNumberDialog.show(activity, view, title, spec,
                    AudioNumericInput.format(read(port.snapshot().context.requested.dsp)), description, ::alive, ::edit)
            })
            view.addView(button(activity.getString(R.string.audio_dsp_reset_number, title,
                AudioNumericInput.format(BigDecimal(default)), spec.unit), resetId) { edit(BigDecimal(default)) })
            view.addView(label(description))
            refreshers += { settings -> caption.text = activity.getString(R.string.audio_dsp_caption,
                title, AudioNumericInput.format(read(settings)), spec.unit) }
        }

        private fun toggle(labelId: Int, viewId: Int, read: (AudioDspSettings) -> Boolean,
            change: (AudioDspSettings, Boolean) -> AudioDspSettings) {
            val box = CheckBox(activity).apply {
                id = viewId; setText(labelId)
                textSize = 18f; minHeight = dp(56); setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, value ->
                    if (!restoring && alive()) submit(change(port.snapshot().context.requested.dsp, value))
                }
            }
            view.addView(box)
            refreshers += { box.isChecked = read(it) }
        }

        private fun submit(settings: AudioDspSettings): String? {
            if (!alive()) return AudioDspDescriptions.rejection(AudioCommandRejection.CLOSED)
            val decision = port.submit(AudioCommandRequest(generation, AudioCommand.SetDsp(settings)))
            val problem = (decision as? AudioCommandDecision.Rejected)?.let { AudioDspDescriptions.rejection(it.reason) }
            result.text = problem ?: if ((decision as AudioCommandDecision.Accepted).changed)
                "Réglages enregistrés. Leur traitement dépend du prochain bloc PCM compatible ; consulte les révisions et la transition ci-dessous."
                else "Valeurs déjà demandées : aucun nouveau changement."
            refresh()
            if (problem != null) result.post {
                if (alive()) result.requestRectangleOnScreen(Rect(0, 0, result.width, result.height), true)
            }
            return problem
        }

        private fun refresh() {
            if (activity.isFinishing || activity.isDestroyed) return
            val snapshot = port.snapshot()
            restoring = true
            try { refreshers.forEach { it(snapshot.context.requested.dsp) } }
            finally { restoring = false }
            state.text = AudioDspDescriptions.state(snapshot, generation)
        }

        private fun alive() = !activity.isFinishing && !activity.isDestroyed && view.isAttachedToWindow
        private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        private fun number(value: Double) = AudioNumericInput.format(BigDecimal.valueOf(value))
        private fun label(value: String, heading: Boolean = false) = TextView(activity).apply {
            text = value; textSize = 18f; setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        private fun button(value: String, viewId: Int, action: () -> Unit) = Button(activity).apply {
            id = viewId; text = value; textSize = 17f; isAllCaps = false; minHeight = dp(56)
            setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
            setOnClickListener { if (alive()) action() }
        }
    }
}
