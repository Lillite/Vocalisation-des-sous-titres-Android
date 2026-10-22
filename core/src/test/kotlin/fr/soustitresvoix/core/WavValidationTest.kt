package fr.soustitresvoix.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavValidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private var sequence = 0

    private fun little32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value.toInt()).array()

    private fun format(encoding: Int = 1, channels: Int = 1, rate: Long = 8_000,
        bits: Int = 16, align: Int = channels * (bits / 8), byteRate: Long = rate * align,
        size: Int = 16, extensionSize: Int = 0): ByteArray {
        val bytes = ByteArray(size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(encoding.toShort()).putShort(channels.toShort()).putInt(rate.toInt())
            .putInt(byteRate.toInt()).putShort(align.toShort()).putShort(bits.toShort())
        if (size >= 18) buffer.putShort(extensionSize.toShort())
        return bytes
    }

    private fun chunk(name: String, bytes: ByteArray, padded: Boolean = true): ByteArray =
        ByteArrayOutputStream().apply {
            write(name.toByteArray(Charsets.US_ASCII)); write(little32(bytes.size.toLong())); write(bytes)
            if (padded && bytes.size % 2 != 0) write(0)
        }.toByteArray()

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII)); chunks.forEach { write(it) }
        }.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + little32(body.size.toLong()) + body
    }

    private fun wave(format: ByteArray = format(), payload: ByteArray = ByteArray(16_000)): ByteArray =
        riff(chunk("fmt ", format), chunk("data", payload))

    private fun set32(bytes: ByteArray, offset: Int, value: Long): ByteArray = bytes.copyOf().also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value.toInt())
    }

    private fun file(bytes: ByteArray): File = temporary.newFile("wave-${++sequence}.wav").apply { writeBytes(bytes) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun acceptsWholeFramesForIntegerAndFloatPcmWithoutChangingSamples() {
        for ((encoding, bits) in listOf(1 to 8, 1 to 16, 1 to 24, 1 to 32, 3 to 32, 3 to 64)) {
            for (channels in 1..2) for (formatSize in listOf(16, 18, 20)) {
                val payload = ByteArray(8_000 * channels * (bits / 8)) { index ->
                    // Deliberate leading and trailing silence is part of the data.
                    if (index < 80 || index >= 8_000 * channels * (bits / 8) - 80) 0 else (index % 127).toByte()
                }
                val bytes = wave(format(encoding, channels, bits = bits, size = formatSize,
                    extensionSize = if (formatSize == 20) 2 else 0), payload)
                val input = file(bytes)
                val info = WavInfo.read(input)
                assertNotNull("encoding=$encoding bits=$bits channels=$channels fmt=$formatSize", info)
                assertEquals(1_000L, info!!.durationMs)
                assertEquals(bytes.size.toLong(), info.bytes)
                assertArrayEquals("Inspection must preserve breathing and all other PCM samples", bytes, input.readBytes())
            }
        }
    }

    @Test fun acceptsAncillaryChunksOddPaddingAndDataBeforeFormat() {
        val payload = ByteArray(8_000) { 128.toByte() }
        val bytes = riff(chunk("JUNK", byteArrayOf(1, 2, 3)), chunk("data", payload),
            chunk("fmt ", format(bits = 8, size = 18)), chunk("LIST", byteArrayOf(7, 8, 9)))
        val info = WavInfo.read(file(bytes))
        assertNotNull(info)
        assertEquals(1_000L, info!!.durationMs)
    }

    @Test fun acceptsTheUnpaddedFinalPcm8DataWrittenByAndroidTts() {
        // Android FileSynthesisCallback writes a 44-byte header and dataLength
        // bytes, without adding the RIFF padding byte after odd PCM8 output.
        val bytes = riff(chunk("fmt ", format(bits = 8)),
            chunk("data", ByteArray(8_001) { 128.toByte() }, padded = false))
        val input = file(bytes)
        val info = WavInfo.read(input)
        assertNotNull(info)
        assertEquals(1_000L, info!!.durationMs)
        assertEquals(8_045L, info.bytes)
        assertArrayEquals(bytes, input.readBytes())
    }

    @Test fun rejectsImpossibleAndInconsistentPcmDescriptions() {
        val invalid = linkedMapOf(
            "zero channels despite plausible byte rate" to format(channels = 0, align = 2, byteRate = 16_000),
            "zero sample rate despite plausible byte rate" to format(rate = 0, byteRate = 16_000),
            "zero block alignment" to format(align = 0, byteRate = 16_000),
            "zero bit depth" to format(bits = 0, align = 2, byteRate = 16_000),
            "stereo stride still describes mono" to format(channels = 2, align = 2),
            "byte rate does not describe the frames" to format(byteRate = 8_000),
            "unsupported packed integer width" to format(bits = 12, align = 2, byteRate = 16_000),
            "invalid IEEE float width" to format(encoding = 3, bits = 16),
            "compressed format disguised as PCM" to format(encoding = 6),
            "byte rate overflow cannot wrap into a valid value" to format(channels = 2,
                rate = 0xffff_ffffL, bits = 32, byteRate = 0xffff_fff8L)
        )
        for ((description, fmt) in invalid) assertNull(description, WavInfo.read(file(wave(fmt))))
    }

    @Test fun rejectsPartialAudioFramesAndIncompleteFormatExtensions() {
        assertNull("The last PCM16 stereo frame is incomplete",
            WavInfo.read(file(wave(format(channels = 2), ByteArray(7)))))
        assertNull("A single dangling cbSize byte is not WAVEFORMATEX",
            WavInfo.read(file(wave(format(size = 17)))))
        assertNull("cbSize cannot extend into the following chunk",
            WavInfo.read(file(wave(format(size = 18, extensionSize = 2)))))
    }

    @Test fun respectsDeclaredRiffBoundsAndRejectsTruncatedChunks() {
        val valid = wave()
        val invalid = linkedMapOf(
            "RIFF size excludes the audio chunk" to set32(valid, 4, 28),
            "RIFF size extends past the actual file" to set32(valid, 4, valid.size.toLong()),
            "audio chunk claims bytes outside RIFF" to set32(valid, 40, 16_002),
            "truncated payload" to valid.copyOf(valid.size - 1),
            "trailing partial chunk header inside RIFF" to riff(chunk("fmt ", format()),
                chunk("data", ByteArray(16_000)), byteArrayOf(1, 2)),
            "missing ancillary chunk padding" to riff(chunk("fmt ", format()),
                chunk("data", ByteArray(16_000)), chunk("JUNK", ByteArray(5), padded = false)),
            "invalid later chunk must not be hidden by an early data return" to riff(
                chunk("fmt ", format()), chunk("data", ByteArray(16_000)),
                "JUNK".toByteArray(Charsets.US_ASCII) + little32(100) + byteArrayOf(1, 2))
        )
        for ((description, bytes) in invalid) assertNull(description, WavInfo.read(file(bytes)))
    }

    @Test fun rejectsAmbiguousRepeatedFormatOrDataChunks() {
        assertNull(WavInfo.read(file(riff(chunk("fmt ", format()), chunk("fmt ", format()),
            chunk("data", ByteArray(16_000))))))
        assertNull(WavInfo.read(file(riff(chunk("fmt ", format()), chunk("data", ByteArray(16_000)),
            chunk("data", ByteArray(16_000))))))
    }

    @Test fun rejectsFilesThatCannotBeFullyValidatedWithinTheChunkBudget() {
        val chunks = mutableListOf(chunk("fmt ", format()))
        repeat(62) { chunks.add(chunk("JUNK", byteArrayOf())) }
        chunks.add(chunk("data", ByteArray(16_000)))
        assertNotNull("The documented 64-chunk bound is inclusive", WavInfo.read(file(riff(*chunks.toTypedArray()))))
        chunks.add(chunk("LIST", byteArrayOf()))
        assertNull("A 65th chunk exceeds the complete-inspection budget", WavInfo.read(file(riff(*chunks.toTypedArray()))))
    }

    @Test fun invalidSynthesisCannotBePublishedAndItsTemporaryFileIsRemoved() {
        val directory = temporary.newFolder("unpublished")
        val cache = SpeechDiskCache(directory) { 16L shl 20 }
        val pending = cache.temporary().apply {
            writeBytes(wave(format(channels = 0, align = 2, byteRate = 16_000)))
        }
        assertNull(cache.put("invalid-engine-output", pending))
        assertFalse(pending.exists())
        assertFalse(File(directory, "invalid-engine-output.wav").exists())
        assertFalse(File(directory, "invalid-engine-output.sha256").exists())
    }

    @Test fun rejectsAnOldInvalidCacheEntryEvenWhenItsDigestMatches() {
        val directory = temporary.newFolder("old-cache")
        val bytes = wave(format(rate = 0, byteRate = 16_000))
        val audio = File(directory, "old-entry.wav").apply { writeBytes(bytes) }
        val digestFile = File(directory, "old-entry.sha256").apply { writeText(digest(bytes)) }
        val cache = SpeechDiskCache(directory) { 16L shl 20 }
        assertNull(cache.get("old-entry"))
        assertFalse("An invalid recording must not be retried on every lookup", audio.exists())
        assertFalse("Its now orphaned digest must be removed too", digestFile.exists())
    }

    @Test fun validExtendedPcmRemainsIdenticalAndReadableAfterCacheRecreation() {
        val directory = temporary.newFolder("valid-cache")
        val payload = ByteArray(48_000) { if (it in 600..47_399) (it % 31).toByte() else 0 }
        val bytes = riff(chunk("JUNK", byteArrayOf(1, 2, 3)),
            chunk("fmt ", format(channels = 2, bits = 24, size = 18)), chunk("data", payload))
        val cache = SpeechDiskCache(directory) { 16L shl 20 }
        val prepared = cache.put("valid-entry", cache.temporary().apply { writeBytes(bytes) })
        assertNotNull(prepared)
        assertEquals(1_000L, prepared!!.durationMs)
        assertArrayEquals(bytes, prepared.file.readBytes())
        val restored = SpeechDiskCache(directory) { 16L shl 20 }.get("valid-entry")
        assertNotNull(restored)
        assertEquals(1_000L, restored!!.durationMs)
        assertArrayEquals("Caching must not trim or rewrite the samples", bytes, restored.file.readBytes())
    }
}
