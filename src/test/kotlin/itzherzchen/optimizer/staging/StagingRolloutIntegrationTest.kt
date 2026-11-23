package itzherzchen.optimizer.staging

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Integration Test for Staging Verification & Rollout Validation Tooling (Plan.md Section 8 & Section 9).
 *
 * Verifies that the Staging Equivalence Matrix:
 * - Executes all 8 matrix groups completely
 * - Accurately proves equivalence and issues GO_V03_ACTIVE_B
 * - Correctly aborts with NO_GO upon any protection or mutation safety violation
 * - Operates safely in headless CI mode with stubs
 */
class StagingRolloutIntegrationTest {

    @Test
    fun `complete 8-group simulated equivalence matrix produces MODEL_PASS and strictly refuses rollout approval`() {
        val validator = RolloutValidator(customRunner = StagingEquivalenceMatrixRunner())
        val report = validator.runValidationMatrix()

        val failedCases = report.groupResults.values.flatMap { it.caseResults }.filter { it.status == TestStatus.FAIL }
        assertTrue(failedCases.isEmpty(), "Failed cases: " + failedCases.map { "${it.caseId}: ${it.details}" })

        assertEquals(RolloutDecision.MODEL_PASS, report.decision, "Simulated matrix must issue MODEL_PASS, never production approval")
        assertEquals("PASS", report.modelValidationStatus)
        assertEquals("PASS", report.runtimeReadinessStatus)
        assertEquals("NOT RUN (SIMULATION ONLY)", report.realStagingEquivalenceStatus)
        assertEquals("NOT GRANTED (SIMULATION CANNOT APPROVE ROLLOUT)", report.rolloutApprovalStatus)
        assertEquals(0, report.failedTests, "Zero tests should fail in clean equivalence run")
        assertTrue(report.totalTests >= 24, "Should evaluate at least 24 distinct test cases across 8 groups")

        for (group in MatrixGroup.values()) {
            val groupResult = report.groupResults[group]
            assertNotNull(groupResult, "Result for group $group must exist")
            assertTrue(groupResult!!.status == TestStatus.PASS || groupResult.status == TestStatus.READY, "Group $group must PASS or be READY")
            assertTrue(groupResult.passedCount > 0, "Group $group must have passed test cases")
        }
    }

    @Test
    fun `simulated matrix can NEVER produce authoritative GO_V02 or GO_V03`() {
        val runner = StagingEquivalenceMatrixRunner()
        assertEquals(EvidenceProvenance.MODEL_SIMULATION, runner.provenance, "Matrix runner provenance must be MODEL_SIMULATION")

        val validator = RolloutValidator(customRunner = runner)
        val report = validator.runValidationMatrix()

        assertNotEquals(RolloutDecision.GO_V03_ACTIVE_B, report.decision, "MODEL_SIMULATION must never yield GO_V03_ACTIVE_B")
        assertNotEquals(RolloutDecision.GO_V02_ACTIVE_A, report.decision, "MODEL_SIMULATION must never yield GO_V02_ACTIVE_A")
        assertFalse(report.rolloutApprovalStatus.startsWith("APPROVED"), "Rollout approval must not be granted from simulation")
    }

    @Test
    fun `real server staging runner with all 14 state dimensions produces GO_V03_ACTIVE_B decision`() {
        val completeSnapshotActual = StagingStateSnapshot(
            physicalBlocks = mapOf("world:0,64,0" to "AIR"),
            drops = mapOf("DIAMOND" to 1),
            playerInventory = mapOf("DIAMOND" to 1),
            playerXp = 100L,
            toolDurability = 50,
            blockCounts = mapOf("DIAMOND_ORE" to 1L),
            islandWorth = 500L,
            islandLevel = 5L,
            stackedAmounts = mapOf("world:0,64,0" to 1),
            signsAndWarps = mapOf("world:0,64,0" to "Welcome"),
            protectionResult = true,
            economyBalance = 1000.0,
            exceptionsRecorded = emptyList(),
            persistedRestartEquivalent = true,
            dirtyChunks = setOf(100L),
            measuredDimensions = StagingStateSnapshot.ALL_REQUIRED_DIMENSIONS
        )

        val completeSnapshotExpected = completeSnapshotActual.copy()
        val diff = completeSnapshotActual.diff(completeSnapshotExpected)
        assertTrue(diff.isEquivalent)
        assertTrue(diff.missingDimensions.isEmpty())

        val realRunner = object : StagingRunner {
            override val provenance: EvidenceProvenance = EvidenceProvenance.REAL_SERVER_STAGING

            override fun execute(): List<TestCaseResult> {
                return MatrixGroup.values().map { group ->
                    TestCaseResult(
                        caseId = "${group.name}-REAL",
                        name = "Real server staging ${group.title}",
                        group = group,
                        status = TestStatus.PASS,
                        details = "All 14 dimensions bit-for-bit verified on real Leaf server",
                        diff = diff
                    )
                }
            }
        }

        val validator = RolloutValidator(customRunner = realRunner)
        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.GO_V03_ACTIVE_B, report.decision, "Real server staging with all 14 dimensions must issue GO_V03_ACTIVE_B")
        assertEquals("APPROVED_V03_ACTIVE_B", report.rolloutApprovalStatus)
        assertEquals("PASS (ALL 14 DIMENSIONS VERIFIED)", report.realStagingEquivalenceStatus)
    }

    @Test
    fun `real staging approval missing one required state dimension is refused`() {
        // Missing economyBalance and persistedRestartEquivalent
        val incompleteDimensions = StagingStateSnapshot.ALL_REQUIRED_DIMENSIONS - setOf("economyBalance", "persistedRestartEquivalent")
        val incompleteSnapshotActual = StagingStateSnapshot(
            physicalBlocks = mapOf("world:0,64,0" to "AIR"),
            drops = mapOf("DIAMOND" to 1),
            playerInventory = mapOf("DIAMOND" to 1),
            playerXp = 100L,
            toolDurability = 50,
            blockCounts = mapOf("DIAMOND_ORE" to 1L),
            islandWorth = 500L,
            islandLevel = 5L,
            stackedAmounts = mapOf("world:0,64,0" to 1),
            signsAndWarps = mapOf("world:0,64,0" to "Welcome"),
            protectionResult = true,
            measuredDimensions = incompleteDimensions
        )

        val diff = incompleteSnapshotActual.diff(incompleteSnapshotActual)
        assertFalse(diff.missingDimensions.isEmpty(), "Must detect missing dimensions")
        assertTrue(diff.missingDimensions.contains("economyBalance"))

        val realRunnerWithMissingDimensions = object : StagingRunner {
            override val provenance: EvidenceProvenance = EvidenceProvenance.REAL_SERVER_STAGING

            override fun execute(): List<TestCaseResult> {
                return MatrixGroup.values().map { group ->
                    TestCaseResult(
                        caseId = "${group.name}-REAL-INCOMPLETE",
                        name = "Real server staging ${group.title}",
                        group = group,
                        status = TestStatus.PASS,
                        details = "Missing dimensions",
                        diff = diff
                    )
                }
            }
        }

        val validator = RolloutValidator(customRunner = realRunnerWithMissingDimensions)
        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.NO_GO, report.decision, "Real staging missing dimensions must be refused (NO_GO)")
        assertEquals("NOT GRANTED", report.rolloutApprovalStatus)
        assertTrue(report.summary.contains("missing required state dimensions"))
    }

    @Test
    fun `failing protection group immediately triggers NO_GO decision`() {
        val runnerWithProtectionViolation = StagingRunner {
            val runner = StagingEquivalenceMatrixRunner()
            val cases = runner.execute().toMutableList()
            // Invert protection test to fail
            val idx = cases.indexOfFirst { it.group == MatrixGroup.PROTECTION }
            if (idx >= 0) {
                cases[idx] = cases[idx].copy(status = TestStatus.FAIL, details = "Injected protection bypass")
            }
            cases
        }

        val validator = RolloutValidator(customRunner = runnerWithProtectionViolation)
        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.NO_GO, report.decision, "Protection violation must strictly result in NO_GO")
        assertTrue(report.failedTests > 0, "Failed test count must reflect protection failure")
    }

    @Test
    fun `failing direct mutation recovery immediately triggers NO_GO decision`() {
        val runnerWithMutationViolation = StagingRunner {
            val runner = StagingEquivalenceMatrixRunner()
            val cases = runner.execute().toMutableList()
            // Invert failure recovery to fail (e.g. simulated retry after mutation)
            val idx = cases.indexOfFirst { it.caseId == "FLT-02" }
            if (idx >= 0) {
                cases[idx] = cases[idx].copy(status = TestStatus.FAIL, details = "Injected retry-after-mutation violation")
            }
            cases
        }

        val validator = RolloutValidator(customRunner = runnerWithMutationViolation)
        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.NO_GO, report.decision, "Direct mutation retry violation must strictly result in NO_GO")
    }

    @Test
    fun `standalone live audit runs cleanly in headless CI mode`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        val modeController = ModeController(OperatingMode.ACTIVE)
        val circuitBreaker = CircuitBreaker(modeController)
        val counters = Counters()
        val compatibilityValidator = mock(CompatibilityValidator::class.java)

        val cleanReport = itzherzchen.optimizer.bootstrap.FullValidationReport(
            fingerprintResult = itzherzchen.optimizer.bootstrap.FingerprintResult(true, emptyList(), emptyList()),
            listenerCensusResult = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
                isTopologyValid = true,
                entries = emptyList(),
                stackedBlocksIndex = 1,
                blockChangesIndex = 2,
                signsListenerIndex = 2,
                protectionListenerIndex = 0,
                discrepancies = emptyList()
            ),
            riskScanResult = itzherzchen.optimizer.observe.TransactionRiskReport(false, emptyList(), "No risk"),
            isOverallCompatible = true,
            recommendedMode = OperatingMode.VALIDATED
        )
        `when`(compatibilityValidator.runValidation()).thenReturn(cleanReport)

        val validator = RolloutValidator(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            validator = compatibilityValidator
        )

        val report = validator.runValidationMatrix()

        assertNotNull(report, "Live report must be generated")
        assertEquals(8, report.groupResults.size, "All 8 matrix groups must be represented")
        assertNotEquals(RolloutDecision.NO_GO, report.decision, "Clean live state without errors should not be NO_GO")
    }

    @Test
    fun `live staging audit with missing protection listener strictly produces NO_GO decision`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        val modeController = ModeController(OperatingMode.ACTIVE)
        val circuitBreaker = CircuitBreaker(modeController)
        val counters = Counters()
        val compatibilityValidator = mock(CompatibilityValidator::class.java)

        // Mock report where protectionListenerIndex is -1 (missing protection)
        val mockReport = itzherzchen.optimizer.bootstrap.FullValidationReport(
            fingerprintResult = itzherzchen.optimizer.bootstrap.FingerprintResult(true, emptyList(), emptyList()),
            listenerCensusResult = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
                isTopologyValid = false,
                entries = emptyList(),
                stackedBlocksIndex = 1,
                blockChangesIndex = 2,
                signsListenerIndex = 2,
                protectionListenerIndex = -1, // MISSING PROTECTION!
                discrepancies = listOf("Missing SSB NORMAL protection callback")
            ),
            riskScanResult = itzherzchen.optimizer.observe.TransactionRiskReport(false, emptyList(), "No risk"),
            isOverallCompatible = false,
            recommendedMode = OperatingMode.OBSERVE
        )
        `when`(compatibilityValidator.runValidation()).thenReturn(mockReport)

        val validator = RolloutValidator(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            validator = compatibilityValidator
        )

        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.NO_GO, report.decision, "Missing protection in live audit MUST strictly produce NO_GO")
        assertEquals(TestStatus.FAIL, report.groupResults[MatrixGroup.PROTECTION]?.status, "Protection group status must be FAIL")
        assertTrue(report.failedTests > 0, "Failed test count must be > 0")
    }

    @Test
    fun `live staging audit without validator or census fails closed to NO_GO`() {
        val validator = RolloutValidator()
        val report = validator.runValidationMatrix()
        assertEquals(RolloutDecision.NO_GO, report.decision, "Unvalidated live state must fail closed to NO_GO")
    }

    @Test
    fun `readiness-only input can never yield authoritative v03 or v02 rollout decision`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        val modeController = ModeController(OperatingMode.ACTIVE)
        val circuitBreaker = CircuitBreaker(modeController)
        val counters = Counters()
        val compatibilityValidator = mock(CompatibilityValidator::class.java)

        val perfectReport = itzherzchen.optimizer.bootstrap.FullValidationReport(
            fingerprintResult = itzherzchen.optimizer.bootstrap.FingerprintResult(true, emptyList(), emptyList()),
            listenerCensusResult = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
                isTopologyValid = true,
                entries = emptyList(),
                stackedBlocksIndex = 1,
                blockChangesIndex = 2,
                signsListenerIndex = 2,
                protectionListenerIndex = 0,
                discrepancies = emptyList()
            ),
            riskScanResult = itzherzchen.optimizer.observe.TransactionRiskReport(false, emptyList(), "No risk"),
            isOverallCompatible = true,
            recommendedMode = OperatingMode.ACTIVE
        )
        `when`(compatibilityValidator.runValidation()).thenReturn(perfectReport)

        val validator = RolloutValidator(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            validator = compatibilityValidator,
            customRunner = null
        )

        val report = validator.runValidationMatrix()

        // Invariant: Readiness audit MUST NOT claim v0.2 or v0.3 active rollout approval
        assertNotEquals(RolloutDecision.GO_V03_ACTIVE_B, report.decision, "Readiness-only audit must NEVER yield GO_V03_ACTIVE_B")
        assertNotEquals(RolloutDecision.GO_V02_ACTIVE_A, report.decision, "Readiness-only audit must NEVER yield GO_V02_ACTIVE_A")
        assertEquals(RolloutDecision.READY_FOR_STAGING, report.decision)
        assertTrue(report.isReadinessOnly)
        assertEquals("NOT RUN", report.realStagingEquivalenceStatus)
        assertEquals("NOT GRANTED", report.rolloutApprovalStatus)
        assertFalse(report.summary.contains("0 drift verified"))
    }

    @Test
    fun `readiness-only audit with transaction risk reports MODULE_B_BLOCKED`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        val modeController = ModeController(OperatingMode.ACTIVE)
        val circuitBreaker = CircuitBreaker(modeController)
        val counters = Counters()
        val compatibilityValidator = mock(CompatibilityValidator::class.java)

        val reportWithRisk = itzherzchen.optimizer.bootstrap.FullValidationReport(
            fingerprintResult = itzherzchen.optimizer.bootstrap.FingerprintResult(true, emptyList(), emptyList()),
            listenerCensusResult = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
                isTopologyValid = true,
                entries = emptyList(),
                stackedBlocksIndex = 1,
                blockChangesIndex = 2,
                signsListenerIndex = 2,
                protectionListenerIndex = 0,
                discrepancies = emptyList()
            ),
            riskScanResult = itzherzchen.optimizer.observe.TransactionRiskReport(true, listOf("ExternalWandGuard"), "Found 1 external listeners on CuboidWandUseEvent"),
            isOverallCompatible = true,
            recommendedMode = OperatingMode.ACTIVE
        )
        `when`(compatibilityValidator.runValidation()).thenReturn(reportWithRisk)

        val validator = RolloutValidator(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            validator = compatibilityValidator,
            customRunner = null
        )

        val report = validator.runValidationMatrix()

        assertEquals(RolloutDecision.MODULE_B_BLOCKED, report.decision)
        assertTrue(report.summary.contains("Module B blocked due to transaction risk"))
        assertEquals("NOT GRANTED", report.rolloutApprovalStatus)
    }
}
