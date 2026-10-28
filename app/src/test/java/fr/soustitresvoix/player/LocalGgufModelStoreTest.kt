package fr.soustitresvoix.player

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalGgufModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun bytes(seed: Int = 7): ByteArray = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf(71, 71, 85, 70)).putInt(3).putLong(0).putLong(0).array() + ByteArray(600) { (it + seed).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure.javaClass.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun completeImportFingerprintsEveryByteAndCurrentIsLightweight() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val original = bytes()
        val progress = mutableListOf<Long>()
        val model = store.importModel(ByteArrayInputStream(original), original.size.toLong(), onProgress = progress::add)
        assertEquals(sha(original), model.sha256)
        assertEquals(original.size.toLong(), model.bytes)
        assertEquals(model.identity, LocalGgufModelStore(folder).current()?.identity)
        assertArrayEquals(original, File(folder, "model.gguf").readBytes())
        assertEquals(original.size.toLong(), progress.last())
        assertFalse(model.toString().contains(folder.absolutePath))
    }

    @Test fun legacyDiagnosticCopyRequiresExplicitPreparationAndKeepsOriginalBytes() {
        val folder = temporary.newFolder()
        val original = bytes(9)
        val file = File(folder, "model.gguf").apply { writeBytes(original) }
        val store = LocalGgufModelStore(folder)
        assertTrue(store.exists())
        assertNull(store.current())
        val model = store.prepareCurrent()
        assertEquals(sha(original), model.sha256)
        assertArrayEquals(original, file.readBytes())
        assertEquals(model.identity, store.current()?.identity)
    }

    @Test fun cancelledOrInvalidReplacementRestoresCompletedCopyAndIdentity() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val before = bytes()
        val model = store.importModel(ByteArrayInputStream(before))
        fails<InterruptedIOException> { store.importModel(ByteArrayInputStream(bytes(8)), cancelled = { true }) }
        assertArrayEquals(before, File(folder, "model.gguf").readBytes())
        assertEquals(model.identity, store.current()?.identity)
        val invalid = bytes(10).also { it[0] = 0 }
        fails<IllegalArgumentException> { store.importModel(ByteArrayInputStream(invalid)) }
        assertArrayEquals(before, File(folder, "model.gguf").readBytes())
        assertEquals(model.identity, store.current()?.identity)
        assertFalse(File(folder, "model-import.part").exists())
    }

    @Test fun providerSizeMismatchCannotReplacePreviousPreparedModel() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val original = store.importModel(ByteArrayInputStream(bytes()))
        fails<IllegalArgumentException> { store.importModel(ByteArrayInputStream(bytes(8)), expectedBytes = 800) }
        assertEquals(original.sha256, store.current()?.sha256)
    }

    @Test fun replacementInvalidatesOldDescriptorEvenWhenTheLengthIsIdentical() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val original = store.importModel(ByteArrayInputStream(bytes()))
        val replacement = store.importModel(ByteArrayInputStream(bytes(8)))
        assertEquals(original.bytes, replacement.bytes)
        assertNotEquals(original.identity, replacement.identity)
        fails<IOException> { store.acquire(original) }
        store.acquire(replacement).close()
    }

    @Test fun importingIdenticalWeightsKeepsTheSameIdentityUsableForResume() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val initial = store.importModel(ByteArrayInputStream(bytes()))
        assertTrue(File(folder, "model.gguf").setLastModified(initial.modifiedMillis - 10_000))
        val original = store.prepareExisting()
        val replacement = store.importModel(ByteArrayInputStream(bytes()))
        assertEquals(original.identity, replacement.identity)
        assertNotEquals(original.modifiedMillis, replacement.modifiedMillis)
        store.acquire(original).close()
    }

    @Test fun readerLeaseBlocksOtherStoreMutationAndCanCloseFromAnotherThread() {
        val folder = temporary.newFolder()
        val first = LocalGgufModelStore(folder)
        val second = LocalGgufModelStore(folder)
        val model = first.importModel(ByteArrayInputStream(bytes()))
        val lease = first.acquire(model)
        fails<LocalGgufModelBusyException> { second.remove() }
        fails<LocalGgufModelBusyException> { second.prepareCurrent() }
        val close = Thread { lease.close(); lease.close() }.apply { start() }
        close.join(3000)
        assertFalse(close.isAlive)
        assertTrue(second.remove())
        assertFalse(first.exists())
    }

    @Test fun diagnosticInvalidationMakesEveryPreviousDescriptorUnusable() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val model = store.importModel(ByteArrayInputStream(bytes()))
        store.acquireMutationLease().use {
            store.invalidatePrepared()
            assertNull(store.current())
            fails<LocalGgufModelBusyException> { store.acquire(model) }
        }
        assertNull(store.current())
        fails<IOException> { store.acquire(model) }
        assertEquals(model.sha256, store.prepareExisting().sha256)
    }

    @Test fun abandonedPartialCopyIsCleanedBeforeNewImport() {
        val folder = temporary.newFolder()
        File(folder, "model-import.part").writeBytes(ByteArray(256))
        val store = LocalGgufModelStore(folder)
        val model = store.importModel(ByteArrayInputStream(bytes()))
        assertEquals(sha(bytes()), model.sha256)
        assertFalse(File(folder, "model-import.part").exists())
    }

    @Test fun corruptedMetadataAndChangedModelCannotBeMistakenForPreparedCopy() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        store.importModel(ByteArrayInputStream(bytes()))
        File(folder, "model.sha256").writeText("not a model identity")
        assertNull(store.current())
        val rebuilt = store.prepareExisting()
        File(folder, "model.gguf").appendBytes(byteArrayOf(1))
        assertNull(store.current())
        fails<IOException> { store.acquire(rebuilt) }
    }
}
