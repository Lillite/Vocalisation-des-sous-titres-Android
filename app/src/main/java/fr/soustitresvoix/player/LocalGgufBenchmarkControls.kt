package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** A manually started, copyable synthetic benchmark, independent from video and subtitle files. */
internal class LocalGgufBenchmarkControls(
    private val activity: Activity,
    private val otherPreparationBusy: () -> Boolean,
    private val launchModelPicker: () -> Unit,
    private val onChanged: () -> Unit = {},
    private val playbackSnapshot: () -> LocalGgufBenchmark.PlaybackObservation? = { null },
    private val benchmarkFactory: ((LocalGgufBenchmark.State) -> Unit) -> LocalGgufBenchmark =
        { changed -> LocalGgufBenchmark(activity.applicationContext, onChanged = changed, playbackSnapshot = playbackSnapshot) }
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
    private var variedButton: Button? = null
    private var cancelButton: Button? = null
    private var threadsInput: EditText? = null
    private var orderChoice: Spinner? = null
    private var reopeningChoice: CheckBox? = null
    private var selectedOrder = LocalGgufBenchmark.Order.CPU_THEN_GPU
    private var reopenForLoad = false
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
    private fun orderChoices() = object : ArrayAdapter<String>(activity,
        android.R.layout.simple_spinner_dropdown_item, listOf("CPU puis GPU OpenCL", "GPU OpenCL puis CPU")) {
        private fun readable(view: View): View = view.apply {
            (this as? TextView)?.apply {
                textSize = 18f; isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
                minimumHeight = dp(64); minHeight = dp(64)
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            readable(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            readable(super.getDropDownView(position, convertView, parent))
    }
    private fun render() {
        val state = benchmark.state
        statusView?.text = localStatus ?: state.status
        reportView?.let { view ->
            val report = state.report()
            if (view.text.toString() != report) view.text = report
        }
        modelControls?.setActive(!state.busy)
        startButton?.isEnabled = !state.busy
        variedButton?.isEnabled = !state.busy
        threadsInput?.isEnabled = !state.busy
        orderChoice?.isEnabled = !state.busy
        reopeningChoice?.isEnabled = !state.busy
        cancelButton?.isEnabled = state.busy
    }
    private fun start(models: LocalGgufModelControls, threads: EditText, options: LocalGgufBenchmark.Options) {
        if (busy) return
        if (otherPreparationBusy()) {
            message("Termine ou annule les autres préparations avant de lancer le comparatif."); return
        }
        if (models.busy) { message("Attends la fin de la préparation du modèle GGUF."); return }
        val model = models.model
        if (model == null) { message("Choisis un modèle GGUF ou prépare celui déjà importé dans le diagnostic."); return }
        val count = threads.text.toString().toIntOrNull()
        if (count == null || count !in 1..8) {
            message("Nombre de threads invalide : indique un entier de 1 à 8."); return
        }
        preferences.edit().putInt("local-threads", count).putString("local-profile", models.profile.name).apply()
        localStatus = null
        benchmark.start(model, count, models.profile, options)
    }
    fun show() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        dismissPanel()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        column.addView(text("Deux essais locaux sont disponibles avec le modèle déjà importé : un essai court qui répète le même texte, et une série étendue de 32 occurrences de sous-titres anglais intégrés. La série comprend des dialogues, panneaux, nombres, phrases longues et répétitions légitimes avec leurs propres identifiants et minutages. Aucun texte de sous-titre personnel, contexte personnel ou contenu vidéo n’est analysé. Aucun envoi ni téléchargement."))
        column.addView(text("La durée est inconnue et peut atteindre plusieurs minutes selon le modèle. Annuler reste disponible. Garde l’application ouverte ; tu peux fermer ce panneau et utiliser le lecteur. Le modèle reste chargé dans chaque série ; CPU et GPU sont exécutés successivement avec les mêmes poids, profil et limites. Les relevés du chargement distinguent des étapes globales, sans isoler tous les transferts ou compilations. L’ordre, le cache système et la chauffe peuvent influencer les mesures."))
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
        threadsInput = threads
        column.addView(text("Threads CPU pour les deux modes — 1 à 8, défaut 4").apply { labelFor = threads.id })
        column.addView(threads)
        column.addView(text("Texte anglais intégré :\n${LocalGgufBenchmark.SOURCE}\nCible : français. Le profil et les limites de traduction sont identiques dans les deux modes ; seul le moteur de calcul demandé change."))
        startButton = action("Comparer CPU puis GPU sur le texte intégré") {
            start(models, threads, LocalGgufBenchmark.Options())
        }.also { column.addView(it) }
        column.addView(text("Comparatif étendu : les mêmes 32 occurrences sont traduites dans chaque mode. Chaque occurrence conserve son identité, même lorsqu’un texte est répété. Choisir l’ordre inverse lors d’un autre essai aide à observer l’effet de l’ordre ; cela ne supprime pas les effets de cache ou de chauffe."))
        orderChoice = Spinner(activity).apply {
            id = View.generateViewId(); contentDescription = ORDER_LABEL; minimumHeight = dp(64)
            adapter = orderChoices()
            setSelection(if (selectedOrder == LocalGgufBenchmark.Order.CPU_THEN_GPU) 0 else 1)
        }.also { choice ->
            column.addView(text(ORDER_LABEL).apply { labelFor = choice.id })
            column.addView(choice)
        }
        reopeningChoice = CheckBox(activity).apply {
            text = REOPEN_LABEL; textSize = 18f; minHeight = dp(64); minimumHeight = dp(64)
            isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null; isChecked = reopenForLoad
        }.also { column.addView(it) }
        column.addView(text("La réouverture facultative libère le modèle puis le recharge dans le même mode pour traduire une seule occurrence intégrée. Elle ajoute du temps et permet de comparer les chargements. Les caches ne sont pas effacés : ce n’est pas un démarrage garanti à froid."))
        variedButton = action(VARIED_START_LABEL) {
            selectedOrder = if (orderChoice?.selectedItemPosition == 1) LocalGgufBenchmark.Order.GPU_THEN_CPU
                else LocalGgufBenchmark.Order.CPU_THEN_GPU
            reopenForLoad = reopeningChoice?.isChecked == true
            start(models, threads, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.VARIED, selectedOrder, reopenForLoad))
        }.also { column.addView(it) }
        cancelButton = action("Annuler l’essai comparatif CPU / GPU") { localStatus = null; benchmark.cancel() }
            .also { column.addView(it) }
        column.addView(action("Copier le rapport CPU / GPU") {
            val report = benchmark.state.report()
            if (report.length > MAX_CLIPBOARD_CHARACTERS) {
                message("Le rapport complet est trop volumineux pour le presse-papiers. Il reste consultable et sélectionnable dans ce panneau ; aucun texte n’est tronqué.")
                return@action
            }
            try {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Comparatif local CPU / GPU", report))
            } catch (_: RuntimeException) {
                message("La copie du rapport complet a échoué. Le rapport reste consultable et sélectionnable dans ce panneau ; aucun texte n’a été tronqué. L’application n’a pas demandé d’effacer le presse-papiers.")
                return@action
            }
            message("Rapport CPU / GPU copié. Il contient seulement les textes intégrés, leurs sorties et les mesures locales, avec les éventuelles observations ponctuelles du lecteur.")
        })
        reportView = text(benchmark.state.report()).apply { setTextIsSelectable(true) }
        column.addView(reportView)
        column.addView(text("Une sélection GPU n’est pas une preuve d’accélération. Le rapport exige un calcul GPU réellement observé pour valider le passage OpenCL. Les sorties sont présentées pour comparaison humaine ; leur identité ne prouve pas une traduction fidèle. Aucun test NPU n’est exécuté."))
        column.addView(text("Si une vidéo est ouverte, le rapport peut ajouter des observations ponctuelles de l’état du lecteur. Elles ne prouvent pas la fluidité continue, la vocalisation de toutes les occurrences, l’autonomie ou le fonctionnement de TalkBack. Le comparatif ne commande ni lecture, ni pause, ni déplacement dans la vidéo."))
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
        orderChoice?.selectedItemPosition?.let {
            selectedOrder = if (it == 1) LocalGgufBenchmark.Order.GPU_THEN_CPU else LocalGgufBenchmark.Order.CPU_THEN_GPU
        }
        reopeningChoice?.let { reopenForLoad = it.isChecked }
        panel = null; statusView = null; reportView = null; startButton = null; variedButton = null
        cancelButton = null; threadsInput = null; orderChoice = null; reopeningChoice = null; localStatus = null
        old?.dismiss()
    }
    override fun close() {
        if (closed) return
        closed = true; dismissPanel(); benchmark.close()
    }
    companion object {
        internal const val SCROLL_TAG = "local-gguf-benchmark-controls-scroll"
        internal const val ORDER_LABEL = "Ordre du comparatif étendu"
        internal const val REOPEN_LABEL = "Recharger le modèle après chaque série pour mesurer une réouverture"
        internal const val VARIED_START_LABEL = "Comparer CPU et GPU sur 32 sous-titres intégrés"
        private const val MAX_CLIPBOARD_CHARACTERS = 400_000
    }
}
