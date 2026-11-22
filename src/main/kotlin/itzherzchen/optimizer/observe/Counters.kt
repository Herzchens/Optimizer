package itzherzchen.optimizer.observe

import java.util.concurrent.atomic.LongAdder

/**
 * High-performance, contention-free observability metrics.
 * Uses LongAdder to eliminate lock and atomic contention on multi-core hot paths.
 */
class Counters {
    val cuboidActions = LongAdder()
    val syntheticBreaks = LongAdder()
    val stackedFastRejects = LongAdder()
    val stackedPassthroughs = LongAdder()
    val monitorFastPaths = LongAdder()
    val monitorAnchors = LongAdder()
    val signPassthroughs = LongAdder()
    val customPassthroughs = LongAdder()
    val spawnerPassthroughs = LongAdder()
    val dirtyTasksAvoided = LongAdder()
    val validationFailures = LongAdder()
    val circuitTrips = LongAdder()
    val externalCuboidCancellations = LongAdder()

    data class Snapshot(
        val cuboidActions: Long,
        val syntheticBreaks: Long,
        val stackedFastRejects: Long,
        val stackedPassthroughs: Long,
        val monitorFastPaths: Long,
        val monitorAnchors: Long,
        val signPassthroughs: Long,
        val customPassthroughs: Long,
        val spawnerPassthroughs: Long,
        val dirtyTasksAvoided: Long,
        val validationFailures: Long,
        val circuitTrips: Long,
        val externalCuboidCancellations: Long
    )

    fun snapshot(): Snapshot = Snapshot(
        cuboidActions = cuboidActions.sum(),
        syntheticBreaks = syntheticBreaks.sum(),
        stackedFastRejects = stackedFastRejects.sum(),
        stackedPassthroughs = stackedPassthroughs.sum(),
        monitorFastPaths = monitorFastPaths.sum(),
        monitorAnchors = monitorAnchors.sum(),
        signPassthroughs = signPassthroughs.sum(),
        customPassthroughs = customPassthroughs.sum(),
        spawnerPassthroughs = spawnerPassthroughs.sum(),
        dirtyTasksAvoided = dirtyTasksAvoided.sum(),
        validationFailures = validationFailures.sum(),
        circuitTrips = circuitTrips.sum(),
        externalCuboidCancellations = externalCuboidCancellations.sum()
    )

    fun reset() {
        cuboidActions.reset()
        syntheticBreaks.reset()
        stackedFastRejects.reset()
        stackedPassthroughs.reset()
        monitorFastPaths.reset()
        monitorAnchors.reset()
        signPassthroughs.reset()
        customPassthroughs.reset()
        spawnerPassthroughs.reset()
        dirtyTasksAvoided.reset()
        validationFailures.reset()
        circuitTrips.reset()
        externalCuboidCancellations.reset()
    }
}
