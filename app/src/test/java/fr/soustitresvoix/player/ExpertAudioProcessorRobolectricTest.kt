package fr.soustitresvoix.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import fr.soustitresvoix.core.AudioDspObservation
import fr.soustitresvoix.core.AudioDspPolicy
import fr.soustitresvoix.core.AudioDspSettings
import fr.soustitresvoix.core.AudioEqBand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the actual Media3 buffer/format implementation. Oracles inspect emitted
 * PCM samples and publications, not the private DSP implementation or acoustics. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExpertAudioProcessorRobolectricTest {
    private fun format(rate: Int = 48_000, channels: Int = 2, encoding: Int = C.ENCODING_PCM_16BIT) =
        AudioProcessor.AudioFormat(rate, channels, encoding)

    private fun configured(settings: AudioDspSettings = AudioDspSettings(), channels: Int = 2): ExpertAudioProcessor =
        ExpertAudioProcessor().apply {
            submit(settings)
            assertEquals(format(channels = channels), configure(format(channels = channels)))
            assertTrue("PCM16 stays active in Media3 even while the controls are disabled", isActive)
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }

    private fun pcm(samples: ShortArray, prefixBytes: Int = 0): ByteBuffer =
        ByteBuffer.allocateDirect(prefixBytes + samples.size * 2 + 3).order(ByteOrder.nativeOrder()).apply {
            position(prefixBytes)
            samples.forEach { putShort(it) }
            limit(position())
            position(prefixBytes)
        }

    private fun output(processor: ExpertAudioProcessor): ShortArray {
        val result = processor.output
        assertTrue(result.isDirect)
        assertEquals(ByteOrder.nativeOrder(), result.order())
        assertEquals(0, result.remaining() % 2)
        return ShortArray(result.remaining() / 2) { result.short }
    }

    private fun process(processor: ExpertAudioProcessor, samples: ShortArray): ShortArray {
        val input = pcm(samples)
        processor.queueInput(input)
        assertFalse("Complete frames must be entirely consumed", input.hasRemaining())
        return output(processor)
    }

    @Test fun disabledBypassPreservesEveryPcm16ValueAndTheReadOnlyInputExactly() {
        val processor = configured(channels = 1)
        val values = ShortArray(65_536) { (it + Short.MIN_VALUE).toShort() }
        val storage = pcm(values, prefixBytes = 8)
        val input = storage.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        val originalLimit = input.limit()
        processor.queueInput(input)
        assertEquals(originalLimit, input.position())
        assertEquals(originalLimit, input.limit())
        assertArrayEquals(values, output(processor))
        assertArrayEquals("The caller owns the input; no in-place DSP writes are allowed", values,
            ShortArray(values.size) { storage.short })
        val state = processor.snapshot
        assertEquals(65_536L, state.observation.processedFrames)
        assertEquals(0L, state.observation.limitedFrames)
        assertEquals(0L, state.observation.clippedSamples)
        assertEquals(1.0, state.observation.outputPeak!!, 0.0)
        assertEquals(0L, state.observation.consumedRevision)
        assertFalse(state.observation.transitioning)
    }

    @Test fun configureRemainsPendingUntilFlushAndDoesNotCorruptTheOldStereoStream() {
        val processor = configured()
        assertArrayEquals(shortArrayOf(100, -200, 300, -400),
            process(processor, shortArrayOf(100, -200, 300, -400)))
        val old = processor.snapshot
        val next = format(96_000, 1)
        assertEquals(next, processor.configure(next))
        assertEquals("A pending format cannot be presented as the format that processed samples", old, processor.snapshot)
        assertArrayEquals(shortArrayOf(500, -600, 700, -800),
            process(processor, shortArrayOf(500, -600, 700, -800)))
        assertEquals(48_000, processor.snapshot.observation.sampleRate)
        assertEquals(2, processor.snapshot.observation.channelCount)
        assertEquals(4L, processor.snapshot.observation.processedFrames)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val flushed = processor.snapshot.observation
        assertEquals(96_000, flushed.sampleRate)
        assertEquals(1, flushed.channelCount)
        assertEquals(0L, flushed.processedFrames)
        assertNull(flushed.consumedRevision)
        assertNull(flushed.outputPeak)
        assertArrayEquals(shortArrayOf(11, -12, 13), process(processor, shortArrayOf(11, -12, 13)))
        assertEquals(3L, processor.snapshot.observation.processedFrames)
    }

    @Test fun unsupportedFormatsAreBypassedByTheRealPipelineAndResetClearsOnlyObservations() {
        val settings = AudioDspSettings(enabled = true, preampDb = -6.0)
        val processor = configured(settings)
        process(processor, shortArrayOf(2000, -1000))
        val revision = processor.snapshot.submittedRevision
        for (unsupported in listOf(format(channels = 6), format(encoding = C.ENCODING_PCM_FLOAT))) {
            val before = processor.snapshot.observation
            val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
            assertEquals(unsupported, pipeline.configure(unsupported))
            assertEquals(before, processor.snapshot.observation)
            pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
            assertFalse(pipeline.isOperational)
            assertFalse(processor.isActive)
            val state = processor.snapshot
            assertEquals(false, state.observation.compatible)
            assertEquals(unsupported.channelCount, state.observation.channelCount)
            assertEquals(0L, state.observation.processedFrames)
            assertNull(state.observation.consumedSettings)
            assertEquals(settings, state.submittedSettings)
        }
        processor.reset()
        assertFalse(processor.isActive)
        assertEquals(AudioDspObservation(), processor.snapshot.observation)
        assertEquals(settings, processor.snapshot.submittedSettings)
        assertEquals(revision, processor.snapshot.submittedRevision)
        processor.configure(format(channels = 1))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(true, processor.snapshot.observation.compatible)
        assertNull(processor.snapshot.observation.consumedRevision)
        process(processor, shortArrayOf(1000))
        assertEquals(revision, processor.snapshot.observation.consumedRevision)
    }

    @Test fun requestsStaySeparateFromConsumedSettingsAndInvalidOrIdenticalSubmissionsHaveNoEffects() {
        val processor = configured()
        val before = processor.snapshot
        val settings = AudioDspSettings(enabled = true, preampDb = -6.0, limiterEnabled = false)
        processor.submit(settings)
        val pending = processor.snapshot
        assertEquals(1L, pending.submittedRevision)
        assertEquals(settings, pending.submittedSettings)
        assertEquals(before.observation, pending.observation)
        assertNull(pending.observation.consumedRevision)
        processor.submit(settings.copy(balance = -0.0))
        assertEquals(pending, processor.snapshot)
        try {
            processor.submit(settings.copy(preampDb = Double.NaN))
            fail("Invalid settings must not be published")
        } catch (_: IllegalArgumentException) { }
        assertEquals(pending, processor.snapshot)
        process(processor, shortArrayOf(2000, -2000))
        val consumed = processor.snapshot
        assertEquals(1L, consumed.observation.consumedRevision)
        assertEquals(settings, consumed.observation.consumedSettings)
        assertTrue(consumed.observation.transitioning)
        assertEquals("An older snapshot must remain immutable", before.observation, pending.observation)
        repeat(3) { assertEquals(consumed, processor.snapshot) }
    }

    @Test fun preampUsesDecibelsAndFinalPcmClippingIsCountedWithoutChangingFrameCount() {
        val attenuated = configured(AudioDspSettings(enabled = true, preampDb = -6.0, limiterEnabled = false))
        val samples = shortArrayOf(16_000, -8000, 6000, -3000)
        val expected = samples.map { (it * 10.0.pow(-6.0 / 20.0)).roundToInt().toShort() }.toShortArray()
        assertArrayEquals(expected, process(attenuated, samples))
        assertEquals(2L, attenuated.snapshot.observation.processedFrames)
        assertEquals(0L, attenuated.snapshot.observation.clippedSamples)
        val boosted = configured(AudioDspSettings(enabled = true, preampDb = 12.0, limiterEnabled = false))
        val result = process(boosted, shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1000, -1000))
        assertEquals(Short.MAX_VALUE, result[0])
        assertEquals(Short.MIN_VALUE, result[1])
        assertEquals((1000 * 10.0.pow(12.0 / 20.0)).roundToInt(), result[2].toInt())
        assertEquals(-result[2].toInt(), result[3].toInt())
        assertEquals(2L, boosted.snapshot.observation.processedFrames)
        assertEquals(2L, boosted.snapshot.observation.clippedSamples)
        assertEquals(0L, boosted.snapshot.observation.limitedFrames)
        assertEquals(1.0, boosted.snapshot.observation.outputPeak!!, 0.0)
        // Values within the nearest-value rounding interval still represent
        // valid PCM samples; a limit requiring saturation must be counted.
        val extremes = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE)
        for ((fractionOfStep, expectedClips) in listOf(0.25 to 0L, 0.75 to 2L)) {
            val edge = configured(AudioDspSettings(enabled = true, limiterEnabled = false,
                preampDb = 20.0 * log10(1.0 + fractionOfStep / Short.MAX_VALUE)))
            assertArrayEquals(extremes, process(edge, extremes))
            assertEquals(expectedClips, edge.snapshot.observation.clippedSamples)
        }
    }

    @Test fun linkedLimiterRetainsStereoRatioAndItsQuantizedOutputRespectsEveryTestedCeiling() {
        for (ceiling in listOf(-12.0, -6.0, -1.0, 0.0)) {
            val processor = configured(AudioDspSettings(enabled = true, preampDb = 12.0,
                limiterEnabled = true, limiterCeilingDb = ceiling))
            val input = ShortArray(80) { if (it % 2 == 0) 30_000 else 15_000 }
            val values = process(processor, input)
            assertEquals(input.size, values.size)
            val limit = 10.0.pow(ceiling / 20.0)
            for (frame in values.indices step 2) {
                assertTrue(abs(values[frame].toInt()) / 32768.0 <= limit)
                assertTrue(abs(2 * values[frame + 1].toInt() - values[frame].toInt()) <= 1)
            }
            val state = processor.snapshot.observation
            assertTrue(state.outputPeak!! <= limit)
            assertEquals(40L, state.processedFrames)
            assertEquals(40L, state.limitedFrames)
            assertEquals("With the limiter enabled no PCM clipping should occur", 0L, state.clippedSamples)
        }
    }

    @Test fun emptyAndIncompleteBuffersPreserveInputAndEosProducesNoExtraFrames() {
        val processor = ExpertAudioProcessor()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        processor.configure(format())
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.submit(AudioDspSettings(preampDb = -3.0))
        val initial = processor.snapshot
        val shortFrame = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        shortFrame.put(byteArrayOf(1, 2, 3)).flip()
        processor.queueInput(shortFrame)
        assertEquals(0, shortFrame.position())
        assertEquals(initial, processor.snapshot)
        val mixed = ByteBuffer.allocateDirect(7).order(ByteOrder.nativeOrder())
        mixed.putShort(123).putShort(-456).put(byteArrayOf(5, 6, 7)).flip()
        processor.queueInput(mixed)
        assertEquals(4, mixed.position())
        assertEquals(7, mixed.limit())
        assertEquals(3, mixed.remaining())
        processor.queueEndOfStream()
        assertFalse(processor.isEnded)
        assertArrayEquals(shortArrayOf(123, -456), output(processor))
        assertTrue(processor.isEnded)
        assertFalse(processor.output.hasRemaining())
        assertEquals(1L, processor.snapshot.observation.processedFrames)
        assertArrayEquals(byteArrayOf(5, 6, 7), ByteArray(3) { mixed.get() })
    }

    @Test fun splittingTheSamePcmStreamIntoDifferentBlocksProducesExactlyTheSameSamples() {
        val settings = AudioDspSettings(enabled = true, preampDb = -3.0, balance = 0.2,
            limiterEnabled = true, band3 = AudioEqBand(true, 1000.0, 8.0, 1.2))
        val samples = ShortArray(12_000) { index ->
            val frame = index / 2
            val phase = if (index % 2 == 0) 0.0 else 0.7
            (10_000 * sin(2 * PI * 1000.0 * frame / 48_000 + phase)).roundToInt().toShort()
        }
        val whole = configured(settings)
        val expected = process(whole, samples)
        val split = configured(settings)
        val actual = ShortArray(samples.size)
        var offset = 0
        val sizes = intArrayOf(1, 7, 13, 1024, 97)
        var block = 0
        while (offset < samples.size) {
            val count = minOf(sizes[block % sizes.size] * 2, samples.size - offset)
            process(split, samples.copyOfRange(offset, offset + count)).copyInto(actual, offset)
            offset += count
            block++
        }
        assertArrayEquals(expected, actual)
        assertEquals(whole.snapshot.observation.processedFrames, split.snapshot.observation.processedFrames)
        assertEquals(whole.snapshot.observation.limitedFrames, split.snapshot.observation.limitedFrames)
        assertEquals(whole.snapshot.observation.clippedSamples, split.snapshot.observation.clippedSamples)
    }

    @Test fun concurrentPublicationNeverExposesTornSettingsOrAnObservationNewerThanItsRequest() {
        val processor = configured()
        val ready = CountDownLatch(1)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val publisher = Thread {
            try {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                repeat(600) { index ->
                    processor.submit(AudioDspSettings(enabled = true,
                        preampDb = if (index % 2 == 0) -12.0 else -6.0,
                        balance = if (index % 2 == 0) -0.5 else 0.5))
                    val snapshot = processor.snapshot
                    assertNull(AudioDspPolicy.validate(snapshot.submittedSettings))
                    snapshot.observation.consumedRevision?.let { assertTrue(it <= snapshot.submittedRevision) }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        publisher.start()
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val block = ShortArray(128) { if (it % 2 == 0) 2000 else -1000 }
            repeat(600) {
                assertEquals(128, process(processor, block).size)
                val snapshot = processor.snapshot
                assertNull(AudioDspPolicy.validate(snapshot.submittedSettings))
                snapshot.observation.consumedSettings?.let { assertNull(AudioDspPolicy.validate(it)) }
                snapshot.observation.consumedRevision?.let { assertTrue(it <= snapshot.submittedRevision) }
            }
        } finally {
            start.countDown()
            publisher.join(5000)
        }
        assertFalse("The publication worker must finish", publisher.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent publication failed", it) }
        assertEquals(38_400L, processor.snapshot.observation.processedFrames)
        assertEquals(0L, processor.snapshot.observation.clippedSamples)
    }
}
