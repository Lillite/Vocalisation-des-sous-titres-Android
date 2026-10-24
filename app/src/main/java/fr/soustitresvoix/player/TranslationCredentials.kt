package fr.soustitresvoix.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authentication keys only. Keystore failure never falls back to plain preferences. */
class TranslationCredentials(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun validateProvider(providerId: String) {
        require(providerId in TranslationProvider.entries.map { it.id } || providerId.matches(Regex("libre-[0-9a-f]{64}"))) { "Moteur de traduction inconnu." }
    }
    @Synchronized fun save(providerId: String, key: String) {
        validateProvider(providerId)
        require(key.length <= 4096 && key.all { it.code in 33..126 }) {
            "Format de clé de traduction invalide."
        }
        if (key.isEmpty()) { remove(providerId); return }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val encoded = "1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString(providerId, encoded).commit())
        } catch (_: Exception) { throw TranslationCredentialException() }
    }
    @Synchronized fun read(providerId: String): String? {
        validateProvider(providerId)
        try {
            val encoded = preferences.getString(providerId, null) ?: return null
            if (encoded.length > 24_000) throw TranslationCredentialException()
            val fields = encoded.split(':')
            if (fields.size != 3 || fields[0] != "1") throw TranslationCredentialException()
            val iv = Base64.decode(fields[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(fields[2], Base64.NO_WRAP)
            if (iv.size != 12 || encrypted.size < 16) throw TranslationCredentialException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { throw TranslationCredentialException() }
    }
    @Synchronized fun remove(providerId: String) {
        validateProvider(providerId)
        try { check(preferences.edit().remove(providerId).commit()) }
        catch (_: Exception) { throw TranslationCredentialException() }
    }
    private fun secretKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object {
        internal const val PREFERENCES = "translation-credentials-encrypted-v1"
        private val KEY_LOCK = Any()
        private const val ALIAS = "soustitresvoix.translation.credentials.v1"
    }
}

class TranslationCredentialException : IllegalStateException(
    "Clé de traduction indisponible ou non enregistrée. Saisis-la à nouveau ; aucun stockage en clair n’est utilisé.")
