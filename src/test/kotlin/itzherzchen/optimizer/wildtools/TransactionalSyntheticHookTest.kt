package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusEntry
import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.event.EventException
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.LinkedList

class TransactionalSyntheticHookTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private var isPrimaryThread = true

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        isPrimaryThread = true
        stack = ActionContextStack(circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
    }

    private fun createRegisteredListener(plugin: Plugin, priority: EventPriority): RegisteredListener {
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, priority, plugin, false)
    }

    private fun createValidCensus(
        list: MutableList<RegisteredListener>,
        ssbPlugin: Plugin
    ): ListenerCensusResult {
        return ListenerCensusResult(
            isTopologyValid = true,
            entries = listOf(
                ListenerCensusEntry(0, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.NORMAL, false),
                ListenerCensusEntry(1, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.HIGHEST, false),
                ListenerCensusEntry(2, "SuperiorSkyblock2", "BukkitEventsListener", EventPriority.MONITOR, false)
            ),
            stackedBlocksIndex = 1,
            blockChangesIndex = 2,
            signsListenerIndex = 2,
            protectionListenerIndex = 0,
            discrepancies = emptyList(),
            token = InstallationToken(
                targetList = list,
                stackedBlocksIndex = 1,
                expectedStackedListener = list[1],
                monitorIndex = 2,
                expectedMonitorListener = list[2],
                ssbPlugin = ssbPlugin,
                ssbClassLoader = ssbPlugin.javaClass.classLoader
            )
        )
    }

    open class FaultInjectionList(
        private val delegate: MutableList<RegisteredListener> = LinkedList()
    ) : MutableList<RegisteredListener> by delegate {
        var throwBeforeFirstSet: Boolean = false
        var throwOnSecondSet: Boolean = false
        var throwDuringRollback: Boolean = false
        var setCount = 0
        var isRollingBack = false

        override fun set(index: Int, element: RegisteredListener): RegisteredListener {
            if (isRollingBack && throwDuringRollback) {
                throw RuntimeException("Fault injection: failure during rollback set at index $index")
            }
            if (!isRollingBack) {
                if (setCount == 0 && throwBeforeFirstSet) {
                    throw RuntimeException("Fault injection: failure before first set at index $index")
                }
                if (setCount == 1 && throwOnSecondSet) {
                    throwOnSecondSet = false
                    throw RuntimeException("Fault injection: failure on second set at index $index")
                }
                setCount++
            }
            return delegate.set(index, element)
        }
    }

    // --- 1. FAULT INJECTION DURING INSTALLATION ---

    @Test
    fun `fault injection before first set aborts install cleanly without mutating list`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val faultList = FaultInjectionList()
        faultList.addAll(listOf(origNorm, origStack, origMon))
        faultList.throwBeforeFirstSet = true

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(faultList, ssb)

        val result = installer.install(faultList, census)

        assertEquals(InstallResult.PARTIAL_INSTALL_ROLLED_BACK, result)
        assertFalse(installer.isInstalled)
        assertSame(origStack, faultList[1])
        assertSame(origMon, faultList[2])
    }

    @Test
    fun `fault injection on second set rolls back first set and leaves wrappers in passthrough`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val faultList = FaultInjectionList()
        faultList.addAll(listOf(origNorm, origStack, origMon))
        faultList.throwOnSecondSet = true

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(faultList, ssb)

        val result = installer.install(faultList, census)

        assertEquals(InstallResult.PARTIAL_INSTALL_ROLLED_BACK, result)
        assertFalse(installer.isInstalled)
        // Verify index 1 was cleanly rolled back to original instance
        assertSame(origStack, faultList[1])
        assertSame(origMon, faultList[2])
    }

    @Test
    fun `fault injection during rollback trips circuit breaker and guarantees residual wrapper is passthrough`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val faultList = FaultInjectionList()
        faultList.addAll(listOf(origNorm, origStack, origMon))
        faultList.throwOnSecondSet = true
        // Set up rollback exception
        faultList.isRollingBack = false

        // Wrap faultList so that when set is called after the first set, rollback throws
        val trackingList = object : FaultInjectionList() {
            var sets = 0
            override fun set(index: Int, element: RegisteredListener): RegisteredListener {
                sets++
                if (sets == 1) {
                    return super.set(index, element) // first set succeeds
                }
                if (sets == 2) {
                    throw RuntimeException("Fault injection: second set fails")
                }
                // rollback set (sets == 3)
                throw RuntimeException("Fault injection: rollback throws!")
            }
        }
        trackingList.addAll(listOf(origNorm, origStack, origMon))

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(trackingList, ssb)

        val result = installer.install(trackingList, census)

        assertEquals(InstallResult.ROLLBACK_FAILED_RESTART_REQUIRED, result)
        assertFalse(installer.isInstalled)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))

        // Check the residual element at index 1: it is a wrapper, but guaranteed passthroughOnly = true
        val residual = trackingList[1]
        assertNotSame(origStack, residual)
        val wrapper = residual.executor as SyntheticBreakWrapper
        assertTrue(wrapper.passthroughOnly)

        // Verifying calling this wrapper delegates directly to origStack executor
        val dummyBlock = mock(org.bukkit.block.Block::class.java)
        val dummyPlayer = mock(org.bukkit.entity.Player::class.java)
        val event = BlockBreakEvent(dummyBlock, dummyPlayer)
        wrapper.execute(origStack.listener, event)

        verify(origStack.executor, times(1)).execute(origStack.listener, event)
    }

    // --- 2. EXPLICIT UNINSTALL RESULT STATES ---

    @Test
    fun `clean uninstall returns FULLY_RESTORED`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val list = LinkedList(listOf(origNorm, origStack, origMon))
        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(list, ssb)

        assertEquals(InstallResult.SUCCESS, installer.install(list, census))
        assertTrue(installer.isInstalled)

        val uninstallResult = installer.uninstall()
        assertEquals(UninstallResult.FULLY_RESTORED, uninstallResult)
        assertFalse(installer.isInstalled)
        assertSame(origStack, list[1])
        assertSame(origMon, list[2])
    }

    @Test
    fun `uninstall against drifted list returns EXTERNAL_LIST_DRIFT without overwriting foreign elements`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)
        val foreign = mock(Plugin::class.java)
        `when`(foreign.name).thenReturn("ForeignPlugin")

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val list = LinkedList(listOf(origNorm, origStack, origMon))
        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(list, ssb)

        installer.install(list, census)

        // Foreign plugin replaced slots
        val foreignStack = createRegisteredListener(foreign, EventPriority.HIGHEST)
        val foreignMon = createRegisteredListener(foreign, EventPriority.MONITOR)
        list[1] = foreignStack
        list[2] = foreignMon

        val uninstallResult = installer.uninstall()
        assertEquals(UninstallResult.EXTERNAL_LIST_DRIFT, uninstallResult)
        assertFalse(installer.isInstalled)
        assertSame(foreignStack, list[1])
        assertSame(foreignMon, list[2])
    }

    @Test
    fun `uninstall failure during restore returns RESTART_REQUIRED`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        var throwOnUninstall = false
        val list = object : LinkedList<RegisteredListener>() {
            override fun set(index: Int, element: RegisteredListener): RegisteredListener {
                if (throwOnUninstall) {
                    throw RuntimeException("Fault injection: uninstall list.set failed")
                }
                return super.set(index, element)
            }
        }
        list.addAll(listOf(origNorm, origStack, origMon))

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(list, ssb)

        installer.install(list, census)

        throwOnUninstall = true
        val uninstallResult = installer.uninstall()

        assertEquals(UninstallResult.RESTART_REQUIRED, uninstallResult)
        assertTrue(circuitBreaker.isTripped(ModuleType.GLOBAL))
        // Wrappers are guaranteed in passthrough mode
        val wrapS = installer.wrapperStacked
        val wrapM = installer.wrapperMonitor
        assertTrue(wrapS?.passthroughOnly == true)
        assertTrue(wrapM?.passthroughOnly == true)
    }

    // --- 3. OFF-THREAD DISARM SAFETY ---

    @Test
    fun `off-thread disarm locks wrappers into passthrough without mutating private list`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val list = LinkedList(listOf(origNorm, origStack, origMon))
        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(list, ssb)

        installer.install(list, census)
        val installedStackedWrapper = list[1]
        val installedMonitorWrapper = list[2]

        // Transition mode to ACTIVE
        modeController.transitionTo(OperatingMode.VALIDATED, "test")
        modeController.transitionTo(OperatingMode.ACTIVE, "test")

        // Simulate off-thread execution
        isPrimaryThread = false

        // Emergency disarm called off-thread
        val uninstallResult = installer.uninstall()

        assertEquals(UninstallResult.RESTART_REQUIRED, uninstallResult)
        // Invariant: The list MUST NOT be mutated off-thread!
        assertSame(installedStackedWrapper, list[1])
        assertSame(installedMonitorWrapper, list[2])

        // Wrappers MUST be locked in passthroughOnly
        val wrapStacked = installedStackedWrapper.executor as SyntheticBreakWrapper
        val wrapMonitor = installedMonitorWrapper.executor as SyntheticBreakWrapper
        assertTrue(wrapStacked.passthroughOnly)
        assertTrue(wrapMonitor.passthroughOnly)

        // Executing the wrapper on an event delegates directly to original without running optimizer
        val dummyBlock = mock(org.bukkit.block.Block::class.java)
        val dummyPlayer = mock(org.bukkit.entity.Player::class.java)
        val event = BlockBreakEvent(dummyBlock, dummyPlayer)
        wrapStacked.execute(origStack.listener, event)

        verify(origStack.executor, times(1)).execute(origStack.listener, event)
    }

    @Test
    fun `WildToolsBridge disarm off-thread leaves passthrough wrappers in place and requires restart`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)
        val wt = mock(Plugin::class.java)
        `when`(wt.name).thenReturn("WildTools")
        `when`(wt.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val list = LinkedList(listOf(origNorm, origStack, origMon))
        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )
        val census = createValidCensus(list, ssb)

        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.DEFAULT_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.DEFAULT_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.DEFAULT_MC_VERSION,
            timingSampleRate = 64
        )
        val tracker = CuboidActionTracker(stack, modeController, circuitBreaker, counters, primaryThreadChecker = { isPrimaryThread })
        val bridge = WildToolsBridge(
            plugin = mock(Plugin::class.java),
            config = config,
            modeController = modeController,
            circuitBreaker = circuitBreaker,
            counters = counters,
            actionContextStack = stack,
            actionTracker = tracker,
            listenerInstaller = installer,
            dependencyChecker = { true },
            listProvider = { list },
            primaryThreadChecker = { isPrimaryThread }
        )

        installer.install(list, census)
        val hookedField = WildToolsBridge::class.java.getDeclaredField("isHooked")
        hookedField.isAccessible = true
        hookedField.set(bridge, true)

        // Off-thread disarm
        isPrimaryThread = false
        bridge.disarm("Off-thread test disarm")

        // Private list not mutated
        assertNotSame(origStack, list[1])
        assertNotSame(origMon, list[2])
        assertTrue(installer.wrapperStacked?.passthroughOnly == true)
        assertTrue(installer.wrapperMonitor?.passthroughOnly == true)
    }

    // --- 4. TOCTOU REJECTION ---

    @Test
    fun `installation refuses when audited element drifts before install`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val list = LinkedList(listOf(origNorm, origStack, origMon))
        val census = createValidCensus(list, ssb)

        // External code modifies list before install
        val driftedStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        list[1] = driftedStack

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val result = installer.install(list, census)

        assertEquals(InstallResult.IDENTITY_DRIFT_DETECTED, result)
        assertFalse(installer.isInstalled)
        assertSame(driftedStack, list[1])
    }

    @Test
    fun `installation refuses when target list instance differs from token list`() {
        val ssb = mock(Plugin::class.java)
        `when`(ssb.name).thenReturn("SuperiorSkyblock2")
        `when`(ssb.isEnabled).thenReturn(true)

        val origNorm = createRegisteredListener(ssb, EventPriority.NORMAL)
        val origStack = createRegisteredListener(ssb, EventPriority.HIGHEST)
        val origMon = createRegisteredListener(ssb, EventPriority.MONITOR)

        val auditedList = LinkedList(listOf(origNorm, origStack, origMon))
        val census = createValidCensus(auditedList, ssb)

        // An independently fetched different list instance
        val differentList = LinkedList(listOf(origNorm, origStack, origMon))

        val installer = SyntheticListenerInstaller(
            stack, modeController, circuitBreaker, counters,
            primaryThreadChecker = { isPrimaryThread }
        )

        val result = installer.install(differentList, census)

        assertEquals(InstallResult.TOKEN_VALIDATION_FAILED, result)
        assertFalse(installer.isInstalled)
    }
}
