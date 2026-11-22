package itzherzchen.optimizer.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Module identifiers for targeted fault isolation.
 */
enum class ModuleType {
    GLOBAL,
    STACKED_FAST_REJECT,
    BLOCK_ACCOUNTING_FAST_PATH
}

data class TripRecord(
    val module: ModuleType,
    val reason: String,
    val timestamp: Long,
    val throwable: Throwable?
)

/**
 * Thread-safe circuit breaker preventing catastrophic cascading failures.
 *
 * When an invariant is broken or an unhandled exception occurs on a fast path,
 * the affected module's circuit breaker trips immediately, forcing all future
 * executions to take the safe original passthrough.
 */
class CircuitBreaker(
    private val modeController: ModeController,
    private val logger: Logger? = null
) {
    private val trippedModules = ConcurrentHashMap<ModuleType, AtomicBoolean>()
    private val tripRecords = ConcurrentHashMap<ModuleType, TripRecord>()

    init {
        for (module in ModuleType.values()) {
            trippedModules[module] = AtomicBoolean(false)
        }
    }

    fun isTripped(module: ModuleType): Boolean {
        // If GLOBAL is tripped or system is disarmed, all modules are considered tripped
        if (modeController.mode == OperatingMode.DISARMED_RESTART_REQUIRED) return true
        if (trippedModules[ModuleType.GLOBAL]?.get() == true) return true
        return trippedModules[module]?.get() == true
    }

    fun getTripRecord(module: ModuleType): TripRecord? = tripRecords[module]

    /**
     * Trips the circuit breaker for a module.
     * If module is GLOBAL or critical, forces modeController into DISARMED_RESTART_REQUIRED.
     */
    fun trip(module: ModuleType, reason: String, throwable: Throwable? = null): Boolean {
        val flag = trippedModules[module] ?: return false
        val firstTrip = flag.compareAndSet(false, true)
        if (firstTrip) {
            val record = TripRecord(module, reason, System.currentTimeMillis(), throwable)
            tripRecords[module] = record

            logger?.log(
                Level.SEVERE,
                "[Optimizer] Circuit breaker TRIPPED for module $module: $reason",
                throwable
            )

            // If global failure, permanently disarm
            if (module == ModuleType.GLOBAL) {
                modeController.disarm("Circuit breaker tripped: $reason", "CIRCUIT_BREAKER")
            }
        }
        return firstTrip
    }

    fun trip(reason: String, throwable: Throwable? = null): Boolean =
        trip(ModuleType.GLOBAL, reason, throwable)

    fun resetForTesting() {
        for (module in ModuleType.values()) {
            trippedModules[module]?.set(false)
            tripRecords.remove(module)
        }
    }
}
