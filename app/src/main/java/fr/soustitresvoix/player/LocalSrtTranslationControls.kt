package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SubtitleExportFormat
import java.util.concurrent.Executors

/** A standalone SRT preparation. Closing its panel leaves the worker available to the reader. */
@UnstableApi
internal class LocalSrtTranslationControls(
    private val activity: Activity,
    private val otherPreparationBusy: () -> Boolean,
    private val launchSubtitlePicker: () -> Unit,
    private val launchModelPicker: () -> Unit,
    private val onExport: (String, String, String) -> Unit,
    private val onChanged: () -> Unit = {},
    private val jobFactory: (Context, (LocalSrtTranslationJob.State) -> Unit) -> LocalSrtTranslationJob =
        { context, changed -> LocalSrtTranslationJob(context, onChanged = changed) }
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
    private var panel: AlertDialog? = null
    private var childDialog: AlertDialog? = null
    private var panelGeneration = 0L
    private var closed = false
    private var awaitingSubtitle = false
    private var metadataGeneration = 0L
    private var sourceSelection = 0L
    private var readingMetadata = false
    private var lastSourceUri: Uri? = null
    private var lastSourceName = "sous-titres.srt"
    private var modelControls: LocalGgufModelControls? = null
    private var statusView: TextView? = null
    private var exampleButton: Button? = null
    private var localStatus: String? = null
    private val job = jobFactory(activity.applicationContext, {
        if (!closed) { render(); onChanged() }
    })
    val busy: Boolean get() = job.state.busy

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(value: String, action: () -> Unit): Button {
        val owner = panelGeneration
        return Button(activity).apply {
            text = value; textSize = 18f; isAllCaps = false; minimumHeight = dp(64); minHeight = dp(64)
            setOnClickListener { if (!closed && owner == panelGeneration && panel?.isShowing == true) action() }
        }
    }
    private fun field(label: String, initial: String): Pair<TextView, EditText> {
        val input = EditText(activity).apply {
            id = View.generateViewId(); setText(initial); textSize = 18f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        return text(label).apply { labelFor = input.id } to input
    }
    private fun message(value: String) { localStatus = value; render() }
    private fun render() {
        val state = job.state
        statusView?.text = (localStatus ?: state.status) +
            (state.sourceName?.let { "\nFichier source actif : $it" } ?: "") +
            (if (state.total > 0) "\nOccurrences validées : ${state.done} / ${state.total}." else "") +
            (state.performance?.let { "\n\nDernières mesures reçues du moteur local :\n$it" } ?: "")
        exampleButton?.isEnabled = !state.busy && !readingMetadata
    }

    fun show() {
        if (closed || activity.isDestroyed || activity.isFinishing) return
        dismissPanel()
        localStatus = null
        val owner = panelGeneration
        var sourceInput: EditText? = null
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        column.addView(text("Prépare une traduction française complète d’un fichier SRT, sans ouvrir de vidéo et sans OCR. Le fichier original est conservé. Le traitement utilise seulement le modèle GGUF choisi sur ce téléphone ; aucun texte, modèle ou média n’est envoyé à un serveur."))
        statusView = text(job.state.status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(statusView)
        column.addView(button("Choisir le fichier SRT à traduire hors ligne") {
            if (busy || readingMetadata) message("Termine ou annule le traitement en cours avant de choisir un autre SRT.")
            else {
                awaitingSubtitle = true
                try { launchSubtitlePicker() }
                catch (_: Exception) { awaitingSubtitle = false; message("Aucun sélecteur Android ne permet de choisir ce SRT.") }
            }
        })
        exampleButton = button("Charger l’exemple SRT anglais intégré") {
            if (busy || readingMetadata) message("Termine ou annule le traitement en cours avant de charger l’exemple.")
            else {
                sourceSelection++; lastSourceUri = null; localStatus = null; sourceInput?.setText("en")
                job.importExample()
            }
        }.also { column.addView(it) }
        column.addView(button("Choisir l’encodage du SRT si nécessaire") {
            val uri = lastSourceUri
            if (uri == null) message("Choisis à nouveau le SRT avec le sélecteur Android avant de changer son encodage.")
            else if (busy || readingMetadata) message("Termine ou annule le traitement avant de changer l’encodage.")
            else {
                val names = arrayOf("UTF-8", "Windows-1252 — français et Europe occidentale", "UTF-16 little endian", "UTF-16 big endian", "Shift_JIS — japonais", "GB18030 — chinois simplifié", "Big5 — chinois traditionnel", "Windows-1256 — arabe", "Windows-1251 — cyrillique")
                val codes = arrayOf("UTF-8", "windows-1252", "UTF-16LE", "UTF-16BE", "Shift_JIS", "GB18030", "Big5", "windows-1256", "windows-1251")
                childDialog?.dismiss()
                childDialog = AlertDialog.Builder(activity).setTitle("Encodage du fichier SRT")
                    .setItems(names) { _, index ->
                        if (!closed && owner == panelGeneration && !busy) {
                            sourceSelection++; localStatus = null; job.importSource(uri, lastSourceName, codes[index])
                        }
                    }.setNegativeButton("Annuler", null).show()
            }
        })
        val models = LocalGgufModelControls(activity, { busy || otherPreparationBusy() }, launchModelPicker,
            onChanged = { localStatus = null; render() }, onMessage = { message(it) })
        modelControls = models
        column.addView(models.view())
        val (sourceLabel, source) = field("Langue originale des sous-titres — code explicite, par exemple en, ja ou es", preferences.getString("language", "en") ?: "en")
        sourceInput = source
        column.addView(sourceLabel); column.addView(source)
        val (threadsLabel, threads) = field("GGUF local : threads de calcul — 1 à 8, défaut 4", preferences.getInt("local-threads", 4).toString())
        threads.inputType = InputType.TYPE_CLASS_NUMBER
        column.addView(threadsLabel); column.addView(threads)
        val (contextLabel, context) = field("GGUF local : contexte de l’œuvre ou de la scène, facultatif — reste sur le téléphone", preferences.getString("local-context", "") ?: "")
        context.isSingleLine = false; context.setLines(3)
        column.addView(contextLabel); column.addView(context)
        column.addView(button("Traduire le SRT hors ligne / reprendre") {
            if (busy || models.busy || readingMetadata) { message("Un traitement est déjà en cours. Attends sa fin ou annule-le."); return@button }
            if (otherPreparationBusy()) { message("Termine ou annule l’autre préparation avant de lancer cette traduction locale."); return@button }
            val model = models.model
            if (model == null) { message("Choisis un modèle GGUF ou prépare celui déjà importé dans le diagnostic."); return@button }
            if (!job.state.hasSource) { message("Choisis d’abord le fichier SRT à traduire."); return@button }
            if (job.state.encodingRequired) { message("Choisis l’encodage du SRT avant de lancer la traduction."); return@button }
            val count = threads.text.toString().toIntOrNull()
            if (count == null || count !in 1..8) { message("Nombre de threads invalide : indique un entier de 1 à 8."); return@button }
            val language = source.text.toString().trim()
            val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
                localModel = model, localThreads = count, localContext = context.text.toString(),
                localExecution = models.execution, localProfile = models.profile)
            val sourceOwner = sourceSelection
            try { TranslationEngines.validateForRequest(config, language, "fr") }
            catch (failure: TranslationFailure) { message(failure.message ?: "Configuration locale invalide."); return@button }
            catch (_: Exception) { message("Configuration locale invalide. Vérifie le modèle et la langue source."); return@button }
            childDialog?.dismiss()
            childDialog = AlertDialog.Builder(activity).setTitle("Confirmer la traduction locale du SRT")
                .setMessage("Le texte et le contexte seront traduits sur ce téléphone avec le modèle GGUF préparé. Exécution demandée : ${config.localExecution}. Les mesures afficheront le moteur réellement utilisé et un éventuel repli CPU. Aucun réseau ni téléchargement de modèle n’est utilisé. Chaque occurrence et son minutage sont conservés. La progression validée est sauvegardée pour reprise. Garde l’application ouverte ; tu peux fermer ce panneau et utiliser le lecteur pendant la préparation. Une vidéo déjà ouverte garde sa piste actuelle tant que tu n’importes pas le SRT traduit.")
                .setPositiveButton("Traduire localement avec le GGUF") { _, _ ->
                    if (closed || owner != panelGeneration || sourceOwner != sourceSelection || models.model !== model ||
                        panel?.isShowing != true || busy || models.busy || readingMetadata || otherPreparationBusy()) return@setPositiveButton
                    preferences.edit().putString("language", language).putInt("local-threads", count)
                        .putString("local-context", config.localContext).apply()
                    models.saveChoices(config.localExecution, config.localProfile)
                    localStatus = null; job.start(language, config)
                }.setNegativeButton("Annuler", null).show()
        })
        column.addView(button("Annuler la traduction du SRT — conserver la progression") {
            metadataGeneration++; readingMetadata = false; localStatus = null; job.cancel(); render()
        })
        for (format in SubtitleExportFormat.values()) column.addView(button("Exporter le SRT traduit en ${format.name}") {
            val content = job.completedExport(format)
            if (content == null) message("Termine d’abord la traduction complète de ce SRT.")
            else onExport(format.mimeType, job.exportFileName(format), content)
        })
        column.addView(text("Le SRT exporté est en UTF-8 et conserve les occurrences et les temps. Il peut être utilisé avec une vidéo, ajouté à un MKV avec un outil adapté ou importé dans le lecteur pour vocalisation. Une traduction lancée continue quand ce panneau est fermé. Un import ou une préparation d’empreinte GGUF encore en cours est annulé à la fermeture du panneau. Fermer ce panneau ne lance ni ne reprend la vidéo."))
        column.addView(button("Fermer le panneau et laisser la traduction continuer") { dismissPanel() })
        panel = AlertDialog.Builder(activity).setTitle("Traduire un fichier SRT hors ligne")
            .setView(ScrollView(activity).apply {
                tag = SCROLL_TAG
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Fermer", null).show().also { dialog ->
                dialog.setOnDismissListener { if (owner == panelGeneration) dismissPanel() }
            }
        render()
    }
    fun selectedSubtitle(uri: Uri?) {
        val requested = awaitingSubtitle
        awaitingSubtitle = false
        if (!requested || closed || uri == null || busy || panel?.isShowing != true) return
        if (uri.scheme != "content") { message("Choisis le SRT avec le sélecteur Android."); return }
        val owner = panelGeneration
        val metadataOwner = ++metadataGeneration
        sourceSelection++; readingMetadata = true
        message("Lecture du nom du fichier SRT…")
        metadataWorker.execute {
            val name = runCatching {
                activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getString(0).take(256) else null
                }
            }.getOrNull() ?: "sous-titres.srt"
            main.post {
                if (!closed && owner == panelGeneration && metadataOwner == metadataGeneration && !busy && panel?.isShowing == true) {
                    readingMetadata = false
                    lastSourceUri = uri; lastSourceName = name
                    localStatus = null; job.importSource(uri, name)
                }
            }
        }
    }
    fun selectedModel(uri: Uri?) { modelControls?.selected(uri) }
    private fun dismissPanel() {
        panelGeneration++; metadataGeneration++; awaitingSubtitle = false; readingMetadata = false
        childDialog?.dismiss(); childDialog = null
        modelControls?.close(); modelControls = null
        val old = panel; panel = null; statusView = null; exampleButton = null; localStatus = null
        old?.dismiss()
    }
    override fun close() {
        if (closed) return
        closed = true; dismissPanel(); job.close(); metadataWorker.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
    companion object { internal const val SCROLL_TAG = "local-srt-translation-controls-scroll" }
}
