package itzherzchen.optimizer.runtime

/**
 * Operating modes for HotPathGuard / Optimizer.
 *
 * State Transitions:
 * - OBSERVE: Default startup state. Passive observation, census, fingerprinting, metrics.
 *            No listeners are hooked; no game state is altered.
 * - VALIDATED: Runtime fingerprint, bytecode structure, and listener topology verified.
 *              Diagnostic milestone. Still zero mutation or hook active.
 * - ACTIVE: Explicitly enabled by operator config at clean server boot.
 *           Allows verified fast-path execution.
 * - DISARMED_RESTART_REQUIRED: Terminal circuit-breaker state triggered by invariant violation,
 *                              topology drift, reflection error, or manual emergency disarm.
 *                              Strict fail-closed passthrough. Cannot be rearmed without full server restart.
 */
enum class OperatingMode {
    OBSERVE,
    VALIDATED,
    ACTIVE,
    DISARMED_RESTART_REQUIRED;

    val isTerminal: Boolean
        get() = this == DISARMED_RESTART_REQUIRED

    val allowsFastPath: Boolean
        get() = this == ACTIVE
}
