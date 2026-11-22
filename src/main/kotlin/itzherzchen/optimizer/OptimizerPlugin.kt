package itzherzchen.optimizer

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.command.OptimizerCommand
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.SampledTiming
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.plugin.java.JavaPlugin

/**
 * Optimizer — Fail-Closed HotPathGuard Plugin for Leaf / Paper 1.21.4.
 *
 * Author: itzherzchen
 * Package Root: itzherzchen.optimizer
 */
class OptimizerPlugin : JavaPlugin() {

    lateinit var optimizerConfig: OptimizerConfig
        private set

    lateinit var modeController: ModeController
        private set

    lateinit var circuitBreaker: CircuitBreaker
        private set

    lateinit var counters: Counters
        private set

    lateinit var timing: SampledTiming
        private set

    lateinit var compatibilityValidator: CompatibilityValidator
        private set

    override fun onLoad() {
        val version = try { pluginMeta.version } catch (_: Throwable) { @Suppress("DEPRECATION") description.version }
        logger.info("[Optimizer] Loading Optimizer plugin v$version by itzherzchen...")
    }

    override fun onEnable() {
        val startTime = System.currentTimeMillis()

        // 1. Configuration
        saveDefaultConfig()
        optimizerConfig = OptimizerConfig.load(config)

        // 2. Runtime Observability & State Machinery
        modeController = ModeController(OperatingMode.OBSERVE, logger)
        circuitBreaker = CircuitBreaker(modeController, logger)
        counters = Counters()
        timing = SampledTiming(optimizerConfig.timingSampleRate)

        // 3. Compatibility & Fingerprint Validator
        compatibilityValidator = CompatibilityValidator(
            optimizerConfig,
            modeController,
            counters,
            logger
        )

        // 4. Command Registration
        val cmd = OptimizerCommand(
            modeController,
            circuitBreaker,
            counters,
            timing,
            compatibilityValidator
        )
        val pluginCmd = getCommand("optimizer")
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd)
            pluginCmd.tabCompleter = cmd
        } else {
            logger.severe("[Optimizer] Failed to find registered command 'optimizer' in plugin.yml!")
        }

        // 5. Initial Boot Validation
        val validationReport = compatibilityValidator.runValidation()
        if (validationReport.isOverallCompatible) {
            val targetMode = if (optimizerConfig.configuredMode == OperatingMode.ACTIVE) {
                // In Phase A/B bootstrap, active fast paths are not yet hooked, safe observe/validated
                OperatingMode.VALIDATED
            } else {
                validationReport.recommendedMode
            }
            modeController.transitionTo(targetMode, "Initial boot validation passed")
        } else {
            logger.warning("[Optimizer] Initial validation found incompatibilities. Remaining in fail-closed OBSERVE mode.")
        }

        // Lock out hot arming after boot
        modeController.markBootComplete()

        val elapsed = System.currentTimeMillis() - startTime
        logger.info("[Optimizer] Optimizer enabled in mode ${modeController.mode} (${elapsed}ms).")
    }

    override fun onDisable() {
        logger.info("[Optimizer] Disabling Optimizer plugin...")

        // Fail-closed emergency disarm on plugin disable
        if (::modeController.isInitialized) {
            modeController.disarm("Plugin onDisable lifecycle invoked", "SYSTEM")
        }

        logger.info("[Optimizer] Disarmed and shutdown completed.")
    }
}
