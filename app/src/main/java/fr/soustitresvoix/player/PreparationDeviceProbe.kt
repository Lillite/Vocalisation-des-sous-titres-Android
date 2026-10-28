package fr.soustitresvoix.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale

/** A declaration is not evidence that this codec, GPU or NPU ran an inference. */
internal data class PreparationCodecDeclaration(
    val name: String,
    val mimeTypes: List<String>,
    val hardwareAccelerated: Boolean?,
    val softwareOnly: Boolean?
)

/** No device identifier, selected file name, URI or subtitle text is collected. */
internal data class PreparationDeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidApi: Int,
    val abis: List<String>,
    val processorCount: Int,
    val totalRamBytes: Long?,
    val availableRamBytes: Long?,
    val lowMemory: Boolean?,
    val processPssBytes: Long?,
    val nativeHeapBytes: Long?,
    val thermalStatus: Int?,
    val measuredAtElapsedMs: Long,
    val codecs: List<PreparationCodecDeclaration>,
    val notes: List<String> = emptyList(),
    val storageAvailableBytes: Long? = null
) {
    fun render(): String = buildString {
        append("Mesures du téléphone à la demande\n")
        append("Appareil : $manufacturer $model\nAndroid : API $androidApi\n")
        append("Architectures : ${abis.joinToString()}\n")
        append("Processeurs disponibles pour l’application : $processorCount\n")
        append("RAM totale rapportée par Android, hors extension par stockage : ${size(totalRamBytes)}\n")
        append("RAM disponible à cet instant : ${size(availableRamBytes)}\n")
        append("État de mémoire basse : ${answer(lowMemory)}\n")
        append("Mémoire PSS du processus à cet instant : ${size(processPssBytes)}\n")
        append("Tas natif alloué à cet instant : ${size(nativeHeapBytes)}\n")
        append("Espace disponible pour les fichiers privés de l’application : ${size(storageAvailableBytes)}\n")
        append("État thermique Android : ${thermal(thermalStatus)}\n")
        append("Ces valeurs sont ponctuelles ; la taille d’un modèle sur disque ne donne pas sa mémoire totale d’exécution.\n")
        append("IA sur CPU, GPU ou NPU : aucune exécution déduite de cet inventaire. Seul un essai de modèle peut l’établir.\n")
        notes.forEach { append("\n$it\n") }
        append("\nDécodeurs déclarés par Android : ${codecs.size}\n")
        append("Cet inventaire ne valide aucun fichier et ne prouve pas un décodage effectif.\n")
        codecs.forEach {
            append("\n${it.name}\n${it.mimeTypes.joinToString()}\n")
            append("Classification déclarée : matériel=${answer(it.hardwareAccelerated)}, logiciel=${answer(it.softwareOnly)}\n")
        }
    }

    companion object {
        private fun size(bytes: Long?): String = bytes?.takeIf { it >= 0 }?.let {
            String.format(Locale.FRANCE, "%.2f Gio (%d octets)", it / 1_073_741_824.0, it)
        } ?: "non exposée"
        private fun answer(value: Boolean?): String = when (value) {
            true -> "oui"
            false -> "non"
            null -> "non exposé"
        }
        private fun thermal(value: Int?): String = when (value) {
            0 -> "aucune limitation déclarée"
            1 -> "léger"
            2 -> "modéré"
            3 -> "sévère"
            4 -> "critique"
            5 -> "urgence"
            6 -> "arrêt demandé par le système"
            null -> "non exposé"
            else -> "code $value"
        }
    }
}

/** Read-only and independent of the playback/narration session. Run on a worker:
 * codec enumeration can be slow on some vendor implementations. */
internal object PreparationDeviceProbe {
    fun read(context: Context): PreparationDeviceSnapshot {
        val notes = mutableListOf<String>()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = runCatching {
            ActivityManager.MemoryInfo().also { requireNotNull(manager).getMemoryInfo(it) }
        }.getOrElse { notes += "Mémoire système indisponible (${it.javaClass.simpleName})."; null }
        val pss = runCatching {
            Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() * 1024L
        }.getOrNull()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull()
        val storage = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        }.getOrNull() else null
        val codecs = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.map { codec ->
                PreparationCodecDeclaration(codec.name, codec.supportedTypes.toList().sorted(),
                    if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else null,
                    if (Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else null)
            }.sortedBy { it.name }
        }.getOrElse { notes += "Inventaire des décodeurs indisponible (${it.javaClass.simpleName})."; emptyList() }
        return PreparationDeviceSnapshot(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toList(), Runtime.getRuntime().availableProcessors(),
            memory?.totalMem, memory?.availMem, memory?.lowMemory, pss, native, thermal,
            SystemClock.elapsedRealtime(), codecs, notes, storage)
    }
}
