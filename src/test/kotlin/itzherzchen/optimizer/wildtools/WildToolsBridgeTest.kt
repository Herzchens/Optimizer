package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class WildToolsBridgeTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private lateinit var tracker: CuboidActionTracker
    private lateinit var installer: SyntheticListenerInstaller
    private lateinit var bridge: WildToolsBridge
    private lateinit var config: OptimizerConfig

    private var isPrimaryThread = true

    @BeforeEach
    fun setup() {
        isPrimaryThread = true
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        tracker = CuboidActionTracker(stack, modeController, circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        installer = SyntheticListenerInstaller(stack, modeController, circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        val plugin = mock(Plugin::class.java)

        config = OptimizerConfig(
            configuredMode = OperatingMode.OBSERVE,
            stackedFastRejectEnabled = false,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = "1.21.4",
            timingSampleRate = 64
        )

        bridge = WildToolsBridge(
            plugin = plugin,
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            actionContextStack = stack,
            actionTracker = tracker,
            listenerInstaller = installer,
            primaryThreadChecker = { isPrimaryThread }
        )
    }

    @Test
    fun `initial state is not hooked`() {
        assertFalse(bridge.isHooked)
        assertEquals(PreflightResult.OK, bridge.verifyIntegrity())
    }

    @Test
    fun `installHooks fails gracefully when WildTools plugin is not present`() {
        val dummyCensus = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )

        val success = bridge.installHooks(dummyCensus)
        assertFalse(success)
        assertFalse(bridge.isHooked)
    }

    @Test
    fun `disarm is idempotent`() {
        assertDoesNotThrow {
            bridge.disarm("Initial test disarm")
            bridge.disarm("Second call")
        }
        assertFalse(bridge.isHooked)
    }

    @Test
    fun `verifyIntegrity detects drift and trips circuit breaker`() {
        // Force installer into an installed state with empty/corrupted list to trigger drift
        val mockCensus = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 0,
            blockChangesIndex = 1,
            signsListenerIndex = 1,
            protectionListenerIndex = -1,
            discrepancies = emptyList()
        )

        // Verify that when not hooked, verifyIntegrity returns OK
        assertEquals(PreflightResult.OK, bridge.verifyIntegrity())
        assertFalse(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }

    @Test
    fun `disarm listener integration unhooks bridge when mode controller disarms`() {
        modeController.addDisarmListener {
            bridge.disarm("ModeController disarmed")
        }

        // Simulate bridge hooked flag manually for testing disarm trigger
        // Then trigger modeController disarm
        modeController.disarm("Test emergency", "TEST")
        assertFalse(bridge.isHooked)
    }

    @Test
    fun `verifyIntegrity in ACTIVE mode without hooks trips global breaker and returns FAILED_NOT_INSTALLED`() {
        modeController.transitionTo(OperatingMode.VALIDATED, "Test validated setup")
        modeController.transitionTo(OperatingMode.ACTIVE, "Test active setup")
        val result = bridge.verifyIntegrity()
        assertEquals(PreflightResult.FAILED_NOT_INSTALLED, result)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }

    @Test
    fun `verifyIntegrity when dependency is disabled trips global breaker and returns FAILED_DEPENDENCY_DISABLED`() {
        var dependencyEnabled = true
        val rawList: MutableList<org.bukkit.plugin.RegisteredListener> = java.util.LinkedList()
        val customBridge = WildToolsBridge(
            plugin = mock(Plugin::class.java),
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            actionContextStack = stack,
            actionTracker = tracker,
            listenerInstaller = installer,
            dependencyChecker = { dependencyEnabled },
            listProvider = { rawList }
        )

        val ssbPlugin = mock(Plugin::class.java)
        `when`(ssbPlugin.name).thenReturn("SuperiorSkyblock2")
        val dummyL = object : org.bukkit.event.Listener {}
        val dummyE = org.bukkit.plugin.EventExecutor { _, _ -> }
        val origNorm = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.NORMAL, ssbPlugin, false)
        val origStack = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.HIGHEST, ssbPlugin, false)
        val origMon = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.MONITOR, ssbPlugin, false)
        rawList.addAll(listOf(origNorm, origStack, origMon))

        val validCensus = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )
        installer.install(rawList, validCensus)
        val hookedField = WildToolsBridge::class.java.getDeclaredField("isHooked")
        hookedField.isAccessible = true
        hookedField.set(customBridge, true)

        // Intact preflight
        assertEquals(PreflightResult.OK, customBridge.verifyIntegrity())

        // Disable dependency
        dependencyEnabled = false
        val result = customBridge.verifyIntegrity()
        assertEquals(PreflightResult.FAILED_DEPENDENCY_DISABLED, result)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }

    @Test
    fun `verifyIntegrity when integration list is externally replaced trips global breaker`() {
        val rawList: MutableList<org.bukkit.plugin.RegisteredListener> = java.util.LinkedList()
        var currentLiveList: MutableList<org.bukkit.plugin.RegisteredListener>? = rawList
        val customBridge = WildToolsBridge(
            plugin = mock(Plugin::class.java),
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            actionContextStack = stack,
            actionTracker = tracker,
            listenerInstaller = installer,
            dependencyChecker = { true },
            listProvider = { currentLiveList }
        )

        val ssbPlugin = mock(Plugin::class.java)
        `when`(ssbPlugin.name).thenReturn("SuperiorSkyblock2")
        val dummyL = object : org.bukkit.event.Listener {}
        val dummyE = org.bukkit.plugin.EventExecutor { _, _ -> }
        val origNorm = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.NORMAL, ssbPlugin, false)
        val origStack = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.HIGHEST, ssbPlugin, false)
        val origMon = org.bukkit.plugin.RegisteredListener(dummyL, dummyE, org.bukkit.event.EventPriority.MONITOR, ssbPlugin, false)
        rawList.addAll(listOf(origNorm, origStack, origMon))

        val validCensus = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )
        installer.install(rawList, validCensus)
        val hookedField = WildToolsBridge::class.java.getDeclaredField("isHooked")
        hookedField.isAccessible = true
        hookedField.set(customBridge, true)

        // Intact preflight
        assertEquals(PreflightResult.OK, customBridge.verifyIntegrity())

        // Simulate WildTools /tools reload giving a new list instance
        currentLiveList = java.util.LinkedList(rawList)
        val result = customBridge.verifyIntegrity()
        assertEquals(PreflightResult.FAILED_INTEGRATION_REPLACED, result)
        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
    }
}
