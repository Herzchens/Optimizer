package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.superior.StackedFastReject
import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.RegisteredListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * EventExecutor wrapper for RegisteredListener instances in WildTools' claimingPluginsBreakMethods.
 *
 * Replaces only SuperiorSkyblock2 HIGHEST (stack) and MONITOR (accounting) callbacks.
 *
 * Invariants:
 * - Thread Safety: World mutations and synthetic block breaking are strictly main-thread.
 *   Off-thread calls trip circuit breaker and fall back to original passthrough.
 * - Fail-Closed: If any error, state inconsistency, or unexpected event type occurs,
 *   safely delegate directly to the original RegisteredListener.
 * - Transparent Observability: In OBSERVE or passthrough mode, accurately records synthetic
 *   break events on the active ActionContext and increments metrics without mutating game state.
 * - Zero Allocation on Hot Path: Avoids lambdas, collections, or allocations per synthetic break.
 */
class SyntheticBreakWrapper(
    val original: RegisteredListener,
    val type: SyntheticCallbackType,
    private val actionContextStack: ActionContextStack,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    private val logger: Logger? = null,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            false
        }
    },
    var stackedFastReject: StackedFastReject? = null,
    var blockAccountingFastPath: itzherzchen.optimizer.superior.BlockAccountingFastPath? = null
) : EventExecutor {

    @Volatile
    var passthroughOnly: Boolean = false

    override fun execute(listener: Listener, event: Event) {
        if (event !is BlockBreakEvent) {
            // Unexpected event type on break chain
            callOriginal(event)
            return
        }

        // Invariant: Main-thread execution only (fail-closed if unable to verify)
        if (!primaryThreadChecker()) {
            counters.circuitTrips.increment()
            logger?.severe(
                "[Optimizer] Off-main-thread synthetic break invocation detected on thread '${Thread.currentThread().name}' in wrapper ($type)! Disarming."
            )
            circuitBreaker.trip(
                ModuleType.GLOBAL,
                "Off-main-thread synthetic break in SyntheticBreakWrapper ($type) on thread '${Thread.currentThread().name}'"
            )
            callOriginal(event)
            return
        }

        // Wrapper pre-delegation bookkeeping
        try {
            recordObservation(event)
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            logger?.log(
                Level.SEVERE,
                "[Optimizer] Unexpected error in SyntheticBreakWrapper ($type) before delegation. Tripping circuit breaker and falling back to original.",
                t
            )
            circuitBreaker.trip(
                ModuleType.GLOBAL,
                "Unexpected exception in SyntheticBreakWrapper ($type): ${t.message}",
                t
            )
            callOriginal(event)
            return
        }

        val currentMode = modeController.mode
        val isGlobalTripped = circuitBreaker.isTripped(ModuleType.GLOBAL)

        if (passthroughOnly || currentMode != OperatingMode.ACTIVE || isGlobalTripped) {
            callOriginal(event)
            return
        }

        if (type == SyntheticCallbackType.STACKED_BLOCKS) {
            val fastReject = stackedFastReject
            if (fastReject != null) {
                fastReject.handle(event, original)
                return
            }
        } else if (type == SyntheticCallbackType.ACCOUNTING) {
            val accounting = blockAccountingFastPath
            if (accounting != null) {
                accounting.handle(event, original)
                return
            }
        }

        // Delegate to original callback exactly once.
        // Invariant: If original.callEvent throws, DO NOT catch and retry! Propagate original's exception.
        callOriginal(event)
    }

    private fun recordObservation(event: BlockBreakEvent) {
        val context = actionContextStack.current()
        if (context != null) {
            context.recordSyntheticBreak(event)
            // Increment syntheticBreaks counter once per synthetic block break (at the first callback, STACKED_BLOCKS)
            if (type == SyntheticCallbackType.STACKED_BLOCKS) {
                counters.syntheticBreaks.increment()
            }
        }
    }

    private fun callOriginal(event: Event) {
        original.callEvent(event)
    }
}
