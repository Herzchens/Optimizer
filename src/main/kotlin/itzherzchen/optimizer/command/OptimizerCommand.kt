package itzherzchen.optimizer.command

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.bootstrap.InvariantStatus
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.SampledTiming
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * Command executor and tab completer for /optimizer (alias /hpg).
 * All administrative actions require optimizer.admin permission.
 * Uses Paper's native Kyori Adventure components for zero legacy deprecation.
 */
class OptimizerCommand(
    private val modeController: ModeController,
    private val circuitBreaker: CircuitBreaker,
    private val counters: Counters,
    private val timing: SampledTiming,
    private val validator: CompatibilityValidator,
    private val wildToolsBridge: itzherzchen.optimizer.wildtools.WildToolsBridge? = null,
    private val rolloutValidator: itzherzchen.optimizer.staging.RolloutValidator = itzherzchen.optimizer.staging.RolloutValidator()
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("optimizer.admin")) {
            sender.sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED))
            return true
        }

        val sub = if (args.isEmpty()) "status" else args[0].lowercase()

        when (sub) {
            "status" -> handleStatus(sender)
            "validate" -> handleValidate(sender)
            "stats" -> handleStats(sender)
            "disarm" -> handleDisarm(sender)
            "staging", "rollout" -> handleStaging(sender, args)
            else -> {
                sender.sendMessage(Component.text("--- Optimizer Commands ---", NamedTextColor.GOLD))
                sender.sendMessage(Component.text("/$label status ", NamedTextColor.YELLOW)
                    .append(Component.text("- View operating mode and circuit health", NamedTextColor.GRAY)))
                sender.sendMessage(Component.text("/$label validate ", NamedTextColor.YELLOW)
                    .append(Component.text("- Run read-only runtime invariant validation", NamedTextColor.GRAY)))
                sender.sendMessage(Component.text("/$label stats ", NamedTextColor.YELLOW)
                    .append(Component.text("- View performance counters and sampled latency", NamedTextColor.GRAY)))
                sender.sendMessage(Component.text("/$label staging ", NamedTextColor.YELLOW)
                    .append(Component.text("- Run staging equivalence matrix and rollout validation", NamedTextColor.GRAY)))
                sender.sendMessage(Component.text("/$label disarm ", NamedTextColor.YELLOW)
                    .append(Component.text("- Emergency fail-closed shutdown (requires restart)", NamedTextColor.GRAY)))
            }
        }

        return true
    }

    private fun handleStatus(sender: CommandSender) {
        sender.sendMessage(Component.text("=== Optimizer Status ===", NamedTextColor.GOLD))
        val mode = modeController.mode
        val modeColor = when (mode) {
            OperatingMode.ACTIVE -> NamedTextColor.GREEN
            OperatingMode.VALIDATED -> NamedTextColor.AQUA
            OperatingMode.OBSERVE -> NamedTextColor.YELLOW
            OperatingMode.DISARMED_RESTART_REQUIRED -> NamedTextColor.RED
        }
        sender.sendMessage(Component.text("Operating Mode: ", NamedTextColor.GRAY)
            .append(Component.text(mode.name, modeColor)))

        if (modeController.reason != null) {
            sender.sendMessage(Component.text("Disarm Reason: ", NamedTextColor.GRAY)
                .append(Component.text(modeController.reason!!, NamedTextColor.RED)))
        }

        sender.sendMessage(Component.text("Circuit Breakers:", NamedTextColor.GRAY))
        for (module in ModuleType.values()) {
            val tripped = circuitBreaker.isTripped(module)
            val statusColor = if (tripped) NamedTextColor.RED else NamedTextColor.GREEN
            val statusText = if (tripped) "TRIPPED" else "OK"
            val record = circuitBreaker.getTripRecord(module)
            val details = if (record != null) " (${record.reason})" else ""
            sender.sendMessage(Component.text("  - $module: ", NamedTextColor.GRAY)
                .append(Component.text("$statusText$details", statusColor)))
        }

        val report = try { validator.runValidation() } catch (_: Throwable) { null }
        if (report != null) {
            sender.sendMessage(Component.text("Module Eligibility:", NamedTextColor.GRAY))
            val aColor = if (report.moduleAEligible) NamedTextColor.GREEN else NamedTextColor.RED
            val aText = if (report.moduleAEligible) "ELIGIBLE" else "REFUSED"
            sender.sendMessage(Component.text("  - Module A (SSBStackedFastReject): ", NamedTextColor.GRAY)
                .append(Component.text(aText, aColor)))
            val bColor = if (report.moduleBEligible) NamedTextColor.GREEN else NamedTextColor.RED
            val bText = if (report.moduleBEligible) "ELIGIBLE" else "REFUSED (${report.moduleBReason})"
            sender.sendMessage(Component.text("  - Module B (BlockAccountingFastPath): ", NamedTextColor.GRAY)
                .append(Component.text(bText, bColor)))
        }
    }

    private fun handleValidate(sender: CommandSender) {
        sender.sendMessage(Component.text("=== Running Optimizer Validation (Read-Only) ===", NamedTextColor.GOLD))
        val report = validator.runValidation()

        sender.sendMessage(Component.text("Fingerprint Checks:", NamedTextColor.YELLOW))
        for (check in report.fingerprintResult.checks) {
            val color = when (check.status) {
                InvariantStatus.PASS -> NamedTextColor.GREEN
                InvariantStatus.FAIL -> NamedTextColor.RED
                InvariantStatus.SKIPPED -> NamedTextColor.GRAY
            }
            sender.sendMessage(Component.text("  [${check.status}] ", color)
                .append(Component.text("${check.name}: ", NamedTextColor.WHITE))
                .append(Component.text(check.details, NamedTextColor.GRAY)))
        }

        sender.sendMessage(Component.text("Listener Census Topology:", NamedTextColor.YELLOW))
        val census = report.listenerCensusResult
        if (census.isTopologyValid) {
            sender.sendMessage(Component.text("  [PASS] Exact topology verified (${census.entries.size} listeners in chain)", NamedTextColor.GREEN))
        } else {
            sender.sendMessage(Component.text("  [FAIL] Topology discrepancies found:", NamedTextColor.RED))
            for (disc in census.discrepancies) {
                sender.sendMessage(Component.text("    - $disc", NamedTextColor.RED))
            }
        }

        sender.sendMessage(Component.text("Transaction Risk Analysis:", NamedTextColor.YELLOW))
        val risk = report.riskScanResult
        if (risk.hasHighRiskExternalListeners) {
            sender.sendMessage(Component.text("  [WARN] External listeners on CuboidWandUseEvent: ${risk.externalListeners.joinToString(", ")}", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("  [OK] No foreign listeners on CuboidWandUseEvent", NamedTextColor.GREEN))
        }

        sender.sendMessage(Component.text("Module Eligibility:", NamedTextColor.YELLOW))
        val modAColor = if (report.moduleAEligible) NamedTextColor.GREEN else NamedTextColor.RED
        val modAText = if (report.moduleAEligible) "ELIGIBLE (${report.moduleAReason})" else "REFUSED (${report.moduleAReason})"
        sender.sendMessage(Component.text("  - Module A (SSBStackedFastReject): ", NamedTextColor.GRAY)
            .append(Component.text(modAText, modAColor)))

        val modBColor = if (report.moduleBEligible) NamedTextColor.GREEN else NamedTextColor.RED
        val modBText = if (report.moduleBEligible) "ELIGIBLE (${report.moduleBReason})" else "REFUSED (${report.moduleBReason})"
        sender.sendMessage(Component.text("  - Module B (BlockAccountingFastPath): ", NamedTextColor.GRAY)
            .append(Component.text(modBText, modBColor)))

        sender.sendMessage(Component.text("Recommended Mode: ", NamedTextColor.YELLOW)
            .append(Component.text(report.recommendedMode.name, NamedTextColor.AQUA)))
    }

    private fun handleStats(sender: CommandSender) {
        sender.sendMessage(Component.text("=== Optimizer Performance & Metrics ===", NamedTextColor.GOLD))
        val snap = counters.snapshot()
        sender.sendMessage(Component.text("Cuboid Actions: ", NamedTextColor.GRAY).append(Component.text(snap.cuboidActions, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Synthetic Breaks: ", NamedTextColor.GRAY).append(Component.text(snap.syntheticBreaks, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Stacked Fast Rejects: ", NamedTextColor.GRAY)
            .append(Component.text(snap.stackedFastRejects, NamedTextColor.GREEN))
            .append(Component.text(" / Passthroughs: ", NamedTextColor.GRAY))
            .append(Component.text(snap.stackedPassthroughs, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Monitor Fast Paths: ", NamedTextColor.GRAY)
            .append(Component.text(snap.monitorFastPaths, NamedTextColor.GREEN))
            .append(Component.text(" / Anchors: ", NamedTextColor.GRAY))
            .append(Component.text(snap.monitorAnchors, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Sign / Custom / Spawner Passthroughs: ", NamedTextColor.GRAY)
            .append(Component.text("${snap.signPassthroughs} / ${snap.customPassthroughs} / ${snap.spawnerPassthroughs}", NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Dirty Tasks Avoided (Est): ", NamedTextColor.GRAY).append(Component.text(snap.dirtyTasksAvoided, NamedTextColor.GREEN)))
        sender.sendMessage(Component.text("Validation Failures: ", NamedTextColor.GRAY).append(Component.text(snap.validationFailures, NamedTextColor.RED)))
        sender.sendMessage(Component.text("Circuit Trips: ", NamedTextColor.GRAY).append(Component.text(snap.circuitTrips, NamedTextColor.RED)))
        sender.sendMessage(Component.text("External Cuboid Cancellations: ", NamedTextColor.GRAY).append(Component.text(snap.externalCuboidCancellations, NamedTextColor.YELLOW)))

        val stats = timing.getStats()
        sender.sendMessage(Component.text("Sampled Latency (1/${timing.sampleRate} actions):", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("  Sampled Count: ", NamedTextColor.GRAY).append(Component.text(stats.count, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Min: ", NamedTextColor.GRAY).append(Component.text("%.3f ms".format(stats.minMillis), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Max: ", NamedTextColor.GRAY).append(Component.text("%.3f ms".format(stats.maxMillis), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Avg: ", NamedTextColor.GRAY).append(Component.text("%.3f ms".format(stats.avgMillis), NamedTextColor.WHITE)))
    }

    private fun handleDisarm(sender: CommandSender) {
        val operator = sender.name
        modeController.disarm("Manual operator request via command", operator)
        circuitBreaker.trip(ModuleType.GLOBAL, "Operator requested disarm via command")
        wildToolsBridge?.disarm("Operator requested disarm via command")
        sender.sendMessage(Component.text("[Optimizer] EMERGENCY DISARM EXECUTED. Mode permanently set to DISARMED_RESTART_REQUIRED.", NamedTextColor.RED))
        sender.sendMessage(Component.text("[Optimizer] All fast paths are disabled. A clean server restart is required to re-evaluate.", NamedTextColor.RED))
    }

    private fun handleStaging(sender: CommandSender, args: Array<out String>) {
        if (args.size > 1 && args[1].equals("matrix", ignoreCase = true)) {
            sender.sendMessage(Component.text("=== Optimizer Equivalence Matrix Groups ===", NamedTextColor.GOLD))
            for (group in itzherzchen.optimizer.staging.MatrixGroup.values()) {
                sender.sendMessage(Component.text("  - ${group.title}: ", NamedTextColor.YELLOW)
                    .append(Component.text(group.description, NamedTextColor.GRAY)))
            }
            return
        }

        sender.sendMessage(Component.text("=== Running Staging Equivalence Matrix ===", NamedTextColor.GOLD))
        val report = rolloutValidator.runValidationMatrix()

        for ((group, groupRes) in report.groupResults) {
            val color = when (groupRes.status) {
                itzherzchen.optimizer.staging.TestStatus.PASS -> NamedTextColor.GREEN
                itzherzchen.optimizer.staging.TestStatus.WARN -> NamedTextColor.YELLOW
                itzherzchen.optimizer.staging.TestStatus.FAIL -> NamedTextColor.RED
            }
            sender.sendMessage(Component.text("  [${groupRes.status}] ", color)
                .append(Component.text("${group.title} ", NamedTextColor.WHITE))
                .append(Component.text("(${groupRes.passedCount}/${groupRes.totalCount} passed)", NamedTextColor.GRAY)))

            for (caseRes in groupRes.caseResults) {
                if (caseRes.status != itzherzchen.optimizer.staging.TestStatus.PASS) {
                    val cColor = if (caseRes.status == itzherzchen.optimizer.staging.TestStatus.WARN) NamedTextColor.YELLOW else NamedTextColor.RED
                    sender.sendMessage(Component.text("    - [${caseRes.caseId}] ${caseRes.name}: ${caseRes.details}", cColor))
                }
            }
        }

        val decisionColor = when (report.decision) {
            itzherzchen.optimizer.staging.RolloutDecision.GO_V03_ACTIVE_B -> NamedTextColor.GREEN
            itzherzchen.optimizer.staging.RolloutDecision.GO_V02_ACTIVE_A -> NamedTextColor.AQUA
            itzherzchen.optimizer.staging.RolloutDecision.GO_V01_OBSERVE -> NamedTextColor.YELLOW
            itzherzchen.optimizer.staging.RolloutDecision.NO_GO -> NamedTextColor.RED
        }

        sender.sendMessage(Component.text("Rollout Decision: ", NamedTextColor.GOLD)
            .append(Component.text(report.decision.name, decisionColor)))
        sender.sendMessage(Component.text("Summary: ", NamedTextColor.GRAY)
            .append(Component.text(report.summary, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("Results: ${report.passedTests}/${report.totalTests} passed (${report.warningTests} warn, ${report.failedTests} fail) in ${report.totalTimeMs}ms", NamedTextColor.GRAY))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("optimizer.admin")) return emptyList()
        if (args.size == 1) {
            val query = args[0].lowercase()
            return listOf("status", "validate", "stats", "staging", "disarm").filter { it.startsWith(query) }
        }
        if (args.size == 2 && (args[0].equals("staging", ignoreCase = true) || args[0].equals("rollout", ignoreCase = true))) {
            val query = args[1].lowercase()
            return listOf("run", "matrix").filter { it.startsWith(query) }
        }
        return emptyList()
    }
}
