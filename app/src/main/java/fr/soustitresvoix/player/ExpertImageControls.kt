package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.method.TextKeyListener
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import fr.soustitresvoix.core.CapabilityStatus
import fr.soustitresvoix.core.ImageCommand
import fr.soustitresvoix.core.ImageCommandDecision
import fr.soustitresvoix.core.ImageCommandDisposition
import fr.soustitresvoix.core.ImageCommandPort
import fr.soustitresvoix.core.ImageCommandRejection
import fr.soustitresvoix.core.ImageCommandRequest
import fr.soustitresvoix.core.ImageSettings
import java.math.BigDecimal

/** Presentation only: no player, narration, preferences or effect scheduler.
 * The owner refreshes this view through its existing UI loop and owns all dialogs.
 * A nested editor retains this panel's original generation, including after reopen.
 */
internal class ExpertImageControls(
    private val activity: Activity,
    private val port: ImageCommandPort,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog?,
    private val alive: () -> Boolean
) {
    // This controller is composed with its dependencies, never inflated from XML.
    val view = LinearLayout(activity).apply {
        id = R.id.image_expert_controls
        orientation = LinearLayout.VERTICAL
    }
    private val generation = port.snapshot().context.generation
    private val requested = label("", R.id.image_adjustment_requested)
    private val submitted = label("", R.id.image_adjustment_submitted)
    private val status = label("", R.id.image_adjustment_status)
    private val result = label("Aucune modification demandée depuis ce panneau.", R.id.image_adjustment_result)
    private var restoringComparison = false
    private val comparison = CheckBox(activity).apply {
        id = R.id.image_original_comparison
        text = activity.getString(R.string.image_original_comparison_label)
        textSize = 17f; minHeight = dp(56); setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, checked ->
            if (!restoringComparison && alive()) submit(ImageCommand.SetOriginalComparison(checked))
        }
    }
    private data class Controls(val value: TextView, val decrease: Button, val increase: Button)
    private val fields = mutableMapOf<Correction, Controls>()

    private enum class Correction(
        val title: String, val minimum: String, val maximum: String,
        val valueId: Int, val decreaseId: Int, val increaseId: Int, val editId: Int, val resetId: Int
    ) {
        CONTRAST("Contraste", "-0.30", "0.30", R.id.image_contrast_value,
            R.id.image_contrast_decrease, R.id.image_contrast_increase,
            R.id.image_contrast_edit, R.id.image_contrast_reset),
        BRIGHTNESS("Luminosité de l’image", "-0.20", "0.20", R.id.image_brightness_value,
            R.id.image_brightness_decrease, R.id.image_brightness_increase,
            R.id.image_brightness_edit, R.id.image_brightness_reset);

        fun value(settings: ImageSettings) = when (this) {
            CONTRAST -> settings.contrast
            BRIGHTNESS -> settings.imageBrightness
        }
        fun command(settings: ImageSettings, value: Float) = when (this) {
            CONTRAST -> ImageCommand.SetAdjustments(value, settings.imageBrightness)
            BRIGHTNESS -> ImageCommand.SetAdjustments(settings.contrast, value)
        }
        fun contains(value: BigDecimal) = value >= BigDecimal(minimum) && value <= BigDecimal(maximum)
        fun bounds() = "${minimum.replace('.', ',')} à +${maximum.replace('.', ',')}"
        val subject get() = if (this == CONTRAST) "le contraste" else "la luminosité de l’image"
    }

    init {
        addView(heading("Corrections d’image — mode expert"))
        addView(label(ImageSettingsDescriptions.INTRO))
        addView(requested); addView(submitted); addView(status)
        addView(label(ImageSettingsDescriptions.OBSERVATION))
        addView(button("Actualiser l’état des corrections", R.id.image_adjustment_refresh) { refresh() })
        addView(result)
        for (field in Correction.entries) {
            addView(heading(field.title))
            addView(label(if (field == Correction.CONTRAST) ImageSettingsDescriptions.CONTRAST
                else ImageSettingsDescriptions.IMAGE_BRIGHTNESS))
            val value = label("", field.valueId)
            addView(value)
            val decrease = button("Diminuer ${field.subject} de 0,01", field.decreaseId) { changeBy(field, -1) }
            val increase = button("Augmenter ${field.subject} de 0,01", field.increaseId) { changeBy(field, 1) }
            addView(decrease); addView(increase)
            addView(button("Saisir ${field.subject}", field.editId) { edit(field) })
            addView(button("Remettre ${field.subject} à zéro", field.resetId) { change(field, 0f) })
            fields[field] = Controls(value, decrease, increase)
        }
        addView(heading("Raccourcis et remise à zéro"))
        addView(label("Contraste léger : demande +0,12 si le contraste est nul ; sinon revient à zéro. " +
            "Éclaircir légèrement : demande +0,06 si la luminosité de l’image est nulle ; sinon revient à zéro. " +
            "Chaque raccourci conserve l’autre correction et reste soumis aux protections indiquées ci-dessus."))
        addView(button("Contraste léger + / original (SDR seulement)") {
            val settings = port.snapshot().context.requested
            submit(Correction.CONTRAST.command(settings, if (settings.contrast == 0f) .12f else 0f))
        })
        addView(button("Éclaircir légèrement / original (SDR seulement)") {
            val settings = port.snapshot().context.requested
            submit(Correction.BRIGHTNESS.command(settings, if (settings.imageBrightness == 0f) .06f else 0f))
        })
        addView(label(ImageSettingsDescriptions.CORRECTION_RESET))
        addView(button("Remettre les deux corrections à zéro", R.id.image_adjustments_reset) {
            submit(ImageCommand.SetAdjustments(0f, 0f))
        })
        addView(heading("Comparaison avec l’original"))
        addView(label(ImageSettingsDescriptions.ORIGINAL_COMPARISON))
        addView(comparison)
        refresh()
    }

    /** Read-only; never overwrites the text of an open numeric editor. */
    fun refresh() {
        if (!alive()) return
        val snapshot = port.snapshot()
        val context = snapshot.context
        val settings = context.requested
        update(requested, "Valeurs demandées : contraste ${format(settings.contrast)} ; " +
            "luminosité de l’image ${format(settings.imageBrightness)}. " +
            "Comparaison ${if (settings.originalComparison) "activée" else "désactivée"}.")
        val last = snapshot.lastSubmitted?.takeIf { it.generation == context.generation }
        update(submitted, last?.let {
            "Dernier plan transmis : contraste ${format(it.contrast)} ; luminosité de l’image ${format(it.imageBrightness)}."
        } ?: "Dernier plan transmis : aucune transmission observée pour cette ouverture de vidéo.")
        update(status, when {
            context.closed -> rejection(ImageCommandRejection.CLOSED)
            context.generation != generation -> rejection(ImageCommandRejection.STALE_MEDIA)
            context.availability == CapabilityStatus.UNAVAILABLE -> rejection(ImageCommandRejection.CAPABILITY_UNAVAILABLE)
            context.availability == CapabilityStatus.UNKNOWN -> rejection(ImageCommandRejection.CAPABILITY_UNKNOWN)
            context.hdrProtected -> "Protection HDR active : les corrections sont neutralisées dans le prochain plan. " +
                "Modifier ou remettre à zéro le contraste et la luminosité de l’image est refusé. La comparaison ne lève pas cette protection."
            settings.originalComparison -> "Comparaison active : valeurs demandées conservées, corrections masquées dans le plan de comparaison. " +
                "Décoche la comparaison pour les retrouver, sous les protections courantes."
            else -> "Corrections disponibles sous les protections du lecteur. " +
                "L’absence de protection HDR active ne confirme pas à elle seule que la vidéo est SDR."
        })
        for ((field, controls) in fields) {
            val current = field.value(settings)
            update(controls.value, "${field.title} demandé : ${format(current)}. Neutre : 0,00. Limites : ${field.bounds()}.")
            controls.decrease.isEnabled = stepped(field, current, -1) != null
            controls.increase.isEnabled = stepped(field, current, 1) != null
        }
        restoringComparison = true
        try { comparison.isChecked = settings.originalComparison } finally { restoringComparison = false }
    }

    private fun change(field: Correction, value: Float): Boolean {
        val current = port.snapshot().context.requested
        return submit(field.command(current, value))
    }

    private fun changeBy(field: Correction, direction: Int) {
        val current = port.snapshot().context.requested
        val value = stepped(field, field.value(current), direction) ?: return
        submit(field.command(current, value))
    }

    private fun stepped(field: Correction, current: Float, direction: Int): Float? {
        // Decimal steps avoid 0.01 + 0.01 ... drifting beyond an inclusive bound.
        // A step outside the range is unavailable, never silently clamped.
        val next = BigDecimal(current.toString()).add(BigDecimal("0.01").multiply(BigDecimal(direction)))
        return next.takeIf(field::contains)?.toFloat()
    }

    private fun submit(command: ImageCommand): Boolean {
        if (!alive()) return false
        val decision = port.submit(ImageCommandRequest(generation, command))
        when (decision) {
            is ImageCommandDecision.Rejected -> {
                val reason = rejection(decision.reason)
                update(result, "Dernière demande refusée. $reason")
                refresh()
                showDialog(AlertDialog.Builder(activity).setTitle("Réglage d’image refusé")
                    .setMessage(reason).setPositiveButton("Fermer", null))
                return false
            }
            is ImageCommandDecision.Accepted -> {
                update(result, if (!decision.changed) "Valeurs déjà demandées : aucun nouveau changement."
                    else when (decision.disposition) {
                        ImageCommandDisposition.ELIGIBLE_FOR_SUBMISSION -> "Demande acceptée. Consulte le dernier plan transmis pour suivre son envoi."
                        ImageCommandDisposition.MASKED_BY_ORIGINAL_COMPARISON -> "Demande conservée ; la comparaison masque les corrections."
                        ImageCommandDisposition.MASKED_BY_HDR -> "Comparaison modifiée ; la protection HDR reste active."
                    })
                refresh()
                return true
            }
        }
    }

    private fun edit(field: Correction) {
        val input = EditText(activity).apply {
            id = R.id.image_adjustment_input
            textSize = 20f; minHeight = dp(56); setTextColor(Color.WHITE)
            // Keep the original text for validation (including pasted invalid
            // text). A locale-dependent DigitsKeyListener can silently remove
            // commas, signs or a percent symbol before the validator sees them.
            keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
            setSingleLine(true)
            setText(format(field.value(port.snapshot().context.requested)))
            selectAll()
        }
        val error = label("", R.id.image_adjustment_error).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(label("${field.title} : de ${field.bounds()}. Zéro est neutre.").apply { labelFor = input.id })
            addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(error)
            addView(label("Utilise une virgule ou un point, avec un signe moins pour une valeur négative. " +
                "Exemple : 0,12. Au maximum 24 caractères. Pas de pourcentage ni de notation scientifique. " +
                "Appliquer valide uniquement ce paramètre ; Annuler conserve la valeur actuelle. " +
                "La valeur retenue est affichée après validation."))
        }
        val dialog = showDialog(AlertDialog.Builder(activity).setTitle("Saisir ${field.subject}")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("Appliquer", null).setNegativeButton("Annuler", null)) ?: return
        // An invalid value must keep the editor and its error visible.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!alive()) return@setOnClickListener
            val text = input.text.toString().trim().replace('−', '-')
            val numeric = if (text.length <= 24 && text.matches(Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)")))
                text.replace(',', '.').toBigDecimalOrNull() else null
            val problem = when {
                numeric == null -> "Valeur refusée : saisis un nombre décimal de 24 caractères au maximum, avec une virgule ou un point."
                !field.contains(numeric) -> "Valeur refusée : ${field.subject} doit rester dans la plage de ${field.bounds()}. Aucune valeur n’a été modifiée."
                else -> null
            }
            if (problem != null) {
                update(error, problem); error.visibility = View.VISIBLE
                update(result, problem)
                error.post {
                    if (dialog.isShowing) error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height), true)
                }
            } else if (change(field, requireNotNull(numeric).toFloat())) {
                dialog.dismiss()
            }
        }
    }

    private fun addView(child: View) { view.addView(child) }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, viewId: Int = View.NO_ID) = TextView(activity).apply {
        id = viewId; text = value; textSize = 17f; setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private fun heading(value: String) = label(value).apply {
        textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private fun button(value: String, viewId: Int = View.NO_ID, action: () -> Unit) = Button(activity).apply {
        id = viewId; text = value; textSize = 16f; isAllCaps = false; minHeight = dp(52)
        setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
        setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.rgb(45, 45, 45))
        setOnClickListener { if (alive()) action() }
    }
    private fun update(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
    private fun format(value: Float): String {
        val decimal = BigDecimal(value.toString()).stripTrailingZeros()
        val text = decimal.setScale(maxOf(2, decimal.scale())).toPlainString().replace('.', ',')
        return if (value > 0f) "+$text" else text
    }
    private fun rejection(reason: ImageCommandRejection): String = when (reason) {
        ImageCommandRejection.CLOSED -> "Lecture fermée : réglage indisponible"
        ImageCommandRejection.STALE_MEDIA -> "La vidéo a changé : rouvre les réglages d’image"
        ImageCommandRejection.NON_FINITE_VALUE -> "Réglage refusé : valeur numérique invalide"
        ImageCommandRejection.OUT_OF_RANGE -> "Réglage refusé : valeur hors des limites autorisées"
        ImageCommandRejection.CAPABILITY_UNAVAILABLE -> "Traitement d’image indisponible avec ce moteur"
        ImageCommandRejection.CAPABILITY_UNKNOWN -> "Disponibilité du traitement d’image non confirmée"
        ImageCommandRejection.HDR_PROTECTED -> "HDR : traitement désactivé"
    }
}
