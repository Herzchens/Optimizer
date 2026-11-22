package itzherzchen.optimizer.observe

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransactionRiskScannerTest {

    @Test
    fun `scan returns clean report when CuboidWandUseEvent has no external listeners`() {
        val scanner = TransactionRiskScanner()
        val report = scanner.scan()

        assertNotNull(report)
        assertFalse(report.hasHighRiskExternalListeners)
        assertTrue(report.externalListeners.isEmpty())
        assertTrue(report.riskSummary.contains("No foreign listeners detected"))
    }
}
