package itzherzchen.optimizer

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.command.OptimizerCommand
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.SampledTiming
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import itzherzchen.optimizer.wildtools.CuboidActionTracker
import itzherzchen.optimizer.wildtools.SyntheticListenerInstaller
import itzherzchen.optimizer.wildtools.WildToolsBridge
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

    lateinit var actionContextStack: ActionContextStack
        private set

    lateinit var actionTracker: CuboidActionTracker
        private set

    lateinit var superiorBridge: itzherzchen.optimizer.superior.SuperiorBridge
        private set

    lateinit var stackedFastReject: itzherzchen.optimizer.superior.StackedFastReject
        private set

    lateinit var blockAccountingFastPath: itzherzchen.optimizer.superior.BlockAccountingFastPath
        private set

    lateinit var listenerInstaller: SyntheticListenerInstaller
        private set

    lateinit var wildToolsBridge: WildToolsBridge
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

        // 3. Action Context & WildTools Containment Machinery
        actionContextStack = ActionContextStack(circuitBreaker, counters, logger)
        superiorBridge = itzherzchen.optimizer.superior.SuperiorBridge(logger)
        stackedFastReject = itzherzchen.optimizer.superior.StackedFastReject(
            optimizerConfig,
            modeController,
            circuitBreaker,
            actionContextStack,
            counters,
            logger
        )
        blockAccountingFastPath = itzherzchen.optimizer.superior.BlockAccountingFastPath(
            optimizerConfig,
            modeController,
            circuitBreaker,
            actionContextStack,
            counters,
            logger = logger
        )
        actionTracker = CuboidActionTracker(actionContextStack, modeController, circuitBreaker, counters, timing, logger)
        listenerInstaller = SyntheticListenerInstaller(
            actionContextStack,
            modeController,
            circuitBreaker,
            counters,
            logger,
            stackedFastReject,
            blockAccountingFastPath
        )
        wildToolsBridge = WildToolsBridge(
            this,
            optimizerConfig,
            modeController,
            circuitBreaker,
            counters,
            actionContextStack,
            actionTracker,
            listenerInstaller,
            logger
        )
        wildToolsBridge.initialize()

        // Automatically disarm WildToolsBridge whenever ModeController transitions to DISARMED_RESTART_REQUIRED
        modeController.addDisarmListener {
            wildToolsBridge.disarm("Automatic disarm via ModeController")
        }

        // 4. Compatibility & Fingerprint Validator
        compatibilityValidator = CompatibilityValidator(
            optimizerConfig,
            modeController,
            counters,
            logger
        )

        // 5. Command Registration
        val rolloutValidator = itzherzchen.optimizer.staging.RolloutValidator(
            config = optimizerConfig,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            validator = compatibilityValidator,
            logger = logger
        )
        val cmd = OptimizerCommand(
            modeController,
            circuitBreaker,
            counters,
            timing,
            compatibilityValidator,
            wildToolsBridge,
            rolloutValidator
        )
        val pluginCmd = getCommand("optimizer")
        if (pluginCmd != null) {
            pluginCmd.setExecutor(cmd)
            pluginCmd.tabCompleter = cmd
        } else {
            logger.severe("[Optimizer] Failed to find registered command 'optimizer' in plugin.yml!")
        }

        // 6. Register Lifecycle Listener
        // Final validation, transaction risk census, dependency capture, and ACTIVE arming
        // are strictly deferred until ServerLoadEvent.STARTUP to prevent incomplete startup race conditions.
        val lifecycleListener = itzherzchen.optimizer.runtime.OptimizerLifecycleListener(
            optimizerConfig = optimizerConfig,
            modeController = modeController,
            compatibilityValidator = compatibilityValidator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath,
            logger = logger
        )
        server.pluginManager.registerEvents(lifecycleListener, this)

        // 7. Initial Read-Only Observer Diagnostics
        val initialReport = compatibilityValidator.runValidation()
        stackedFastReject.isEligible = initialReport.moduleAEligible
        blockAccountingFastPath.isEligible = initialReport.moduleBEligible

        val elapsed = System.currentTimeMillis() - startTime
        logger.info("[Optimizer] Safe OBSERVE infrastructure initialized (${elapsed}ms). Awaiting ServerLoadEvent.STARTUP for final validation.")
    }

    override fun onDisable() {
        logger.info("[Optimizer] Disabling Optimizer plugin...")

        // Fail-closed emergency disarm on plugin disable
        if (::wildToolsBridge.isInitialized) {
            wildToolsBridge.disarm("Plugin onDisable lifecycle invoked")
        }

        if (::actionContextStack.isInitialized) {
            actionContextStack.clear()
        }

        if (::modeController.isInitialized) {
            modeController.disarm("Plugin onDisable lifecycle invoked", "SYSTEM")
        }

        logger.info("[Optimizer] Disarmed and shutdown completed.")
    }
}
