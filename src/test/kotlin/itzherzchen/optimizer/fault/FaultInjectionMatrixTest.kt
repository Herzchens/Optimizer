package itzherzchen.optimizer.fault

import com.bgsoftware.superiorskyblock.api.island.Island
import com.bgsoftware.superiorskyblock.api.key.Key
import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContext
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.superior.BlockAccountingFastPath
import itzherzchen.optimizer.superior.StackedFastReject
import itzherzchen.optimizer.superior.VanillaBlockClassifier
import itzherzchen.optimizer.wildtools.SyntheticListenerInstaller
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
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

/**
 * Deep Fault-Injection Matrix Test (Plan.md Section 7, Section 8, and Section 9).
 *
 * Verifies non-negotiable safety rules:
 * - Mutation and exception rule: Before mutation, safe fallback to original is valid.
 *   After direct mutation has begun (handleBlockBreak), NEVER retry original callback.
 * - Tripped circuit breakers guarantee safe passthrough on all future operations.
 * - Off-thread synthetic execution trips circuit breaker and falls back to passthrough.
 * - External listener list drift during reload is preserved without destructive overwrites.
 * - Stale context detection prevents cross-action context leaks.
 */
class FaultInjectionMatrixTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private lateinit var config: OptimizerConfig
    private lateinit var island: Island
    private lateinit var world: World
    private lateinit var player: Player

    private var isPrimary = true

    class FaultTestKey(private val global: String) : Key {
        override fun getGlobalKey(): String = global
        override fun getSubKey(): String = ""
        override fun compareTo(other: Key): Int = global.compareTo(other.globalKey)
    }

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.ACTIVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        isPrimary = true
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimary })
        config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        island = mock(Island::class.java)
        world = mock(World::class.java)
        `when`(world.name).thenReturn("world")
        `when`(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true)
        player = mock(Player::class.java)
    }

    private fun pushContext(): ActionContext {
        val rootBlock = mock(Block::class.java)
        val rootEvent = BlockBreakEvent(rootBlock, player)
        val tool = mock(CuboidTool::class.java)
        val ctx = ActionContext(500L, rootEvent, player, tool)
        stack.push(ctx)
        return ctx
    }

    private fun createBreakEvent(material: Material = Material.STONE): Pair<BlockBreakEvent, RegisteredListener> {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(material)
        `when`(block.x).thenReturn(10)
        `when`(block.y).thenReturn(64)
        `when`(block.z).thenReturn(10)
        `when`(block.world).thenReturn(world)
        `when`(block.location).thenReturn(Location(world, 10.0, 64.0, 10.0))

        for (face in arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            val rel = mock(Block::class.java)
            `when`(rel.type).thenReturn(Material.AIR)
            `when`(block.getRelative(face)).thenReturn(rel)
        }

        val bd = mock(org.bukkit.block.data.BlockData::class.java)
        `when`(block.blockData).thenReturn(bd)

        val event = BlockBreakEvent(block, player)
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("SuperiorSkyblock2")
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        val registeredListener = RegisteredListener(listener, executor, EventPriority.MONITOR, plugin, false)
        return event to registeredListener
    }

    // --- 1. DIRECT MUTATION FAULT INJECTION (THE CARDINAL RULE) ---

    @Test
    fun `direct mutation exception in handleBlockBreak trips breaker and NEVER calls original`() {
        val ctx = pushContext()
        ctx.chunkCoverage.markCovered(world, 0, 0, island)

        // Make direct handleBlockBreak throw!
        `when`(island.handleBlockBreak(any(Key::class.java), anyInt(), anyInt())).thenThrow(
            RuntimeException("Database connection dropped during island accounting write!")
        )

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = VanillaBlockClassifier(
                targetClassName = FaultTestKey::class.java.name,
                keyProvider = { FaultTestKey(Material.STONE.name) }
            ),
            islandProvider = { island },
            primaryThreadChecker = { isPrimary }
        )

        val (event, original) = createBreakEvent(Material.STONE)

        // Execution must not rethrow unchecked, but MUST trip circuit breaker
        assertDoesNotThrow {
            fastPath.handle(event, original)
        }

        // INVARIANT 1: Circuit breaker must be TRIPPED
        assertTrue(circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))

        // INVARIANT 2: Original callback MUST NOT HAVE BEEN RETRIED!
        verify(original.executor, never()).execute(any(), any())

        // INVARIANT 3: Counter circuitTrips must be recorded
        assertEquals(1L, counters.circuitTrips.sum())

        // INVARIANT 4: Next event must pass through to original safely because breaker is tripped
        val (nextEvent, nextOriginal) = createBreakEvent(Material.STONE)
        fastPath.handle(nextEvent, nextOriginal)
        verify(nextOriginal.executor, times(1)).execute(any(), eq(nextEvent))
    }

    // --- 2. PRE-MUTATION FAULT INJECTION (SAFE FALLBACK) ---

    @Test
    fun `pre-mutation exception during island query trips breaker and invokes original exactly once`() {
        pushContext()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            islandProvider = { throw IllegalStateException("Island grid cache corrupted") },
            primaryThreadChecker = { isPrimary }
        )

        val (event, original) = createBreakEvent(Material.STONE)

        fastPath.handle(event, original)

        // Pre-mutation: circuit breaker trips, but original is called exactly once as safe fallback
        assertTrue(circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))
        verify(original.executor, times(1)).execute(any(), eq(event))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(1L, counters.validationFailures.sum())
    }

    @Test
    fun `pre-mutation exception in sign checker fails closed and falls back to original`() {
        pushContext()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            islandProvider = { island },
            primaryThreadChecker = { isPrimary },
            signChecker = { throw RuntimeException("Tag lookup error") }
        )

        val (event, original) = createBreakEvent(Material.STONE)

        fastPath.handle(event, original)

        // Fails closed as signPassthrough
        verify(original.executor, times(1)).execute(any(), eq(event))
        assertEquals(1L, counters.signPassthroughs.sum())
        // Direct mutation must not have occurred
        verify(island, never()).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())
    }

    @Test
    fun `pre-mutation exception in stacked block provider trips breaker and delegates to original`() {
        pushContext()

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            stackedAmountProvider = { throw RuntimeException("SSB stacked blocks service unavailable") }
        )

        val (event, original) = createBreakEvent(Material.STONE)

        fastReject.handle(event, original)

        assertTrue(circuitBreaker.isTripped(ModuleType.STACKED_FAST_REJECT))
        verify(original.executor, times(1)).execute(any(), eq(event))
        assertEquals(1L, counters.circuitTrips.sum())
    }

    // --- 3. OFF-THREAD INVOCATION CONTAINMENT ---

    @Test
    fun `off-thread execution in fast path trips global breaker and falls back to original`() {
        pushContext()
        isPrimary = false // Simulate worker thread

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            primaryThreadChecker = { isPrimary }
        )

        val (event, original) = createBreakEvent(Material.STONE)

        fastPath.handle(event, original)

        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        verify(original.executor, times(1)).execute(any(), eq(event))
    }

    @Test
    fun `off-thread push on ActionContextStack is rejected and trips global breaker`() {
        isPrimary = false // Simulate off-thread

        val rootBlock = mock(Block::class.java)
        val rootEvent = BlockBreakEvent(rootBlock, player)
        val tool = mock(CuboidTool::class.java)
        val ctx = ActionContext(999L, rootEvent, player, tool)

        val success = stack.push(ctx)

        assertFalse(success)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertNull(stack.current())
    }

    // --- 4. LIST DRIFT ON RELOAD ---

    @Test
    fun `listener installer detects list drift on reload and preserves foreign entries`() {
        val ssbPlugin = mock(Plugin::class.java)
        `when`(ssbPlugin.name).thenReturn("SuperiorSkyblock2")
        val foreignPlugin = mock(Plugin::class.java)
        `when`(foreignPlugin.name).thenReturn("SomeOtherPlugin")

        val originalList = arrayListOf(
            RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.NORMAL, ssbPlugin, false),
            RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.HIGHEST, ssbPlugin, false),
            RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.MONITOR, ssbPlugin, false)
        )

        val census = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
            isTopologyValid = true,
            entries = listOf(
                itzherzchen.optimizer.bootstrap.ListenerCensusEntry(0, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.NORMAL, false),
                itzherzchen.optimizer.bootstrap.ListenerCensusEntry(1, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.HIGHEST, false),
                itzherzchen.optimizer.bootstrap.ListenerCensusEntry(2, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.MONITOR, false)
            ),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )

        val installer = SyntheticListenerInstaller(
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimary }
        )

        // Install hooks
        val installed = installer.install(originalList, census)
        assertEquals(itzherzchen.optimizer.wildtools.InstallResult.SUCCESS, installed)

        // Simulate external plugin reload: a new list was constructed with completely different instances
        val foreignStacked = RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.HIGHEST, foreignPlugin, false)
        val foreignMonitor = RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.MONITOR, foreignPlugin, false)
        originalList[1] = foreignStacked
        originalList[2] = foreignMonitor

        // Uninstall against reloaded list: must detect drift and NOT clobber foreign list
        val result = installer.uninstall()

        assertEquals(itzherzchen.optimizer.wildtools.UninstallResult.EXTERNAL_LIST_DRIFT, result)
        // Foreign entries must remain completely untouched
        assertEquals(foreignPlugin, originalList[1].plugin)
        assertEquals(foreignPlugin, originalList[2].plugin)
    }

    // --- 5. STALE CONTEXT SAFETY ---

    @Test
    fun `stale context between actions trips circuit breaker and clears stack to prevent leak`() {
        // Push first context without popping
        pushContext()
        assertEquals(1, stack.depth())

        // Next root action begins
        val secondBlock = mock(Block::class.java)
        val secondEvent = BlockBreakEvent(secondBlock, player)
        val secondTool = mock(CuboidTool::class.java)
        val secondCtx = ActionContext(501L, secondEvent, player, secondTool)

        val pushed = stack.push(secondCtx)

        assertFalse(pushed, "Pushing new action over stale unclosed action must fail")
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertEquals(0, stack.depth(), "Stack must be cleared upon stale context detection")
    }

    // --- 6. MISSING FIELD & REFLECTION FAILURE ---

    @Test
    fun `missing claimingPluginsBreakMethods field in WildTools fails closed cleanly returning null`() {
        val foreignPlugin = mock(Plugin::class.java)
        // No getEvents method or field exists on this mock
        val result = itzherzchen.optimizer.wildtools.WildToolsBridge.getClaimingPluginsBreakMethods(foreignPlugin)
        assertNull(result, "Missing field/method must fail closed and return null without throwing")
    }

    // --- 7. STARTUP TOPOLOGY REJECTION ---

    @Test
    fun `installer strictly rejects invalid listener topology with missing protection listener`() {
        val ssbPlugin = mock(Plugin::class.java).apply { `when`(name).thenReturn("SuperiorSkyblock2") }
        val rawList = arrayListOf(
            RegisteredListener(object : Listener {}, mock(EventExecutor::class.java), EventPriority.HIGHEST, ssbPlugin, false)
        )

        val invalidCensus = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
            isTopologyValid = false,
            entries = emptyList(),
            stackedBlocksIndex = 0,
            blockChangesIndex = -1,
            signsListenerIndex = -1,
            protectionListenerIndex = -1, // MISSING
            discrepancies = listOf("Missing SSB NORMAL protection callback")
        )

        val installer = SyntheticListenerInstaller(stack, modeController, circuitBreaker, counters, primaryThreadChecker = { isPrimary })
        val installResult = installer.install(rawList, invalidCensus)

        assertEquals(itzherzchen.optimizer.wildtools.InstallResult.INVALID_TOPOLOGY, installResult)
        assertFalse(installer.isInstalled)
    }

    // --- 8. POST-MUTATION CIRCUIT BREAKER TRIPPING PASS-THROUGH ---

    @Test
    fun `downstream failure after direct mutation leaves future events in passthrough without original retry`() {
        val ctx = pushContext()
        ctx.chunkCoverage.markCovered(world, 0, 0, island)

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = VanillaBlockClassifier(
                targetClassName = FaultTestKey::class.java.name,
                keyProvider = { FaultTestKey(Material.STONE.name) }
            ),
            islandProvider = { island },
            primaryThreadChecker = { isPrimary }
        )

        val (firstEvent, firstOriginal) = createBreakEvent(Material.STONE)
        fastPath.handle(firstEvent, firstOriginal)

        // handleBlockBreak was invoked for the fast-path block
        verify(island, times(1)).handleBlockBreak(any(Key::class.java), eq(1), eq(3))
        verify(firstOriginal.executor, never()).execute(any(), any())

        // Simulate external downstream failure tripping the circuit breaker after mutation
        circuitBreaker.trip(ModuleType.BLOCK_ACCOUNTING_FAST_PATH, "Downstream post-mutation event pipeline error")

        // Next event must safely fall back to original passthrough
        val (secondEvent, secondOriginal) = createBreakEvent(Material.STONE)
        fastPath.handle(secondEvent, secondOriginal)

        verify(secondOriginal.executor, times(1)).execute(any(), eq(secondEvent))
        // Direct mutation must NOT have been called a second time
        verify(island, times(1)).handleBlockBreak(any(Key::class.java), eq(1), eq(3))
    }
}
