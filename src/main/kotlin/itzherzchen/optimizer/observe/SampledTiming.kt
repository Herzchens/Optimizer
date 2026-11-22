package itzherzchen.optimizer.observe

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * Low-overhead sampled action timer.
 * Samples 1 out of [sampleRate] actions to measure execution latency
 * without polluting the per-block hot loop with System.nanoTime() invocations.
 */
class SampledTiming(
    val sampleRate: Int = 64
) {
    private val invocationCount = AtomicLong(0)
    private val sampledCount = LongAdder()
    private val totalNanos = LongAdder()
    private val minNanos = AtomicLong(Long.MAX_VALUE)
    private val maxNanos = AtomicLong(0)

    /**
     * Determines if the current action should be sampled.
     */
    fun shouldSample(): Boolean {
        if (sampleRate <= 1) return true
        return (invocationCount.incrementAndGet() % sampleRate) == 0L
    }

    /**
     * Records a measured duration in nanoseconds.
     */
    fun record(durationNanos: Long) {
        sampledCount.increment()
        totalNanos.add(durationNanos)

        while (true) {
            val curMin = minNanos.get()
            if (durationNanos >= curMin || minNanos.compareAndSet(curMin, durationNanos)) {
                break
            }
        }

        while (true) {
            val curMax = maxNanos.get()
            if (durationNanos <= curMax || maxNanos.compareAndSet(curMax, durationNanos)) {
                break
            }
        }
    }

    data class Stats(
        val count: Long,
        val minMillis: Double,
        val maxMillis: Double,
        val avgMillis: Double
    )

    fun getStats(): Stats {
        val count = sampledCount.sum()
        if (count == 0L) {
            return Stats(0, 0.0, 0.0, 0.0)
        }
        val total = totalNanos.sum()
        val min = minNanos.get()
        val max = maxNanos.get()

        return Stats(
            count = count,
            minMillis = if (min == Long.MAX_VALUE) 0.0 else min / 1_000_000.0,
            maxMillis = max / 1_000_000.0,
            avgMillis = (total.toDouble() / count) / 1_000_000.0
        )
    }

    fun reset() {
        invocationCount.set(0)
        sampledCount.reset()
        totalNanos.reset()
        minNanos.set(Long.MAX_VALUE)
        maxNanos.set(0)
    }
}
