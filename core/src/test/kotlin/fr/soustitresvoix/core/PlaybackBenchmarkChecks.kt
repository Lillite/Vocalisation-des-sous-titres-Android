package fr.soustitresvoix.core

object PlaybackBenchmarkChecks {
    private val identity = PlaybackBenchmark.Identity(
        PlaybackBenchmark.fingerprint(listOf("content://private/example")),
        PlaybackBenchmark.fingerprint(listOf("voice=fr", "rate=1.0")),
        "Appareil d’essai", "Moteur de test 1 / français", "test", 0)
    private val baseline = PlaybackBenchmark.Counters(50_000, 1_000, 10_000, 7, 3, 2)

    fun elapsedWallTimeDoesNotShortenTheVideoExcerpt() {
        val bench = PlaybackBenchmark()
        check(bench.start(120_000, identity, baseline))
        check(!bench.tick(baseline.copy(elapsedRealtimeMs = 230_000, processCpuMs = 46_000,
            videoPositionMs = 70_000, droppedFrames = 10, cacheHits = 11, cacheMisses = 4)))
        val ongoing = requireNotNull(bench.snapshot())
        check(ongoing.active && !ongoing.completed)
        check(ongoing.elapsedMs == 180_000L)
        check(ongoing.processCpuMs == 45_000L && ongoing.cpuPercentOfOneCore == 25.0)
        check(ongoing.droppedFrames == 3L && ongoing.cacheHits == 8L && ongoing.cacheMisses == 2L)
        check(bench.tick(baseline.copy(elapsedRealtimeMs = 300_000, processCpuMs = 50_000,
            videoPositionMs = 130_000, droppedFrames = 12, cacheHits = 15, cacheMisses = 4)))
        val finished = requireNotNull(bench.snapshot())
        check(finished.completed && !finished.active)
        check(finished.finalPositionMs - finished.initialPositionMs == finished.requestedMs)
        check(finished.elapsedMs == 250_000L)
        check(!bench.tick(baseline.copy(elapsedRealtimeMs = 400_000, videoPositionMs = 200_000)))
        check(bench.snapshot() == finished)
    }
    fun newObservationsAreIsolatedAndBounded() {
        val bench = PlaybackBenchmark(3)
        val source = LatencyWindow(8)
        source.setObserver { bench.record(PlaybackBenchmark.Metric.SYNTHESIS, it) }
        source.add(999)
        bench.start(120_000, identity, baseline)
        listOf(10L, 20L, 30L, 40L).forEach(source::add)
        val current = requireNotNull(bench.snapshot())
        val samples = current.latencies.getValue(PlaybackBenchmark.Metric.SYNTHESIS)
        check(samples.observed == 4L && samples.retained == 3)
        check(samples.p50 == 30L && samples.p95 == 40L && samples.p99 == 40L && samples.maximum == 40L)
        check(current.latencies.getValue(PlaybackBenchmark.Metric.FIRST_AUDIO).observed == 0L)
        check(samples.summary().contains("3 dernières retenues"))
        check(source.summary().contains("n=5")) // A benchmark never clears session history.
        bench.finish(baseline.copy(elapsedRealtimeMs = 51_000), "Pause demandée")
        source.add(777)
        check(bench.snapshot()!!.latencies.getValue(PlaybackBenchmark.Metric.SYNTHESIS) == samples)
        bench.start(120_000, identity, baseline.copy(elapsedRealtimeMs = 60_000))
        source.add(55)
        check(bench.snapshot()!!.latencies.getValue(PlaybackBenchmark.Metric.SYNTHESIS).observed == 1L)
        source.setObserver(null); source.add(66)
        check(bench.snapshot()!!.latencies.getValue(PlaybackBenchmark.Metric.SYNTHESIS).observed == 1L)
    }
    fun explicitInterruptionNeverBecomesAnAccidentalSuccess() {
        val bench = PlaybackBenchmark()
        bench.start(120_000, identity, baseline)
        check(!bench.start(1_800_000, identity, baseline))
        bench.finish(baseline.copy(elapsedRealtimeMs = 300_000, videoPositionMs = 140_000), "Déplacement temporel demandé")
        val end = bench.snapshot()!!
        check(!end.active && !end.completed)
        check(end.reason == "Déplacement temporel demandé")
        bench.finish(baseline, "Ancien callback")
        check(bench.snapshot() == end)
        check(bench.start(1_800_000, identity, baseline.copy(elapsedRealtimeMs = 400_000)))
        check(bench.snapshot()!!.requestedMs == 1_800_000L)
        check(bench.report().contains("Déplacement temporel demandé"))
    }
    fun counterResetInvalidatesComparison() {
        val bench = PlaybackBenchmark()
        bench.start(120_000, identity, baseline)
        check(bench.tick(baseline.copy(elapsedRealtimeMs = 51_000, cacheHits = 0)))
        val result = bench.snapshot()!!
        check(!result.active && !result.completed)
        check(result.reason.contains("Compteurs réinitialisés"))
        check(result.cacheHits == 0L)
    }
    fun fingerprintsPreserveFieldBoundariesAndHideRawMediaIdentifiers() {
        check(PlaybackBenchmark.fingerprint(listOf("a", "bc")) != PlaybackBenchmark.fingerprint(listOf("ab", "c")))
        check(PlaybackBenchmark.fingerprint(listOf("a|b", "c")) != PlaybackBenchmark.fingerprint(listOf("a", "b|c")))
        val first = PlaybackBenchmark().apply { start(120_000, identity, baseline) }.snapshot()!!
        val same = PlaybackBenchmark().apply { start(120_000, identity, baseline.copy(elapsedRealtimeMs = 100_000)) }.snapshot()!!
        check(first.comparableKey == same.comparableKey)
        val moved = PlaybackBenchmark().apply { start(120_000, identity, baseline.copy(videoPositionMs = 10_001)) }.snapshot()!!
        check(first.comparableKey != moved.comparableKey)
        val changed = PlaybackBenchmark().apply { start(120_000, identity.copy(settingsFingerprint = "other-settings"), baseline) }.snapshot()!!
        check(first.comparableKey != changed.comparableKey)
        check(!first.report().contains("content://private/example"))
        check(first.report().contains("moteur vocal externe non inclus"))
    }
    fun historyIsBoundedAndCompletedSnapshotsAreImmutable() {
        val bench = PlaybackBenchmark()
        var original: PlaybackBenchmark.Snapshot? = null
        repeat(6) { i ->
            val start = baseline.copy(elapsedRealtimeMs = 100_000L * i)
            bench.start(1000, identity, start)
            bench.record(PlaybackBenchmark.Metric.CACHE, (i + 1).toLong())
            bench.tick(start.copy(elapsedRealtimeMs = start.elapsedRealtimeMs + 2000, videoPositionMs = start.videoPositionMs + 1000))
            if (i == 0) original = bench.snapshot()
        }
        check(Regex("Essai \\d+ —").findAll(bench.report()).count() == 4)
        check(original!!.latencies.getValue(PlaybackBenchmark.Metric.CACHE).p50 == 1L)
        check(!bench.report().contains("Essai 1 —"))
    }
    fun observerMayInspectItsLatencyWindow() {
        val window = LatencyWindow()
        var summary = ""
        window.setObserver { summary = window.summary() }
        window.add(25)
        check(summary.contains("p50=25 ms"))
        window.add(-1)
        check(window.summary().contains("n=1"))
    }
    fun runAll() {
        elapsedWallTimeDoesNotShortenTheVideoExcerpt()
        newObservationsAreIsolatedAndBounded()
        explicitInterruptionNeverBecomesAnAccidentalSuccess()
        counterResetInvalidatesComparison()
        fingerprintsPreserveFieldBoundariesAndHideRawMediaIdentifiers()
        historyIsBoundedAndCompletedSnapshotsAreImmutable()
        observerMayInspectItsLatencyWindow()
        println("PlaybackBenchmarkChecks: 7 checks passed")
    }
    @JvmStatic fun main(args: Array<String>) = runAll()
}
