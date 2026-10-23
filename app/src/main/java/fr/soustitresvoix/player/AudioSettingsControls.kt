package fr.soustitresvoix.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import fr.soustitresvoix.core.AudioCommand
import fr.soustitresvoix.core.AudioCommandDecision
import fr.soustitresvoix.core.AudioCommandPort
import fr.soustitresvoix.core.AudioCommandRejection
import fr.soustitresvoix.core.AudioCommandRequest
import fr.soustitresvoix.core.AudioNumericInput
import fr.soustitresvoix.core.AudioSettings
import kotlin.math.roundToInt

/** Step controls, exact decimal editors and observations; all writes go through the borrowed port. */
internal class AudioSettingsControls(
    private val activity: Activity,
    private val port: AudioCommandPort,
    private val generation: Long = port.snapshot().context.generation,
    includeLevels: Boolean = true,
    afterLevels: View? = null
) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val refreshers = mutableListOf<(AudioSettings) -> Unit>()
    private val state = label("").apply { id = R.id.audio_settings_state }
    private val result = label("Aucune modification demandée depuis ce panneau.").apply {
        id = R.id.audio_settings_result
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var restoringSpectral = false
    private val spectral = CheckBox(activity).apply {
        id = R.id.audio_spectral_toggle
        setText(R.string.audio_spectral_activation)
        textSize = 18f; minHeight = dp(56); setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, value ->
            if (!restoringSpectral) submit(AudioCommand.SetSpectralDucking(value))
        }
    }

    init {
        view.addView(label("Son du film — réglages et explications", heading = true))
        view.addView(label(AudioSettingsDescriptions.INTRO))
        if (includeLevels) {
            level("Volume du film", AudioSettingsDescriptions.MOVIE_VOLUME, true)
            level("Son du film pendant la voix", AudioSettingsDescriptions.DUCK_VOLUME, false)
        }
        if (afterLevels != null) view.addView(afterLevels)
        timing("Durée de baisse du film", AudioSettingsDescriptions.ATTACK, Timing.ATTACK, 40, 160)
        timing("Durée de remontée du film", AudioSettingsDescriptions.RELEASE, Timing.RELEASE, 100, 500)
        timing("Attente avant remontée entre deux répliques", AudioSettingsDescriptions.HOLD, Timing.HOLD, 50, 350)
        view.addView(label(AudioSettingsDescriptions.SPECTRAL)); view.addView(spectral)
        view.addView(button("Rétablir le filtre spectral désactivé par défaut", R.id.audio_spectral_reset) {
            submit(AudioCommand.SetSpectralDucking(false))
        })
        view.addView(label(AudioSettingsDescriptions.OBSERVATION))
        view.addView(state)
        view.addView(button("Actualiser l’état du son du film", R.id.audio_settings_refresh) { refresh() })
        view.addView(result)
        refresh()
    }

    private fun level(title: String, description: String, normal: Boolean) {
        view.addView(label(description))
        val caption = label("", heading = true).apply {
            id = if (normal) R.id.audio_movie_value else R.id.audio_duck_value
        }; view.addView(caption)
        fun value(settings: AudioSettings) = if (normal) settings.movieVolume else settings.duckVolume
        fun change(value: Float): String? {
            val settings = port.snapshot().context.requested
            return submit(if (normal) AudioCommand.SetLevels(value, settings.duckVolume)
                else AudioCommand.SetLevels(settings.movieVolume, value))
        }
        val row = LinearLayout(activity)
        val controls = listOf(-5, 5).map { delta ->
            button(if (delta < 0) "−5 points" else "+5 points") {
                val current = value(port.snapshot().context.requested)
                change(((current * 100).roundToInt() + delta).coerceIn(0, 100) / 100f)
            }.apply {
                contentDescription = "$title : ${if (delta < 0) "diminuer" else "augmenter"} de cinq points de pourcentage"
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        view.addView(row)
        view.addView(button(activity.getString(R.string.audio_numeric_title, title),
            if (normal) R.id.audio_movie_edit else R.id.audio_duck_edit) {
            AudioNumberDialog.show(activity, view, title, AudioNumericInput.PERCENT,
                AudioNumericInput.percent(value(port.snapshot().context.requested)), description) {
                change(it.movePointLeft(2).toFloat())
            }
        })
        view.addView(button(if (normal) "Rétablir le volume du film à 100 %" else "Rétablir le son pendant la voix à 25 %",
            if (normal) R.id.audio_movie_reset else R.id.audio_duck_reset) { change(if (normal) 1f else .25f) })
        refreshers += { settings ->
            val level = value(settings)
            caption.text = activity.getString(R.string.audio_precise_level_caption, title, AudioNumericInput.percent(level))
            // Gestures can leave a fractional percent that rounds to a bound.
            // Keep the last step available until the actual level reaches it.
            controls[0].isEnabled = level > 0f
            controls[1].isEnabled = level < 1f
        }
    }

    private enum class Timing(val editId: Int) {
        ATTACK(R.id.audio_attack_edit), RELEASE(R.id.audio_release_edit), HOLD(R.id.audio_hold_edit)
    }

    private fun timing(title: String, description: String, timing: Timing, step: Int, default: Int) {
        view.addView(label(description))
        val caption = label("", heading = true); view.addView(caption)
        fun value(settings: AudioSettings) = when (timing) {
            Timing.ATTACK -> settings.attackMs
            Timing.RELEASE -> settings.releaseMs
            Timing.HOLD -> settings.holdMs
        }
        fun change(value: Int): String? {
            val current = port.snapshot().context.requested
            return submit(AudioCommand.SetDuckingTiming(
                if (timing == Timing.ATTACK) value else current.attackMs,
                if (timing == Timing.RELEASE) value else current.releaseMs,
                if (timing == Timing.HOLD) value else current.holdMs))
        }
        val row = LinearLayout(activity)
        val controls = listOf(-step, step).map { delta ->
            button("${if (delta < 0) "−" else "+"} $step ms") {
                change((value(port.snapshot().context.requested) + delta).coerceIn(0, 2000))
            }.apply {
                contentDescription = "$title : ${if (delta < 0) "diminuer" else "augmenter"} de $step millisecondes"
                row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        view.addView(row)
        view.addView(button(activity.getString(R.string.audio_numeric_title, title), timing.editId) {
            AudioNumberDialog.show(activity, view, title, AudioNumericInput.MILLISECONDS,
                value(port.snapshot().context.requested).toString(), description) { change(it.intValueExact()) }
        })
        view.addView(button("Rétablir $title : $default ms") { change(default) })
        refreshers += { settings ->
            val current = value(settings)
            caption.text = activity.getString(R.string.audio_timing_caption, title, current)
            controls[0].isEnabled = current > 0
            controls[1].isEnabled = current < 2000
        }
    }

    /** Null means accepted; a readable refusal keeps a numeric editor open. */
    private fun submit(command: AudioCommand): String? {
        if (activity.isFinishing || activity.isDestroyed) return AudioCommandText.rejection(AudioCommandRejection.CLOSED)
        val decision = port.submit(AudioCommandRequest(generation, command))
        result.text = when (decision) {
            is AudioCommandDecision.Rejected -> AudioCommandText.rejection(decision.reason)
            is AudioCommandDecision.Accepted -> if (!decision.changed) "Valeur déjà demandée : aucun changement."
                else when (command) {
                    is AudioCommand.SetLevels -> "Niveaux enregistrés ; cible appliquée immédiatement au volume du lecteur."
                    is AudioCommand.SetDuckingTiming -> "Durées enregistrées pour les prochaines transitions. Le fondu déjà planifié conserve ses durées."
                    is AudioCommand.SetSpectralDucking -> "Préférence enregistrée et transmise au processeur du film ; résultat acoustique non mesuré."
                    is AudioCommand.SetDsp -> "Réglages DSP enregistrés ; application aux prochains échantillons compatibles."
                }
        }
        refresh()
        if (decision is AudioCommandDecision.Rejected) result.post {
            if (view.isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed)
                result.requestRectangleOnScreen(Rect(0, 0, result.width, result.height), true)
        }
        return if (decision is AudioCommandDecision.Rejected) AudioCommandText.rejection(decision.reason) else null
    }

    /** Refreshing never issues a command, including when updating the checkbox. */
    fun refresh() {
        val snapshot = port.snapshot()
        val context = snapshot.context
        refreshers.forEach { it(context.requested) }
        restoringSpectral = true
        try { spectral.isChecked = context.requested.spectralDucking } finally { restoringSpectral = false }
        state.text = when {
            context.closed -> AudioCommandText.rejection(AudioCommandRejection.CLOSED)
            context.generation != generation -> AudioCommandText.rejection(AudioCommandRejection.STALE_MEDIA)
            else -> "État à la dernière actualisation : volume configuré dans le lecteur ${percent(snapshot.configuredVolume)}. " +
                "Filtre activé dans le processeur : ${yesNo(snapshot.spectralEnabled)} ; signal de parole : ${yesNo(snapshot.speaking)}. " +
                "Échantillons individuels traités, tous canaux cumulés depuis le début de session : ${snapshot.processedSamples ?: "non établi"}."
        }
    }

    private fun percent(value: Float?) = value?.let { "${AudioNumericInput.percent(it)} %" } ?: "non établi"
    private fun yesNo(value: Boolean?) = when (value) { true -> "oui"; false -> "non"; null -> "non établi" }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(8))
        if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private fun button(value: String, viewId: Int = View.NO_ID, action: () -> Unit) = Button(activity).apply {
        id = viewId; text = value; textSize = 17f; isAllCaps = false; minHeight = dp(56)
        setOnClickListener { if (!activity.isFinishing && !activity.isDestroyed) action() }
    }
}

internal object AudioCommandText {
    fun rejection(reason: AudioCommandRejection): String = when (reason) {
        AudioCommandRejection.CLOSED -> "Modification refusée : la session est fermée. Rouvre le lecteur et ses réglages."
        AudioCommandRejection.STALE_MEDIA -> "Modification refusée : une autre ouverture de vidéo a eu lieu. Ferme les fenêtres de réglages jusqu’au lecteur, puis rouvre les réglages audio. Les réglages audio enregistrés sont conservés."
        AudioCommandRejection.NON_FINITE_VALUE -> "Modification refusée : les volumes doivent être des nombres finis entre 0 et 100 %. Aucune valeur modifiée."
        AudioCommandRejection.OUT_OF_RANGE -> "Modification refusée : volumes de 0 à 100 % ; durées de 0 à 2 000 ms. Aucune valeur modifiée."
        AudioCommandRejection.CAPABILITY_UNAVAILABLE -> "Activation refusée : ce composant ne permet pas l’atténuation spectrale. Aucune valeur modifiée."
        AudioCommandRejection.CAPABILITY_UNKNOWN -> "Activation refusée : la disponibilité de l’atténuation spectrale n’est pas déclarée par ce composant. Aucune valeur modifiée."
        AudioCommandRejection.DSP_CAPABILITY_UNAVAILABLE -> "Activation refusée : le traitement audio avancé est indisponible avec ce composant. Aucune valeur modifiée."
        AudioCommandRejection.DSP_CAPABILITY_UNKNOWN -> "Activation refusée : la disponibilité du traitement audio avancé n’est pas déclarée par ce composant. Aucune valeur modifiée."
    }
}
