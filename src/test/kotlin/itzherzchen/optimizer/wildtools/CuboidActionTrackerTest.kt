package itzherzchen.optimizer.wildtools

import com.bgsoftware.wildtools.api.events.CuboidWandUseEvent
import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class CuboidActionTrackerTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var timing: itzherzchen.optimizer.observe.SampledTiming
    private lateinit var stack: ActionContextStack
    private lateinit var tracker: CuboidActionTracker

    private var isPrimaryThread = true
    private var isTrackedInToolBreaks = false
    private val cuboidToolMock = mock(CuboidTool::class.java)

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        timing = itzherzchen.optimizer.observe.SampledTiming(sampleRate = 1)
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        tracker = CuboidActionTracker(
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            timing = timing,
            primaryThreadChecker = { isPrimaryThread },
            cuboidToolResolver = { item -> if (item.type.name == "DIAMOND_PICKAXE") cuboidToolMock else null },
            toolBreaksTrackerCheck = { isTrackedInToolBreaks }
        )
    }

    private fun createPlayerWithTool(toolType: String): Pair<Player, BlockBreakEvent> {
        val player = mock(Player::class.java)
        val inv = mock(PlayerInventory::class.java)
        val item = mock(ItemStack::class.java)
        val itemType = mock(org.bukkit.Material::class.java)
        `when`(itemType.name).thenReturn(toolType)
        `when`(item.type).thenReturn(itemType)
        `when`(inv.itemInMainHand).thenReturn(item)
        `when`(player.inventory).thenReturn(inv)

        val block = mock(Block::class.java)
        val event = BlockBreakEvent(block, player)
        return player to event
    }

    @Test
    fun `tracks valid Cuboid action with synthetic breaks and increments cuboidActions counter`() {
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertTrue(stack.hasActiveContext())
        val ctx = stack.current()
        assertNotNull(ctx)
        assertEquals(0, ctx!!.syntheticBreaksCount)

        // Simulate synthetic breaks occurring inside WildTools
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, rootEvent.player)
        ctx.recordSyntheticBreak(synthEvent)
        assertEquals(1, ctx.syntheticBreaksCount)

        tracker.onRootBlockBreakMonitor(rootEvent)
        assertFalse(stack.hasActiveContext())
        assertEquals(1L, counters.cuboidActions.sum())
    }

    @Test
    fun `ignores event when player does not hold CuboidTool`() {
        val (_, rootEvent) = createPlayerWithTool("STICK")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertFalse(stack.hasActiveContext())

        tracker.onRootBlockBreakMonitor(rootEvent)
        assertEquals(0L, counters.cuboidActions.sum())
    }

    @Test
    fun `ignores synthetic event when player is in ToolBreaksTracker`() {
        isTrackedInToolBreaks = true
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertFalse(stack.hasActiveContext())
    }

    @Test
    fun `action with zero synthetic breaks does not increment cuboidActions`() {
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertTrue(stack.hasActiveContext())

        // MONITOR called without synthetic breaks
        tracker.onRootBlockBreakMonitor(rootEvent)
        assertFalse(stack.hasActiveContext())
        assertEquals(0L, counters.cuboidActions.sum())
    }

    @Test
    fun `stale context between events trips circuit breaker and disarms system`() {
        val (_, event1) = createPlayerWithTool("DIAMOND_PICKAXE")
        val (_, event2) = createPlayerWithTool("DIAMOND_PICKAXE")

        // Event 1 starts but MONITOR is aborted due to simulated exception
        tracker.onRootBlockBreakLowest(event1)
        assertTrue(stack.hasActiveContext())

        // Event 2 arrives: stale context detected!
        tracker.onRootBlockBreakLowest(event2)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertEquals(1L, counters.validationFailures.sum())
        assertFalse(stack.hasActiveContext())
    }

    @Test
    fun `ignores root event when mode is DISARMED_RESTART_REQUIRED`() {
        modeController.disarm("Prior fault", "TEST")
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertFalse(stack.hasActiveContext())
    }

    @Test
    fun `detects external cancellation on CuboidWandUseEvent`() {
        val player = mock(Player::class.java)
        val eventCancelled = CuboidWandUseEvent(player, cuboidToolMock, emptyList())
        eventCancelled.isCancelled = true

        tracker.onCuboidWandUseMonitor(eventCancelled)
        assertEquals(1L, counters.externalCuboidCancellations.sum())

        val eventUncancelled = CuboidWandUseEvent(player, cuboidToolMock, emptyList())
        tracker.onCuboidWandUseMonitor(eventUncancelled)
        assertEquals(1L, counters.externalCuboidCancellations.sum())
    }

    @Test
    fun `off thread invocation is ignored without creating context`() {
        isPrimaryThread = false
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertFalse(stack.hasActiveContext())

        tracker.onRootBlockBreakMonitor(rootEvent)
        assertEquals(0L, counters.cuboidActions.sum())
    }

    @Test
    fun `records sampled latency on confirmed cuboid action`() {
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        val ctx = stack.current()
        assertNotNull(ctx)
        assertTrue(ctx!!.startTimeNanos > 0L)

        // Record a synthetic break to confirm
        val synthBlock = mock(Block::class.java)
        ctx.recordSyntheticBreak(BlockBreakEvent(synthBlock, rootEvent.player))

        tracker.onRootBlockBreakMonitor(rootEvent)
        val stats = timing.getStats()
        assertEquals(1L, stats.count)
        assertTrue(stats.avgMillis >= 0.0)
    }

    @Test
    fun `does not record sampled latency when action produces no synthetic breaks`() {
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        tracker.onRootBlockBreakMonitor(rootEvent)

        val stats = timing.getStats()
        assertEquals(0L, stats.count)
    }

    @Test
    fun `normal root action integrity check passes and pushes context`() {
        tracker.integrityChecker = { true }
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)
        assertTrue(stack.hasActiveContext(), "Root action must create active context when integrity check passes")
        assertEquals(OperatingMode.OBSERVE, modeController.mode)
        assertFalse(circuitBreaker.isTripped(itzherzchen.optimizer.runtime.ModuleType.GLOBAL))
    }

    @Test
    fun `WT list drift on root action triggers global disarm and aborts action context`() {
        // Integrity check simulates WT list drift
        tracker.integrityChecker = {
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "WildTools list drift detected"
            )
            false
        }
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)

        assertFalse(stack.hasActiveContext(), "Context must not be pushed on failed preflight")
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(circuitBreaker.isTripped(itzherzchen.optimizer.runtime.ModuleType.GLOBAL))
    }

    @Test
    fun `replacement listener triggers disarm and aborts action context`() {
        tracker.integrityChecker = {
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "Replacement listener detected at wrapped slot"
            )
            false
        }
        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")

        tracker.onRootBlockBreakLowest(rootEvent)

        assertFalse(stack.hasActiveContext(), "No context should be created for failed integrity preflight")
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(circuitBreaker.isTripped(itzherzchen.optimizer.runtime.ModuleType.GLOBAL))
    }

    @Test
    fun `hooks absent and OBSERVE mode produces no unsafe behavior`() {
        // In OBSERVE mode with no integrityChecker wired or hooks absent
        tracker.integrityChecker = null
        assertEquals(OperatingMode.OBSERVE, modeController.mode)

        val (_, rootEvent) = createPlayerWithTool("DIAMOND_PICKAXE")
        tracker.onRootBlockBreakLowest(rootEvent)

        assertTrue(stack.hasActiveContext(), "OBSERVE mode allows passive tracking without hooks")
        assertEquals(OperatingMode.OBSERVE, modeController.mode)
        assertFalse(circuitBreaker.isTripped(itzherzchen.optimizer.runtime.ModuleType.GLOBAL))
    }
}
