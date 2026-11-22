package itzherzchen.optimizer.runtime

import com.bgsoftware.superiorskyblock.api.island.Island
import org.bukkit.World

/**
 * Action-local chunk coverage tracker.
 *
 * Implements Plan.md Section 5.2:
 * - action-local LongArray(16), Array<Island?>(16), Array<World?>(16), count.
 * - key: (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL).
 * - Linear lookup matching island identity, world identity, and chunk coordinates.
 * - Handles negative coordinates correctly.
 * - Overflow fallback: if count reaches capacity (16), cannot anchor further chunks,
 *   safely falling back to original callback.
 * - Zero per-block heap allocation.
 */
class ChunkCoverage(
    private val capacity: Int = 16
) {
    private val chunkKeys = LongArray(capacity)
    private val islands = arrayOfNulls<Island>(capacity)
    private val worlds = arrayOfNulls<World>(capacity)
    var count: Int = 0
        private set

    companion object {
        fun computeKey(chunkX: Int, chunkZ: Int): Long {
            return (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)
        }
    }

    /**
     * Checks if the given chunk coordinates, world, and island have already been anchored in this action.
     */
    fun isCovered(world: World, chunkX: Int, chunkZ: Int, island: Island): Boolean {
        val key = computeKey(chunkX, chunkZ)
        for (i in 0 until count) {
            if (chunkKeys[i] == key && islands[i] === island && worlds[i] === world) {
                return true
            }
        }
        return false
    }

    /**
     * Marks the given chunk as covered for this action after the anchor callback succeeds.
     * Returns true if successfully marked, or false if capacity is reached (overflow fallback).
     */
    fun markCovered(world: World, chunkX: Int, chunkZ: Int, island: Island): Boolean {
        if (isCovered(world, chunkX, chunkZ, island)) {
            return true
        }
        if (count >= capacity) {
            // Capacity reached; overflow fallback: cannot anchor more chunks
            return false
        }
        val key = computeKey(chunkX, chunkZ)
        chunkKeys[count] = key
        islands[count] = island
        worlds[count] = world
        count++
        return true
    }

    /**
     * Resets the tracker and clears all references to prevent memory retention.
     */
    fun reset() {
        for (i in 0 until count) {
            islands[i] = null
            worlds[i] = null
        }
        count = 0
    }
}
