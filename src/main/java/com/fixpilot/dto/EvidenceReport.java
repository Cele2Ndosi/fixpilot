package com.fixpilot.dto;

import com.fixpilot.model.Investigation;
import com.fixpilot.model.InvestigationStatus;

/**
 * Responsibility (single):
 * - Turn an Investigation's accumulated results into the markdown report a
 *   human actually reads (PR body, or GET /investigations/{id}/report).
 *
 * Pure function, no I/O: it only ever reads fields InvestigationOrchestrator
 * already populated. It never calls GitHubService, GroqService, or
 * SandboxService, and it never fabricates a section - a stage that hasn't
 * run yet (e.g. no patch was needed) is simply omitted rather than padded.
 */
public class EvidenceReport {

    public static String render(Investigation investigation) {
        StringBuilder sb = new StringBuilder();
        sb.append("### FixPilot Investigation `").append(investigation.getId(), 0, 8).append("`\n\n");

        TriageResult triage = investigation.getTriage();
        if (triage != null) {
            sb.append("**Verdict:** ").append(triage.verdict())
                    .append(" (confidence ").append(triage.confidencePercent()).append("%)\n\n");
            sb.append("Failing tests: ").append(String.join(", ", triage.failingTests())).append("\n\n");
        }

        RootCauseResult rc = investigation.getRootCause();
        if (rc != null) {
            sb.append("**Root cause**\n\n");
            sb.append(rc.summary()).append("\n\n");
            sb.append("- Suspected commit: `").append(rc.suspectedCommitSha()).append("`\n");
            sb.append("- Suspected file: `").append(rc.suspectedFile()).append("`\n");
            sb.append("- Confidence: ").append(rc.confidencePercent()).append("%\n");
            sb.append("- Evidence: ").append(rc.evidence()).append("\n\n");
        }

        PatchResult patch = investigation.getPatch();
        if (patch != null) {
            sb.append("**Proposed fix** (attempt ").append(patch.attemptNumber()).append(")\n\n");
            sb.append(patch.explanation()).append("\n\n");
            sb.append("```diff\n").append(patch.unifiedDiff()).append("\n```\n\n");
        }

        VerificationResult v = investigation.getVerification();
        if (v != null) {
            sb.append("**Validation**\n\n");
            sb.append("| Check | Result |\n|---|---|\n");
            sb.append("| Reproduction before fix | ").append(v.reproductionPassedBeforeFix() ? "PASS (bug not reproduced?)" : "FAIL (reproduced)").append(" |\n");
            sb.append("| Reproduction after fix | ").append(v.reproductionPassedAfterFix() ? "PASS" : "FAIL").append(" |\n");
            sb.append("| Related tests | ").append(v.relatedTestsPassed()).append("/").append(v.relatedTestsTotal()).append(" |\n");
            sb.append("| Full suite | ").append(v.fullSuitePassed()).append("/").append(v.fullSuiteTotal()).append(" |\n\n");
            sb.append("**Overall: ").append(v.verified() ? "VERIFIED" : "UNCERTAIN — needs human review").append("**\n");
        }

        if (investigation.getStatus() == InvestigationStatus.FAILED) {
            sb.append("\n_Investigation failed: ").append(investigation.getErrorMessage()).append("_\n");
        }

        return sb.toString();
    }
}
