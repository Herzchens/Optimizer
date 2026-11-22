package itzherzchen.optimizer.observe

import org.bukkit.event.HandlerList
import java.util.logging.Logger

data class TransactionRiskReport(
    val hasHighRiskExternalListeners: Boolean,
    val externalListeners: List<String>,
    val riskSummary: String
)

/**
 * Scans Bukkit's global HandlerList for external listeners on CuboidWandUseEvent.
 * If third-party plugins intercept or cancel the wand use after accounting mutations,
 * this flags the baseline transaction risk.
 */
class TransactionRiskScanner(
    private val logger: Logger? = null
) {
    fun scan(): TransactionRiskReport {
        val externalListeners = mutableListOf<String>()

        try {
            // Find CuboidWandUseEvent class if available
            val eventClass = try {
                Class.forName("com.bgsoftware.wildtools.api.events.CuboidWandUseEvent")
            } catch (_: ClassNotFoundException) {
                null
            }

            if (eventClass != null) {
                val getHandlerListMethod = eventClass.getMethod("getHandlerList")
                val handlerList = getHandlerListMethod.invoke(null) as? HandlerList

                if (handlerList != null) {
                    val registered = handlerList.registeredListeners
                    for (listener in registered) {
                        val pluginName = listener.plugin?.name ?: "Unknown"
                        if (pluginName != "WildTools" && pluginName != "Optimizer") {
                            val listenerClass = listener.listener?.javaClass?.name ?: "Unknown"
                            externalListeners.add("$pluginName ($listenerClass @ ${listener.priority})")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger?.fine("[Optimizer] Transaction risk scan completed with notice: ${e.message}")
        }

        val hasRisk = externalListeners.isNotEmpty()
        val summary = if (hasRisk) {
            "Found ${externalListeners.size} external listeners on CuboidWandUseEvent: ${externalListeners.joinToString(", ")}"
        } else {
            "No foreign listeners detected on CuboidWandUseEvent"
        }

        return TransactionRiskReport(
            hasHighRiskExternalListeners = hasRisk,
            externalListeners = externalListeners,
            riskSummary = summary
        )
    }
}
