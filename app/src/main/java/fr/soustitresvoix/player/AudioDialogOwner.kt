package fr.soustitresvoix.player

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** Nested audio panels borrow their parent's lifetime. Closing a parent cascades
 * through detached views, including a numeric editor inside an EQ-band panel. */
internal object AudioDialogOwner {
    fun show(
        activity: Activity,
        owner: View,
        builder: AlertDialog.Builder,
        alive: () -> Boolean = { true },
        onDismiss: () -> Unit = {}
    ): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed || !owner.isAttachedToWindow || !alive()) return null
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        if (lifecycle?.currentState == Lifecycle.State.DESTROYED) return null
        val dialog = builder.create()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { dialog.dismiss() }
        }
        owner.addOnAttachStateChangeListener(attachment)
        lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            owner.removeOnAttachStateChangeListener(attachment)
            lifecycle?.removeObserver(observer)
            onDismiss()
        }
        dialog.show()
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply {
                minHeight = (56 * activity.resources.displayMetrics.density).toInt()
                textSize = 17f; isAllCaps = false
                setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
            }
        }
        return dialog
    }
}
