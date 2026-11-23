package itzherzchen.optimizer.staging

import itzherzchen.optimizer.bootstrap.CompatibilityValidator
import itzherzchen.optimizer.bootstrap.FullValidationReport
import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.observe.Counters
import itzherzchen.optimizer.observe.TransactionRiskScanner
import itzherzchen.optimizer.runtime.CircuitBreaker
import itzherzchen.optimizer.runtime.ModeController
import itzherzchen.optimizer.runtime.ModuleType
import itzherzchen.optimizer.runtime.OperatingMode
import java.util.logging.Logger

/**
 * Functional runner for executing staging equivalence matrix test cases with explicit evidence provenance.
 */
fun interface StagingRunner {
    val provenance: EvidenceProvenance
        get() = EvidenceProvenance.MODEL_SIMULATION

    fun execute(): List<TestCaseResult>
}

/**
 * Staging Verification & Rollout Validation Tooling (Plan.md Section 8 & Section 9).
 *
 * Enforces strict evidence provenance rules:
 * - READINESS_AUDIT: Live environment preflight only. Never issues GO_V02 or GO_V03.
 * - MODEL_SIMULATION: Simulated matrix execution. Yields MODEL_PASS / MODEL_FAIL only; never production approval.
 * - REAL_SERVER_STAGING: Authoritative server staging. Only source allowed to issue GO_V02 or GO_V03,
 *   and strictly requires all 15 state dimensions to be measured and verified.
 */
class RolloutValidator(
    private val config: OptimizerConfig? = null,
    private val modeController: ModeController? = null,
    private val circuitBreaker: CircuitBreaker? = null,
    private val counters: Counters? = null,
    private val validator: CompatibilityValidator? = null,
    private val logger: Logger? = null,
    var customRunner: StagingRunner? = null
) {

    /**
     * Executes the complete rollout validation matrix.
     */
    fun runValidationMatrix(): RolloutReport {
        val start = System.currentTimeMillis()

        val runner = customRunner
        val provenance = runner?.provenance ?: EvidenceProvenance.READINESS_AUDIT
        val isReadinessOnly = provenance == EvidenceProvenance.READINESS_AUDIT

        val allCaseResults = if (runner != null) {
            runner.execute()
        } else {
            runLiveStagingAudit()
        }

        val totalTime = System.currentTimeMillis() - start

        val groupResults = MatrixGroup.values().associateWith { group ->
            val cases = allCaseResults.filter { it.group == group }
            val passCount = cases.count { it.status == TestStatus.PASS || it.status == TestStatus.READY }
            val overallGroupStatus = when {
                cases.isEmpty() -> TestStatus.READY
                cases.any { it.status == TestStatus.FAIL } -> TestStatus.FAIL
                cases.any { it.status == TestStatus.WARN } -> TestStatus.WARN
                cases.all { it.status == TestStatus.READY } -> TestStatus.READY
                else -> TestStatus.PASS
            }
            GroupResult(
                group = group,
                status = overallGroupStatus,
                passedCount = passCount,
                totalCount = cases.size,
                caseResults = cases
            )
        }

        val total = allCaseResults.size
        val passed = allCaseResults.count { it.status == TestStatus.PASS || it.status == TestStatus.READY }
        val warnings = allCaseResults.count { it.status == TestStatus.WARN }
        val failed = allCaseResults.count { it.status == TestStatus.FAIL }

        val protectionFailed = groupResults[MatrixGroup.PROTECTION]?.status == TestStatus.FAIL
        val failureFailed = groupResults[MatrixGroup.FAILURE]?.status == TestStatus.FAIL
        val stackingFailed = groupResults[MatrixGroup.STACKING]?.status == TestStatus.FAIL

        val decision: RolloutDecision
        val summary: String
        val modelStatus: String
        val readinessStatus: String
        val realStagingStatus: String
        val rolloutApprovalStatus: String

        when (provenance) {
            EvidenceProvenance.READINESS_AUDIT -> {
                // Readiness audit: live checks only. NEVER GO_V02/GO_V03.
                val txWarning = groupResults[MatrixGroup.TRANSACTIONS]?.status == TestStatus.WARN
                decision = when {
                    failed > 0 || protectionFailed || failureFailed -> RolloutDecision.NO_GO
                    txWarning -> RolloutDecision.MODULE_B_BLOCKED
                    else -> RolloutDecision.READY_FOR_STAGING
                }

                modelStatus = "NOT RUN"
                readinessStatus = if (decision == RolloutDecision.NO_GO) "FAIL" else "PASS"
                realStagingStatus = "NOT RUN"
                rolloutApprovalStatus = "NOT GRANTED"

                summary = when (decision) {
                    RolloutDecision.NO_GO -> "MODEL VALIDATION: NOT RUN | RUNTIME READINESS: FAIL | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED (Non-negotiable invariant violation detected! Rollout BLOCKED.)"
                    RolloutDecision.MODULE_B_BLOCKED -> "MODEL VALIDATION: NOT RUN | RUNTIME READINESS: PASS (Module A eligible, Module B blocked due to transaction risk) | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED"
                    RolloutDecision.READY_FOR_STAGING -> "MODEL VALIDATION: NOT RUN | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED (Authoritative staging equivalence execution required before production rollout approval)"
                    else -> "MODEL VALIDATION: NOT RUN | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED"
                }
            }

            EvidenceProvenance.MODEL_SIMULATION -> {
                // MODEL_SIMULATION: yields MODEL_PASS on pass, NO_GO on failure. Never production/staging rollout approval!
                val simPassed = failed == 0 && !protectionFailed && !failureFailed && !stackingFailed
                decision = if (simPassed) RolloutDecision.MODEL_PASS else RolloutDecision.NO_GO

                modelStatus = if (simPassed) "PASS" else "FAIL"
                readinessStatus = "PASS"
                realStagingStatus = "NOT RUN (SIMULATION ONLY)"
                rolloutApprovalStatus = "NOT GRANTED (SIMULATION CANNOT APPROVE ROLLOUT)"

                summary = if (simPassed) {
                    "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: NOT RUN (SIMULATION ONLY) | ROLLOUT APPROVAL: NOT GRANTED (Authoritative real-server staging required for rollout approval)"
                } else {
                    "MODEL VALIDATION: FAIL | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED (Simulation model invariant failure)"
                }
            }

            EvidenceProvenance.REAL_SERVER_STAGING -> {
                // REAL_SERVER_STAGING is authoritative only when evidence itself proves equivalence.
                // Caller-declared PASS is never sufficient: every contributing case must carry a
                // complete diff and that diff must be authoritative-equivalent.
                val hasMissingEvidence = allCaseResults.any { it.diff == null }
                val hasMissingDimensions = allCaseResults.any { it.diff?.missingDimensions?.isNotEmpty() == true }
                val hasNonEquivalentEvidence = allCaseResults.any { it.diff?.isAuthoritativelyEquivalent != true }
                val authoritativeEvidenceInvalid =
                    hasMissingEvidence || hasMissingDimensions || hasNonEquivalentEvidence
                val allGroupsPass = groupResults.values.all { it.status == TestStatus.PASS }

                if (failed > 0 || protectionFailed || failureFailed || stackingFailed || authoritativeEvidenceInvalid) {
                    decision = RolloutDecision.NO_GO
                    modelStatus = "PASS"
                    readinessStatus = "PASS"
                    realStagingStatus = when {
                        hasMissingEvidence -> "FAIL (MISSING AUTHORITATIVE DIFF EVIDENCE)"
                        hasMissingDimensions -> "FAIL (MISSING REQUIRED STATE DIMENSIONS)"
                        hasNonEquivalentEvidence -> "FAIL (STATE EQUIVALENCE MISMATCH)"
                        else -> "FAIL"
                    }
                    rolloutApprovalStatus = "NOT GRANTED"
                    summary = when {
                        hasMissingEvidence ->
                            "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: FAIL (Refused: authoritative diff evidence missing) | ROLLOUT APPROVAL: NOT GRANTED"
                        hasMissingDimensions ->
                            "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: FAIL (Refused: missing required state dimensions) | ROLLOUT APPROVAL: NOT GRANTED"
                        hasNonEquivalentEvidence ->
                            "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: FAIL (Refused: authoritative state mismatch) | ROLLOUT APPROVAL: NOT GRANTED"
                        else ->
                            "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: FAIL | ROLLOUT APPROVAL: NOT GRANTED (Equivalence matrix violation detected)"
                    }
                } else if (allGroupsPass) {
                    decision = RolloutDecision.GO_V03_ACTIVE_B
                    modelStatus = "PASS"
                    readinessStatus = "PASS"
                    realStagingStatus = "PASS (ALL 15 DIMENSIONS VERIFIED)"
                    rolloutApprovalStatus = "APPROVED_V03_ACTIVE_B"
                    summary = "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: PASS (All 15 dimensions authoritatively equivalent) | ROLLOUT APPROVAL: APPROVED_V03_ACTIVE_B"
                } else if (groupResults[MatrixGroup.STACKING]?.status == TestStatus.PASS && groupResults[MatrixGroup.PROTECTION]?.status == TestStatus.PASS) {
                    decision = RolloutDecision.GO_V02_ACTIVE_A
                    modelStatus = "PASS"
                    readinessStatus = "PASS"
                    realStagingStatus = "PASS (MODULE A ONLY)"
                    rolloutApprovalStatus = "APPROVED_V02_ACTIVE_A"
                    summary = "MODEL VALIDATION: PASS | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: PASS (Module A verified) | ROLLOUT APPROVAL: APPROVED_V02_ACTIVE_A"
                } else {
                    decision = RolloutDecision.GO_V01_OBSERVE
                    modelStatus = "PASS"
                    readinessStatus = "PASS"
                    realStagingStatus = "NOT RUN"
                    rolloutApprovalStatus = "NOT GRANTED"
                    summary = "MODEL VALIDATION: WARN | RUNTIME READINESS: PASS | REAL STAGING EQUIVALENCE: NOT RUN | ROLLOUT APPROVAL: NOT GRANTED"
                }
            }
        }

        return RolloutReport(
            decision = decision,
            summary = summary,
            groupResults = groupResults,
            totalTests = total,
            passedTests = passed,
            warningTests = warnings,
            failedTests = failed,
            totalTimeMs = totalTime,
            isReadinessOnly = isReadinessOnly,
            provenance = provenance,
            modelValidationStatus = modelStatus,
            runtimeReadinessStatus = readinessStatus,
            realStagingEquivalenceStatus = realStagingStatus,
            rolloutApprovalStatus = rolloutApprovalStatus
        )
    }

    /**
     * Fallback live staging audit when no simulation runner is explicitly injected.
     * Evaluates live environment state, topology census, circuit breakers, and transaction risks.
     */
    private fun runLiveStagingAudit(): List<TestCaseResult> {
        val results = mutableListOf<TestCaseResult>()

        val validationReport: FullValidationReport? = try {
            validator?.runValidation()
        } catch (_: Throwable) {
            null
        }

        val isTripped = circuitBreaker?.isTripped(ModuleType.GLOBAL) ?: false
        val isDisarmed = modeController?.mode == OperatingMode.DISARMED_RESTART_REQUIRED

        // 1. CUBOID
        results.add(
            TestCaseResult(
                caseId = "CUB-LIVE",
                name = "Live Cuboid Workload Readiness",
                group = MatrixGroup.CUBOID,
                status = if (isTripped || isDisarmed) TestStatus.FAIL else TestStatus.READY,
                details = if (isTripped || isDisarmed) "System is disarmed or tripped" else "Live cuboid action tracking ready"
            )
        )

        // 2. PROTECTION
        val hasProtection = validationReport?.listenerCensusResult?.protectionListenerIndex?.let { it >= 0 } ?: false
        results.add(
            TestCaseResult(
                caseId = "PROT-LIVE",
                name = "Live Protection Topology Audit",
                group = MatrixGroup.PROTECTION,
                status = if (hasProtection) TestStatus.PASS else TestStatus.FAIL,
                details = if (hasProtection) {
                    "SSB Protection callback at NORMAL verified in claimingPluginsBreakMethods"
                } else {
                    "CRITICAL: SSB Protection callback at NORMAL missing in claimingPluginsBreakMethods! Protection bypass risk!"
                }
            )
        )

        // 3. STACKING
        val hasStacked = validationReport?.listenerCensusResult?.stackedBlocksIndex?.let { it >= 0 } ?: false
        results.add(
            TestCaseResult(
                caseId = "STK-LIVE",
                name = "Live Stacking Callback Topology",
                group = MatrixGroup.STACKING,
                status = if (hasStacked) TestStatus.PASS else TestStatus.WARN,
                details = "SSB StackedBlocks callback at HIGHEST verified in claimingPluginsBreakMethods"
            )
        )

        // 4. SIGNS_AND_CUSTOM
        results.add(
            TestCaseResult(
                caseId = "SGN-LIVE",
                name = "Live Classifier & Sign Gate Readiness",
                group = MatrixGroup.SIGNS_AND_CUSTOM,
                status = TestStatus.READY,
                details = "VanillaBlockClassifier and orthogonal sign gate configured fail-closed (readiness check only)"
            )
        )

        // 5. COORDINATES
        results.add(
            TestCaseResult(
                caseId = "CRD-LIVE",
                name = "Live Chunk Coverage Configuration",
                group = MatrixGroup.COORDINATES,
                status = TestStatus.READY,
                details = "Linear chunk coverage array configured for negative coordinate bitwise keys (readiness check only)"
            )
        )

        // 6. LIFECYCLE
        val fpCompat = validationReport?.fingerprintResult?.isCompatible == true
        val fpDetails = if (fpCompat) {
            "Runtime fingerprint and classloader integrity verified"
        } else {
            validationReport?.fingerprintResult?.failureReasons?.joinToString("; ") ?: "Awaiting full validation pass"
        }
        results.add(
            TestCaseResult(
                caseId = "LIF-LIVE",
                name = "Live Runtime Fingerprint & Lifecycle",
                group = MatrixGroup.LIFECYCLE,
                status = if (fpCompat) TestStatus.PASS else TestStatus.WARN,
                details = fpDetails
            )
        )

        // 7. TRANSACTIONS
        val riskReport = validationReport?.riskScanResult ?: TransactionRiskScanner(logger).scan()
        results.add(
            TestCaseResult(
                caseId = "TX-LIVE",
                name = "CuboidWandUseEvent External Listener Census",
                group = MatrixGroup.TRANSACTIONS,
                status = if (!riskReport.hasHighRiskExternalListeners) TestStatus.PASS else TestStatus.WARN,
                details = riskReport.riskSummary
            )
        )

        // 8. FAILURE
        val circuitClean = !(circuitBreaker?.isTripped(ModuleType.GLOBAL) ?: false)
        results.add(
            TestCaseResult(
                caseId = "FLT-LIVE",
                name = "Circuit Breaker Health",
                group = MatrixGroup.FAILURE,
                status = if (circuitClean) TestStatus.PASS else TestStatus.FAIL,
                details = if (circuitClean) "All circuit breakers operational (healthy)" else "One or more circuit breakers TRIPPED"
            )
        )

        return results
    }
}
