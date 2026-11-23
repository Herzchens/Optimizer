package itzherzchen.optimizer.superior

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
import itzherzchen.optimizer.wildtools.SyntheticBreakWrapper
import itzherzchen.optimizer.wildtools.SyntheticCallbackType
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Slab
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

class BlockAccountingFastPathTest {

    class TestKey(
        private val global: String,
        private val sub: String? = ""
    ) : Key {
        override fun getGlobalKey(): String = global
        override fun getSubKey(): String? = sub
        override fun compareTo(other: Key): Int = 0
    }

    private val targetClassName = TestKey::class.java.name

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private lateinit var config: OptimizerConfig
    private lateinit var island: Island
    private lateinit var world: World

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
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        island = mock(Island::class.java)
        world = mock(World::class.java)
        `when`(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true)
    }

    private fun createClassifier(keyProvider: (Block) -> Key?): VanillaBlockClassifier {
        return VanillaBlockClassifier(
            targetClassName = targetClassName,
            keyProvider = keyProvider
        )
    }

    private fun createRegisteredListener(): Pair<RegisteredListener, EventExecutor> {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("SuperiorSkyblock2")
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, EventPriority.MONITOR, plugin, false) to executor
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

    private fun mockBlock(
        material: Material = Material.STONE,
        x: Int = 10,
        y: Int = 64,
        z: Int = 10,
        isDoubleSlab: Boolean = false,
        neighborSignFace: BlockFace? = null
    ): Block {
        val block = mock(Block::class.java)
        `when`(block.type).thenReturn(material)
        `when`(block.world).thenReturn(world)
        `when`(block.x).thenReturn(x)
        `when`(block.y).thenReturn(y)
        `when`(block.z).thenReturn(z)
        `when`(block.location).thenReturn(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))

        val defaultData = mock(org.bukkit.block.data.BlockData::class.java)
        `when`(block.blockData).thenReturn(defaultData)

        if (isDoubleSlab) {
            val slabData = mock(Slab::class.java)
            `when`(slabData.type).thenReturn(Slab.Type.DOUBLE)
            `when`(block.blockData).thenReturn(slabData)
        }

        // Neighbors
        val faces = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
        for (face in faces) {
            val neighbor = mock(Block::class.java)
            if (face == neighborSignFace) {
                `when`(neighbor.type).thenReturn(Material.OAK_SIGN)
            } else {
                `when`(neighbor.type).thenReturn(Material.AIR)
            }
            `when`(block.getRelative(face)).thenReturn(neighbor)
        }

        return block
    }

    @Test
    fun `first block in chunk anchors via original callback and marks chunk covered`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey("STONE") },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val block = mockBlock(material = Material.STONE, x = 10, z = 10)
        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        // Must invoke original MONITOR callback as the anchor
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        // Direct island mutation must NOT be called for the anchor
        verify(island, never()).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())
        assertEquals(1L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
        assertEquals(0L, counters.dirtyTasksAvoided.sum())
    }

    @Test
    fun `second block in covered chunk uses fast path direct mutation and avoids dirty task`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Block 1: anchor (chunkX = 0, chunkZ = 0)
        val block1 = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event1 = BlockBreakEvent(block1, player)
        fastPath.handle(event1, origRL)

        assertEquals(1L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event1))

        // Block 2: same chunk (chunkX = 0, chunkZ = 0) -> fast path!
        val block2 = mockBlock(material = Material.DIRT, x = 2, z = 2)
        val event2 = BlockBreakEvent(block2, player)
        fastPath.handle(event2, origRL)

        // Original callback must NOT be invoked again
        verify(mockExecutor, never()).execute(eq(origRL.listener), eq(event2))
        // Direct mutation must be invoked: key = DIRT, amount = 1, flags = 3
        verify(island, times(1)).handleBlockBreak(argThat<Key> { it.globalKey == "DIRT" }, eq(1), eq(3))
        assertEquals(1L, counters.monitorAnchors.sum())
        assertEquals(1L, counters.monitorFastPaths.sum())
        assertEquals(1L, counters.dirtyTasksAvoided.sum())
    }

    @Test
    fun `double slab on fast path uses amount 2`() {
        pushContext()
        val (origRL, _) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Anchor first
        val block1 = mockBlock(material = Material.STONE, x = 1, z = 1)
        fastPath.handle(BlockBreakEvent(block1, player), origRL)

        // Second block is a double slab in same chunk
        val slabBlock = mockBlock(material = Material.OAK_SLAB, x = 2, z = 2, isDoubleSlab = true)
        val slabEvent = BlockBreakEvent(slabBlock, player)
        fastPath.handle(slabEvent, origRL)

        verify(island, times(1)).handleBlockBreak(argThat<Key> { it.globalKey == "OAK_SLAB" }, eq(2), eq(3))
        assertEquals(1L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `sign block or adjacent sign block fails sign gate and falls back to original without anchoring`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Block adjacent to a wall sign on NORTH face
        val blockNearSign = mockBlock(material = Material.STONE, x = 5, z = 5, neighborSignFace = BlockFace.NORTH)
        val event = BlockBreakEvent(blockNearSign, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        verify(island, never()).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())
        assertEquals(1L, counters.signPassthroughs.sum())
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `spawner block falls back to original without anchoring`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { null },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val spawnerBlock = mockBlock(material = Material.SPAWNER, x = 5, z = 5)
        val event = BlockBreakEvent(spawnerBlock, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(1L, counters.spawnerPassthroughs.sum())
        assertEquals(0L, counters.monitorAnchors.sum())
    }

    @Test
    fun `custom block (e-g- ItemsAdder with subkey) falls back to original without anchoring`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val customKey = TestKey("DIAMOND_ORE", "custom_gem")

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { customKey },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val customBlock = mockBlock(material = Material.DIAMOND_ORE, x = 5, z = 5)
        val event = BlockBreakEvent(customBlock, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(1L, counters.customPassthroughs.sum())
        assertEquals(0L, counters.monitorAnchors.sum())
    }

    @Test
    fun `block outside island falls back to original without anchoring`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey("STONE") },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { null } // Outside any island
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 5, z = 5)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `cancelled event falls back to original and does not anchor`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey("STONE") },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 5, z = 5)
        val event = BlockBreakEvent(block, player)
        event.isCancelled = true

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
    }

    @Test
    fun `direct mutation exception trips circuit breaker and NEVER retries original`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        // Fault injection: island throws when mutating!
        doThrow(RuntimeException("SSB database disk failure during block accounting"))
            .`when`(island).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Anchor first block
        val block1 = mockBlock(material = Material.STONE, x = 1, z = 1)
        fastPath.handle(BlockBreakEvent(block1, player), origRL)
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), any())

        // Fast path on second block in same chunk triggers the exception in handleBlockBreak
        val block2 = mockBlock(material = Material.STONE, x = 2, z = 2)
        val event2 = BlockBreakEvent(block2, player)

        assertDoesNotThrow {
            fastPath.handle(event2, origRL)
        }

        // Circuit breaker must trip for BLOCK_ACCOUNTING_FAST_PATH
        assertTrue(circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))
        assertEquals(1L, counters.circuitTrips.sum())

        // CRITICAL INVARIANT: NEVER RETRY ORIGINAL AFTER DIRECT MUTATION STARTS!
        // mockExecutor was called once for block 1 (anchor), but must NOT be called for block 2!
        verify(mockExecutor, never()).execute(eq(origRL.listener), eq(event2))

        // Subsequent call falls back to original because circuit breaker is tripped
        val block3 = mockBlock(material = Material.STONE, x = 3, z = 3)
        val event3 = BlockBreakEvent(block3, player)
        fastPath.handle(event3, origRL)
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event3))
    }

    @Test
    fun `end-to-end integration via SyntheticBreakWrapper with ACCOUNTING type`() {
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val wrapper = SyntheticBreakWrapper(
            original = origRL,
            type = SyntheticCallbackType.ACCOUNTING,
            actionContextStack = stack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            primaryThreadChecker = { isPrimaryThread },
            blockAccountingFastPath = fastPath
        )

        pushContext()
        val player = mock(Player::class.java)

        // Block 1: anchor
        val block1 = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event1 = BlockBreakEvent(block1, player)
        wrapper.execute(origRL.listener, event1)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event1))
        assertEquals(1L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())

        // Block 2: fast path in same chunk
        val block2 = mockBlock(material = Material.STONE, x = 2, z = 2)
        val event2 = BlockBreakEvent(block2, player)
        wrapper.execute(origRL.listener, event2)

        verify(island, times(1)).handleBlockBreak(any(Key::class.java), eq(1), eq(3))
        assertEquals(1L, counters.monitorFastPaths.sum())
        assertEquals(1L, counters.dirtyTasksAvoided.sum())
    }

    @Test
    fun `off main thread invocation trips global circuit breaker and invokes original`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()
        isPrimaryThread = false

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        assertEquals(1L, counters.circuitTrips.sum())
    }

    @Test
    fun `observe mode preserves original callback and does not anchor or fast path`() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `module disabled in config preserves original callback`() {
        config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `missing action context preserves original callback`() {
        // stack is empty (no pushContext)
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `closed action context preserves original callback`() {
        val ctx = pushContext()
        ctx.close()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `island provider exception trips circuit breaker and invokes original callback`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { throw RuntimeException("Grid database failure") }
        )

        val player = mock(Player::class.java)
        val block = mockBlock(material = Material.STONE, x = 1, z = 1)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        assertTrue(circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(1L, counters.validationFailures.sum())
    }

    @Test
    fun `blockData exception before mutation trips circuit breaker and invokes original without mutating island`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Block 1: anchor (chunkX = 0, chunkZ = 0)
        val block1 = mockBlock(material = Material.STONE, x = 1, z = 1)
        fastPath.handle(BlockBreakEvent(block1, player), origRL)
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), any())

        // Block 2 in same chunk: block.blockData throws!
        val block2 = mockBlock(material = Material.STONE, x = 2, z = 2)
        `when`(block2.blockData).thenThrow(IllegalStateException("Chunk block state corrupted"))
        val event2 = BlockBreakEvent(block2, player)

        assertDoesNotThrow {
            fastPath.handle(event2, origRL)
        }

        // Must trip circuit breaker
        assertTrue(circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH))
        assertEquals(1L, counters.circuitTrips.sum())
        assertEquals(1L, counters.validationFailures.sum())

        // Pre-mutation fallback: must invoke original callback!
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event2))

        // Direct island mutation must NEVER be called!
        verify(island, never()).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())
    }

    @Test
    fun `cuboid wand breaks crossing chunk boundary with adjacent sign in neighboring chunk`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Block A: At chunk boundary x = 15, z = 0 (chunk 0, 0)
        // Has an adjacent sign at EAST face (which is x = 16, z = 0 in chunk 1, 0)
        val blockA = mockBlock(material = Material.STONE, x = 15, z = 0, neighborSignFace = BlockFace.EAST)
        val eventA = BlockBreakEvent(blockA, player)
        fastPath.handle(eventA, origRL)

        // Sign gate must detect the neighboring sign across the chunk boundary and fall back to original
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(eventA))
        assertEquals(1L, counters.signPassthroughs.sum())
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())

        // Block B: In chunk 0, 0 at x = 10, z = 0 with NO sign neighbors
        val blockB = mockBlock(material = Material.STONE, x = 10, z = 0)
        val eventB = BlockBreakEvent(blockB, player)
        fastPath.handle(eventB, origRL)

        // Block B successfully anchors chunk 0, 0!
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(eventB))
        assertEquals(1L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())

        // Block C: In chunk 1, 0 at x = 20, z = 0 with NO sign neighbors
        val blockC = mockBlock(material = Material.STONE, x = 20, z = 0)
        val eventC = BlockBreakEvent(blockC, player)
        fastPath.handle(eventC, origRL)

        // Block C successfully anchors chunk 1, 0!
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(eventC))
        assertEquals(2L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())

        // Block D: Second safe block in chunk 0, 0 at x = 11, z = 0
        val blockD = mockBlock(material = Material.STONE, x = 11, z = 0)
        val eventD = BlockBreakEvent(blockD, player)
        fastPath.handle(eventD, origRL)

        // Block D uses fast path!
        verify(mockExecutor, never()).execute(eq(origRL.listener), eq(eventD))
        verify(island, times(1)).handleBlockBreak(any(Key::class.java), eq(1), eq(3))
        assertEquals(2L, counters.monitorAnchors.sum())
        assertEquals(1L, counters.monitorFastPaths.sum())

        // Block E: Second safe block in chunk 1, 0 at x = 21, z = 0
        val blockE = mockBlock(material = Material.STONE, x = 21, z = 0)
        val eventE = BlockBreakEvent(blockE, player)
        fastPath.handle(eventE, origRL)

        // Block E uses fast path!
        verify(mockExecutor, never()).execute(eq(origRL.listener), eq(eventE))
        verify(island, times(2)).handleBlockBreak(any(Key::class.java), eq(1), eq(3))
        assertEquals(2L, counters.monitorAnchors.sum())
        assertEquals(2L, counters.monitorFastPaths.sum())
    }

    @Test
    fun `unloaded neighboring chunk sign gate refuses fast path without loading chunk and uses original callback`() {
        pushContext()
        val (origRL, mockExecutor) = createRegisteredListener()

        // Neighbor chunk (1, 0) is unloaded
        `when`(world.isChunkLoaded(1, 0)).thenReturn(false)

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = createClassifier { TestKey(it.type.name) },
            primaryThreadChecker = { isPrimaryThread },
            islandProvider = { island }
        )

        val player = mock(Player::class.java)

        // Block at chunk border x = 15, z = 5 (chunk 0, 0)
        // Neighbor EAST is at x = 16 (chunk 1, 0, which is unloaded)
        val block = mockBlock(material = Material.STONE, x = 15, y = 64, z = 5)
        val event = BlockBreakEvent(block, player)

        fastPath.handle(event, origRL)

        // Must fail closed to original callback
        verify(mockExecutor, times(1)).execute(eq(origRL.listener), eq(event))
        verify(island, never()).handleBlockBreak(any(Key::class.java), anyInt(), anyInt())
        assertEquals(1L, counters.signPassthroughs.sum(), "Unloaded neighbor chunk must be treated as sign passthrough")
        assertEquals(0L, counters.monitorAnchors.sum())
        assertEquals(0L, counters.monitorFastPaths.sum())

        // Block relative EAST must NOT have been queried across the unloaded chunk boundary!
        verify(block, never()).getRelative(BlockFace.EAST)
    }

    @Test
    fun `safeVanillaKey classifies vanilla blocks directly with zero ClassificationResult allocation`() {
        val classifier = VanillaBlockClassifier(
            targetClassName = targetClassName,
            keyProvider = { TestKey("STONE", "0") }
        )

        val block = mockBlock(material = Material.STONE, x = 10, y = 64, z = 10)
        val key = classifier.safeVanillaKey(block)

        assertNotNull(key, "Valid vanilla block must return non-null key directly")
        assertEquals("STONE", key?.globalKey)
        assertFalse(classifier.isSpawner(Material.STONE))
        assertTrue(classifier.isSpawner(Material.SPAWNER))
    }
}
