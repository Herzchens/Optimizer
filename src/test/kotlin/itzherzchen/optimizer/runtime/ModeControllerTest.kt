package itzherzchen.optimizer.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModeControllerTest {

    @Test
    fun `default initial mode is OBSERVE`() {
        val controller = ModeController()
        assertEquals(OperatingMode.OBSERVE, controller.mode)
        assertFalse(controller.mode.allowsFastPath)
        assertFalse(controller.mode.isTerminal)
        assertNull(controller.reason)
    }

    @Test
    fun `transition from OBSERVE to VALIDATED succeeds`() {
        val controller = ModeController()
        val success = controller.transitionTo(OperatingMode.VALIDATED, "Tests passed")
        assertTrue(success)
        assertEquals(OperatingMode.VALIDATED, controller.mode)
        assertFalse(controller.mode.allowsFastPath)
    }

    @Test
    fun `transition to ACTIVE succeeds during boot phase`() {
        val controller = ModeController()
        val success = controller.transitionTo(OperatingMode.ACTIVE, "Boot config allowed")
        assertTrue(success)
        assertEquals(OperatingMode.ACTIVE, controller.mode)
        assertTrue(controller.mode.allowsFastPath)
    }

    @Test
    fun `transition to ACTIVE is rejected after boot phase completes`() {
        val controller = ModeController()
        controller.markBootComplete()

        val success = controller.transitionTo(OperatingMode.ACTIVE, "Hot rearm attempt")
        assertFalse(success)
        assertEquals(OperatingMode.OBSERVE, controller.mode)
    }

    @Test
    fun `emergency disarm is terminal and locks out subsequent transitions`() {
        val controller = ModeController()
        val disarmed = controller.disarm("Critical error occurred", "TEST_OPERATOR")
        assertTrue(disarmed)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, controller.mode)
        assertTrue(controller.mode.isTerminal)
        assertFalse(controller.mode.allowsFastPath)
        assertTrue(controller.reason!!.contains("Critical error occurred"))

        // Attempting to transition to VALIDATED or ACTIVE must fail
        assertFalse(controller.transitionTo(OperatingMode.VALIDATED, "Try recover"))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, controller.mode)

        assertFalse(controller.transitionTo(OperatingMode.ACTIVE, "Try recover active"))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, controller.mode)

        assertFalse(controller.transitionTo(OperatingMode.OBSERVE, "Try recover observe"))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, controller.mode)
    }

    @Test
    fun `concurrent transitions maintain state consistency`() {
        val controller = ModeController()
        val threads = 16
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        val disarmSuccessCount = AtomicInteger(0)

        for (i in 0 until threads) {
            executor.submit {
                latch.await()
                if (i % 2 == 0) {
                    controller.transitionTo(OperatingMode.VALIDATED, "Thread $i")
                } else {
                    if (controller.disarm("Thread $i disarm", "T$i")) {
                        disarmSuccessCount.incrementAndGet()
                    }
                }
            }
        }

        latch.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        // Once disarmed, must be terminal
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, controller.mode)
        assertTrue(controller.mode.isTerminal)
    }
}
