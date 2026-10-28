package fr.soustitresvoix.player

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private var cancellation = AtomicBoolean(false)
    private var generation = 0L
    private var closed = false
    private var active = true
    private var waitingPicker = false
    var busy = false
        private set
    var model: LocalGgufModel? = store.current()
        private set
    private var status: TextView? = null
    private var chooseButton: Button? = null
    private var existingButton: Button? = null
    private var cancelButton: Button? = null

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 18f; setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun action(value: String, block: () -> Unit) = Button(activity).apply {
        text = value; textSize = 18f; isAllCaps = false; minimumHeight = dp(64); minHeight = dp(64)
        setOnClickListener { block() }
    }
    fun view(): View = LinearLayout(activity).apply {
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
                        if (!token.get()) {
                            model = it
                            status?.text = modelDescription()
                            onChanged()
                            onMessage("Modèle GGUF prêt. Indexe la piste puis confirme la traduction locale.")
                        } else status?.text = "Préparation annulée. " + modelDescription()
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
    }
}
