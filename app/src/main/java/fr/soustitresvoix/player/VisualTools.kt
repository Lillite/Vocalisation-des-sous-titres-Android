package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import fr.soustitresvoix.core.BlackBars
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class VisualTools(private val session: PlaybackSession) {
    var margins = BlackBars.Margins(); private set
    var contrast = 0f; private set
    var brightness = 0f; private set
    var bypass = false; private set
    val hdr: Boolean get() = session.player.videoFormat?.colorInfo?.colorTransfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) ||
        session.player.videoFormat?.sampleMimeType == "video/dolby-vision"
    fun reset() { margins = BlackBars.Margins(); contrast=0f; brightness=0f; bypass=false; apply() }
    fun crop(value: BlackBars.Margins) { require(value.safe()); margins=value; apply() }
    fun adjust(c: Float, b: Float) { contrast=c.coerceIn(-.3f,.3f); brightness=b.coerceIn(-.2f,.2f); apply() }
    fun toggleComparison() { bypass=!bypass; apply() }
    fun apply() {
        val effects = mutableListOf<Effect>()
        if (!hdr && !bypass) {
            if (margins.nonzero()) effects += Crop(-1f+2*margins.left, 1f-2*margins.right, -1f+2*margins.bottom, 1f-2*margins.top)
            if (contrast != 0f) effects += Contrast(contrast)
            if (brightness != 0f) effects += Brightness(brightness)
        }
        session.player.setVideoEffects(effects)
    }
    fun showAnalysis(activity: Activity, updated: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 27) {
            AlertDialog.Builder(activity).setMessage("L’analyse bornée des images nécessite Android 8.1 ou plus. Aucun recadrage appliqué.").setPositiveButton("Fermer", null).show(); return
        }
        val saved = session.media ?: return
        if (hdr) { message(activity, "Analyse et traitements désactivés pour HDR/Dolby Vision : rendu non validé."); return }
        session.pause()
        val cancelled = AtomicBoolean(false); val worker = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val progress = AlertDialog.Builder(activity).setTitle("Diagnostic local des bandes")
            .setMessage("Examen de sept images réparties dans la vidéo. Aucun fichier modifié. Le résultat est une hypothèse, jamais une suppression automatique.")
            .setNegativeButton("Annuler") { _, _ -> cancelled.set(true) }.create()
        progress.setOnCancelListener { cancelled.set(true) }; progress.show()
        worker.execute {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(activity, Uri.parse(saved.uri))
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    require(duration > 0) { "Durée indisponible pour cette analyse." }
                    for (fraction in listOf(.1,.23,.36,.5,.64,.77,.9)) {
                        if (cancelled.get()) break
                        val frame = retriever.getScaledFrameAtTime((duration*1000*fraction).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                            ?: throw IllegalStateException("Extraction des images indisponible pour ce codec.")
                        val editable = requireNotNull(frame.copy(Bitmap.Config.ARGB_8888, true))
                        frame.recycle(); bitmaps += editable
                    }
                } finally { retriever.release() }
                val candidate = BlackBars.consensus(bitmaps.map { b ->
                    val pixels=IntArray(b.width*b.height); b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
                    BlackBars.inspect(pixels,b.width,b.height)
                })
                handler.post {
                    progress.dismiss()
                    if (cancelled.get() || activity.isFinishing || activity.isDestroyed || session.media?.uri != saved.uri) {
                        bitmaps.forEach { it.recycle() }; return@post
                    }
                    if (candidate == null) {
                        bitmaps.forEach { it.recycle() }
                        message(activity, "Aucune marge constante suffisamment fiable. Les scènes sombres, textes dans les marges ou variations empêchent la proposition. Aucun recadrage appliqué.")
                    } else {
                        val column=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                        column.addView(TextView(activity).apply { text="Cadre proposé en vert. Vérifie les sept images, notamment les sous-titres incrustés. Cela ne prouve pas l’absence de texte dans toutes les autres images. Gauche ${percent(candidate.left)}, droite ${percent(candidate.right)}, haut ${percent(candidate.top)}, bas ${percent(candidate.bottom)}."; textSize=18f })
                        bitmaps.forEach { b ->
                            val canvas=Canvas(b); val paint=Paint().apply { color=Color.GREEN; style=Paint.Style.STROKE; strokeWidth=3f }
                            canvas.drawRect(candidate.left*b.width,candidate.top*b.height,(1-candidate.right)*b.width,(1-candidate.bottom)*b.height,paint)
                            column.addView(ImageView(activity).apply { setImageBitmap(b); adjustViewBounds=true; contentDescription="Image analysée et limite de recadrage proposée" })
                        }
                        val dialog=AlertDialog.Builder(activity).setTitle("Aperçu — validation obligatoire")
                            .setView(ScrollView(activity).apply { addView(column) })
                            .setPositiveButton("Appliquer ces marges") { _, _ ->
                                if (session.media?.uri == saved.uri) { crop(candidate); updated() }
                            }.setNegativeButton("Conserver l’image entière",null).create()
                        dialog.setOnDismissListener { bitmaps.forEach { it.recycle() } }; dialog.show()
                    }
                }
            } catch (e: Exception) {
                handler.post { progress.dismiss(); bitmaps.forEach { it.recycle() }
                    if (!cancelled.get() && !activity.isFinishing && !activity.isDestroyed) message(activity, e.message ?: "Analyse indisponible") }
            } finally { worker.shutdown() }
        }
    }
    private fun percent(v: Float) = String.format(java.util.Locale.FRANCE,"%.1f %%",v*100)
    private fun message(a: Activity,s: String) { AlertDialog.Builder(a).setMessage(s).setPositiveButton("Fermer",null).show() }
}
