package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.superior.BlockAccountingFastPath
import itzherzchen.optimizer.superior.StackedFastReject
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class CompatibilityValidatorTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var config: OptimizerConfig

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.ACTIVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
    }

    private fun createValidCensus(): ListenerCensusResult {
        return ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )
    }

    @Test
    fun `no external listener produces report where Module B is eligible`() {
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = { TransactionRiskReport(false, emptyList(), "No risk") }
        )

        val report = validator.runValidation()

        assertTrue(report.isOverallCompatible)
        assertTrue(report.moduleAEligible)
        assertTrue(report.moduleBEligible)
        assertEquals(OperatingMode.ACTIVE, report.recommendedMode)
        assertTrue(report.moduleBReason.contains("verified safe"))
    }

    @Test
    fun `external listener present marks Module A eligible but Module B refused due to transaction risk`() {
        val foreignListener = "ForeignClaimPlugin (com.example.ClaimGuard @ NORMAL)"
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = {
                TransactionRiskReport(
                    hasHighRiskExternalListeners = true,
                    externalListeners = listOf(foreignListener),
                    riskSummary = "Found 1 external listeners on CuboidWandUseEvent: $foreignListener"
                )
            }
        )

        val report = validator.runValidation()

        assertTrue(report.isOverallCompatible, "Fingerprint + census are intact, runtime is overall compatible")
        assertTrue(report.moduleAEligible, "Module A must remain independently eligible")
        assertFalse(report.moduleBEligible, "Module B must be refused when external listeners exist on CuboidWandUseEvent")
        assertTrue(report.moduleBReason.contains("Refused due to transaction risk"))
        assertTrue(report.moduleBReason.contains("ForeignClaimPlugin"))
    }

    @Test
    fun `risk disappears only after restart or revalidation preventing runtime hot arm`() {
        var externalListenerPresent = true
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = {
                if (externalListenerPresent) {
                    TransactionRiskReport(true, listOf("ExternalGuard"), "Risk present")
                } else {
                    TransactionRiskReport(false, emptyList(), "No risk")
                }
            }
        )

        // Boot validation
        val initialReport = validator.runValidation()
        assertFalse(initialReport.moduleBEligible)

        val stack = ActionContextStack(circuitBreaker, counters)
        val moduleB = BlockAccountingFastPath(
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            actionContextStack = stack,
            counters = counters
        )
        moduleB.isEligible = initialReport.moduleBEligible
        assertFalse(moduleB.isEligible)

        // Simulate third-party plugin unregistering dynamically at runtime
        externalListenerPresent = false

        // Invariant: Module B MUST NOT hot-arm without restart/revalidation
        assertFalse(moduleB.isEligible, "Module B must remain ineligible until authoritative restart/revalidation")

        val originalListener = mock(RegisteredListener::class.java)
        val block = mock(Block::class.java)
        val player = mock(org.bukkit.entity.Player::class.java)
        val event = BlockBreakEvent(block, player)

        // Fast path handle must strictly call original callback due to ineligibility
        moduleB.handle(event, originalListener)
        verify(originalListener, times(1)).callEvent(event)

        // After revalidation/restart, Module B eligibility can be cleanly updated
        val restartReport = validator.runValidation()
        assertTrue(restartReport.moduleBEligible)
        moduleB.isEligible = restartReport.moduleBEligible
        assertTrue(moduleB.isEligible)
    }

    @Test
    fun `scan failure marks Module A eligible but Module B refused due to scan failure`() {
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = {
                TransactionRiskReport(
                    state = itzherzchen.optimizer.observe.TransactionRiskState.SCAN_FAILED,
                    externalListeners = emptyList(),
                    riskSummary = "Scan failed due to missing CuboidWandUseEvent class"
                )
            }
        )

        val report = validator.runValidation()

        assertTrue(report.isOverallCompatible, "Fingerprint + census are intact")
        assertTrue(report.moduleAEligible, "Module A must remain independently eligible")
        assertFalse(report.moduleBEligible, "Module B must be refused when transaction risk scan fails (unknown is not clean)")
        assertTrue(report.moduleBReason.contains("Refused due to transaction risk scan failure"))
    }

    @Test
    fun `unknown SSB internal callback at HIGHEST marks Module A refused while Module B is eligible`() {
        val foreignAudit = itzherzchen.optimizer.superior.DispatcherAuditResult(
            moduleAEligible = false,
            moduleBEligible = true,
            highestCallbacks = listOf("ForeignHighestListener"),
            monitorCallbacks = listOf("SignsListener", "BlockChangesListener"),
            discrepancies = listOf("Unexpected SSB callback at HIGHEST: ForeignHighestListener")
        )

        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = { TransactionRiskReport(false, emptyList(), "No risk") },
            dispatcherAuditorProvider = { foreignAudit }
        )

        val report = validator.runValidation()

        assertTrue(report.isOverallCompatible)
        assertFalse(report.moduleAEligible, "Module A must be refused when HIGHEST has foreign callback")
        assertTrue(report.moduleAReason.contains("ForeignHighestListener"))
        assertTrue(report.moduleBEligible, "Module B remains eligible when MONITOR composition is standard")
    }

    @Test
    fun `unknown SSB internal callback at MONITOR marks Module B refused while Module A is eligible`() {
        val foreignAudit = itzherzchen.optimizer.superior.DispatcherAuditResult(
            moduleAEligible = true,
            moduleBEligible = false,
            highestCallbacks = listOf("StackedBlocksListener"),
            monitorCallbacks = listOf("SignsListener", "BlockChangesListener", "ForeignMonitorListener"),
            discrepancies = listOf("Unexpected SSB callback at MONITOR: ForeignMonitorListener")
        )

        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = { TransactionRiskReport(false, emptyList(), "No risk") },
            dispatcherAuditorProvider = { foreignAudit }
        )

        val report = validator.runValidation()

        assertTrue(report.isOverallCompatible)
        assertTrue(report.moduleAEligible, "Module A remains eligible")
        assertFalse(report.moduleBEligible, "Module B must be refused when MONITOR has foreign callback")
        assertTrue(report.moduleBReason.contains("ForeignMonitorListener"))
    }
}
