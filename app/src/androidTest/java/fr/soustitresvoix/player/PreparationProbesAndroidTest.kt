package fr.soustitresvoix.player

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android memory APIs, extractor, codec and output buffers. Only the
 * repository's synthetic demonstration and a deliberately invalid test file
 * are opened. No activity, speech service or external model is required. */
@RunWith(AndroidJUnit4::class)
class PreparationProbesAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun platformSnapshotReportsRealMemoryWithoutClaimingNeuralAcceleration() {
        val snapshot = PreparationDeviceProbe.read(context)
        val totalRam = requireNotNull(snapshot.totalRamBytes)
        val availableRam = requireNotNull(snapshot.availableRamBytes)
        assertTrue(totalRam > 0)
        assertTrue(availableRam > 0)
        assertTrue(availableRam <= totalRam)
        assertTrue(requireNotNull(snapshot.processPssBytes) > 0)
        assertTrue(requireNotNull(snapshot.storageAvailableBytes) >= 0)
        assertTrue(snapshot.processorCount > 0)
        assertTrue(snapshot.codecs.any { it.mimeTypes.any { type -> type.startsWith("video/") } })
        assertTrue(snapshot.render().contains("aucune exécution déduite de cet inventaire"))
        assertFalse(snapshot.render().contains(context.filesDir.absolutePath))
    }

    @Test fun actualSyntheticVideoProducesReadablePixelsAndSourcePresentationTimes() {
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.demonstration}")
        val result = PreparationVideoProbe.run(context, uri)
        assertEquals(result.render(), PreparationVideoStatus.SUCCESS, result.status)
        assertEquals(result.render(), PreparationVideoProbe.MAX_FRAMES, result.decodedFrames)
        assertEquals(result.decodedFrames, result.presentationTimesUs.size)
        assertTrue(result.render(), result.accessibleFrames > 0)
        assertTrue(result.render(), result.presentationTimesUs.all { it >= 0 })
        assertTrue(result.render(), result.presentationTimesUs.zipWithNext().any { (a, b) -> b > a })
        assertEquals("video/avc", result.mimeType)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
        assertTrue(result.render(), !result.decoderName.isNullOrBlank())
        assertFalse(result.render().contains(uri.toString()))
        // A second independently owned decoder proves that the first released
        // its extractor/codec; this is not a sustained throughput benchmark.
        val second = PreparationVideoProbe.run(context, uri)
        assertEquals(second.render(), PreparationVideoStatus.SUCCESS, second.status)
        assertEquals(PreparationVideoProbe.MAX_FRAMES, second.decodedFrames)
    }

    @Test fun cancellationBeforeOpenDoesNotReadEvenAnInvalidSource() {
        val result = PreparationVideoProbe.run(context, Uri.parse("file:///not-a-real-video/private-name.avi")) { true }
        assertEquals(PreparationVideoStatus.CANCELLED, result.status)
        assertEquals(0, result.decodedFrames)
        assertNull(result.decoderName)
        assertTrue(result.presentationTimesUs.isEmpty())
        assertFalse(result.render().contains("private-name"))
    }

    @Test fun networkUriIsRejectedBeforeExtractorCreation() {
        val result = PreparationVideoProbe.run(context, Uri.parse("https://example.invalid/private-video.avi"))
        assertEquals(PreparationVideoStatus.UNSUPPORTED, result.status)
        assertEquals(0, result.decodedFrames)
        assertNull(result.decoderName)
        assertFalse(result.render().contains("example.invalid"))
    }

    @Test fun malformedLocalFileFailsPerceptiblyWithoutDisclosingItsName() {
        val file = File.createTempFile("diagnostic-private-invalid-", ".avi", context.cacheDir)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val result = PreparationVideoProbe.run(context, Uri.fromFile(file))
            assertTrue(result.render(), result.status in setOf(PreparationVideoStatus.FAILED, PreparationVideoStatus.UNSUPPORTED))
            assertEquals(0, result.decodedFrames)
            assertTrue(result.reason.isNotBlank())
            assertFalse(result.render().contains(file.name))
            assertFalse(result.render().contains(file.absolutePath))
        } finally { file.delete() }
    }
}
