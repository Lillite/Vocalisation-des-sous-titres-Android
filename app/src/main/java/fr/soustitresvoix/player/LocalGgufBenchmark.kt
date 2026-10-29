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
    },
    private val playbackSnapshot: () -> PlaybackObservation? = { null }
) : AutoCloseable {
    enum class Scenario { SHORT, VARIED }
    enum class Order { CPU_THEN_GPU, GPU_THEN_CPU }
    data class Options(val scenario: Scenario = Scenario.SHORT,
        val order: Order = Order.CPU_THEN_GPU, val reopenForLoad: Boolean = false)
    /** Immutable, read-only observations supplied by the player's application thread. */
    data class PlaybackObservation(val observedAtMs: Long = 0, val mediaGeneration: Long = 0,
        val mediaOpened: Boolean = false, val playing: Boolean = false, val positionMs: Long = 0,
        val droppedFrames: Long = 0, val buffering: Boolean = false, val wantsPlayback: Boolean = false,
        val speaking: Boolean = false)

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
        val performance: LocalTranslationPerformance?, val cue: LocalGgufBenchmarkCue? = null,
        val nativeCalls: List<LocalTranslationPerformance> = performance?.let { listOf(it) } ?: emptyList(),
        val environment: Environment? = null, val playback: PlaybackObservation? = null,
        val finishedAtMs: Long = 0)
    data class Run(val requestedMode: String, val samples: List<Sample>, val before: Environment,
        val after: Environment, val wallMs: Long, val failure: String? = null,
        val cleanupConfirmed: Boolean = true, val loadMs: Long = 0,
        val lastPerformance: LocalTranslationPerformance? = null, val reopening: Boolean = false,
        val loadPerformance: LocalTranslationPerformance? = null, val plannedSamples: Int = 2,
        val playbackBefore: PlaybackObservation? = null, val playbackAfter: PlaybackObservation? = null,
        val startedAtMs: Long = 0, val finishedAtMs: Long = 0)
    data class State(val status: String = "Prêt. Aucun essai comparatif lancé.", val busy: Boolean = false,
        val modelSha256: String = "", val modelBytes: Long = 0, val threads: Int = 4,
        val profile: LocalGgufProfile = LocalGgufProfile.AUTO, val runs: List<Run> = emptyList(),
        val currentPerformance: LocalTranslationPerformance? = null, val platform: Platform? = null,
        val options: Options = Options()) {
        fun report(): String = LocalGgufBenchmarkReport.render(this)
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var sequence = 0L
    @Volatile private var closed = false
    private var cancelled = AtomicBoolean(false)
    @Volatile var state = State()
        private set

    fun start(model: LocalGgufModel, threads: Int, profile: LocalGgufProfile, options: Options = Options()) {
        if (closed || state.busy) return
        if (threads !in 1..8) {
            publish(state.copy(status = "Nombre de threads invalide : indique un entier de 1 à 8.")); return
        }
        val owner = ++sequence
        val token = AtomicBoolean(false).also { cancelled = it }
        publish(State("Comparatif demandé. Sa durée dépend du modèle ; tu peux l’annuler.", true,
            model.sha256, model.bytes, threads, profile, platform = Platform.read(app), options = options))
        worker.execute {
            val results = mutableListOf<Run>()
            var fatal = false
            try {
                LocalGgufModelStore(app).acquire(model).use {
                    val modes = if (options.order == Order.CPU_THEN_GPU) listOf("CPU", "OPENCL") else listOf("OPENCL", "CPU")
                    sessions@ for (mode in modes) {
                        for (reopening in if (options.reopenForLoad) listOf(false, true) else listOf(false)) {
                            if (token.get() || closed) break@sessions
                            val planned = if (reopening) cues(options.scenario).take(1) else cues(options.scenario)
                            val before = snapshot()
                            val playbackBefore = readPlayback()
                            val started = clock()
                            val samples = mutableListOf<Sample>()
                            var engine: TranslationEngine? = null
                            var latest: LocalTranslationPerformance? = null
                            var loadPerformance: LocalTranslationPerformance? = null
                            var failure: String? = null
                            var clean = true
                            var loadMs = 0L
                            var activeFrames: MutableList<LocalTranslationPerformance>? = null
                            var tooManyFrames = false
                            try {
                                val config = TranslationProviderConfig(TranslationProvider.LOCAL_GGUF,
                                    localModel = model, localThreads = threads, localContext = "",
                                    localExecution = mode, localProfile = profile)
                                val activeEngine = engineFactory(config).also { engine = it }
                                (activeEngine as? LocalTranslationPerformanceSource)?.setPerformanceListener { performance ->
                                    latest = performance
                                    if (performance.phase == "load") {
                                        loadMs = performance.loadMs
                                        loadPerformance = performance
                                    } else if (performance.phase == "generation") activeFrames?.let { frames ->
                                        if (frames.size < MAX_PERFORMANCE_FRAMES) frames += performance else tooManyFrames = true
                                    }
                                    post(owner) {
                                        it.copy(status = "Mode $mode : " + if (performance.phase == "load")
                                            "modèle chargé ; calcul du texte intégré…" else "passage calculé…",
                                            currentPerformance = performance)
                                    }
                                }
                                planned.forEachIndexed { index, cue ->
                                    if (token.get() || closed) throw TranslationCancelledException()
                                    post(owner) { it.copy(status = "Mode $mode${if (reopening) ", réouverture" else ""}, " +
                                        "occurrence ${index + 1} sur ${planned.size}. Durée inconnue ; annulation disponible.",
                                        currentPerformance = if (index == 0) null else it.currentPerformance) }
                                    latest = null
                                    val frames = mutableListOf<LocalTranslationPerformance>()
                                    activeFrames = frames
                                    tooManyFrames = false
                                    val callStarted = clock()
                                    val output = try {
                                        if (options.scenario == Scenario.VARIED && activeEngine is ContextualTranslationEngine) {
                                            val history = samples.takeLast(2)
                                            activeEngine.translateWithContext(listOf(cue.source), "en", "fr",
                                                history.map { requireNotNull(it.cue).source }, history.map { it.output }) { token.get() || closed }
                                        } else activeEngine.translate(listOf(cue.source), "en", "fr") { token.get() || closed }
                                    } finally { activeFrames = null }
                                    val callFinished = clock()
                                    if (token.get() || closed) throw TranslationCancelledException()
                                    if (output.size != 1 || output.single().isBlank())
                                        throw TranslationFailure(TranslationFailure.Reason.RESPONSE)
                                    if (output.single().toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES)
                                        throw BenchmarkFailure("La réponse synthétique dépasse la taille prévue pour un rapport complet ; l’occurrence est refusée, sans tronquer le texte.")
                                    if (tooManyFrames) throw BenchmarkFailure("Trop de mesures natives pour cette occurrence ; aucun résultat partiel n’est validé.")
                                    if (frames.isEmpty()) throw BenchmarkFailure("Les mesures du moteur réellement exécuté sont absentes ; ce passage n’est pas validé.")
                                    frames.forEach { validatePerformance(it, mode) }
                                    val environmentAfter = snapshot()
                                    val playbackAfter = readPlayback()
                                    val playbackReadAt = clock()
                                    samples += Sample(index + 1, output.single(), (callFinished - callStarted).coerceAtLeast(0),
                                        frames.last(), cue, frames.toList(), environmentAfter, playbackAfter, playbackReadAt)
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
                                activeFrames = null
                                engine?.let { opened ->
                                    try { closeEngine(opened) }
                                    catch (_: Exception) {
                                        clean = false; fatal = true
                                        failure = (failure?.let { "$it " } ?: "") + "La libération du modèle n’a pas été confirmée ; le comparatif s’arrête."
                                    } finally { runCatching { opened.close() } }
                                }
                            }
                            val finished = clock()
                            val environmentAfter = snapshot()
                            val playbackAfter = readPlayback()
                            val playbackReadAt = clock()
                            results += Run(mode, samples.toList(), before, environmentAfter, (finished - started).coerceAtLeast(0),
                                failure, clean, loadMs, latest, reopening, loadPerformance, planned.size,
                                playbackBefore, playbackAfter, started, playbackReadAt)
                            val copy = results.toList()
                            post(owner) { it.copy(runs = copy, currentPerformance = null,
                                status = if (failure == null) "Mode $mode${if (reopening) ", réouverture" else ""} terminé." else "Mode $mode : $failure") }
                            if (fatal || token.get() || closed || failure != null) break@sessions
                        }
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
                val expected = if (options.reopenForLoad) 4 else 2
                post(owner) {
                    it.copy(busy = false, runs = copy, currentPerformance = null, status = when {
                        token.get() -> "Comparatif annulé. Les résultats des passages terminés sont conservés."
                        fatal -> it.status
                        copy.size == expected && copy.all { run -> run.failure == null } -> "Comparatif terminé. Compare les temps, les moteurs observés et les sorties synthétiques."
                        copy.any { run -> run.failure != null } -> "Comparatif interrompu après un échec. Consulte le résultat du mode concerné."
                        else -> "Comparatif terminé sans exécution GPU validée."
                    })
                }
            }
        }
    }
    private fun readPlayback(): PlaybackObservation? = runCatching { playbackSnapshot() }.getOrNull()?.takeIf {
        it.observedAtMs >= 0 && it.mediaGeneration >= 0 && it.positionMs >= 0 && it.droppedFrames >= 0
    }
    private fun validatePerformance(value: LocalTranslationPerformance, mode: String) {
        if (value.requestedMode != mode) throw BenchmarkFailure("Les mesures ne correspondent pas au mode demandé ; ce passage n’est pas validé.")
        if (value.phase != "generation" || value.promptMs < 0 || value.generationMs < 0 || value.generatedTokens < 0 ||
            value.promptTokens < 1 || value.cachedPromptTokens < 0 || value.promptEvaluatedTokens < 1 ||
            value.cachedPromptTokens.toLong() + value.promptEvaluatedTokens != value.promptTokens.toLong() || value.gpuGraphNodes < 0)
            throw BenchmarkFailure("Les mesures natives de cette occurrence sont incohérentes ; ce passage n’est pas validé.")
        if (mode == "CPU" && (value.backend != "CPU" || value.gpuObserved || value.gpuGraphNodes > 0 || value.openClTransport.isNotEmpty()))
            throw BenchmarkFailure("Le passage CPU n’a pas confirmé une exécution CPU seule ; ce passage n’est pas validé.")
        if (mode == "OPENCL" && (value.backend != "OPENCL" || value.openClTransport != "DIRECT" || !value.gpuObserved || value.gpuGraphNodes <= 0))
            throw TranslationFailure(TranslationFailure.Reason.LOCAL_BACKEND)
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
        private const val MAX_PERFORMANCE_FRAMES = 256
        // A fixed synthetic cue has a bounded response, keeping a complete report copyable.
        private const val MAX_OUTPUT_BYTES = 2_048
        fun cues(scenario: Scenario): List<LocalGgufBenchmarkCue> = when (scenario) {
            Scenario.VARIED -> LocalGgufBenchmarkCorpus.cues
            Scenario.SHORT -> listOf(LocalGgufBenchmarkCue("short-01", 0, 3_000, SOURCE, "dialogue"),
                LocalGgufBenchmarkCue("short-02", 4_000, 7_000, SOURCE, "dialogue"))
        }
    }
    private class BenchmarkFailure(message: String) : Exception(message)
}
