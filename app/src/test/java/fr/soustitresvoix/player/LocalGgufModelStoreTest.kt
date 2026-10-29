package fr.soustitresvoix.player

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
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
    private fun namedHeader(template: String? = null): ByteArray {
        val output = ByteArrayOutputStream()
        fun number(value: Long, width: Int) { output.write(ByteBuffer.allocate(width).order(ByteOrder.LITTLE_ENDIAN)
            .also { if (width == 4) it.putInt(value.toInt()) else it.putLong(value) }.array()) }
        fun text(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); number(encoded.size.toLong(), 8); output.write(encoded) }
        val entries = listOf("general.architecture" to "hunyuan-dense", "general.name" to "Global_Step_560") +
            listOfNotNull(template?.let { "tokenizer.chat_template" to it })
        output.write(byteArrayOf(71, 71, 85, 70)); number(3, 4); number(354, 8); number(entries.size.toLong(), 8)
        entries.forEach { (key, value) ->
            text(key); number(8, 4); text(value)
        }
        return output.toByteArray()
    }
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

    @Test fun importedPublisherMetadataIsPersistedWithoutChangingTheSourceOrDigest() {
        val folder = temporary.newFolder()
        val original = namedHeader() + ByteArray(72)
        val store = LocalGgufModelStore(folder)
        val model = store.importModel(ByteArrayInputStream(original))
        assertEquals("Global_Step_560", model.metadata.name)
        assertEquals("hunyuan-dense", model.metadata.architecture)
        assertEquals(sha(original), model.sha256)
        assertArrayEquals(original, File(folder, "model.gguf").readBytes())
        assertEquals(model.metadata, LocalGgufModelStore(folder).current()?.metadata)
        assertFalse(model.toString().contains("Global_Step"))
    }

    @Test fun oldVersionOneIdentityUpgradesHeaderHintsWithoutHashingAllWeightsAgain() {
        val folder = temporary.newFolder()
        val original = namedHeader() + ByteArray(512 * 1024)
        val file = File(folder, "model.gguf").apply { writeBytes(original) }
        val digest = sha(original)
        DataOutputStream(File(folder, "model.sha256").outputStream()).use {
            it.writeInt(0x4c474746); it.writeInt(1); it.writeUTF(digest)
            it.writeLong(file.length()); it.writeLong(file.lastModified())
        }
        val store = LocalGgufModelStore(folder)
        val previous = requireNotNull(store.current())
        assertEquals(LocalGgufMetadata(), previous.metadata)
        val upgraded = store.prepareExisting(onProgress = { fail("An existing valid SHA must not require reading the weights again") })
        assertEquals(previous.identity, upgraded.identity)
        assertEquals("Global_Step_560", upgraded.metadata.name)
        assertEquals(upgraded.metadata, LocalGgufModelStore(folder).current()?.metadata)
        store.acquire(previous).close()
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun templatePresenceSurvivesNewDescriptorAndUpgradesVersionTwoWithoutRehashingWeights() {
        val folder = temporary.newFolder()
        val template = "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}"
        val original = namedHeader(template) + ByteArray(128 * 1024)
        val store = LocalGgufModelStore(folder)
        val imported = store.importModel(ByteArrayInputStream(original))
        assertTrue(imported.metadata.chatTemplateAvailable)
        assertTrue(requireNotNull(LocalGgufModelStore(folder).current()).metadata.chatTemplateAvailable)
        DataOutputStream(File(folder, "model.sha256").outputStream()).use {
            it.writeInt(0x4c474746); it.writeInt(2); it.writeUTF(imported.sha256)
            it.writeLong(imported.bytes); it.writeLong(imported.modifiedMillis)
            it.writeUTF(imported.metadata.architecture); it.writeUTF(imported.metadata.name)
            it.writeUTF(imported.metadata.basename); it.writeUTF(imported.metadata.finetune)
            it.writeUTF(imported.metadata.fileType); it.writeBoolean(imported.metadata.translationTemplate)
        }
        val legacy = requireNotNull(store.current())
        assertFalse(legacy.metadata.chatTemplateAvailable)
        val upgraded = store.prepareExisting(onProgress = { fail("A valid legacy digest must not require hashing model weights") })
        assertEquals(imported.sha256, upgraded.sha256)
        assertEquals(imported.identity, upgraded.identity)
        assertTrue(upgraded.metadata.chatTemplateAvailable)
        assertEquals(upgraded.metadata, LocalGgufModelStore(folder).current()?.metadata)
        store.acquire(legacy).close()
        assertArrayEquals(original, File(folder, "model.gguf").readBytes())
    }

    @Test fun aTemplateHintReportsPresenceWithoutCertifyingTheCustomProgram() {
        val hints = LocalGgufMetadataReader.parse(namedHeader("custom format not supported by the native renderer"))
        assertTrue(hints.chatTemplateAvailable)
        assertFalse(hints.translationTemplate)
        assertEquals("hunyuan-dense", hints.architecture)
        assertFalse(LocalGgufMetadataReader.parse(namedHeader("")).chatTemplateAvailable)
    }

    @Test fun truncatedOrOutsideBoundedSampleTemplateDoesNotInventCapability() {
        val header = namedHeader("<|im_start|>user{{ content }}<|im_end|>")
        assertFalse(LocalGgufMetadataReader.parse(header.copyOf(header.size - 1)).chatTemplateAvailable)
        val longHeader = namedHeader("x".repeat(LocalGgufMetadataReader.MAX_HEADER_BYTES))
        assertFalse(LocalGgufMetadataReader.parse(longHeader).chatTemplateAvailable)
        assertEquals("Global_Step_560", LocalGgufMetadataReader.parse(longHeader).name)
    }

    @Test fun interruptionArrivingAfterAtomicReplacementStillCompletesTheNewIdentity() {
        val folder = temporary.newFolder()
        val store = LocalGgufModelStore(folder)
        val previous = store.importModel(ByteArrayInputStream(bytes()))
        val replacement = namedHeader() + ByteArray(100)
        val lateCancellation = AtomicBoolean(false)
        val input = object : ByteArrayInputStream(replacement) {
            override fun close() {
                // DigestInputStream closes after DiagnosticModelImport has
                // returned, therefore after its atomic replacement committed.
                lateCancellation.set(true)
                Thread.currentThread().interrupt()
            }
        }
        try {
            val imported = store.importModel(input, cancelled = lateCancellation::get)
            assertTrue(lateCancellation.get())
            assertEquals(sha(replacement), imported.sha256)
            assertNotEquals(previous.sha256, imported.sha256)
            assertEquals(imported.identity, store.current()?.identity)
            assertEquals("Global_Step_560", imported.metadata.name)
        } finally { Thread.interrupted() }
    }
}
