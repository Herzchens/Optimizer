package itzherzchen.optimizer.runtime

import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Thread-safe controller for optimizer runtime state transitions.
 *
 * Enforces fail-closed rules:
 * - ACTIVE mode requires explicit configuration AND boot-time initialization.
 * - Hot re-arming via commands or reload is strictly forbidden.
 * - Once DISARMED_RESTART_REQUIRED is reached, no transition can exit it until JVM restart.
 */
class ModeController(
    initialMode: OperatingMode = OperatingMode.OBSERVE,
    private val logger: Logger? = null
) {
    private val currentMode = AtomicReference(initialMode)
    private val disarmReason = AtomicReference<String?>(null)
    private val isBootPhase = AtomicReference(true)

    val mode: OperatingMode
        get() = currentMode.get()

    val reason: String?
        get() = disarmReason.get()

    fun markBootComplete() {
        isBootPhase.set(false)
    }

    /**
     * Attempts to transition to the requested mode.
     * Returns true if transition succeeded, false if disallowed or locked.
     */
    fun transitionTo(newMode: OperatingMode, transitionReason: String): Boolean {
        while (true) {
            val current = currentMode.get()

            // If already in terminal disarmed state, no transitions are permitted
            if (current == OperatingMode.DISARMED_RESTART_REQUIRED) {
                logger?.warning("[Optimizer] Ignored transition to $newMode: system is in DISARMED_RESTART_REQUIRED ($transitionReason)")
                return false
            }

            // Cannot transition to ACTIVE after boot phase has completed
            if (newMode == OperatingMode.ACTIVE && !isBootPhase.get()) {
                logger?.warning("[Optimizer] Rejected transition to ACTIVE: hot arming is forbidden after server boot.")
                return false
            }

            if (currentMode.compareAndSet(current, newMode)) {
                logger?.info("[Optimizer] Mode transition: $current -> $newMode (reason: $transitionReason)")
                if (newMode == OperatingMode.DISARMED_RESTART_REQUIRED) {
                    disarmReason.set(transitionReason)
                }
                return true
            }
        }
    }

    /**
     * Idempotent emergency disarm.
     * Immediately transitions to DISARMED_RESTART_REQUIRED and permanently locks out fast paths.
     */
    fun disarm(disarmReasonText: String, operator: String = "SYSTEM"): Boolean {
        logger?.severe("[Optimizer] EMERGENCY DISARM triggered by $operator: $disarmReasonText")
        disarmReason.set("[$operator] $disarmReasonText")
        currentMode.set(OperatingMode.DISARMED_RESTART_REQUIRED)
        return true
    }
}
