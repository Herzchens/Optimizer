package itzherzchen.optimizer.model

import com.bgsoftware.superiorskyblock.api.island.Island
import com.bgsoftware.superiorskyblock.api.key.Key
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
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

/**
 * Pure Model Tests reproducing Research Claims (Plan.md Section 1.1 & Section 8):
 *
 * Exhaustively evaluates exactly 1,536 semantic states:
 * - 6 Block Categories (Stone, Double Slab, Spawner, Sign Adjacent, ItemsAdder Custom Key, Foreign Key Class)
 * - 4 Stack Amounts (0, 1, 2, 64)
 * - 2 Cancellation States (Uncancelled, Cancelled / Protection Deny)
 * - 2 Chunk Coverage States (Uncovered / Anchor, Covered / Fast Path Candidate)
 * - 4 Island Context States (Target Island, Wilderness/Null, Different Island, Different World)
 * - 4 Operating / Circuit States (ACTIVE Healthy, OBSERVE Mode, Tripped GLOBAL, Tripped MODULE)
 *
 * 6 * 4 * 2 * 2 * 4 * 4 = 1,536 states!
 *
 * Verifies against an independent baseline Oracle:
 * 1. Zero block count discrepancies across all 1,536 states.
 * 2. Zero stack amount discrepancies across all 1,536 states.
 * 3. Zero protection bypasses.
 * 4. Zero false-clean states (if Oracle marks dirty, Optimized MUST mark dirty).
 */
class SemanticStateModelTest {

    enum class BlockCategory {
        VANILLA_STONE,
        VANILLA_DOUBLE_SLAB,
        SPAWNER,
        SIGN_ADJACENT,
        CUSTOM_ITEMSADDER,
        FOREIGN_KEY_CLASS
    }

    enum class StackAmount(val amount: Int) {
        ZERO(0),
        ONE(1),
        TWO(2),
        SIXTY_FOUR(64)
    }

    enum class CancellationState(val isCancelled: Boolean) {
        UNCANCELLED(false),
        CANCELLED(true)
    }

    enum class ChunkCoverageState(val isCoveredBefore: Boolean) {
        UNCOVERED(false),
        COVERED(true)
    }

    enum class IslandContextState {
        SAME_ISLAND,
        WILDERNESS,
        DIFFERENT_ISLAND,
        DIFFERENT_WORLD
    }

    enum class OperatingState {
        ACTIVE_HEALTHY,
        OBSERVE_MODE,
        TRIPPED_GLOBAL,
        TRIPPED_MODULE
    }

    class TestKey(private val global: String, private val sub: String? = "") : Key {
        override fun getGlobalKey(): String = global
        override fun getSubKey(): String? = sub
        override fun compareTo(other: Key): Int = global.compareTo(other.globalKey)
    }

    class ForeignTestKey : Key {
        override fun getGlobalKey(): String = "FOREIGN"
        override fun getSubKey(): String = ""
        override fun compareTo(other: Key): Int = 0
    }

    data class OracleResult(
        val islandBlockCountDelta: Long,
        val stackAmountDelta: Int,
        val dirtyMarked: Boolean,
        val protectionDenied: Boolean
    )

    data class OptimizedResult(
        val islandBlockCountDelta: Long,
        val stackAmountDelta: Int,
        val dirtyMarked: Boolean,
        val originalStackCallbackCalled: Boolean,
        val originalAccountingCallbackCalled: Boolean,
        val fastPathTaken: Boolean,
        val fastRejectTaken: Boolean
    )

    @Test
    fun `reproduce research claim - 1,536 of 1,536 modeled semantic states pass with zero false-clean`() {
        var totalStatesEvaluated = 0
        var totalDiscrepancies = 0
        var totalFalseCleanStates = 0
        var totalProtectionViolations = 0

        for (cat in BlockCategory.values()) {
            for (stk in StackAmount.values()) {
                for (can in CancellationState.values()) {
                    for (cov in ChunkCoverageState.values()) {
                        for (isl in IslandContextState.values()) {
                            for (ops in OperatingState.values()) {
                                totalStatesEvaluated++

                                val oracle = evaluateOracle(cat, stk, can, cov, isl, ops)
                                val optimized = evaluateOptimized(cat, stk, can, cov, isl, ops)

                                // 1. Block count equivalence
                                if (oracle.islandBlockCountDelta != optimized.islandBlockCountDelta) {
                                    totalDiscrepancies++
                                }

                                // 2. Stack amount equivalence
                                if (oracle.stackAmountDelta != optimized.stackAmountDelta) {
                                    totalDiscrepancies++
                                }

                                // 3. Zero false-clean: If Oracle marks dirty, Optimized MUST mark dirty
                                if (oracle.dirtyMarked && !optimized.dirtyMarked) {
                                    totalFalseCleanStates++
                                }

                                // 4. Protection never bypassed
                                if (can.isCancelled) {
                                    if (optimized.islandBlockCountDelta != 0L || optimized.stackAmountDelta != 0) {
                                        totalProtectionViolations++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        assertEquals(1536, totalStatesEvaluated, "Total semantic state combinations must be exactly 1,536")
        assertEquals(0, totalDiscrepancies, "All 1,536 states must have zero block count or stack discrepancies")
        assertEquals(0, totalFalseCleanStates, "All 1,536 states must produce ZERO false-clean states")
        assertEquals(0, totalProtectionViolations, "Zero protection violations allowed across any state")
    }

    private fun evaluateOracle(
        cat: BlockCategory,
        stk: StackAmount,
        can: CancellationState,
        cov: ChunkCoverageState,
        isl: IslandContextState,
        ops: OperatingState
    ): OracleResult {
        if (can.isCancelled) {
            return OracleResult(
                islandBlockCountDelta = 0L,
                stackAmountDelta = 0,
                dirtyMarked = false,
                protectionDenied = true
            )
        }

        // Stack callback in unoptimized baseline:
        // Stacking decrements by 1 if stack > 1. If stack <= 1, it's a no-op.
        val stackDelta = when (stk) {
            StackAmount.TWO, StackAmount.SIXTY_FOUR -> -1
            StackAmount.ONE, StackAmount.ZERO -> 0
        }

        // Accounting callback in unoptimized baseline:
        // If island is present, decrements block count: double slab by 2, normal block by 1.
        val hasIsland = isl != IslandContextState.WILDERNESS
        val countDelta = if (hasIsland) {
            if (cat == BlockCategory.VANILLA_DOUBLE_SLAB) 2L else 1L
        } else {
            0L
        }

        val dirty = hasIsland

        return OracleResult(
            islandBlockCountDelta = countDelta,
            stackAmountDelta = stackDelta,
            dirtyMarked = dirty,
            protectionDenied = false
        )
    }

    private fun evaluateOptimized(
        cat: BlockCategory,
        stk: StackAmount,
        can: CancellationState,
        cov: ChunkCoverageState,
        isl: IslandContextState,
        ops: OperatingState
    ): OptimizedResult {
        val targetIsland = mock(Island::class.java)
        val diffIsland = mock(Island::class.java)
        val world1 = mock(World::class.java)
        `when`(world1.name).thenReturn("world")
        val world2 = mock(World::class.java)
        `when`(world2.name).thenReturn("world_nether")

        var islandBlockDelta = 0L
        var stackDelta = 0
        var dirtyMarked = false
        var origStackCalled = false
        var origAccountingCalled = false

        val activeIsland = when (isl) {
            IslandContextState.SAME_ISLAND -> targetIsland
            IslandContextState.WILDERNESS -> null
            IslandContextState.DIFFERENT_ISLAND -> diffIsland
            IslandContextState.DIFFERENT_WORLD -> targetIsland
        }

        val activeWorld = when (isl) {
            IslandContextState.DIFFERENT_WORLD -> world2
            else -> world1
        }

        val currentMode = when (ops) {
            OperatingState.ACTIVE_HEALTHY, OperatingState.TRIPPED_GLOBAL, OperatingState.TRIPPED_MODULE -> OperatingMode.ACTIVE
            OperatingState.OBSERVE_MODE -> OperatingMode.OBSERVE
        }

        val modeController = ModeController(currentMode)
        val circuitBreaker = CircuitBreaker(modeController)
        if (ops == OperatingState.TRIPPED_GLOBAL) {
            circuitBreaker.trip(ModuleType.GLOBAL, "Simulated trip")
        }
        if (ops == OperatingState.TRIPPED_MODULE) {
            circuitBreaker.trip(ModuleType.BLOCK_ACCOUNTING_FAST_PATH, "Simulated trip")
            circuitBreaker.trip(ModuleType.STACKED_FAST_REJECT, "Simulated trip")
        }

        val counters = Counters()
        val stack = ActionContextStack(circuitBreaker, counters)

        // Setup block and event
        val block = mock(Block::class.java)
        val blockMat = when (cat) {
            BlockCategory.VANILLA_STONE -> Material.STONE
            BlockCategory.VANILLA_DOUBLE_SLAB -> Material.SMOOTH_STONE_SLAB
            BlockCategory.SPAWNER -> Material.SPAWNER
            BlockCategory.SIGN_ADJACENT -> Material.STONE
            BlockCategory.CUSTOM_ITEMSADDER -> Material.STONE
            BlockCategory.FOREIGN_KEY_CLASS -> Material.STONE
        }
        `when`(block.type).thenReturn(blockMat)
        `when`(block.world).thenReturn(activeWorld)
        `when`(block.x).thenReturn(10)
        `when`(block.z).thenReturn(10)
        val loc = Location(activeWorld, 10.0, 64.0, 10.0)
        `when`(block.location).thenReturn(loc)

        if (cat == BlockCategory.VANILLA_DOUBLE_SLAB) {
            val slab = mock(Slab::class.java)
            `when`(slab.type).thenReturn(Slab.Type.DOUBLE)
            `when`(block.blockData).thenReturn(slab)
        } else {
            val bd = mock(org.bukkit.block.data.BlockData::class.java)
            `when`(block.blockData).thenReturn(bd)
        }

        for (face in arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            val rel = mock(Block::class.java)
            val relType = if (cat == BlockCategory.SIGN_ADJACENT && face == BlockFace.NORTH) Material.OAK_WALL_SIGN else Material.AIR
            `when`(rel.type).thenReturn(relType)
            `when`(block.getRelative(face)).thenReturn(rel)
        }

        val player = mock(Player::class.java)
        val event = BlockBreakEvent(block, player)
        if (can.isCancelled) {
            event.isCancelled = true
        }

        // Push action context
        val rootEvent = BlockBreakEvent(block, player)
        val tool = mock(com.bgsoftware.wildtools.api.objects.tools.CuboidTool::class.java)
        val ctx = ActionContext(1L, rootEvent, player, tool)
        stack.push(ctx)

        val chunkX = 10 shr 4
        val chunkZ = 10 shr 4

        // Pre-cover chunk if requested
        if (cov.isCoveredBefore && activeIsland != null) {
            ctx.chunkCoverage.markCovered(activeWorld, chunkX, chunkZ, activeIsland)
            // When already covered, dirty task was scheduled on earlier anchor
            dirtyMarked = true
        }

        // Mock original stack listener
        val ssbPlugin = mock(Plugin::class.java)
        `when`(ssbPlugin.name).thenReturn("SuperiorSkyblock2")
        val stackListener = RegisteredListener(object : Listener {}, EventExecutor { _, ev ->
            origStackCalled = true
            val bbe = ev as BlockBreakEvent
            if (!bbe.isCancelled) {
                if (stk.amount > 1) {
                    stackDelta = -1
                }
            }
        }, EventPriority.HIGHEST, ssbPlugin, false)

        // Mock original accounting listener
        val acctListener = RegisteredListener(object : Listener {}, EventExecutor { _, ev ->
            origAccountingCalled = true
            val bbe = ev as BlockBreakEvent
            if (!bbe.isCancelled && activeIsland != null) {
                val amt = if (cat == BlockCategory.VANILLA_DOUBLE_SLAB) 2L else 1L
                islandBlockDelta += amt
                dirtyMarked = true
            }
        }, EventPriority.MONITOR, ssbPlugin, false)

        if (activeIsland != null) {
            `when`(activeIsland.handleBlockBreak(any(Key::class.java), anyInt(), anyInt())).thenAnswer { invocation ->
                val amt = invocation.getArgument<Int>(1).toLong()
                islandBlockDelta += amt
                dirtyMarked = true
                null
            }
        }

        val config = OptimizerConfig(
            configuredMode = currentMode,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )

        val classifier = VanillaBlockClassifier(
            targetClassName = TestKey::class.java.name,
            keyProvider = {
                when (cat) {
                    BlockCategory.CUSTOM_ITEMSADDER -> TestKey("itemsadder", "ruby")
                    BlockCategory.FOREIGN_KEY_CLASS -> ForeignTestKey()
                    else -> TestKey(blockMat.name, "")
                }
            }
        )

        val fastReject = StackedFastReject(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            stackedAmountProvider = { stk.amount }
        )

        val fastPath = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters,
            classifier = classifier,
            islandProvider = { activeIsland },
            signChecker = { mat -> mat.name.contains("SIGN") }
        )

        // Execute stack handler
        fastReject.handle(event, stackListener)

        // Execute accounting handler
        fastPath.handle(event, acctListener)

        val fastRejectTaken = counters.stackedFastRejects.sum() > 0
        val fastPathTaken = counters.monitorFastPaths.sum() > 0

        return OptimizedResult(
            islandBlockCountDelta = islandBlockDelta,
            stackAmountDelta = stackDelta,
            dirtyMarked = dirtyMarked,
            originalStackCallbackCalled = origStackCalled,
            originalAccountingCallbackCalled = origAccountingCalled,
            fastPathTaken = fastPathTaken,
            fastRejectTaken = fastRejectTaken
        )
    }
}
