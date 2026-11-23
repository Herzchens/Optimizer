package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.wildtools.InstallationToken
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import java.lang.reflect.Field
import java.util.logging.Logger

data class ListenerCensusEntry(
    val index: Int,
    val pluginName: String,
    val listenerClassName: String,
    val priority: EventPriority,
    val isIgnoringCancelled: Boolean
)

data class ListenerCensusResult(
    val isTopologyValid: Boolean,
    val entries: List<ListenerCensusEntry>,
    val stackedBlocksIndex: Int,
    val blockChangesIndex: Int,
    val signsListenerIndex: Int,
    val protectionListenerIndex: Int,
    val discrepancies: List<String>,
    val token: InstallationToken? = null
)

/**
 * Inspects WildTools' private claimingPluginsBreakMethods list and SuperiorSkyblock2 callbacks.
 *
 * Bytecode Invariant Verification:
 * - SuperiorSkyblock2 registers events into Bukkit using `BukkitEventsListener` as the Listener instance.
 * - In WildTools' `claimingPluginsBreakMethods`, SuperiorSkyblock2 entries appear as:
 *   - Priority NORMAL: executes ProtectionListener callbacks.
 *   - Priority HIGHEST: executes StackedBlocksListener callbacks.
 *   - Priority MONITOR: executes SignsListener and BlockChangesListener callbacks.
 * - Exact ordering MUST satisfy: protectionListenerIndex < stackedBlocksIndex < blockChangesIndex.
 *   Any inversion would allow stack bypass or accounting before protection checks!
 */
class ListenerCensus(
    private val logger: Logger? = null
) {
    fun performCensus(): ListenerCensusResult {
        val entries = mutableListOf<ListenerCensusEntry>()
        val discrepancies = mutableListOf<String>()

        val wtPlugin = try {
            Bukkit.getPluginManager()?.getPlugin("WildTools")
        } catch (_: Exception) {
            null
        }

        if (wtPlugin == null || !wtPlugin.isEnabled) {
            return ListenerCensusResult(
                isTopologyValid = false,
                entries = emptyList(),
                stackedBlocksIndex = -1,
                blockChangesIndex = -1,
                signsListenerIndex = -1,
                protectionListenerIndex = -1,
                discrepancies = listOf("WildTools is not available or not enabled")
            )
        }

        val rawList = getClaimingPluginsBreakMethods(wtPlugin)
        if (rawList == null) {
            return ListenerCensusResult(
                isTopologyValid = false,
                entries = emptyList(),
                stackedBlocksIndex = -1,
                blockChangesIndex = -1,
                signsListenerIndex = -1,
                protectionListenerIndex = -1,
                discrepancies = listOf("Could not access claimingPluginsBreakMethods list on WildTools")
            )
        }

        return auditListenerList(rawList, wtPlugin)
    }

    /**
     * Audits an arbitrary list of listeners against the expected SSB topology.
     * Accessible for isolated testing without live Bukkit plugin manager.
     */
    fun auditListenerList(rawList: List<*>, wtPlugin: Plugin? = null): ListenerCensusResult {
        val entries = mutableListOf<ListenerCensusEntry>()
        val discrepancies = mutableListOf<String>()

        if (rawList.isEmpty()) {
            discrepancies.add("claimingPluginsBreakMethods list is empty")
        }

        var stackedBlocksIdx = -1
        var monitorIdx = -1
        var protectionListenerIdx = -1

        for ((index, item) in rawList.withIndex()) {
            if (item !is RegisteredListener) {
                discrepancies.add("Entry at index $index is not a RegisteredListener (${item?.javaClass?.name})")
                continue
            }

            val plugin = item.plugin
            val pluginName = plugin?.name ?: "Unknown"
            val listenerClass = item.listener?.javaClass?.name ?: "null"
            val priority = item.priority
            val ignoreCancelled = item.isIgnoringCancelled

            entries.add(
                ListenerCensusEntry(
                    index = index,
                    pluginName = pluginName,
                    listenerClassName = listenerClass,
                    priority = priority,
                    isIgnoringCancelled = ignoreCancelled
                )
            )

            if (pluginName == "SuperiorSkyblock2") {
                // SSB registers its Bukkit events via BukkitEventsListener
                val isExpectedListenerClass = listenerClass.endsWith("BukkitEventsListener") ||
                        listenerClass.contains("BukkitEventsListener")

                if (!isExpectedListenerClass) {
                    discrepancies.add(
                        "SSB entry at index $index has unexpected listener class $listenerClass (expected BukkitEventsListener)"
                    )
                }

                when (priority) {
                    EventPriority.NORMAL -> {
                        if (protectionListenerIdx != -1) {
                            discrepancies.add("Duplicate SSB NORMAL listener at index $index (already at $protectionListenerIdx)")
                        }
                        protectionListenerIdx = index
                    }
                    EventPriority.HIGHEST -> {
                        if (stackedBlocksIdx != -1) {
                            discrepancies.add("Duplicate SSB HIGHEST listener at index $index (already at $stackedBlocksIdx)")
                        }
                        stackedBlocksIdx = index
                    }
                    EventPriority.MONITOR -> {
                        if (monitorIdx != -1) {
                            discrepancies.add("Duplicate SSB MONITOR listener at index $index (already at $monitorIdx)")
                        }
                        monitorIdx = index
                    }
                    else -> {
                        discrepancies.add("SSB registered unexpected listener priority $priority at index $index")
                    }
                }
            }
        }

        // Validate presence of expected callbacks
        if (protectionListenerIdx == -1) {
            discrepancies.add("SSB Protection callback (NORMAL) not found in claimingPluginsBreakMethods")
        }
        if (stackedBlocksIdx == -1) {
            discrepancies.add("SSB StackedBlocks callback (HIGHEST) not found in claimingPluginsBreakMethods")
        }
        if (monitorIdx == -1) {
            discrepancies.add("SSB BlockChanges/Signs callback (MONITOR) not found in claimingPluginsBreakMethods")
        }

        // Validate sequence order: NORMAL (protection) must precede HIGHEST (stack), which must precede MONITOR (accounting)
        if (protectionListenerIdx != -1 && stackedBlocksIdx != -1 && protectionListenerIdx >= stackedBlocksIdx) {
            discrepancies.add(
                "SSB listener priority inversion: Protection (NORMAL at $protectionListenerIdx) must precede StackedBlocks (HIGHEST at $stackedBlocksIdx)"
            )
        }
        if (stackedBlocksIdx != -1 && monitorIdx != -1 && stackedBlocksIdx >= monitorIdx) {
            discrepancies.add(
                "SSB listener priority inversion: StackedBlocks (HIGHEST at $stackedBlocksIdx) must precede Accounting (MONITOR at $monitorIdx)"
            )
        }

        val isValid = discrepancies.isEmpty()
        if (!isValid) {
            logger?.warning("[Optimizer] Listener census found discrepancies: ${discrepancies.joinToString("; ")}")
        } else {
            logger?.info(
                "[Optimizer] Listener census verified: Protection at index $protectionListenerIdx, StackedBlocks at index $stackedBlocksIdx, Accounting at index $monitorIdx"
            )
        }

        val token = if (isValid && rawList is MutableList<*> && rawList.all { it is RegisteredListener }) {
            @Suppress("UNCHECKED_CAST")
            val typedList = rawList as MutableList<RegisteredListener>
            val sListener = typedList[stackedBlocksIdx]
            val mListener = typedList[monitorIdx]
            InstallationToken(
                targetList = typedList,
                stackedBlocksIndex = stackedBlocksIdx,
                expectedStackedListener = sListener,
                monitorIndex = monitorIdx,
                expectedMonitorListener = mListener,
                ssbPlugin = sListener.plugin,
                ssbClassLoader = sListener.plugin.javaClass.classLoader,
                wtPlugin = wtPlugin,
                wtClassLoader = wtPlugin?.javaClass?.classLoader
            )
        } else {
            null
        }

        return ListenerCensusResult(
            isTopologyValid = isValid,
            entries = entries,
            stackedBlocksIndex = stackedBlocksIdx,
            blockChangesIndex = monitorIdx,
            signsListenerIndex = monitorIdx,
            protectionListenerIndex = protectionListenerIdx,
            discrepancies = discrepancies,
            token = token
        )
    }

    private fun getClaimingPluginsBreakMethods(wtPlugin: Plugin): List<*>? {
        return try {
            val getEventsMethod = wtPlugin.javaClass.getMethod("getEvents")
            val eventsHandler = getEventsMethod.invoke(wtPlugin) ?: return null
            val field = findField(eventsHandler.javaClass, "claimingPluginsBreakMethods") ?: return null
            field.isAccessible = true
            field.get(eventsHandler) as? List<*>
        } catch (e: Exception) {
            logger?.warning("[Optimizer] Failed to read claimingPluginsBreakMethods: ${e.message}")
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
