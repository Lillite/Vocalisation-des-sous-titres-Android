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
    private fun openSource(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" && !uri.host.isNullOrBlank()) try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(activity, "Aucun navigateur disponible", Toast.LENGTH_LONG).show() }
    }
    fun show(activity: Activity) {
        val doc = runCatching {
            activity.assets.open("voice-catalogue.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrElse {
            AlertDialog.Builder(activity).setMessage("Catalogue indisponible : aucune installation effectuée.")
                .setPositiveButton("Fermer", null).show(); return
        }
        val entries = doc.getJSONArray("entries")
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        root.addView(TextView(activity).apply { text = "${entries.length()} notices — sources consultées le ${doc.getString("verified")}."; textSize = 17f })
        root.addView(Button(activity).apply {
            text = "Comprendre les catégories et les limites"; isAllCaps = false; minHeight = dp(48)
            setOnClickListener { AlertDialog.Builder(activity).setTitle("À propos du catalogue").setMessage(doc.getString("scope"))
                .setPositiveButton("Fermer",null).show() }
        })
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
                    text = "${item.getString("name")}\n${item.getString("kind")}"; isAllCaps = false; textSize = 17f; minHeight = dp(56)
                    setOnClickListener {
                        val installation = item.optString("installation").ifBlank {
                            when (item.optString("category")) {
                                "model" -> "Ce modèle nécessite un moteur compatible ; ce fichier ne s’installe pas comme une application Android."
                                "demo", "source", "historical", "discontinued", "vendor_offer" -> "Vérifier la distribution et ses limites : cette notice ne garantit pas un moteur installable aujourd’hui."
                                else -> "Installer le moteur et ses données, puis choisir une voix utilisable hors ligne dans ce lecteur."
                            }
                        }
                        val detail = "${item.getString("kind")}\n\nLangues : ${item.getString("languages")}\n\n${item.getString("status")}\n\nDistribution : ${item.optString("distribution", "Consulter la source")}\n\nTaille : ${item.getString("size")}\n\n${item.getString("notes")}\n\nInstallation : $installation\n\nSource : ${item.getString("url")}"
                        val sources = mutableListOf(item.getString("url"))
                        item.optJSONArray("sources")?.let { array -> for (j in 0 until array.length()) {
                            val url = array.optString(j); if (url.startsWith("https://") && url !in sources) sources += url
                        } }
                        val details = AlertDialog.Builder(activity).setTitle(item.getString("name")).setMessage(detail)
                            .setPositiveButton("Source principale") { _, _ -> openSource(activity, item.getString("url")) }
                            .setNegativeButton("Fermer", null)
                        if (sources.size > 1) details.setNeutralButton("Toutes les sources") { _, _ ->
                            AlertDialog.Builder(activity).setTitle(item.getString("name")).setItems(sources.toTypedArray()) { _, index -> openSource(activity, sources[index]) }
                                .setNegativeButton("Fermer", null).show()
                        }
                        details.show()
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
