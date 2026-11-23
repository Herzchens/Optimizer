package itzherzchen.optimizer.command

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.bootstrap.FingerprintResult
import itzherzchen.optimizer.bootstrap.FullValidationReport
import itzherzchen.optimizer.bootstrap.ListenerCensusResult
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.SampledTiming
import itzherzchen.optimizer.observe.TransactionRiskReport
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class OptimizerCommandTest {

    private val plainSerializer = PlainTextComponentSerializer.plainText()

    private fun captureMessages(sender: CommandSender): List<String> {
        val captor = ArgumentCaptor.forClass(Component::class.java)
        verify(sender, atLeastOnce()).sendMessage(captor.capture())
        return captor.allValues.map { plainSerializer.serialize(it) }
    }

    @Test
    fun `command execution without permission is rejected`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(false)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("status"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("You do not have permission") })
    }

    @Test
    fun `status subcommand reports mode and circuit states`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("status"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Optimizer Status") })
        assertTrue(messages.any { it.contains("Operating Mode:") })
        assertTrue(messages.any { it.contains("Circuit Breakers:") })
    }

    @Test
    fun `disarm subcommand triggers fail-closed terminal state`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        `when`(sender.name).thenReturn("TestAdmin")
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("disarm"))
        assertTrue(result)

        assertEquals(OperatingMode.DISARMED_RESTART_REQUIRED, modeController.mode)
        assertTrue(breaker.isTripped(ModuleType.GLOBAL))

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("EMERGENCY DISARM EXECUTED") })
    }

    @Test
    fun `validate subcommand runs read-only audit without arming`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val mockReport = FullValidationReport(
            fingerprintResult = FingerprintResult(true, emptyList(), emptyList()),
            listenerCensusResult = ListenerCensusResult(true, emptyList(), 1, 2, 2, 0, emptyList()),
            riskScanResult = TransactionRiskReport(false, emptyList(), "No risk"),
            isOverallCompatible = true,
            recommendedMode = OperatingMode.VALIDATED
        )
        `when`(validator.runValidation()).thenReturn(mockReport)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        command.onCommand(sender, bukkitCmd, "hpg", arrayOf("validate"))

        // Mode must remain OBSERVE, validate is read-only
        assertEquals(OperatingMode.OBSERVE, modeController.mode)
        verify(validator).runValidation()

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Running Optimizer Validation (Read-Only)") })
        assertTrue(messages.any { it.contains("Recommended Mode:") })
    }

    @Test
    fun `stats subcommand outputs counter snapshot and sampled latency`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        counters.cuboidActions.increment()
        counters.syntheticBreaks.add(10)
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("stats"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Optimizer Performance & Metrics") })
        assertTrue(messages.any { it.contains("Cuboid Actions: 1") })
        assertTrue(messages.any { it.contains("Synthetic Breaks: 10") })
    }

    @Test
    fun `help subcommand output on unknown argument`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("unknown_arg"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Optimizer Commands") })
    }

    @Test
    fun `tab completion filters available subcommands`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val completions = command.onTabComplete(sender, bukkitCmd, "hpg", arrayOf("s"))
        assertEquals(listOf("status", "stats", "staging"), completions)

        val emptyQueryCompletions = command.onTabComplete(sender, bukkitCmd, "hpg", arrayOf(""))
        assertEquals(listOf("status", "validate", "stats", "staging", "disarm"), emptyQueryCompletions)

        val stagingSubCompletions = command.onTabComplete(sender, bukkitCmd, "hpg", arrayOf("staging", ""))
        assertEquals(listOf("run", "matrix"), stagingSubCompletions)
    }

    @Test
    fun `staging subcommand executes rollout validation matrix`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("staging", "run"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Staging") })
        assertTrue(messages.any { it.contains("MODEL VALIDATION:") })
        assertTrue(messages.any { it.contains("RUNTIME READINESS:") })
        assertTrue(messages.any { it.contains("REAL STAGING EQUIVALENCE:") })
        assertTrue(messages.any { it.contains("ROLLOUT APPROVAL:") })
        assertTrue(messages.any { it.contains("Rollout Decision") })
    }

    @Test
    fun `staging matrix subcommand displays matrix groups`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(true)
        val bukkitCmd = mock(Command::class.java)

        val result = command.onCommand(sender, bukkitCmd, "hpg", arrayOf("staging", "matrix"))
        assertTrue(result)

        val messages = captureMessages(sender)
        assertTrue(messages.any { it.contains("Optimizer Equivalence Matrix Groups") })
        assertTrue(messages.any { it.contains("Cuboid Workload") })
    }

    @Test
    fun `tab completion returns empty without permission`() {
        val modeController = ModeController()
        val breaker = CircuitBreaker(modeController)
        val counters = Counters()
        val timing = SampledTiming()
        val validator = mock(CompatibilityValidator::class.java)

        val command = OptimizerCommand(modeController, breaker, counters, timing, validator)

        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("optimizer.admin")).thenReturn(false)
        val bukkitCmd = mock(Command::class.java)

        val completions = command.onTabComplete(sender, bukkitCmd, "hpg", arrayOf(""))
        assertTrue(completions.isEmpty())
    }
}
