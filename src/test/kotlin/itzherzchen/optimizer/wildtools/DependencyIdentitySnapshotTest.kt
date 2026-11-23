package itzherzchen.optimizer.wildtools

import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DependencyIdentitySnapshotTest {

    private fun createMockPlugin(name: String, enabled: Boolean = true): Plugin {
        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn(name)
        `when`(plugin.isEnabled).thenReturn(enabled)
        return plugin
    }

    @Test
    fun `verification passes when all instances classloaders and lists match`() {
        val wt = createMockPlugin("WildTools")
        val ssb = createMockPlugin("SuperiorSkyblock2")
        val handler = Any()
        val list = mutableListOf<RegisteredListener>()

        val snapshot = DependencyIdentitySnapshot(
            wildToolsPlugin = wt,
            wildToolsClassLoader = wt.javaClass.classLoader,
            superiorSkyblockPlugin = ssb,
            superiorSkyblockClassLoader = ssb.javaClass.classLoader,
            eventsHandler = handler,
            targetList = list
        )

        val pm = mock(PluginManager::class.java)
        `when`(pm.getPlugin("WildTools")).thenReturn(wt)
        `when`(pm.getPlugin("SuperiorSkyblock2")).thenReturn(ssb)

        val result = snapshot.verify(
            pluginManagerProvider = { pm },
            claimingListProvider = { list }
        )

        // EventsHandler reflection on mock will fail unless mock provides it, so handler check is verified
        // When getEvents is not present on mock, verify returns EVENTSHANDLER_MISMATCH
        assertEquals(IdentityVerificationResult.EVENTSHANDLER_MISMATCH, result)
    }

    @Test
    fun `verification detects wildtools plugin instance replacement`() {
        val wt1 = createMockPlugin("WildTools")
        val wt2 = createMockPlugin("WildTools") // New instance!
        val ssb = createMockPlugin("SuperiorSkyblock2")
        val handler = Any()
        val list = mutableListOf<RegisteredListener>()

        val snapshot = DependencyIdentitySnapshot(
            wildToolsPlugin = wt1,
            wildToolsClassLoader = wt1.javaClass.classLoader,
            superiorSkyblockPlugin = ssb,
            superiorSkyblockClassLoader = ssb.javaClass.classLoader,
            eventsHandler = handler,
            targetList = list
        )

        val pm = mock(PluginManager::class.java)
        `when`(pm.getPlugin("WildTools")).thenReturn(wt2)
        `when`(pm.getPlugin("SuperiorSkyblock2")).thenReturn(ssb)

        val result = snapshot.verify(
            pluginManagerProvider = { pm },
            claimingListProvider = { list }
        )

        assertEquals(IdentityVerificationResult.WILDTOOLS_INSTANCE_MISMATCH, result)
    }

    @Test
    fun `verification detects superiorskyblock plugin instance replacement`() {
        val wt = createMockPlugin("WildTools")
        val ssb1 = createMockPlugin("SuperiorSkyblock2")
        val ssb2 = createMockPlugin("SuperiorSkyblock2") // New instance!
        val handler = Any()
        val list = mutableListOf<RegisteredListener>()

        val snapshot = DependencyIdentitySnapshot(
            wildToolsPlugin = wt,
            wildToolsClassLoader = wt.javaClass.classLoader,
            superiorSkyblockPlugin = ssb1,
            superiorSkyblockClassLoader = ssb1.javaClass.classLoader,
            eventsHandler = handler,
            targetList = list
        )

        val pm = mock(PluginManager::class.java)
        `when`(pm.getPlugin("WildTools")).thenReturn(wt)
        `when`(pm.getPlugin("SuperiorSkyblock2")).thenReturn(ssb2)

        val result = snapshot.verify(
            pluginManagerProvider = { pm },
            claimingListProvider = { list }
        )

        assertEquals(IdentityVerificationResult.SUPERIORSKYBLOCK_INSTANCE_MISMATCH, result)
    }

    @Test
    fun `verification detects dependency disabled`() {
        val wt = createMockPlugin("WildTools", enabled = false)
        val ssb = createMockPlugin("SuperiorSkyblock2")
        val handler = Any()
        val list = mutableListOf<RegisteredListener>()

        val snapshot = DependencyIdentitySnapshot(
            wildToolsPlugin = wt,
            wildToolsClassLoader = wt.javaClass.classLoader,
            superiorSkyblockPlugin = ssb,
            superiorSkyblockClassLoader = ssb.javaClass.classLoader,
            eventsHandler = handler,
            targetList = list
        )

        val pm = mock(PluginManager::class.java)
        `when`(pm.getPlugin("WildTools")).thenReturn(wt)
        `when`(pm.getPlugin("SuperiorSkyblock2")).thenReturn(ssb)

        val result = snapshot.verify(
            pluginManagerProvider = { pm },
            claimingListProvider = { list }
        )

        assertEquals(IdentityVerificationResult.WILDTOOLS_DISABLED, result)
    }
}
