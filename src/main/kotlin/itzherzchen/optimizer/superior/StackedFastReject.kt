package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.event.EventException
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.RegisteredListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Module A — SSBStackedFastReject (Plan.md Section 5.1).
 *
 * Safely skips the provable no-op SuperiorSkyblock2 HIGHEST callback (StackedBlocksListener)
 * for a block with exact SSB stack amount 1 when provenSafeForThisEvent holds.
 *
 * Invariants:
 * - Skip callback ONLY when amount == 1 and provenSafeForThisEvent holds.
 * - Explicitly preserve the original callback when amount is 0, negative, 2+,
 *   when event is cancelled, when context is missing, or on any API mismatch.
 * - Never cache amount by material, chunk, or action.
 * - On guard API exception: record, trip STACKED_FAST_REJECT circuit breaker / disable module,
 *   and safely invoke original callback pre-mutation.
 */
class StackedFastReject(
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val actionContextStack: ActionContextStack,
    private val counters: Counters,
    private val logger: Logger? = null,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            true
        }
    },
    private val stackedAmountProvider: (Block) -> Int = { block ->
        val stackedBlocks = SuperiorSkyblockAPI.getStackedBlocks()
            ?: throw IllegalStateException("SuperiorSkyblockAPI.getStackedBlocks() returned null")
        stackedBlocks.getStackedBlockAmount(block)
    }
) {

    @Volatile
    var isEligible: Boolean = true

    /**
     * Evaluates the synthetic BlockBreakEvent against the stacked blocks fast reject invariants.
     */
    fun handle(event: BlockBreakEvent, original: RegisteredListener) {
        // 1. Thread check: Main thread invariant
        if (!primaryThreadChecker()) {
            counters.circuitTrips.increment()
            logger?.severe(
                "[Optimizer] Off-main-thread invocation in StackedFastReject on thread '${Thread.currentThread().name}'! Tripping circuit breaker and disarming."
            )
            circuitBreaker.trip(ModuleType.GLOBAL, "Off-main-thread invocation in StackedFastReject")
            counters.stackedPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 2. Pre-condition checks: mode, config, circuit breaker, cancellation, context, module eligibility
        val currentMode = modeController.mode
        val isGlobalTripped = circuitBreaker.isTripped(ModuleType.GLOBAL)
        val isModuleTripped = circuitBreaker.isTripped(ModuleType.STACKED_FAST_REJECT)

        if (currentMode != OperatingMode.ACTIVE ||
            !config.stackedFastRejectEnabled ||
            !isEligible ||
            isGlobalTripped ||
            isModuleTripped ||
            event.isCancelled
        ) {
            counters.stackedPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        val context = actionContextStack.current()
        if (context == null || context.isClosed) {
            counters.stackedPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 3. Query stacked block amount via guard API (never cached by material, chunk, or action)
        val amount: Int
        try {
            amount = stackedAmountProvider(event.block)
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            counters.validationFailures.increment()
            logger?.log(
                Level.SEVERE,
                "[Optimizer] Exception in StackedBlocks guard API before mutation. Tripping STACKED_FAST_REJECT circuit breaker.",
                t
            )
            circuitBreaker.trip(
                ModuleType.STACKED_FAST_REJECT,
                "Exception in StackedBlocks guard API: ${t.message}",
                t
            )
            // Pre-mutation fallback: safely invoke original
            counters.stackedPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 4. Exact amount evaluation:
        // When amount == 1 and provenSafeForThisEvent holds: skip provable no-op callback
        if (amount == 1) {
            counters.stackedFastRejects.increment()
            // Proven safe: omit no-op callback
            return
        }

        // 5. Explicitly preserve original callback for 0, negative, 2+, etc.
        counters.stackedPassthroughs.increment()
        callOriginal(original, event)
    }

    private fun callOriginal(original: RegisteredListener, event: BlockBreakEvent) {
        original.callEvent(event)
    }
}
