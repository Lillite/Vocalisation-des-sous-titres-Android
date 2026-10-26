package fr.soustitresvoix.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class OfflineSpeechLibraryTest {
    private fun withLibrary(block: (File, OfflineSpeechLibrary) -> Unit) {
        val directory = Files.createTempDirectory("offline-speech-test").toFile()
        try { block(directory, OfflineSpeechLibrary(directory, reserveBytes = 0)) }
        finally { directory.deleteRecursively() }
    }
    private fun wav(file: File, millis: Int = 1000) {
        val sampleRate = 8000; val dataSize = sampleRate * 2 * millis / 1000
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()); buffer.putInt(36 + dataSize); buffer.put("WAVEfmt ".toByteArray())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1); buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2); buffer.putShort(2); buffer.putShort(16)
        buffer.put("data".toByteArray()); buffer.putInt(dataSize)
        file.writeBytes(buffer.array())
    }
    private fun configure(library: OfflineSpeechLibrary, complete: Boolean = true) = library.configure(
        "content://private/film", "Film d’essai", listOf(
            OfflineSpeechCue("sign", 0, 7000, "ENTRÉE"),
            OfflineSpeechCue("line-1", 2000, 4000, "Bonjour. Comment vas-tu ?"),
            OfflineSpeechCue("line-2", 6000, 8000, "Bonjour. Comment vas-tu ?")
        ), 10_000, complete, "voice-rate-filter-config", "fr")
    private fun fill(library: OfflineSpeechLibrary, plan: OfflineSpeechLibrary.Plan) {
        plan.segments.forEach { segment ->
            val source = library.temporary(plan.mediaKey); wav(source)
            assertNotNull(library.put(plan.mediaKey, segment.key, source))
            source.delete()
        }
    }
    @Test fun completePinnedPlanSurvivesRecreationAndRequiresEveryWaveform() = withLibrary { directory, library ->
        val plan = configure(library)
        assertFalse(library.status(plan.mediaKey).ready)
        assertEquals(0, library.status(plan.mediaKey).readySegments)
        fill(library, plan)
        val reopened = OfflineSpeechLibrary(directory, 0)
        assertEquals(plan.cues, reopened.plan(plan.mediaKey)!!.cues)
        assertEquals(3, reopened.plan(plan.mediaKey)!!.cues.size)
        assertEquals(3, plan.segments.size)
        val status = reopened.status(plan.mediaKey)
        assertTrue(status.ready)
        assertEquals(10_000L, status.readyThroughMs)
        assertEquals(status.expectedSegments, status.readySegments)
    }
    @Test fun partialTranslationNeverClaimsReadyEvenWhenAllItsAudioExists() = withLibrary { _, library ->
        val plan = configure(library, complete = false); fill(library, plan)
        val status = library.status(plan.mediaKey)
        assertEquals(status.expectedSegments, status.readySegments)
        assertFalse(status.sourceComplete); assertFalse(status.ready)
        assertEquals(8000L, status.readyThroughMs)
    }
    @Test fun corruptedPcmWithValidHeaderInvalidatesReadinessAndCanBeRegenerated() = withLibrary { _, library ->
        val plan = configure(library); fill(library, plan)
        val first = plan.segments.first()
        val file = library.get(plan.mediaKey, first.key)!!.file
        RandomAccessFile(file, "rw").use { it.seek(100); it.write(1) }
        assertNull(library.get(plan.mediaKey, first.key))
        assertFalse(library.status(plan.mediaKey).ready)
        val replacement = library.temporary(plan.mediaKey); wav(replacement)
        library.put(plan.mediaKey, first.key, replacement); replacement.delete()
        assertTrue(library.status(plan.mediaKey).ready)
    }
    @Test fun sourceOrVoiceChangeRetainsOldAudioButRequiresNewExactKeys() = withLibrary { _, library ->
        val old = configure(library); fill(library, old)
        val changed = library.configure("content://private/film", "Film", old.cues, 10_000, true, "another-voice", "fr")
        assertEquals(old.mediaKey, changed.mediaKey)
        assertFalse(library.status(changed.mediaKey).ready)
        assertNotNull(library.get(old.mediaKey, old.segments.first().key))
        assertNull(library.get(changed.mediaKey, changed.segments.first().key))
    }
    @Test fun textsIndexAndVoicesAreIndependentlyRemovableAndWorkRemainsListed() = withLibrary { _, library ->
        val plan = configure(library); fill(library, plan)
        library.removeTexts(plan.mediaKey)
        assertFalse(library.status(plan.mediaKey).ready)
        assertEquals(1, library.list().size)
        assertNotNull(library.get(plan.mediaKey, plan.segments.first().key))
        configure(library)
        assertTrue(library.status(plan.mediaKey).ready)
        library.removeIndex(plan.mediaKey)
        assertNull(library.plan(plan.mediaKey))
        assertEquals(1, library.list().size)
        configure(library); library.removeVoices(plan.mediaKey)
        assertNotNull(library.plan(plan.mediaKey))
        assertFalse(library.status(plan.mediaKey).ready)
        assertEquals(0, library.status(plan.mediaKey).readySegments)
    }
    @Test fun incompleteCheckpointAndMixedPlanComponentsFailClosed() = withLibrary { directory, library ->
        val plan = configure(library); fill(library, plan)
        val texts = File(directory, "${plan.mediaKey}/texts.bin")
        val bytes = texts.readBytes(); texts.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(OfflineSpeechLibrary(directory, 0).plan(plan.mediaKey))
        assertFalse(library.status(plan.mediaKey).ready)
        configure(library)
        assertTrue(library.status(plan.mediaKey).ready)
        File(directory, "${plan.mediaKey}/index.bin").appendBytes(byteArrayOf(1))
        assertFalse(library.status(plan.mediaKey).ready)
    }
    @Test fun estimatesExposeRequiredStorageAndInsufficientSpaceRefusesPlan() = withLibrary { directory, library ->
        val plan = configure(library)
        val status = library.status(plan.mediaKey)
        assertTrue(status.estimatedBytes > 0); assertTrue(status.availableBytes > 0)
        val unavailable = OfflineSpeechLibrary(File(directory, "no-room"), Long.MAX_VALUE)
        try { configure(unavailable); fail("Expected a storage refusal") } catch (_: java.io.IOException) { }
        assertTrue(unavailable.list().isEmpty())
    }
    @Test fun invalidIdsAndBoundsCannotCreateAmbiguousPlansOrEscapeWorkDirectory() = withLibrary { directory, library ->
        val cue = OfflineSpeechCue("same", 0, 1000, "Hello")
        try { library.configure("film", "", listOf(cue, cue), 2000, true, "voice"); fail() }
        catch (_: IllegalArgumentException) { }
        try { library.configure("film", "", listOf(cue.copy(endMs = 9000)), 2000, true, "voice"); fail() }
        catch (_: IllegalArgumentException) { }
        try { library.removeVoices("../outside"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
    @Test fun interimProgressNeverCertifiesReadyBeforeExplicitDeepValidation() = withLibrary { _, library ->
        val plan = configure(library); fill(library, plan)
        val interim = library.status(plan.mediaKey, deepValidation = false)
        assertEquals(interim.expectedSegments, interim.readySegments)
        assertFalse(interim.ready)
        assertTrue(library.status(plan.mediaKey).ready)
    }
    @Test fun storagePreviewDoesNotPersistPrivateTextsOrStartAWork() = withLibrary { directory, library ->
        val cue = OfflineSpeechCue("preview", 0, 1000, "Texte privé de démonstration.")
        val preview = library.preview("preview-media", "Aperçu", listOf(cue), 2000, true, "voice", "fr")
        assertEquals(1, preview.expectedSegments)
        assertEquals(0, preview.readySegments)
        assertTrue(preview.estimatedBytes > 0)
        assertEquals(preview.estimatedBytes, preview.additionalBytes)
        assertFalse(preview.ready)
        assertTrue(directory.listFiles().isNullOrEmpty())
        assertTrue(library.list().isEmpty())
        val plan = library.configure("preview-media", "Aperçu", listOf(cue), 2000, true, "voice", "fr")
        fill(library, plan)
        assertTrue(library.preview("preview-media", "Aperçu", listOf(cue), 2000, true, "voice", "fr").ready)
    }
}
