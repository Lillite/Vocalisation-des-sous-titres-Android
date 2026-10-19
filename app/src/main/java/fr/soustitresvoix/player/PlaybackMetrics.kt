package fr.soustitresvoix.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.Locale

/** Local measurements, not invented CPU/GPU/NPU utilization percentages. */
class PlaybackMetrics(private val context: Context) {
    private val started = SystemClock.elapsedRealtime()
    private val initialCpu = Process.getElapsedCpuTime()
    var droppedFrames = 0L
    var decoder = "Non encore initialisé"
    var openedAt = 0L
    var firstFrameMs: Long? = null
    var indexMs: Long? = null
    var indexCount = 0
    var indexState = "Non demandé"
    fun allowPreparation(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return !power.isPowerSaveMode && (Build.VERSION.SDK_INT < 29 || power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE)
    }
    fun report(speech: AndroidSpeech): String {
        val runtime = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeUnless { it == Int.MIN_VALUE }
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus?.toString() else "indisponible"
        fun mb(v: Long) = String.format(Locale.FRANCE, "%.1f Mio", v / 1048576.0)
        val rtf = if (speech.generatedAudioMs > 0) String.format(Locale.FRANCE, "%.3f", speech.synthesisMs.toDouble() / speech.generatedAudioMs) else "pas encore mesuré"
        return """
            Appareil : ${Build.MANUFACTURER} ${Build.MODEL}
            Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            ABI : ${Build.SUPPORTED_ABIS.joinToString()}
            Durée de cette session : ${(SystemClock.elapsedRealtime() - started) / 1000} s
            Temps CPU consommé par le lecteur : ${Process.getElapsedCpuTime() - initialCpu} ms
            Mémoire Java utilisée : ${mb(runtime.totalMemory() - runtime.freeMemory())}
            Mémoire native allouée : ${mb(Debug.getNativeHeapAllocatedSize())}
            Température de BATTERIE : ${temp?.let { "${it / 10.0} °C" } ?: "indisponible"}
            État thermique Android (0 = normal) : $thermal
            Préparation autorisée actuellement : ${allowPreparation()}
            Décodeur vidéo : $decoder
            Images perdues signalées par Media3 : $droppedFrames
            Ouverture → première image : ${firstFrameMs?.let { "$it ms" } ?: "pas encore mesuré"}
            Index : $indexState ; $indexCount répliques ; ${indexMs?.let { "$it ms" } ?: "durée non mesurée"}
            Cache vocal : ${speech.cacheEntries} segments, ${mb(speech.cacheBytes)}
            Segments servis depuis le cache : ${speech.cacheHits}
            Segments servis en synthèse directe : ${speech.cacheMisses}
            Segments synthétisés à l’avance : ${speech.synthCount}
            Temps préparation / durée audio préparée : $rtf
            Dernier facteur vocal adaptatif : ${speech.lastRateFactor}

            Ces mesures concernent cette session et ce lecteur. Le temps CPU et la mémoire du moteur TTS, exécuté dans un autre processus, ne sont PAS inclus. La température de batterie n’est PAS la température CPU/GPU. L’usage GPU/NPU du moteur TTS n’est pas exposé par l’API Android : non mesuré. Aucun résultat du OnePlus 15 n’est présumé avant essai sur cet appareil. Aucun nom de film ni dialogue dans ce rapport.
        """.trimIndent()
    }
}
