package itzherzchen.optimizer.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Thread-safe controller for optimizer runtime state transitions.
 *
 * Enforces strict fail-closed state machine progression:
 *   OBSERVE -> VALIDATED -> ACTIVE
 *   any non-terminal state -> DISARMED_RESTART_REQUIRED
 *
 * Rules:
 * - ACTIVE mode requires explicit configuration, boot phase, and prior VALIDATED state.
 * - Hot re-arming via commands, reload, or late transitions is strictly forbidden.
 * - Once DISARMED_RESTART_REQUIRED is reached, no transition can exit it until JVM restart.
 * - disarm() is genuinely idempotent: the first disarm reason is retained forever,
 *   disarm listeners are notified exactly once, and repeated disarm calls do not overwrite
 *   forensic diagnostic state.
 */
class ModeController(
    initialMode: OperatingMode = OperatingMode.OBSERVE,
    private val logger: Logger? = null
) {
    private val currentMode = AtomicReference(initialMode)
    private val disarmReason = AtomicReference<String?>(null)
    private val isBootPhase = AtomicReference(true)
    private val disarmListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val disarmNotified = AtomicBoolean(false)

    val mode: OperatingMode
        get() = currentMode.get()

    val reason: String?
        get() = disarmReason.get()

    fun addDisarmListener(listener: () -> Unit) {
        disarmListeners.add(listener)
    }

    fun markBootComplete() {
        isBootPhase.set(false)
    }

    fun isBootPhase(): Boolean = isBootPhase.get()

    /**
     * Attempts to transition to the requested mode following the legal progression.
     * Returns true if transition succeeded, false if disallowed or locked.
     */
    fun transitionTo(newMode: OperatingMode, transitionReason: String): Boolean {
        while (true) {
            val current = currentMode.get()

            // If already in terminal disarmed state, no transitions are permitted
            if (current == OperatingMode.DISARMED_RESTART_REQUIRED) {
                logger?.warning("[Optimizer] Ignored transition to $newMode: system is in terminal DISARMED_RESTART_REQUIRED ($transitionReason)")
                return false
            }

            // Same-mode transition is a no-op success
            if (current == newMode) return true

            // Transition to terminal DISARMED_RESTART_REQUIRED is permitted from any state
            if (newMode == OperatingMode.DISARMED_RESTART_REQUIRED) {
                if (currentMode.compareAndSet(current, OperatingMode.DISARMED_RESTART_REQUIRED)) {
                    disarmReason.compareAndSet(null, transitionReason)
                    notifyDisarmOnce()
                    logger?.severe("[Optimizer] Mode transition: $current -> DISARMED_RESTART_REQUIRED (reason: $transitionReason)")
                    return true
                }
                continue
            }

            // Conceptual progression check:
            // OBSERVE -> VALIDATED
            // VALIDATED -> ACTIVE (only during boot phase)
            // No direct OBSERVE -> ACTIVE
            // No backward transitions (e.g. ACTIVE -> VALIDATED / OBSERVE)
            val isAllowed = when (current) {
                OperatingMode.OBSERVE -> newMode == OperatingMode.VALIDATED
                OperatingMode.VALIDATED -> newMode == OperatingMode.ACTIVE && isBootPhase.get()
                OperatingMode.ACTIVE -> false
                OperatingMode.DISARMED_RESTART_REQUIRED -> false
            }

            if (!isAllowed) {
                logger?.warning("[Optimizer] Rejected illegal mode transition: $current -> $newMode (reason: $transitionReason, bootPhase=${isBootPhase.get()})")
                return false
            }

            if (currentMode.compareAndSet(current, newMode)) {
                logger?.info("[Optimizer] Mode transition: $current -> $newMode (reason: $transitionReason)")
                return true
            }
        }
    }

    /**
     * Genuinely idempotent emergency disarm:
     * - First disarm reason is retained.
     * - Disarm listeners are notified once.
     * - Repeated calls do not overwrite useful forensic state.
     */
    fun disarm(disarmReasonText: String, operator: String = "SYSTEM"): Boolean {
        val formattedReason = "[$operator] $disarmReasonText"
        disarmReason.compareAndSet(null, formattedReason)
        val wasNonTerminal = currentMode.getAndSet(OperatingMode.DISARMED_RESTART_REQUIRED) != OperatingMode.DISARMED_RESTART_REQUIRED
        if (wasNonTerminal) {
            logger?.severe("[Optimizer] EMERGENCY DISARM triggered by $operator: $disarmReasonText")
        }
        notifyDisarmOnce()
        return true
    }

    private fun notifyDisarmOnce() {
        if (disarmNotified.compareAndSet(false, true)) {
            for (listener in disarmListeners) {
                try {
                    listener()
                } catch (t: Throwable) {
                    logger?.log(java.util.logging.Level.SEVERE, "[Optimizer] Error in disarm listener", t)
                }
            }
        }
    }
}
