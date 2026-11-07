package fr.soustitresvoix.player

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.text.InputType
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit local import only. No model is loaded, downloaded or translated by this form. */
internal class LocalGgufModelControls(
    private val activity: Activity,
    private val translationBusy: () -> Boolean,
    private val launchPicker: () -> Unit,
    private val onChanged: () -> Unit,
    private val onMessage: (String) -> Unit
) : AutoCloseable {
    private val store = LocalGgufModelStore(activity.applicationContext)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences("translation-choices-v1", Activity.MODE_PRIVATE)
    private var cancellation = AtomicBoolean(false)
    private var generation = 0L
    private var closed = false
    private var active = true
    private var waitingPicker = false
    var busy = false
        private set
    var model: LocalGgufModel? = store.current()
        private set
    var execution: String = preferences.getString("local-execution", "AUTO")?.takeIf { it in EXECUTION_MODES } ?: "AUTO"
        get() = executionSpinner?.selectedItemPosition?.takeIf { it in EXECUTION_MODES.indices }?.let { EXECUTION_MODES[it] } ?: field
        private set
    var profile: LocalGgufProfile = runCatching {
        LocalGgufProfile.valueOf(preferences.getString("local-profile", "AUTO") ?: "AUTO")
    }.getOrDefault(LocalGgufProfile.AUTO)
        get() = profileSpinner?.selectedItemPosition?.takeIf { it in PROFILES.indices }?.let { PROFILES[it] } ?: field
        private set
    var contextPolicy: LocalGgufContextPolicy = LocalGgufContextPolicy.fromPreference(preferences.getString("local-context-policy", null))
        get() = contextPolicySpinner?.selectedItemPosition?.takeIf { it in CONTEXT_POLICIES.indices }?.let { CONTEXT_POLICIES[it] } ?: field
        private set
    private var contextPolicySpinner: Spinner? = null
    private var status: TextView? = null
    private var chooseButton: Button? = null
    private var existingButton: Button? = null
    private var cancelButton: Button? = null
    private var executionSpinner: Spinner? = null
    private var profileSpinner: Spinner? = null
    private var storedLoadSettings = runCatching {
        LocalGgufLoadSettings(preferences.getInt("local-gpu-layers", -1), preferences.getInt("local-batch-tokens", 512),
            preferences.getInt("local-micro-batch-tokens", 128), preferences.getBoolean("local-compact-swa", false),
            preferences.getString("local-kv-cache-type", "F16")?.takeIf { it == "F16" || it == "Q8_0" } ?: "F16",
            preferences.getString("local-flash-attention", "AUTO") ?: "AUTO",
            preferences.getBoolean("local-qualcomm-compatibility", false),
            preferences.getBoolean("local-native-cpu-weights", false))
            .also { it.validate() }
    }.getOrDefault(LocalGgufLoadSettings())
    private var gpuLayersField: EditText? = null
    private var batchField: EditText? = null
    private var microBatchField: EditText? = null
    private var compactSwaChoice: CheckBox? = null
    private var quantizedKvChoice: CheckBox? = null
    private var flashAttentionChoice: Spinner? = null
    private var qualcommCompatibilityChoice: CheckBox? = null
    private var nativeCpuWeightsChoice: CheckBox? = null
    private val loadingControls = mutableListOf<View>()
    val loadSettings: LocalGgufLoadSettings get() {
        fun value(field: EditText?, fallback: Int): Int = if (field == null) fallback else field.text.toString().toIntOrNull() ?: 0
        return LocalGgufLoadSettings(value(gpuLayersField, storedLoadSettings.gpuLayers),
            value(batchField, storedLoadSettings.batchTokens), value(microBatchField, storedLoadSettings.microBatchTokens),
            compactSwaChoice?.isChecked ?: storedLoadSettings.compactSwa,
            quantizedKvChoice?.let { if (it.isChecked) "Q8_0" else "F16" } ?: storedLoadSettings.kvCacheType,
            flashAttentionChoice?.selectedItemPosition?.takeIf { it in FLASH_POLICIES.indices }?.let { FLASH_POLICIES[it] }
                ?: storedLoadSettings.flashAttentionPolicy,
            qualcommCompatibilityChoice?.isChecked ?: storedLoadSettings.qualcommCompatibility,
            nativeCpuWeightsChoice?.isChecked ?: storedLoadSettings.nativeCpuWeights)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun action(value: String, block: () -> Unit) = Button(activity).apply {
        text = value; textSize = 18f; isAllCaps = false; minimumHeight = dp(64); minHeight = dp(64)
        isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
        setOnClickListener { block() }
    }
    private fun choices(labels: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels) {
        private fun readable(view: View): View = view.apply {
            (this as? TextView)?.apply {
                textSize = 18f; isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
                minimumHeight = dp(64); minHeight = dp(64)
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = readable(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = readable(super.getDropDownView(position, convertView, parent))
    }
    fun view(showExecutionChoice: Boolean = true, showContextChoice: Boolean = true,
        showLoadChoices: Boolean = showExecutionChoice): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("Modèle GGUF local : choisis son fichier avec Android ou réutilise celui du diagnostic. L’import crée une copie privée et conserve l’original. Aucun téléchargement de modèle et aucun envoi de sous-titres ne sont effectués ici."))
        status = label(modelDescription()).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        addView(status)
        chooseButton = action("Choisir un modèle GGUF local pour traduire") { choose() }.also { addView(it) }
        existingButton = action("Préparer le modèle GGUF déjà importé dans le diagnostic") {
            runImport { cancelled, progress -> store.prepareExisting(cancelled, progress) }
        }.also { addView(it) }
        cancelButton = action("Annuler l’import ou la préparation du modèle GGUF") { cancel() }.also { addView(it) }
        addView(label("Le moteur local reste indépendant de l’OCR. Sa vitesse et sa mémoire dépendent du modèle choisi. Garde l’application ouverte pendant l’import et le traitement."))
        if (showExecutionChoice) {
            executionSpinner = Spinner(activity).apply {
                id = View.generateViewId(); contentDescription = "Exécution du modèle GGUF"
                minimumHeight = dp(64)
                adapter = choices(listOf("Automatique (recommandé)", "CPU uniquement", "GPU OpenCL uniquement — sans repli CPU",
                    "GPU Vulkan uniquement — sans repli CPU",
                    "NPU Hexagon — expérimental ; sans repli CPU"))
                setSelection(EXECUTION_MODES.indexOf(execution).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        execution = EXECUTION_MODES[position]
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }.also { spinner ->
                addView(label("Exécution du modèle GGUF").apply { labelFor = spinner.id })
                addView(spinner)
            }
            addView(label("Automatique essaie le GPU OpenCL disponible et peut revenir au CPU avec une raison affichée. OpenCL seul et Vulkan seul refusent un GPU indisponible. Vulkan est une option de comparaison : aucun gain de vitesse ou de mémoire n’est garanti. Le mode NPU Hexagon est expérimental et refuse un accélérateur indisponible ; la participation de certains opérateurs CPU reste possible. Les mesures distinguent un calcul GPU et un calcul Hexagon réellement observés. Le choix d’un accélérateur ne prouve pas son exécution et n’ajoute pas de RAM au téléphone."))
        }
        if (showLoadChoices) {
            addView(label("Mémoire du calcul GGUF — réglages de chargement"))
            addView(label("En mode Hexagon, le nombre de couches GPU ci-dessous règle les couches déportées vers le NPU. Les poids et la capacité de contexte restent ceux du modèle choisi."))
            fun number(title: String, initial: Int, signed: Boolean = false): EditText {
                val input = EditText(activity).apply {
                    id = View.generateViewId(); textSize = 18f; minimumHeight = dp(64)
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    inputType = InputType.TYPE_CLASS_NUMBER or if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0
                    setText(initial.toString()); contentDescription = title
                }
                addView(label(title).apply { labelFor = input.id }); addView(input); loadingControls.add(input)
                return input
            }
            gpuLayersField = number("Couches GPU : -1 pour toutes, sinon 1 à 1024 (le moteur borne au modèle)", storedLoadSettings.gpuLayers, true)
            batchField = number("Lot logique : 16 à 512 tokens", storedLoadSettings.batchTokens)
            microBatchField = number("Micro-lot : 16 à 128 tokens, inférieur ou égal au lot logique", storedLoadSettings.microBatchTokens)
            compactSwaChoice = CheckBox(activity).apply {
                text = "Cache d’attention locale compact — expérimental pour les modèles à attention locale"
                textSize = 18f; minimumHeight = dp(64); minHeight = dp(64); isChecked = storedLoadSettings.compactSwa
                isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
            }.also { addView(it); loadingControls.add(it) }
            quantizedKvChoice = CheckBox(activity).apply {
                id = View.generateViewId()
                text = "Cache d’attention K/V en 8 bits — expérimental ; F16 si désactivé"
                textSize = 18f; minimumHeight = dp(64); minHeight = dp(64)
                isChecked = storedLoadSettings.kvCacheType == "Q8_0"
                isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
            }.also { addView(it); loadingControls.add(it) }
            addView(label("Le cache K/V reste en F16 par défaut. L’option 8 bits conserve les poids du modèle mais réduit la précision des états d’attention ; sa compatibilité et la fidélité de ses traductions doivent être comparées. Un moteur incompatible doit signaler une erreur, sans changer silencieusement cette précision."))
            flashAttentionChoice = Spinner(activity).apply {
                id = View.generateViewId(); contentDescription = "Politique Flash Attention du modèle GGUF"
                minimumHeight = dp(64)
                adapter = choices(listOf("Flash Attention : automatique", "Flash Attention : activé explicitement",
                    "Flash Attention : désactivé explicitement"))
                setSelection(FLASH_POLICIES.indexOf(storedLoadSettings.flashAttentionPolicy).coerceAtLeast(0))
            }.also { addView(label("Politique Flash Attention du modèle GGUF").apply { labelFor = it.id }); addView(it); loadingControls.add(it) }
            addView(label("Ce choix est une politique demandée, pas une preuve d’exécution de Flash Attention. Le cache K/V 8 bits exige Flash Attention : Automatique demande alors son activation ; Désactivé est une configuration invalide. Une incompatibilité est signalée sans repli opaque de précision."))
            qualcommCompatibilityChoice = CheckBox(activity).apply {
                text = "Compatibilité Vulkan Qualcomm — expérimental ; nouveau processus requis après changement"
                textSize = 18f; minimumHeight = dp(64); minHeight = dp(64)
                isChecked = storedLoadSettings.qualcommCompatibility
                isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
            }.also { addView(it); loadingControls.add(it) }
            addView(label("Le profil de compatibilité concerne uniquement Vulkan sur pilote propriétaire Qualcomm. Il ajuste certains shaders ; il ne garantit ni stabilité ni gain de vitesse et ne change pas les poids. Son changement exige un nouveau processus de calcul, sinon une erreur explicite est présentée."))
            nativeCpuWeightsChoice = CheckBox(activity).apply {
                text = "Poids CPU natifs — éviter les conversions internes de quantification"
                textSize = 18f; minimumHeight = dp(64); minHeight = dp(64)
                isChecked = storedLoadSettings.nativeCpuWeights
                isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
            }.also { addView(it); loadingControls.add(it) }
            addView(label("Cette option conserve les poids CPU dans leur format natif et évite certains buffers de conversion accélérée. Elle peut ralentir le CPU et modifier les résultats numériques par rapport aux kernels accélérés. Le fichier GGUF n’est pas réécrit. Son bénéfice mémoire et sa fidélité doivent être mesurés."))
            fun preset(title: String, batch: Int, micro: Int) {
                action(title) {
                    batchField?.setText(batch.toString()); microBatchField?.setText(micro.toString())
                }.also { addView(it); loadingControls.add(it) }
            }
            preset("Lots standard : 512 et 128 tokens", 512, 128)
            preset("Réduire la mémoire temporaire : 128 et 32 tokens", 128, 32)
            preset("Profil mémoire prudent : lot 64 et micro-lot 16 tokens", 64, 16)
            addView(label("Ces réglages conservent les poids, le texte complet, le contexte de 2048 tokens et la limite de sortie. Des lots plus petits peuvent ralentir l’entrée. Répartir les couches entre CPU et GPU ne crée pas de RAM supplémentaire. En mode CPU, le nombre de couches GPU est ignoré. Le cache compact peut demander un recalcul du contexte ; son bénéfice et la fidélité doivent être vérifiés. Aucune de ces commandes ne lance de calcul."))
        }
        profileSpinner = Spinner(activity).apply {
            id = View.generateViewId(); contentDescription = "Profil de traduction du modèle GGUF"
            minimumHeight = dp(64)
            adapter = choices(PROFILES.map { it.label })
            setSelection(PROFILES.indexOf(profile).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    profile = PROFILES[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }.also { spinner ->
            addView(label("Profil de traduction du modèle GGUF").apply { labelFor = spinner.id })
            addView(spinner)
        }
        addView(label("Automatique privilégie les profils de traduction spécialisés, puis utilise le format de conversation reconnu dans le GGUF. Le profil Gabarit intégré exige un format exploitable ; son absence ou une incompatibilité est signalée. Les gabarits personnalisés et les architectures absentes du moteur peuvent rester incompatibles. Le choix du profil ne télécharge rien, ne change pas les poids et ne démarre aucun calcul. La mémoire et le moteur de calcul doivent aussi convenir au modèle ; la qualité de traduction reste à vérifier."))
        if (showContextChoice) {
            contextPolicySpinner = Spinner(activity).apply {
                id = View.generateViewId(); contentDescription = "Contexte des sous-titres GGUF"
                minimumHeight = dp(64)
                adapter = choices(CONTEXT_POLICIES.map { it.label })
                setSelection(CONTEXT_POLICIES.indexOf(contextPolicy).coerceAtLeast(0))
            }.also { spinner ->
                addView(label("Contexte des sous-titres GGUF").apply { labelFor = spinner.id })
                addView(spinner)
            }
            addView(label("Le contexte peut aider à interpréter une réplique ou propager une erreur. Aucun choix ne garantit une meilleure traduction. Deux échanges conserve le comportement actuel ; occurrence seule retire les échanges précédents ; les blocs conservent jusqu’à cinq échanges puis recommencent tous les six sous-titres. Si le budget du modèle est dépassé, seules des paires anciennes entières sont retirées ; le texte courant reste complet. Ce réglage ne change ni les poids ni la précision du modèle et ne déclenche aucun calcul."))
        }
        updateButtons()
    }

    private fun modelDescription(): String = model?.let {
        String.format(Locale.FRANCE, "Modèle prêt : %.2f Gio ; empreinte %s. Le chargement sera vérifié lors de la traduction.",
            it.bytes / 1_073_741_824.0, it.sha256.take(12))
    } ?: if (store.exists()) "Un modèle du diagnostic est disponible. Prépare son empreinte pour l’utiliser en traduction."
        else "Aucun modèle GGUF préparé pour la traduction."

    private fun updateButtons() {
        chooseButton?.isEnabled = active && !busy
        existingButton?.isEnabled = active && !busy && store.exists()
        cancelButton?.isEnabled = active && busy
        executionSpinner?.isEnabled = active && !busy
        profileSpinner?.isEnabled = active && !busy
        contextPolicySpinner?.isEnabled = active && !busy
        loadingControls.forEach { it.isEnabled = active && !busy }
    }
    fun saveChoices(selectedExecution: String = execution, selectedProfile: LocalGgufProfile = profile,
        selectedContextPolicy: LocalGgufContextPolicy = contextPolicy,
        selectedLoadSettings: LocalGgufLoadSettings = loadSettings) {
        require(selectedExecution in EXECUTION_MODES)
        selectedLoadSettings.validate()
        storedLoadSettings = selectedLoadSettings
        preferences.edit().putString("local-execution", selectedExecution)
            .putString("local-profile", selectedProfile.name)
            .putString("local-context-policy", selectedContextPolicy.name)
            .putInt("local-gpu-layers", selectedLoadSettings.gpuLayers).putInt("local-batch-tokens", selectedLoadSettings.batchTokens)
            .putInt("local-micro-batch-tokens", selectedLoadSettings.microBatchTokens)
            .putBoolean("local-compact-swa", selectedLoadSettings.compactSwa)
            .putString("local-kv-cache-type", selectedLoadSettings.kvCacheType)
            .putString("local-flash-attention", selectedLoadSettings.flashAttentionPolicy)
            .putBoolean("local-qualcomm-compatibility", selectedLoadSettings.qualcommCompatibility)
            .putBoolean("local-native-cpu-weights", selectedLoadSettings.nativeCpuWeights).apply()
    }
    fun setActive(value: Boolean) {
        active = value
        if (!value) { waitingPicker = false; if (busy) cancel() }
        updateButtons()
    }
    private fun choose() {
        if (closed || !active || busy) return
        if (translationBusy()) {
            onMessage("Termine ou annule la préparation en cours avant de remplacer le modèle GGUF.")
            return
        }
        waitingPicker = true
        try { launchPicker() }
        catch (_: Exception) {
            waitingPicker = false
            onMessage("Aucun sélecteur Android ne permet de choisir le modèle GGUF.")
        }
    }
    fun selected(uri: Uri?) {
        val requested = waitingPicker
        waitingPicker = false
        if (!requested || closed || !active || uri == null) return
        if (uri.scheme != "content") {
            onMessage("Choisis le fichier GGUF avec le sélecteur Android."); return
        }
        runImport { cancelled, progress -> store.importModel(uri, cancelled, progress) }
    }
    private fun runImport(operation: (() -> Boolean, (Long) -> Unit) -> LocalGgufModel) {
        if (closed || !active || busy) return
        if (translationBusy()) {
            onMessage("Termine ou annule la préparation en cours avant de remplacer le modèle GGUF.")
            return
        }
        busy = true
        val owner = ++generation
        val token = AtomicBoolean(false).also { cancellation = it }
        status?.text = "Préparation de la copie privée et de l’empreinte du modèle GGUF…"
        updateButtons()
        worker.execute {
            var lastProgress = 0L
            val result = runCatching {
                operation({ token.get() || Thread.currentThread().isInterrupted }) { bytes ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 500) {
                        lastProgress = now
                        main.post {
                            if (!closed && owner == generation && !token.get())
                                status?.text = String.format(Locale.FRANCE, "Préparation du modèle : %.2f Gio lus…", bytes / 1_073_741_824.0)
                        }
                    }
                }
            }
            main.post {
                if (!closed && owner == generation) {
                    busy = false
                    result.fold(onSuccess = {
                        // Returning a descriptor proves the private copy and
                        // its identity were committed. A later UI cancellation
                        // cannot restore or truthfully display the old model.
                        model = it
                        val message = if (token.get())
                            "Le modèle GGUF a été préparé avant l’annulation. La copie actuelle est prête ; l’original est conservé."
                        else "Modèle GGUF prêt. Indexe la piste puis confirme la traduction locale."
                        status?.text = message + "\n" + modelDescription()
                        if (active) {
                            onChanged()
                            onMessage(message)
                        }
                    }, onFailure = {
                        val message = when {
                            token.get() -> "Préparation annulée ; le fichier original est conservé."
                            it is LocalGgufModelBusyException -> "Le modèle est utilisé par un autre traitement. Termine ou annule ce traitement avant de le remplacer."
                            else -> "Impossible de préparer ce modèle GGUF. Vérifie le fichier et l’espace disponible ; l’original est conservé."
                        }
                        status?.text = message + "\n" + modelDescription()
                        if (active) onMessage(message)
                    })
                    updateButtons()
                }
            }
        }
    }
    fun cancel() {
        if (closed || !busy) return
        cancellation.set(true)
        status?.text = "Annulation de la préparation du modèle GGUF…"
        cancelButton?.isEnabled = false
    }
    override fun close() {
        if (closed) return
        closed = true; waitingPicker = false; generation++; cancellation.set(true)
        worker.shutdownNow(); main.removeCallbacksAndMessages(null)
        status = null; chooseButton = null; existingButton = null; cancelButton = null
        executionSpinner = null; profileSpinner = null; contextPolicySpinner = null
        gpuLayersField = null; batchField = null; microBatchField = null; compactSwaChoice = null; quantizedKvChoice = null
        loadingControls.clear()
    }
    companion object {
        private val CONTEXT_POLICIES = LocalGgufContextPolicy.entries
        private val FLASH_POLICIES = listOf("AUTO", "ENABLED", "DISABLED")
        private val EXECUTION_MODES = listOf("AUTO", "CPU", "OPENCL", "VULKAN", "HEXAGON")
        private val PROFILES = LocalGgufProfile.entries
    }
}
