package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed synthetic content only. CPU and strict OpenCL runs own one model sequentially. */
internal class LocalGgufBenchmark(
    context: Context,
    private val onChanged: (State) -> Unit,
    private val engineFactory: (TranslationProviderConfig) -> TranslationEngine = {
        TranslationEngines.create(it, context = context.applicationContext)
    },
    private val snapshot: () -> Environment = { Environment.read(context.applicationContext) },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val closeEngine: (TranslationEngine) -> Unit = { engine ->
        if (engine is LocalGgufTranslationEngine) engine.closeAndAwait(timeoutMs = 10_000)
        else engine.close()
    }
) : AutoCloseable {
    data class Platform(val androidRelease: String, val api: Int, val manufacturer: String,
        val model: String, val abis: List<String>, val appVersion: String?, val appVersionCode: Long?,
        val startedAtUtc: String) {
        fun render(): String = buildString {
            append("Début de l’essai (UTC) : "); append(startedAtUtc)
            append("\nApplication : "); append(appVersion ?: "version indisponible")
            appVersionCode?.let { append(" ; code "); append(it) }
            append("\nPlateforme rapportée par Android : "); append(androidRelease); append(" ; API "); append(api)
            append("\nAppareil déclaré : "); append(manufacturer); append(' '); append(model)
            append(" ; ABI : "); append(abis.joinToString(", ").ifEmpty { "indisponible" })
        }
        companion object {
            @Suppress("DEPRECATION")
            fun read(context: Context): Platform {
                val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                fun bounded(value: String?) = value.orEmpty().filter { !it.isISOControl() }.take(128).ifBlank { "indisponible" }
                return Platform(bounded(Build.VERSION.RELEASE), Build.VERSION.SDK_INT,
                    bounded(Build.MANUFACTURER), bounded(Build.MODEL), Build.SUPPORTED_ABIS.take(8).map(::bounded),
                    info?.versionName?.let(::bounded), info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() },
                    Instant.now().toString())
            }
        }
    }
    data class Environment(val availableRamBytes: Long = 0, val thermalStatus: Int? = null,
        val batteryTenthsCelsius: Int? = null) {
        fun render(): String = buildString {
            append("RAM disponible : ")
            append(if (availableRamBytes > 0) String.format(Locale.FRANCE, "%.2f Gio", availableRamBytes / 1_073_741_824.0) else "indisponible")
            append(" ; état thermique Android : "); append(thermalStatus?.let(::thermalLabel) ?: "indisponible")
            batteryTenthsCelsius?.let {
                append(" ; température de batterie : "); append(String.format(Locale.FRANCE, "%.1f °C", it / 10.0))
                append(" (ce n’est pas la température CPU)")
            }
        }
        companion object {
            fun read(context: Context): Environment {
                val ram = runCatching {
                    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }.availMem
                }.getOrDefault(0)
                val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
                    context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                }.getOrNull() else null
                val battery = runCatching {
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        ?.takeIf { it != Int.MIN_VALUE }
                }.getOrNull()
                return Environment(ram, thermal, battery)
            }
            private fun thermalLabel(value: Int): String = when (value) {
                0 -> "aucune limitation déclarée"
                1 -> "léger"
                2 -> "modéré"
                3 -> "sévère"
                4 -> "critique"
                5 -> "urgence"
                6 -> "arrêt imminent"
                else -> "valeur $value"
            }
        }
    }
    data class Sample(val number: Int, val output: String, val wallMs: Long,
        val performance: LocalTranslationPerformance?)
    data class Run(val requestedMode: String, val samples: List<Sample>, val before: Environment,
        val after: Environment, val wallMs: Long, val failure: String? = null,
        val cleanupConfirmed: Boolean = true, val loadMs: Long = 0,
        val lastPerformance: LocalTranslationPerformance? = null)
    data class State(val status: String = "Prêt. Aucun essai comparatif lancé.", val busy: Boolean = false,
        val modelSha256: String = "", val modelBytes: Long = 0, val threads: Int = 4,
        val profile: LocalGgufProfile = LocalGgufProfile.AUTO, val runs: List<Run> = emptyList(),
        val currentPerformance: LocalTranslationPerformance? = null, val platform: Platform? = null) {
        fun report(): String = buildString {
            append("Comparatif local CPU / GPU — texte synthétique intégré\n")
            append(status)
            if (modelSha256.isEmpty()) { append("\nAucun modèle n’a été exécuté."); return@buildString }
            platform?.let { append('\n'); append(it.render()) }
            append("\nModèle : "); append(modelBytes); append(" octets ; SHA-256 "); append(modelSha256)
            append("\nProfil demandé : "); append(profile.label); append(" ; threads CPU demandés : "); append(threads)
            append("\nSource anglaise fixe : "); append(SOURCE)
            append("\nCible : français ; contexte personnel vide ; mêmes poids, profil et limites de génération pour les deux modes.")
            append("\nOrdre fixe : CPU puis GPU OpenCL strict. Deux passages identiques par mode : premier passage puis réutilisation éventuelle du préfixe.")
            append("\nLes durées d’appel et de mode comprennent déjà le chargement éventuel ; sa mesure séparée détaille le total.")
            append("\nLes modèles ne sont jamais chargés simultanément. L’ordre, le cache système, la chauffe, la lecture éventuelle et les autres applications peuvent influencer ces mesures ; leur activité n’est pas mesurée ici. Ces mesures ne prouvent pas la qualité sur un film.\n")
            currentPerformance?.let {
                append("\nDernières mesures de l’essai en cours :\n"); append(it.render()); append('\n')
            }
            runs.forEach { run ->
                append("\nMode demandé : "); append(run.requestedMode)
                append("\nAvant : "); append(run.before.render())
                append("\nChargement du modèle : "); append(if (run.loadMs > 0) "${run.loadMs} ms" else
                    "aucune mesure positive de chargement réussi reçue ; un chargement a pu être tenté")
                run.samples.forEach { sample ->
                    append("\n\nPassage "); append(sample.number)
                    append(if (sample.number == 1) " — premier appel" else " — même texte dans la même session")
                    append("\nDurée de l’appel complet observée par l’application : "); append(sample.wallMs); append(" ms")
                    append("\n"); append(sample.performance?.render() ?: "Mesures natives indisponibles ; aucun moteur réel déduit.")
                    append("\nSortie sur le texte synthétique uniquement :\n"); append(sample.output)
                }
                append("\n\nDurée du mode, chargement et libération inclus : "); append(run.wallMs); append(" ms")
                append("\nAprès : "); append(run.after.render())
                append("\nLibération avant le mode suivant : "); append(if (run.cleanupConfirmed) "confirmée" else "non confirmée ; aucun autre mode lancé")
                run.failure?.let { append("\nRésultat : "); append(it) }
                if (run.failure != null && run.lastPerformance != null) {
                    append("\nDernières mesures reçues avant l’arrêt :\n"); append(run.lastPerformance.render())
                }
                if (run.failure == null) append("\nRésultat : deux réponses complètes validées.")
                append('\n')
            }
            if (runs.size == 2 && runs.all { it.failure == null && it.samples.size == 2 }) {
                val same = runs[0].samples.map { it.output } == runs[1].samples.map { it.output }
                append(if (same) "\nLes sorties CPU et GPU sont identiques sur ces passages ; cela ne prouve pas leur fidélité linguistique."
                    else "\nLes sorties CPU et GPU diffèrent : examine les textes avant d’interpréter les durées. Aucun classement de qualité automatique.")
            }
            append("\nLa mémoire résidente et son pic concernent tout le processus de calcul. Les relevés RAM et thermiques sont ponctuels. Aucun test NPU ni classement automatique de fidélité linguistique.")
        }
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var sequence = 0L
    @Volatile private var closed = false
    private var cancelled = AtomicBoolean(false)
    @Volatile var state = State()
        private set

    fun start(model: LocalGgufModel, threads: Int, profile: LocalGgufProfile) {
        if (closed || state.busy) return
        if (threads !in 1..8) {
            publish(state.copy(status = "Nombre de threads invalide : indique un entier de 1 à 8.")); return
        }
        val owner = ++sequence
        val token = AtomicBoolean(false).also { cancelled = it }
        publish(State("Comparatif demandé. Sa durée dépend du modèle ; tu peux l’annuler.", true,
            model.sha256, model.bytes, threads, profile, platform = Platform.read(app)))
        worker.execute {
            val results = mutableListOf<Run>()
            var fatal = false
            try {
                LocalGgufModelStore(app).acquire(model).use {
                    for (mode in listOf("CPU", "OPENCL")) {
                        if (token.get() || closed) break
                        val before = snapshot()
                        val started = clock()
                        val samples = mutableListOf<Sample>()
                        var engine: TranslationEngine? = null
                        var latest: LocalTranslationPerformance? = null
                        var failure: String? = null
                        var clean = true
                        var loadMs = 0L
                        try {
                            val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
                                localModel = model, localThreads = threads, localContext = "",
                                localExecution = mode, localProfile = profile)
                            engine = engineFactory(config)
                            (engine as? LocalTranslationPerformanceSource)?.setPerformanceListener { performance ->
                                latest = performance
                                if (performance.phase == "load") loadMs = performance.loadMs
                                post(owner) {
                                    it.copy(status = "Mode $mode : " + if (performance.phase == "load")
                                        "modèle chargé ; calcul du texte intégré…" else "passage calculé…",
                                        currentPerformance = performance)
                                }
                            }
                            for (number in 1..2) {
                                if (token.get() || closed) throw TranslationCancelledException()
                                post(owner) { it.copy(status = "Mode $mode, passage $number sur 2. Durée inconnue ; annulation disponible.",
                                    currentPerformance = if (number == 1) null else it.currentPerformance) }
                                latest = null
                                val callStarted = clock()
                                val output = engine.translate(listOf(SOURCE), "en", "fr") { token.get() || closed }
                                if (token.get() || closed) throw TranslationCancelledException()
                                if (output.size != 1 || output.single().isBlank()) throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                                val performance = latest?.takeIf { it.phase == "generation" }
                                if (performance == null)
                                    throw BenchmarkFailure("Les mesures du moteur réellement exécuté sont absentes ; ce passage n’est pas validé.")
                                if (performance.requestedMode != mode)
                                    throw BenchmarkFailure("Les mesures ne correspondent pas au mode demandé ; ce passage n’est pas validé.")
                                if (mode == "CPU" && (performance.backend != "CPU" || performance.gpuObserved || performance.gpuGraphNodes > 0))
                                    throw BenchmarkFailure("Le passage CPU n’a pas confirmé une exécution CPU seule ; ce passage n’est pas validé.")
                                if (mode == "OPENCL" && (!performance.gpuObserved || performance.gpuGraphNodes <= 0))
                                    throw TranslationFailure(TranslationFailure.Reason.LOCAL_BACKEND)
                                samples += Sample(number, output.single(), (clock() - callStarted).coerceAtLeast(0), performance)
                            }
                        } catch (_: TranslationCancelledException) {
                            failure = "Essai annulé ; aucune réponse partielle n’est déclarée complète."
                        } catch (error: BenchmarkFailure) {
                            failure = error.message
                        } catch (error: TranslationFailure) {
                            failure = error.message ?: "Échec du calcul local."
                        } catch (_: Exception) {
                            failure = "Essai local interrompu ; moteur et résultat non validés."
                        } finally {
                            if (engine != null) {
                                try { closeEngine(engine) }
                                catch (_: Exception) {
                                    clean = false; fatal = true
                                    failure = (failure?.let { "$it " } ?: "") + "La libération du modèle n’a pas été confirmée ; le comparatif s’arrête."
                                } finally { runCatching { engine.close() } }
                            }
                        }
                        results += Run(mode, samples.toList(), before, snapshot(), (clock() - started).coerceAtLeast(0), failure, clean, loadMs, latest)
                        val copy = results.toList()
                        post(owner) { it.copy(runs = copy, currentPerformance = null, status = if (failure == null) "Mode $mode terminé." else "Mode $mode : $failure") }
                        if (fatal || token.get() || closed || failure != null) break
                    }
                }
            } catch (_: LocalGgufModelBusyException) {
                fatal = true
                post(owner) { it.copy(status = "Le modèle est utilisé par un autre traitement. Termine ou annule ce traitement avant le comparatif.") }
            } catch (_: Exception) {
                fatal = true
                post(owner) { it.copy(status = "Le modèle a changé ou n’est plus disponible. Prépare le modèle actuel avant de relancer le comparatif.") }
            } finally {
                val copy = results.toList()
                post(owner) {
                    it.copy(busy = false, runs = copy, currentPerformance = null, status = when {
                        token.get() -> "Comparatif annulé. Les résultats des passages terminés sont conservés."
                        fatal -> it.status
                        copy.size == 2 && copy.all { run -> run.failure == null } -> "Comparatif terminé. Compare les temps, les moteurs observés et les sorties synthétiques."
                        copy.any { run -> run.failure != null } -> "Comparatif interrompu après un échec. Consulte le résultat du mode concerné."
                        else -> "Comparatif terminé sans exécution GPU validée."
                    })
                }
            }
        }
    }
    fun cancel() {
        if (closed || !state.busy) return
        cancelled.set(true)
        publish(state.copy(status = "Annulation du comparatif demandée ; libération du modèle en cours…"))
    }
    private fun publish(value: State) { state = value; onChanged(value) }
    private fun post(owner: Long, update: (State) -> State) {
        main.post { if (!closed && owner == sequence) publish(update(state)) }
    }
    override fun close() {
        if (closed) return
        closed = true; sequence++; cancelled.set(true); worker.shutdownNow(); main.removeCallbacksAndMessages(null)
    }
    companion object {
        const val SOURCE = "The train leaves at 07:30. Please keep the blue door closed."
    }
    private class BenchmarkFailure(message: String) : Exception(message)
}
