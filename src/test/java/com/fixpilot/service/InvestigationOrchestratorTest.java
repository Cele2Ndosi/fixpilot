package com.fixpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixpilot.config.FixPilotProperties;
import com.fixpilot.dto.PatchResult;
import com.fixpilot.dto.RootCauseResult;
import com.fixpilot.dto.TriageResult;
import com.fixpilot.model.Investigation;
import com.fixpilot.model.InvestigationStatus;
import com.fixpilot.repository.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * These tests mock GitHubService, GroqService, and SandboxService entirely -
 * no network, no Docker, no API keys required. They exist to pin down the
 * ORCHESTRATOR'S SEQUENCING (what runs, what's skipped, when the retry loop
 * stops) which is exactly the logic that's hardest to verify by eye and
 * easiest to get subtly wrong when the pipeline is edited later.
 *
 * Note: InvestigationOrchestrator.run() is annotated @Async in the real app,
 * but that annotation only takes effect through a Spring-managed proxy. This
 * test constructs the orchestrator directly with `new`, so run() executes
 * synchronously on the test thread - by the time run() returns, the pipeline
 * has already finished and assertions can run immediately.
 */
class InvestigationOrchestratorTest {

    @Mock private GitHubService gitHub;
    @Mock private GroqService groq;
    @Mock private SandboxService sandbox;

    private InvestigationOrchestrator orchestrator;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode empty;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        InvestigationRepository repository = new InvestigationRepository();
        FixPilotProperties props = new FixPilotProperties();
        props.getSandbox().setMaxFixRetries(2);
        orchestrator = new InvestigationOrchestrator(gitHub, groq, sandbox, repository, props);

        empty = mapper.readTree("{}");
        when(gitHub.getCommitDiff(any(), any(), any())).thenReturn(empty);
        when(gitHub.getWorkflowRunJobs(any(), any(), any())).thenReturn(empty);
        when(gitHub.getPreviousRuns(any(), any(), any(), anyInt())).thenReturn(empty);
        when(gitHub.getCommitHistory(any(), any(), any(), anyInt())).thenReturn(empty);
    }

    private Investigation newInvestigation() {
        Investigation inv = new Investigation();
        inv.setOwner("acme");
        inv.setRepo("demo-app");
        inv.setHeadSha("abc123");
        inv.setBranch("main");
        inv.setWorkflowRunId("999");
        return inv;
    }

    @Test
    void infraIssueStopsBeforeRootCause() {
        Investigation inv = newInvestigation();
        when(groq.triage(any(), any(), any()))
                .thenReturn(new TriageResult("INFRA_ISSUE", 80, "bad env var", List.of()));

        orchestrator.run(inv);

        assertThat(inv.getStatus()).isEqualTo(InvestigationStatus.UNCERTAIN);
        verify(groq, never()).rootCause(any(), any(), any());
        verify(sandbox, never()).reproduce(any(), any(), any(), any());
    }

    @Test
    void cannotReproduceStopsBeforeFixGeneration() {
        Investigation inv = newInvestigation();
        when(groq.triage(any(), any(), any()))
                .thenReturn(new TriageResult("PRODUCT_BUG", 90, "race condition", List.of("checkout.spec.ts")));
        when(groq.rootCause(any(), any(), any()))
                .thenReturn(new RootCauseResult("race condition", "abc123", "src/checkout.ts", 90, "evidence"));
        // passed=true means the sandbox could NOT reproduce the failure.
        when(sandbox.reproduce(any(), any(), any(), any()))
                .thenReturn(new SandboxService.TestRunResult(true, 0, "unexpectedly passed", 10, 10, Map.of()));

        orchestrator.run(inv);

        assertThat(inv.getStatus()).isEqualTo(InvestigationStatus.UNCERTAIN);
        verify(groq, never()).generatePatch(any(), any(), anyInt(), any());
    }

    @Test
    void exhaustingRetriesLandsOnUncertainAndNeverOpensAPr() {
        Investigation inv = newInvestigation();
        when(groq.triage(any(), any(), any()))
                .thenReturn(new TriageResult("PRODUCT_BUG", 90, "race condition", List.of("checkout.spec.ts")));
        when(groq.rootCause(any(), any(), any()))
                .thenReturn(new RootCauseResult("race condition", "abc123", "src/checkout.ts", 90, "evidence"));
        when(sandbox.reproduce(any(), any(), any(), any()))
                .thenReturn(new SandboxService.TestRunResult(false, 1, "failed as expected", 9, 10, Map.of()));
        when(groq.generatePatch(any(), any(), anyInt(), any()))
                .thenReturn(new PatchResult("--- a/x\n+++ b/x\n", "attempt", false, 1));
        // Every attempt keeps failing verification.
        when(sandbox.applyPatchAndRerun(any(), any(), any(), any(), any()))
                .thenReturn(new SandboxService.TestRunResult(false, 1, "still failing", 9, 10, Map.of()));

        orchestrator.run(inv);

        assertThat(inv.getStatus()).isEqualTo(InvestigationStatus.UNCERTAIN);
        // props.sandbox.maxFixRetries = 2 in setUp() - confirms the loop actually stops at the cap
        // instead of retrying forever or off-by-one.
        verify(groq, times(2)).generatePatch(any(), any(), anyInt(), any());
        verify(gitHub, never()).createPullRequest(any(), any(), any(), any(), any(), any());
    }

    @Test
    void firstAttemptVerifiedOpensPullRequestWithPatchedFiles() throws Exception {
        Investigation inv = newInvestigation();
        when(groq.triage(any(), any(), any()))
                .thenReturn(new TriageResult("PRODUCT_BUG", 94, "race condition", List.of("checkout.spec.ts")));
        when(groq.rootCause(any(), any(), any()))
                .thenReturn(new RootCauseResult("race condition", "abc123", "src/checkout.ts", 94, "evidence"));
        when(sandbox.reproduce(any(), any(), any(), any()))
                .thenReturn(new SandboxService.TestRunResult(false, 1, "failed as expected", 46, 47, Map.of()));
        when(groq.generatePatch(any(), any(), anyInt(), any()))
                .thenReturn(new PatchResult("--- a/x\n+++ b/x\n", "attempt", false, 1));
        when(sandbox.applyPatchAndRerun(any(), any(), any(), any(), any()))
                .thenReturn(new SandboxService.TestRunResult(true, 0, "passed", 47, 47,
                        Map.of("src/checkout.ts", "fixed file content")));
        when(gitHub.getBranchHeadSha(any(), any(), any())).thenReturn("headsha");
        when(gitHub.getFileSha(any(), any(), any(), any())).thenReturn("blobsha");
        when(gitHub.createPullRequest(any(), any(), any(), any(), any(), any()))
                .thenReturn(mapper.readTree("{\"html_url\":\"https://github.com/acme/demo-app/pull/1\"}"));

        orchestrator.run(inv);

        assertThat(inv.getStatus()).isEqualTo(InvestigationStatus.VERIFIED);
        assertThat(inv.getPullRequestUrl()).isEqualTo("https://github.com/acme/demo-app/pull/1");
        verify(groq, times(1)).generatePatch(any(), any(), anyInt(), any());
        verify(gitHub).createBranch(eq("acme"), eq("demo-app"), startsWith("fixpilot/"), eq("headsha"));
        verify(gitHub).putFileContents(eq("acme"), eq("demo-app"), eq("src/checkout.ts"),
                startsWith("fixpilot/"), eq("fixed file content"), any(), eq("blobsha"));
    }
}
