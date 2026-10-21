package fr.soustitresvoix.player

import androidx.media3.common.util.UnstableApi
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Pure diagnostic mapping checks. The separate real-indexer/UI regression
 * exercises the filesystem error and clipboard; this class does not fake scan().
 */
@UnstableApi
class FullSubtitleIndexerFailureMessageTest {
    private val privatePath = "/storage/emulated/0/Films/synthetic-private-episode.ass"
    private val privateUri = "content://synthetic.provider/private/synthetic-private-episode.ass"
    private val ioMessage = "Indexation impossible : fichier indisponible ou lecture du fichier échouée. Rouvre-le puis réessaie ; la lecture directe reste disponible."
    private val otherMessage = "Indexation impossible : ce fichier n’a pas pu être analysé. La lecture directe reste disponible."
    private val deniedMessage = "Indexation impossible : accès au fichier refusé. Rouvre la vidéo et autorise son accès."

    private fun assertNoProviderDetails(message: String) {
        assertFalse(message.contains(privatePath))
        assertFalse(message.contains(privateUri))
        assertFalse(message.contains("synthetic-private-episode"))
        assertFalse(message.contains("synthetic.provider"))
    }

    @Test fun allTenApplicationOwnedReasonsRemainVerbatim() {
        // Literal expectations preserve each existing explanation, including
        // cancellation and incomplete-index limits. No production constants are
        // imported, so changing a reason accidentally cannot update its oracle.
        val failures = listOf(
            IOException("Indexation annulée"),
            IllegalArgumentException("Fichier de sous-titres hors limite (8 Mio)."),
            IOException("Indexation textuelle au-delà des limites de sécurité"),
            IllegalArgumentException("Indexation locale uniquement"),
            IOException("Indexation limitée à 32 Gio lus. Lecture directe conservée."),
            IOException("Indexation complète indisponible pour ce conteneur. La lecture directe reste disponible."),
            IOException("Indexation incomplète : limites de sécurité ou sous-titre textuel illisible. Lecture directe conservée."),
            IOException("Ce conteneur ne permet plus de faire progresser l’indexation."),
            IOException("Trop de déplacements dans ce conteneur"),
            IOException("Index au-delà de 100 000 répliques / 12 millions de caractères. Prélecture directe conservée.")
        )
        assertEquals(10, failures.map { it.message }.distinct().size)
        for (failure in failures) {
            val message = FullSubtitleIndexer.safeFailureMessage(failure)
            assertEquals(failure.message, message)
            assertNoProviderDetails(message)
        }
    }

    @Test fun prefixedSuffixedAndNestedProviderMessagesNeverEnterTheDiagnostic() {
        val failures = listOf(
            IOException(privatePath) to ioMessage,
            IOException("Indexation annulée : $privatePath") to ioMessage,
            IOException("$privateUri — Indexation annulée") to ioMessage,
            IOException("Indexation annulée\n$privatePath") to ioMessage,
            IOException(IOException(privatePath)) to ioMessage,
            IOException() to ioMessage,
            IllegalArgumentException("Sous-titre illisible : $privateUri", IOException(privatePath)) to otherMessage,
            RuntimeException(IOException(privateUri)) to otherMessage,
            RuntimeException() to otherMessage,
            // A safe top-level reason may retain its internal cause, but that
            // cause must never be traversed or appended to the displayed text.
            IOException("Indexation annulée", IOException(privatePath)) to "Indexation annulée"
        )
        for ((failure, expected) in failures) {
            val message = FullSubtitleIndexer.safeFailureMessage(failure)
            assertEquals(expected, message)
            assertNoProviderDetails(message)
        }
    }

    @Test fun deniedAccessExplainsTheRecoveryWithoutRevealingTheProvider() {
        val failures = listOf(
            SecurityException("Permission denied for $privateUri", IOException(privatePath)),
            SecurityException()
        )
        for (failure in failures) {
            val message = FullSubtitleIndexer.safeFailureMessage(failure)
            assertEquals(deniedMessage, message)
            assertTrue(message.contains("accès au fichier refusé"))
            assertTrue(message.contains("Rouvre la vidéo et autorise son accès"))
            assertNoProviderDetails(message)
        }
    }
}
