package fr.soustitresvoix.player

import android.app.AlertDialog
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Subtitle-only controls. OCR proposals are not injected into the live queue. */
@UnstableApi
internal class SubtitleCoverageControls(
    private val activity: ComponentActivity,
    private val session: PlaybackSession,
    private val openModel: () -> Unit,
    private val exportPartial: (String) -> Unit,
    private val message: (String, Boolean) -> Unit
) {
    private val context = activity.applicationContext
    private val models = OcrModelStore(File(context.filesDir, "ocr-models-v1"))
    private val main = Handler(Looper.getMainLooper())

    fun show() {
        session.pause()
        val choices = arrayOf("Ajouter une piste de panneaux", "Préparer une piste image PGS ou VobSub",
            "Lire le texte incrusté à cet instant", "Échantillonner le texte incrusté sur un intervalle",
            "Importer un modèle OCR local (.traineddata)")
        AlertDialog.Builder(activity).setTitle("Couverture des sous-titres")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> chooseSupplementaryTrack()
                    1 -> prepareImageTrack()
                    2 -> reviewFrame()
                    3 -> chooseFrameInterval()
                    4 -> openModel()
                }
            }.setNegativeButton("Fermer", null).show()
    }

    private fun chooseSupplementaryTrack() {
        val primary = session.primaryTextId()
        if (primary == null || primary == "external-subtitle") {
            message("Sélectionne une piste textuelle intégrée comme piste principale.", true)
            return
        }
        val entries = linkedMapOf<String?, String>(null to "Piste principale seule")
        for (group in session.player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val id = session.subtitleIndexId(format) ?: continue
                if (id == primary || id == "external-subtitle" || isBitmapSubtitle(format)) continue
                val language = format.language?.let { Locale.forLanguageTag(it).getDisplayName(Locale.FRENCH) }
                    ?.ifBlank { null } ?: "langue non indiquée"
                entries[id] = "${format.label ?: "Piste ${entries.size}"} — $language" +
                    if (id == session.supplementaryTextId) " — sélectionnée" else ""
            }
        }
        val generation = session.generation
        val tracks = entries.keys.toList()
        AlertDialog.Builder(activity).setTitle("Piste de panneaux supplémentaire")
            .setItems(entries.values.toTypedArray()) { _, index ->
                if (isCurrent(generation) && session.primaryTextId() == primary)
                    session.setSupplementarySubtitleTrack(tracks[index])
            }.setNegativeButton("Annuler", null).show()
    }

    fun importModel(uri: Uri) {
        runOnWorker("Vérification du modèle OCR local", null, task = {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: throw IllegalArgumentException("Nom de modèle indisponible.")
            models.importModel(name) { requireNotNull(context.contentResolver.openInputStream(uri)) }
        }) { language -> message("Modèle OCR $language installé. Choisis-le pour préparer une piste image.", false) }
    }

    private fun prepareImageTrack() {
        val media = session.media ?: return message("Ouvre d’abord une vidéo.", false)
        val id = session.primaryTextId() ?: return message("Sélectionne d’abord une piste image.", true)
        val format = session.player.currentTracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_TEXT) null
            else (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
        }
        if (format == null || !isBitmapSubtitle(format)) {
            message("Sélectionne une piste image PGS ou VobSub dans Piste de sous-titres.", true)
            return
        }
        val generation = session.generation
        val mediaEndUs = session.player.duration.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000) ?: C.TIME_UNSET
        chooseOcrLanguage(allowManual = true) { language ->
            if (!isCurrent(generation) || id != session.primaryTextId()) return@chooseOcrLanguage
            val indexer = FullSubtitleIndexer(context)
            runOnWorker("Extraction des sous-titres image", generation, indexer::cancel, task = { progress ->
                language?.let(models::open).use { recognizer ->
                    val draft = ImageSubtitleDraft { image -> recognizer?.recognize(image).orEmpty() }
                    indexer.scanImageCues(media, id, draft::accept, progress)
                    draft.finish(mediaEndUs)
                }
            }) { proposals ->
                if (id == session.primaryTextId()) reviewDraft(generation, id, proposals)
            }
        }
    }

    private fun reviewFrame() {
        val media = session.media ?: return message("Ouvre d’abord une vidéo locale.", false)
        val uri = Uri.parse(media.uri)
        if (uri.scheme !in setOf("file", "content", "android.resource")) {
            message("La reconnaissance d’une image du film est disponible sur les fichiers locaux.", true)
            return
        }
        val generation = session.generation
        val positionMs = session.player.currentPosition
        if (positionMs !in 0..Long.MAX_VALUE / 1000) return
        chooseOcrLanguage(allowManual = false) { language ->
            if (!isCurrent(generation) || session.player.currentPosition != positionMs) return@chooseOcrLanguage
            runOnWorker("Reconnaissance locale de l’image", generation, task = {
                models.open(requireNotNull(language)).use { ocr -> recognizeLocalVideoFrame(context, uri, positionMs * 1000, ocr) }
            }) { text ->
                if (session.player.currentPosition == positionMs)
                    editText("Texte incrusté à vérifier — ${formatTime(positionMs)}", text, "Valider et écouter") { reviewed ->
                        if (isCurrent(generation) && session.player.currentPosition == positionMs) session.previewReviewedSubtitle(reviewed)
                    }
            }
        }
    }

    private fun chooseFrameInterval() {
        val media = session.media ?: return message("Ouvre d’abord une vidéo locale.", false)
        val uri = Uri.parse(media.uri)
        val durationMs = session.player.duration
        if (uri.scheme !in setOf("file", "content", "android.resource") || durationMs !in 1..Long.MAX_VALUE / 1000) {
            message("L’échantillonnage nécessite un fichier vidéo local de durée connue.", true)
            return
        }
        val generation = session.generation
        val durationUs = durationMs * 1000
        val positionUs = session.player.currentPosition.coerceIn(0, durationMs - 1) * 1000
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        layout.addView(TextView(activity).apply {
            text = "Des images espacées sont analysées. Des sous-titres courts peuvent manquer et les minutages restent approximatifs. L’export SRT sera partiel."
        })
        val start = numberField(layout, "Début en secondes", seconds(positionUs))
        val end = numberField(layout, "Fin en secondes", seconds(positionUs + minOf(30_000_000L, durationUs - positionUs)))
        val step = numberField(layout, "Pas en millisecondes (100 à 10 000)", "500", decimal = false)
        val dialog = AlertDialog.Builder(activity).setTitle("Échantillonnage OCR local").setView(layout)
            .setPositiveButton("Choisir le modèle", null).setNegativeButton("Annuler", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val interval = readInterval(start, end, durationUs) ?: return@setOnClickListener
            val stepMs = step.text.toString().trim().toLongOrNull()?.takeIf { it in 100..10_000 }
            if (stepMs == null || (interval.second - interval.first - 1) / (stepMs * 1000) >= MAX_OCR_SAMPLES) {
                step.error = "Choisis un pas valide ou réduis l’intervalle (20 000 images maximum)."
                return@setOnClickListener
            }
            dialog.dismiss()
            chooseOcrLanguage(allowManual = false) { language ->
                if (!isCurrent(generation)) return@chooseOcrLanguage
                runOnWorker("Échantillonnage des images vidéo", generation, task = { progress ->
                    models.open(requireNotNull(language)).use { ocr ->
                        recognizeLocalVideoInterval(context, uri, interval.first, interval.second, stepMs * 1000, ocr, progress)
                    }
                }) { proposals ->
                    if (proposals.isEmpty()) message("Aucun texte détecté dans les images échantillonnées. La couverture reste partielle.", false)
                    else reviewDraft(generation, null, proposals, durationUs)
                }
            }
        }
    }

    private fun chooseOcrLanguage(allowManual: Boolean, selected: (String?) -> Unit) {
        val installed = models.languages()
        val choices = installed.map<String, String?> { it } + if (allowManual) listOf(null) else emptyList()
        val builder = AlertDialog.Builder(activity).setTitle("Modèle OCR local")
            .setNeutralButton("Importer un modèle", { _, _ -> openModel() })
            .setNegativeButton("Annuler", null)
        if (choices.isEmpty()) builder.setMessage("Importe un modèle Tesseract .traineddata pour reconnaître les textes sur le téléphone.")
        else builder.setItems(choices.map { it ?: "Saisie manuelle des répliques image" }.toTypedArray()) { _, which -> selected(choices[which]) }
        builder.show()
    }

    private fun reviewDraft(generation: Long, track: String?, proposals: List<ImageSubtitleProposal>, sampledDurationUs: Long? = null) {
        val edited = proposals.toMutableList()
        val reviewed = arrayOfNulls<String>(proposals.size)
        var count = 0
        fun row(index: Int): String {
            val entry = edited[index]
            val text = reviewed[index] ?: entry.suggestion
            val state = if (reviewed[index] == null) "À vérifier" else "Validé"
            return "${formatTime(entry.startUs / 1000)} — $state — ${text.take(180).ifBlank { "Texte à saisir" }}"
        }
        val labels = proposals.indices.map(::row).toMutableList()
        val lines = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val summary = TextView(activity).apply { setPadding(24, 16, 24, 16) }
        layout.addView(summary)
        val list = ListView(activity).apply { adapter = lines; contentDescription = "Répliques extraites à vérifier" }
        layout.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (360 * activity.resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (sampledDurationUs == null) "Vérifier les sous-titres image" else "Vérifier le brouillon OCR partiel")
            .setView(layout).setNegativeButton("Annuler", null)
            .setPositiveButton(if (sampledDurationUs == null) "Joindre la piste validée" else "Exporter le SRT partiel", null).create()
        fun refresh() {
            val instruction = if (sampledDurationUs == null) "Corrige et confirme chaque texte avant de joindre la piste."
                else "Couverture partielle. Corrige les textes et les minutages estimés avant l’export."
            summary.text = "$count / ${proposals.size} répliques validées. $instruction"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = count == proposals.size
        }
        list.setOnItemClickListener { _, _, index, _ ->
            val entry = edited[index]
            val title = "Réplique ${index + 1} — ${formatTime(entry.startUs / 1000)}"
            val update: (String, ImageSubtitleProposal) -> Unit = { text, updated ->
                if (reviewed[index] == null) count++
                reviewed[index] = text
                edited[index] = updated
                labels[index] = row(index)
                lines.notifyDataSetChanged()
                refresh()
            }
            val suggestion = reviewed[index] ?: entry.suggestion
            if (sampledDurationUs == null) editText(title, suggestion, "Valider") { text -> update(text, entry) }
            else editSample(title, entry, suggestion, sampledDurationUs, update)
        }
        dialog.show()
        refresh()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (count != proposals.size || !isCurrent(generation) || (track != null && track != session.primaryTextId())) return@setOnClickListener
            val checked = reviewed.toList()
            dialog.dismiss()
            if (sampledDurationUs != null) {
                runOnWorker("Préparation du SRT partiel", generation, task = {
                    val file = ReviewedImageSubtitles.write(context.cacheDir, edited, checked)
                    try { file.readText(Charsets.UTF_8) } finally { file.delete() }
                }, completed = exportPartial)
                return@setOnClickListener
            }
            runOnWorker("Enregistrement de la piste relue", generation, task = {
                ReviewedImageSubtitles.write(File(context.filesDir, "subtitles"), edited, checked)
            }, discard = { it.delete() }) { file ->
                if (track != session.primaryTextId()) { file.delete(); return@runOnWorker }
                session.attachSubtitle(file.absolutePath, MimeTypes.APPLICATION_SUBRIP)
                message("Piste textuelle relue ajoutée. Le bilan porte sur les répliques extraites et validées de cette piste. Appuie sur Lire.", false)
            }
        }
    }

    private fun editText(title: String, suggestion: String, action: String, reviewed: (String) -> Unit) {
        val text = textField(suggestion)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(text)
            .setPositiveButton(action, null).setNegativeButton("Annuler", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = readText(text) ?: return@setOnClickListener
            dialog.dismiss()
            reviewed(value)
        }
    }

    private fun editSample(title: String, entry: ImageSubtitleProposal, suggestion: String, durationUs: Long,
        reviewed: (String, ImageSubtitleProposal) -> Unit) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val start = numberField(layout, "Début estimé en secondes", seconds(entry.startUs))
        val end = numberField(layout, "Fin estimée en secondes", seconds(entry.endUs))
        val text = textField(suggestion)
        layout.addView(text)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(layout)
            .setPositiveButton("Valider", null).setNegativeButton("Annuler", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val interval = readInterval(start, end, durationUs) ?: return@setOnClickListener
            val value = readText(text) ?: return@setOnClickListener
            dialog.dismiss()
            reviewed(value, entry.copy(startUs = interval.first, endUs = interval.second))
        }
    }

    private fun textField(suggestion: String) = EditText(activity).apply {
        hint = "Texte à vérifier ou à saisir"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(24_000))
        setText(suggestion)
    }

    private fun readText(field: EditText): String? = field.text.toString().trim().takeIf(String::isNotBlank)
        ?: run { field.error = "Saisis le texte de cette occurrence."; null }

    private fun numberField(layout: LinearLayout, label: String, initial: String, decimal: Boolean = true): EditText {
        val field = EditText(activity).apply {
            id = android.view.View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            filters = arrayOf(InputFilter.LengthFilter(24))
            setText(initial)
        }
        layout.addView(TextView(activity).apply { text = label; labelFor = field.id })
        layout.addView(field)
        return field
    }

    private fun readInterval(start: EditText, end: EditText, durationUs: Long): Pair<Long, Long>? {
        fun time(field: EditText) = runCatching {
            field.text.toString().trim().replace(DecimalFormatSymbols.getInstance().decimalSeparator, '.')
                .replace(',', '.').toBigDecimal().movePointRight(6).longValueExact()
        }.getOrNull()
        val from = time(start)
        if (from == null || from !in 0 until durationUs) {
            start.error = "Indique une position comprise dans la vidéo."
            return null
        }
        val to = time(end)
        if (to == null || to !in from + 1..durationUs) {
            end.error = "Indique une position après le début et avant la fin de la vidéo."
            return null
        }
        return from to to
    }

    private fun seconds(us: Long): String = BigDecimal.valueOf(us, 6).stripTrailingZeros().toPlainString()
        .replace('.', DecimalFormatSymbols.getInstance().decimalSeparator)

    private fun <T> runOnWorker(title: String, generation: Long?, cancel: () -> Unit = {},
        task: ((Int) -> Unit) -> T, discard: (T) -> Unit = {}, completed: (T) -> Unit) {
        val cancelled = AtomicBoolean(false)
        val status = TextView(activity).apply { text = "Traitement en cours"; setPadding(24, 16, 24, 16) }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(status).setNegativeButton("Annuler", null).create()
        var finished = false
        val worker = Thread({
            val result = runCatching { task { percent -> main.post {
                if (!cancelled.get() && !finished && isCurrent(generation))
                    status.text = if (percent >= 0) "Extraction : $percent %" else "Extraction en cours"
            } } }
            main.post {
                finished = true
                dialog.dismiss()
                if (cancelled.get() || !isCurrent(generation)) { result.getOrNull()?.let(discard); return@post }
                result.onSuccess(completed).onFailure {
                    message("Traitement OCR indisponible. Vérifie le modèle, le format, les minutages et l’accès au fichier.", true)
                }
            }
        }, "subtitle-ocr")
        fun stop() {
            if (!finished && cancelled.compareAndSet(false, true)) {
                // Closing a data source is allowed to unblock I/O. Tesseract
                // itself stays owned by its worker until the current call ends.
                Thread({ cancel() }, "subtitle-ocr-cancel").start()
                worker.interrupt()
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) { stop(); dialog.dismiss() } }
        dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer); stop() }
        activity.lifecycle.addObserver(observer)
        dialog.show()
        worker.start()
    }

    private fun isCurrent(generation: Long?): Boolean = !activity.isDestroyed && !activity.isFinishing &&
        (generation == null || session.generation == generation)

    private fun formatTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}
