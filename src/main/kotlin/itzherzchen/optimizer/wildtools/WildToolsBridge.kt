package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import java.lang.reflect.Field
import java.util.logging.Logger

/**
 * Bridge managing the integration between Optimizer and WildTools.
 * Coordinates Cuboid action tracking and the synthetic break listener hook lifecycle.
 */
class WildToolsBridge(
    private val plugin: Plugin,
    private val config: OptimizerConfig,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    val actionContextStack: ActionContextStack,
    val actionTracker: CuboidActionTracker,
    val listenerInstaller: SyntheticListenerInstaller,
    private val logger: Logger? = null,
    private val dependencyChecker: () -> Boolean = {
        try {
            val pm = Bukkit.getPluginManager()
            val wt = pm?.getPlugin("WildTools")
            val ssb = pm?.getPlugin("SuperiorSkyblock2")
            wt != null && wt.isEnabled && ssb != null && ssb.isEnabled
        } catch (_: Throwable) {
            false
        }
    },
    private val listProvider: () -> MutableList<RegisteredListener>? = {
        try {
            val wt = Bukkit.getPluginManager()?.getPlugin("WildTools")
            if (wt != null) getClaimingPluginsBreakMethods(wt) else null
        } catch (_: Throwable) {
            null
        }
    },
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            true
        }
    }
) {
    var isHooked: Boolean = false
        private set

    var dependencyIdentity: DependencyIdentitySnapshot? = null

    init {
        actionTracker.integrityChecker = { verifyIntegrity() == PreflightResult.OK }
    }

    /**
     * Captures exact dependency instance, classloader, handler and list identities for preflight validation.
     */
    fun captureDependencies(): DependencyIdentitySnapshot? {
        val pm = try { Bukkit.getPluginManager() } catch (_: Throwable) { null } ?: return null
        val wt = try { pm.getPlugin("WildTools") } catch (_: Throwable) { null } ?: return null
        val ssb = try { pm.getPlugin("SuperiorSkyblock2") } catch (_: Throwable) { null } ?: return null
        val getEventsMethod = try { wt.javaClass.getMethod("getEvents") } catch (_: Throwable) { null } ?: return null
        val eventsHandler = try { getEventsMethod.invoke(wt) } catch (_: Throwable) { null } ?: return null
        val targetList = getClaimingPluginsBreakMethods(wt) ?: return null

        val snapshot = DependencyIdentitySnapshot(
            wildToolsPlugin = wt,
            wildToolsClassLoader = wt.javaClass.classLoader,
            superiorSkyblockPlugin = ssb,
            superiorSkyblockClassLoader = ssb.javaClass.classLoader,
            eventsHandler = eventsHandler,
            targetList = targetList
        )
        dependencyIdentity = snapshot
        return snapshot
    }

    /**
     * Initializes the bridge and registers root event listeners.
     */
    fun initialize() {
        actionTracker.integrityChecker = { verifyIntegrity() == PreflightResult.OK }
        try {
            Bukkit.getPluginManager().registerEvents(actionTracker, plugin)
            logger?.info("[Optimizer] CuboidActionTracker registered for root block breaks.")
        } catch (e: Exception) {
            logger?.severe("[Optimizer] Failed to register CuboidActionTracker: ${e.message}")
        }
    }

    /**
     * Installs wrappers on WildTools' private claimingPluginsBreakMethods if census topology is valid.
     */
    fun installHooks(censusResult: ListenerCensusResult): Boolean {
        if (isHooked) return true

        val wtPlugin = try {
            Bukkit.getPluginManager().getPlugin("WildTools")
        } catch (_: Throwable) {
            null
        }

        if (wtPlugin == null || !wtPlugin.isEnabled) {
            logger?.warning("[Optimizer] Cannot install WildTools hooks: WildTools plugin is not available or not enabled.")
            return false
        }

        val rawList = getClaimingPluginsBreakMethods(wtPlugin)
        if (rawList == null) {
            logger?.severe("[Optimizer] Cannot install WildTools hooks: failed to obtain claimingPluginsBreakMethods list.")
            return false
        }

        val token = censusResult.token
        val result = if (token != null) {
            // Eliminate census -> install TOCTOU: audited list instance MUST equal live list instance
            if (rawList !== token.targetList) {
                logger?.severe("[Optimizer] TOCTOU detected: WildTools claimingPluginsBreakMethods list instance changed between census and install!")
                return false
            }
            listenerInstaller.install(token)
        } else {
            listenerInstaller.install(rawList, censusResult)
        }

        if (result == InstallResult.SUCCESS) {
            isHooked = true
            captureDependencies()
            logger?.info("[Optimizer] WildTools synthetic break wrappers successfully installed.")
            return true
        } else {
            logger?.warning("[Optimizer] Failed to install WildTools synthetic break wrappers: $result")
            return false
        }
    }

    /**
     * Reversible emergency disarm and safe uninstallation of private-list hooks.
     */
    fun disarm(reason: String = "Manual disarm") {
        if (!isHooked && !listenerInstaller.isInstalled) return

        logger?.warning("[Optimizer] Disarming WildToolsBridge: $reason")

        // Step 1: Thread-safe emergency disarm (valid from ANY thread)
        listenerInstaller.emergencyDisarm()
        listenerInstaller.stackedFastReject?.isEligible = false
        listenerInstaller.blockAccountingFastPath?.isEligible = false

        // Step 2: Thread check - NEVER mutate private list off-thread
        if (!primaryThreadChecker()) {
            logger?.severe(
                "[Optimizer] WildToolsBridge disarm called off-main-thread on '${Thread.currentThread().name}'. " +
                "Wrappers forced to passthrough. Physical list restoration rejected off-thread; server restart required."
            )
            isHooked = false
            return
        }

        val uninstallResult = listenerInstaller.uninstall()
        isHooked = (uninstallResult == UninstallResult.RESIDUAL_PASSTHROUGH_REMAINS)
        logger?.info("[Optimizer] WildTools hooks uninstalled: $uninstallResult")
    }

    /**
     * Verifies that the installed wrappers have not drifted due to external list modification or reload.
     * Evaluates live identity before root actions:
     * - installed wrapper identities occupy expected slots
     * - required dependencies remain enabled
     * - exact dependency plugin instances and classloaders match captured snapshot
     * - expected live listener state still exists
     * - integration has not been externally replaced/reloaded
     */
    fun verifyIntegrity(): PreflightResult {
        if (!isHooked) {
            if (modeController.mode == itzherzchen.optimizer.runtime.OperatingMode.ACTIVE) {
                counters.validationFailures.increment()
                circuitBreaker.trip(
                    itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                    "WildToolsBridge is not hooked while in ACTIVE mode. Disarming system."
                )
                disarm("Uninstalled hooks in ACTIVE mode")
                return PreflightResult.FAILED_NOT_INSTALLED
            }
            return PreflightResult.OK
        }

        // 1. Required dependencies remain enabled
        if (!dependencyChecker()) {
            counters.validationFailures.increment()
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "Required dependencies (WildTools/SuperiorSkyblock2) are disabled or missing. Disarming system."
            )
            disarm("Required dependencies disabled or missing")
            return PreflightResult.FAILED_DEPENDENCY_DISABLED
        }

        // 2. Exact dependency instance and classloader verification
        val snapshot = dependencyIdentity
        if (snapshot != null) {
            val identityResult = snapshot.verify()
            if (identityResult != IdentityVerificationResult.OK) {
                counters.validationFailures.increment()
                circuitBreaker.trip(
                    itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                    "Dependency identity drift detected ($identityResult). Disarming system."
                )
                disarm("Dependency identity drift: $identityResult")
                return PreflightResult.FAILED_INTEGRATION_REPLACED
            }
        }

        // 3. Integration replaced / reloaded check
        val currentLiveList = listProvider()
        if (currentLiveList == null || currentLiveList !== listenerInstaller.targetList) {
            counters.validationFailures.increment()
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "WildTools claimingPluginsBreakMethods list was externally replaced or reloaded. Disarming system."
            )
            disarm("WildTools integration externally replaced or reloaded")
            return PreflightResult.FAILED_INTEGRATION_REPLACED
        }

        // 4. Wrapper identities occupy expected slots & live listener state intact
        val result = listenerInstaller.verifyIntegrity()
        if (result != PreflightResult.OK) {
            counters.validationFailures.increment()
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "WildTools listener integrity verification failed: $result. Disarming system."
            )
            disarm("Listener integrity check failed ($result)")
            return result
        }

        return PreflightResult.OK
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun getClaimingPluginsBreakMethods(wtPlugin: Plugin): MutableList<RegisteredListener>? {
            return try {
                val getEventsMethod = wtPlugin.javaClass.getMethod("getEvents")
                val eventsHandler = getEventsMethod.invoke(wtPlugin) ?: return null
                val field = findField(eventsHandler.javaClass, "claimingPluginsBreakMethods") ?: return null
                field.isAccessible = true
                field.get(eventsHandler) as? MutableList<RegisteredListener>
            } catch (_: Throwable) {
                null
            }
        }

        private fun findField(clazz: Class<*>, fieldName: String): Field? {
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                try {
                    return current.getDeclaredField(fieldName)
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                }
            }
            return null
        }
    }
}
