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
        column.addView(button("Télécharger d’autres moteurs : sources officielles") {
            val labels = arrayOf("SherpaTTS — F-Droid (modèles locaux Piper/Coqui)",
                "Acapela TTS Voices — éditeur (voix payantes possibles)", "Synthèse vocale Google — Google Play")
            val links = arrayOf("https://f-droid.org/packages/org.woheller69.ttsengine/",
                "https://www.acapela-group.com/fr/solutions/acapela-tts-voices-for-google-play/",
                "https://play.google.com/store/apps/details?id=com.google.android.tts")
            AlertDialog.Builder(activity).setTitle("Ouvre le navigateur ; aucun téléchargement automatique")
                .setItems(labels) { _, index -> runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(links[index]))) } }
                .setNegativeButton("Fermer", null).show()
        })
        column.addView(text("Télécharge une voix DANS son moteur, puis choisis ce moteur ici et une voix hors ligne. Un fichier de modèle isolé ne s’installe pas directement dans ce lecteur. La qualité et l’accélération CPU/GPU/NPU dépendent du moteur choisi."))
        column.addView(text("Adaptation, préparation et reprise"))
        number(column, "Accélération maximale du mode adaptatif", "adaptiveMax", 125, 100, 160, 5, "%")
        column.addView(text("Le mode adaptatif utilise la durée de l’audio préparé quand elle est disponible. Sinon, il conserve la vitesse normale et la pause de confort. Aucun mot n’est résumé ou supprimé."))
        check(column, "Préparer les prochaines phrases dans un cache local", "cache", true)
        number(column, "Limite du cache", "cacheMiB", 128, 16, 256, 16, "Mio")
        number(column, "Segments préparés à l’avance", "lookAheadCount", 6, 1, 12, 1, "segments")
        column.addView(button("Préparation étendue : 256 Mio et 12 segments") {
            prefs.putInt("cacheMiB", 256); prefs.putInt("lookAheadCount", 12); prefs.putBool("cache", true)
            notice("Préparation étendue enregistrée. Les tâches anticipées restent suspendues en cas d’état thermique sévère. Rouvre les réglages pour actualiser les valeurs affichées.")
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
