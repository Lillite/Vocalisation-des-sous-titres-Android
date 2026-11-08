package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Documentation opens only after an explicit action; no download or model import. */
internal object DocumentationLinks {
    const val MODELS_GUIDE = "https://github.com/Lillite/Vocalisation-des-sous-titres-Android/blob/main/docs/modeles-traduction.md"

    fun openModelsGuide(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODELS_GUIDE))) }
        catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(activity).setTitle("Guide des modèles de traduction")
                .setMessage("Aucun navigateur n’est disponible. Le guide peut être consulté à cette adresse :\n$MODELS_GUIDE")
                .setPositiveButton("Fermer", null).show()
        }
    }
}
