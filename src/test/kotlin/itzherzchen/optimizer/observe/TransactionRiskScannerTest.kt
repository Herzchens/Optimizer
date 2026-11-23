package itzherzchen.optimizer.observe

import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TransactionRiskScannerTest {

    @Test
    fun `scan returns clean report when CuboidWandUseEvent has no external listeners`() {
        val scanner = TransactionRiskScanner()
        val report = scanner.scan()

        assertNotNull(report)
        assertEquals(TransactionRiskState.CLEAN, report.state)
        assertFalse(report.hasHighRiskExternalListeners)
        assertTrue(report.isEligible)
        assertTrue(report.externalListeners.isEmpty())
        assertTrue(report.riskSummary.contains("No foreign listeners detected"))
    }

    @Test
    fun `scan returns RISK_FOUND when foreign listeners are detected`() {
        val handlerList = HandlerList()
        val mockPlugin = mock(Plugin::class.java)
        `when`(mockPlugin.name).thenReturn("ExternalProtectionPlugin")

        val registeredListener = RegisteredListener(
            object : Listener {},
            { _, _ -> },
            EventPriority.NORMAL,
            mockPlugin,
            false
        )
        handlerList.register(registeredListener)

        val scanner = TransactionRiskScanner(handlerListProvider = { handlerList })
        val report = scanner.scan()

        assertNotNull(report)
        assertEquals(TransactionRiskState.RISK_FOUND, report.state)
        assertTrue(report.hasHighRiskExternalListeners)
        assertFalse(report.isEligible)
        assertEquals(1, report.externalListeners.size)
        assertTrue(report.externalListeners[0].contains("ExternalProtectionPlugin"))
    }

    @Test
    fun `scan returns SCAN_FAILED when handler list is null`() {
        val scanner = TransactionRiskScanner(handlerListProvider = { null })
        val report = scanner.scan()

        assertNotNull(report)
        assertEquals(TransactionRiskState.SCAN_FAILED, report.state)
        assertTrue(report.hasHighRiskExternalListeners)
        assertFalse(report.isEligible)
        assertTrue(report.riskSummary.contains("returned null"))
    }

    @Test
    fun `scan returns SCAN_FAILED when classloader fails to find event class`() {
        val emptyClassLoader = object : ClassLoader(null) {
            override fun findClass(name: String): Class<*> {
                throw ClassNotFoundException("Simulated missing class: $name")
            }
        }
        val scanner = TransactionRiskScanner(classLoader = emptyClassLoader)
        val report = scanner.scan()

        assertNotNull(report)
        assertEquals(TransactionRiskState.SCAN_FAILED, report.state)
        assertTrue(report.hasHighRiskExternalListeners)
        assertFalse(report.isEligible)
        assertTrue(report.riskSummary.contains("not found in runtime"))
    }

    @Test
    fun `scan returns SCAN_FAILED when handlerListProvider throws unexpected exception`() {
        val scanner = TransactionRiskScanner(handlerListProvider = {
            throw RuntimeException("Simulated internal exception during scan")
        })
        val report = scanner.scan()

        assertNotNull(report)
        assertEquals(TransactionRiskState.SCAN_FAILED, report.state)
        assertTrue(report.hasHighRiskExternalListeners)
        assertFalse(report.isEligible)
        assertTrue(report.riskSummary.contains("Simulated internal exception"))
    }
}
