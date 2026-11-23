package itzherzchen.optimizer.observe

import org.bukkit.event.HandlerList
import java.util.logging.Logger

enum class TransactionRiskState {
    CLEAN,
    RISK_FOUND,
    SCAN_FAILED
}

data class TransactionRiskReport(
    val state: TransactionRiskState,
    val externalListeners: List<String>,
    val riskSummary: String
) {
    // Secondary constructor for compatibility with existing tests and call sites
    constructor(hasHighRiskExternalListeners: Boolean, externalListeners: List<String>, riskSummary: String) : this(
        state = if (hasHighRiskExternalListeners) TransactionRiskState.RISK_FOUND else TransactionRiskState.CLEAN,
        externalListeners = externalListeners,
        riskSummary = riskSummary
    )

    val hasHighRiskExternalListeners: Boolean
        get() = state != TransactionRiskState.CLEAN

    val isEligible: Boolean
        get() = state == TransactionRiskState.CLEAN
}

/**
 * Scans Bukkit's global HandlerList for external listeners on CuboidWandUseEvent.
 * Fail-closed invariant:
 * - If CuboidWandUseEvent class cannot be found, reflection fails, or an exception occurs,
 *   state is SCAN_FAILED (unknown is NOT clean).
 * - If foreign listeners are detected, state is RISK_FOUND.
 * - Only when scan completes without errors and zero foreign listeners are present is state CLEAN.
 */
class TransactionRiskScanner(
    private val logger: Logger? = null,
    private val classLoader: ClassLoader = TransactionRiskScanner::class.java.classLoader,
    private val handlerListProvider: (() -> HandlerList?)? = null
) {
    fun scan(): TransactionRiskReport {
        val externalListeners = mutableListOf<String>()

        try {
            val handlerList: HandlerList? = if (handlerListProvider != null) {
                handlerListProvider.invoke()
            } else {
                val eventClass = try {
                    Class.forName("com.bgsoftware.wildtools.api.events.CuboidWandUseEvent", true, classLoader)
                } catch (e: Throwable) {
                    logger?.warning("[Optimizer] CuboidWandUseEvent class could not be resolved: ${e.message}")
                    null
                }

                if (eventClass == null) {
                    return TransactionRiskReport(
                        state = TransactionRiskState.SCAN_FAILED,
                        externalListeners = emptyList(),
                        riskSummary = "CuboidWandUseEvent class not found in runtime; unable to audit listeners"
                    )
                }

                val getHandlerListMethod = eventClass.getMethod("getHandlerList")
                getHandlerListMethod.invoke(null) as? HandlerList
            }

            if (handlerList == null) {
                return TransactionRiskReport(
                    state = TransactionRiskState.SCAN_FAILED,
                    externalListeners = emptyList(),
                    riskSummary = "CuboidWandUseEvent.getHandlerList() returned null or non-HandlerList"
                )
            }

            val registered = handlerList.registeredListeners
            for (listener in registered) {
                val pluginName = listener.plugin.name
                if (pluginName != "WildTools" && pluginName != "Optimizer") {
                    val listenerClass = listener.listener.javaClass.name
                    externalListeners.add("$pluginName ($listenerClass @ ${listener.priority})")
                }
            }
        } catch (e: Throwable) {
            logger?.severe("[Optimizer] Transaction risk scan failed with exception: ${e.message}")
            return TransactionRiskReport(
                state = TransactionRiskState.SCAN_FAILED,
                externalListeners = emptyList(),
                riskSummary = "Scan failed due to exception: ${e.message ?: e.javaClass.simpleName}"
            )
        }

        val hasRisk = externalListeners.isNotEmpty()
        val state = if (hasRisk) TransactionRiskState.RISK_FOUND else TransactionRiskState.CLEAN
        val summary = if (hasRisk) {
            "Found ${externalListeners.size} external listeners on CuboidWandUseEvent: ${externalListeners.joinToString(", ")}"
        } else {
            "No foreign listeners detected on CuboidWandUseEvent"
        }

        return TransactionRiskReport(
            state = state,
            externalListeners = externalListeners,
            riskSummary = summary
        )
    }
}
