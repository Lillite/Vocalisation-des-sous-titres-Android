package fr.soustitresvoix.core

import org.junit.Test

/** Three reproducible passes; these test cases run sequentially. */
class CoreTest {
    @Test fun regressionSuiteSeed42() { CoreChecks.runAll(42) }
    @Test fun regressionSuiteSeed2026() { CoreChecks.runAll(2026) }
    @Test fun regressionSuiteSeed7351() { CoreChecks.runAll(7351) }
}
