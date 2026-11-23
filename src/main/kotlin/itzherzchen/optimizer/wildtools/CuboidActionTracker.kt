package itzherzchen.optimizer.wildtools

import com.bgsoftware.wildtools.api.WildToolsAPI
import com.bgsoftware.wildtools.api.events.CuboidWandUseEvent
import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import com.bgsoftware.wildtools.tools.ToolBreaksTracker
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.SampledTiming
import itzherzchen.optimizer.runtime.ActionContext
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Listener tracking the lifecycle of root Cuboid Wand BlockBreakEvents on Bukkit's global bus.
 *
 * Implements Plan.md Section 4.3 and Section 7:
 * - Listens at LOWEST to identify candidate Cuboid actions before third-party listeners mutate or cancel.
 * - Pushes an ActionContext onto ActionContextStack.
 * - Filters out synthetic events triggered inside an ongoing WildTools break (using ToolBreaksTracker).
 * - Samples action execution latency without polluting hot path per-block.
 * - Listens at MONITOR to pop and close the action, recording verified actions in counters and timing.
 * - Detects external cancellations on CuboidWandUseEvent.
 */
class CuboidActionTracker(
    private val actionContextStack: ActionContextStack,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    private val timing: SampledTiming? = null,
    private val logger: Logger? = null,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            false
        }
    },
    private val cuboidToolResolver: (ItemStack) -> CuboidTool? = { item ->
        try {
            WildToolsAPI.getCuboidTool(item)
        } catch (_: Throwable) {
            null
        }
    },
    private val toolBreaksTrackerCheck: (Player) -> Boolean? = { player ->
        try {
            ToolBreaksTracker.containsPlayer(player)
        } catch (_: Throwable) {
            null
        }
    },
    var integrityChecker: (() -> Boolean)? = null,
    var blockAccountingFastPath: itzherzchen.optimizer.superior.BlockAccountingFastPath? = null,
    private val riskScanner: itzherzchen.optimizer.observe.TransactionRiskScanner = itzherzchen.optimizer.observe.TransactionRiskScanner(logger = logger)
) : Listener {

    private val actionIdSequence = AtomicLong(0)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onRootBlockBreakLowest(event: BlockBreakEvent) {
        if (!primaryThreadChecker()) {
            if (modeController.mode == OperatingMode.ACTIVE) {
                counters.circuitTrips.increment()
                logger?.severe(
                    "[Optimizer] Off-main-thread root Cuboid action in ACTIVE mode on thread '${Thread.currentThread().name}'! Tripping circuit breaker and disarming."
                )
                circuitBreaker.trip(
                    itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                    "Off-main-thread root Cuboid action on thread '${Thread.currentThread().name}'"
                )
            }
            return
        }

        if (modeController.mode == OperatingMode.DISARMED_RESTART_REQUIRED) return

        val player = event.player

        // Query whether player is currently in WildTools synthetic breaking state
        val inToolBreak = toolBreaksTrackerCheck(player)
        if (inToolBreak == true) {
            // Player is actively executing a synthetic tool break: ignore synthetic event on global bus
            return
        }
        if (inToolBreak == null) {
            // Failure to query tool break state: unknown root/synthetic identity. Fail closed!
            if (modeController.mode == OperatingMode.ACTIVE) {
                counters.validationFailures.increment()
                logger?.warning("[Optimizer] Failed to query ToolBreaksTracker for player '${player.name}'; aborting optimization.")
            }
            return
        }

        // Check if player holds a CuboidTool in main hand
        val item = try {
            player.inventory.itemInMainHand
        } catch (_: Throwable) {
            null
        } ?: return

        val tool = cuboidToolResolver(item) ?: return

        // Record observable candidate metric
        counters.cuboidRootCandidates.increment()

        // Cheap action-boundary fail-closed guard for Module B listener topology
        val accounting = blockAccountingFastPath
        if (accounting != null && accounting.isEligible) {
            val riskReport = riskScanner.scan()
            if (riskReport.state != itzherzchen.optimizer.observe.TransactionRiskState.CLEAN) {
                accounting.isEligible = false
                logger?.warning(
                    "[Optimizer] Action-boundary transaction guard detected risk on CuboidWandUseEvent " +
                            "(${riskReport.state}: ${riskReport.riskSummary}). Module B monotonically disabled; restart required."
                )
            }
        }

        // Live identity preflight validation before every root Cuboid action
        val checker = integrityChecker
        if (checker != null) {
            if (!checker()) {
                logger?.severe("[Optimizer] Aborting root Cuboid action: live identity verification failed.")
                return
            }
        } else if (modeController.mode == OperatingMode.ACTIVE) {
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "Root Cuboid action in ACTIVE mode without wired live identity validation. Disarming system."
            )
            return
        }

        // Sample timing if enabled (zero System.nanoTime() calls for non-sampled actions)
        val startNanos = if (timing?.shouldSample() == true) System.nanoTime() else 0L

        // Push candidate context onto stack
        val actionId = actionIdSequence.incrementAndGet()
        val context = ActionContext(actionId, event, player, tool, startNanos)

        val pushed = actionContextStack.push(context)
        if (!pushed) {
            logger?.warning("[Optimizer] Failed to push ActionContext #$actionId onto stack (fail-closed disarm active).")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onRootBlockBreakMonitor(event: BlockBreakEvent) {
        if (!primaryThreadChecker()) {
            if (modeController.mode == OperatingMode.ACTIVE) {
                counters.circuitTrips.increment()
                logger?.severe(
                    "[Optimizer] Off-main-thread MONITOR root Cuboid action in ACTIVE mode on thread '${Thread.currentThread().name}'! Tripping circuit breaker and disarming."
                )
                circuitBreaker.trip(
                    itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                    "Off-main-thread MONITOR root Cuboid action on thread '${Thread.currentThread().name}'"
                )
            }
            actionContextStack.clear()
            return
        }

        val current = actionContextStack.current()
        if (current != null && current.rootEvent === event) {
            counters.cuboidRootCompleted.increment()
            val confirmed = current.syntheticBreaksCount > 0 || current.isConfirmed
            // If the action produced synthetic breaks or was confirmed, increment verified cuboidActions counter
            if (confirmed) {
                counters.cuboidActions.increment()
                if (current.startTimeNanos > 0L && timing != null) {
                    timing.record(System.nanoTime() - current.startTimeNanos)
                }
            }
            actionContextStack.pop(event)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onCuboidWandUseMonitor(event: CuboidWandUseEvent) {
        if (event.isCancelled) {
            counters.externalCuboidCancellations.increment()
            logger?.info("[Optimizer] External cancellation observed on CuboidWandUseEvent.")
        }
    }
}
