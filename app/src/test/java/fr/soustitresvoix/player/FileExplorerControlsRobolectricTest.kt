package fr.soustitresvoix.player

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class FileExplorerControlsRobolectricTest {
    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 30) {
            val shared = File(context.cacheDir, "synthetic-shared-volume").apply { mkdirs() }
            val storage = shadowOf(context.getSystemService(StorageManager::class.java))
            storage.resetStorageVolumeList()
            storage.addStorageVolume(StorageVolumeBuilder("synthetic-primary", shared, "Synthetic shared storage",
                Process.myUserHandle(), Environment.MEDIA_MOUNTED).setIsPrimary(true).setIsEmulated(true).build())
            shadowOf(context.getSystemService(AppOpsManager::class.java))
                .setMode("android:manage_external_storage", context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ERRORED)
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun click(root: View, label: String) = descendants(root).filterIsInstance<Button>().single { it.text.toString() == label }.performClick()

    @Test fun deniedSharedStorageAlwaysKeepsSystemPickerAndOffersAndroidSettings() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.get().setTheme(R.style.AppTheme)
        var pickerCalls = 0
        val controls = FileExplorerControls(owner.get(), {}, { _, _ -> }, { pickerCalls++ }, { fail("No legacy request on Android 11") })
        try {
            val view = controls.createView()
            click(view, "Choisir avec Android")
            assertEquals(1, pickerCalls)
            click(view, "Autoriser l’accès à tous les fichiers")
            val launched = shadowOf(owner.get()).nextStartedActivity
            assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, launched.action)
            assertEquals("package:${owner.get().packageName}", launched.data.toString())
            assertFalse(LocalFileAccess.granted(owner.get()))
            shadowOf(owner.get().getSystemService(AppOpsManager::class.java)).setMode(
                "android:manage_external_storage", owner.get().applicationInfo.uid, owner.get().packageName, AppOpsManager.MODE_ALLOWED)
            val application = ApplicationProvider.getApplicationContext<Context>()
            assertEquals("Framework and Activity must check the same application UID", application.applicationInfo.uid, owner.get().applicationInfo.uid)
            assertEquals("Framework and Activity must check the same package", application.packageName, owner.get().packageName)
            assertEquals("The mounted volume fixture must still exist after launching Settings", 1,
                application.getSystemService(StorageManager::class.java).storageVolumes.size)
            // The actual Android 30 framework implementation checks numeric op 92.
            assertEquals("The exact framework AppOp must have MODE_ALLOWED", AppOpsManager.MODE_ALLOWED,
                shadowOf(application.getSystemService(AppOpsManager::class.java)).checkOpNoThrow(
                    92, application.applicationInfo.uid, application.packageName))
            assertTrue("The framework's direct per-volume query must observe that AppOp",
                Environment.isExternalStorageManager(File(application.cacheDir, "synthetic-shared-volume")))
            assertTrue(LocalFileAccess.granted(owner.get()))
            controls.onResume()
            assertTrue(descendants(view).filterIsInstance<Button>().none { it.text.toString() == "Autoriser l’accès à tous les fichiers" })
        } finally { controls.close(); owner.pause().stop().destroy() }
    }

    @Test fun missingSharedStorageVolumeKeepsApplicationAndSystemPickerAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(StorageManager::class.java)).resetStorageVolumeList()
        assertTrue(context.getSystemService(StorageManager::class.java).storageVolumes.isEmpty())
        assertFalse(LocalFileAccess.granted(context))
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.get().setTheme(R.style.AppTheme)
        var pickerCalls = 0
        val controls = FileExplorerControls(owner.get(), {}, { _, _ -> }, { pickerCalls++ }, {})
        try {
            val view = controls.createView()
            click(view, "Choisir avec Android")
            assertEquals(1, pickerCalls)
        } finally { controls.close(); owner.pause().stop().destroy() }
    }

    @Config(sdk = [29]) @Test fun olderAndroidRequestsOnlyLegacyReadPermission() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.get().setTheme(R.style.AppTheme)
        shadowOf(owner.get().application).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        var requests = 0
        val controls = FileExplorerControls(owner.get(), {}, { _, _ -> }, {}, { requests++ })
        try {
            val view = controls.createView()
            click(view, "Autoriser la lecture des fichiers")
            assertEquals(1, requests)
            assertFalse(LocalFileAccess.granted(owner.get()))
            shadowOf(owner.get().application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
            assertTrue(LocalFileAccess.granted(owner.get()))
        } finally { controls.close(); owner.pause().stop().destroy() }
    }

    @Test fun connectionPasswordIsMaskedAndExcludedFromSavedViewState() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.get().setTheme(R.style.AppTheme)
        val controls = FileExplorerControls(owner.get(), {}, { _, _ -> }, {}, {})
        try {
            val view = controls.createView()
            click(view, "Serveurs")
            click(view, "Ajouter un serveur")
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            val password = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single {
                it.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            password.setText("synthetic-only-password")
            assertTrue(password.transformationMethod is PasswordTransformationMethod)
            assertFalse(password.transformationMethod.getTransformation(password.text, password).toString().contains("synthetic-only-password"))
            assertFalse(password.isSaveEnabled)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, password.importantForAutofill)
            assertFalse(password.contentDescription?.contains("synthetic-only-password") ?: false)
        } finally { controls.close(); owner.pause().stop().destroy() }
    }
}
