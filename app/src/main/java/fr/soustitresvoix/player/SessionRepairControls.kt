package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Guided repair delegates selection to the Activity's existing SAF launcher.
 * No broad storage permission and no filesystem search are required. The owner
 * verifies the replacement before calling MediaSessionStore.relocate(). */
object SessionRepairControls {
    fun show(
        activity: Activity,
        session: MediaSessionSnapshot,
        onChooseReplacement: () -> Unit,
        onRetry: () -> Unit = {},
        onForgetSession: () -> Unit = {},
        alive: () -> Boolean = { true }
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed || !alive()) return null
        fun display(builder: AlertDialog.Builder): AlertDialog? {
            if (activity.isFinishing || activity.isDestroyed || !alive()) return null
            val dialog = builder.create()
            val owner = activity as? LifecycleOwner
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss() }
            owner?.lifecycle?.addObserver(observer)
            dialog.setOnDismissListener { owner?.lifecycle?.removeObserver(observer) }
            dialog.show()
            return dialog
        }
        return display(AlertDialog.Builder(activity)
            .setTitle("Retrouver le fichier de cette séance")
            .setMessage("La vidéo « ${session.title} » n’est plus accessible. Elle peut avoir été déplacée, supprimée ou son autorisation Android peut avoir expiré.\n\nChoisir de nouveau la même vidéo dans le sélecteur Android permet de réparer l’accès. La position, les pistes et les réglages mémorisés sont conservés en attendant la vérification du fichier sélectionné. Les vidéos et sous-titres d’origine ne sont pas effacés.")
            .setPositiveButton("Choisir de nouveau la vidéo") { _, _ -> if (alive()) onChooseReplacement() }
            .setNegativeButton("Fermer — conserver la séance", null)
            .setNeutralButton("Autres solutions") { _, _ ->
                display(AlertDialog.Builder(activity).setTitle("Réparer l’accès au fichier")
                    .setItems(arrayOf("Réessayer l’accès actuel", "Oublier uniquement cette séance mémorisée")) { _, index ->
                        if (alive()) when (index) {
                            0 -> onRetry()
                            1 -> display(AlertDialog.Builder(activity).setTitle("Oublier cette séance ?")
                                .setMessage("La position, les choix de pistes et les réglages propres à cette séance seront oubliés. Les fichiers vidéo, sous-titres et préparations conservées restent intacts.")
                                .setPositiveButton("Oublier la séance") { _, _ -> if (alive()) onForgetSession() }
                                .setNegativeButton("Conserver", null))
                        }
                    }.setNegativeButton("Fermer", null))
            })
    }
}
