package itzherzchen.optimizer.superior

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class SuperiorDispatcherAuditorTest {

    private val auditor = SuperiorDispatcherAuditor()

    @Test
    fun `null plugin returns fail closed audit result`() {
        val result = auditor.audit(null)
        assertFalse(result.moduleAEligible)
        assertFalse(result.moduleBEligible)
        assertTrue(result.discrepancies.any { it.contains("null") })
    }

    @Test
    fun `plugin throwing during dispatcher lookup fails closed`() {
        val plugin = mock(Plugin::class.java)
        val result = auditor.audit(plugin)
        assertFalse(result.moduleAEligible)
        assertFalse(result.moduleBEligible)
    }

    @Test
    fun `valid composition with exact standard SSB callbacks passes both modules`() {
        val highest = listOf(SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER)
        val monitor = listOf(SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER, SuperiorDispatcherAuditor.EXPECTED_CHANGES_LISTENER)

        val result = auditor.evaluateComposition(highest, monitor)

        assertTrue(result.moduleAEligible, "Module A must be eligible with exact StackedBlocksListener")
        assertTrue(result.moduleBEligible, "Module B must be eligible with SignsListener and BlockChangesListener")
        assertTrue(result.discrepancies.isEmpty())
        assertEquals(listOf(SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER), result.highestCallbacks)
        assertEquals(2, result.monitorCallbacks.size)
    }

    @Test
    fun `unknown callback at HIGHEST refuses Module A while Module B remains eligible`() {
        val highest = listOf(
            SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER,
            "com.example.addon.CustomAddonHighestListener"
        )
        val monitor = listOf(SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER, SuperiorDispatcherAuditor.EXPECTED_CHANGES_LISTENER)

        val result = auditor.evaluateComposition(highest, monitor)

        assertFalse(result.moduleAEligible, "Module A must be refused when foreign callback exists at HIGHEST")
        assertTrue(result.moduleBEligible, "Module B remains independently eligible")
        assertTrue(result.discrepancies.any { it.contains("Unexpected SSB callback at HIGHEST") && it.contains("CustomAddonHighestListener") })
    }

    @Test
    fun `unknown callback at MONITOR refuses Module B while Module A remains eligible`() {
        val highest = listOf(SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER)
        val monitor = listOf(
            SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER,
            SuperiorDispatcherAuditor.EXPECTED_CHANGES_LISTENER,
            "com.example.addon.CustomAddonMonitorListener"
        )

        val result = auditor.evaluateComposition(highest, monitor)

        assertTrue(result.moduleAEligible, "Module A remains eligible")
        assertFalse(result.moduleBEligible, "Module B must be refused when foreign callback exists at MONITOR")
        assertTrue(result.discrepancies.any { it.contains("Unexpected SSB callback at MONITOR") && it.contains("CustomAddonMonitorListener") })
    }

    @Test
    fun `empty HIGHEST callbacks refuses Module A`() {
        val highest = emptyList<String>()
        val monitor = listOf(SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER, SuperiorDispatcherAuditor.EXPECTED_CHANGES_LISTENER)

        val result = auditor.evaluateComposition(highest, monitor)

        assertFalse(result.moduleAEligible, "Empty HIGHEST must refuse Module A")
        assertTrue(result.moduleBEligible)
        assertTrue(result.discrepancies.any { it.contains("HIGHEST has no registered callbacks") })
    }

    @Test
    fun `missing BlockChangesListener at MONITOR refuses Module B`() {
        val highest = listOf(SuperiorDispatcherAuditor.EXPECTED_STACKED_LISTENER)
        val monitor = listOf(SuperiorDispatcherAuditor.EXPECTED_SIGNS_LISTENER)

        val result = auditor.evaluateComposition(highest, monitor)

        assertTrue(result.moduleAEligible)
        assertFalse(result.moduleBEligible, "Missing BlockChangesListener must refuse Module B")
        assertTrue(result.discrepancies.any { it.contains("MONITOR missing expected") })
    }
}
