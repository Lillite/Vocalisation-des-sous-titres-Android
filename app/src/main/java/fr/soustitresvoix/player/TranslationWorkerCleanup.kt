package fr.soustitresvoix.player

import android.os.Looper

/** A worker barrier; a UI cancellation must continue to use non-blocking close(). */
internal fun closeTranslationEngineOnWorker(engine: TranslationEngine): String? {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Wait for translation cleanup on a worker" }
    return try {
        if (engine is LocalGgufTranslationEngine) {
            // Cancellation invalidates the result, not the need to release the
            // previous model before another task can acquire the service.
            engine.closeAndAwait(cancelled = { false }, timeoutMs = 10_000)
        } else engine.close()
        null
    } catch (_: Exception) {
        // Keep any completed translation/export. Do not claim that the native
        // owner or its allocations have been released after a missing ACK.
        if (engine is LocalGgufTranslationEngine)
            "La libération du modèle local n’a pas été confirmée. Un nouveau calcul peut rester indisponible ; les traductions déjà validées sont conservées."
        else "La fermeture du traducteur n’a pas été confirmée. Les traductions déjà validées sont conservées."
    }
}
