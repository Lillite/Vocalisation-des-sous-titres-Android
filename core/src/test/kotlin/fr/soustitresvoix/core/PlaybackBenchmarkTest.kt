package fr.soustitresvoix.core

import org.junit.Test

class PlaybackBenchmarkTest {
    @Test fun measuresTheSameVideoExcerpt() = PlaybackBenchmarkChecks.elapsedWallTimeDoesNotShortenTheVideoExcerpt()
    @Test fun isolatesWindowObservations() = PlaybackBenchmarkChecks.newObservationsAreIsolatedAndBounded()
    @Test fun preservesInterruptionReasons() = PlaybackBenchmarkChecks.explicitInterruptionNeverBecomesAnAccidentalSuccess()
    @Test fun detectsCounterResets() = PlaybackBenchmarkChecks.counterResetInvalidatesComparison()
    @Test fun fingerprintsComparableInputs() = PlaybackBenchmarkChecks.fingerprintsPreserveFieldBoundariesAndHideRawMediaIdentifiers()
    @Test fun keepsBoundedImmutableHistory() = PlaybackBenchmarkChecks.historyIsBoundedAndCompletedSnapshotsAreImmutable()
    @Test fun notifiesMeasurements() = PlaybackBenchmarkChecks.observerMayInspectItsLatencyWindow()
}
