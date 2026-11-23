package itzherzchen.optimizer.staging

/**
 * Rollout validation decisions for optimizer staging gates.
 *
 * Implements Plan.md Section 8 & Section 9:
 * - v0.1 OBSERVE gate: Scaffolding/observe validated, no WT mutation.
 * - v0.2 ACTIVE A gate: StackedFastReject approved, stacked x2/x64 and protection equivalence verified.
 * - v0.3 ACTIVE B gate: BlockAccountingFastPath approved, full equivalence matrix verified.
 * - NO_GO: Any count drift, worth drift, protection bypass, dupe, or false-clean blocks rollout.
 */
enum class RolloutDecision {
    GO_V03_ACTIVE_B,
    GO_V02_ACTIVE_A,
    GO_V01_OBSERVE,
    NO_GO
}

/**
 * The 8 core equivalence matrix groups defined in Plan.md Section 8:
 * 1. CUBOID: Stone, double slab (amount=2), level 3/7/10 (~1000 blocks).
 * 2. PROTECTION: SSB DENY, WorldGuard DENY, synthetic/root cancellation.
 * 3. STACKING: x2, x64, amount 1 fast reject.
 * 4. SIGNS_AND_CUSTOM: normal/wall/hanging/warp sign, ItemsAdder block, normal + RoseStacker spawner.
 * 5. COORDINATES: chunk crossings, negative coordinates, island boundaries / wilderness.
 * 6. LIFECYCLE: disconnect/kick, /tools reload list drift, SSB reload, plugin disable/enable, classloader changes.
 * 7. TRANSACTIONS: cancelled CuboidWandUseEvent, XP before physical apply census.
 * 8. FAILURE: exceptions before mutation (original called), exceptions in handleBlockBreak (never retry original), modified topology.
 */
enum class MatrixGroup(val title: String, val description: String) {
    CUBOID("Cuboid Workload", "Stone, double slab (x2 accounting), multi-scale bounds up to ~1,000 blocks"),
    PROTECTION("Protection & Cancellation", "SSB DENY, WorldGuard DENY, synthetic and root event cancellation"),
    STACKING("Block Stacking", "Stacked blocks x2 and x64 passthrough, stack amount 1 verified no-op skip"),
    SIGNS_AND_CUSTOM("Signs & Non-Standard Blocks", "Normal/wall/hanging/warp signs, ItemsAdder custom keys, vanilla and RoseStacker spawners"),
    COORDINATES("Spatial & Chunk Boundaries", "Chunk crossings, negative chunk coordinates, island to wilderness boundaries"),
    LIFECYCLE("Runtime Lifecycle & Drift", "Disconnects, /tools reload list drift preservation, SSB reload, plugin disable/enable"),
    TRANSACTIONS("Transaction Risk Census", "CuboidWandUseEvent external listener census, wand cancellation, XP accounting order"),
    FAILURE("Fault Injection & Fail-Closed", "Pre-mutation fallback, direct mutation exception circuit break without retry, topology drift")
}

enum class TestStatus {
    PASS,
    WARN,
    FAIL
}

data class TestCaseResult(
    val caseId: String,
    val name: String,
    val group: MatrixGroup,
    val status: TestStatus,
    val details: String,
    val executionTimeMs: Long = 0,
    val diff: StagingStateDiff? = null
)

data class GroupResult(
    val group: MatrixGroup,
    val status: TestStatus,
    val passedCount: Int,
    val totalCount: Int,
    val caseResults: List<TestCaseResult>
)

data class RolloutReport(
    val decision: RolloutDecision,
    val summary: String,
    val groupResults: Map<MatrixGroup, GroupResult>,
    val totalTests: Int,
    val passedTests: Int,
    val warningTests: Int,
    val failedTests: Int,
    val totalTimeMs: Long
)
