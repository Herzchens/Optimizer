package itzherzchen.optimizer.superior

import org.bukkit.plugin.Plugin
import java.util.logging.Logger

data class DispatcherAuditResult(
    val moduleAEligible: Boolean,
    val moduleBEligible: Boolean,
    val highestCallbacks: List<String>,
    val monitorCallbacks: List<String>,
    val discrepancies: List<String>
)

/**
 * Audits the internal callback composition of SuperiorSkyblock2's GameEventsDispatcher.
 *
 * Requirements:
 * - BLOCK_BREAK_EVENT at HIGHEST must contain ONLY StackedBlocksListener.
 *   If any unexpected callback exists, Module A is refused.
 * - BLOCK_BREAK_EVENT at MONITOR must contain ONLY SignsListener and BlockChangesListener.
 *   If any unexpected callback exists, Module B is refused.
 * - Inability to audit fails closed (both modules refused).
 */
class SuperiorDispatcherAuditor(
    private val logger: Logger? = null
) {
    companion object {
        const val EXPECTED_STACKED_LISTENER = "com.bgsoftware.superiorskyblock.listener.StackedBlocksListener"
        const val EXPECTED_SIGNS_LISTENER = "com.bgsoftware.superiorskyblock.listener.SignsListener"
        const val EXPECTED_CHANGES_LISTENER = "com.bgsoftware.superiorskyblock.listener.BlockChangesListener"
    }

    fun audit(ssbPlugin: Plugin?): DispatcherAuditResult {
        if (ssbPlugin == null) {
            return DispatcherAuditResult(
                moduleAEligible = false,
                moduleBEligible = false,
                highestCallbacks = emptyList(),
                monitorCallbacks = emptyList(),
                discrepancies = listOf("SuperiorSkyblock2 plugin instance is null")
            )
        }

        return try {
            val ssbClass = ssbPlugin.javaClass
            val getDispatcherMethod = try {
                ssbClass.getMethod("getGameEventsDispatcher")
            } catch (_: NoSuchMethodException) {
                ssbClass.getMethod("getEventsDispatcher")
            }

            val dispatcher = getDispatcherMethod.invoke(ssbPlugin)
                ?: return DispatcherAuditResult(
                    moduleAEligible = false,
                    moduleBEligible = false,
                    highestCallbacks = emptyList(),
                    monitorCallbacks = emptyList(),
                    discrepancies = listOf("SuperiorSkyblock2 events dispatcher is null")
                )

            auditDispatcher(dispatcher, ssbClass.classLoader)
        } catch (t: Throwable) {
            val msg = "Failed to audit SuperiorSkyblock2 internal dispatcher: ${t.message}"
            logger?.severe("[Optimizer] $msg")
            DispatcherAuditResult(
                moduleAEligible = false,
                moduleBEligible = false,
                highestCallbacks = emptyList(),
                monitorCallbacks = emptyList(),
                discrepancies = listOf(msg)
            )
        }
    }

    fun auditDispatcher(dispatcher: Any, classLoader: ClassLoader): DispatcherAuditResult {
        val highestListeners = mutableListOf<String>()
        val monitorListeners = mutableListOf<String>()

        try {
            var currentClass: Class<*>? = dispatcher.javaClass
            var callbacksField: java.lang.reflect.Field? = null
            while (currentClass != null && currentClass != Any::class.java) {
                try {
                    callbacksField = currentClass.getDeclaredField("callbacks")
                    break
                } catch (_: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                }
            }

            if (callbacksField == null) {
                return DispatcherAuditResult(
                    moduleAEligible = false,
                    moduleBEligible = false,
                    highestCallbacks = emptyList(),
                    monitorCallbacks = emptyList(),
                    discrepancies = listOf("Could not find callbacks field in ${dispatcher.javaClass.name}")
                )
            }

            callbacksField.isAccessible = true
            val callbacksMap = callbacksField.get(dispatcher)
                ?: return DispatcherAuditResult(
                    moduleAEligible = false,
                    moduleBEligible = false,
                    highestCallbacks = emptyList(),
                    monitorCallbacks = emptyList(),
                    discrepancies = listOf("Dispatcher callbacks map is null")
                )

            // Look up BLOCK_BREAK_EVENT type
            val eventTypeClass = Class.forName("com.bgsoftware.superiorskyblock.platform.event.GameEventType", true, classLoader)
            val blockBreakField = eventTypeClass.getField("BLOCK_BREAK_EVENT")
            val blockBreakType = blockBreakField.get(null)

            // Look up EnumerateMap.get(Enumerable) or Map.get
            val priorityMap = try {
                val enumerableClass = Class.forName("com.bgsoftware.superiorskyblock.api.objects.Enumerable", true, classLoader)
                val getMethod = callbacksMap.javaClass.getMethod("get", enumerableClass)
                getMethod.invoke(callbacksMap, blockBreakType) as? Map<*, *>
            } catch (_: Throwable) {
                if (callbacksMap is Map<*, *>) {
                    callbacksMap[blockBreakType] as? Map<*, *>
                } else null
            }

            if (priorityMap == null || priorityMap.isEmpty()) {
                return DispatcherAuditResult(
                    moduleAEligible = false,
                    moduleBEligible = false,
                    highestCallbacks = emptyList(),
                    monitorCallbacks = emptyList(),
                    discrepancies = listOf("No callbacks registered for BLOCK_BREAK_EVENT in SSB dispatcher")
                )
            }

            for ((priorityObj, listenersListObj) in priorityMap) {
                val priorityName = priorityObj?.toString() ?: continue
                val listenersList = listenersListObj as? List<*> ?: continue

                for (registeredListener in listenersList) {
                    if (registeredListener == null) continue
                    val listenerObj = try {
                        val field = registeredListener.javaClass.getField("listener")
                        field.get(registeredListener)
                    } catch (_: NoSuchFieldException) {
                        val field = registeredListener.javaClass.getDeclaredField("listener").apply { isAccessible = true }
                        field.get(registeredListener)
                    }
                    val className = listenerObj?.javaClass?.name ?: registeredListener.javaClass.name

                    when (priorityName) {
                        "HIGHEST" -> highestListeners.add(className)
                        "MONITOR" -> monitorListeners.add(className)
                    }
                }
            }

            return evaluateComposition(highestListeners, monitorListeners)
        } catch (t: Throwable) {
            val msg = "Exception during SSB dispatcher audit: ${t.message}"
            logger?.severe("[Optimizer] $msg")
            return DispatcherAuditResult(
                moduleAEligible = false,
                moduleBEligible = false,
                highestCallbacks = highestListeners,
                monitorCallbacks = monitorListeners,
                discrepancies = listOf(msg)
            )
        }
    }

    /**
     * Evaluates callback composition against safety invariants.
     */
    fun evaluateComposition(highestListeners: List<String>, monitorListeners: List<String>): DispatcherAuditResult {
        val discrepancies = mutableListOf<String>()

        var moduleAEligible = true
        if (highestListeners.isEmpty()) {
            moduleAEligible = false
            discrepancies.add("HIGHEST has no registered callbacks (expected $EXPECTED_STACKED_LISTENER)")
        } else {
            for (listenerName in highestListeners) {
                if (listenerName != EXPECTED_STACKED_LISTENER) {
                    moduleAEligible = false
                    discrepancies.add("Unexpected SSB callback at HIGHEST: $listenerName")
                }
            }
        }

        var moduleBEligible = true
        val allowedMonitor = setOf(EXPECTED_SIGNS_LISTENER, EXPECTED_CHANGES_LISTENER)
        if (monitorListeners.isEmpty() || !monitorListeners.contains(EXPECTED_CHANGES_LISTENER)) {
            moduleBEligible = false
            discrepancies.add("MONITOR missing expected $EXPECTED_CHANGES_LISTENER")
        }
        for (listenerName in monitorListeners) {
            if (listenerName !in allowedMonitor) {
                moduleBEligible = false
                discrepancies.add("Unexpected SSB callback at MONITOR: $listenerName")
            }
        }

        return DispatcherAuditResult(
            moduleAEligible = moduleAEligible,
            moduleBEligible = moduleBEligible,
            highestCallbacks = highestListeners,
            monitorCallbacks = monitorListeners,
            discrepancies = discrepancies
        )
    }
}
