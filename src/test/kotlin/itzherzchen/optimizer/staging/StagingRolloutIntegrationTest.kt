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
    fun `complete 8-group equivalence matrix produces GO_V03_ACTIVE_B decision`() {
        val validator = RolloutValidator(customRunner = StagingEquivalenceMatrixRunner())
        val report = validator.runValidationMatrix()

        val failedCases = report.groupResults.values.flatMap { it.caseResults }.filter { it.status != TestStatus.PASS }
        assertTrue(failedCases.isEmpty(), "Failed cases: " + failedCases.map { "${it.caseId}: ${it.details}" })

        assertEquals(RolloutDecision.GO_V03_ACTIVE_B, report.decision, "Full clean matrix must issue GO_V03_ACTIVE_B")
        assertEquals(0, report.failedTests, "Zero tests should fail in clean equivalence run")
        assertTrue(report.totalTests >= 24, "Should evaluate at least 24 distinct test cases across 8 groups")

        for (group in MatrixGroup.values()) {
            val groupResult = report.groupResults[group]
            assertNotNull(groupResult, "Result for group $group must exist")
            assertEquals(TestStatus.PASS, groupResult!!.status, "Group $group must PASS")
            assertTrue(groupResult.passedCount > 0, "Group $group must have passed test cases")
        }
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
}
