package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.text.method.TextKeyListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import fr.soustitresvoix.core.SubtitleTimeline

/** Transactional timing editor. Preview is explicit; every cancellation path
 * restores the original map. The owner applies timing on its own session thread
 * and keeps playback paused. An expired media ticket must make alive() false. */
class SyncControls(
    private val activity: Activity,
    private val read: () -> SubtitleTimeline,
    private val onApply: (SubtitleTimeline) -> Unit,
    private val currentSourceMs: () -> Long,
    private val preview: () -> Unit = {},
    private val alive: () -> Boolean = { true }
) {
    private data class AnchorRow(val source: EditText, val video: EditText, val view: LinearLayout)
    private data class PortionRow(val start: EditText, val end: EditText, val offset: EditText, val view: LinearLayout)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(6))
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 17f; isAllCaps = false; minHeight = dp(56)
        setOnClickListener { action() }
    }
    private fun number(label: String, value: Long, signed: Boolean): EditText = EditText(activity).apply {
        id = View.generateViewId(); textSize = 20f; setTextColor(Color.WHITE)
        keyListener = TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
        setRawInputType(InputType.TYPE_CLASS_NUMBER or if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
        setSingleLine(true); minHeight = dp(56); contentDescription = label
        setText(value.toString())
    }

    fun show(): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed || !alive()) return null
        val original = read()
        var committed = false
        var previewed = false
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val error = text("").apply {
            visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun problem(message: String) {
            error.text = message; error.visibility = View.VISIBLE
            error.post { error.requestRectangleOnScreen(android.graphics.Rect(0, 0, error.width, error.height), true) }
        }
        column.addView(text("Les valeurs sont en millisecondes : 1 000 ms = 1 seconde. Un décalage positif retarde les sous-titres ou la voix. Prévisualiser applique temporairement les corrections en laissant la vidéo en pause ; Annuler restaure les valeurs d’ouverture."))

        fun offset(label: String, value: Long, limit: Long, presets: List<Long>): EditText {
            column.addView(text(label))
            val input = number(label, value, true)
            column.addView(input)
            val row = LinearLayout(activity)
            fun step(delta: Long) {
                val current = input.text.toString().trim().toLongOrNull()
                if (current == null || current !in -limit..limit) { problem("Saisis un nombre entier dans les limites indiquées."); return }
                input.setText((current + delta).coerceIn(-limit, limit).toString())
                input.announceForAccessibility("$label : ${input.text} millisecondes")
            }
            row.addView(button("− 50 ms") { step(-50) }.apply { contentDescription = "Diminuer $label de 50 millisecondes" }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("+ 50 ms") { step(50) }.apply { contentDescription = "Augmenter $label de 50 millisecondes" }, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(row)
            val choices = LinearLayout(activity)
            presets.forEach { preset ->
                choices.addView(button("$preset ms") { input.setText(preset.toString()) }.apply {
                    contentDescription = "Régler $label à $preset millisecondes"
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            column.addView(choices)
            column.addView(text("Limites : −$limit à +$limit ms."))
            return input
        }
        val subtitleOffset = offset("Décalage global des sous-titres", original.globalOffsetMs,
            SubtitleTimeline.MAX_TIME_MS, listOf(-1000, 0, 1000))
        val narrationOffset = offset("Décalage propre à la narration", original.narrationOffsetMs,
            SubtitleTimeline.MAX_NARRATION_OFFSET_MS, listOf(-250, 0, 250))
        column.addView(text("Dérive régulière"))
        column.addView(text("Indique le temps original du sous-titre et le temps correspondant dans la vidéo, hors décalage global. Deux repères corrigent une dérive régulière. Des repères supplémentaires changent progressivement la pente : le temps est interpolé entre les points. Les deux colonnes doivent augmenter strictement ; aucun événement n’est supprimé."))
        val anchorsColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(anchorsColumn)
        val rows = mutableListOf<AnchorRow>()
        fun addAnchor(anchor: SubtitleTimeline.Anchor) {
            if (rows.size >= SubtitleTimeline.MAX_ANCHORS) { problem("La limite de ${SubtitleTimeline.MAX_ANCHORS} repères est atteinte."); return }
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val source = number("Temps original du sous-titre, en millisecondes", anchor.sourceMs, false)
            val video = number("Temps correspondant dans la vidéo, en millisecondes", anchor.videoMs, false)
            row.addView(text("Temps original du sous-titre (ms)").apply { labelFor = source.id }); row.addView(source)
            row.addView(text("Temps correspondant dans la vidéo (ms)").apply { labelFor = video.id }); row.addView(video)
            val item = AnchorRow(source, video, row)
            row.addView(button("Retirer ce repère") { rows.remove(item); anchorsColumn.removeView(row) })
            rows += item; anchorsColumn.addView(row)
        }
        original.anchors.forEach(::addAnchor)
        column.addView(button("Ajouter deux repères pour corriger la dérive") {
            if (rows.isNotEmpty()) { problem("Les repères existent déjà. Modifie-les ou ajoute un point pour une portion."); return@button }
            val current = currentSourceMs().coerceIn(1000, SubtitleTimeline.MAX_TIME_MS)
            addAnchor(SubtitleTimeline.Anchor(0, 0)); addAnchor(SubtitleTimeline.Anchor(current, current))
        })
        column.addView(button("Ajouter un repère pour une portion") {
            val last = rows.lastOrNull()
            if (last == null) {
                val current = currentSourceMs().coerceIn(1000, SubtitleTimeline.MAX_TIME_MS)
                addAnchor(SubtitleTimeline.Anchor(0, 0)); addAnchor(SubtitleTimeline.Anchor(current, current))
            } else {
                val source = last.source.text.toString().toLongOrNull()
                val video = last.video.text.toString().toLongOrNull()
                if (source == null || video == null || source > SubtitleTimeline.MAX_TIME_MS - 60_000 || video > SubtitleTimeline.MAX_TIME_MS - 60_000) {
                    problem("Corrige les temps du dernier repère avant d’en ajouter un autre.")
                } else addAnchor(SubtitleTimeline.Anchor(source + 60_000, video + 60_000))
            }
        })
        column.addView(button("Retirer tous les repères de dérive") { rows.clear(); anchorsColumn.removeAllViews() })
        column.addView(text("Portions pour scènes ajoutées ou supprimées"))
        column.addView(text("Pour chaque portion, saisis son début inclus et sa fin exclue dans la piste originale, puis le décalage ajouté après la dérive et le décalage global. Une valeur positive retarde, une valeur négative avance cette portion. Les portions doivent être ordonnées, sans chevauchement dans la source. Des chevauchements dans la vidéo restent possibles : toutes les répliques sont conservées et la voix peut arrêter l’image. Une réplique qui traverse une limite conserve le décalage de son début et toute sa durée. Cette correction ne supprime jamais les textes d’une scène absente ; vérifie le montage à l’écoute."))
        val portionsColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(portionsColumn)
        val portionRows = mutableListOf<PortionRow>()
        fun addPortion(portion: SubtitleTimeline.Portion) {
            if (portionRows.size >= SubtitleTimeline.MAX_PORTIONS) {
                problem("La limite de ${SubtitleTimeline.MAX_PORTIONS} portions est atteinte."); return
            }
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val start = number("Début de la portion originale, en millisecondes", portion.startMs, false)
            val end = number("Fin de la portion originale, en millisecondes", portion.endMs, false)
            val offset = number("Décalage de cette portion, en millisecondes", portion.offsetMs, true)
            row.addView(text("Début original inclus (ms)").apply { labelFor = start.id }); row.addView(start)
            row.addView(text("Fin originale exclue (ms)").apply { labelFor = end.id }); row.addView(end)
            row.addView(text("Décalage ajouté à cette portion (ms)").apply { labelFor = offset.id }); row.addView(offset)
            val steps = LinearLayout(activity)
            fun step(delta: Long) {
                val value = offset.text.toString().trim().toLongOrNull()
                if (value == null || value !in -SubtitleTimeline.MAX_TIME_MS..SubtitleTimeline.MAX_TIME_MS) {
                    problem("Saisis un décalage entier dans les limites indiquées."); return
                }
                offset.setText((value + delta).coerceIn(-SubtitleTimeline.MAX_TIME_MS, SubtitleTimeline.MAX_TIME_MS).toString())
                offset.announceForAccessibility("Décalage de cette portion : ${offset.text} millisecondes")
            }
            steps.addView(button("− 50 ms") { step(-50) }.apply {
                contentDescription = "Avancer cette portion de 50 millisecondes"
            }, LinearLayout.LayoutParams(0, -2, 1f))
            steps.addView(button("+ 50 ms") { step(50) }.apply {
                contentDescription = "Retarder cette portion de 50 millisecondes"
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(steps)
            val item = PortionRow(start, end, offset, row)
            row.addView(button("Retirer cette portion") { portionRows.remove(item); portionsColumn.removeView(row) })
            portionRows += item; portionsColumn.addView(row)
        }
        original.portions.forEach(::addPortion)
        column.addView(button("Ajouter une portion avec décalage") {
            val lastEnd = portionRows.lastOrNull()?.end?.text?.toString()?.trim()?.toLongOrNull()
            if (portionRows.isNotEmpty() && lastEnd == null) {
                problem("Corrige la fin de la dernière portion avant d’en ajouter une autre.")
            } else {
                val start = maxOf(currentSourceMs().coerceIn(0, SubtitleTimeline.MAX_TIME_MS - 1), lastEnd ?: 0)
                if (start >= SubtitleTimeline.MAX_TIME_MS) problem("La dernière portion atteint déjà la limite de temps.")
                else addPortion(SubtitleTimeline.Portion(start, (start + 60_000).coerceAtMost(SubtitleTimeline.MAX_TIME_MS), 0))
            }
        })
        column.addView(button("Retirer toutes les portions avec décalage") { portionRows.clear(); portionsColumn.removeAllViews() })
        column.addView(error)
        lateinit var dialog: AlertDialog
        fun readDraft(): SubtitleTimeline? = try {
            fun parse(input: EditText): Long {
                val raw = input.text.toString().trim()
                require(raw.length <= 16 && raw.matches(Regex("[+-]?\\d+"))) { "Les temps doivent être des nombres entiers en millisecondes." }
                return raw.toLongOrNull() ?: throw IllegalArgumentException("Une valeur est trop grande.")
            }
            SubtitleTimeline(parse(subtitleOffset), parse(narrationOffset), rows.map { SubtitleTimeline.Anchor(parse(it.source), parse(it.video)) },
                portionRows.map { SubtitleTimeline.Portion(parse(it.start), parse(it.end), parse(it.offset)) })
        } catch (failure: IllegalArgumentException) { problem(failure.message ?: "Correction invalide."); null }
        fun submit(): Boolean {
            if (!alive()) { problem("La vidéo ou la piste a changé. Ferme ce panneau et rouvre la synchronisation."); return false }
            val draft = readDraft() ?: return false
            return try {
                onApply(draft); previewed = true; error.visibility = View.GONE; true
            } catch (_: Exception) { problem("La correction n’a pas pu être appliquée. La vidéo reste en pause."); false }
        }
        column.addView(button("Prévisualiser sur la réplique courante — laisse en pause") {
            if (submit()) preview()
        })
        column.addView(button("Rétablir les valeurs d’ouverture") {
            subtitleOffset.setText(original.globalOffsetMs.toString()); narrationOffset.setText(original.narrationOffsetMs.toString())
            rows.clear(); anchorsColumn.removeAllViews(); original.anchors.forEach(::addAnchor)
            portionRows.clear(); portionsColumn.removeAllViews(); original.portions.forEach(::addPortion)
            if (alive()) { onApply(original); previewed = false }
        })
        fun rollback() {
            if (!committed && previewed) {
                previewed = false
                if (alive()) onApply(original)
            }
        }
        // Dialog's standard OnCancel/OnDismiss listeners are delivered through
        // Handler messages. Restore synchronously so a caller closing this
        // preview can never observe its temporary timing as committed state.
        dialog = object : AlertDialog(activity) {
            override fun cancel() { rollback(); super.cancel() }
            override fun dismiss() { rollback(); super.dismiss() }
        }.apply {
            setTitle("Synchronisation précise")
            setView(ScrollView(activity).apply { addView(column) })
            setButton(AlertDialog.BUTTON_POSITIVE, "Conserver les corrections") { _, _ -> }
            setButton(AlertDialog.BUTTON_NEGATIVE, "Annuler") { _, _ -> }
        }
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
        owner?.lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            owner?.lifecycle?.removeObserver(observer)
            rollback()
        }
        dialog.setOnCancelListener { rollback() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { rollback(); dialog.dismiss() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (submit()) { committed = true; dialog.dismiss() }
        }
        return dialog
    }
}
