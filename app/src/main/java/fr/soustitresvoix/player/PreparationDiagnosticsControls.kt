package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Presentation only. Reading files, workers and cancellation belong to the owner.
 * Opening, updating or dismissing this panel never starts a probe or playback. */
internal class PreparationDiagnosticsControls(
    private val context: Context,
    private val actions: Actions
) {
    data class Actions(
        val refreshDevice: () -> Unit,
        val runDemo: () -> Unit,
        val chooseVideo: () -> Unit,
        val chooseModel: () -> Unit,
        val runModel: () -> Unit,
        val cancel: () -> Unit,
        val close: () -> Unit,
        val clearModel: () -> Unit = {},
        val copyReport: () -> Unit = {},
        val runBuiltinModel: () -> Unit = {}
    )

    private var panel: AlertDialog? = null
    private var generation = 0L
    private var reportValue = "Aucun essai lancé."
    private var busyValue = false
    private var modelReadyValue = false
    private var stageValue = "Prêt. Choisis explicitement une mesure ou un essai."
    private var reportView: TextView? = null
    private var statusView: TextView? = null
    private val taskButtons = mutableListOf<Button>()
    private var modelButton: Button? = null
    private var clearModelButton: Button? = null
    private var cancelButton: Button? = null

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun text(value: String) = TextView(context).apply {
        text = value
        textSize = 18f
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun action(label: String, ticket: Long, callback: () -> Unit) = Button(context).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setSingleLine(false)
        maxLines = Int.MAX_VALUE
        ellipsize = null
        minHeight = dp(56)
        minimumHeight = dp(56)
        setOnClickListener {
            if (isEnabled && ticket == generation && panel?.isShowing == true) callback()
        }
    }

    fun show(): AlertDialog {
        dismiss()
        val ticket = ++generation
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        column.addView(text("Diagnostic local — étape 1").apply {
            textSize = 22f
            ViewCompat.setAccessibilityHeading(this, true)
        })
        column.addView(text(
            "Les mesures Android déclarées et les essais réellement exécutés sont distingués. " +
                "Le modèle local est essayé sur une petite phrase synthétique, sur le CPU uniquement. " +
                "Le GPU et le NPU ne sont pas exécutés dans cette étape ; leur présence ne prouve pas une accélération. " +
                "Un essai réussi ne valide pas la qualité des traductions d’un film."
        ))
        column.addView(text(
            "L’OCR et la traduction restent deux fonctions indépendantes. Ce diagnostic n’active pas encore " +
                "l’OCR ni la traduction locale des sous-titres du film. Les essais restent sur le téléphone : " +
                "aucune vidéo et aucun modèle ne sont téléversés. Le rapport ne doit pas contenir le nom ni " +
                "l’adresse d’un fichier personnel."
        ))
        statusView = text(stageValue).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { column.addView(it) }
        reportView = text(reportValue).apply {
            setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }.also { column.addView(it) }

        listOf(
            "Actualiser les mesures du téléphone" to actions.refreshDevice,
            "Tester la démonstration vidéo intégrée" to actions.runDemo,
            "Tester le moteur CPU intégré" to actions.runBuiltinModel,
            "Choisir une vidéo pour un essai de décodage" to actions.chooseVideo,
            "Choisir un modèle GGUF local" to actions.chooseModel
        ).forEach { (label, callback) ->
            action(label, ticket, callback).also {
                taskButtons += it
                column.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        }
        modelButton = action("Tester le modèle sur une phrase synthétique", ticket, actions.runModel).also {
            column.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        clearModelButton = action("Supprimer la copie du modèle importé", ticket, actions.clearModel).also {
            column.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(action("Copier le rapport de diagnostic", ticket, actions.copyReport),
            LinearLayout.LayoutParams(-1, -2))
        column.addView(text(
            "L’import du modèle crée une copie privée dans l’application. Un fichier de 9 Go occupe donc " +
                "aussi cette place pour sa copie. Un import interrompu peut laisser une copie incomplète. " +
                "Supprimer la copie nettoie aussi cet import incomplet, même sans modèle prêt. Le remplacement " +
                "ou la suppression de la copie privée ne modifie jamais le fichier original. Copier le rapport " +
                "est une action explicite ; aucun partage n’est déclenché automatiquement."
        ))
        column.addView(text(
            "Tester le moteur CPU intégré utilise uniquement un modèle miniature synthétique fourni avec " +
                "l’application. Ce contrôle vérifie le chargement et l’exécution du moteur ; il ne mesure " +
                "ni la qualité d’un modèle de traduction ni les performances d’un grand modèle importé."
        ))
        column.addView(text(
            "Le chargement d’un grand modèle peut échouer si la mémoire disponible est insuffisante. " +
                "Annuler demande l’arrêt de l’essai ; Fermer annule aussi le travail de ce diagnostic. " +
                "Aucune de ces commandes ne lance la lecture ni une voix."
        ))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (context.resources.displayMetrics.heightPixels * .65f).toInt()
        }
        root.addView(ScrollView(context).apply {
            tag = SCROLL_TAG
            isFillViewport = false
            addView(column, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        cancelButton = action("Annuler l’essai en cours", ticket, actions.cancel).also {
            footer.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        footer.addView(action("Fermer le diagnostic", ticket) { closeByUser(ticket) },
            LinearLayout.LayoutParams(-1, -2))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(context).setView(root).create()
        panel = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { closeByUser(ticket) }
        dialog.setOnDismissListener {
            if (panel === dialog) {
                generation++
                panel = null
                clearViews()
            }
        }
        if (context !is Activity || (!context.isFinishing && !context.isDestroyed)) {
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (context.resources.displayMetrics.heightPixels * .9f).toInt())
        }
        applyState()
        return dialog
    }

    /** All updates are expected on the UI thread. Only the short stage is live. */
    fun update(report: String, busy: Boolean, modelReady: Boolean, stage: String) {
        reportValue = report
        busyValue = busy
        modelReadyValue = modelReady
        stageValue = stage
        applyState()
    }

    private fun applyState() {
        reportView?.let { if (it.text.toString() != reportValue) it.text = reportValue }
        statusView?.let { if (it.text.toString() != stageValue) it.text = stageValue }
        taskButtons.forEach { it.isEnabled = !busyValue }
        modelButton?.isEnabled = modelReadyValue && !busyValue
        clearModelButton?.isEnabled = !busyValue
        cancelButton?.isEnabled = busyValue
    }

    private fun closeByUser(ticket: Long) {
        if (ticket != generation) return
        val old = panel
        generation++
        panel = null
        clearViews()
        try { actions.close() } finally { old?.dismiss() }
    }

    /** Silent teardown: the owner already controls cancellation and lifecycle. */
    fun dismiss() {
        generation++
        val old = panel
        panel = null
        old?.setOnCancelListener(null)
        old?.setOnDismissListener(null)
        old?.dismiss()
        clearViews()
    }

    private fun clearViews() {
        reportView = null
        statusView = null
        taskButtons.clear()
        modelButton = null
        clearModelButton = null
        cancelButton = null
    }

    companion object {
        internal const val SCROLL_TAG = "local-preparation-diagnostic-scroll"
    }
}
