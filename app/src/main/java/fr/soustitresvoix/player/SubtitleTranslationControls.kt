package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.util.UnstableApi

/** Explicit provider selection, configuration help, volume preview and network consent. */
@UnstableApi
class SubtitleTranslationControls(private val activity: Activity, private val session: PlaybackSession) {
    private val storage = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
    private val credentials = TranslationCredentials(activity)
    private var panel: AlertDialog? = null
    private var confirmation: AlertDialog? = null
    private var status: TextView? = null
    private var localStatus: String? = null
    private var panelGeneration = 0L
    private var pendingPreview = false
    private fun showLocalStatus(value: String) { localStatus = value; status?.text = value }
    private fun clearLocalStatus() { localStatus = null; status?.text = session.translation.status }
    private fun invalidatePanel() {
        panelGeneration++
        if (pendingPreview) { pendingPreview = false; session.translation.cancel() }
        confirmation?.dismiss(); confirmation = null
    }
    private fun openLink(url: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { showLocalStatus("Aucun navigateur disponible pour ouvrir ce guide.") }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 18f; isAllCaps = false; minimumHeight = dp(64); setOnClickListener { action() }
    }
    private fun field(label: String, initial: String, secret: Boolean = false): Pair<TextView, EditText> {
        val input = EditText(activity).apply {
            id = View.generateViewId(); setText(initial); textSize = 18f; isSingleLine = true
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        return text(label).apply { labelFor = input.id } to input
    }
    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        close(); session.pause(); clearLocalStatus()
        val generation = panelGeneration
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        column.addView(text("Traduit la piste textuelle choisie vers le français AVANT la lecture. La voix lit uniquement la traduction complète. Les minutages, répliques, panneaux et répétitions sont conservés. L’affichage vidéo conserve la piste originale. Les filtres vocaux facultatifs restent applicables."))
        status = text(session.translation.status).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(status)
        val providers = TranslationProvider.values()
        column.addView(text("Moteur de traduction en ligne — aucun envoi avant confirmation"))
        val spinner = Spinner(activity).apply {
            contentDescription = "Moteur de traduction"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, providers.map { it.label })
            setSelection(providers.indexOfFirst { it.id == storage.getString("provider", "azure") }.coerceAtLeast(0))
            minimumHeight = dp(56)
        }
        column.addView(spinner)
        val providerDescription = text(""); column.addView(providerDescription)
        val (languageLabel, language) = field("Langue originale — code explicite, par exemple en, es, de, it, sr-Latn ou sr-Cyrl", storage.getString("language", "en") ?: "en")
        column.addView(languageLabel); column.addView(language)
        column.addView(button("Choisir une langue courante") {
            val names = arrayOf("Anglais — en", "Espagnol — es", "Allemand — de", "Italien — it", "Serbe latin — sr-Latn", "Serbe cyrillique — sr-Cyrl", "Portugais — pt", "Arabe — ar", "Japonais — ja", "Coréen — ko", "Chinois — zh", "Russe — ru", "Turc — tr")
            val codes = arrayOf("en", "es", "de", "it", "sr-Latn", "sr-Cyrl", "pt", "ar", "ja", "ko", "zh", "ru", "tr")
            AlertDialog.Builder(activity).setTitle("Langue source — disponibilité selon moteur").setItems(names) { _, i -> language.setText(codes[i]) }
                .setNegativeButton("Fermer", null).show()
        })
        column.addView(text("La langue est celle des sous-titres, pas forcément celle du film. Pour DeepL, indique sr pour le serbe ; les variantes sr-Latn/sr-Cyrl concernent Azure. Une langue refusée n’est jamais remplacée par une langue voisine."))
        val (keyLabel, key) = field("Clé API personnelle — masquée ; laisse vide pour tenter une piste déjà en cache", "", true)
        column.addView(keyLabel); column.addView(key)
        val remember = CheckBox(activity).apply { text = "Mémoriser cette clé, chiffrée avec Android Keystore sur ce téléphone"; textSize = 18f }
        column.addView(remember)
        val (regionLabel, region) = field("Région Azure — code de la ressource, par exemple francecentral ; vide uniquement si la clé est globale", storage.getString("region", "") ?: "")
        column.addView(regionLabel); column.addView(region)
        val (endpointLabel, endpoint) = field("Adresse de base du serveur LibreTranslate — HTTPS, sans identifiant, paramètres ou fragment", storage.getString("endpoint", "") ?: "")
        column.addView(endpointLabel); column.addView(endpoint)
        fun selected() = providers[spinner.selectedItemPosition]
        fun credentialScope(): String? = if (selected() != TranslationProvider.LIBRE) selected().id else
            try { providerIdentity(TranslationProviderConfig(TranslationProvider.LIBRE, endpoint = endpoint.text.toString().trim())) }
            catch (_: Exception) { null }
        var currentCredentialScope: String? = null
        fun loadCredentials() {
            // Clear first: a key from server A must never follow the user to server B.
            key.setText(""); remember.isChecked = false
            val scope = credentialScope(); currentCredentialScope = scope
            if (scope != null) try { key.setText(credentials.read(scope).orEmpty()) }
            catch (_: Exception) { showLocalStatus("La clé enregistrée ne peut pas être déchiffrée. Saisis-la à nouveau ou efface-la.") }
        }
        fun providerChanged() {
            clearLocalStatus()
            val provider = selected()
            providerDescription.text = description(provider)
            region.visibility = if (provider == TranslationProvider.AZURE) View.VISIBLE else View.GONE
            regionLabel.visibility = region.visibility
            endpoint.visibility = if (provider == TranslationProvider.LIBRE) View.VISIBLE else View.GONE
            endpointLabel.visibility = endpoint.visibility
            loadCredentials()
        }
        endpoint.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (selected() == TranslationProvider.LIBRE && credentialScope() != currentCredentialScope) {
                    clearLocalStatus(); loadCredentials()
                }
            }
            override fun afterTextChanged(s: Editable?) { }
        })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { providerChanged() }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
        providerChanged()
        column.addView(button("Effacer la clé mémorisée pour ce moteur") {
            clearLocalStatus()
            val scope = credentialScope()
            if (scope == null) showLocalStatus("Indique d’abord l’adresse HTTPS valide du serveur dont tu veux effacer la clé.")
            else try { credentials.remove(scope); key.setText(""); remember.isChecked = false; showLocalStatus("Clé mémorisée effacée pour ce moteur et ce serveur.") }
            catch (_: Exception) { showLocalStatus("La clé n’a pas pu être effacée. Réessaie après fermeture du panneau.") }
        })
        column.addView(button("Indexer la piste et estimer la traduction") {
            clearLocalStatus()
            val provider = selected()
            val config = TranslationProviderConfig(provider, key.text.toString().trim(),
                if (provider == TranslationProvider.AZURE) region.text.toString().trim() else "",
                if (provider == TranslationProvider.LIBRE) endpoint.text.toString().trim() else "")
            val sourceLanguage = language.text.toString().trim()
            val identity = try { providerIdentity(config) } catch (_: Exception) {
                showLocalStatus("Adresse LibreTranslate invalide. Utilise une adresse HTTPS sans identifiant, paramètres ou fragment."); return@button
            }
            storage.edit().putString("provider", provider.id).putString("language", sourceLanguage)
                .putString("region", region.text.toString().trim()).putString("endpoint", endpoint.text.toString().trim()).apply()
            val saveKey = remember.isChecked
            pendingPreview = true
            session.translation.prepare(sourceLanguage, identity) { preview ->
                if (activity.isDestroyed || activity.isFinishing || generation != panelGeneration || panel?.isShowing != true) {
                    pendingPreview = false; session.translation.cancel(); return@prepare
                }
                val destination = if (provider == TranslationProvider.LIBRE) TranslationEngines.libreEndpoint(config.endpoint).host else provider.label
                confirmation?.dismiss()
                confirmation = AlertDialog.Builder(activity).setTitle("Confirmer la traduction vers le français")
                    .setMessage("${preview.plan.cues.size} occurrences, ${preview.plan.estimatedCharacters} unités UTF-16 au maximum avant décompte du fournisseur. Les caractères Unicode supplémentaires peuvent être comptés différemment par son service.\n\nLe texte intégral de cette piste sera envoyé à $destination si aucun cache complet correspondant n’existe. La vidéo et l’audio ne sont pas envoyés. La clé autorise l’usage de TON compte : vérifie son quota et ses plafonds de dépense. Aucun tarif en euros ni solde de compte n’est déduit ici. Une réponse réseau perdue peut avoir été facturée ; aucune relance automatique.\n\nUne piste complète en cache est réutilisée sans requête et sans clé. Un cache absent, évincé ou corrompu nécessite une nouvelle traduction. Reste dans l’application ; la préparation ne reprend pas automatiquement après fermeture. La lecture restera en pause. Choisis ensuite une voix française dans Voix et confort.")
                    .setPositiveButton("Traduire / réutiliser le cache") { _, _ ->
                        if (generation != panelGeneration || panel?.isShowing != true) { session.translation.cancel(); return@setPositiveButton }
                        pendingPreview = false; clearLocalStatus()
                        if (saveKey && config.apiKey.isNotBlank()) {
                            try { credentials.save(identity, config.apiKey) }
                            catch (_: Exception) { showLocalStatus("La clé n’a pas pu être mémorisée. Elle sera utilisée uniquement pour cette demande.") }
                        }
                        session.translation.start(preview) {
                            val remote = TranslationEngines.create(config)
                            object : TranslationEngine by remote { override val id = identity }
                        }
                    }.setNegativeButton("Annuler") { _, _ -> pendingPreview = false; clearLocalStatus(); session.translation.cancel() }
                    .setOnCancelListener { pendingPreview = false; clearLocalStatus(); session.translation.cancel() }.show()
            }
        })
        column.addView(button("Annuler la préparation / traduction") { pendingPreview = false; clearLocalStatus(); session.translation.cancel() })
        column.addView(button("Revenir explicitement à la piste originale") { pendingPreview = false; clearLocalStatus(); session.translation.useOriginal() })
        column.addView(button("Guide complet : moteurs, clés API et traduction sur téléphone") {
            openLink("https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/GUIDE_MOTEURS_TRADUCTION.md")
        })
        column.addView(button("Documentation officielle du moteur choisi") {
            openLink(when (selected()) {
                TranslationProvider.AZURE -> "https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/quickstart/rest-api"
                TranslationProvider.DEEPL_FREE, TranslationProvider.DEEPL_PRO -> "https://developers.deepl.com/docs/getting-started/auth"
                TranslationProvider.LIBRE -> "https://docs.libretranslate.com/guides/api_usage/"
            })
        })
        column.addView(text("Modèles locaux et Hugging Face : aucun runtime local de traduction n’est encore intégré dans cette étape. Un fichier GGUF, SafeTensors ou ONNX ne peut pas être importé ici. Le guide du projet décrit les candidats, leurs limites et les étapes restant à développer. Une traduction en cache fonctionne hors ligne ; ce n’est pas un moteur local."))
        panel = AlertDialog.Builder(activity).setTitle("Traduire les sous-titres en français")
            .setView(ScrollView(activity).apply {
                id = R.id.translation_controls_scroll
                addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Fermer", null).show().also { dialog ->
                dialog.setOnDismissListener { if (generation == panelGeneration) invalidatePanel() }
            }
    }
    fun refresh() { status?.let {
        val message = localStatus ?: session.translation.status
        if (panel?.isShowing == true && it.text.toString() != message) it.text = message
    } }
    fun close() { invalidatePanel(); panel?.dismiss(); panel = null; status = null; localStatus = null }

    companion object {
        internal fun providerIdentity(config: TranslationProviderConfig): String = TranslationEngines.identity(config)
        fun description(provider: TranslationProvider): String = when (provider) {
            TranslationProvider.AZURE -> "Microsoft Azure Translator : service en ligne. Crée une ressource Translator dans le portail Azure, choisis le niveau gratuit F0 si disponible, puis copie la clé et la région depuis Clés et point de terminaison. F0 annonce 2 millions de caractères/mois ; vérifie les conditions actuelles de ton compte. Prend en charge le serbe latin et cyrillique. La région doit correspondre à la ressource. Aucun abonnement n’est souscrit par l’application."
            TranslationProvider.DEEPL_FREE -> "DeepL, endpoint api-free.deepl.com : nécessite une clé API affectée à ce point d’accès. Un abonnement au site ou à l’application DeepL ne remplace pas un accès API. Les offres ont changé : l’ancien API Free n’est plus ouvert aux nouveaux achats ; l’essai API Developer annonce 1 million de caractères au total, non renouvelable. Vérifie dans ton compte quel endpoint utiliser. Crée/copie ta clé dans les paramètres API, puis colle-la ici. Aucun compte n’est créé par l’application."
            TranslationProvider.DEEPL_PRO -> "DeepL, endpoint api.deepl.com : nécessite une clé API correspondant à ce point d’accès. Les offres actuelles et historiques ont des règles distinctes : Growth, Enterprise ou ancien Pro selon ton compte. Consulte la facturation et configure une limite de dépense avant usage. Un abonnement DeepL pour le site ou l’application ne suffit pas. Chaque sous-titre est traduit séparément ; le tableau envoyé ne fournit pas automatiquement un contexte de scène partagé."
            TranslationProvider.LIBRE -> "LibreTranslate : traduction par un serveur que tu choisis. Indique son adresse HTTPS et sa clé si l’instance en exige une ; demande à son administrateur les langues installées, les quotas et la politique de conservation. La gratuité dépend de l’instance et de son hébergement. L’application appelle /translate et refuse les redirections. Ce serveur ne s’exécute pas sur le téléphone : même auto-hébergé, il reçoit les sous-titres. L’application ne crée ni ne déploie de serveur."
        }
    }
}
