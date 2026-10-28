package fr.soustitresvoix.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic work is session-independent. No playback, narration, subtitle or
 * preference command is issued here. Results are accepted only by their owner. */
internal class PreparationDiagnosticsController(
    private val context: Context,
    private val playbackIdle: () -> Boolean,
    private val launchModelPicker: () -> Unit,
    private val launchVideoPicker: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val directory = File(context.filesDir, "local-diagnostic").apply { mkdirs() }
    private val model = File(directory, "model.gguf")
    private val pendingImport = File(directory, "model-import.part")
    private val modelStore = LocalGgufModelStore(context)
    private var modelLease: AutoCloseable? = null
    private val reports = linkedMapOf<String, String>()
    private var measuredModelStamp: String? = null
    private var ticket = 0L
    private var cancellation = AtomicBoolean(false)
    private var busy = false
    private var closed = false
    private var panelOpen = false
    private var pendingModel = false
    private var pendingVideo = false
    private var stage = "Prêt. Aucun essai lancé."
    private var client: LocalModelProbeClient? = null
    private val controls = PreparationDiagnosticsControls(context,
        PreparationDiagnosticsControls.Actions(::refreshDevice, ::runDemo, ::chooseVideo,
            ::chooseModel, ::runModel, ::cancel, ::dismissPanel, ::clearModel, ::copyReport,
            ::runBuiltinModel))

    fun show() {
        if (closed) return
        panelOpen = true
        controls.show()
        refresh()
    }

    private fun modelStamp() = "${model.length()}:${model.lastModified()}:${modelStore.current()?.sha256.orEmpty()}"

    private fun report(): String {
        // The translation panels can replace the same private copy too. An
        // earlier diagnostic result belongs only to the exact measured copy.
        if (measuredModelStamp != null && measuredModelStamp != modelStamp()) {
            reports.remove("model")
            measuredModelStamp = null
        }
        return buildString {
        append("Diagnostic local — étape 1\n")
        append("Les essais ne modifient pas la vidéo, ses sous-titres ou les réglages vocaux.\n")
        if (reports.isEmpty()) append("Aucune mesure demandée.\n")
        reports.values.forEach { append('\n'); append(it); append('\n') }
        if (model.isFile) append("\nUne copie privée de modèle est disponible (${model.length()} octets). Son import ne prouve pas sa compatibilité.\n")
        if (pendingImport.isFile) append("\nUne copie incomplète occupe ${pendingImport.length()} octets. Le bouton de suppression permet de récupérer cet espace.\n")
        }
    }

    private fun refresh() {
        if (!closed && panelOpen) controls.update(report(), busy, model.isFile, stage)
    }

    private fun begin(requireIdle: Boolean): Long? {
        if (closed || busy) return null
        if (requireIdle && !playbackIdle()) {
            stage = "Mets la lecture et les préparations en pause avant cet essai isolé. Le diagnostic ne les arrête ni ne les reprend automatiquement."
            refresh(); return null
        }
        busy = true
        cancellation = AtomicBoolean(false)
        return ++ticket
    }

    private fun finish(owner: Long, key: String, value: String, status: String) {
        if (closed || ticket != owner) return
        reports[key] = value
        stage = status
        busy = false
        refresh()
    }

    private fun refreshDevice() {
        val owner = begin(false) ?: return
        val cancelled = cancellation
        stage = "Lecture des mesures Android…"; refresh()
        worker.execute {
            val result = runCatching { PreparationDeviceProbe.read(context).render() }
                .getOrElse { "Mesures indisponibles (${it.javaClass.simpleName})." }
            main.post {
                finish(owner, "device", result, if (cancelled.get()) "Mesure annulée." else "Mesures actualisées.")
            }
        }
    }

    private fun runDemo() = runVideo(Uri.parse("android.resource://${context.packageName}/${R.raw.demonstration}"))

    private fun chooseVideo() {
        if (closed || busy) return
        if (!playbackIdle()) { begin(true); return }
        pendingVideo = true
        try { launchVideoPicker() } catch (_: Exception) {
            pendingVideo = false; stage = "Le sélecteur de vidéo n’est pas disponible."; refresh()
        }
    }

    fun selectedVideo(uri: Uri?) {
        val requested = pendingVideo
        pendingVideo = false
        if (!closed && requested && uri != null) runVideo(uri)
    }

    private fun runVideo(uri: Uri) {
        val owner = begin(true) ?: return
        val cancelled = cancellation
        stage = "Décodage de quelques images hors écran…"; refresh()
        worker.execute {
            val result = PreparationVideoProbe.run(context, uri) { cancelled.get() }
            main.post { finish(owner, "video", result.render(), "Essai vidéo terminé : ${result.status}.") }
        }
    }

    private fun chooseModel() {
        if (closed || busy) return
        pendingModel = true
        try { launchModelPicker() } catch (_: Exception) {
            pendingModel = false; stage = "Le sélecteur de modèle n’est pas disponible."; refresh()
        }
    }

    fun selectedModel(uri: Uri?) {
        val requested = pendingModel
        pendingModel = false
        if (closed || !requested || uri == null) return
        val owner = begin(true) ?: return
        val cancelled = cancellation
        stage = "Copie privée du modèle. Le fichier original est conservé…"; refresh()
        worker.execute {
            var lastProgress = 0L
            val result = runCatching {
                val expected = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { size -> size >= 0 } else null
                    }
                }.getOrNull()
                requireNotNull(context.contentResolver.openInputStream(uri)) { "Fichier inaccessible." }.use { input ->
                    modelStore.importModel(input, expected, { cancelled.get() }) { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 1000) {
                            lastProgress = now
                            main.post {
                                if (!closed && ticket == owner && !cancelled.get()) {
                                    stage = String.format(Locale.FRANCE, "Import : %.2f Gio copiés…", bytes / 1_073_741_824.0)
                                    refresh()
                                }
                            }
                        }
                    }
                }
            }
            main.post {
                result.fold(onSuccess = {
                    // An old model's timings must never appear beside a new
                    // import as though they measured the replacement weights.
                    reports.remove("model")
                    measuredModelStamp = null
                    finish(owner, "import", "Copie privée complète : ${it.bytes} octets ; en-tête GGUF accepté.\nEmpreinte SHA-256 : ${it.sha256}.\nLe contenu et le chargement du modèle restent à tester. Le fichier d’origine est conservé.", "Modèle importé. L’ancien résultat a été retiré ; lance un nouvel essai.")
                }, onFailure = {
                    val reason = when {
                        cancelled.get() || it is InterruptedIOException -> "Import annulé ; la copie précédente est conservée."
                        it is LocalGgufModelBusyException -> it.message.orEmpty()
                        else -> "Import impossible (${it.javaClass.simpleName}). Vérifie le fichier choisi et l’espace disponible."
                    }
                    finish(owner, "import", reason, "Import interrompu ou refusé.")
                })
            }
        }
    }

    private fun runModel() {
        if (!model.isFile) return
        val owner = begin(true) ?: return
        try {
            modelLease = modelStore.acquireMutationLease()
        } catch (_: LocalGgufModelBusyException) {
            finish(owner, "model", "Le modèle local est utilisé par une traduction ou un import. Attends sa fin avant cet essai.", "Modèle local déjà utilisé.")
            return
        }
        reports.remove("model"); measuredModelStamp = null
        startModel(owner, model)
    }

    private fun runBuiltinModel() {
        val owner = begin(true) ?: return
        val cancelled = cancellation
        stage = "Préparation du petit modèle synthétique intégré…"; refresh()
        worker.execute {
            val prepared = runCatching {
                File(context.cacheDir, "diagnostic-model.gguf").also { fixture ->
                    context.resources.openRawResource(R.raw.diagnostic_model).use { input ->
                        DiagnosticModelImport.copy(input, fixture, cancelled = { cancelled.get() })
                    }
                }
            }
            main.post {
                if (!closed && ticket == owner) prepared.fold(
                    onSuccess = {
                        if (cancelled.get()) finish(owner, "builtin", "Essai intégré annulé.", "Essai annulé.")
                        else startModel(owner, it, "builtin")
                    },
                    onFailure = { finish(owner, "builtin", "Modèle synthétique indisponible (${it.javaClass.simpleName}).", "Essai intégré interrompu.") })
            }
        }
    }

    private fun startModel(owner: Long, file: File, reportKey: String = "model") {
        stage = "Démarrage de l’essai CPU dans un processus séparé…"; refresh()
        val started = LocalModelProbeClient(context,
            onStage = { value ->
                if (!closed && ticket == owner) { stage = value; refresh() }
            }, onResult = { result ->
                if (!closed && ticket == owner) {
                    client?.close(); client = null
                    modelLease?.close(); modelLease = null
                    if (reportKey == "model") measuredModelStamp = modelStamp()
                    val source = if (reportKey == "builtin") "Modèle miniature synthétique intégré. Cet essai ne mesure ni la qualité de traduction ni la capacité à charger un grand modèle.\n" else "Modèle importé. Essai court sur texte synthétique.\n"
                    finish(owner, reportKey, source + result.render(), "Essai de modèle terminé : ${result.status}.")
                }
            })
        client = started
        started.start(file, threads = minOf(4, Runtime.getRuntime().availableProcessors()), maxOutputTokens = 32)
    }

    fun cancel() {
        if (closed || !busy) return
        cancellation.set(true)
        stage = "Annulation demandée. Attente de la libération des ressources…"
        client?.cancel()
        refresh()
    }

    fun onHostPaused() { if (busy) cancel() }

    private fun clearModel() {
        if (closed || busy) return
        val deleted = try {
            modelStore.remove() && File(context.cacheDir, "model-import.part").let { !it.exists() || it.delete() }
        } catch (_: LocalGgufModelBusyException) {
            stage = "Le modèle local est utilisé par un autre traitement. Attends sa fin avant de supprimer sa copie."
            refresh(); return
        }
        stage = if (deleted) "Copie privée et import incomplet supprimés. Le fichier original est conservé."
            else "La copie privée n’a pas pu être entièrement supprimée."
        if (deleted) { reports.remove("model"); reports.remove("import"); measuredModelStamp = null }
        refresh()
    }

    private fun copyReport() {
        if (closed) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) stage = "Presse-papiers indisponible."
        else {
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic local", report()))
            stage = "Rapport copié dans le presse-papiers sur demande. Aucun envoi automatique."
        }
        refresh()
    }

    private fun dismissPanel() {
        cancel()
        pendingModel = false; pendingVideo = false; panelOpen = false
        controls.dismiss()
    }

    fun close() {
        if (closed) return
        cancellation.set(true); closed = true; ++ticket
        pendingModel = false; pendingVideo = false; panelOpen = false
        client?.close(); client = null; controls.dismiss()
        modelLease?.close(); modelLease = null
        worker.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
}
