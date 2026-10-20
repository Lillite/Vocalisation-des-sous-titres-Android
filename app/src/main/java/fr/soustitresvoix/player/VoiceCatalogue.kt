package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import org.json.JSONObject
import java.util.Locale

/** Offline, versioned source catalogue. Only a deliberate button opens the browser. */
object VoiceCatalogue {
    fun show(activity: Activity) {
        val doc = runCatching {
            activity.assets.open("voice-catalogue.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse {
            AlertDialog.Builder(activity).setMessage("Catalogue indisponible : aucune installation effectuée.")
                .setPositiveButton("Fermer", null).show(); return
        }
        val entries = doc.getJSONArray("entries")
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        root.addView(TextView(activity).apply { text = "Sources vérifiées le ${doc.getString("verified")}. ${doc.getString("scope")}"; textSize = 17f })
        val search = EditText(activity).apply { hint = "Rechercher : français, local, modèle…"; contentDescription = "Rechercher dans le catalogue vocal"; setSingleLine(true) }
        root.addView(search)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render(query: String) {
            rows.removeAllViews()
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                if (query.isNotBlank() && !item.toString().lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) continue
                rows.addView(Button(activity).apply {
                    text = "${item.getString("name")}\n${item.getString("kind")}"; isAllCaps = false; textSize = 17f; minHeight = 64
                    setOnClickListener {
                        val detail = "${item.getString("kind")}\n\nLangues : ${item.getString("languages")}\n\n${item.getString("status")}\n\nTaille : ${item.getString("size")}\n\n${item.getString("notes")}\n\nSource : ${item.getString("url")}\n\nAprès installation et téléchargement des données, choisir le moteur puis sa voix dans CE lecteur."
                        AlertDialog.Builder(activity).setTitle(item.getString("name")).setMessage(detail)
                            .setPositiveButton("Ouvrir la source officielle") { _, _ ->
                                val uri = Uri.parse(item.getString("url"))
                                if (uri.scheme == "https") try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(activity, "Aucun navigateur disponible", Toast.LENGTH_LONG).show() }
                            }.setNegativeButton("Fermer", null).show()
                    }
                })
            }
            if (rows.childCount == 0) rows.addView(TextView(activity).apply { text = "Aucun résultat pour cette recherche." })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
        val dialog = AlertDialog.Builder(activity).setTitle("Moteurs, modèles et limites").setView(root)
            .setPositiveButton("Fermer", null).create()
        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * .94).toInt(), (activity.resources.displayMetrics.heightPixels * .9).toInt())
    }
}
