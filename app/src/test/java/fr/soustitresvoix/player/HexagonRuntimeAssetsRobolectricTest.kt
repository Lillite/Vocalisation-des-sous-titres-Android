package fr.soustitresvoix.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HexagonRuntimeAssetsRobolectricTest {
    @get:Rule val temporary = TemporaryFolder()
    private val names = listOf(73, 75, 79, 81, 85).map { "libggml-htp-v$it.so" }
    private fun elf(marker: Int): ByteArray = ByteArray(96).also {
        it[0] = 127; it[1] = 69; it[2] = 76; it[3] = 70; it[4] = 1; it[5] = 1
        it[16] = 3; it[18] = 164.toByte(); it[64] = marker.toByte()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun assets(): MutableMap<String, ByteArray> {
        val result = names.mapIndexed { index, name -> "hexagon/$name" to elf(index) }.toMap().toMutableMap()
        val files = JSONArray()
        for (name in names) result.getValue("hexagon/$name").let { bytes ->
            files.put(JSONObject().put("name", name).put("size", bytes.size).put("sha256", hash(bytes)))
        }
        result["hexagon/manifest.json"] = JSONObject().put("version", 1)
            .put("sourceRevision", HexagonRuntimeAssets.SOURCE_REVISION).put("files", files)
            .toString().toByteArray()
        return result
    }
    private fun prepare(directory: File, assets: Map<String, ByteArray>, opened: MutableList<String> = mutableListOf(),
        cancelled: () -> Boolean = { false }) = HexagonRuntimeAssets.prepare(directory, { path ->
        opened += path
        ByteArrayInputStream(assets.getValue(path))
    }, cancelled)
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Malformed or untrusted Hexagon payload accepted") }
        catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.BACKEND, failure.code) }
    }
    private fun mutateManifest(data: MutableMap<String, ByteArray>, mutate: (JSONObject) -> Unit) {
        val value = JSONObject(data.getValue("hexagon/manifest.json").toString(Charsets.UTF_8))
        mutate(value)
        data["hexagon/manifest.json"] = value.toString().toByteArray()
    }

    @Test fun extractsCompletePinnedPayloadAndRechecksExistingBytesWithoutReopeningAssets() {
        val base = temporary.newFolder()
        val data = assets()
        val opened = mutableListOf<String>()
        val first = prepare(base, data, opened)
        assertEquals(names.toSet(), first.list()?.toSet())
        for (name in names) assertArrayEquals(data.getValue("hexagon/$name"), File(first, name).readBytes())
        assertEquals(6, opened.size)
        opened.clear()
        val second = prepare(base, data, opened)
        assertEquals(first, second)
        assertEquals(listOf("hexagon/manifest.json"), opened)
        assertEquals(1, File(base, "hexagon-runtime").list()?.size)
    }

    @Test fun refusesWrongHashAndDoesNotPublishPartialExtraction() {
        for (invalid in listOf(elf(99), elf(2).copyOf(80), elf(2).copyOf(97))) {
            val base = temporary.newFolder()
            val data = assets()
            data["hexagon/${names[2]}"] = invalid
            expectFailure { prepare(base, data) }
            assertTrue(File(base, "hexagon-runtime").list().orEmpty().isEmpty())
        }
    }

    @Test fun refusesTraversalDuplicateMissingWrongRevisionAndOversizedManifest() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("files").getJSONObject(0).put("name", "../outside.so") },
            { it.getJSONArray("files").getJSONObject(0).put("name", names[1]) },
            { it.getJSONArray("files").remove(0) },
            { it.put("sourceRevision", "0".repeat(40)) },
            { it.getJSONArray("files").getJSONObject(0).put("size", 33L * 1024 * 1024) },
            { it.getJSONArray("files").getJSONObject(0).put("size", "96") },
            { it.put("version", 1.5) },
            { it.put("unexpected", true) })
        for (mutation in mutations) {
            val data = assets(); mutateManifest(data, mutation)
            expectFailure { prepare(temporary.newFolder(), data) }
        }
        val huge = assets().apply { put("hexagon/manifest.json", ByteArray(16_385) { 32 }) }
        expectFailure { prepare(temporary.newFolder(), huge) }
    }

    @Test fun refusesWrongElfArchitectureEvenWithMatchingManifestDigest() {
        val data = assets()
        val bytes = data.getValue("hexagon/${names[0]}").copyOf().apply { this[4] = 2; this[18] = 183.toByte() }
        data["hexagon/${names[0]}"] = bytes
        mutateManifest(data) { it.getJSONArray("files").getJSONObject(0).put("sha256", hash(bytes)) }
        expectFailure { prepare(temporary.newFolder(), data) }
    }

    @Test fun refusesCorruptExistingPayloadRatherThanOverwritingPotentiallyMappedLibrary() {
        val data = assets()
        val base = temporary.newFolder()
        val extracted = prepare(base, data)
        val file = File(extracted, names[0])
        file.setWritable(true, true)
        file.writeBytes(elf(88))
        expectFailure { prepare(base, data) }
        assertArrayEquals(elf(88), file.readBytes())
    }

    @Test fun refusesEscapingPrivateDirectoryAndAdditionalFiles() {
        val base = temporary.newFolder()
        val elsewhere = temporary.newFolder()
        Files.createSymbolicLink(File(base, "hexagon-runtime").toPath(), elsewhere.toPath())
        expectFailure { prepare(base, assets()) }
        assertTrue(elsewhere.list().orEmpty().isEmpty())
        val data = assets()
        val secondBase = temporary.newFolder()
        val extracted = prepare(secondBase, data)
        File(extracted, "unlisted.so").writeText("untrusted")
        expectFailure { prepare(secondBase, data) }
    }

    @Test fun cancellationBeforePreparationDoesNotReadAssetsOrCreateDirectories() {
        val base = temporary.newFolder()
        val opened = mutableListOf<String>()
        try { prepare(base, assets(), opened) { true }; fail("Cancellation ignored") }
        catch (failure: LocalGgufRuntime.Failure) { assertEquals(LocalGgufRuntime.Code.CANCELLED, failure.code) }
        assertTrue(opened.isEmpty())
        assertTrue(base.list().orEmpty().isEmpty())
    }
}
