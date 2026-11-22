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
    val recommendedMode: OperatingMode
)

/**
 * High-level compatibility gate validator.
 * Orchestrates fingerprinting, listener census, and transaction risk auditing.
 */
class CompatibilityValidator(
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val counters: Counters,
    private val logger: Logger? = null
) {
    fun runValidation(): FullValidationReport {
        logger?.info("[Optimizer] Running runtime validation suite...")

        val fingerprint = RuntimeFingerprint(config, logger).validate()
        val census = ListenerCensus(logger).performCensus()
        val riskReport = TransactionRiskScanner(logger).scan()

        if (!fingerprint.isCompatible) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Runtime fingerprint failed: ${fingerprint.failureReasons.joinToString("; ")}")
        }

        if (!census.isTopologyValid) {
            counters.validationFailures.increment()
            logger?.severe("[Optimizer] Listener census failed: ${census.discrepancies.joinToString("; ")}")
        }

        if (riskReport.hasHighRiskExternalListeners) {
            logger?.warning("[Optimizer] High-risk external listeners detected on CuboidWandUseEvent: ${riskReport.externalListeners.joinToString(", ")}")
        }

        val compatible = fingerprint.isCompatible && census.isTopologyValid

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
            recommendedMode = recommendedMode
        )
    }
}
