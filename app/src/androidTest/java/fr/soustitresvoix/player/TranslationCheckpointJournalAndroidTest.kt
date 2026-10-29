package fr.soustitresvoix.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android AtomicFile and file sync; new store instances, not a process restart or power-loss test. */
@RunWith(AndroidJUnit4::class)
class TranslationCheckpointJournalAndroidTest {
    @Test fun privateJournalResumesUnicodeOccurrencesRepairsItsTailAndMigratesLegacyResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "translation-journal-platform-").toFile()
        try {
            val source = TranslationPlan.create(listOf(
                TranslationCue("sign", 0, 20_000_000, "EXIT"),
                TranslationCue("first", 1_234_000, 4_567_000, "Hello Élodie 😀"),
                TranslationCue("overlap-repeat", 1_234_000, 4_567_000, "Hello Élodie 😀"),
                TranslationCue("last", 8_000_000, 10_000_000, "Final line 東京")
            ), "en", "fr", "platform-journal-fixture")
            val expected = listOf("Sortie 🚪", "Bonjour Élodie 😀", "Bonjour Élodie 😀", "Dernière réplique 東京")
            val path = File(directory, "${source.fingerprint}.progress")
            val initial = TranslationCheckpointStore(directory)
            assertTrue(initial.append(source, 0, listOf(expected[0]), false))
            assertTrue(initial.append(source, 1, listOf(expected[1]), false))
            val reopened = TranslationCheckpointStore(directory)
            val before = requireNotNull(reopened.read(source))
            assertEquals(expected.take(2), before.texts)
            assertFalse(before.uncertainRequest); assertFalse(before.recoveredTail)
            assertTrue(reopened.append(source, 2, listOf(expected[2]), false))
            val validatedPrefix = path.readBytes()
            assertTrue(reopened.append(source, 3, emptyList(), true))
            // Damage only the newest in-flight-request marker. Earlier complete
            // occurrences must survive, while charge uncertainty remains visible.
            RandomAccessFile(path, "rw").use { file -> file.setLength(file.length() - 1); file.fd.sync() }
            val repaired = TranslationCheckpointStore(directory)
            val recovered = requireNotNull(repaired.read(source))
            assertEquals(expected.take(3), recovered.texts)
            assertTrue(recovered.recoveredTail); assertTrue(recovered.uncertainRequest)
            assertTrue(repaired.append(source, 3, listOf(expected[3]), false))
            assertArrayEquals(validatedPrefix, path.readBytes().copyOfRange(0, validatedPrefix.size))
            val complete = requireNotNull(TranslationCheckpointStore(directory).read(source))
            assertEquals(expected, complete.texts)
            assertFalse(complete.uncertainRequest); assertFalse(complete.recoveredTail)
            val track = source.complete(complete.texts)
            assertEquals(source.cues.map { Triple(it.id, it.startUs, it.endUs) },
                track.cues.map { Triple(it.id, it.startUs, it.endUs) })
            assertEquals(2, track.cues.count { it.text == "Bonjour Élodie 😀" })

            val legacy = File(directory, "legacy").apply { assertTrue(mkdirs()) }
            val legacyPath = File(legacy, path.name)
            val oldPrefix = TranslationPlan.create(source.cues.take(2), source.sourceLanguage,
                source.targetLanguage, source.providerId).complete(expected.take(2))
            legacyPath.outputStream().use { raw ->
                DataOutputStream(raw).use { output ->
                    output.writeInt(0x53545650); output.writeInt(1); output.writeUTF(source.fingerprint)
                    output.writeBoolean(true); output.writeInt(2)
                    expected.take(2).forEach { text ->
                        val bytes = text.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes)
                    }
                    output.writeUTF(oldPrefix.fingerprint); output.flush(); raw.fd.sync()
                }
            }
            val migrating = TranslationCheckpointStore(legacy)
            assertEquals(expected.take(2), requireNotNull(migrating.read(source)).texts)
            assertTrue(requireNotNull(migrating.read(source)).uncertainRequest)
            assertTrue(migrating.append(source, 2, listOf(expected[2]), false))
            DataInputStream(legacyPath.inputStream()).use { input ->
                assertEquals(0x53545650, input.readInt()); assertEquals(2, input.readInt())
            }
            assertTrue(migrating.append(source, 3, listOf(expected[3]), false))
            val migrated = requireNotNull(TranslationCheckpointStore(legacy).read(source))
            assertEquals(expected, migrated.texts)
            assertFalse(migrated.uncertainRequest); assertFalse(migrated.recoveredTail)
        } finally {
            assertTrue("Only the test-owned private directory must be removed", directory.deleteRecursively())
        }
    }
}
