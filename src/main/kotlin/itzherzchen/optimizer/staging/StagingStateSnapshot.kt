package itzherzchen.optimizer.staging

import itzherzchen.optimizer.observe.Counters

/**
 * Programmatic state snapshot of island and runtime state before and after block break actions.
 * Used by Staging Equivalence Tooling to prove bit-for-bit equivalence between unoptimized baseline
 * and optimized execution paths.
 */
data class StagingStateSnapshot(
    val blockCounts: Map<String, Long> = emptyMap(),
    val stackedAmounts: Map<String, Int> = emptyMap(),
    val dirtyChunks: Set<Long> = emptySet(),
    val islandWorth: Long = 0L,
    val islandLevel: Long = 0L,
    val circuitTripped: Boolean = false,
    val counters: Counters.Snapshot? = null
) {
    /**
     * Compares this state (actual after optimized run) against expected state (oracle / baseline).
     */
    fun diff(expected: StagingStateSnapshot): StagingStateDiff {
        val blockCountMismatches = mutableMapOf<String, Pair<Long, Long>>() // Key -> (Actual, Expected)
        val allKeys = this.blockCounts.keys + expected.blockCounts.keys
        for (k in allKeys) {
            val act = this.blockCounts[k] ?: 0L
            val exp = expected.blockCounts[k] ?: 0L
            if (act != exp) {
                blockCountMismatches[k] = act to exp
            }
        }

        val stackMismatches = mutableMapOf<String, Pair<Int, Int>>() // Pos -> (Actual, Expected)
        val allStacks = this.stackedAmounts.keys + expected.stackedAmounts.keys
        for (pos in allStacks) {
            val act = this.stackedAmounts[pos] ?: 0
            val exp = expected.stackedAmounts[pos] ?: 0
            if (act != exp) {
                stackMismatches[pos] = act to exp
            }
        }

        // False clean check: Expected has dirty chunk, but Actual does not have it marked dirty.
        // This is a CRITICAL invariant violation: dirty save omitted, risking data loss.
        val falseCleanChunks = expected.dirtyChunks - this.dirtyChunks

        // Stale dirty check: Actual has dirty chunk marked, but Expected did not mark it.
        // This is a conservative deviation (extra save work), not a data loss risk.
        val staleDirtyChunks = this.dirtyChunks - expected.dirtyChunks

        val worthMismatched = this.islandWorth != expected.islandWorth
        val levelMismatched = this.islandLevel != expected.islandLevel

        val isEquivalent = blockCountMismatches.isEmpty() &&
                stackMismatches.isEmpty() &&
                falseCleanChunks.isEmpty() &&
                !worthMismatched &&
                !levelMismatched

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
            expectedLevel = expected.islandLevel
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
    val expectedLevel: Long = 0L
) {
    fun summary(): String {
        if (isEquivalent) {
            return if (staleDirtyChunks.isNotEmpty()) {
                "Equivalence confirmed (with ${staleDirtyChunks.size} conservative stale-dirty chunk saves)"
            } else {
                "Exact bit-for-bit equivalence confirmed"
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

        return issues.joinToString(" | ")
    }
}
