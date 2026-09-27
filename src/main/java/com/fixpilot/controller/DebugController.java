package com.fixpilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixpilot.dto.RootCauseResult;
import com.fixpilot.dto.TriageResult;
import com.fixpilot.service.GitHubService;
import com.fixpilot.service.GroqService;
import com.fixpilot.service.SandboxService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility (single):
 * - Give you a curl-friendly way to exercise GitHubService, GroqService, and
 *   SandboxService ONE AT A TIME, before trusting the full webhook-triggered
 *   pipeline.
 *
 * Only active under the "debug" Spring profile, so it can never accidentally
 * answer a real webhook call or show up during the actual demo. Run with
 * `mvn spring-boot:run -Dspring-boot.run.profiles=debug` to enable it.
 */
@RestController
@RequestMapping("/debug")
@Profile("debug")
public class DebugController {

    private final GitHubService gitHub;
    private final GroqService groq;
    private final SandboxService sandbox;

    public DebugController(GitHubService gitHub, GroqService groq, SandboxService sandbox) {
        this.gitHub = gitHub;
        this.groq = groq;
        this.sandbox = sandbox;
    }

    /** Confirms your PAT can read the seeded repo at all - the cheapest possible first check. */
    @GetMapping("/github/commits")
    public JsonNode commits(@RequestParam String owner, @RequestParam String repo,
                             @RequestParam(defaultValue = "main") String branch) {
        return gitHub.getCommitHistory(owner, repo, branch, 5);
    }

    @GetMapping("/github/diff")
    public JsonNode diff(@RequestParam String owner, @RequestParam String repo, @RequestParam String sha) {
        return gitHub.getCommitDiff(owner, repo, sha);
    }

    @GetMapping("/github/logs")
    public String logs(@RequestParam String owner, @RequestParam String repo, @RequestParam String runId) {
        JsonNode jobs = gitHub.getWorkflowRunJobs(owner, repo, runId);
        StringBuilder sb = new StringBuilder();
        for (JsonNode job : jobs.get("jobs")) {
            sb.append("--- ").append(job.get("name").asText()).append(" ---\n");
            sb.append(gitHub.getJobLogs(owner, repo, job.get("id").asText())).append("\n");
        }
        return sb.toString();
    }

    /** Tests Groq in isolation - no GitHub token or Docker needed, just paste in a diff and some log text. */
    public record TriageRequest(String diff, String logs, String previousRuns) {}

    @PostMapping("/groq/triage")
    public TriageResult triage(@RequestBody TriageRequest request) {
        return groq.triage(request.diff(), request.logs(), request.previousRuns());
    }

    public record RootCauseRequest(String diff, String commitHistory, String logs) {}

    @PostMapping("/groq/root-cause")
    public RootCauseResult rootCause(@RequestBody RootCauseRequest request) {
        return groq.rootCause(request.diff(), request.commitHistory(), request.logs());
    }

    /** Tests Docker + git clone against a real seeded commit, independent of Groq entirely. */
    @GetMapping("/sandbox/reproduce")
    public SandboxService.TestRunResult reproduce(@RequestParam String owner, @RequestParam String repo,
                                                   @RequestParam String sha) {
        return sandbox.reproduce(owner, repo, sha, "debug-" + System.currentTimeMillis());
    }
}
