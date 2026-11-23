package itzherzchen.optimizer.runtime

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.TransactionRiskScanner
import itzherzchen.optimizer.observe.TransactionRiskState
import itzherzchen.optimizer.superior.BlockAccountingFastPath
import itzherzchen.optimizer.superior.StackedFastReject
import itzherzchen.optimizer.wildtools.WildToolsBridge
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import java.util.logging.Logger

/**
 * Dedicated lifecycle listener managing safe state progression across server events:
 * - ServerLoadEvent.STARTUP: Perform final full compatibility validation, transaction risk census,
 *   capture dependency identities, and transition from OBSERVE -> VALIDATED -> ACTIVE (if configured and eligible).
 *   Closes boot activation window once complete.
 * - ServerLoadEvent.RELOAD: Fail-closed terminal disarm. Server reloads do not rearm.
 * - PluginDisableEvent: If core dependencies (WildTools/SuperiorSkyblock2) are disabled, permanently disarm.
 * - PluginEnableEvent: If core dependencies are re-enabled or replaced, permanently disarm.
 *   If third-party plugins are enabled after startup, rescan transaction risk and monotonically disable Module B
 *   if any risk or scan failure is detected.
 */
class OptimizerLifecycleListener(
    private val optimizerConfig: OptimizerConfig,
    private val modeController: ModeController,
    private val compatibilityValidator: CompatibilityValidator,
    private val wildToolsBridge: WildToolsBridge,
    private val stackedFastReject: StackedFastReject,
    private val blockAccountingFastPath: BlockAccountingFastPath,
    private val riskScanner: TransactionRiskScanner = TransactionRiskScanner(),
    private val logger: Logger? = null
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type == ServerLoadEvent.LoadType.RELOAD) {
            logger?.severe("[Optimizer] Server reload detected via ServerLoadEvent.RELOAD! Disarming permanently; restart required.")
            modeController.disarm("Server reload detected via ServerLoadEvent.RELOAD; restart required", "SYSTEM")
            return
        }

        if (event.type == ServerLoadEvent.LoadType.STARTUP) {
            logger?.info("[Optimizer] Server startup complete (ServerLoadEvent.STARTUP). Performing final validation and lifecycle gating...")

            val validationReport = compatibilityValidator.runValidation()
            stackedFastReject.isEligible = validationReport.moduleAEligible
            blockAccountingFastPath.isEligible = validationReport.moduleBEligible

            if (!validationReport.moduleBEligible) {
                logger?.warning("[Optimizer] Module B (BlockAccountingFastPath) refused at startup: ${validationReport.moduleBReason}")
            }
            if (validationReport.moduleAEligible) {
                logger?.info("[Optimizer] Module A (SSBStackedFastReject) is eligible at startup.")
            }

            if (validationReport.isOverallCompatible) {
                val transitionedToValidated = modeController.transitionTo(
                    OperatingMode.VALIDATED,
                    "ServerLoadEvent STARTUP validation passed"
                )
                if (transitionedToValidated && optimizerConfig.configuredMode == OperatingMode.ACTIVE) {
                    val hooked = wildToolsBridge.installHooks(validationReport.listenerCensusResult)
                    if (hooked) {
                        modeController.transitionTo(OperatingMode.ACTIVE, "Boot-time hooks installed successfully")
                        logger?.info("[Optimizer] ACTIVE mode successfully armed at startup.")
                    } else {
                        logger?.warning("[Optimizer] WildTools hook installation failed. Remaining in VALIDATED mode.")
                    }
                }
            } else {
                logger?.warning("[Optimizer] Validation found incompatibilities at startup. Remaining in fail-closed OBSERVE mode.")
            }

            // Lock out any subsequent transitions to ACTIVE
            modeController.markBootComplete()
            logger?.info("[Optimizer] Boot activation window closed. Current mode: ${modeController.mode}")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) {
        val pluginName = event.plugin.name
        if (pluginName == "WildTools" || pluginName == "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Critical dependency '$pluginName' was disabled! Terminal disarm triggered.")
            modeController.disarm("Critical dependency '$pluginName' disabled after startup; restart required", "SYSTEM")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) {
        val pluginName = event.plugin.name
        if (pluginName == "WildTools" || pluginName == "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Critical dependency '$pluginName' was re-enabled or replaced after startup! Terminal disarm triggered.")
            modeController.disarm("Critical dependency '$pluginName' re-enabled or replaced after startup; restart required", "SYSTEM")
            return
        }

        // Monotonic transaction risk check for foreign plugins loaded late
        if (blockAccountingFastPath.isEligible) {
            val riskReport = riskScanner.scan()
            if (riskReport.state != TransactionRiskState.CLEAN) {
                blockAccountingFastPath.isEligible = false
                logger?.warning(
                    "[Optimizer] Plugin '$pluginName' enabled after startup introduced transaction risk or caused scan failure: " +
                            "${riskReport.state} (${riskReport.riskSummary}). Module B monotonically disabled; restart required to regain eligibility."
                )
            }
        }
    }
}
