package fr.soustitresvoix.player

import fr.soustitresvoix.core.TranslationCue
import fr.soustitresvoix.core.TranslationPlan
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationCheckpointJournalRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun plan(count: Int = 12, provider: String = "journal-fixture") = TranslationPlan.create((0 until count).map {
        TranslationCue("occurrence-$it", it * 1000L, it * 1000L + 2000, "Again 😀")
    }, "en", "fr", provider)
    private fun path(directory: File, plan: TranslationPlan) = File(directory, "${plan.fingerprint}.progress")

    @Test fun appendsKeepPriorBytesAndRestoreEveryDistinctOccurrenceWithLinearStorage() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        var previous = ByteArray(0); var halfway = 0L
        repeat(12) { index ->
            assertTrue(store.append(source, index, listOf("Encore 😀"), false))
            val bytes = path(directory, source).readBytes()
            assertArrayEquals(previous, bytes.copyOfRange(0, previous.size))
            previous = bytes
            val restored = TranslationCheckpointStore(directory).read(source)!!
            assertEquals(List(index + 1) { "Encore 😀" }, restored.texts)
            assertFalse(restored.uncertainRequest); assertFalse(restored.recoveredTail)
            if (index == 5) halfway = bytes.size.toLong()
        }
        assertTrue("Appending six more equal records must not rewrite six growing prefixes", previous.size < halfway * 2)
    }

    @Test fun everyTruncatedFinalRecordKeepsOnlyThePrecedingVerifiedResultsAndUnknownCharge() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        assertTrue(store.append(source, 1, listOf("Deuxième 😀"), false))
        val before = path(directory, source).length().toInt()
        assertTrue(store.append(source, 2, listOf("Troisième 東京"), false))
        val complete = path(directory, source).readBytes()
        for (cut in before + 1 until complete.size) {
            path(directory, source).writeBytes(complete.copyOf(cut))
            val recovered = TranslationCheckpointStore(directory).read(source)!!
            assertEquals(listOf("Premier", "Deuxième 😀"), recovered.texts)
            assertTrue(recovered.recoveredTail); assertTrue(recovered.uncertainRequest)
        }
        path(directory, source).writeBytes(complete)
        assertEquals(3, TranslationCheckpointStore(directory).read(source)!!.texts.size)
    }

    @Test fun corruptedMiddleRecordCannotBeSkippedToAcceptItsLaterRecords() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        val firstEnd = path(directory, source).length().toInt()
        assertTrue(store.append(source, 1, listOf("Deuxième"), false))
        val secondEnd = path(directory, source).length().toInt()
        assertTrue(store.append(source, 2, listOf("Troisième"), false))
        val bytes = path(directory, source).readBytes()
        val changed = (firstEnd + secondEnd) / 2
        bytes[changed] = (bytes[changed].toInt() xor 1).toByte()
        path(directory, source).writeBytes(bytes)
        val recovered = TranslationCheckpointStore(directory).read(source)!!
        assertEquals(listOf("Premier"), recovered.texts)
        assertTrue(recovered.recoveredTail); assertTrue(recovered.uncertainRequest)
    }

    @Test fun damagedUncertainRequestMarkerNeverClaimsItsChargeIsKnown() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        assertTrue(store.append(source, 1, emptyList(), true))
        val target = path(directory, source)
        RandomAccessFile(target, "rw").use { it.setLength(it.length() - 1) }
        val recovered = TranslationCheckpointStore(directory).read(source)!!
        assertEquals(listOf("Premier"), recovered.texts)
        assertTrue(recovered.recoveredTail); assertTrue(recovered.uncertainRequest)
    }

    @Test fun recoveredSuffixIsRemovedBeforeNewValidatedResultsAreAppended() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        assertTrue(store.append(source, 1, listOf("Deuxième"), false))
        val prefix = path(directory, source).readBytes()
        path(directory, source).appendBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
        val recovered = TranslationCheckpointStore(directory)
        assertTrue(recovered.read(source)!!.recoveredTail)
        assertTrue(recovered.append(source, 2, listOf("Nouveau résultat 😀"), false))
        assertArrayEquals(prefix, path(directory, source).readBytes().copyOfRange(0, prefix.size))
        val restored = TranslationCheckpointStore(directory).read(source)!!
        assertEquals(listOf("Premier", "Deuxième", "Nouveau résultat 😀"), restored.texts)
        assertFalse(restored.recoveredTail); assertFalse(restored.uncertainRequest)
    }

    @Test fun legacyCheckpointMigratesAtomicallyWithoutLosingItsValidatedPrefix() {
        val directory = temporary.newFolder(); val source = plan(); val texts = listOf("Premier", "Deuxième 😀")
        val prefix = TranslationPlan.create(source.cues.take(2), "en", "fr", source.providerId).complete(texts)
        DataOutputStream(path(directory, source).outputStream()).use { output ->
            output.writeInt(0x53545650); output.writeInt(1); output.writeUTF(source.fingerprint)
            output.writeBoolean(true); output.writeInt(texts.size)
            texts.forEach { text -> val bytes = text.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
            output.writeUTF(prefix.fingerprint)
        }
        val store = TranslationCheckpointStore(directory)
        assertEquals(texts, store.read(source)!!.texts)
        assertTrue(store.append(source, 2, listOf("Troisième"), false))
        DataInputStream(path(directory, source).inputStream()).use { assertEquals(0x53545650, it.readInt()); assertEquals(2, it.readInt()) }
        assertEquals(texts + "Troisième", TranslationCheckpointStore(directory).read(source)!!.texts)
    }

    @Test fun failedAppendSyncCannotTurnIntoASuccessfulNoOpSnapshotWithoutAnotherSuccessfulSync() {
        val directory = temporary.newFolder(); val source = plan(); var attempts = 0
        val store = TranslationCheckpointStore(directory) { descriptor ->
            attempts++
            if (attempts == 2 || attempts == 3) throw IOException("Synthetic sync failure")
            descriptor.sync()
        }
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        assertFalse(store.append(source, 1, listOf("Deuxième"), false))
        // The complete second record may be readable despite its failed sync.
        // The runner's fallback must not mistake those bytes for a durable ack.
        assertFalse(store.write(source, listOf("Premier", "Deuxième"), false))
        assertEquals(3, attempts)
        assertTrue(store.write(source, listOf("Premier", "Deuxième"), false))
        assertEquals(4, attempts)
        assertEquals(listOf("Premier", "Deuxième"), TranslationCheckpointStore(directory).read(source)!!.texts)
    }

    @Test fun wrongPlanInvalidUnicodeAndOutOfOrderAppendsCannotReplaceVerifiedResults() {
        val directory = temporary.newFolder(); val source = plan(); val store = TranslationCheckpointStore(directory)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        assertFalse(store.append(source, 2, listOf("Trou"), false))
        assertFalse(store.append(source, 1, listOf("\uD800"), false))
        assertFalse(store.write(source, listOf("\u0000"), false))
        assertEquals(listOf("Premier"), TranslationCheckpointStore(directory).read(source)!!.texts)
        val other = plan(provider = "different-engine")
        path(directory, source).copyTo(path(directory, other))
        assertNull(TranslationCheckpointStore(directory).read(other))
        RandomAccessFile(path(directory, other), "rw").use { it.setLength(TranslationRunner.MAX_CACHE_BYTES + 1) }
        assertNull(TranslationCheckpointStore(directory).read(other))
    }

    @Test fun accumulatedRequestMarkersCompactAtomicallyWithoutLosingResultsAtTheJournalLimit() {
        val directory = temporary.newFolder(); val source = plan()
        val store = TranslationCheckpointStore(directory, maximumBytes = 256)
        assertTrue(store.append(source, 0, listOf("Premier"), false))
        var previousBytes = path(directory, source).length(); var compacted = false
        repeat(20) { index ->
            val uncertain = index % 2 == 0
            assertTrue(store.append(source, 1, emptyList(), uncertain))
            val bytes = path(directory, source).length()
            assertTrue(bytes <= 256)
            compacted = compacted || bytes < previousBytes
            previousBytes = bytes
            val restored = TranslationCheckpointStore(directory).read(source)!!
            assertEquals(listOf("Premier"), restored.texts)
            assertEquals(uncertain, restored.uncertainRequest)
            assertFalse(restored.recoveredTail)
        }
        assertTrue("A full marker journal must compact rather than lose the next durable save", compacted)
    }
}
