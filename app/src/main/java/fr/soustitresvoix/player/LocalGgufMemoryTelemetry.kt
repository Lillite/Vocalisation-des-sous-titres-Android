package fr.soustitresvoix.player

import org.json.JSONArray
import org.json.JSONObject

/** Bounded allocator accounting; no path, model text or arbitrary driver description is retained. */
class LocalGgufMemoryTelemetry private constructor(
    val estimates: List<Row>, val breakdown: List<Row>, val samples: List<Sample>,
    val openCl: Map<String, Long>, val vulkanObserved: Boolean, val vulkan: Map<String, Long>,
    val vulkanBudget: VulkanBudget?
) {
    data class Row(val device: String, val modelBytes: Long, val contextBytes: Long, val computeBytes: Long)
    data class Sample(val stage: String, val residentBytes: Long, val peakRssBytes: Long, val availableRamBytes: Long)
    data class VulkanBudget(val observed: Boolean, val extension: Boolean, val budgetBytes: Long,
        val heapUsageBytes: Long, val freeBytes: Long, val heapCount: Int)

    fun render(): String = buildString {
        if (estimates.isNotEmpty()) {
            append("\nEstimation native avant allocation (ne garantit pas que le modèle tienne) :")
            estimates.forEach { appendRow(it) }
        }
        if (breakdown.isNotEmpty()) {
            append("\nVentilation des buffers natifs :")
            breakdown.forEach { appendRow(it) }
            append("\nCes octets de buffers ne sont pas une comptabilité de toute la RAM ; ne pas les additionner au RSS ni compter deux fois la mémoire partagée.")
        }
        samples.forEach {
            append("\nRelevé mémoire "); append(it.stage)
            append(" : RSS "); append(if (it.residentBytes < 0) "inconnu" else "${it.residentBytes} octets")
            append(" ; pic du processus "); append(it.peakRssBytes)
            append(" octets ; RAM disponible "); append(if (it.availableRamBytes < 0) "inconnue" else "${it.availableRamBytes} octets")
        }
        if (openCl.values.any { it > 0 }) {
            append("\nRéserves OpenCL observées : scratch courant "); append(openCl.getValue("scratchCurrentBytes"))
            append(" ; maximum scratch "); append(openCl.getValue("scratchPeakBytes"))
            append(" ; maximum tampon de transfert "); append(openCl.getValue("uploadPeakBytes")); append(" octets.")
            append("\nAllocations OpenCL refusées : "); append(openCl.getValue("allocationFailures"))
            append(" ; replis mémoire hôte : "); append(openCl.getValue("hostFallbacks"))
            append(" ; vues en pool : "); append(openCl.getValue("poolEntries"))
            append(" ; octets de vues/alias : "); append(openCl.getValue("poolBytes"))
            append(". Les vues peuvent partager des buffers ; ces octets ne sont pas une consommation physique additionnelle.")
            append("\nRéserves initiales OpenCL non demandées : "); append(openCl.getValue("skippedInitialBytes"))
            append(" octets ; ce compteur ne mesure pas une économie de RAM physique.")
        }
        if (vulkanObserved) {
            append("\nCompteurs du backend Vulkan depuis le démarrage du processus : courant "); append(vulkan.getValue("allocatedBytes"))
            append(" ; maximum "); append(vulkan.getValue("peakAllocatedBytes")); append(" octets")
            append(" ; allocations "); append(vulkan.getValue("allocations"))
            append(" ; échecs "); append(vulkan.getValue("allocationFailures"))
            append(" ; plus grande allocation "); append(vulkan.getValue("largestAllocationBytes")); append(" octets.")
            append("\nSous-ensembles Vulkan : mémoire importée "); append(vulkan.getValue("importedBytes"))
            append(" ; visible de l’hôte "); append(vulkan.getValue("hostVisibleBytes"))
            append(" octets. Ces compteurs peuvent se recouvrir et chevaucher le RSS ; ne pas les additionner. Ils ne mesurent pas le budget total du pilote.")
        }
        vulkanBudget?.takeIf { it.observed }?.let { budget ->
            if (!budget.extension) append("\nBudget Vulkan EXT : extension non exposée ; budget et mémoire libre du pilote inconnus.")
            else {
                append("\nBudget Vulkan EXT estimé : "); append(budget.budgetBytes)
                append(" ; usage des heaps "); append(budget.heapUsageBytes)
                append(" ; marge estimée "); append(budget.freeBytes)
                append(" octets ; heaps retenus "); append(budget.heapCount)
                append(". Budget dynamique fourni par le pilote, sans garantie d’allocation ; ne pas l’additionner au RSS ou à MemAvailable.")
            }
        }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("memoryEstimateAvailable", estimates.isNotEmpty()).put("memoryBreakdownAvailable", breakdown.isNotEmpty())
        .put("memoryEstimate", rowsJson(estimates)).put("memoryBreakdown", rowsJson(breakdown))
        .put("memorySamples", JSONArray().apply { samples.forEach { sample -> put(JSONObject()
            .put("stage", sample.stage).put("residentBytes", sample.residentBytes).put("peakRssBytes", sample.peakRssBytes)
            .put("availableRamBytes", sample.availableRamBytes)) } })
        .put("openClMemory", JSONObject().apply { openCl.forEach { (key, value) -> put(key, value) } })
        .put("vulkanMemory", JSONObject().put("observed", vulkanObserved).apply { vulkan.forEach { (key, value) -> put(key, value) } })
        .apply { vulkanBudget?.let { put("vulkanBudget", JSONObject().put("observed", it.observed)
            .put("extension", it.extension).put("budgetBytes", it.budgetBytes).put("heapUsageBytes", it.heapUsageBytes)
            .put("freeBytes", it.freeBytes).put("heapCount", it.heapCount)) } }

    private fun rowsJson(rows: List<Row>) = JSONArray().apply { rows.forEach { row -> put(JSONObject()
        .put("device", row.device).put("modelBytes", row.modelBytes).put("contextBytes", row.contextBytes).put("computeBytes", row.computeBytes)) } }

    private fun StringBuilder.appendRow(row: Row) {
        append("\n"); append(row.device); append(" : poids "); append(row.modelBytes)
        append(" ; contexte "); append(row.contextBytes); append(" ; calcul "); append(row.computeBytes); append(" octets.")
    }

    companion object {
        private const val MAX_BYTES = 1L shl 44
        private val DEVICES = setOf("HOST", "CPU", "OPENCL", "VULKAN", "HEXAGON", "OTHER")
        private val STAGES = setOf("BEFORE_MODEL_LOAD", "AFTER_MODEL_LOAD", "AFTER_CONTEXT_INIT", "BEFORE_REQUEST",
            "BEFORE_PREFILL", "AFTER_PREFILL", "AFTER_GENERATION")
        private val OPENCL_FIELDS = setOf("scratchCurrentBytes", "scratchPeakBytes", "uploadPeakBytes", "allocationFailures",
            "hostFallbacks", "poolEntries", "poolBytes", "skippedInitialBytes")
        private val VULKAN_FIELDS = setOf("allocatedBytes", "peakAllocatedBytes", "allocations", "allocationFailures",
            "largestAllocationBytes", "importedBytes", "hostVisibleBytes")

        internal fun parse(response: JSONObject): LocalGgufMemoryTelemetry? {
            val fields = setOf("memoryEstimate", "memoryEstimateAvailable", "memoryBreakdown", "memoryBreakdownAvailable",
                "memorySamples", "openClMemory", "vulkanMemory", "vulkanBudget")
            if (fields.none(response::has)) return null
            fun flag(key: String): Boolean = if (!response.has(key)) false
                else response.get(key) as? Boolean ?: error("Invalid memory flag")
            fun rows(key: String, available: Boolean): List<Row> {
                val array = if (response.has(key)) response.get(key) as? JSONArray ?: error("Invalid memory rows") else JSONArray()
                require(array.length() <= 6 && (available || array.length() == 0))
                return (0 until array.length()).map {
                    val value = array.getJSONObject(it)
                    require(value.keys().asSequence().toSet() == setOf("device", "modelBytes", "contextBytes", "computeBytes"))
                    val device = value.get("device") as? String ?: error("Invalid device")
                    require(device in DEVICES)
                    Row(device, number(value, "modelBytes"), number(value, "contextBytes"), number(value, "computeBytes"))
                }
            }
            val estimates = rows("memoryEstimate", flag("memoryEstimateAvailable"))
            val breakdown = rows("memoryBreakdown", flag("memoryBreakdownAvailable"))
            val array = if (response.has("memorySamples")) response.get("memorySamples") as? JSONArray
                ?: error("Invalid samples") else JSONArray()
            require(array.length() <= 8)
            val samples = (0 until array.length()).map {
                val value = array.getJSONObject(it)
                require(value.keys().asSequence().toSet() == setOf("stage", "residentBytes", "peakRssBytes", "availableRamBytes"))
                val stage = value.get("stage") as? String ?: error("Invalid sample stage")
                require(stage in STAGES)
                Sample(stage, number(value, "residentBytes", -1), number(value, "peakRssBytes"), number(value, "availableRamBytes", -1))
            }
            val openCl = if (response.has("openClMemory")) {
                val value = response.get("openClMemory") as? JSONObject ?: error("Invalid OpenCL memory")
                require(value.keys().asSequence().toSet() == OPENCL_FIELDS)
                OPENCL_FIELDS.associateWith { number(value, it) }
            } else OPENCL_FIELDS.associateWith { 0L }
            var vulkanObserved = false
            val vulkan = if (response.has("vulkanMemory")) {
                val value = response.get("vulkanMemory") as? JSONObject ?: error("Invalid Vulkan memory")
                require(value.keys().asSequence().toSet() == VULKAN_FIELDS + "observed")
                vulkanObserved = value.get("observed") as? Boolean ?: error("Invalid Vulkan observation")
                VULKAN_FIELDS.associateWith { number(value, it) }.also { require(vulkanObserved || it.values.all { bytes -> bytes == 0L }) }
            } else VULKAN_FIELDS.associateWith { 0L }
            val budget = if (response.has("vulkanBudget")) {
                val value = response.get("vulkanBudget") as? JSONObject ?: error("Invalid Vulkan budget")
                require(value.keys().asSequence().toSet() == setOf("observed", "extension", "budgetBytes", "heapUsageBytes", "freeBytes", "heapCount"))
                val observed = value.get("observed") as? Boolean ?: error("Invalid budget observation")
                val extension = value.get("extension") as? Boolean ?: error("Invalid budget extension")
                val total = number(value, "budgetBytes"); val usage = number(value, "heapUsageBytes")
                val free = number(value, "freeBytes"); val heaps = number(value, "heapCount")
                require(heaps in 0..16 && free <= total && (observed || !extension))
                require(extension || total == 0L && usage == 0L && free == 0L && heaps == 0L)
                VulkanBudget(observed, extension, total, usage, free, heaps.toInt())
            } else null
            return LocalGgufMemoryTelemetry(estimates, breakdown, samples, openCl, vulkanObserved, vulkan, budget)
        }

        private fun number(value: JSONObject, key: String, minimum: Long = 0): Long {
            val number = when (val raw = value.get(key)) {
                is Int -> raw.toLong()
                is Long -> raw
                else -> error("Non integral memory observation")
            }
            require(number in minimum..MAX_BYTES)
            return number
        }
    }
}
