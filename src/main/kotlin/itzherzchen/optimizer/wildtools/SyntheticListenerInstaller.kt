package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
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
    ALREADY_INSTALLED,
    UNEXPECTED_ERROR
}

enum class UninstallResult {
    SUCCESS_REVERTED,
    LIST_DRIFT_PRESERVED,
    NOT_INSTALLED,
    UNEXPECTED_ERROR
}

enum class PreflightResult {
    OK,
    FAILED_NOT_INSTALLED,
    FAILED_LIST_DRIFT
}

/**
 * Manages the lifecycle of RegisteredListener wrappers in WildTools' claimingPluginsBreakMethods.
 *
 * Implements Plan.md Section 4.4 and Section 2:
 * - Wrap only SuperiorSkyblock2 HIGHEST (stack) and MONITOR (accounting) callbacks.
 * - Leaves SSB NORMAL (protection), WorldGuard, and all third-party listeners untouched.
 * - Identity-checked, reversible install and uninstall.
 * - Atomic passthrough switch on disarm before attempting list restoration.
 * - Preserves list on list drift (e.g. /tools reload) without clobbering new listeners.
 */
class SyntheticListenerInstaller(
    private val actionContextStack: ActionContextStack,
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    private val logger: Logger? = null,
    var stackedFastReject: itzherzchen.optimizer.superior.StackedFastReject? = null
) {
    @Volatile
    var isInstalled: Boolean = false
        private set

    private var targetList: MutableList<RegisteredListener>? = null

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
     * Reversibly installs wrappers into claimingPluginsBreakMethods at verified SSB callback indexes.
     */
    @Synchronized
    fun install(
        rawList: MutableList<RegisteredListener>,
        censusResult: ListenerCensusResult
    ): InstallResult {
        if (isInstalled) {
            logger?.warning("[Optimizer] SyntheticListenerInstaller.install called while already installed.")
            return InstallResult.ALREADY_INSTALLED
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

        // Verify priorities and plugin identity
        if (origStacked.priority != EventPriority.HIGHEST || origStacked.plugin.name != "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Target stacked listener at index $sIndex does not match expected SSB HIGHEST callback!")
            return InstallResult.INVALID_TOPOLOGY
        }

        if (origMonitor.priority != EventPriority.MONITOR || origMonitor.plugin.name != "SuperiorSkyblock2") {
            logger?.severe("[Optimizer] Target monitor listener at index $mIndex does not match expected SSB MONITOR callback!")
            return InstallResult.INVALID_TOPOLOGY
        }

        // Validate public RegisteredListener constructor behavior before mutating
        if (!validateConstructor(origStacked.plugin)) {
            logger?.severe("[Optimizer] RegisteredListener constructor validation failed!")
            return InstallResult.CONSTRUCTOR_VALIDATION_FAILED
        }

        try {
            val wrapStacked = SyntheticBreakWrapper(
                original = origStacked,
                type = SyntheticCallbackType.STACKED_BLOCKS,
                actionContextStack = actionContextStack,
                modeController = modeController,
                circuitBreaker = circuitBreaker,
                counters = counters,
                logger = logger,
                stackedFastReject = stackedFastReject
            )

            val wrapMonitor = SyntheticBreakWrapper(
                original = origMonitor,
                type = SyntheticCallbackType.ACCOUNTING,
                actionContextStack = actionContextStack,
                modeController = modeController,
                circuitBreaker = circuitBreaker,
                counters = counters,
                logger = logger
            )

            val repS = RegisteredListener(
                origStacked.listener,
                wrapStacked,
                origStacked.priority,
                origStacked.plugin,
                origStacked.isIgnoringCancelled
            )

            val repM = RegisteredListener(
                origMonitor.listener,
                wrapMonitor,
                origMonitor.priority,
                origMonitor.plugin,
                origMonitor.isIgnoringCancelled
            )

            // Reversibly replace elements in list
            rawList[sIndex] = repS
            rawList[mIndex] = repM

            targetList = rawList
            savedStackedOriginal = origStacked
            savedMonitorOriginal = origMonitor
            replacementStacked = repS
            replacementMonitor = repM
            wrapperStacked = wrapStacked
            wrapperMonitor = wrapMonitor
            stackedBlocksIndex = sIndex
            monitorIndex = mIndex
            isInstalled = true

            logger?.info(
                "[Optimizer] Reversibly installed SyntheticBreakWrappers: STACKED_BLOCKS at index $sIndex, ACCOUNTING at index $mIndex."
            )
            return InstallResult.SUCCESS
        } catch (t: Throwable) {
            logger?.log(Level.SEVERE, "[Optimizer] Unexpected error installing synthetic break wrappers", t)
            return InstallResult.UNEXPECTED_ERROR
        }
    }

    /**
     * Uninstalls wrappers:
     * 1. Immediately switches wrappers to passthroughOnly atomically.
     * 2. If list identity and elements are intact, reverts list elements to originals.
     *    If list was reloaded/replaced externally, preserves new elements and logs drift.
     */
    @Synchronized
    fun uninstall(): UninstallResult {
        if (!isInstalled) return UninstallResult.NOT_INSTALLED

        try {
            // Step 1: Immediately disarm wrappers
            wrapperStacked?.passthroughOnly = true
            wrapperMonitor?.passthroughOnly = true

            val list = targetList
            val origS = savedStackedOriginal
            val origM = savedMonitorOriginal
            val repS = replacementStacked
            val repM = replacementMonitor
            val sIdx = stackedBlocksIndex
            val mIdx = monitorIndex

            var result = UninstallResult.LIST_DRIFT_PRESERVED

            // Step 2: Safe, identity-checked restoration
            if (list != null && origS != null && origM != null && repS != null && repM != null) {
                if (sIdx in list.indices && mIdx in list.indices) {
                    val currentS = list[sIdx]
                    val currentM = list[mIdx]
                    if (currentS === repS && currentM === repM) {
                        list[sIdx] = origS
                        list[mIdx] = origM
                        result = UninstallResult.SUCCESS_REVERTED
                        logger?.info("[Optimizer] Successfully reverted RegisteredListener objects in claimingPluginsBreakMethods.")
                    } else {
                        logger?.warning(
                            "[Optimizer] List drift detected during uninstall: elements at indexes ($sIdx, $mIdx) do not match our wrappers. Preserving current list without overwriting."
                        )
                    }
                }
            }

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

            return result
        } catch (t: Throwable) {
            logger?.log(Level.SEVERE, "[Optimizer] Unexpected error during uninstall", t)
            isInstalled = false
            return UninstallResult.UNEXPECTED_ERROR
        }
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

        if (sIdx !in list.indices || mIdx !in list.indices) {
            return PreflightResult.FAILED_LIST_DRIFT
        }

        if (list[sIdx] !== repS || list[mIdx] !== repM) {
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
