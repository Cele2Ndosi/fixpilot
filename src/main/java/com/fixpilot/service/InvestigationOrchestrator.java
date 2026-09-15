package com.fixpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixpilot.config.FixPilotProperties;
import com.fixpilot.dto.EvidenceReport;
import com.fixpilot.dto.PatchResult;
import com.fixpilot.dto.RootCauseResult;
import com.fixpilot.dto.TriageResult;
import com.fixpilot.dto.VerificationResult;
import com.fixpilot.model.Investigation;
import com.fixpilot.model.InvestigationStatus;
import com.fixpilot.repository.InvestigationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responsibility (single):
 * - Own the sequencing of one investigation from RECEIVED to a terminal
 *   status (VERIFIED / UNCERTAIN / FAILED), reading from and writing to a
 *   single Investigation object at every stage.
 *
 * This is the ONLY class allowed to call GitHubService, GroqService, and
 * SandboxService together. Controllers never call those services directly,
 * and EvidenceReport never calls any of them at all - it only reads the
 * Investigation this class has already populated. If a new pipeline stage
 * is needed later, it's added here as one more step in runPipeline(), not
 * as a new service the controller wires up separately.
 */
@Service
public class InvestigationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(InvestigationOrchestrator.class);

    private final GitHubService gitHub;
    private final GroqService groq;
    private final SandboxService sandbox;
    private final InvestigationRepository repository;
    private final int maxFixRetries;

    public InvestigationOrchestrator(GitHubService gitHub, GroqService groq, SandboxService sandbox,
                                      InvestigationRepository repository, FixPilotProperties props) {
        this.gitHub = gitHub;
        this.groq = groq;
        this.sandbox = sandbox;
        this.repository = repository;
        this.maxFixRetries = props.getSandbox().getMaxFixRetries();
    }

    /**
     * Entry point called by WebhookController. Runs on the dedicated
     * "investigationExecutor" pool (see AsyncConfig) so the webhook that
     * triggered this returns immediately instead of blocking on a pipeline
     * that involves multiple Groq calls and at least one Docker run.
     */
    @Async("investigationExecutor")
    public void run(Investigation investigation) {
        try {
            runPipeline(investigation);
        } catch (Exception e) {
            log.error("Investigation {} failed", investigation.getId(), e);
            investigation.setStatus(InvestigationStatus.FAILED);
            investigation.setErrorMessage(e.getMessage());
            repository.save(investigation);
        }
    }

    private void runPipeline(Investigation investigation) {
        String owner = investigation.getOwner();
        String repo = investigation.getRepo();
        String sha = investigation.getHeadSha();

        // --- Stage 1: Triage ---
        investigation.setStatus(InvestigationStatus.TRIAGING);
        repository.save(investigation);

        JsonNode commitDiff = gitHub.getCommitDiff(owner, repo, sha);
        String logs = collectJobLogs(owner, repo, gitHub.getWorkflowRunJobs(owner, repo, investigation.getWorkflowRunId()));
        String previousRunsSummary = summarizePreviousRuns(gitHub.getPreviousRuns(owner, repo, investigation.getBranch(), 10));

        TriageResult triage = groq.triage(commitDiff.toString(), logs, previousRunsSummary);
        investigation.setTriage(triage);
        repository.save(investigation);

        boolean actionable = "PRODUCT_BUG".equals(triage.verdict()) || "FLAKY_TEST".equals(triage.verdict());
        if (!actionable) {
            // INFRA_ISSUE and UNCERTAIN both stop here - fixing a bad env var or a
            // parse failure isn't a code patch, and a human should look at either.
            investigation.setStatus(InvestigationStatus.UNCERTAIN);
            repository.save(investigation);
            return;
        }

        // --- Stage 2: Root cause ---
        investigation.setStatus(InvestigationStatus.ANALYZING_ROOT_CAUSE);
        repository.save(investigation);

        String commitHistorySummary = summarizeCommitHistory(gitHub.getCommitHistory(owner, repo, investigation.getBranch(), 10));
        RootCauseResult rootCause = groq.rootCause(commitDiff.toString(), commitHistorySummary, logs);
        investigation.setRootCause(rootCause);
        repository.save(investigation);

        // --- Stage 3: Reproduce ---
        investigation.setStatus(InvestigationStatus.REPRODUCING);
        repository.save(investigation);

        SandboxService.TestRunResult reproBeforeFix = sandbox.reproduce(owner, repo, sha, investigation.getId());
        if (reproBeforeFix.passed()) {
            // Couldn't reproduce the failure in isolation - don't fabricate a fix
            // for something we can't independently confirm is broken.
            investigation.setStatus(InvestigationStatus.UNCERTAIN);
            repository.save(investigation);
            return;
        }

        // --- Stage 4 + 5: Generate fix, verify, retry within the cap ---
        investigation.setStatus(InvestigationStatus.GENERATING_FIX);
        repository.save(investigation);

        PatchResult acceptedPatch = null;
        SandboxService.TestRunResult verificationRun = null;
        String previousFeedback = null;
        String suspectedFileContents = fetchSuspectedFileContents(owner, repo, sha, rootCause);

        for (int attempt = 1; attempt <= maxFixRetries; attempt++) {
            PatchResult candidate = groq.generatePatch(rootCause, suspectedFileContents, attempt, previousFeedback);
            investigation.setPatch(candidate);
            repository.save(investigation);

            investigation.setStatus(InvestigationStatus.VERIFYING);
            repository.save(investigation);

            SandboxService.TestRunResult afterFix =
                    sandbox.applyPatchAndRerun(owner, repo, sha, investigation.getId(), candidate.unifiedDiff());

            if (afterFix.passed()) {
                acceptedPatch = candidate;
                verificationRun = afterFix;
                break;
            }
            previousFeedback = afterFix.output();
            log.info("Investigation {}: fix attempt {} did not pass verification", investigation.getId(), attempt);
        }

        if (acceptedPatch == null) {
            // Every attempt within the cap failed verification. This is the
            // "Quality Judge" outcome from the design doc, implemented as a
            // bounded loop rather than a fifth agent: don't open a PR for a
            // patch that didn't prove itself.
            investigation.setStatus(InvestigationStatus.UNCERTAIN);
            repository.save(investigation);
            return;
        }

        VerificationResult verification = new VerificationResult(
                false, // reproductionPassedBeforeFix - it correctly failed, confirming the bug
                true,  // reproductionPassedAfterFix
                verificationRun.testsPassed(), verificationRun.testsTotal(),
                verificationRun.testsPassed(), verificationRun.testsTotal(),
                true
        );
        investigation.setVerification(verification);
        investigation.setStatus(InvestigationStatus.VERIFIED);
        repository.save(investigation);

        // --- Stage 6: Open the PR ---
        openPullRequest(investigation, verificationRun);
    }

    /**
     * Commits every file the verified patch touched (from
     * verificationRun.patchedFileContents(), harvested by SandboxService
     * straight from the checkout git apply produced) onto a new branch, then
     * opens a PR with the rendered evidence report as the description.
     */
    private void openPullRequest(Investigation investigation, SandboxService.TestRunResult verificationRun) {
        String owner = investigation.getOwner();
        String repo = investigation.getRepo();
        String baseBranch = investigation.getBranch();
        String branchName = "fixpilot/" + investigation.getId().substring(0, 8);
        String commitMessage = "FixPilot: " + investigation.getRootCause().summary();

        String headSha = gitHub.getBranchHeadSha(owner, repo, baseBranch);
        gitHub.createBranch(owner, repo, branchName, headSha);

        for (Map.Entry<String, String> changedFile : verificationRun.patchedFileContents().entrySet()) {
            String existingSha = gitHub.getFileSha(owner, repo, changedFile.getKey(), baseBranch);
            gitHub.putFileContents(owner, repo, changedFile.getKey(), branchName,
                    changedFile.getValue(), commitMessage, existingSha);
        }

        String report = EvidenceReport.render(investigation);
        JsonNode pr = gitHub.createPullRequest(owner, repo, branchName, baseBranch, commitMessage, report);
        if (pr != null && pr.has("html_url")) {
            investigation.setPullRequestUrl(pr.get("html_url").asText());
            repository.save(investigation);
        }
    }

    private String fetchSuspectedFileContents(String owner, String repo, String sha, RootCauseResult rootCause) {
        if (rootCause.suspectedFile() == null || rootCause.suspectedFile().isBlank()) {
            return "";
        }
        String content = gitHub.getFileContents(owner, repo, rootCause.suspectedFile(), sha);
        return content == null ? "" : content;
    }

    private String collectJobLogs(String owner, String repo, JsonNode jobs) {
        StringBuilder sb = new StringBuilder();
        if (jobs != null && jobs.has("jobs")) {
            for (JsonNode job : jobs.get("jobs")) {
                sb.append("--- job ").append(job.get("name").asText()).append(" ---\n");
                sb.append(gitHub.getJobLogs(owner, repo, job.get("id").asText())).append("\n");
            }
        }
        return sb.toString();
    }

    private String summarizePreviousRuns(JsonNode runs) {
        List<String> lines = new ArrayList<>();
        if (runs != null && runs.has("workflow_runs")) {
            for (JsonNode run : runs.get("workflow_runs")) {
                lines.add(run.get("head_sha").asText().substring(0, 7) + ": " + run.get("conclusion").asText());
            }
        }
        return String.join("\n", lines);
    }

    private String summarizeCommitHistory(JsonNode commits) {
        List<String> lines = new ArrayList<>();
        if (commits != null && commits.isArray()) {
            for (JsonNode commit : commits) {
                lines.add(commit.get("sha").asText().substring(0, 7) + ": " + commit.at("/commit/message").asText());
            }
        }
        return String.join("\n", lines);
    }
}
