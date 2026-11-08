package fr.soustitresvoix.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real Android Keystore on the tested APK; no synthetic provider. */
@RunWith(AndroidJUnit4::class)
class RemoteKeystoreAndroidTest {
    @Test fun connectionAndSignedUrlSurviveNewStoreWithoutPlaintextPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = RemoteConnection(label = "Test synthétique Keystore", protocol = RemoteProtocol.SFTP,
            host = "synthetic.invalid", username = "synthetic-android-user", password = "synthetic-android-password")
        val store = RemoteConnectionStore(context)
        var direct: android.net.Uri? = null
        try {
            store.save(connection)
            assertEquals(connection, RemoteConnectionStore(context).get(connection.id))
            val rawUrl = "https://synthetic.invalid/film.mkv?token=synthetic-android-token"
            direct = store.saveDirectUrl(rawUrl)
            assertEquals(rawUrl, RemoteConnectionStore(context).directUrl(direct))
            val disk = context.getSharedPreferences("remote-connections-private-v1", Context.MODE_PRIVATE).all.toString()
            assertFalse(disk.contains(connection.password))
            assertFalse(disk.contains(connection.username))
            assertFalse(disk.contains("synthetic-android-token"))
            assertFalse(direct.toString().contains("synthetic"))
        } finally {
            store.delete(connection.id)
            direct?.let(store::deleteDirectUrl)
        }
    }
}
