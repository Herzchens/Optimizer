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
            true
        }
    },
    private val cuboidToolResolver: (ItemStack) -> CuboidTool? = { item ->
        try {
            WildToolsAPI.getCuboidTool(item)
        } catch (_: Throwable) {
            null
        }
    },
    private val toolBreaksTrackerCheck: (Player) -> Boolean = { player ->
        try {
            ToolBreaksTracker.containsPlayer(player)
        } catch (_: Throwable) {
            false
        }
    }
) : Listener {

    private val actionIdSequence = AtomicLong(0)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onRootBlockBreakLowest(event: BlockBreakEvent) {
        if (!primaryThreadChecker()) return

        if (modeController.mode == OperatingMode.DISARMED_RESTART_REQUIRED) return

        val player = event.player

        // If player is already tracked by WildTools as breaking blocks, this is a synthetic event on the bus
        if (toolBreaksTrackerCheck(player)) return

        // Check if player holds a CuboidTool in main hand
        val item = try {
            player.inventory.itemInMainHand
        } catch (_: Throwable) {
            null
        } ?: return

        val tool = cuboidToolResolver(item) ?: return

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
        if (!primaryThreadChecker()) return

        val current = actionContextStack.current()
        if (current != null && current.rootEvent === event) {
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
