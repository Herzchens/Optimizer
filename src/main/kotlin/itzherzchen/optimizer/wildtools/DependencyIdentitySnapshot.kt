package itzherzchen.optimizer.wildtools

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener

enum class IdentityVerificationResult {
    OK,
    PLUGIN_MANAGER_UNAVAILABLE,
    WILDTOOLS_MISSING,
    WILDTOOLS_DISABLED,
    WILDTOOLS_INSTANCE_MISMATCH,
    WILDTOOLS_CLASSLOADER_MISMATCH,
    SUPERIORSKYBLOCK_MISSING,
    SUPERIORSKYBLOCK_DISABLED,
    SUPERIORSKYBLOCK_INSTANCE_MISMATCH,
    SUPERIORSKYBLOCK_CLASSLOADER_MISMATCH,
    EVENTSHANDLER_MISMATCH,
    LIST_MISMATCH
}

/**
 * Captures and verifies the exact runtime identity of critical dependencies:
 * - WildTools Plugin instance reference and ClassLoader
 * - SuperiorSkyblock2 Plugin instance reference and ClassLoader
 * - WildTools EventsHandler instance reference
 * - WildTools claimingPluginsBreakMethods list reference
 *
 * Prevents hot-swapped plugins or classloader substitutions from masquerading as verified dependencies.
 */
data class DependencyIdentitySnapshot(
    val wildToolsPlugin: Plugin,
    val wildToolsClassLoader: ClassLoader,
    val superiorSkyblockPlugin: Plugin,
    val superiorSkyblockClassLoader: ClassLoader,
    val eventsHandler: Any,
    val targetList: List<RegisteredListener>
) {
    fun verify(
        pluginManagerProvider: () -> org.bukkit.plugin.PluginManager? = {
            try { Bukkit.getPluginManager() } catch (_: Throwable) { null }
        },
        claimingListProvider: (Plugin) -> MutableList<RegisteredListener>? = { wt ->
            WildToolsBridge.getClaimingPluginsBreakMethods(wt)
        }
    ): IdentityVerificationResult {
        val pm = pluginManagerProvider() ?: return IdentityVerificationResult.PLUGIN_MANAGER_UNAVAILABLE
        val wt = try { pm.getPlugin("WildTools") } catch (_: Throwable) { null }
            ?: return IdentityVerificationResult.WILDTOOLS_MISSING
        val ssb = try { pm.getPlugin("SuperiorSkyblock2") } catch (_: Throwable) { null }
            ?: return IdentityVerificationResult.SUPERIORSKYBLOCK_MISSING

        if (!wt.isEnabled) return IdentityVerificationResult.WILDTOOLS_DISABLED
        if (!ssb.isEnabled) return IdentityVerificationResult.SUPERIORSKYBLOCK_DISABLED

        if (wt !== wildToolsPlugin) return IdentityVerificationResult.WILDTOOLS_INSTANCE_MISMATCH
        if (wt.javaClass.classLoader !== wildToolsClassLoader) return IdentityVerificationResult.WILDTOOLS_CLASSLOADER_MISMATCH

        if (ssb !== superiorSkyblockPlugin) return IdentityVerificationResult.SUPERIORSKYBLOCK_INSTANCE_MISMATCH
        if (ssb.javaClass.classLoader !== superiorSkyblockClassLoader) return IdentityVerificationResult.SUPERIORSKYBLOCK_CLASSLOADER_MISMATCH

        val currentEventsHandler = try {
            val m = wt.javaClass.getMethod("getEvents")
            m.invoke(wt)
        } catch (_: Throwable) { null }
        if (currentEventsHandler !== eventsHandler) return IdentityVerificationResult.EVENTSHANDLER_MISMATCH

        val currentList = claimingListProvider(wt)
        if (currentList !== targetList) return IdentityVerificationResult.LIST_MISMATCH

        return IdentityVerificationResult.OK
    }
}
