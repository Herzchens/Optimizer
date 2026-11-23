package itzherzchen.optimizer.runtime

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.bootstrap.FingerprintResult
import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.observe.TransactionRiskScanner
import itzherzchen.optimizer.observe.TransactionRiskState
import itzherzchen.optimizer.superior.BlockAccountingFastPath
import itzherzchen.optimizer.superior.StackedFastReject
import itzherzchen.optimizer.wildtools.WildToolsBridge
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class OptimizerLifecycleListenerTest {

    private lateinit var config: OptimizerConfig
    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var actionContextStack: ActionContextStack
    private lateinit var stackedFastReject: StackedFastReject
    private lateinit var blockAccountingFastPath: BlockAccountingFastPath
    private lateinit var wildToolsBridge: WildToolsBridge

    private fun createValidCensus(): ListenerCensusResult {
        return ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyCensus(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return createValidCensus() as T
    }

    @BeforeEach
    fun setup() {
        config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true
        )
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        actionContextStack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { true })
        stackedFastReject = StackedFastReject(
            config, modeController, circuitBreaker, actionContextStack, counters,
            primaryThreadChecker = { true }
        )
        blockAccountingFastPath = BlockAccountingFastPath(
            config, modeController, circuitBreaker, actionContextStack, counters,
            primaryThreadChecker = { true }
        )
        wildToolsBridge = mock(WildToolsBridge::class.java)
        doReturn(true).`when`(wildToolsBridge).installHooks(anyCensus())
    }

    @Test
    fun `ServerLoad STARTUP runs final validation and arms ACTIVE if configured and compatible`() {
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters,
            fingerprintProvider = { FingerprintResult(true, emptyList(), emptyList()) },
            censusProvider = { createValidCensus() },
            riskScannerProvider = { TransactionRiskReport(TransactionRiskState.CLEAN, emptyList(), "No risk") }
        )

        val lifecycleListener = OptimizerLifecycleListener(
            optimizerConfig = config,
            modeController = modeController,
            compatibilityValidator = validator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath
        )

        assertEquals(OperatingMode.OBSERVE, modeController.mode)
        assertTrue(modeController.isBootPhase())

        val event = mock(ServerLoadEvent::class.java)
        `when`(event.type).thenReturn(ServerLoadEvent.LoadType.STARTUP)
        lifecycleListener.onServerLoad(event)

        assertEquals(OperatingMode.ACTIVE, modeController.mode)
        assertTrue(stackedFastReject.isEligible)
        assertTrue(blockAccountingFastPath.isEligible)
        assertFalse(modeController.isBootPhase(), "Boot activation window must be closed after STARTUP")
    }

    @Test
    fun `ServerLoad RELOAD triggers fail-closed terminal disarm and refuses rearm`() {
        val validator = CompatibilityValidator(
            config = config,
            modeController = modeController,
            counters = counters
        )

        val lifecycleListener = OptimizerLifecycleListener(
            optimizerConfig = config,
            modeController = modeController,
            compatibilityValidator = validator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath
        )

        val event = mock(ServerLoadEvent::class.java)
        `when`(event.type).thenReturn(ServerLoadEvent.LoadType.RELOAD)
        lifecycleListener.onServerLoad(event)

        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(modeController.reason!!.contains("ServerLoadEvent.RELOAD"))

        // Attempting to re-arm on STARTUP or transition must be rejected
        val startupEvent = mock(ServerLoadEvent::class.java)
        `when`(startupEvent.type).thenReturn(ServerLoadEvent.LoadType.STARTUP)
        lifecycleListener.onServerLoad(startupEvent)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
    }

    @Test
    fun `core dependency disable triggers terminal disarm`() {
        val validator = CompatibilityValidator(config = config, modeController = modeController, counters = counters)
        val lifecycleListener = OptimizerLifecycleListener(
            optimizerConfig = config,
            modeController = modeController,
            compatibilityValidator = validator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath
        )

        val mockWt = mock(Plugin::class.java)
        `when`(mockWt.name).thenReturn("WildTools")

        val disableEvent = mock(PluginDisableEvent::class.java)
        `when`(disableEvent.plugin).thenReturn(mockWt)
        lifecycleListener.onPluginDisable(disableEvent)

        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(modeController.reason!!.contains("WildTools"))
    }

    @Test
    fun `core dependency re-enable triggers terminal disarm`() {
        val validator = CompatibilityValidator(config = config, modeController = modeController, counters = counters)
        val lifecycleListener = OptimizerLifecycleListener(
            optimizerConfig = config,
            modeController = modeController,
            compatibilityValidator = validator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath
        )

        val mockSsb = mock(Plugin::class.java)
        `when`(mockSsb.name).thenReturn("SuperiorSkyblock2")

        val enableEvent = mock(PluginEnableEvent::class.java)
        `when`(enableEvent.plugin).thenReturn(mockSsb)
        lifecycleListener.onPluginEnable(enableEvent)

        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(modeController.reason!!.contains("SuperiorSkyblock2"))
    }

    @Test
    fun `late foreign plugin registration monotonically disables Module B and never hot re-enables`() {
        var scannerState = TransactionRiskState.CLEAN
        val riskScanner = mock(TransactionRiskScanner::class.java)
        `when`(riskScanner.scan()).thenAnswer {
            TransactionRiskReport(scannerState, if (scannerState == TransactionRiskState.RISK_FOUND) listOf("ForeignWandAudit") else emptyList(), "Audit")
        }

        val validator = CompatibilityValidator(config = config, modeController = modeController, counters = counters)
        val lifecycleListener = OptimizerLifecycleListener(
            optimizerConfig = config,
            modeController = modeController,
            compatibilityValidator = validator,
            wildToolsBridge = wildToolsBridge,
            stackedFastReject = stackedFastReject,
            blockAccountingFastPath = blockAccountingFastPath,
            riskScanner = riskScanner
        )

        blockAccountingFastPath.isEligible = true

        val foreignPlugin1 = mock(Plugin::class.java)
        `when`(foreignPlugin1.name).thenReturn("LateClaimGuard")

        // 1. Foreign plugin loads and introduces listener -> Module B disabled
        scannerState = TransactionRiskState.RISK_FOUND
        val enableEvent1 = mock(PluginEnableEvent::class.java)
        `when`(enableEvent1.plugin).thenReturn(foreignPlugin1)
        lifecycleListener.onPluginEnable(enableEvent1)

        assertFalse(blockAccountingFastPath.isEligible, "Module B must be disabled on foreign listener discovery")

        // 2. Another plugin loads later and scanner is now clean -> Module B must NOT re-enable (monotonicity!)
        scannerState = TransactionRiskState.CLEAN
        val foreignPlugin2 = mock(Plugin::class.java)
        `when`(foreignPlugin2.name).thenReturn("HarmlessChatPlugin")

        val enableEvent2 = mock(PluginEnableEvent::class.java)
        `when`(enableEvent2.plugin).thenReturn(foreignPlugin2)
        lifecycleListener.onPluginEnable(enableEvent2)

        assertFalse(blockAccountingFastPath.isEligible, "Module B must remain monotonically disabled until restart")
    }
}
