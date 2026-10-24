package fr.soustitresvoix.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore, never a JVM substitute for device cryptography. Synthetic keys only. */
@RunWith(AndroidJUnit4::class)
class TranslationCredentialsAndroidTest {
    private val context: Context get() = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("fixture-$name", mode)
    }
    @Test fun encryptedKeySurvivesANewStoreInstanceAndRemoval() {
        val store = TranslationCredentials(context)
        val synthetic = "synthetic-translator-key-not-an-actual-credential"
        try {
            store.save("azure", synthetic)
            val raw = context.getSharedPreferences(TranslationCredentials.PREFERENCES, Context.MODE_PRIVATE).getString("azure", null)
            assertNotNull(raw)
            assertFalse(raw!!.contains(synthetic))
            assertTrue(raw.startsWith("1:"))
            assertEquals(synthetic, TranslationCredentials(context).read("azure"))
            store.remove("azure")
            assertNull(TranslationCredentials(context).read("azure"))
        } finally { store.remove("azure") }
    }
    @Test fun repeatedSavesUseFreshAuthenticatedEncryption() {
        val store = TranslationCredentials(context)
        val preferences = context.getSharedPreferences(TranslationCredentials.PREFERENCES, Context.MODE_PRIVATE)
        try {
            store.save("deepl-free", "synthetic-only")
            val first = preferences.getString("deepl-free", null)
            store.save("deepl-free", "synthetic-only")
            val second = preferences.getString("deepl-free", null)
            assertNotEquals(first, second)
            assertEquals("synthetic-only", store.read("deepl-free"))
        } finally { store.remove("deepl-free") }
    }
    @Test fun corruptOrSwappedCiphertextFailsClosedWithoutPlaintextFallback() {
        val store = TranslationCredentials(context)
        val preferences = context.getSharedPreferences(TranslationCredentials.PREFERENCES, Context.MODE_PRIVATE)
        val serverOne = TranslationEngines.identity(TranslationProviderConfig(TranslationProvider.LIBRE, endpoint = "https://one.example.test"))
        val serverTwo = TranslationEngines.identity(TranslationProviderConfig(TranslationProvider.LIBRE, endpoint = "https://two.example.test"))
        try {
            store.save("azure", "synthetic-only")
            val encrypted = preferences.getString("azure", null)
            assertTrue(preferences.edit().putString("libre", encrypted).commit())
            try { store.read("libre"); fail("Provider binding must reject swapped ciphertext") }
            catch (failure: TranslationCredentialException) { assertNull(failure.cause) }
            assertTrue(preferences.edit().putString("azure", "synthetic-plaintext-invalid-envelope").commit())
            try { store.read("azure"); fail("Corrupt ciphertext must fail closed") }
            catch (failure: TranslationCredentialException) { assertFalse(failure.message!!.contains("synthetic-plaintext")) }
            store.save(serverOne, "synthetic-server-one-key")
            assertEquals("synthetic-server-one-key", store.read(serverOne))
            assertNull(store.read(serverTwo))
            assertTrue(preferences.edit().putString(serverTwo, preferences.getString(serverOne, null)).commit())
            try { store.read(serverTwo); fail("Ciphertext must be bound to its exact Libre server") }
            catch (_: TranslationCredentialException) { }
        } finally { store.remove("azure"); store.remove("libre"); store.remove(serverOne); store.remove(serverTwo) }
    }
    @Test fun invalidCredentialDoesNotOverwriteThePreviousKeyAndEmptyKeyRemovesIt() {
        val store = TranslationCredentials(context)
        try {
            store.save("azure", "synthetic-previous-key")
            try { store.save("azure", "invalid\r\nheader"); fail("Invalid key") }
            catch (_: IllegalArgumentException) { }
            assertEquals("synthetic-previous-key", store.read("azure"))
            store.save("azure", "")
            assertNull(store.read("azure"))
        } finally { store.remove("azure") }
    }
}
