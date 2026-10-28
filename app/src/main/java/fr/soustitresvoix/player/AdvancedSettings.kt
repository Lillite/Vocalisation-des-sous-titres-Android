package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.util.UnstableApi
import fr.soustitresvoix.core.SpeechTools

@UnstableApi
class AdvancedSettings(private val activity: Activity, private val session: PlaybackSession, private val prefs: PlayerPreferences,
    private val startBenchmark: (Int) -> Unit = { session.startPlaybackBenchmark(it) }) {
    private fun beforePreferenceChange(key: String) {
        if (key in setOf("cache", "cacheMiB", "lookAheadCount", "lookAheadSeconds", "skipSpeakers", "skipSongs", "skipNotes")) {
            session.beforeSettingsChange()
        }
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(value: String, click: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 17f; minHeight = dp(56); setOnClickListener { click() }
    }
    private fun check(column: LinearLayout, label: String, key: String, default: Boolean = false) {
        val control = CheckBox(activity).apply {
            text = label; textSize = 18f; minHeight = dp(56); isChecked = prefs.bool(key, default)
            isEnabled = key !in setOf("skipSpeakers", "skipSongs", "skipNotes") || !prefs.bool("integralReading", true)
            setOnCheckedChangeListener { _, value -> beforePreferenceChange(key); prefs.putBool(key, value); session.refreshSettings() }
        }
        column.addView(control)
        numericRefreshers += {
            control.isEnabled = key !in setOf("skipSpeakers", "skipSongs", "skipNotes") || !prefs.bool("integralReading", true)
            val desired = prefs.bool(key, default)
            if (control.isChecked != desired) {
                control.setOnCheckedChangeListener(null); control.isChecked = desired
                control.setOnCheckedChangeListener { _, value -> beforePreferenceChange(key); prefs.putBool(key, value); session.refreshSettings() }
            }
        }
    }
    private fun number(column: LinearLayout, label: String, key: String, default: Int, min: Int, max: Int, step: Int, unit: String) {
        val presets = when (key) {
            "adaptiveMax" -> listOf(100, 125, 150, 180, 200)
            "cacheMiB" -> listOf(64, 512, 2048)
            "lookAheadCount" -> listOf(32, 256, 1024)
            "lookAheadSeconds" -> listOf(15, 120, 300)
            "allowedLagMs" -> listOf(0, 600, 1200)
            else -> listOf(default)
        }
        val control = SettingsNumberControl(activity, label, min, max, step, unit,
            { prefs.int(key, default, min, max) },
            { value -> beforePreferenceChange(key); prefs.putInt(key, value); session.refreshSettings() }, presets, slider = false)
        column.addView(control)
        numericRefreshers += control::refresh
    }
    private val numericRefreshers = mutableListOf<() -> Unit>()
    private fun options(title: String, labels: Array<String>, key: String, default: Int) {
        AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(labels, prefs.int(key, default, 0, labels.lastIndex)) { dialog, index ->
            prefs.putInt(key, index); session.refreshSettings(); dialog.dismiss()
        }.setNegativeButton("Fermer", null).show()
    }
    fun populate(column: LinearLayout) {
        val panel = SettingsPanel(activity)
        populate(panel)
        column.addView(panel, LinearLayout.LayoutParams(-1, 600))
    }
    internal fun populate(panel: SettingsPanel) {
        numericRefreshers.clear()
        val legacy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val column = legacy
        val boundaries = mutableListOf<Pair<Int, SettingsSection>>()
        fun section(value: SettingsSection) { boundaries += column.childCount to value }
        section(SettingsSection.VOICE)
        val audioGeneration = session.audioCommands.snapshot().context.generation
        column.addView(text("Moteurs et voix naturelles"))
        column.addView(button("Choisir le moteur vocal de CE lecteur") {
            val engines = session.speech.engines()
            val names = arrayOf("Moteur par défaut du téléphone") + engines.map { "${it.label} — ${it.name}" }
            AlertDialog.Builder(activity).setTitle("Ne modifie pas le moteur de TalkBack")
                .setItems(names) { _, index ->
                    session.beforeSettingsChange()
                    prefs.enginePackage = if (index == 0) "" else engines[index - 1].name
                    session.refreshSettings()
                }.setNegativeButton("Annuler", null).show()
        })
        column.addView(button("Ouvrir le moteur choisi pour installer ses voix") {
            val pkg = prefs.enginePackage
            val intent = if (pkg.isNotEmpty()) activity.packageManager.getLaunchIntentForPackage(pkg) else null
            runCatching { activity.startActivity(intent ?: Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { notice("Ouvre le moteur vocal depuis la liste des applications Android.") }
        })
        column.addView(button("Actualiser les moteurs et les voix") { session.beforeSettingsChange(); session.pause(); session.speech.retry() })
        column.addView(button("Catalogue des moteurs et modèles — sources vérifiées") { VoiceCatalogue.show(activity) })

        column.addView(text("Télécharge une voix DANS son moteur, puis choisis ce moteur ici et une voix hors ligne. Un fichier de modèle isolé ne s’installe pas directement dans ce lecteur. La qualité et l’accélération CPU/GPU/NPU dépendent du moteur choisi."))
        section(SettingsSection.SYNCHRONIZATION)
        column.addView(text("Adaptation, préparation et reprise"))
        number(column, "Accélération maximale du mode adaptatif", "adaptiveMax", 180, 100, 250, 5, "%")
        column.addView(text("Adaptatif : durée mesurée pour le cache ; estimation calibrée en lecture directe. Budget partagé entre les segments, accélération plafonnée et retard maximal choisi. Les pauses de ponctuation ne sont pas supprimées. Une pause reste possible au-delà du plafond."))
        section(SettingsSection.STORAGE)
        check(column, "Préparer les prochaines phrases dans un cache local", "cache", true)
        number(column, "Limite du cache", "cacheMiB", 512, 16, 8192, 64, "Mio")
        number(column, "Segments préparés à l’avance", "lookAheadCount", 256, 1, 4096, 16, "segments")
        number(column, "Budget d’audio anticipé estimé", "lookAheadSeconds", 120, 5, 7200, 5, "s")

        column.addView(button("Préparer les 5 prochaines minutes") { session.prepareRange(300) })
        column.addView(button("Indexer et préparer toute la vidéo") {
            AlertDialog.Builder(activity).setTitle("Préparation locale complète")
                .setMessage("La vidéo sera mise en pause. Une lecture séquentielle du fichier est nécessaire pour indexer les pistes intégrées, sans décoder la vidéo. Le cache reste limité : s’il est trop petit, les anciens segments sont évincés. L’écran doit rester ouvert. Aucun fichier personnel n’est envoyé.")
                .setPositiveButton("Lancer") { _, _ -> session.prepareRange(null) }.setNegativeButton("Annuler",null).show()
        })
        column.addView(button("État de l’indexation et de la préparation") { notice("${session.indexingStatus}\n${session.speech.preparationStatus}") })
        column.addView(button("Annuler la préparation") { session.cancelPreparation() })
        column.addView(button("Choisir le profil de préparation") {
            AlertDialog.Builder(activity).setTitle("Paramètres réels — aucun NPU activé artificiellement")
                .setItems(arrayOf("Économie : 15 s, 64 Mio, 32 segments max", "Équilibré : 120 s, 512 Mio, 256 segments max", "Intensif : 300 s, 2 048 Mio, 1 024 segments max")) { _, i ->
                    session.beforeSettingsChange()
                    prefs.putInt("performanceProfile",i); prefs.putBool("cache",true)
                    prefs.putInt("lookAheadSeconds",intArrayOf(15,120,300)[i]); prefs.putInt("cacheMiB",intArrayOf(64,512,2048)[i])
                    prefs.putInt("lookAheadCount",intArrayOf(32,256,1024)[i]); numericRefreshers.toList().forEach { it() }; panel.refresh()
                    session.refreshSettings(); notice("Profil enregistré. Les valeurs sont actualisées dans ce panneau. Le traitement vocal externe et ses processeurs restent gérés par ce moteur.")
                }.setNegativeButton("Fermer",null).show()
        })
        column.addView(button("Modèle du moteur mis à jour : invalider les anciennes voix préparées") {
            session.beforeSettingsChange()
            prefs.putInt("modelRevision",prefs.int("modelRevision",0,0,99999)+1); session.refreshSettings()
            notice("Nouvelle identité de cache. À utiliser lorsqu’un modèle est remplacé DANS un moteur sans mise à jour de son application ; Android ne fournit pas forcément l’identité de ce modèle interne.")
        })
        column.addView(button("Vider le cache vocal") { session.beforeSettingsChange(); session.pause(); session.speech.clearCache(); notice("Nettoyage du cache demandé. Les vidéos originales ne sont pas modifiées.") })
        column.addView(button("Préparation conservée et stockage hors ligne") { session.showOfflineStorage(activity) })
        section(SettingsSection.SYNCHRONIZATION)
        number(column, "Retard vocal maximal après le sous-titre", "allowedLagMs", 600, 0, 2000, 100, "ms")
        column.addView(button("Décalage, dérive et calibration des sous-titres") { session.showSynchronization(activity) })
        column.addView(button("Reprise de séance : vidéo et narration") { session.showResumeSession(activity) })
        column.addView(button("Choisir la reprise après Pause") {
            options("Reprise vocale", arrayOf("Reprendre la réplique entière", "Reprendre le segment de phrase interrompu",
                "Attendre le bouton Reprendre la voix"), "resumePolicy", 0)
        })
        column.addView(text("Les segments sont des phrases ou des morceaux longs à une frontière de mot. La reprise n’essaie pas de deviner un mot à partir du temps écoulé."))
        column.addView(button("Reprendre explicitement la voix et la vidéo") { session.resumeVoice() })
        section(SettingsSection.SOUND)
        column.addView(text("Mélange sonore"))
        // Entries are moved out of the temporary column below. Nested audio
        // dialogs must borrow the actual settings panel's attached lifetime.
        column.addView(AudioDspControls.openButton(activity, panel, session.audioCommands, audioGeneration))
        column.addView(AudioSettingsControls(activity, session.audioCommands, includeLevels = false).view)
        section(SettingsSection.SUBTITLES)
        column.addView(text("Filtres facultatifs — désactivés en Lecture intégrale ; l’affichage original reste intact"))
        check(column, "Ignorer les étiquettes MAJUSCULES suivies de deux-points", "skipSpeakers")
        check(column, "Ignorer les lignes commençant par ♪ ou ♫", "skipSongs")
        check(column, "Ignorer les notes explicitement marquées NDT / note du traducteur", "skipNotes")
        column.addView(text("Ces règles sont textuelles, pas une identification des personnages. Elles peuvent retirer une ligne utile : conserve-les désactivées sauf besoin précis. Le filtre des crochets se règle plus haut."))
        column.addView(button("Aperçu des filtres : original et texte vocalisé") {
            val sample = session.lastRawCaption.ifBlank { "ALICE: Bonjour.\n[musique]\n♪ Une chanson\nNDT: Note du traducteur." }
            notice("ORIGINAL\n$sample\n\nVOCALISÉ\n${SpeechTools.filter(sample, prefs.filters).ifBlank { "(aucun texte)" }}")
        })
        section(SettingsSection.IMAGE)
        column.addView(text("Plein écran et gestes"))
        number(column, "Masquage automatique des commandes (0 = jamais)", "controlsTimeout", 5, 0, 30, 1, "s")
        column.addView(button("Orientation au prochain passage en plein écran") {
            options("Orientation", arrayOf("Suivre le téléphone", "Paysage", "Verrouiller l’orientation courante"), "orientation", 1)
        })
        check(column, "Double toucher et glissement horizontal : déplacement temporel", "gestureSeek", true)
        check(column, "Glissement vertical : luminosité à gauche, son du film à droite", "gestureLevels", true)
        check(column, "Pincement pour zoomer, puis glissement pour déplacer l’image", "gestureZoom", true)
        column.addView(text("Avec l’exploration tactile TalkBack, ces gestes sont désactivés et les commandes ne disparaissent pas automatiquement. Retour déverrouille d’abord, puis quitte le plein écran. Le mode « Image entière, proportions originales » conserve le cadre."))
        check(column, "Adapter la cadence d’affichage si transition sans coupure disponible", "matchFrameRate", true)
        section(SettingsSection.DIAGNOSTIC)
        column.addView(button("Décodeurs disponibles / secours logiciel") { CodecTools.show(activity, session) })
        column.addView(button("Bilan de vocalisation : occurrences, erreurs et intégrité") { session.showNarrationReport(activity) })
        column.addView(text("Banc d’essai : synthèse et lecture réelle"))
        column.addView(button("Préparation vocale : six textes reproductibles") { session.benchmark() })
        column.addView(button("Banc d’essai de lecture : 2 ou 30 minutes") {
            AlertDialog.Builder(activity).setTitle("Mesurer le même extrait de vidéo")
                .setMessage("L’essai commence à la position actuelle. Il mesure la lecture réelle avec tes réglages : latences vocales, synthèse, cache, pauses, images abandonnées et CPU du lecteur. Les pauses prolongent le temps réel. Le lecteur s’arrête à la fin de l’extrait. Une pause, un déplacement ou un changement de réglage interrompt l’essai et conserve ses résultats. Pour comparer, utilise le même fichier, la même piste et la même position ; distingue cache froid et cache déjà préparé.")
                .setPositiveButton("Court : 2 minutes") { _, _ -> startBenchmark(120) }
                .setNeutralButton("Prolongé : 30 minutes") { _, _ -> startBenchmark(1800) }
                .setNegativeButton("Annuler", null).show()
        })
        column.addView(button("Revenir au début du dernier extrait mesuré") { session.replayPlaybackBenchmark() })
        column.addView(button("Terminer l’essai et garder ses mesures") { session.stopPlaybackBenchmark() })
        column.addView(button("Résultats des essais de lecture — copier ou consulter") {
            val report = session.playbackBenchmarkReport()
            AlertDialog.Builder(activity).setTitle("Mesures de l’extrait, sans télémétrie")
                .setView(ScrollView(activity).apply { addView(text(report)) })
                .setNeutralButton("Copier") { _, _ ->
                    activity.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Essais de lecture", report))
                    notice("Résultats copiés à ta demande.")
                }.setPositiveButton("Fermer", null).show()
        })
        column.addView(button("Copier le diagnostic complet") {
            val clipboard=activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic local",session.diagnostics()))
            notice("Diagnostic copié à ta demande ; aucun dialogue ou nom de fichier inclus.")
        })
        column.addView(button("Mesures réelles de cette session") { showDiagnostics() })
        val children = (0 until column.childCount).map { column.getChildAt(it) }
        children.forEachIndexed { index, view ->
            val target = boundaries.last { it.first <= index }.second
            column.removeView(view)
            panel.add(target, searchableText(view), view, aliases(target, searchableText(view)))
        }
        panel.onRefresh { numericRefreshers.toList().forEach { it() } }
    }
    private fun searchableText(view: android.view.View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { searchableText(view.getChildAt(it)) }
        else -> view.contentDescription?.toString().orEmpty()
    }
    private fun aliases(section: SettingsSection, title: String): String = when {
        "cache" in title.lowercase() -> "stockage espace mémoire audio préparé"
        "adaptatif" in title.lowercase() -> "vitesse plafond accélération débit synchronisation"
        "reprise" in title.lowercase() -> "reprendre interruption narration séance"
        "décalage" in title.lowercase() -> "synchronisation avance retard dérive timing"
        "orientation" in title.lowercase() -> "portrait paysage rotation affichage"
        "masquage" in title.lowercase() -> "délai commandes écran interface"
        "gestes" in title.lowercase() -> "toucher glissement zoom"
        section == SettingsSection.DIAGNOSTIC -> "diagnostic"
        else -> ""
    }
    private fun notice(message: String) {
        AlertDialog.Builder(activity).setMessage(message).setPositiveButton("Fermer", null).show()
    }
    private fun showDiagnostics() {
        val box = text(session.diagnostics())
        AlertDialog.Builder(activity).setTitle("Mesures locales — pas de télémétrie")
            .setView(ScrollView(activity).apply { addView(box) }).setNeutralButton("Actualiser") { _, _ -> showDiagnostics() }
            .setPositiveButton("Fermer", null).show()
    }
}
