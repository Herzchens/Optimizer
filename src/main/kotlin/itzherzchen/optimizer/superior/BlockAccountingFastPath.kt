package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import com.bgsoftware.superiorskyblock.api.island.Island
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Slab
import org.bukkit.event.EventException
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.RegisteredListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Module B — SSBBlockAccountingFastPath (Plan.md Section 5.2).
 *
 * Provides a guarded, fail-closed fast path for SuperiorSkyblock2 MONITOR accounting callbacks:
 * - Classification: Accept only exact MaterialKey with matching globalKey and empty subKey.
 * - Spawner gate: All spawners bypass fast path and fall back to original callback.
 * - Sign gate: Block and its six orthogonal neighbors are inspected. Any sign bypasses fast path.
 * - Chunk coverage: Action-local tracker. First safe block in a chunk acts as anchor through original MONITOR callback.
 * - Subsequent safe blocks in covered chunk bypass redundant callback and invoke island.handleBlockBreak(key, amount, 3).
 * - Direct mutation invariant: If exception occurs during handleBlockBreak, NEVER retry original.
 */
class BlockAccountingFastPath(
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val actionContextStack: ActionContextStack,
    private val counters: Counters,
    private val classifier: VanillaBlockClassifier = VanillaBlockClassifier(),
    private val logger: Logger? = null,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            true
        }
    },
    private val islandProvider: (Location) -> Island? = { loc ->
        SuperiorSkyblockAPI.getGrid()?.getIslandAt(loc)
    },
    private val signChecker: (Material) -> Boolean = { material ->
        try {
            Tag.ALL_SIGNS.isTagged(material)
        } catch (_: Throwable) {
            // Standalone test / uninitialized Bukkit fallback: check material name
            material.name.contains("SIGN")
        }
    }
) {

    @Volatile
    var isEligible: Boolean = true

    private val orthogonalFaces = arrayOf(
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST,
        BlockFace.UP,
        BlockFace.DOWN
    )

    fun handle(event: BlockBreakEvent, original: RegisteredListener) {
        // 1. Thread check
        if (!primaryThreadChecker()) {
            counters.circuitTrips.increment()
            logger?.severe(
                "[Optimizer] Off-main-thread invocation in BlockAccountingFastPath on thread '${Thread.currentThread().name}'! Tripping circuit breaker and disarming."
            )
            circuitBreaker.trip(ModuleType.GLOBAL, "Off-main-thread invocation in BlockAccountingFastPath")
            callOriginal(original, event)
            return
        }

        // 2. Pre-condition checks: mode, config, circuit breaker, cancellation, module eligibility
        val currentMode = modeController.mode
        val isGlobalTripped = circuitBreaker.isTripped(ModuleType.GLOBAL)
        val isModuleTripped = circuitBreaker.isTripped(ModuleType.BLOCK_ACCOUNTING_FAST_PATH)

        if (currentMode != OperatingMode.ACTIVE ||
            !config.blockAccountingFastPathEnabled ||
            !isEligible ||
            isGlobalTripped ||
            isModuleTripped ||
            event.isCancelled
        ) {
            callOriginal(original, event)
            return
        }

        val context = actionContextStack.current()
        if (context == null || context.isClosed) {
            callOriginal(original, event)
            return
        }

        val block = event.block

        // 3. Island check
        val island: Island?
        try {
            island = islandProvider(block.location)
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            counters.validationFailures.increment()
            circuitBreaker.trip(
                ModuleType.BLOCK_ACCOUNTING_FAST_PATH,
                "Exception querying island at block location: ${t.message}",
                t
            )
            callOriginal(original, event)
            return
        }

        if (island == null) {
            // Block is not on an island: original MONITOR callback
            callOriginal(original, event)
            return
        }

        // 4. Sign gate: check block and 6 orthogonal neighbors
        if (isSignOrNearSign(block)) {
            counters.signPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 5. Vanilla block classification
        val classification = classifier.classify(block)
        when (classification.status) {
            ClassificationStatus.SPAWNER -> {
                counters.spawnerPassthroughs.increment()
                callOriginal(original, event)
                return
            }
            ClassificationStatus.CUSTOM_OR_NONSTANDARD -> {
                counters.customPassthroughs.increment()
                callOriginal(original, event)
                return
            }
            ClassificationStatus.SAFE_VANILLA -> {
                // Continue to chunk coverage evaluation
            }
        }

        val key = classification.key
        if (key == null) {
            counters.customPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 6. Chunk coverage evaluation
        val chunkX = block.x shr 4
        val chunkZ = block.z shr 4
        val world = block.world

        val isCovered = context.chunkCoverage.isCovered(world, chunkX, chunkZ, island)

        if (!isCovered) {
            // Anchor path: first block in this chunk uses original MONITOR callback
            callOriginal(original, event)

            // Mark chunk covered ONLY after original succeeds uncancelled
            if (!event.isCancelled) {
                val marked = context.chunkCoverage.markCovered(world, chunkX, chunkZ, island)
                if (marked) {
                    counters.monitorAnchors.increment()
                }
            }
            return
        }

        // 7. Fast path: subsequent block in covered chunk bypasses MONITOR callback
        val amount: Int
        try {
            amount = getBreakAmount(block)
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            counters.validationFailures.increment()
            logger?.log(
                Level.SEVERE,
                "[Optimizer] Exception determining break amount in BlockAccountingFastPath before mutation. Tripping circuit breaker.",
                t
            )
            circuitBreaker.trip(
                ModuleType.BLOCK_ACCOUNTING_FAST_PATH,
                "Exception determining break amount: ${t.message}",
                t
            )
            callOriginal(original, event)
            return
        }

        // DIRECT MUTATION BOUNDARY:
        // Once handleBlockBreak is invoked, never retry original if an exception occurs!
        try {
            island.handleBlockBreak(key, amount, 3)
            counters.monitorFastPaths.increment()
            counters.dirtyTasksAvoided.increment()
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            logger?.log(
                Level.SEVERE,
                "[Optimizer] Exception during direct island.handleBlockBreak in BlockAccountingFastPath! Tripping circuit breaker; NEVER retrying original.",
                t
            )
            circuitBreaker.trip(
                ModuleType.BLOCK_ACCOUNTING_FAST_PATH,
                "Exception during direct island.handleBlockBreak: ${t.message}",
                t
            )
            // INVARIANT: DO NOT RETRY ORIGINAL!
        }
    }

    private fun isSignOrNearSign(block: Block): Boolean {
        try {
            if (signChecker(block.type)) {
                return true
            }
            for (face in orthogonalFaces) {
                val neighborType = block.getRelative(face).type
                if (signChecker(neighborType)) {
                    return true
                }
            }
            return false
        } catch (_: Throwable) {
            // Unsafe or throwing material access: fail closed!
            return true
        }
    }

    private fun getBreakAmount(block: Block): Int {
        val blockData = block.blockData
        return if (blockData is Slab && blockData.type == Slab.Type.DOUBLE) {
            2
        } else {
            1
        }
    }

    private fun callOriginal(original: RegisteredListener, event: BlockBreakEvent) {
        original.callEvent(event)
    }
}
