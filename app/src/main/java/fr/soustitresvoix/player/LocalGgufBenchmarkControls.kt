package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/** A manually started, copyable synthetic benchmark, independent from video and subtitle files. */
internal class LocalGgufBenchmarkControls(
    private val activity: Activity,
    private val otherPreparationBusy: () -> Boolean,
    private val launchModelPicker: () -> Unit,
    private val onChanged: () -> Unit = {},
    private val playbackSnapshot: () -> LocalGgufBenchmark.PlaybackObservation? = { null },
    private val onExport: ((String, String, String) -> Unit)? = null,
    private val benchmarkFactory: ((LocalGgufBenchmark.State) -> Unit) -> LocalGgufBenchmark =
        { changed -> LocalGgufBenchmark(activity.applicationContext, onChanged = changed, playbackSnapshot = playbackSnapshot) }
) : AutoCloseable {
    private val reportWorker = Executors.newSingleThreadExecutor()
    private val reportMain = Handler(Looper.getMainLooper())
    private var exportPreparing = false
    private var exportButton: Button? = null
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
    private var contextButton: Button? = null
    private var qualityButton: Button? = null
    private var cancelButton: Button? = null
    private var threadsInput: EditText? = null
    private var gpuChoice: Spinner? = null
    private var selectedGpuMode = "OPENCL"
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
    private fun readableChoices(values: List<String>) = object : ArrayAdapter<String>(activity,
        android.R.layout.simple_spinner_dropdown_item, values) {
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
        statusView?.let { view ->
            val status = localStatus ?: state.status
            if (view.text.toString() != status) view.text = status
        }
        reportView?.let { view ->
            val report = state.report()
            if (view.text.toString() != report) view.text = report
        }
        modelControls?.setActive(!state.busy)
        startButton?.isEnabled = !state.busy
        variedButton?.isEnabled = !state.busy
        contextButton?.isEnabled = !state.busy
        qualityButton?.isEnabled = !state.busy
        threadsInput?.isEnabled = !state.busy
        gpuChoice?.isEnabled = !state.busy
        orderChoice?.isEnabled = !state.busy
        reopeningChoice?.isEnabled = !state.busy
        cancelButton?.isEnabled = state.busy
        exportButton?.isEnabled = onExport != null && !state.busy && !exportPreparing && state.runs.isNotEmpty()
    }
    private fun exportReport() {
        val exporter = onExport ?: return
        val snapshot = benchmark.state
        if (closed || snapshot.busy || snapshot.runs.isEmpty() || exportPreparing || panel?.isShowing != true) return
        val owner = generation
        exportPreparing = true
        localStatus = "Préparation du rapport TXT complet…"
        statusView?.text = localStatus
        exportButton?.isEnabled = false
        reportWorker.execute {
            val prepared = runCatching { snapshot.report() }
            reportMain.post {
                // A panel replacement/close never launches a late file picker.
                if (closed || owner != generation || panel?.isShowing != true) return@post
                exportPreparing = false
                val report = prepared.getOrNull()
                if (report == null) message("La préparation du rapport TXT a échoué. Le rapport reste consultable ; réessaie.")
                else try {
                    exporter("text/plain", "comparatif-local-gguf.txt", report)
                    message("Rapport TXT complet prêt. Choisis son emplacement avec Android ; une annulation conserve le rapport dans ce panneau.")
                } catch (_: Exception) {
                    message("Le choix du fichier TXT n’a pas pu être ouvert. Le rapport reste consultable ; réessaie.")
                }
            }
        }
    }
    private fun start(models: LocalGgufModelControls, threads: EditText, options: LocalGgufBenchmark.Options) {
        if (busy) return
        if (exportPreparing) { message("Attends la préparation du rapport TXT avant de lancer un autre comparatif."); return }
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
        val loadSettings = try { models.loadSettings.also { it.validate() } }
        catch (_: TranslationFailure) { message("Réglages de chargement invalides ; vérifie les valeurs avant le comparatif."); return }
        preferences.edit().putInt("local-threads", count).putString("local-profile", models.profile.name).apply()
        localStatus = null
        selectedGpuMode = if (gpuChoice?.selectedItemPosition == 1) "VULKAN" else "OPENCL"
        benchmark.start(model, count, models.profile,
            options.copy(contextPolicy = models.contextPolicy, gpuMode = selectedGpuMode, loadSettings = loadSettings))
    }
    fun show() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        dismissPanel()
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        column.addView(text("Plusieurs essais locaux sont disponibles avec le modèle déjà importé : un essai court qui répète le même texte, et une série étendue de 32 occurrences de sous-titres anglais intégrés et un comparatif de trois stratégies de contexte sur GPU. La série comprend des dialogues, panneaux, nombres, phrases longues et répétitions légitimes avec leurs propres identifiants et minutages. Aucun texte de sous-titre personnel, contexte personnel ou contenu vidéo n’est analysé. Aucun envoi ni téléchargement."))
        column.addView(text("La durée est inconnue et peut atteindre plusieurs minutes selon le modèle. Annuler reste disponible. Garde l’application ouverte ; tu peux fermer ce panneau et utiliser le lecteur. Le modèle reste chargé dans chaque série ; CPU et GPU sont exécutés successivement avec les mêmes poids, profil et limites. Les relevés du chargement distinguent des étapes globales, sans isoler tous les transferts ou compilations. L’ordre, le cache système et la chauffe peuvent influencer les mesures."))
        statusView = text(benchmark.state.status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(statusView)
        val models = LocalGgufModelControls(activity, { busy || otherPreparationBusy() }, launchModelPicker,
            onChanged = { localStatus = null; render() }, onMessage = ::message)
        modelControls = models
        column.addView(models.view(showExecutionChoice = false, showLoadChoices = true))
        val threads = EditText(activity).apply {
            id = View.generateViewId(); tag = THREADS_TAG; setText(preferences.getInt("local-threads", 4).toString())
            textSize = 18f; isSingleLine = true; inputType = InputType.TYPE_CLASS_NUMBER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        threadsInput = threads
        column.addView(text("Threads CPU pour les deux modes — 1 à 8, défaut 4").apply { labelFor = threads.id })
        column.addView(threads)
        gpuChoice = Spinner(activity).apply {
            id = View.generateViewId(); contentDescription = GPU_LABEL; minimumHeight = dp(64)
            adapter = readableChoices(listOf("GPU OpenCL strict", "GPU Vulkan strict"))
            setSelection(if (selectedGpuMode == "VULKAN") 1 else 0)
        }.also { choice ->
            column.addView(text(GPU_LABEL).apply { labelFor = choice.id })
            column.addView(choice)
        }
        column.addView(text("Le moteur choisi s’applique à tous les essais GPU ci-dessous. OpenCL et Vulkan sont comparés séparément au CPU ; un repli vers le CPU interrompt le passage GPU. Leur disponibilité et leur calcul effectif restent à vérifier sur l’appareil."))
        column.addView(text("Texte anglais intégré :\n${LocalGgufBenchmark.SOURCE}\nCible : français. Le profil et les limites de traduction sont identiques dans les deux modes ; seul le moteur de calcul demandé change."))
        startButton = action("Comparer CPU puis GPU sur le texte intégré") {
            start(models, threads, LocalGgufBenchmark.Options())
        }.also { column.addView(it) }
        column.addView(text("Comparatif étendu : les mêmes 32 occurrences sont traduites dans chaque mode. Chaque occurrence conserve son identité, même lorsqu’un texte est répété. Choisir l’ordre inverse lors d’un autre essai aide à observer l’effet de l’ordre ; cela ne supprime pas les effets de cache ou de chauffe."))
        orderChoice = Spinner(activity).apply {
            id = View.generateViewId(); contentDescription = ORDER_LABEL; minimumHeight = dp(64)
            adapter = readableChoices(listOf("CPU puis GPU", "GPU puis CPU"))
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
        column.addView(text("Comparatif de contexte : les trois stratégies traduisent successivement les mêmes 32 occurrences sur le moteur GPU strict choisi ci-dessus. Chaque stratégie possède sa session et son historique. Les ressources sont libérées avant la suivante. Ce comparatif ne change pas ton choix de contexte, n’attribue aucun score de qualité et n’utilise pas les options d’ordre CPU/GPU ou de réouverture ci-dessus. Les sorties doivent être comparées aux sources ; aucune stratégie n’est présumée meilleure."))
        contextButton = action(CONTEXT_START_LABEL) {
            start(models, threads, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.CONTEXT_POLICIES))
        }.also { column.addView(it) }
        column.addView(text("Un corpus complémentaire contient des phrases synthétiques distinctes du comparatif historique. Il sert à comparer nombres, négations, pronoms, conditions et panneaux sans adapter les réponses aux exemples précédents. Ses sources et toutes les traductions figurent dans le rapport ; aucun score automatique de fidélité n’est attribué."))
        qualityButton = action(QUALITY_START_LABEL) {
            start(models, threads, LocalGgufBenchmark.Options(LocalGgufBenchmark.Scenario.CONTEXT_POLICIES, qualityCorpus = true))
        }.also { column.addView(it) }
        cancelButton = action("Annuler l’essai comparatif CPU / GPU") { localStatus = null; benchmark.cancel() }
            .also { column.addView(it) }
        column.addView(action("Copier le rapport CPU / GPU") {
            val report = benchmark.state.report()
            if (!canCopyReport(report)) {
                message("Le rapport complet est trop volumineux pour le presse-papiers. Il reste consultable et sélectionnable dans ce panneau ; utilise Exporter le rapport complet en TXT. Aucun texte n’est tronqué.")
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
        exportButton = action(EXPORT_LABEL) { exportReport() }.also { column.addView(it) }
        column.addView(text("L’export TXT conserve le rapport entier, y compris un rapport trop volumineux pour le presse-papiers. Il devient disponible après la fin ou l’arrêt du comparatif. Choisis son emplacement avec Android ; les textes du rapport ne sont pas placés dans la demande d’ouverture du sélecteur."))
        reportView = text(benchmark.state.report()).apply { setTextIsSelectable(true) }
        column.addView(reportView)
        column.addView(text("Une sélection GPU n’est pas une preuve d’accélération. Le rapport exige un calcul GPU réellement observé pour valider chaque passage OpenCL ou Vulkan. Les sorties sont présentées pour comparaison humaine ; leur identité ne prouve pas une traduction fidèle. Aucun test NPU n’est exécuté."))
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
        gpuChoice?.selectedItemPosition?.let { selectedGpuMode = if (it == 1) "VULKAN" else "OPENCL" }
        orderChoice?.selectedItemPosition?.let {
            selectedOrder = if (it == 1) LocalGgufBenchmark.Order.GPU_THEN_CPU else LocalGgufBenchmark.Order.CPU_THEN_GPU
        }
        reopeningChoice?.let { reopenForLoad = it.isChecked }
        panel = null; statusView = null; reportView = null; startButton = null; variedButton = null; contextButton = null; qualityButton = null
        cancelButton = null; threadsInput = null; gpuChoice = null; orderChoice = null; reopeningChoice = null; localStatus = null
        exportButton = null; exportPreparing = false
        old?.dismiss()
    }
    override fun close() {
        if (closed) return
        closed = true; dismissPanel(); benchmark.close()
        reportWorker.shutdownNow(); reportMain.removeCallbacksAndMessages(null)
    }
    companion object {
        internal const val EXPORT_LABEL = "Exporter le rapport complet en TXT"
        internal fun canCopyReport(report: String): Boolean = report.length <= MAX_CLIPBOARD_CHARACTERS
        internal const val SCROLL_TAG = "local-gguf-benchmark-controls-scroll"
        internal const val GPU_LABEL = "Moteur GPU du comparatif"
        internal const val THREADS_TAG = "local-gguf-benchmark-threads"
        internal const val ORDER_LABEL = "Ordre du comparatif étendu"
        internal const val REOPEN_LABEL = "Recharger le modèle après chaque série pour mesurer une réouverture"
        internal const val QUALITY_START_LABEL = "Comparer les stratégies sur des phrases inédites — GPU"
        internal const val CONTEXT_START_LABEL = "Comparer les trois stratégies de contexte sur GPU — 32 sous-titres"
        internal const val VARIED_START_LABEL = "Comparer CPU et GPU sur 32 sous-titres intégrés"
        private const val MAX_CLIPBOARD_CHARACTERS = 400_000
    }
}
