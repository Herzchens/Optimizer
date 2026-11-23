package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import com.bgsoftware.superiorskyblock.api.island.Island
import com.bgsoftware.superiorskyblock.api.key.Key
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.observe.TransactionRiskScanner
import itzherzchen.optimizer.observe.TransactionRiskState
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
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.RegisteredListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Module B — SSBBlockAccountingFastPath (Plan.md Section 5.2).
 *
 * Provides a guarded, fail-closed fast path for SuperiorSkyblock2 MONITOR accounting callbacks:
 * - Classification: Accept only exact MaterialKey with matching globalKey and empty/zero subKey.
 * - Zero Allocation on Hot Path: safeVanillaKey and action-local reusable Location.
 * - Spawner gate: All spawners bypass fast path and fall back to original callback.
 * - Sign gate: Block and its six orthogonal neighbors are inspected without loading unloaded neighbor chunks.
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
    private val transactionRiskCheck: () -> TransactionRiskReport = TransactionRiskScanner(logger)::scan,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            false
        }
    },
    private val islandProvider: (Location) -> Island? = { loc ->
        SuperiorSkyblockAPI.getGrid()?.getIslandAt(loc)
    },
    private val signChecker: (Material) -> Boolean = { material ->
        try {
            Tag.ALL_SIGNS.isTagged(material) || material.name.contains("SIGN")
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
        // 1. Thread check: Fail-closed on unable to verify primary thread
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

        // Close the remaining LOWEST -> synthetic-accounting registration window.
        // This census executes once per ActionContext, immediately before Module B can
        // anchor or directly mutate accounting state. Subsequent blocks do not rescan.
        if (!context.moduleBTransactionRiskVerified) {
            val riskReport = try {
                transactionRiskCheck()
            } catch (t: Throwable) {
                isEligible = false
                counters.validationFailures.increment()
                logger?.log(
                    Level.WARNING,
                    "[Optimizer] Action-local Module B transaction-risk census threw before accounting. " +
                            "Module B monotonically disabled until restart.",
                    t
                )
                callOriginal(original, event)
                return
            }

            if (riskReport.state != TransactionRiskState.CLEAN) {
                isEligible = false
                counters.validationFailures.increment()
                logger?.warning(
                    "[Optimizer] Action-local Module B transaction-risk census refused optimization " +
                            "(${riskReport.state}: ${riskReport.riskSummary}). Module B monotonically disabled until restart."
                )
                callOriginal(original, event)
                return
            }

            context.moduleBTransactionRiskVerified = true
        }

        val block = event.block

        // 3. Island check (using action-local reusable Location without per-block allocation)
        val island: Island?
        try {
            val loc = try {
                block.getLocation(context.reusableLocation) ?: block.location
            } catch (_: Throwable) {
                block.location
            }
            island = islandProvider(loc)
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

        // 4. Sign gate: check block and 6 orthogonal neighbors, avoiding loading unloaded neighbor chunks
        val isSign: Boolean
        try {
            isSign = isSignOrNearSign(block)
        } catch (t: Throwable) {
            counters.signPassthroughs.increment()
            callOriginal(original, event)
            return
        }
        if (isSign) {
            counters.signPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 5. Spawner gate: zero-allocation spawner check
        val isSpawner: Boolean
        try {
            isSpawner = classifier.isSpawner(block.type)
        } catch (t: Throwable) {
            counters.spawnerPassthroughs.increment()
            callOriginal(original, event)
            return
        }
        if (isSpawner) {
            counters.spawnerPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 6. Vanilla block classification (zero allocation: safeVanillaKey returns Key? directly)
        val key: Key?
        try {
            key = classifier.safeVanillaKey(block)
        } catch (t: Throwable) {
            counters.customPassthroughs.increment()
            callOriginal(original, event)
            return
        }
        if (key == null) {
            counters.customPassthroughs.increment()
            callOriginal(original, event)
            return
        }

        // 7. Chunk coverage evaluation
        val chunkX = block.x shr 4
        val chunkZ = block.z shr 4
        val world = block.world

        val isCovered: Boolean
        try {
            isCovered = context.chunkCoverage.isCovered(world, chunkX, chunkZ, island)
        } catch (t: Throwable) {
            counters.circuitTrips.increment()
            counters.validationFailures.increment()
            circuitBreaker.trip(
                ModuleType.BLOCK_ACCOUNTING_FAST_PATH,
                "Exception checking chunk coverage: ${t.message}",
                t
            )
            callOriginal(original, event)
            return
        }

        if (!isCovered) {
            // Anchor path: first block in this chunk uses original MONITOR callback
            callOriginal(original, event)

            // Mark chunk covered ONLY after original succeeds uncancelled
            if (!event.isCancelled) {
                try {
                    val marked = context.chunkCoverage.markCovered(world, chunkX, chunkZ, island)
                    if (marked) {
                        counters.monitorAnchors.increment()
                    }
                } catch (_: Throwable) {
                    // Ignore coverage marking exception post-call
                }
            }
            return
        }

        // 8. Fast path: subsequent block in covered chunk bypasses MONITOR callback
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

            val world = block.world
            val bx = block.x
            val by = block.y
            val bz = block.z
            val blockChunkX = bx shr 4
            val blockChunkZ = bz shr 4

            val minY = try {
                val h = world.minHeight
                if (h == 0 && world.maxHeight == 0) -64 else h
            } catch (_: Throwable) {
                -64
            }
            val maxY = try {
                val h = world.maxHeight
                if (h <= 0) 320 else h
            } catch (_: Throwable) {
                320
            }

            for (face in orthogonalFaces) {
                val targetY = by + face.modY
                if (targetY < minY || targetY >= maxY) {
                    // Out-of-world vertical bounds: cannot be a valid sign
                    continue
                }

                val targetX = bx + face.modX
                val targetZ = bz + face.modZ
                val targetChunkX = targetX shr 4
                val targetChunkZ = targetZ shr 4

                if (targetChunkX != blockChunkX || targetChunkZ != blockChunkZ) {
                    val isLoaded = try {
                        world.isChunkLoaded(targetChunkX, targetChunkZ)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!isLoaded) {
                        // Neighbor chunk across chunk border is not loaded!
                        // Fail closed: do NOT synchronously load chunk, treat as sign/passthrough.
                        return true
                    }
                }

                val neighbor = block.getRelative(face)
                if (signChecker(neighbor.type)) {
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
