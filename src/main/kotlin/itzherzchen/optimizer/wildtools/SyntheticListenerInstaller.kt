package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import java.util.logging.Level
import java.util.logging.Logger

enum class InstallResult {
    SUCCESS,
    INVALID_TOPOLOGY,
    INVALID_INDEX,
    CONSTRUCTOR_VALIDATION_FAILED,
    TOKEN_VALIDATION_FAILED,
    IDENTITY_DRIFT_DETECTED,
    ALREADY_INSTALLED,
    THREAD_SAFETY_VIOLATION,
    PARTIAL_INSTALL_ROLLED_BACK,
    ROLLBACK_FAILED_RESTART_REQUIRED,
    UNEXPECTED_ERROR
}

enum class UninstallResult {
    FULLY_RESTORED,
    RESIDUAL_PASSTHROUGH_REMAINS,
    EXTERNAL_LIST_DRIFT,
    RESTART_REQUIRED,
    NOT_INSTALLED;

    companion object {
        @Deprecated("Use FULLY_RESTORED", ReplaceWith("FULLY_RESTORED"))
        val SUCCESS_REVERTED = FULLY_RESTORED

        @Deprecated("Use EXTERNAL_LIST_DRIFT", ReplaceWith("EXTERNAL_LIST_DRIFT"))
        val LIST_DRIFT_PRESERVED = EXTERNAL_LIST_DRIFT
    }
}

enum class PreflightResult {
    OK,
    FAILED_NOT_INSTALLED,
    FAILED_LIST_DRIFT,
    FAILED_DEPENDENCY_DISABLED,
    FAILED_INTEGRATION_REPLACED
}

/**
 * Manages the lifecycle of RegisteredListener wrappers in WildTools' claimingPluginsBreakMethods.
 *
 * Implements Plan.md Section 4.4 and Section 2:
 * - Wrap only SuperiorSkyblock2 HIGHEST (stack) and MONITOR (accounting) callbacks.
 * - Transactional installation with atomic rollback on fault injection.
 * - Leaves SSB NORMAL (protection), WorldGuard, and all third-party listeners untouched.
 * - Reversible emergency disarm on ANY thread without off-thread list mutation.
 * - Explicit uninstall result states distinguishing full restoration, drift, and restart required.
 */
class SyntheticListenerInstaller(
    private val actionContextStack: ActionContextStack,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    private val logger: Logger? = null,
    var stackedFastReject: itzherzchen.optimizer.superior.StackedFastReject? = null,
    var blockAccountingFastPath: itzherzchen.optimizer.superior.BlockAccountingFastPath? = null,
    private val primaryThreadChecker: () -> Boolean = {
        try {
            Bukkit.isPrimaryThread()
        } catch (_: Throwable) {
            false
        }
    }
) {
    @Volatile
    var isInstalled: Boolean = false
        private set

    var targetList: MutableList<RegisteredListener>? = null
        private set

    var savedStackedOriginal: RegisteredListener? = null
        private set

    var savedMonitorOriginal: RegisteredListener? = null
        private set

    var replacementStacked: RegisteredListener? = null
        private set

    var replacementMonitor: RegisteredListener? = null
        private set

    var wrapperStacked: SyntheticBreakWrapper? = null
        private set

    var wrapperMonitor: SyntheticBreakWrapper? = null
        private set

    var stackedBlocksIndex: Int = -1
        private set

    var monitorIndex: Int = -1
        private set

    /**
     * Thread-safe emergency disarm: locks any existing wrappers into passthrough mode immediately.
     * Can be safely called from ANY thread at any time.
     */
    fun emergencyDisarm() {
        wrapperStacked?.passthroughOnly = true
        wrapperMonitor?.passthroughOnly = true
    }

    /**
     * Transactionally installs wrappers using a verified InstallationToken.
     * Guarantees that if any mutation fails, clean rollback is attempted and any wrapper
     * left behind is permanently forced into passthrough-only mode.
     */
    @Synchronized
    fun install(token: InstallationToken): InstallResult {
        if (!primaryThreadChecker()) {
            logger?.severe("[Optimizer] SyntheticListenerInstaller.install called off-main-thread! Rejecting installation.")
            return InstallResult.THREAD_SAFETY_VIOLATION
        }

        if (isInstalled) {
            logger?.warning("[Optimizer] SyntheticListenerInstaller.install called while already installed.")
            return InstallResult.ALREADY_INSTALLED
        }

        val list = token.targetList
        val sIdx = token.stackedBlocksIndex
        val mIdx = token.monitorIndex

        if (sIdx !in list.indices || mIdx !in list.indices || sIdx == mIdx) {
            logger?.severe("[Optimizer] Cannot install listeners: callback indexes out of bounds ($sIdx, $mIdx, size=${list.size})")
            return InstallResult.INVALID_INDEX
        }

        val liveS = list[sIdx]
        val liveM = list[mIdx]

        // Revalidate exact identities immediately before mutation to eliminate TOCTOU
        if (liveS !== token.expectedStackedListener || liveM !== token.expectedMonitorListener) {
            logger?.severe("[Optimizer] TOCTOU detected: listener elements drifted between audit and install!")
            return InstallResult.IDENTITY_DRIFT_DETECTED
        }

        if (liveS.plugin !== token.expectedStackedListener.plugin || liveM.plugin !== token.expectedMonitorListener.plugin) {
            logger?.severe("[Optimizer] Plugin instance mismatch on target listeners!")
            return InstallResult.IDENTITY_DRIFT_DETECTED
        }

        if (liveS.plugin.name != "SuperiorSkyblock2" || liveM.plugin.name != "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Target listeners are not SuperiorSkyblock2!")
            return InstallResult.INVALID_TOPOLOGY
        }

        if (liveS.priority != EventPriority.HIGHEST || liveM.priority != EventPriority.MONITOR) {
            logger?.severe("[Optimizer] Priority mismatch on target listeners!")
            return InstallResult.INVALID_TOPOLOGY
        }

        // Validate public RegisteredListener constructor behavior before mutating
        if (!validateConstructor(liveS.plugin)) {
            logger?.severe("[Optimizer] RegisteredListener constructor validation failed!")
            return InstallResult.CONSTRUCTOR_VALIDATION_FAILED
        }

        // Prepare wrappers
        val wrapStacked = SyntheticBreakWrapper(
            original = liveS,
            type = SyntheticCallbackType.STACKED_BLOCKS,
            actionContextStack = actionContextStack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            logger = logger,
            primaryThreadChecker = primaryThreadChecker,
            stackedFastReject = stackedFastReject
        )

        val wrapMonitor = SyntheticBreakWrapper(
            original = liveM,
            type = SyntheticCallbackType.ACCOUNTING,
            actionContextStack = actionContextStack,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            logger = logger,
            primaryThreadChecker = primaryThreadChecker,
            blockAccountingFastPath = blockAccountingFastPath
        )

        val repS = RegisteredListener(
            liveS.listener,
            wrapStacked,
            liveS.priority,
            liveS.plugin,
            liveS.isIgnoringCancelled
        )

        val repM = RegisteredListener(
            liveM.listener,
            wrapMonitor,
            liveM.priority,
            liveM.plugin,
            liveM.isIgnoringCancelled
        )

        // Transactional mutation
        var stackedInstalled = false
        var monitorInstalled = false
        var mutationError: Throwable? = null

        try {
            list[sIdx] = repS
            stackedInstalled = true
            list[mIdx] = repM
            monitorInstalled = true
        } catch (t: Throwable) {
            mutationError = t
        }

        if (mutationError != null) {
            logger?.log(
                Level.SEVERE,
                "[Optimizer] Exception during listener installation mutation. Executing transactional rollback.",
                mutationError
            )

            // Step A: Immediately switch any wrapper to passthrough
            wrapStacked.passthroughOnly = true
            wrapMonitor.passthroughOnly = true

            // Step B: Rollback mutations safely
            var rollbackFailed = false
            if (monitorInstalled) {
                try {
                    if (list[mIdx] === repM) {
                        list[mIdx] = liveM
                    } else {
                        rollbackFailed = true
                        logger?.severe("[Optimizer] Target monitor element drifted externally during install failure; refusing to overwrite!")
                    }
                } catch (rbEx: Throwable) {
                    rollbackFailed = true
                    logger?.log(Level.SEVERE, "[Optimizer] Exception rolling back monitor wrapper", rbEx)
                }
            }

            if (stackedInstalled) {
                try {
                    if (list[sIdx] === repS) {
                        list[sIdx] = liveS
                    } else {
                        rollbackFailed = true
                        logger?.severe("[Optimizer] Target stacked element drifted externally during install failure; refusing to overwrite!")
                    }
                } catch (rbEx: Throwable) {
                    rollbackFailed = true
                    logger?.log(Level.SEVERE, "[Optimizer] Exception rolling back stacked wrapper", rbEx)
                }
            }

            return if (rollbackFailed) {
                circuitBreaker.trip(
                    ModuleType.GLOBAL,
                    "Transactional listener installation failed and clean rollback could not be proven: ${mutationError.message}",
                    mutationError
                )
                InstallResult.ROLLBACK_FAILED_RESTART_REQUIRED
            } else {
                InstallResult.PARTIAL_INSTALL_ROLLED_BACK
            }
        }

        targetList = list
        savedStackedOriginal = liveS
        savedMonitorOriginal = liveM
        replacementStacked = repS
        replacementMonitor = repM
        wrapperStacked = wrapStacked
        wrapperMonitor = wrapMonitor
        stackedBlocksIndex = sIdx
        monitorIndex = mIdx
        isInstalled = true

        logger?.info(
            "[Optimizer] Reversibly installed SyntheticBreakWrappers: STACKED_BLOCKS at index $sIdx, ACCOUNTING at index $mIdx."
        )
        return InstallResult.SUCCESS
    }

    /**
     * Backward-compatible install helper that creates an InstallationToken if none is provided.
     */
    @Synchronized
    fun install(
        rawList: MutableList<RegisteredListener>,
        censusResult: ListenerCensusResult
    ): InstallResult {
        if (!primaryThreadChecker()) {
            logger?.severe("[Optimizer] SyntheticListenerInstaller.install called off-main-thread! Rejecting installation.")
            return InstallResult.THREAD_SAFETY_VIOLATION
        }

        val existingToken = censusResult.token
        if (existingToken != null) {
            if (existingToken.targetList !== rawList) {
                logger?.severe("[Optimizer] Provided list does not match audited token list!")
                return InstallResult.TOKEN_VALIDATION_FAILED
            }
            return install(existingToken)
        }

        if (!censusResult.isTopologyValid) {
            logger?.severe("[Optimizer] Cannot install listeners: listener topology census is invalid!")
            return InstallResult.INVALID_TOPOLOGY
        }

        val sIndex = censusResult.stackedBlocksIndex
        val mIndex = censusResult.blockChangesIndex

        if (sIndex < 0 || sIndex >= rawList.size || mIndex < 0 || mIndex >= rawList.size) {
            logger?.severe("[Optimizer] Cannot install listeners: callback indexes out of bounds (sIndex=$sIndex, mIndex=$mIndex, listSize=${rawList.size})")
            return InstallResult.INVALID_INDEX
        }

        val origStacked = rawList[sIndex]
        val origMonitor = rawList[mIndex]

        if (origStacked.priority != EventPriority.HIGHEST || origStacked.plugin.name != "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Target stacked listener at index $sIndex does not match expected SSB HIGHEST callback!")
            return InstallResult.INVALID_TOPOLOGY
        }

        if (origMonitor.priority != EventPriority.MONITOR || origMonitor.plugin.name != "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Target monitor listener at index $mIndex does not match expected SSB MONITOR callback!")
            return InstallResult.INVALID_TOPOLOGY
        }

        val token = InstallationToken(
            targetList = rawList,
            stackedBlocksIndex = sIndex,
            expectedStackedListener = origStacked,
            monitorIndex = mIndex,
            expectedMonitorListener = origMonitor,
            ssbPlugin = origStacked.plugin,
            ssbClassLoader = origStacked.plugin.javaClass.classLoader
        )

        return install(token)
    }

    /**
     * Uninstalls wrappers:
     * 1. Immediately switches wrappers to passthroughOnly atomically on ANY thread.
     * 2. Checks primary thread before attempting list mutation. Off-thread mutation is strictly rejected.
     * 3. If list identity and elements are intact, reverts list elements to originals.
     * 4. If list was reloaded/replaced externally, preserves new elements and logs drift.
     */
    @Synchronized
    fun uninstall(): UninstallResult {
        // Step 1: Immediately disarm wrappers on ANY thread
        emergencyDisarm()

        // Step 2: Thread check - NEVER mutate private list off-thread
        if (!primaryThreadChecker()) {
            logger?.severe(
                "[Optimizer] SyntheticListenerInstaller.uninstall called off-main-thread on '${Thread.currentThread().name}'. " +
                "Wrappers set to passthrough, but physical list restoration rejected off-thread."
            )
            return UninstallResult.RESTART_REQUIRED
        }

        if (!isInstalled) return UninstallResult.NOT_INSTALLED

        try {
            val list = targetList
            val origS = savedStackedOriginal
            val origM = savedMonitorOriginal
            val repS = replacementStacked
            val repM = replacementMonitor
            val sIdx = stackedBlocksIndex
            val mIdx = monitorIndex

            if (list == null || origS == null || origM == null || repS == null || repM == null ||
                sIdx !in list.indices || mIdx !in list.indices
            ) {
                clearState()
                return UninstallResult.EXTERNAL_LIST_DRIFT
            }

            val currentS = list[sIdx]
            val currentM = list[mIdx]
            val sMatches = currentS === repS
            val mMatches = currentM === repM

            if (!sMatches && !mMatches) {
                // Total external list drift
                logger?.warning("[Optimizer] External list drift detected: neither wrapper matches list slots. Preserving list.")
                clearState()
                return UninstallResult.EXTERNAL_LIST_DRIFT
            }

            var stackedRestored = false
            var monitorRestored = false
            var restoreThrew = false

            if (sMatches) {
                try {
                    list[sIdx] = origS
                    stackedRestored = true
                } catch (t: Throwable) {
                    restoreThrew = true
                    logger?.log(Level.SEVERE, "[Optimizer] Failed to restore stacked listener during uninstall", t)
                }
            }

            if (mMatches) {
                try {
                    list[mIdx] = origM
                    monitorRestored = true
                } catch (t: Throwable) {
                    restoreThrew = true
                    logger?.log(Level.SEVERE, "[Optimizer] Failed to restore monitor listener during uninstall", t)
                }
            }

            if (restoreThrew) {
                circuitBreaker.trip(ModuleType.GLOBAL, "Exception during synthetic listener list restoration")
                return UninstallResult.RESTART_REQUIRED
            }

            if ((sMatches && !stackedRestored) || (mMatches && !monitorRestored)) {
                return UninstallResult.RESIDUAL_PASSTHROUGH_REMAINS
            }

            if (!sMatches || !mMatches) {
                clearState()
                return UninstallResult.EXTERNAL_LIST_DRIFT
            }

            clearState()
            logger?.info("[Optimizer] Successfully reverted RegisteredListener objects in claimingPluginsBreakMethods.")
            return UninstallResult.FULLY_RESTORED
        } catch (t: Throwable) {
            logger?.log(Level.SEVERE, "[Optimizer] Unexpected error during uninstall", t)
            circuitBreaker.trip(ModuleType.GLOBAL, "Unexpected error during uninstall: ${t.message}", t)
            return UninstallResult.RESTART_REQUIRED
        }
    }

    private fun clearState() {
        isInstalled = false
        targetList = null
        savedStackedOriginal = null
        savedMonitorOriginal = null
        replacementStacked = null
        replacementMonitor = null
        wrapperStacked = null
        wrapperMonitor = null
        stackedBlocksIndex = -1
        monitorIndex = -1
    }

    /**
     * Fast O(1) integrity verification checking that our installed wrappers are still in place.
     */
    fun verifyIntegrity(): PreflightResult {
        if (!isInstalled) return PreflightResult.OK

        val list = targetList ?: return PreflightResult.FAILED_LIST_DRIFT
        val sIdx = stackedBlocksIndex
        val mIdx = monitorIndex
        val repS = replacementStacked ?: return PreflightResult.FAILED_LIST_DRIFT
        val repM = replacementMonitor ?: return PreflightResult.FAILED_LIST_DRIFT
        val origS = savedStackedOriginal ?: return PreflightResult.FAILED_LIST_DRIFT
        val origM = savedMonitorOriginal ?: return PreflightResult.FAILED_LIST_DRIFT

        if (sIdx !in list.indices || mIdx !in list.indices) {
            return PreflightResult.FAILED_LIST_DRIFT
        }

        if (list[sIdx] !== repS || list[mIdx] !== repM) {
            return PreflightResult.FAILED_LIST_DRIFT
        }

        if (repS.listener !== origS.listener || repM.listener !== origM.listener) {
            return PreflightResult.FAILED_LIST_DRIFT
        }

        return PreflightResult.OK
    }

    private fun validateConstructor(plugin: Plugin?): Boolean {
        return try {
            val dummyListener = object : Listener {}
            val dummyExecutor = EventExecutor { _, _ -> }
            val testRL = RegisteredListener(
                dummyListener,
                dummyExecutor,
                EventPriority.NORMAL,
                plugin ?: return false,
                false
            )
            testRL.listener === dummyListener &&
                    testRL.executor === dummyExecutor &&
                    testRL.priority == EventPriority.NORMAL &&
                    testRL.plugin === plugin &&
                    !testRL.isIgnoringCancelled
        } catch (_: Throwable) {
            false
        }
    }
}
