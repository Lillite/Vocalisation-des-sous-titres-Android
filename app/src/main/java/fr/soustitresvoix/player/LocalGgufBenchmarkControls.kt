package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A manually started, copyable synthetic benchmark, independent from video and subtitle files. */
internal class LocalGgufBenchmarkControls(
    private val activity: Activity,
    private val otherPreparationBusy: () -> Boolean,
    private val launchModelPicker: () -> Unit,
    private val onChanged: () -> Unit = {},
    private val benchmarkFactory: ((LocalGgufBenchmark.State) -> Unit) -> LocalGgufBenchmark =
        { changed -> LocalGgufBenchmark(activity.applicationContext, onChanged = changed) }
) : AutoCloseable {
    private val preferences = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
    private var panel: AlertDialog? = null
    private var generation = 0L
    private var closed = false
    private var modelControls: LocalGgufModelControls? = null
    private var statusView: TextView? = null
    private var reportView: TextView? = null
    private var localStatus: String? = null
    private var startButton: Button? = null
    private var cancelButton: Button? = null
    private val benchmark = benchmarkFactory {
        if (!closed) { localStatus = null; render(); onChanged() }
    }
    val busy: Boolean get() = benchmark.state.busy
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun action(value: String, block: () -> Unit): Button {
        val owner = generation
        return Button(activity).apply {
            text = value; textSize = 18f; isAllCaps = false; minHeight = dp(64); minimumHeight = dp(64)
            setOnClickListener { if (!closed && owner == generation && panel?.isShowing == true) block() }
        }
    }
    private fun message(value: String) { localStatus = value; render() }
    private fun render() {
        val state = benchmark.state
        statusView?.text = localStatus ?: state.status
        reportView?.let { if (it.text.toString() != state.report()) it.text = state.report() }
        modelControls?.setActive(!state.busy)
        startButton?.isEnabled = !state.busy
        cancelButton?.isEnabled = state.busy
    }
    fun show() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        dismissPanel()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        column.addView(text("Compare le CPU puis le GPU OpenCL avec le même modèle déjà importé et le même court texte anglais intégré. Deux passages par mode permettent d’observer la réutilisation éventuelle du préfixe. Aucun sous-titre personnel, vidéo ou contexte personnel n’est utilisé. Aucun envoi ni téléchargement."))
        column.addView(text("La durée est inconnue et peut être longue selon le modèle. Annuler reste disponible. Garde l’application ouverte ; tu peux fermer ce panneau et utiliser le lecteur. Le rapport distingue les temps de chargement, de traitement de l’entrée et de génération. L’ordre CPU puis GPU, le cache système et la chauffe peuvent influencer les mesures."))
        statusView = text(benchmark.state.status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(statusView)
        val models = LocalGgufModelControls(activity, { busy || otherPreparationBusy() }, launchModelPicker,
            onChanged = { localStatus = null; render() }, onMessage = ::message)
        modelControls = models
        column.addView(models.view(showExecutionChoice = false))
        val threads = EditText(activity).apply {
            id = View.generateViewId(); setText(preferences.getInt("local-threads", 4).toString())
            textSize = 18f; isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        column.addView(text("Threads CPU pour les deux modes — 1 à 8, défaut 4").apply { labelFor = threads.id })
        column.addView(threads)
        column.addView(text("Texte anglais intégré :\n${LocalGgufBenchmark.SOURCE}\nCible : français. Le profil et les limites de traduction sont identiques dans les deux modes ; seul le moteur de calcul demandé change."))
        startButton = action("Comparer CPU puis GPU sur le texte intégré") {
            if (otherPreparationBusy()) {
                message("Termine ou annule les autres préparations avant de lancer le comparatif."); return@action
            }
            if (models.busy) { message("Attends la fin de la préparation du modèle GGUF."); return@action }
            val model = models.model
            if (model == null) { message("Choisis un modèle GGUF ou prépare celui déjà importé dans le diagnostic."); return@action }
            val count = threads.text.toString().toIntOrNull()
            if (count == null || count !in 1..8) { message("Nombre de threads invalide : indique un entier de 1 à 8."); return@action }
            preferences.edit().putInt("local-threads", count).putString("local-profile", models.profile.name).apply()
            localStatus = null
            benchmark.start(model, count, models.profile)
        }.also { column.addView(it) }
        cancelButton = action("Annuler l’essai comparatif CPU / GPU") { localStatus = null; benchmark.cancel() }
            .also { column.addView(it) }
        column.addView(action("Copier le rapport CPU / GPU") {
            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Comparatif local CPU / GPU", benchmark.state.report()))
            message("Rapport CPU / GPU copié. Il contient seulement le texte intégré, ses sorties et les mesures locales.")
        })
        reportView = text(benchmark.state.report()).apply { setTextIsSelectable(true) }
        column.addView(reportView)
        column.addView(text("Une sélection GPU n’est pas une preuve d’accélération. Le rapport exige un calcul GPU réellement observé pour valider le passage OpenCL. Les sorties sont présentées pour comparaison humaine ; leur identité ne prouve pas une traduction fidèle. Aucun test NPU n’est exécuté."))
        column.addView(action("Fermer le panneau et laisser l’essai continuer") { dismissPanel() })
        val owner = generation
        panel = AlertDialog.Builder(activity).setTitle("Comparatif local CPU / GPU")
            .setView(ScrollView(activity).apply {
                tag = SCROLL_TAG
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }).setPositiveButton("Fermer", null).show().also { dialog ->
                dialog.setOnDismissListener { if (owner == generation) dismissPanel() }
            }
        render()
    }
    fun selectedModel(uri: Uri?) { modelControls?.selected(uri) }
    private fun dismissPanel() {
        generation++
        modelControls?.close(); modelControls = null
        val old = panel
        panel = null; statusView = null; reportView = null; startButton = null; cancelButton = null; localStatus = null
        old?.dismiss()
    }
    override fun close() {
        if (closed) return
        closed = true; dismissPanel(); benchmark.close()
    }
    companion object { internal const val SCROLL_TAG = "local-gguf-benchmark-controls-scroll" }
}
