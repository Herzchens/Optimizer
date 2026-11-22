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
    private val logger: Logger? = null
) {
    var isHooked: Boolean = false
        private set

    /**
     * Initializes the bridge and registers root event listeners.
     */
    fun initialize() {
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

        val result = listenerInstaller.install(rawList, censusResult)
        if (result == InstallResult.SUCCESS) {
            isHooked = true
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
        if (!isHooked) return

        logger?.warning("[Optimizer] Disarming WildToolsBridge: $reason")
        val uninstallResult = listenerInstaller.uninstall()
        isHooked = false
        logger?.info("[Optimizer] WildTools hooks uninstalled: $uninstallResult")
    }

    /**
     * Verifies that the installed wrappers have not drifted due to external list modification or reload.
     */
    fun verifyIntegrity(): PreflightResult {
        if (!isHooked) return PreflightResult.OK
        val result = listenerInstaller.verifyIntegrity()
        if (result != PreflightResult.OK) {
            counters.validationFailures.increment()
            circuitBreaker.trip(
                itzherzchen.optimizer.runtime.ModuleType.GLOBAL,
                "WildTools listener integrity verification failed: $result. Disarming system."
            )
            disarm("Listener integrity check failed ($result)")
        }
        return result
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
