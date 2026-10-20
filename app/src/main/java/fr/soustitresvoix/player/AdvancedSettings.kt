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
class AdvancedSettings(private val activity: Activity, private val session: PlaybackSession, private val prefs: PlayerPreferences) {
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(value: String, click: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 17f; minHeight = dp(56); setOnClickListener { click() }
    }
    private fun check(column: LinearLayout, label: String, key: String, default: Boolean = false) {
        column.addView(CheckBox(activity).apply {
            text = label; textSize = 18f; minHeight = dp(56); isChecked = prefs.bool(key, default)
            setOnCheckedChangeListener { _, value -> prefs.putBool(key, value) }
        })
    }
    private fun number(column: LinearLayout, label: String, key: String, default: Int, min: Int, max: Int, step: Int, unit: String) {
        val caption = text(""); column.addView(caption)
        fun refresh() { caption.text = "$label : ${prefs.int(key, default, min, max)} $unit" }
        val row = LinearLayout(activity)
        fun control(sign: String, delta: Int) {
            row.addView(button("$sign $step $unit") { prefs.putInt(key, (prefs.int(key, default, min, max) + delta).coerceIn(min, max)); refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        control("−", -step); control("+", step); column.addView(row); refresh()
    }
    private fun options(title: String, labels: Array<String>, key: String, default: Int) {
        AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(labels, prefs.int(key, default, 0, labels.lastIndex)) { dialog, index ->
            prefs.putInt(key, index); dialog.dismiss()
        }.setNegativeButton("Fermer", null).show()
    }
    fun populate(column: LinearLayout) {
        column.addView(text("Moteurs et voix naturelles"))
        column.addView(button("Choisir le moteur vocal de CE lecteur") {
            val engines = session.speech.engines()
            val names = arrayOf("Moteur par défaut du téléphone") + engines.map { "${it.label} — ${it.name}" }
            AlertDialog.Builder(activity).setTitle("Ne modifie pas le moteur de TalkBack")
                .setItems(names) { _, index ->
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
        column.addView(button("Actualiser les moteurs et les voix") { session.pause(); session.speech.retry() })
        column.addView(button("Catalogue des moteurs et modèles — sources vérifiées") { VoiceCatalogue.show(activity) })
        column.addView(button("Décodeurs disponibles / secours logiciel") { CodecTools.show(activity, session) })
        column.addView(text("Télécharge une voix DANS son moteur, puis choisis ce moteur ici et une voix hors ligne. Un fichier de modèle isolé ne s’installe pas directement dans ce lecteur. La qualité et l’accélération CPU/GPU/NPU dépendent du moteur choisi."))
        column.addView(text("Adaptation, préparation et reprise"))
        number(column, "Accélération maximale du mode adaptatif", "adaptiveMax", 180, 100, 250, 5, "%")
        column.addView(text("Adaptatif : durée mesurée pour le cache ; estimation calibrée en lecture directe. Budget partagé entre les segments, accélération plafonnée et retard maximal choisi. Les pauses de ponctuation ne sont pas supprimées. Une pause reste possible au-delà du plafond."))
        check(column, "Préparer les prochaines phrases dans un cache local", "cache", true)
        number(column, "Limite du cache", "cacheMiB", 256, 16, 2048, 64, "Mio")
        number(column, "Segments préparés à l’avance", "lookAheadCount", 64, 1, 512, 16, "segments")
        number(column, "Budget d’audio anticipé estimé", "lookAheadSeconds", 45, 5, 600, 5, "s")
        number(column, "Retard vocal maximal après le sous-titre", "allowedLagMs", 600, 0, 2000, 100, "ms")
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
                .setItems(arrayOf("Économie : 15 s, 64 Mio, 32 segments max", "Équilibré : 45 s, 256 Mio, 128 segments max", "Intensive : 180 s, 1 024 Mio, 512 segments max")) { _, i ->
                    prefs.putInt("performanceProfile",i); prefs.putBool("cache",true)
                    prefs.putInt("lookAheadSeconds",intArrayOf(15,45,180)[i]); prefs.putInt("cacheMiB",intArrayOf(64,256,1024)[i])
                    prefs.putInt("lookAheadCount",intArrayOf(32,128,512)[i]); notice("Profil enregistré. Rouvre ce panneau pour actualiser les nombres. Le traitement vocal externe et ses processeurs restent gérés par ce moteur.")
                }.setNegativeButton("Fermer",null).show()
        })
        column.addView(button("Modèle du moteur mis à jour : invalider les anciennes voix préparées") {
            prefs.putInt("modelRevision",prefs.int("modelRevision",0,0,99999)+1); session.refreshSettings()
            notice("Nouvelle identité de cache. À utiliser lorsqu’un modèle est remplacé DANS un moteur sans mise à jour de son application ; Android ne fournit pas forcément l’identité de ce modèle interne.")
        })
        column.addView(button("Vider le cache vocal") { session.pause(); session.speech.clearCache(); notice("Nettoyage du cache demandé. Les vidéos originales ne sont pas modifiées.") })
        column.addView(button("Choisir la reprise après Pause") {
            options("Reprise vocale", arrayOf("Reprendre la réplique entière", "Reprendre le segment de phrase interrompu",
                "Attendre le bouton Reprendre la voix"), "resumePolicy", 0)
        })
        column.addView(text("Les segments sont des phrases ou des morceaux longs à une frontière de mot. La reprise n’essaie pas de deviner un mot à partir du temps écoulé."))
        column.addView(button("Reprendre explicitement la voix et la vidéo") { session.resumeVoice() })
        column.addView(text("Mélange sonore"))
        number(column, "Durée de baisse du film", "duckAttack", 160, 0, 2000, 40, "ms")
        number(column, "Durée de remontée du film", "duckRelease", 500, 0, 2000, 100, "ms")
        number(column, "Attente avant remontée entre deux répliques", "duckHold", 350, 0, 2000, 50, "ms")
        check(column, "Expérimental : atténuer surtout la bande 250–3 200 Hz du film pendant la voix (PCM16)", "spectralDucking")
        column.addView(text("Ce filtre modifie aussi la musique : ce n’est pas une séparation des dialogues. Désactivé par défaut. Comparer à la baisse uniforme avant de le garder."))
        column.addView(text("Filtres facultatifs — l’affichage original reste intact"))
        check(column, "Ignorer les étiquettes MAJUSCULES suivies de deux-points", "skipSpeakers")
        check(column, "Ignorer les lignes commençant par ♪ ou ♫", "skipSongs")
        check(column, "Ignorer les notes explicitement marquées NDT / note du traducteur", "skipNotes")
        column.addView(text("Ces règles sont textuelles, pas une identification des personnages. Elles peuvent retirer une ligne utile : conserve-les désactivées sauf besoin précis. Le filtre des crochets se règle plus haut."))
        column.addView(button("Aperçu des filtres : original et texte vocalisé") {
            val sample = session.lastRawCaption.ifBlank { "ALICE: Bonjour.\n[musique]\n♪ Une chanson\nNDT: Note du traducteur." }
            notice("ORIGINAL\n$sample\n\nVOCALISÉ\n${SpeechTools.filter(sample, prefs.filters).ifBlank { "(aucun texte)" }}")
        })
        column.addView(text("Plein écran et gestes"))
        number(column, "Masquage automatique des commandes (0 = jamais)", "controlsTimeout", 5, 0, 30, 1, "s")
        column.addView(button("Orientation au prochain passage en plein écran") {
            options("Orientation", arrayOf("Suivre le téléphone", "Paysage", "Verrouiller l’orientation courante"), "orientation", 1)
        })
        check(column, "Double toucher et glissement horizontal : déplacement temporel", "gestureSeek", true)
        check(column, "Glissement vertical : luminosité à gauche, son du film à droite", "gestureLevels", true)
        check(column, "Pincement pour zoomer, puis glissement pour déplacer l’image", "gestureZoom", true)
        column.addView(text("Avec l’exploration tactile TalkBack, ces gestes sont désactivés et les commandes ne disparaissent pas automatiquement. Retour déverrouille d’abord, puis quitte le plein écran. Le mode Adapter conserve l’image entière."))
        check(column, "Adapter la cadence d’affichage si transition sans coupure disponible", "matchFrameRate", true)
        column.addView(button("Banc d’essai local : six extraits reproductibles") { session.benchmark() })
        column.addView(button("Copier le diagnostic complet") {
            val clipboard=activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostic local",session.diagnostics()))
            notice("Diagnostic copié à ta demande ; aucun dialogue ou nom de fichier inclus.")
        })
        column.addView(button("Mesures réelles de cette session") { showDiagnostics() })
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
