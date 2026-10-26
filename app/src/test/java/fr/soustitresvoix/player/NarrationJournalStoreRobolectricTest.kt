package fr.soustitresvoix.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.soustitresvoix.core.NarrationLedger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationJournalStoreRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val folder get() = File(context.filesDir, "narration-journals-v1")
    @Before fun clear() { folder.deleteRecursively() }
    private fun ledger(identity: String = "synthetic-media|track-fr") = NarrationLedger().also {
        it.setContext(identity)
        it.register(NarrationLedger.Occurrence("private subtitle source identity", 1000, 2000))
        it.queued("private subtitle source identity"); it.started("private subtitle source identity")
    }
    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid journal must be refused") } catch (_: IllegalArgumentException) { }
    }

    @Test fun metadataPersistsAcrossStoreRecreationAndPendingNarrationRestoresInterrupted() {
        val source = ledger()
        val store = NarrationJournalStore(context)
        store.save(source.currentContextHash, source.exportMetadata())
        val encoded = NarrationJournalStore(context).read(source.currentContextHash)!!
        val restored = NarrationLedger().also { it.setContext("synthetic-media|track-fr") }
        assertTrue(restored.restoreMetadata(encoded))
        assertEquals(1, restored.snapshot().interruptedCount)
        assertFalse(encoded.contains("private subtitle"))
        assertFalse(folder.listFiles()!!.single().readText().contains("synthetic-media"))

        source.interrupted("private subtitle source identity")
        val interrupted = source.exportMetadata()
        val concurrent = java.util.concurrent.Executors.newFixedThreadPool(3)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = listOf(
                concurrent.submit { start.await(); repeat(12) { NarrationJournalStore(context).save(source.currentContextHash, encoded) } },
                concurrent.submit { start.await(); repeat(12) { NarrationJournalStore(context).save(source.currentContextHash, interrupted) } },
                concurrent.submit { start.await(); repeat(24) {
                    val observed = NarrationJournalStore(context).read(source.currentContextHash)
                    assertTrue("Concurrent owners exposed a missing or partial journal", observed == encoded || observed == interrupted)
                } }
            )
            start.countDown()
            tasks.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { concurrent.shutdownNow() }

    }

    @Test fun corruptTruncatedOrWrongContextFilesAreNotAcceptedAsValidHistory() {
        val source = ledger()
        val store = NarrationJournalStore(context)
        store.save(source.currentContextHash, source.exportMetadata())
        val file = folder.listFiles()!!.single()
        file.writeText(file.readText().dropLast(20))
        assertNull(store.read(source.currentContextHash)); assertNotNull(store.lastWarning)
        store.save(source.currentContextHash, source.exportMetadata())
        file.writeText(file.readText().replace(source.currentContextHash, "0".repeat(64)))
        assertNull(store.read(source.currentContextHash)); assertNotNull(store.lastWarning)
    }

    @Test fun interruptedAtomicReplacementRecoversPreviousVerifiedMetadata() {
        val source = ledger()
        val encoded = source.exportMetadata()
        val store = NarrationJournalStore(context)
        store.save(source.currentContextHash, encoded)
        val record = folder.listFiles()!!.single()
        assertTrue(record.renameTo(File(record.path + ".bak")))
        record.writeText("interrupted")
        assertEquals(encoded, store.read(source.currentContextHash))
        assertNull(store.lastWarning)
    }

    @Test fun malformedRawTextWrongKeysAndOversizedInputCannotReplaceAValidJournal() {
        val source = ledger()
        val encoded = source.exportMetadata()
        val store = NarrationJournalStore(context)
        store.save(source.currentContextHash, encoded)
        reject { store.save(source.currentContextHash, "raw private subtitle text") }
        reject { store.save("0".repeat(64), encoded) }
        reject { store.save(source.currentContextHash, "x".repeat(NarrationJournalStore.MAX_BYTES + 1)) }
        reject { store.read("../../private") }
        assertEquals(encoded, store.read(source.currentContextHash))
    }

    @Test fun explicitReplayTargetsSurviveDurableStorageAndLegacyMetadataRemainsReadable() {
        val source = ledger().apply {
            completed("private subtitle source identity")
            started("last-complete"); completed("last-complete")
            queued("private subtitle source identity")
            started("spoken-interruption"); interrupted("spoken-interruption")
            queued("never-spoken"); interrupted("never-spoken")
        }
        val store = NarrationJournalStore(context)
        store.save(source.currentContextHash, source.exportMetadata())
        val restored = NarrationLedger().apply { setContext("synthetic-media|track-fr") }
        assertTrue(restored.restoreMetadata(NarrationJournalStore(context).read(source.currentContextHash)!!))
        assertEquals(NarrationLedger.hashIdentity("last-complete"), restored.snapshot().lastCompleted!!.identityHash)
        assertEquals(NarrationLedger.hashIdentity("spoken-interruption"), restored.snapshot().lastInterrupted!!.identityHash)
        val legacy = source.exportMetadata().lineSequence().mapIndexed { index, line ->
            when (index) {
                0 -> "NARRATION-LEDGER-1"
                2 -> line.split('\t').take(2).joinToString("\t")
                else -> line
            }
        }.joinToString("\n")
        store.save(source.currentContextHash, legacy)
        assertEquals(legacy, NarrationJournalStore(context).read(source.currentContextHash))
        assertTrue(restored.restoreMetadata(legacy))
        assertEquals(2, restored.snapshot().completedCount)
        assertNull(restored.snapshot().lastCompleted)
        assertNull(restored.snapshot().lastInterrupted)
        val invalid = source.exportMetadata().lines().toMutableList().apply {
            val header = this[2].split('\t').toMutableList()
            header[3] = "0".repeat(64)
            this[2] = header.joinToString("\t")
        }.joinToString("\n")
        reject { store.save(source.currentContextHash, invalid) }
        assertEquals(legacy, store.read(source.currentContextHash))
    }

    @Test fun deletionIsScopedToTheChosenMediaAndTrackContext() {
        val first = ledger()
        val second = ledger("synthetic-media|track-en")
        val store = NarrationJournalStore(context)
        store.save(first.currentContextHash, first.exportMetadata())
        store.save(second.currentContextHash, second.exportMetadata())
        store.remove(first.currentContextHash)
        assertNull(store.read(first.currentContextHash))
        assertEquals(second.exportMetadata(), store.read(second.currentContextHash))
    }
}
