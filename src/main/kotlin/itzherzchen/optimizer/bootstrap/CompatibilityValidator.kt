package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.observe.TransactionRiskScanner

import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import java.util.logging.Logger

data class FullValidationReport(
    val fingerprintResult: FingerprintResult,
    val listenerCensusResult: ListenerCensusResult,
    val riskScanResult: TransactionRiskReport,
    val isOverallCompatible: Boolean,
    val recommendedMode: OperatingMode,
    val moduleAEligible: Boolean = isOverallCompatible,
    val moduleAReason: String = if (isOverallCompatible) "SSBStackedFastReject verified safe" else "Runtime incompatible",
    val moduleBEligible: Boolean = isOverallCompatible && !riskScanResult.hasHighRiskExternalListeners,
    val moduleBReason: String = when {
        !isOverallCompatible -> "Runtime incompatible"
        riskScanResult.hasHighRiskExternalListeners -> "Refused due to transaction risk (${riskScanResult.riskSummary})"
        else -> "BlockAccountingFastPath verified safe"
    }
)

/**
 * High-level compatibility gate validator.
 * Orchestrates fingerprinting, listener census, and transaction risk auditing.
 */
class CompatibilityValidator(
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val counters: Counters,
    private val logger: Logger? = null,
    private val fingerprintProvider: () -> FingerprintResult = { RuntimeFingerprint(config, logger).validate() },
    private val censusProvider: () -> ListenerCensusResult = { ListenerCensus(logger).performCensus() },
    private val riskScannerProvider: () -> TransactionRiskReport = { TransactionRiskScanner(logger).scan() }
) {
    fun runValidation(): FullValidationReport {
        logger?.info("[Optimizer] Running runtime validation suite...")

        val fingerprint = fingerprintProvider()
        val census = censusProvider()
        val riskReport = riskScannerProvider()

        if (!fingerprint.isCompatible) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Runtime fingerprint failed: ${fingerprint.failureReasons.joinToString("; ")}")
        }

        if (!census.isTopologyValid) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Listener census failed: ${census.discrepancies.joinToString("; ")}")
        }

        val compatible = fingerprint.isCompatible && census.isTopologyValid

        val moduleAEligible = compatible
        val moduleAReason = if (compatible) {
            "SSBStackedFastReject verified safe"
        } else {
            "Runtime incompatible"
        }

        val moduleBEligible = compatible && !riskReport.hasHighRiskExternalListeners
        val moduleBReason = when {
            !compatible -> "Runtime incompatible"
            riskReport.hasHighRiskExternalListeners -> {
                logger?.warning("[Optimizer] Module B (BlockAccountingFastPath) refused: transaction risk detected on CuboidWandUseEvent (${riskReport.riskSummary})")
                "Refused due to transaction risk (${riskReport.riskSummary})"
            }
            else -> "BlockAccountingFastPath verified safe"
        }

        val recommendedMode = when {
            !compatible -> OperatingMode.OBSERVE
            config.configuredMode == OperatingMode.ACTIVE -> OperatingMode.ACTIVE
            else -> OperatingMode.VALIDATED
        }

        return FullValidationReport(
            fingerprintResult = fingerprint,
            listenerCensusResult = census,
            riskScanResult = riskReport,
            isOverallCompatible = compatible,
            recommendedMode = recommendedMode,
            moduleAEligible = moduleAEligible,
            moduleAReason = moduleAReason,
            moduleBEligible = moduleBEligible,
            moduleBReason = moduleBReason
        )
    }
}
