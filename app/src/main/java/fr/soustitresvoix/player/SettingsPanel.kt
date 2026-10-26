package fr.soustitresvoix.player

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import java.text.Normalizer
import java.util.Locale

/** Search indexes the actual command and its aliases, never private subtitle text. */
internal object SettingsSearch {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace("œ", "oe").replace('’', ' ').replace('-', ' ')
    fun matches(query: String, text: String): Boolean {
        val haystack = normalize(text)
        return normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }.all { it in haystack }
    }
}

internal enum class SettingsSection(val title: String) {
    VOICE("Voix"), SYNCHRONIZATION("Synchronisation"), TRANSLATION("Traduction"), SUBTITLES("Sous-titres"),
    IMAGE("Image"), SOUND("Son"), STORAGE("Stockage"), DIAGNOSTIC("Diagnostic")
}

/** Eight real sections. Search reveals only the matching commands across sections. */
internal class SettingsPanel(context: Context) : LinearLayout(context) {
    private data class Entry(val section: SettingsSection, val text: String, val view: View)
    private val entries = mutableListOf<Entry>()
    private val groups = linkedMapOf<SettingsSection, LinearLayout>()
    private val refreshers = mutableListOf<() -> Unit>()
    private var selected = SettingsSection.VOICE
    private var sectionSelector: Spinner? = null
    val search = EditText(context).apply {
        id = View.generateViewId(); hint = "Rechercher un réglage : vitesse, cache, traduction…"
        contentDescription = "Rechercher dans les huit rubriques de réglages"
        textSize = 18f; minHeight = dp(56); setSingleLine(true)
    }
    private val results = TextView(context).apply {
        textSize = 17f; setTextColor(Color.WHITE)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    init {
        orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
        // AlertDialog initially measures custom content with AT_MOST. A real
        // minimum gives the weighted scroll area space in portrait and landscape.
        minimumHeight = (resources.displayMetrics.heightPixels * .65f).toInt()
        val semantics = TextView(context).apply {
            text = "Les réglages prennent effet immédiatement. Fermer les conserve. Annuler modifications restaure les réglages du début de ce panneau, sans rétablir les fichiers supprimés ni annuler un service déjà lancé."
            textSize = 17f; setTextColor(Color.WHITE)
        }
        addView(search)
        val sections = Spinner(context).apply {
            contentDescription = "Choisir une rubrique de réglages"
            minimumHeight = dp(56)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                SettingsSection.values().map { it.title })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in SettingsSection.values().indices) select(SettingsSection.values()[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        sectionSelector = sections
        addView(sections); addView(results)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        content.addView(semantics)
        SettingsSection.values().forEach { section ->
            val group = LinearLayout(context).apply { orientation = VERTICAL }
            group.addView(TextView(context).apply {
                text = section.title; textSize = 23f; setTextColor(Color.WHITE)
                setPadding(0, dp(12), 0, dp(8)); ViewCompat.setAccessibilityHeading(this, true)
            })
            groups[section] = group; content.addView(group)
        }
        addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        filter()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun add(section: SettingsSection, title: String, view: View, aliases: String = "") {
        entries += Entry(section, "$title $aliases", view)
        require(view.parent == null) { "Settings views have exactly one owner" }
        groups.getValue(section).addView(view); filter()
    }
    fun onRefresh(action: () -> Unit) { refreshers += action }
    fun refresh() { refreshers.toList().forEach { it() }; filter() }
    fun select(section: SettingsSection) {
        selected = section
        if (sectionSelector?.selectedItemPosition != section.ordinal) sectionSelector?.setSelection(section.ordinal)
        if (search.text.isNotEmpty()) search.setText("") else filter()
        results.text = "Rubrique ${section.title}"
    }
    private fun filter() {
        val query = search.text.toString().trim()
        var count = 0
        entries.forEach { entry ->
            val visible = if (query.isBlank()) entry.section == selected
                else SettingsSearch.matches(query, "${entry.section.title} ${entry.text}")
            entry.view.visibility = if (visible) VISIBLE else GONE
            if (visible) count++
        }
        groups.forEach { (section, group) ->
            group.visibility = if (entries.any { it.section == section && it.view.visibility == VISIBLE }) VISIBLE else GONE
        }
        val summary = if (query.isBlank()) "Rubrique ${selected.title}" else "$count résultat(s) pour « $query »"
        if (results.text.toString() != summary) results.text = summary
    }
}
