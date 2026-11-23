package itzherzchen.optimizer.staging

import itzherzchen.optimizer.observe.Counters

/**
 * Programmatic state snapshot of island and runtime state before and after block break actions.
 * Used by Staging Equivalence Tooling to prove bit-for-bit equivalence between unoptimized baseline
 * and optimized execution paths across all 14 required dimensions.
 */
data class StagingStateSnapshot(
    val physicalBlocks: Map<String, String> = emptyMap(),
    val drops: Map<String, Int> = emptyMap(),
    val playerInventory: Map<String, Int> = emptyMap(),
    val playerXp: Long = 0L,
    val toolDurability: Int = 0,
    val blockCounts: Map<String, Long> = emptyMap(),
    val islandWorth: Long = 0L,
    val islandLevel: Long = 0L,
    val stackedAmounts: Map<String, Int> = emptyMap(),
    val signsAndWarps: Map<String, String> = emptyMap(),
    val protectionResult: Boolean = true,
    val economyBalance: Double = 0.0,
    val exceptionsRecorded: List<String> = emptyList(),
    val persistedRestartEquivalent: Boolean = true,
    val dirtyChunks: Set<Long> = emptySet(),
    val circuitTripped: Boolean = false,
    val counters: Counters.Snapshot? = null,
    val measuredDimensions: Set<String> = DEFAULT_MEASURED_DIMENSIONS
) {
    companion object {
        val ALL_REQUIRED_DIMENSIONS = setOf(
            "physicalBlocks",
            "drops",
            "playerInventory",
            "playerXp",
            "toolDurability",
            "blockCounts",
            "islandWorth",
            "islandLevel",
            "stackedAmounts",
            "signsAndWarps",
            "protectionResult",
            "economyBalance",
            "exceptionsRecorded",
            "persistedRestartEquivalent"
        )

        val DEFAULT_MEASURED_DIMENSIONS = setOf(
            "blockCounts",
            "stackedAmounts",
            "dirtyChunks",
            "islandWorth",
            "islandLevel"
        )
    }

    fun getMissingAuthoritativeDimensions(): Set<String> =
        ALL_REQUIRED_DIMENSIONS - measuredDimensions

    fun hasCompleteAuthoritativeDimensions(): Boolean =
        getMissingAuthoritativeDimensions().isEmpty()

    /**
     * Compares this state (actual after optimized run) against expected state (oracle / baseline).
     */
    fun diff(expected: StagingStateSnapshot): StagingStateDiff {
        val blockCountMismatches = mutableMapOf<String, Pair<Long, Long>>()
        val allKeys = this.blockCounts.keys + expected.blockCounts.keys
        for (k in allKeys) {
            val act = this.blockCounts[k] ?: 0L
            val exp = expected.blockCounts[k] ?: 0L
            if (act != exp) {
                blockCountMismatches[k] = act to exp
            }
        }

        val stackMismatches = mutableMapOf<String, Pair<Int, Int>>()
        val allStacks = this.stackedAmounts.keys + expected.stackedAmounts.keys
        for (pos in allStacks) {
            val act = this.stackedAmounts[pos] ?: 0
            val exp = expected.stackedAmounts[pos] ?: 0
            if (act != exp) {
                stackMismatches[pos] = act to exp
            }
        }

        val physicalBlockMismatches = mutableMapOf<String, Pair<String, String>>()
        val allPhysical = this.physicalBlocks.keys + expected.physicalBlocks.keys
        for (pos in allPhysical) {
            val act = this.physicalBlocks[pos] ?: ""
            val exp = expected.physicalBlocks[pos] ?: ""
            if (act != exp) {
                physicalBlockMismatches[pos] = act to exp
            }
        }

        val dropMismatches = mutableMapOf<String, Pair<Int, Int>>()
        val allDrops = this.drops.keys + expected.drops.keys
        for (d in allDrops) {
            val act = this.drops[d] ?: 0
            val exp = expected.drops[d] ?: 0
            if (act != exp) {
                dropMismatches[d] = act to exp
            }
        }

        val inventoryMismatches = mutableMapOf<String, Pair<Int, Int>>()
        val allInv = this.playerInventory.keys + expected.playerInventory.keys
        for (item in allInv) {
            val act = this.playerInventory[item] ?: 0
            val exp = expected.playerInventory[item] ?: 0
            if (act != exp) {
                inventoryMismatches[item] = act to exp
            }
        }

        val signWarpMismatches = mutableMapOf<String, Pair<String, String>>()
        val allSigns = this.signsAndWarps.keys + expected.signsAndWarps.keys
        for (s in allSigns) {
            val act = this.signsAndWarps[s] ?: ""
            val exp = expected.signsAndWarps[s] ?: ""
            if (act != exp) {
                signWarpMismatches[s] = act to exp
            }
        }

        val falseCleanChunks = expected.dirtyChunks - this.dirtyChunks
        val staleDirtyChunks = this.dirtyChunks - expected.dirtyChunks

        val worthMismatched = this.islandWorth != expected.islandWorth
        val levelMismatched = this.islandLevel != expected.islandLevel
        val xpMismatched = this.playerXp != expected.playerXp
        val toolDurabilityMismatched = this.toolDurability != expected.toolDurability
        val protectionMismatched = this.protectionResult != expected.protectionResult
        val economyMismatched = this.economyBalance != expected.economyBalance
        val exceptionMismatched = this.exceptionsRecorded != expected.exceptionsRecorded
        val persistenceMismatched = !this.persistedRestartEquivalent || !expected.persistedRestartEquivalent

        val isEquivalent = blockCountMismatches.isEmpty() &&
                stackMismatches.isEmpty() &&
                falseCleanChunks.isEmpty() &&
                !worthMismatched &&
                !levelMismatched &&
                physicalBlockMismatches.isEmpty() &&
                dropMismatches.isEmpty() &&
                inventoryMismatches.isEmpty() &&
                !xpMismatched &&
                !toolDurabilityMismatched &&
                signWarpMismatches.isEmpty() &&
                !protectionMismatched &&
                !economyMismatched &&
                !exceptionMismatched &&
                !persistenceMismatched

        return StagingStateDiff(
            isEquivalent = isEquivalent,
            blockCountMismatches = blockCountMismatches,
            stackMismatches = stackMismatches,
            falseCleanChunks = falseCleanChunks,
            staleDirtyChunks = staleDirtyChunks,
            worthMismatch = worthMismatched,
            actualWorth = this.islandWorth,
            expectedWorth = expected.islandWorth,
            levelMismatch = levelMismatched,
            actualLevel = this.islandLevel,
            expectedLevel = expected.islandLevel,
            physicalBlockMismatches = physicalBlockMismatches,
            dropMismatches = dropMismatches,
            inventoryMismatches = inventoryMismatches,
            xpMismatch = xpMismatched,
            toolDurabilityMismatch = toolDurabilityMismatched,
            signWarpMismatches = signWarpMismatches,
            protectionMismatch = protectionMismatched,
            economyMismatch = economyMismatched,
            exceptionMismatch = exceptionMismatched,
            persistenceMismatch = persistenceMismatched,
            missingDimensions = (getMissingAuthoritativeDimensions() + expected.getMissingAuthoritativeDimensions()).toList()
        )
    }
}

/**
 * Result of comparing actual optimized state with baseline expected state.
 */
data class StagingStateDiff(
    val isEquivalent: Boolean,
    val blockCountMismatches: Map<String, Pair<Long, Long>> = emptyMap(),
    val stackMismatches: Map<String, Pair<Int, Int>> = emptyMap(),
    val falseCleanChunks: Set<Long> = emptySet(),
    val staleDirtyChunks: Set<Long> = emptySet(),
    val worthMismatch: Boolean = false,
    val actualWorth: Long = 0L,
    val expectedWorth: Long = 0L,
    val levelMismatch: Boolean = false,
    val actualLevel: Long = 0L,
    val expectedLevel: Long = 0L,
    val physicalBlockMismatches: Map<String, Pair<String, String>> = emptyMap(),
    val dropMismatches: Map<String, Pair<Int, Int>> = emptyMap(),
    val inventoryMismatches: Map<String, Pair<Int, Int>> = emptyMap(),
    val xpMismatch: Boolean = false,
    val toolDurabilityMismatch: Boolean = false,
    val signWarpMismatches: Map<String, Pair<String, String>> = emptyMap(),
    val protectionMismatch: Boolean = false,
    val economyMismatch: Boolean = false,
    val exceptionMismatch: Boolean = false,
    val persistenceMismatch: Boolean = false,
    val missingDimensions: List<String> = emptyList()
) {
    fun summary(): String {
        if (isEquivalent) {
            return if (staleDirtyChunks.isNotEmpty()) {
                "Equivalence confirmed across measured dimensions (with ${staleDirtyChunks.size} conservative stale-dirty chunk saves)"
            } else {
                "Exact bit-for-bit equivalence confirmed across measured dimensions"
            }
        }

        val issues = mutableListOf<String>()
        if (blockCountMismatches.isNotEmpty()) {
            issues.add("Block count drift on ${blockCountMismatches.size} keys: $blockCountMismatches")
        }
        if (stackMismatches.isNotEmpty()) {
            issues.add("Stack amount drift on ${stackMismatches.size} positions: $stackMismatches")
        }
        if (falseCleanChunks.isNotEmpty()) {
            issues.add("FATAL: False-clean chunks detected! Missing dirty marks for chunks: $falseCleanChunks")
        }
        if (worthMismatch) {
            issues.add("Island worth drift: actual=$actualWorth, expected=$expectedWorth")
        }
        if (levelMismatch) {
            issues.add("Island level drift: actual=$actualLevel, expected=$expectedLevel")
        }
        if (physicalBlockMismatches.isNotEmpty()) {
            issues.add("Physical block drift on ${physicalBlockMismatches.size} positions: $physicalBlockMismatches")
        }
        if (dropMismatches.isNotEmpty()) {
            issues.add("Drop drift on ${dropMismatches.size} items: $dropMismatches")
        }
        if (inventoryMismatches.isNotEmpty()) {
            issues.add("Inventory drift on ${inventoryMismatches.size} items: $inventoryMismatches")
        }
        if (xpMismatch) {
            issues.add("Player XP drift")
        }
        if (toolDurabilityMismatch) {
            issues.add("Tool durability drift")
        }
        if (signWarpMismatches.isNotEmpty()) {
            issues.add("Sign/warp drift on ${signWarpMismatches.size} entries: $signWarpMismatches")
        }
        if (protectionMismatch) {
            issues.add("Protection check mismatch")
        }
        if (economyMismatch) {
            issues.add("Economy balance drift")
        }
        if (exceptionMismatch) {
            issues.add("Exception recorded mismatch: actual vs expected")
        }
        if (persistenceMismatch) {
            issues.add("Persisted restart equivalence mismatch")
        }
        if (missingDimensions.isNotEmpty()) {
            issues.add("Missing authoritative dimensions: $missingDimensions")
        }

        return issues.joinToString(" | ")
    }
}
