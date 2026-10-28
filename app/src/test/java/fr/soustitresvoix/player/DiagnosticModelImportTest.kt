package fr.soustitresvoix.player

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real file/stream/atomic-replacement checks of the basic import contract.
 * These deliberately tiny GGUF headers do not contain usable model tensors;
 * successful import is not a model compatibility or inference assertion. */
class DiagnosticModelImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun bytes(version: Int = 3, payloadSize: Int = 173): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(71, 71, 85, 70)).putInt(version).putLong(0).putLong(0).array()
        return header + ByteArray(payloadSize) { ((it * 31 + 7) and 255).toByte() }
    }

    private data class Destination(val file: File, val oldBytes: ByteArray) {
        val pending get() = File(file.parentFile, "model-import.part")
        fun assertUnchanged() {
            assertArrayEquals(oldBytes, file.readBytes())
            assertFalse("A failed import must remove its temporary partial file", pending.exists())
        }
    }

    private fun destination(): Destination {
        val file = File(temporary.newFolder(), "selected-model.gguf")
        val previous = byteArrayOf(9, 8, 7, 6, 5)
        file.writeBytes(previous)
        return Destination(file, previous)
    }

    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure.javaClass.simpleName}", failure)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun completeVersionThreeImportHandlesFragmentedReadsAndReportsExactBytes() {
        val expected = bytes(payloadSize = 700)
        val destination = destination()
        val progress = mutableListOf<Long>()
        val fragmented = object : ByteArrayInputStream(expected) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 3))
        }
        val result = fragmented.use {
            DiagnosticModelImport.copy(it, destination.file, expected.size.toLong(), onProgress = { count -> progress += count })
        }
        assertEquals(expected.size.toLong(), result.bytes)
        assertEquals(3, result.formatVersion)
        assertArrayEquals(expected, destination.file.readBytes())
        assertFalse(destination.pending.exists())
        assertTrue(progress.isNotEmpty())
        assertEquals(expected.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun versionTwoWithUnknownProviderSizeStillCopiesTheWholeStream() {
        val expected = bytes(version = 2, payloadSize = 289)
        val destination = destination()
        val result = ByteArrayInputStream(expected).use {
            DiagnosticModelImport.copy(it, destination.file, expectedBytes = -1)
        }
        assertEquals(2, result.formatVersion)
        assertEquals(expected.size.toLong(), result.bytes)
        assertArrayEquals(expected, destination.file.readBytes())
        assertFalse(destination.pending.exists())
    }

    @Test fun invalidMagicCannotReplaceAnExistingModel() {
        val invalid = bytes().also { it[0] = 0 }
        val destination = destination()
        val before = invalid.copyOf()
        ByteArrayInputStream(invalid).use {
            fails<IllegalArgumentException> { DiagnosticModelImport.copy(it, destination.file) }
        }
        destination.assertUnchanged()
        assertArrayEquals(before, invalid)
    }

    @Test fun unsupportedOrWrongEndianVersionCannotReplaceAnExistingModel() {
        for (version in listOf(0, 1, 4, 3 shl 24)) {
            val destination = destination()
            ByteArrayInputStream(bytes(version)).use {
                fails<IllegalArgumentException> { DiagnosticModelImport.copy(it, destination.file) }
            }
            destination.assertUnchanged()
        }
    }

    @Test fun truncatedHeaderCannotReplaceAnExistingModel() {
        for (size in listOf(0, 4, 8, 23)) {
            val destination = destination()
            ByteArrayInputStream(bytes().copyOf(size)).use {
                fails<IllegalArgumentException> { DiagnosticModelImport.copy(it, destination.file) }
            }
            destination.assertUnchanged()
        }
    }

    @Test fun eitherProviderSizeMismatchRejectsTheCopyAndCleansPartialBytes() {
        val expected = bytes(payloadSize = 400)
        for (announced in listOf(expected.size.toLong() - 1, expected.size.toLong() + 1)) {
            val destination = destination()
            ByteArrayInputStream(expected).use {
                fails<IllegalArgumentException> {
                    DiagnosticModelImport.copy(it, destination.file, expectedBytes = announced)
                }
            }
            destination.assertUnchanged()
        }
    }

    @Test fun cancellationBeforeReadingPreservesTheOldModelAndCleansAnOldPartialFile() {
        val destination = destination()
        destination.pending.writeBytes(byteArrayOf(1, 2, 3))
        var reads = 0
        val input = object : ByteArrayInputStream(bytes()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(buffer, offset, length)
            }
        }
        input.use {
            fails<InterruptedIOException> { DiagnosticModelImport.copy(it, destination.file, cancelled = { true }) }
        }
        assertEquals(0, reads)
        destination.assertUnchanged()
    }

    @Test fun cancellationAfterARealChunkKeepsSourceAndPreviousDestinationIntact() {
        val source = temporary.newFile("original-user-model.gguf")
        val expected = bytes(payloadSize = 600_000)
        source.writeBytes(expected)
        val destination = destination()
        val cancel = AtomicBoolean(false)
        var progressObserved = false
        source.inputStream().use { input ->
            fails<InterruptedIOException> {
                DiagnosticModelImport.copy(input, destination.file, expected.size.toLong(),
                    cancelled = cancel::get, onProgress = {
                        assertTrue(it > 24)
                        assertTrue(destination.pending.exists())
                        progressObserved = true
                        cancel.set(true)
                    })
            }
        }
        assertTrue(progressObserved)
        destination.assertUnchanged()
        assertArrayEquals(expected, source.readBytes())
    }

    @Test fun sourceFileRemainsByteForByteIdenticalAfterSuccessfulReplacement() {
        val source = temporary.newFile("original-success-model.gguf")
        val expected = bytes(payloadSize = 4_096)
        source.writeBytes(expected)
        val destination = destination()
        source.inputStream().use { DiagnosticModelImport.copy(it, destination.file, source.length()) }
        assertTrue(source.isFile)
        assertArrayEquals(expected, source.readBytes())
        assertArrayEquals(expected, destination.file.readBytes())
        assertFalse(destination.pending.exists())
    }

    @Test fun anInterruptedWorkerCancelsEvenWithoutACancellationCallback() {
        val destination = destination()
        val wasInterrupted = Thread.currentThread().isInterrupted
        try {
            Thread.currentThread().interrupt()
            ByteArrayInputStream(bytes()).use {
                fails<InterruptedIOException> { DiagnosticModelImport.copy(it, destination.file) }
            }
            assertTrue("The importer must not consume the interruption flag", Thread.currentThread().isInterrupted)
            destination.assertUnchanged()
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }
}
