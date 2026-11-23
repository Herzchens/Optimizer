package itzherzchen.optimizer.runtime

import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import itzherzchen.optimizer.observe.Counters
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class ActionContextStackTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { true })
    }

    private fun createMockContext(actionId: Long): Pair<ActionContext, BlockBreakEvent> {
        val player = mock(Player::class.java)
        val block = mock(Block::class.java)
        val event = BlockBreakEvent(block, player)
        val tool = mock(CuboidTool::class.java)
        return ActionContext(actionId, event, player, tool) to event
    }

    @Test
    fun `push current and pop work correctly in normal happy path`() {
        val (ctx, event) = createMockContext(1L)

        assertFalse(stack.hasActiveContext())
        assertEquals(0, stack.depth())
        assertNull(stack.current())

        val pushed = stack.push(ctx)
        assertTrue(pushed)
        assertTrue(stack.hasActiveContext())
        assertEquals(1, stack.depth())
        assertSame(ctx, stack.current())

        val popped = stack.pop(event)
        assertSame(ctx, popped)
        assertFalse(stack.hasActiveContext())
        assertEquals(0, stack.depth())
        assertNull(stack.current())

        // Invariant: Reference hygiene on close
        assertTrue(ctx.isClosed)
        assertNull(ctx.rootEvent)
        assertNull(ctx.player)
        assertNull(ctx.tool)
    }

    @Test
    fun `stale context detection trips circuit breaker and clears stack`() {
        val (ctx1, _) = createMockContext(1L)
        val (ctx2, _) = createMockContext(2L)

        // First action pushed but never popped (simulating abnormal dispatch abort)
        assertTrue(stack.push(ctx1))
        assertEquals(1, stack.depth())

        // Second action attempted: stale context must be detected!
        val pushed2 = stack.push(ctx2)
        assertFalse(pushed2)

        // Circuit breaker must trip GLOBAL
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertEquals(1L, counters.validationFailures.sum())
        assertEquals(1L, counters.circuitTrips.sum())

        // Stack must be cleared
        assertFalse(stack.hasActiveContext())
        assertEquals(0, stack.depth())
        assertTrue(ctx1.isClosed)
    }

    @Test
    fun `pop mismatch trips circuit breaker and cleans up to prevent leak`() {
        val (ctx1, event1) = createMockContext(1L)
        val (_, eventWrong) = createMockContext(999L)

        assertTrue(stack.push(ctx1))

        // Pop with mismatched expected event
        val popped = stack.pop(eventWrong)
        assertSame(ctx1, popped)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(1L, counters.validationFailures.sum())
        assertEquals(1L, counters.circuitTrips.sum())

        // Must still close popped context to avoid leaking player/event
        assertTrue(ctx1.isClosed)
        assertNull(ctx1.player)
        assertFalse(stack.hasActiveContext())
    }

    @Test
    fun `off thread push is rejected and trips circuit breaker`() {
        val offThreadStack = ActionContextStack(
            circuitBreaker,
            counters,
            primaryThreadChecker = { false } // simulate background thread
        )

        val (ctx, _) = createMockContext(1L)
        val pushed = offThreadStack.push(ctx)

        assertFalse(pushed)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertEquals(1L, counters.circuitTrips.sum())
        assertFalse(offThreadStack.hasActiveContext())
    }

    @Test
    fun `pop on empty stack returns null safely`() {
        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        val popped = stack.pop(event)
        assertNull(popped)
        assertFalse(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }

    @Test
    fun `clear closes all active contexts`() {
        val (ctx1, _) = createMockContext(1L)
        stack.push(ctx1)

        stack.clear()
        assertFalse(stack.hasActiveContext())
        assertEquals(0, stack.depth())
        assertTrue(ctx1.isClosed)
    }

    @Test
    fun `sequential actions succeed cleanly`() {
        val (ctx1, event1) = createMockContext(1L)
        val (ctx2, event2) = createMockContext(2L)

        assertTrue(stack.push(ctx1))
        assertSame(ctx1, stack.pop(event1))

        assertTrue(stack.push(ctx2))
        assertSame(ctx2, stack.pop(event2))

        assertEquals(0L, counters.validationFailures.sum())
        assertFalse(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }
}
