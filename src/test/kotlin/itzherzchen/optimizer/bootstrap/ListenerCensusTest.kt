package itzherzchen.optimizer.bootstrap

import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ListenerCensusTest {

    private fun createListener(
        pluginName: String,
        listener: Listener,
        priority: EventPriority,
        ignoreCancelled: Boolean = false
    ): RegisteredListener {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn(pluginName)
        val executor = mock(EventExecutor::class.java)
        return RegisteredListener(listener, executor, priority, plugin, ignoreCancelled)
    }

    private class MockBukkitEventsListener : Listener
    private class ForeignListener : Listener

    @Test
    fun `census reports invalid when WildTools is absent in standalone unit test`() {
        val census = ListenerCensus()
        val result = census.performCensus()

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.isNotEmpty())
        assertEquals(-1, result.stackedBlocksIndex)
        assertEquals(-1, result.blockChangesIndex)
    }

    @Test
    fun `auditListenerList verifies valid SSB topology`() {
        val ssbListener = MockBukkitEventsListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertTrue(result.isTopologyValid)
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(0, result.protectionListenerIndex)
        assertEquals(1, result.stackedBlocksIndex)
        assertEquals(2, result.blockChangesIndex)
        assertEquals(2, result.signsListenerIndex)
        assertEquals(3, result.entries.size)
    }

    @Test
    fun `auditListenerList verifies valid SSB topology with third-party protection present`() {
        val ssbListener = MockBukkitEventsListener()
        val wgListener = ForeignListener()

        val list = listOf(
            createListener("WorldGuard", wgListener, EventPriority.LOW),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertTrue(result.isTopologyValid)
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(1, result.protectionListenerIndex)
        assertEquals(2, result.stackedBlocksIndex)
        assertEquals(3, result.blockChangesIndex)
        assertEquals(4, result.entries.size)
    }

    @Test
    fun `auditListenerList fails when SSB Protection callback (NORMAL) is missing`() {
        val ssbListener = MockBukkitEventsListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertEquals(-1, result.protectionListenerIndex)
        assertTrue(result.discrepancies.any { it.contains("Protection callback (NORMAL) not found") })
    }

    @Test
    fun `auditListenerList fails when SSB StackedBlocks callback (HIGHEST) is missing`() {
        val ssbListener = MockBukkitEventsListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertEquals(-1, result.stackedBlocksIndex)
        assertTrue(result.discrepancies.any { it.contains("StackedBlocks callback (HIGHEST) not found") })
    }

    @Test
    fun `auditListenerList fails when SSB Accounting callback (MONITOR) is missing`() {
        val ssbListener = MockBukkitEventsListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertEquals(-1, result.blockChangesIndex)
        assertTrue(result.discrepancies.any { it.contains("BlockChanges/Signs callback (MONITOR) not found") })
    }

    @Test
    fun `auditListenerList fails when priority order is inverted (protection bypass risk)`() {
        val ssbListener = MockBukkitEventsListener()

        // HIGHEST placed before NORMAL
        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.any { it.contains("priority inversion") })
    }

    @Test
    fun `auditListenerList fails when SSB listener class is foreign or unexpected`() {
        val foreignListener = ForeignListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", foreignListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", foreignListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", foreignListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.any { it.contains("unexpected listener class") })
    }

    @Test
    fun `auditListenerList fails on duplicate priority for SSB`() {
        val ssbListener = MockBukkitEventsListener()

        val list = listOf(
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.NORMAL),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.HIGHEST),
            createListener("SuperiorSkyblock2", ssbListener, EventPriority.MONITOR)
        )

        val census = ListenerCensus()
        val result = census.auditListenerList(list)

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.any { it.contains("Duplicate SSB NORMAL listener") })
    }

    @Test
    fun `auditListenerList fails on empty list`() {
        val census = ListenerCensus()
        val result = census.auditListenerList(emptyList<Any>())

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.any { it.contains("list is empty") })
    }

    @Test
    fun `auditListenerList flags non-RegisteredListener items in raw list`() {
        val census = ListenerCensus()
        val result = census.auditListenerList(listOf("NotAListener"))

        assertFalse(result.isTopologyValid)
        assertTrue(result.discrepancies.any { it.contains("is not a RegisteredListener") })
    }
}
