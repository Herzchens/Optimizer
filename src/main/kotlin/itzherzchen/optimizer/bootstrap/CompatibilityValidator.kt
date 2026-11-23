package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.observe.TransactionRiskScanner
import itzherzchen.optimizer.observe.TransactionRiskState
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.superior.DispatcherAuditResult
import itzherzchen.optimizer.superior.SuperiorDispatcherAuditor
import org.bukkit.Bukkit
import java.util.logging.Logger

data class FullValidationReport(
    val fingerprintResult: FingerprintResult,
    val listenerCensusResult: ListenerCensusResult,
    val riskScanResult: TransactionRiskReport,
    val isOverallCompatible: Boolean,
    val recommendedMode: OperatingMode,
    val moduleAEligible: Boolean = isOverallCompatible,
    val moduleAReason: String = if (isOverallCompatible) "SSBStackedFastReject verified safe" else "Runtime incompatible",
    val moduleBEligible: Boolean = isOverallCompatible && riskScanResult.state == TransactionRiskState.CLEAN,
    val moduleBReason: String = when {
        !isOverallCompatible -> "Runtime incompatible"
        riskScanResult.state == TransactionRiskState.RISK_FOUND -> "Refused due to transaction risk (${riskScanResult.riskSummary})"
        riskScanResult.state == TransactionRiskState.SCAN_FAILED -> "Refused due to transaction risk scan failure (${riskScanResult.riskSummary})"
        else -> "BlockAccountingFastPath verified safe"
    },
    val dispatcherAuditResult: DispatcherAuditResult? = null
)

/**
 * High-level compatibility gate validator.
 * Orchestrates fingerprinting, listener census, transaction risk auditing,
 * and SuperiorSkyblock2 internal dispatcher callback auditing.
 */
class CompatibilityValidator(
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val counters: Counters,
    private val logger: Logger? = null,
    private val fingerprintProvider: () -> FingerprintResult = { RuntimeFingerprint(config, logger).validate() },
    private val censusProvider: () -> ListenerCensusResult = { ListenerCensus(logger).performCensus() },
    private val riskScannerProvider: () -> TransactionRiskReport = { TransactionRiskScanner(logger).scan() },
    private val dispatcherAuditorProvider: () -> DispatcherAuditResult = {
        val ssb = try { Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2") } catch (_: Throwable) { null }
        if (ssb != null) {
            SuperiorDispatcherAuditor(logger).audit(ssb)
        } else {
            DispatcherAuditResult(
                moduleAEligible = true,
                moduleBEligible = true,
                highestCallbacks = listOf(SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER),
                monitorCallbacks = listOf(SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER, SuperiorDispatcherAuditor.EXPECTED_CHANGES_LISTENER),
                discrepancies = emptyList()
            )
        }
    }
) {
    @Volatile
    var lastReport: FullValidationReport? = null
        private set

    fun runValidation(): FullValidationReport {
        logger?.info("[Optimizer] Running runtime validation suite...")

        val fingerprint = fingerprintProvider()
        val census = censusProvider()
        val riskReport = riskScannerProvider()
        val dispatcherAudit = dispatcherAuditorProvider()

        if (!fingerprint.isCompatible) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Runtime fingerprint failed: ${fingerprint.failureReasons.joinToString("; ")}")
        }

        if (!census.isTopologyValid) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Listener census failed: ${census.discrepancies.joinToString("; ")}")
        }

        val compatible = fingerprint.isCompatible && census.isTopologyValid

        val moduleAEligible = compatible && dispatcherAudit.moduleAEligible
        val moduleAReason = when {
            !compatible -> "Runtime incompatible"
            !dispatcherAudit.moduleAEligible -> "Refused due to SSB internal callback discrepancy (${dispatcherAudit.discrepancies.joinToString("; ")})"
            else -> "SSBStackedFastReject verified safe"
        }

        val moduleBEligible = compatible && (riskReport.state == TransactionRiskState.CLEAN) && dispatcherAudit.moduleBEligible
        val moduleBReason = when {
            !compatible -> "Runtime incompatible"
            riskReport.state == TransactionRiskState.RISK_FOUND -> {
                logger?.warning("[Optimizer] Module B (BlockAccountingFastPath) refused: transaction risk detected on CuboidWandUseEvent (${riskReport.riskSummary})")
                "Refused due to transaction risk (${riskReport.riskSummary})"
            }
            riskReport.state == TransactionRiskState.SCAN_FAILED -> {
                logger?.warning("[Optimizer] Module B (BlockAccountingFastPath) refused: transaction risk scan failed (${riskReport.riskSummary})")
                "Refused due to transaction risk scan failure (${riskReport.riskSummary})"
            }
            !dispatcherAudit.moduleBEligible -> {
                logger?.warning("[Optimizer] Module B (BlockAccountingFastPath) refused: SSB internal callback discrepancy (${dispatcherAudit.discrepancies.joinToString("; ")})")
                "Refused due to SSB internal callback discrepancy (${dispatcherAudit.discrepancies.joinToString("; ")})"
            }
            else -> "BlockAccountingFastPath verified safe"
        }

        val recommendedMode = when {
            !compatible -> OperatingMode.OBSERVE
            config.configuredMode == OperatingMode.ACTIVE -> OperatingMode.ACTIVE
            else -> OperatingMode.VALIDATED
        }

        val report = FullValidationReport(
            fingerprintResult = fingerprint,
            listenerCensusResult = census,
            riskScanResult = riskReport,
            isOverallCompatible = compatible,
            recommendedMode = recommendedMode,
            moduleAEligible = moduleAEligible,
            moduleAReason = moduleAReason,
            moduleBEligible = moduleBEligible,
            moduleBReason = moduleBReason,
            dispatcherAuditResult = dispatcherAudit
        )

        lastReport = report
        return report
    }
}
