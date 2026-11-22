package itzherzchen.optimizer.superior

import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContext
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.wildtools.SyntheticBreakWrapper
import itzherzchen.optimizer.wildtools.SyntheticCallbackType
import org.bukkit.block.Block
import org.bukkit.entity.Player
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

class StackedFastRejectTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private lateinit var config: OptimizerConfig

    private var isPrimaryThread = true

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.ACTIVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
    }

    private fun createRegisteredListener(): Pair<RegisteredListener, EventExecutor> {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("SuperiorSkyblock2")
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, EventPriority.HIGHEST, plugin, false) to executor
    }

    private fun pushContext(): ActionContext {
        val player = mock(Player::class.java)
        val rootBlock = mock(Block::class.java)
        val rootEvent = BlockBreakEvent(rootBlock, player)
        val tool = mock(CuboidTool::class.java)
        val ctx = ActionContext(1L, rootEvent, player, tool)
        stack.push(ctx)
        return ctx
    }

    @Test
    fun `amount 1 skips provable no-op callback and increments stackedFastRejects`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        // Original callback must NOT be invoked
        verify(mockExecutor, never()).execute(any(), any())
        assertEquals(1L, counters.stackedFastRejects.sum())
        assertEquals(0L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `amount 0 preserves original callback and increments stackedPassthroughs`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 0 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `negative amount preserves original callback`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { -1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `amount 2 preserves original callback for stacked block unstacking`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 2 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `amount 64 preserves original callback for large stacked block`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 64 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `cancelled event preserves original callback even if amount is 1`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)
        event.isCancelled = true

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `missing action context preserves original callback even if amount is 1`() {
        // No context pushed
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `closed action context preserves original callback`() {
        val ctx = pushContext()
        ctx.close()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `module disabled in config preserves original callback`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val disabledConfig = config.copy(stackedFastRejectEnabled = false)

        val fastReject = StackedFastReject(
            config = disabledConfig,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `observe mode preserves original callback`() {
        pushContext()
        modeController.transitionTo(OperatingMode.OBSERVE, "Test observe")
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(0L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }

    @Test
    fun `guard API exception trips circuit breaker and invokes original callback pre-mutation`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { throw RuntimeException("SSB StackedBlocks database unavailable") }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        // Must NOT re-throw; must fail closed to original callback
        assertDoesNotThrow {
            fastReject.handle(event, origRL)
        }

        // Circuit breaker for STACKED_FAST_REJECT tripped
        assertTrue(circuitBreaker.isTripped(ModuleType.STACKED_FAST_REJECT))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(1L, counters.validationFailures.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
        verify(mockExecutor, times(1)).execute(origRL.listener, event)

        // Subsequent call passes through without hitting provider again
        fastReject.handle(event, origRL)
        assertEquals(2L, counters.stackedPassthroughs.sum())
        verify(mockExecutor, times(2)).execute(origRL.listener, event)
    }

    @Test
    fun `off main thread invocation trips global circuit breaker and invokes original`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        isPrimaryThread = false

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = { 1 }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastReject.handle(event, origRL)

        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
        verify(mockExecutor, times(1)).execute(origRL.listener, event)
    }

    @Test
    fun `never caches amount and checks every block query`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        var callCount = 0
        val amounts = listOf(1, 2, 1, 64, 1)

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = {
                amounts[callCount++]
            }
        )

        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        for (i in 0 until 5) {
            fastReject.handle(event, origRL)
        }

        assertEquals(5, callCount)
        assertEquals(3L, counters.stackedFastRejects.sum())
        assertEquals(2L, counters.stackedPassthroughs.sum())
        verify(mockExecutor, times(2)).execute(origRL.listener, event)
    }

    @Test
    fun `end-to-end integration via SyntheticBreakWrapper`() {
        val (origRL, mockExecutor) = createRegisteredListener()

        var queryCount = 0
        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedAmountProvider = {
                queryCount++
                if (queryCount == 1) 1 else 2
            }
        )

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            stackedFastReject = fastReject
        )

        val ctx = pushContext()
        val block = mock(Block::class.java)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        // First call: amount 1 -> fast reject
        wrapper.execute(origRL.listener, event)
        verify(mockExecutor, never()).execute(any(), any())
        assertEquals(1, ctx.syntheticBreaksCount)
        assertEquals(1L, counters.syntheticBreaks.sum())
        assertEquals(1L, counters.stackedFastRejects.sum())
        assertEquals(0L, counters.stackedPassthroughs.sum())

        // Second call: amount 2 -> original called
        wrapper.execute(origRL.listener, event)
        verify(mockExecutor, times(1)).execute(origRL.listener, event)
        assertEquals(2, ctx.syntheticBreaksCount)
        assertEquals(2L, counters.syntheticBreaks.sum())
        assertEquals(1L, counters.stackedFastRejects.sum())
        assertEquals(1L, counters.stackedPassthroughs.sum())
    }
}
