package itzherzchen.optimizer.wildtools

import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContext
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class SyntheticBreakWrapperTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack

    private var isPrimaryThread = true

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
    }

    private fun createRegisteredListener(pluginName: String, priority: EventPriority): Pair<RegisteredListener, EventExecutor> {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn(pluginName)
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, priority, plugin, false) to executor
    }

    @Test
    fun `normal execution delegates to original and records synthetic break when context active`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val rootBlock = mock(Block::class.java)
        val rootEvent = BlockBreakEvent(rootBlock, player)
        val tool = mock(CuboidTool::class.java)
        val ctx = ActionContext(1L, rootEvent, player, tool)
        stack.push(ctx)

        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        // Verify original executor was invoked
        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
        assertEquals(1, ctx.syntheticBreaksCount)
        assertEquals(1L, counters.syntheticBreaks.sum())
        assertFalse(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }

    @Test
    fun `second callback in chain (ACCOUNTING) delegates without double incrementing syntheticBreaks`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.ACCOUNTING,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val rootBlock = mock(Block::class.java)
        val rootEvent = BlockBreakEvent(rootBlock, player)
        val tool = mock(CuboidTool::class.java)
        val ctx = ActionContext(1L, rootEvent, player, tool)
        stack.push(ctx)

        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
        assertEquals(1, ctx.syntheticBreaksCount)
        // ACCOUNTING does not increment syntheticBreaks counter (only STACKED_BLOCKS does)
        assertEquals(0L, counters.syntheticBreaks.sum())
    }

    @Test
    fun `delegates to original without error when no context active`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
        assertEquals(0L, counters.syntheticBreaks.sum())
    }

    @Test
    fun `off thread invocation trips circuit breaker and delegates to original`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        isPrimaryThread = false
        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
    }

    @Test
    fun `passthroughOnly true delegates to original directly`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        wrapper.passthroughOnly = true

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
    }

    @Test
    fun `non BlockBreakEvent delegates directly to original`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val foreignEvent = mock(Event::class.java)
        wrapper.execute(origRL.listener, foreignEvent)

        verify(mockExecutor, times(1)).execute(origRL.listener, foreignEvent)
    }

    @Test
    fun `when original throws RuntimeException, wrapper preserves exact exception without wrapping and without retry`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val expectedEx = RuntimeException("SSB downstream runtime failure")
        `when`(mockExecutor.execute(any(), any())).thenThrow(expectedEx)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        val thrown = assertThrows(RuntimeException::class.java) {
            wrapper.execute(origRL.listener, synthEvent)
        }
        assertSame(expectedEx, thrown)

        // Critical safety invariant: Never invoke original more than once per event!
        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
    }

    @Test
    fun `when original throws EventException, wrapper preserves exact exception without retry`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val expectedEx = org.bukkit.event.EventException(IllegalStateException("Root cause"))
        `when`(mockExecutor.execute(any(), any())).thenThrow(expectedEx)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        val thrown = assertThrows(org.bukkit.event.EventException::class.java) {
            wrapper.execute(origRL.listener, synthEvent)
        }
        assertSame(expectedEx, thrown)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
    }

    @Test
    fun `when original throws Error, wrapper preserves exact Error without retry`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val expectedError = AssertionError("Assertion error in SSB listener")
        `when`(mockExecutor.execute(any(), any())).thenThrow(expectedError)

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        val thrown = assertThrows(AssertionError::class.java) {
            wrapper.execute(origRL.listener, synthEvent)
        }
        assertSame(expectedError, thrown)

        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
    }

    @Test
    fun `when pre-delegation bookkeeping throws, trips circuit breaker and calls original exactly once`() {
        val (origRL, mockExecutor) = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)

        // Create a faulty stack that throws during current()
        val mockStack = mock(ActionContextStack::class.java)
        `when`(mockStack.current()).thenThrow(RuntimeException("Stack internal error"))

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = mockStack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val player = mock(Player::class.java)
        val synthBlock = mock(Block::class.java)
        val synthEvent = BlockBreakEvent(synthBlock, player)

        wrapper.execute(origRL.listener, synthEvent)

        // Circuit breaker must trip GLOBAL
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(1L, counters.circuitTrips.sum())

        // Original executor must be invoked exactly once as safe fail-closed fallback
        verify(mockExecutor, times(1)).execute(origRL.listener, synthEvent)
    }
}
