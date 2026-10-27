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
import fr.soustitresvoix.core.SubtitleExportFormat
import fr.soustitresvoix.core.SubtitleLanguageProposal
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import android.os.Handler
import android.os.Looper

/** Explicit provider selection, configuration help, volume preview and network consent. */
@UnstableApi
class SubtitleTranslationControls(private val activity: Activity, private val session: PlaybackSession,
    private val onExport: (String, String, String) -> Unit = { _, _, _ -> }) {
    private var auxiliaryWorker: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    private fun background(action: () -> Unit) {
        val executor = auxiliaryWorker?.takeUnless { it.isShutdown } ?: Executors.newSingleThreadExecutor().also { auxiliaryWorker = it }
        executor.execute(action)
    }
    private val storage = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
    private val credentials = TranslationCredentials(activity)
    private var panel: AlertDialog? = null
    private var confirmation: AlertDialog? = null
    private var status: TextView? = null
    private var localStatus: String? = null
    @Volatile private var panelGeneration = 0L
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
        column.addView(text("Traduit la piste textuelle choisie vers le français avant lecture, ou progressivement sur choix explicite. Chaque réplique, panneau et répétition conserve son identité et son minutage. Le choix d’affichage est indépendant de la voix. Une préparation ou une reprise ne lance jamais la lecture."))
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
        val progressive = CheckBox(activity).apply { text = "Traduction progressive : autoriser la lecture avec au moins une minute de tampon validé"; textSize = 18f; isChecked = false }
        column.addView(progressive)
        column.addView(text("Désactivé par défaut : attend la piste entière. En progressif, la lecture s’arrête avant une réplique indisponible ; appuie sur Lire après préparation, aucune reprise automatique. Un déplacement loin dans la vidéo peut attendre la traduction précédente."))
        val (budgetLabel, budget) = field("Plafond de nouveaux caractères UTF-16 pour cette tâche — vide : aucune limite locale supplémentaire", "")
        budget.inputType = InputType.TYPE_CLASS_NUMBER
        column.addView(budgetLabel); column.addView(budget)
        val (contextLabel, translationContext) = field("DeepL : contexte de l’œuvre ou de la scène, facultatif — envoyé avec chaque lot, 24 000 caractères maximum", "")
        translationContext.isSingleLine = false; translationContext.setLines(3)
        column.addView(contextLabel); column.addView(translationContext)
        val (glossaryLabel, glossary) = field("DeepL : identifiant d’un glossaire déjà créé dans TON compte, facultatif", "")
        column.addView(glossaryLabel); column.addView(glossary)
        val (revisionLabel, glossaryRevision) = field("DeepL : révision du glossaire — change-la après une modification du glossaire distant", "")
        column.addView(revisionLabel); column.addView(glossaryRevision)
        val (modelLabel, model) = field("DeepL : modèle facultatif — quality_optimized, prefer_quality_optimized ou latency_optimized", "")
        column.addView(modelLabel); column.addView(model)
        val (formalityLabel, formality) = field("DeepL : registre — default, more, less, prefer_more ou prefer_less", "default")
        column.addView(formalityLabel); column.addView(formality)
        column.addView(text("Ces options s’appliquent à DeepL seulement. Contexte : synopsis court, noms et explications utiles ; il est envoyé au service avec chaque lot. Registre : more préfère un ton formel, less un ton moins formel, prefer_more/prefer_less tolèrent les langues sans ce réglage. Modèle : quality_optimized privilégie la qualité ; latency_optimized vise une attente plus courte ; prefer_quality_optimized permet la sélection compatible du fournisseur. Disponibilité selon le compte et la langue. Le contexte guide la traduction sans garantir les noms ou le registre. L’application ne crée pas de glossaire distant. Les options font partie de l’identité du cache."))
        var latestPreview: SubtitleTranslationSession.Preview? = null
        val comparisons = mutableListOf<TranslationProviderConfig>()
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
        fun configuration() = TranslationProviderConfig(selected(), key.text.toString().trim(),
            if (selected() == TranslationProvider.AZURE) region.text.toString().trim() else "",
            if (selected() == TranslationProvider.LIBRE) endpoint.text.toString().trim() else "",
            if (selected() == TranslationProvider.DEEPL_FREE || selected() == TranslationProvider.DEEPL_PRO)
                TranslationOptions(translationContext.text.toString(), glossary.text.toString().trim(),
                    model.text.toString().trim(), formality.text.toString().trim(), glossaryRevision.text.toString().trim())
            else TranslationOptions())
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
            val optionsVisibility = if (provider == TranslationProvider.DEEPL_FREE || provider == TranslationProvider.DEEPL_PRO) View.VISIBLE else View.GONE
            listOf(contextLabel, translationContext, glossaryLabel, glossary, revisionLabel, glossaryRevision, modelLabel, model, formalityLabel, formality).forEach { it.visibility = optionsVisibility }
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
            val config = configuration()
            val sourceLanguage = language.text.toString().trim()
            val credentialIdentity = try { providerIdentity(config) } catch (_: Exception) {
                showLocalStatus("Adresse LibreTranslate invalide. Utilise une adresse HTTPS sans identifiant, paramètres ou fragment."); return@button
            }
            storage.edit().putString("provider", provider.id).putString("language", sourceLanguage)
                .putString("region", region.text.toString().trim()).putString("endpoint", endpoint.text.toString().trim()).apply()
            val identity = try { TranslationEngines.translationIdentity(config) } catch (_: Exception) {
                showLocalStatus("Options invalides ou non prises en charge par le moteur choisi."); return@button
            }
            val cap = budget.text.toString().trim().let { if (it.isEmpty()) Long.MAX_VALUE else it.toLongOrNull() }
            if (cap == null || cap < 0) { showLocalStatus("Plafond invalide : indique un entier positif ou laisse vide."); return@button }
            val saveKey = remember.isChecked
            pendingPreview = true
            session.translation.prepareWithOptions(sourceLanguage, identity, progressive = progressive.isChecked, maximumNewCharacters = cap) { preview ->
                latestPreview = preview
                if (activity.isDestroyed || activity.isFinishing || generation != panelGeneration || panel?.isShowing != true) {
                    pendingPreview = false; session.translation.cancel(); return@prepareWithOptions
                }
                val destination = if (provider == TranslationProvider.LIBRE) TranslationEngines.libreEndpoint(config.endpoint).host else provider.label
                confirmation?.dismiss()
                confirmation = AlertDialog.Builder(activity).setTitle("Confirmer la traduction vers le français")
                    .setMessage("${preview.plan.cues.size} occurrences, ${preview.plan.estimatedCharacters} unités UTF-16 pour la piste. Nouveaux caractères estimés après reprise/cache : ${preview.estimatedNewCharacters}. Plafond local : ${if (preview.maximumNewCharacters == Long.MAX_VALUE) "non limité" else preview.maximumNewCharacters.toString()}. Décompte final du fournisseur distinct. Les caractères Unicode supplémentaires peuvent être comptés différemment par son service.\n\nLe texte intégral de cette piste sera envoyé à $destination si aucun cache complet correspondant n’existe. La vidéo et l’audio ne sont pas envoyés. La clé autorise l’usage de TON compte : vérifie son quota et ses plafonds de dépense. Aucun tarif en euros ni solde de compte n’est déduit ici. DeepL : les appels sont espacés ; un refus temporaire de débit (HTTP 429/529) est réessayé au maximum cinq fois, avec une attente croissante et au plus deux minutes d’attente cumulée par lot. Un quota épuisé (456) ou une réponse réseau perdue n’est pas relancé automatiquement ; cette dernière peut avoir été facturée.\n\nUne piste complète en cache ou conservée est réutilisée sans requête et sans clé. ${preview.resumedOccurrences} occurrences validées peuvent être reprises. ${if (preview.uncertainRequest) "Une requête interrompue a un état de facturation inconnu : sa reprise peut être facturée de nouveau." else "Les lots validés sont enregistrés pour reprise."} Les caractères restants doivent respecter ton plafond local. Le travail peut être interrompu par Android ; rouvre la même piste et confirme pour reprendre. La lecture restera en pause. Choisis ensuite une voix française dans Voix et confort.")
                    .setPositiveButton("Traduire / réutiliser le cache") { _, _ ->
                        if (generation != panelGeneration || panel?.isShowing != true) { session.translation.cancel(); return@setPositiveButton }
                        pendingPreview = false; clearLocalStatus()
                        if (saveKey && config.apiKey.isNotBlank()) {
                            try { credentials.save(credentialIdentity, config.apiKey) }
                            catch (_: Exception) { showLocalStatus("La clé n’a pas pu être mémorisée. Elle sera utilisée uniquement pour cette demande.") }
                        }
                        session.translation.start(preview) {
                            TranslationEngines.create(config)
                        }
                    }.setNegativeButton("Annuler") { _, _ -> pendingPreview = false; clearLocalStatus(); session.translation.cancel() }
                    .setOnCancelListener { pendingPreview = false; clearLocalStatus(); session.translation.cancel() }.show()
            }
        })
        column.addView(button("Proposer la langue à partir de l’index déjà préparé — sans réseau") {
            val plan = latestPreview?.plan
            if (plan == null) { showLocalStatus("Indexe d’abord la piste ; annule la confirmation d’envoi pour examiner seulement le texte.") }
            else {
                val hint = SubtitleLanguageProposal.suggest(plan.texts)
                AlertDialog.Builder(activity).setTitle("Proposition de langue à confirmer")
                    .setMessage((hint.code?.let { "Code proposé : $it.\n" } ?: "") + hint.explanation + "\nLa langue ne change pas automatiquement.")
                    .setPositiveButton(if (hint.code == null) "Fermer" else "Choisir cette proposition") { _, _ -> hint.code?.let { language.setText(it) } }
                    .setNegativeButton("Conserver ma langue", null).show()
            }
        })
        column.addView(button("Consulter le quota DeepL — aucun texte envoyé") {
            val config = configuration()
            if (config.provider != TranslationProvider.DEEPL_FREE && config.provider != TranslationProvider.DEEPL_PRO) {
                showLocalStatus("Cette consultation est disponible pour DeepL. Pour les autres services, consulte le tableau de bord de ton compte.")
            } else {
                showLocalStatus("Consultation du quota DeepL…")
                background {
                    val result = runCatching { TranslationEngines.usage(config, { generation != panelGeneration || Thread.currentThread().isInterrupted }) }
                    main.post { if (generation == panelGeneration && panel?.isShowing == true) {
                        result.fold({ usage -> showLocalStatus("DeepL : ${usage.characterCount} / ${usage.characterLimit} caractères déclarés pour le compte ; ${usage.remainingCharacters} restants. " +
                            (usage.apiKeyRemainingCharacters?.let { "Clé : $it restants. " } ?: "") + "Valeurs du fournisseur à cet instant, sans garantie de facture ni réservation de quota.") },
                            { showLocalStatus((it as? TranslationFailure)?.message ?: "Quota indisponible ; aucun sous-titre envoyé.") })
                    } }
                }
            }
        })
        column.addView(button("Ajouter la configuration actuelle au comparatif — aucun envoi") {
            try {
                val config = configuration(); TranslationEngines.validateForRequest(config, language.text.toString().trim(), "fr")
                if (comparisons.size >= 4) showLocalStatus("Quatre configurations maximum. Ferme puis rouvre ce panneau pour vider le comparatif.")
                else { comparisons += config; showLocalStatus("${comparisons.size} configuration(s) dans le comparatif. Les clés restent uniquement en mémoire pour ce panneau.") }
            } catch (_: Exception) { showLocalStatus("Configuration ou langue invalide pour ce comparatif.") }
        })
        column.addView(button("Comparer explicitement un échantillon indexé") {
            val samplePreview = latestPreview?.takeIf { it.generation == session.generation && it.track == session.selectedTextId() }
            val plan = samplePreview?.plan
            if (plan == null || comparisons.isEmpty()) showLocalStatus("Indexe d’abord la piste, annule l’envoi puis ajoute une ou plusieurs configurations au comparatif.")
            else {
                val sample = plan.texts.take(10).let { texts ->
                    val output = mutableListOf<String>(); var length = 0
                    for (value in texts) { if (length + value.length > 4000) break; output += value; length += value.length }; output.toList()
                }
                if (sample.isEmpty()) showLocalStatus("Première occurrence trop longue pour cet échantillon borné ; aucune requête.")
                else {
                    val configs = comparisons.toList()
                    val sampleGeneration = samplePreview?.generation ?: -1L
                    val sampleTrack = samplePreview?.track
                    val destinations = configs.mapIndexed { i, config -> "Configuration ${i+1} : " + if (config.provider == TranslationProvider.LIBRE) TranslationEngines.libreEndpoint(config.endpoint).host else config.provider.label }.joinToString("\n")
                    AlertDialog.Builder(activity).setTitle("Confirmer tous les destinataires du comparatif")
                        .setMessage("${sample.size} occurrences (${sample.sumOf { it.length }} unités UTF-16) seront envoyées séparément à :\n$destinations\n\nChaque service peut facturer cet essai. Les résultats sont comparés visuellement, sans classement automatique de fidélité. Texte, contexte et glossaire selon chaque configuration.")
                        .setPositiveButton("Envoyer cet échantillon") { _, _ ->
                            if (generation != panelGeneration || sampleGeneration != session.generation || sampleTrack != session.selectedTextId() || panel?.isShowing != true) {
                                showLocalStatus("La vidéo ou la piste a changé. Indexe le nouvel échantillon avant tout envoi."); return@setPositiveButton
                            }
                            showLocalStatus("Comparaison en cours…")
                            background {
                                val result = runCatching { TranslationSampleComparator.compareConfirmedSample(configs, sample, plan.sourceLanguage, cancelled = { generation != panelGeneration || sampleGeneration != session.generation || sampleTrack != session.selectedTextId() || Thread.currentThread().isInterrupted }) }
                                main.post { if (generation == panelGeneration && panel?.isShowing == true) result.fold({ results ->
                                    val comparison = results.mapIndexed { number, value -> "Configuration ${number+1} — " + value.providerLabel + " — " + value.elapsedMillis + " ms\n" + (value.failure?.message ?: value.translations.mapIndexed { i, text -> "${i+1}. Source : ${sample[i]}\nTraduction : $text" }.joinToString("\n")) }.joinToString("\n\n")
                                    AlertDialog.Builder(activity).setTitle("Comparer les traductions — jugement humain requis").setView(ScrollView(activity).apply { addView(text(comparison)) }).setPositiveButton("Fermer", null).show()
                                }, { showLocalStatus((it as? TranslationFailure)?.message ?: "Comparaison interrompue ; vérifie les quotas avant une nouvelle tentative.") }) }
                            }
                        }.setNegativeButton("Annuler", null).show()
                }
            }
        })
        column.addView(button("Choisir l’affichage des sous-titres") {
            val modes = TranslationDisplayMode.values()
            val labels = arrayOf("Piste originale", "Traduction française", "Original et français", "Masquer les sous-titres visibles")
            AlertDialog.Builder(activity).setTitle("Affichage indépendant de la narration").setSingleChoiceItems(labels, modes.indexOf(session.translation.displayMode)) { dialog, which ->
                session.translation.setDisplayMode(modes[which]); dialog.dismiss()
            }.setNegativeButton("Fermer", null).show()
        })
        column.addView(button("Conserver durablement la traduction complète") { clearLocalStatus(); session.translation.saveCurrentTranslationAsync() })
        column.addView(button("Voir le stockage des traductions conservées") {
            AlertDialog.Builder(activity).setTitle("Traductions sur ce téléphone").setMessage(session.translation.storedTranslationSummary())
                .setPositiveButton("Fermer", null).setNeutralButton("Retirer la conservation de cette piste") { _, _ ->
                    AlertDialog.Builder(activity).setTitle("Retirer cette traduction conservée ?")
                        .setMessage("Retire uniquement sa conservation durable. Le fichier vidéo, les sous-titres originaux et les exports ne sont pas modifiés. Le cache évictable et la session peuvent encore contenir la traduction.")
                        .setPositiveButton("Retirer la conservation") { _, _ -> clearLocalStatus(); session.translation.forgetCurrentSavedTranslation() }
                        .setNegativeButton("Annuler", null).show()
                }.show()
        })
        for (format in SubtitleExportFormat.values()) column.addView(button("Exporter la traduction en ${format.name}") {
            val output = session.translation.exportCurrent(format)
            if (output == null) showLocalStatus("Termine d’abord la traduction complète de cette piste.")
            else onExport(format.mimeType, "sous-titres-francais.${format.extension}", output)
        })
        column.addView(text("Un SRT/VTT exporté peut être réimporté avec Ajouter des sous-titres dans la bibliothèque. Il conserve le texte et les temps, sans styles avancés ni secrets. Il devient une piste française autonome ; n’active pas de nouvelle traduction français vers français."))
        column.addView(button("Vérifier les alertes linguistiques") {
            val hints = session.translation.reviewHints
            val message = if (hints.isEmpty()) "Aucun indice détecté. Cela ne prouve pas la fidélité linguistique de la traduction." else hints.take(100).joinToString("\n\n") { hint ->
                val index = session.translation.sourceCues.indexOfFirst { it.id == hint.cueId } + 1
                "Occurrence $index : ${hint.reasons.joinToString(" ")}" } + if (hints.size > 100) "\nAutres alertes non affichées : ${hints.size - 100}." else ""
            AlertDialog.Builder(activity).setTitle("Indices à vérifier, sans certitude de contresens").setView(ScrollView(activity).apply { addView(text(message)) }).setPositiveButton("Fermer", null).show()
        })
        column.addView(button("Corriger une réplique de la traduction complète") {
            val cues = session.translation.editableCues()
            if (!session.translation.complete || cues.isEmpty()) showLocalStatus("Termine d’abord la traduction complète.")
            else {
                val (label, number) = field("Numéro d’occurrence de 1 à ${cues.size}", "1")
                number.inputType = InputType.TYPE_CLASS_NUMBER
                AlertDialog.Builder(activity).setTitle("Choisir la réplique à corriger").setView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(number) })
                    .setPositiveButton("Ouvrir") { _, _ ->
                        val index = number.text.toString().toIntOrNull()?.minus(1)
                        val cue = index?.let { cues.getOrNull(it) }
                        if (cue == null) showLocalStatus("Numéro d’occurrence invalide.")
                        else {
                            val (editLabel, edit) = field("Traduction — conserve le sens et les noms", cue.text)
                            edit.isSingleLine = false; edit.setLines(5)
                            val source = session.translation.sourceCues.firstOrNull { it.id == cue.id }?.text.orEmpty()
                            AlertDialog.Builder(activity).setTitle("Occurrence ${index+1}").setView(ScrollView(activity).apply { addView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(text("Source : $source")); addView(editLabel); addView(edit) }) })
                                .setPositiveButton("Corriger et préparer cette voix") { _, _ ->
                                    if (session.translation.editCueAndPrepareVoice(cue.id, edit.text.toString())) showLocalStatus(session.translation.status + " Conserve ou exporte la piste pour garder cette modification.")
                                }.setNegativeButton("Annuler", null).show()
                        }
                    }.setNegativeButton("Annuler", null).show()
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
    fun close() { invalidatePanel(); auxiliaryWorker?.shutdownNow(); auxiliaryWorker = null; panel?.dismiss(); panel = null; status = null; localStatus = null }

    companion object {
        internal fun providerIdentity(config: TranslationProviderConfig): String = TranslationEngines.identity(config)
        fun description(provider: TranslationProvider): String = when (provider) {
            TranslationProvider.AZURE -> "Microsoft Azure Translator : service en ligne. Crée une ressource Translator dans le portail Azure, choisis le niveau gratuit F0 si disponible, puis copie la clé et la région depuis Clés et point de terminaison. F0 annonce 2 millions de caractères/mois ; vérifie les conditions actuelles de ton compte. Prend en charge le serbe latin et cyrillique. La région doit correspondre à la ressource. Aucun abonnement n’est souscrit par l’application."
            TranslationProvider.DEEPL_FREE -> "DeepL, endpoint api-free.deepl.com : nécessite une clé API affectée à ce point d’accès. Un abonnement au site ou à l’application DeepL ne remplace pas un accès API. Les offres ont changé : l’ancien API Free n’est plus ouvert aux nouveaux achats ; l’essai API Developer annonce 1 million de caractères au total, non renouvelable. Vérifie dans ton compte quel endpoint utiliser. Crée/copie ta clé dans les paramètres API, puis colle-la ici. Les lots sont espacés de 500 ms au minimum ; les refus de débit déclenchent des attentes bornées avant reprise. HTTP 456 signale un quota du compte ou une limite propre à la clé, même si le solde global reste positif. Aucun compte n’est créé par l’application."
            TranslationProvider.DEEPL_PRO -> "DeepL, endpoint api.deepl.com : nécessite une clé API correspondant à ce point d’accès. Les offres actuelles et historiques ont des règles distinctes : Growth, Enterprise ou ancien Pro selon ton compte. Consulte la facturation et configure une limite de dépense avant usage. Un abonnement DeepL pour le site ou l’application ne suffit pas. Chaque sous-titre est traduit séparément ; le tableau envoyé ne fournit pas automatiquement un contexte de scène partagé."
            TranslationProvider.LIBRE -> "LibreTranslate : traduction par un serveur que tu choisis. Indique son adresse HTTPS et sa clé si l’instance en exige une ; demande à son administrateur les langues installées, les quotas et la politique de conservation. La gratuité dépend de l’instance et de son hébergement. L’application appelle /translate et refuse les redirections. Ce serveur ne s’exécute pas sur le téléphone : même auto-hébergé, il reçoit les sous-titres. L’application ne crée ni ne déploie de serveur."
        }
    }
}
