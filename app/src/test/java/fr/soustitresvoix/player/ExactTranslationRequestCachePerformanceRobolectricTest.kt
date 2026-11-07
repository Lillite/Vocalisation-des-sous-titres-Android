package fr.soustitresvoix.player

import android.util.AtomicFile
import fr.soustitresvoix.core.TranslationPlan
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic I/O counts and JVM allocation bytes, never a wall-clock claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExactTranslationRequestCachePerformanceRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun belowQuotaInventorySkipsAllRecencyReadsAndUnrelatedFileStats() {
        val root = temporary.newFolder(); val counts = Counts(); val directory = CountedDirectory(root, counts)
        repeat(512) { index -> File(root, "%064x.request".format(index)).writeText("x") }
        repeat(32) { index -> File(root, "unrelated-$index.srt").writeText("Source") }
        ExactTranslationRequestCache(directory).write("f".repeat(64), listOf("Bonjour"))
        val optimized = counts.copy()
        assertEquals(1, optimized.listings)
        assertEquals(513, optimized.types)
        assertEquals(513, optimized.lengths)
        assertEquals(0, optimized.modified)

        counts.reset()
        // Reference: the previous implementation grouped and sorted on every
        // write, even when its real inventory was comfortably below the quota.
        val entries = directory.listFiles()!!.filter { it.isFile && it.name.endsWith(".request") }
        val groups = entries.groupBy { it.name }
        val bytes = entries.sumOf { it.length() }
        val sorted = groups.entries.sortedBy { (_, files) -> files.maxOf { it.lastModified() } }
        assertTrue(bytes < ExactTranslationRequestCache.MAX_BYTES)
        assertEquals(513, sorted.size)
        assertEquals(545, counts.types)
        assertTrue(counts.modified >= 1024)
        println("Exact request inventory: entries=513, optimized=$optimized, reference=$counts")
    }

    @Test fun largeReadsAllocateLessThanThePreviousWholeBodyCopyPath() {
        // HotSpot exposes a per-thread byte counter. Other JVMs still run the
        // functional and I/O regressions; no timing threshold is substituted.
        val management = runCatching { Class.forName("java.lang.management.ManagementFactory") }.getOrNull()
        val counterType = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        assumeTrue("The JVM does not expose thread allocation counters", management != null && counterType != null)
        val bean = management!!.getMethod("getThreadMXBean").invoke(null)
        assumeTrue(counterType!!.isInstance(bean))
        assumeTrue(counterType.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
        counterType.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val getter = counterType.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        fun allocated() = getter.invoke(bean, thread) as Long

        val directory = temporary.newFolder(); val requests = ExactTranslationRequestCache(directory)
        val key = "e".repeat(64)
        val values = List(10) { "漢".repeat(TranslationPlan.MAX_CUE_CHARACTERS) }
        requests.write(key, values)
        val path = File(directory, "$key.request")
        assertTrue(path.length() > 700_000)
        repeat(8) {
            assertEquals(values, requests.read(key))
            assertEquals(values, previousRead(path, key))
        }
        val iterations = 12
        fun measured(read: () -> List<String>?): Long {
            val before = allocated()
            repeat(iterations) { assertEquals(values, read()) }
            return allocated() - before
        }
        val optimized = measured { requests.read(key) }
        val reference = measured { previousRead(path, key) }
        val removedBodyBytes = (path.length() - 32) * iterations
        assertTrue("One full body copy per read must be absent: optimized=$optimized, reference=$reference",
            reference - optimized >= removedBodyBytes / 2)
        println("Exact request reads: iterations=$iterations, entryBytes=${path.length()}, optimizedAllocated=$optimized, referenceAllocated=$reference")
    }

    /** Original v1 read path retained only as an allocation benchmark baseline. */
    private fun previousRead(path: File, key: String): List<String>? = AtomicFile(path).openRead().use { stream ->
        if (path.length() !in 1..1024 * 1024L) return null
        val all = stream.readBytes()
        if (all.size <= 32) return null
        val body = all.copyOfRange(0, all.size - 32)
        val expected = all.copyOfRange(all.size - 32, all.size)
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(body))) return null
        val input = DataInputStream(ByteArrayInputStream(body))
        if (input.readInt() != 0x53545652 || input.readInt() != 1 || input.readUTF() != key) return null
        val count = input.readInt()
        if (count !in 1..TranslationRunner.MAX_BATCH_ITEMS) return null
        val values = ArrayList<String>(count)
        repeat(count) {
            val length = input.readInt()
            if (length !in 1..TranslationPlan.MAX_CUE_CHARACTERS * 4) return null
            val bytes = ByteArray(length).also { input.readFully(it) }
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            if (value.isBlank() || '\u0000' in value || value.length > TranslationPlan.MAX_CUE_CHARACTERS) return null
            values += value
        }
        if (input.read() != -1) return null
        path.setLastModified(System.currentTimeMillis())
        values
    }

    private data class Counts(var listings: Int = 0, var types: Int = 0, var lengths: Int = 0, var modified: Int = 0) {
        fun reset() { listings = 0; types = 0; lengths = 0; modified = 0 }
    }
    private class CountedDirectory(root: File, private val counts: Counts) : File(root.path) {
        override fun listFiles(): Array<File>? {
            counts.listings++
            return super.listFiles()?.map { CountedEntry(it, counts) as File }?.toTypedArray()
        }
    }
    private class CountedEntry(file: File, private val counts: Counts) : File(file.path) {
        override fun isFile(): Boolean { counts.types++; return super.isFile() }
        override fun length(): Long { counts.lengths++; return super.length() }
        override fun lastModified(): Long { counts.modified++; return super.lastModified() }
    }
}
