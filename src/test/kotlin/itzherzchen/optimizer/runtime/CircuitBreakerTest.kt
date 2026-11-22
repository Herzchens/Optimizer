package itzherzchen.optimizer.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CircuitBreakerTest {

    @Test
    fun `initial circuit breaker state is not tripped`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)

        for (module in ModuleType.values()) {
            assertFalse(breaker.isTripped(module))
            assertNull(breaker.getTripRecord(module))
        }
    }

    @Test
    fun `tripping module isolates failure to that module`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)

        val firstTrip = breaker.trip(ModuleType.STACKED_FAST_REJECT, "Null pointer in stack check")
        assertTrue(firstTrip)
        assertTrue(breaker.isTripped(ModuleType.STACKED_FAST_REJECT))

        // Other module must not be tripped
        assertFalse(breaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))
        assertFalse(breaker.isTripped(ModuleType.GLOBAL))

        // Operating mode should remain unchanged for localized trip
        assertEquals(OperatingMode.OBSERVE, modeController.mode)

        val record = breaker.getTripRecord(ModuleType.STACKED_FAST_REJECT)
        assertNotNull(record)
        assertEquals("Null pointer in stack check", record!!.reason)
        assertEquals(ModuleType.STACKED_FAST_REJECT, record.module)

        // Second trip for same module returns false (idempotent)
        assertFalse(breaker.trip(ModuleType.STACKED_FAST_REJECT, "Duplicate trip"))
    }

    @Test
    fun `tripping GLOBAL module trips all modules and disarms controller`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)

        breaker.trip(ModuleType.GLOBAL, "Severe invariant violation")

        assertTrue(breaker.isTripped(ModuleType.GLOBAL))
        assertTrue(breaker.isTripped(ModuleType.STACKED_FAST_REJECT))
        assertTrue(breaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))

        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(modeController.mode.isTerminal)
    }

    @Test
    fun `controller disarmed externally causes all modules to report tripped`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)

        modeController.disarm("Operator disarm", "ADMIN")

        for (module in ModuleType.values()) {
            assertTrue(breaker.isTripped(module))
        }
    }
}
