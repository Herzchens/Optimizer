package itzherzchen.optimizer.observe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampledTimingTest {

    @Test
    fun `sample rate 1 samples every invocation`() {
        val timing = SampledTiming(sampleRate = 1)
        for (i in 0 until 10) {
            assertTrue(timing.shouldSample())
        }
    }

    @Test
    fun `sample rate 4 samples exactly 1 out of 4 invocations`() {
        val timing = SampledTiming(sampleRate = 4)
        var sampled = 0
        val total = 100

        for (i in 0 until total) {
            if (timing.shouldSample()) {
                sampled++
            }
        }

        assertEquals(25, sampled)
    }

    @Test
    fun `statistics calculation min max avg`() {
        val timing = SampledTiming(sampleRate = 1)
        // 1ms, 2ms, 6ms in nanoseconds
        timing.record(1_000_000L)
        timing.record(2_000_000L)
        timing.record(6_000_000L)

        val stats = timing.getStats()
        assertEquals(3L, stats.count)
        assertEquals(1.0, stats.minMillis, 0.001)
        assertEquals(6.0, stats.maxMillis, 0.001)
        assertEquals(3.0, stats.avgMillis, 0.001)
    }

    @Test
    fun `empty stats returns zeros`() {
        val timing = SampledTiming(sampleRate = 64)
        val stats = timing.getStats()
        assertEquals(0L, stats.count)
        assertEquals(0.0, stats.minMillis)
        assertEquals(0.0, stats.maxMillis)
        assertEquals(0.0, stats.avgMillis)
    }

    @Test
    fun `reset clears all recorded samples`() {
        val timing = SampledTiming(sampleRate = 1)
        timing.record(5_000_000L)
        assertEquals(1L, timing.getStats().count)

        timing.reset()
        assertEquals(0L, timing.getStats().count)
    }
}
