package itzherzchen.optimizer.wildtools

import itzherzchen.optimizer.bootstrap.ListenerCensusEntry
import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.runtime.ActionContextStack
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.LinkedList

class SyntheticListenerInstallerTest {

    private lateinit var modeController: ModeController
    private lateinit var circuitBreaker: CircuitBreaker
    private lateinit var counters: Counters
    private lateinit var stack: ActionContextStack
    private lateinit var installer: SyntheticListenerInstaller

    @BeforeEach
    fun setup() {
        modeController = ModeController(OperatingMode.OBSERVE)
        circuitBreaker = CircuitBreaker(modeController)
        counters = Counters()
        stack = ActionContextStack(circuitBreaker, counters)
        installer = SyntheticListenerInstaller(stack, modeController, circuitBreaker, counters)
    }

    private fun createRegisteredListener(pluginName: String, priority: EventPriority): RegisteredListener {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn(pluginName)
        val listener = object : Listener {}
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, priority, plugin, false)
    }

    private fun createValidCensusResult(): ListenerCensusResult {
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
            discrepancies = emptyList()
        )
    }

    @Test
    fun `normal install successfully wraps SSB HIGHEST and MONITOR listeners reversibly`() {
        val origNormal = createRegisteredListener("SuperiorSkyblock2", EventPriority.NORMAL)
        val origStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val origMonitor = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)

        val list: MutableList<RegisteredListener> = LinkedList(listOf(origNormal, origStacked, origMonitor))
        val census = createValidCensusResult()

        val installResult = installer.install(list, census)
        assertEquals(InstallResult.SUCCESS, installResult)
        assertTrue(installer.isInstalled)

        // Index 0 (NORMAL protection) must remain completely untouched
        assertSame(origNormal, list[0])

        // Indexes 1 and 2 must be replaced by wrappers
        assertNotSame(origStacked, list[1])
        assertNotSame(origMonitor, list[2])
        assertSame(installer.replacementStacked, list[1])
        assertSame(installer.replacementMonitor, list[2])

        // Preflight integrity verification must pass
        assertEquals(PreflightResult.OK, installer.verifyIntegrity())

        // Reversible uninstall
        val uninstallResult = installer.uninstall()
        assertEquals(UninstallResult.SUCCESS_REVERTED, uninstallResult)
        assertFalse(installer.isInstalled)

        // List must be restored to original instances
        assertSame(origStacked, list[1])
        assertSame(origMonitor, list[2])
    }

    @Test
    fun `uninstall detects list drift on reload and preserves list without overwriting`() {
        val origNormal = createRegisteredListener("SuperiorSkyblock2", EventPriority.NORMAL)
        val origStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val origMonitor = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)

        val list: MutableList<RegisteredListener> = LinkedList(listOf(origNormal, origStacked, origMonitor))
        val census = createValidCensusResult()

        assertEquals(InstallResult.SUCCESS, installer.install(list, census))

        // Simulate /tools reload replacing the list contents with new RegisteredListeners
        val newReloadedStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val newReloadedMonitor = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)
        list[1] = newReloadedStacked
        list[2] = newReloadedMonitor

        // verifyIntegrity must flag list drift
        assertEquals(PreflightResult.FAILED_LIST_DRIFT, installer.verifyIntegrity())

        // Uninstall must preserve the new reloaded elements without overwriting
        val uninstallResult = installer.uninstall()
        assertEquals(UninstallResult.LIST_DRIFT_PRESERVED, uninstallResult)
        assertFalse(installer.isInstalled)
        assertSame(newReloadedStacked, list[1])
        assertSame(newReloadedMonitor, list[2])
    }

    @Test
    fun `install fails when topology is invalid`() {
        val origStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val list: MutableList<RegisteredListener> = LinkedList(listOf(origStacked))
        val invalidCensus = ListenerCensusResult(
            isTopologyValid = false,
            entries = emptyList(),
            stackedBlocksIndex = -1,
            blockChangesIndex = -1,
            signsListenerIndex = -1,
            protectionListenerIndex = -1,
            discrepancies = listOf("Invalid topology")
        )

        val result = installer.install(list, invalidCensus)
        assertEquals(InstallResult.INVALID_TOPOLOGY, result)
        assertFalse(installer.isInstalled)
        assertSame(origStacked, list[0])
    }

    @Test
    fun `install fails when index is out of bounds`() {
        val origStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val list: MutableList<RegisteredListener> = LinkedList(listOf(origStacked))
        val oobCensus = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 5,
            blockChangesIndex = 10,
            signsListenerIndex = 10,
            protectionListenerIndex = 0,
            discrepancies = emptyList()
        )

        val result = installer.install(list, oobCensus)
        assertEquals(InstallResult.INVALID_INDEX, result)
        assertFalse(installer.isInstalled)
    }

    @Test
    fun `install fails when listener at index has mismatched plugin or priority`() {
        val wgListener = createRegisteredListener("WorldGuard", EventPriority.LOW)
        val origMonitor = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)
        val list: MutableList<RegisteredListener> = LinkedList(listOf(wgListener, origMonitor))

        val census = ListenerCensusResult(
            isTopologyValid = true,
            entries = emptyList(),
            stackedBlocksIndex = 0, // points to WorldGuard LOW instead of SSB HIGHEST
            blockChangesIndex = 1,
            signsListenerIndex = 1,
            protectionListenerIndex = -1,
            discrepancies = emptyList()
        )

        val result = installer.install(list, census)
        assertEquals(InstallResult.INVALID_TOPOLOGY, result)
        assertFalse(installer.isInstalled)
    }

    @Test
    fun `install is idempotent and rejects double installation`() {
        val origNormal = createRegisteredListener("SuperiorSkyblock2", EventPriority.NORMAL)
        val origStacked = createRegisteredListener("SuperiorSkyblock2", EventPriority.HIGHEST)
        val origMonitor = createRegisteredListener("SuperiorSkyblock2", EventPriority.MONITOR)

        val list: MutableList<RegisteredListener> = LinkedList(listOf(origNormal, origStacked, origMonitor))
        val census = createValidCensusResult()

        assertEquals(InstallResult.SUCCESS, installer.install(list, census))
        assertEquals(InstallResult.ALREADY_INSTALLED, installer.install(list, census))
    }

    @Test
    fun `uninstall when not installed returns NOT_INSTALLED`() {
        assertEquals(UninstallResult.NOT_INSTALLED, installer.uninstall())
    }
}
