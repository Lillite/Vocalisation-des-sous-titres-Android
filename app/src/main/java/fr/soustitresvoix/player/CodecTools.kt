package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors

@UnstableApi
object CodecTools {
    fun show(activity: Activity, session: PlaybackSession) {
        val wait=AlertDialog.Builder(activity).setMessage("Inventaire des décodeurs déclarés par Android…").setNegativeButton("Fermer",null).show()
        val worker=Executors.newSingleThreadExecutor()
        worker.execute {
            val inventory=runCatching {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.joinToString("\n\n") { codec ->
                    val acceleration=if(Build.VERSION.SDK_INT>=29) "matériel=${codec.isHardwareAccelerated}, logiciel=${codec.isSoftwareOnly}" else "type non exposé par cette version Android"
                    "${codec.name}\n$acceleration\n${codec.supportedTypes.joinToString() }"
                }
            }.getOrElse { "Inventaire indisponible : ${it.javaClass.simpleName}" }
            Handler(Looper.getMainLooper()).post {
                val stillShown=wait.isShowing;wait.dismiss()
                if(!stillShown || activity.isFinishing || activity.isDestroyed) return@post
                val observed=session.diagnosticSnapshot().capabilities
                    .filter { it.id in setOf("video.decoder", "video.decoder_kind", "video.format") }
                    .joinToString("\n\n") { PlaybackDiagnosticText.render(it) }
                val text="$observed\n\nCes capacités sont déclarées, pas un test de chaque codec. Aucune bibliothèque FFmpeg/LibVLC supplémentaire n’est intégrée. L’essai logiciel ne crée pas un décodeur absent et peut augmenter la consommation.\n\n$inventory"
                AlertDialog.Builder(activity).setTitle("Compatibilité et secours logiciel").setMessage(text)
                    .setPositiveButton("Essayer priorité logicielle") { _,_-> session.retryDecoder(true) }
                    .setNeutralButton("Revenir à la sélection normale") { _,_->session.retryDecoder(false) }
                    .setNegativeButton("Fermer",null).show()
            }
            worker.shutdown()
        }
    }
}
