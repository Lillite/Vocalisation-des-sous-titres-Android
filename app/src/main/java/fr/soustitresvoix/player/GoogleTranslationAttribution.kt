package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Official attribution stays in the visual interface, never in text sent to the narrator. */
internal object GoogleTranslationAttribution {
    const val VIDEO_TAG = "google-translation-attribution"

    fun badge(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.google_translate_badge)
        contentDescription = "Traduction automatique fournie par Google Traduction"
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        setPadding(4, 4, 4, 4)
    }

    fun results(context: Context, view: View, google: Boolean): View = if (!google) view else
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(badge(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                (24 * context.resources.displayMetrics.density).toInt()))
            addView(view)
        }

    /** Reserve a separate strip above controls/insets; the badge cannot cover a caption. */
    fun positionVideoBadge(video: ViewGroup, captions: View, left: Int = 0, right: Int = 0,
        bottomInset: Int = 0, bottomControlsHeight: Int = 0) {
        val badge = video.findViewWithTag<View>(VIDEO_TAG) ?: return
        val density = video.resources.displayMetrics.density
        val floor = maxOf(bottomInset, bottomControlsHeight)
        captions.setPadding(left, 0, right,
            if (badge.visibility == View.VISIBLE) floor + (32 * density).toInt() else bottomInset)
        val params = badge.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = floor + (4 * density).toInt()
        val end = right + (8 * density).toInt()
        if (params.bottomMargin != margin || params.rightMargin != end) {
            params.bottomMargin = margin; params.rightMargin = end
            badge.layoutParams = params
        }
    }

    fun showInformation(activity: Activity) {
        val message = TextView(activity).apply {
            textSize = 18f
            setPadding(20, 16, 20, 16)
            text = "Les résultats Google sont des traductions automatiques. Google Traduction fournit la traduction en ligne ; ML Kit fournit la traduction sur le téléphone. L’application n’est ni éditée ni approuvée par Google.\n\n" +
                "Avec ML Kit, les textes sont traités localement. Le SDK peut transmettre des données techniques (appareil, application, performances, tailles et langues) à Google. Les modèles sont téléchargés selon le choix Wi-Fi ou données mobiles.\n\n" +
                "Google décline toute garantie relative aux traductions, notamment leur exactitude, leur fiabilité et leur adéquation à un usage particulier. La traduction automatique peut contenir des erreurs ; la piste originale reste disponible.\n\n" +
                "Informations : https://developers.google.com/ml-kit/language/translation ; https://developers.google.com/ml-kit/android-data-disclosure ; https://cloud.google.com/translate."
        }
        AlertDialog.Builder(activity).setTitle("Traductions automatiques Google")
            .setView(android.widget.ScrollView(activity).apply { addView(results(activity, message, true)) })
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Google Traduction") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/"))) }
            }.show()
    }
}
