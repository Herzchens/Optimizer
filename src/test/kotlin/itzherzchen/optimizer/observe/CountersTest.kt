package itzherzchen.optimizer.observe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountersTest {

    @Test
    fun `initial counters are all zero`() {
        val counters = Counters()
        val snap = counters.snapshot()

        assertEquals(0L, snap.cuboidActions)
        assertEquals(0L, snap.syntheticBreaks)
        assertEquals(0L, snap.stackedFastRejects)
        assertEquals(0L, snap.stackedPassthroughs)
        assertEquals(0L, snap.monitorFastPaths)
        assertEquals(0L, snap.monitorAnchors)
        assertEquals(0L, snap.signPassthroughs)
        assertEquals(0L, snap.customPassthroughs)
        assertEquals(0L, snap.spawnerPassthroughs)
        assertEquals(0L, snap.dirtyTasksAvoided)
        assertEquals(0L, snap.validationFailures)
        assertEquals(0L, snap.circuitTrips)
        assertEquals(0L, snap.externalCuboidCancellations)
    }

    @Test
    fun `concurrent increments accumulate accurately without loss`() {
        val counters = Counters()
        val threads = 10
        val incrementsPerThread = 5_000
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)

        for (t in 0 until threads) {
            executor.submit {
                latch.await()
                for (i in 0 until incrementsPerThread) {
                    counters.cuboidActions.increment()
                    counters.syntheticBreaks.add(10)
                    counters.stackedFastRejects.increment()
                    counters.dirtyTasksAvoided.add(2)
                }
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        val snap = counters.snapshot()
        val expectedActions = (threads * incrementsPerThread).toLong()
        assertEquals(expectedActions, snap.cuboidActions)
        assertEquals(expectedActions * 10, snap.syntheticBreaks)
        assertEquals(expectedActions, snap.stackedFastRejects)
        assertEquals(expectedActions * 2, snap.dirtyTasksAvoided)
    }

    @Test
    fun `reset clears all counters to zero`() {
        val counters = Counters()
        counters.cuboidActions.add(42)
        counters.circuitTrips.increment()
        counters.validationFailures.add(5)

        assertEquals(42L, counters.snapshot().cuboidActions)
        assertEquals(1L, counters.snapshot().circuitTrips)
        assertEquals(5L, counters.snapshot().validationFailures)

        counters.reset()

        val snap = counters.snapshot()
        assertEquals(0L, snap.cuboidActions)
        assertEquals(0L, snap.circuitTrips)
        assertEquals(0L, snap.validationFailures)
    }
}
