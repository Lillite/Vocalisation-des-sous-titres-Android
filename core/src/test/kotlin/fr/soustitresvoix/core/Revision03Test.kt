package fr.soustitresvoix.core
import org.junit.Test
class Revision03Test {
    @Test fun seed42() = Revision03Checks.runAll(42)
    @Test fun seed2026() = Revision03Checks.runAll(2026)
    @Test fun seed7351() = Revision03Checks.runAll(7351)
}
