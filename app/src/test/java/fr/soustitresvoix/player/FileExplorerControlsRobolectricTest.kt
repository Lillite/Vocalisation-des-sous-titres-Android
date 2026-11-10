package fr.soustitresvoix.player

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
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
import org.robolectric.shadows.ShadowContentResolver
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

    @Test fun grantedTreeListsHiddenFoldersAndOpensUnknownMediaAndAssWithoutAllFilesAccess() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = owner.get().apply { setTheme(R.style.AppTheme) }
        val authority = "synthetic.explorer.documents"
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root:synthetic")
        val provider = TreeProvider().apply { attachInfo(activity, ProviderInfo().also { it.authority = authority }) }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val opened = mutableListOf<SavedMedia>()
        val subtitles = mutableListOf<Pair<Uri, String>>()
        val controls = FileExplorerControls(activity, { opened += it }, { uri, name -> subtitles += uri to name }, {}, {})
        try {
            assertFalse(LocalFileAccess.granted(activity))
            val view = controls.createView()
            click(view, "Ajouter un dossier avec Android")
            val launched = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, launched.intent.action)
            assertTrue(activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK,
                Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
            awaitButton(view, "Dossier · .panneaux")
            click(view, "Dossier · .panneaux")
            awaitButton(view, "Fichier · .panneaux.ASS")
            click(view, "Fichier · .panneaux.ASS")
            assertEquals(listOf(DocumentsContract.buildDocumentUriUsingTree(tree, "ass:opaque/2") to ".panneaux.ASS"), subtitles)
            click(view, "Dossier parent")
            awaitButton(view, "Fichier · Film sans extension")
            click(view, "Fichier · Film sans extension")
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(click(dialog.window!!.decorView, "Lire comme média"))
            // AlertDialog delivers its positive action through a Handler.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(SavedMedia(DocumentsContract.buildDocumentUriUsingTree(tree, "film:opaque/1").toString(),
                "Film sans extension")), opened)
        } finally { controls.close(); owner.pause().stop().destroy() }
    }

    private fun awaitButton(root: View, label: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (descendants(root).filterIsInstance<Button>().any { it.text.toString() == label }) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Bouton absent : $label")
    }

    private class TreeProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            val rows = when {
                uri.lastPathSegment != "children" -> listOf(Triple("root:synthetic", "Dossier synthétique", Document.MIME_TYPE_DIR))
                DocumentsContract.getDocumentId(uri) == "root:synthetic" -> listOf(
                    Triple("folder:opaque/0", ".panneaux", Document.MIME_TYPE_DIR),
                    Triple("film:opaque/1", "Film sans extension", "application/octet-stream"))
                else -> listOf(Triple("ass:opaque/2", ".panneaux.ASS", "text/x-ssa"))
            }
            return MatrixCursor(columns).apply {
                rows.forEach { (id, name, mime) -> addRow(columns.map { column -> when (column) {
                    Document.COLUMN_DOCUMENT_ID -> id
                    Document.COLUMN_DISPLAY_NAME -> name
                    Document.COLUMN_MIME_TYPE -> mime
                    Document.COLUMN_FLAGS -> 0
                    else -> null
                } }) }
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

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
