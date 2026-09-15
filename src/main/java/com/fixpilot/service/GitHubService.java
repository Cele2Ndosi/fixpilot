package com.fixpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixpilot.config.FixPilotProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsibility (single):
 * - Wrap the GitHub REST API calls the agent needs and nothing else. Each
 *   public method here is one "tool" from the design doc's tool list:
 *   get_diff, get_commit_history, get_logs, get_previous_runs, plus the
 *   file-read and branch/commit primitives needed to open a PR.
 *
 * This class never decides *when* to call GitHub or *what to do* with the
 * response beyond returning it — that sequencing lives in
 * InvestigationOrchestrator. If you're tempted to add pipeline logic here
 * (e.g. "if the diff touches file X, then..."), it belongs in the
 * orchestrator instead.
 */
@Service
public class GitHubService {

    private final RestClient client;
    private final FixPilotProperties props;

    public GitHubService(RestClient githubRestClient, FixPilotProperties props) {
        this.client = githubRestClient;
        this.props = props;
    }

    /** Diff (files changed + patch text) for a single commit. */
    public JsonNode getCommitDiff(String owner, String repo, String sha) {
        return client.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}", owner, repo, sha)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Recent commit history on a branch, most recent first. */
    public JsonNode getCommitHistory(String owner, String repo, String branch, int perPage) {
        return client.get()
                .uri("/repos/{owner}/{repo}/commits?sha={branch}&per_page={perPage}", owner, repo, branch, perPage)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Jobs for a workflow run - needed to find job IDs before fetching logs. */
    public JsonNode getWorkflowRunJobs(String owner, String repo, String runId) {
        return client.get()
                .uri("/repos/{owner}/{repo}/actions/runs/{runId}/jobs", owner, repo, runId)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Plain-text logs for a single job. */
    public String getJobLogs(String owner, String repo, String jobId) {
        return client.get()
                .uri("/repos/{owner}/{repo}/actions/jobs/{jobId}/logs", owner, repo, jobId)
                .retrieve()
                .body(String.class);
    }

    /** Previous completed runs on the same branch, for flake-vs-consistent-failure comparison. */
    public JsonNode getPreviousRuns(String owner, String repo, String branch, int perPage) {
        return client.get()
                .uri("/repos/{owner}/{repo}/actions/runs?branch={branch}&status=completed&per_page={perPage}",
                        owner, repo, branch, perPage)
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * Raw contents-API metadata for a file at a given ref (branch, sha, or tag).
     * Kept private because callers only ever need one of the two things it
     * carries (the decoded text, or the blob sha needed to update it) - see
     * getFileContents and getFileSha below. Returns null if the file doesn't
     * exist at that ref, which is a normal outcome (e.g. root cause names a
     * file that doesn't exist at the base branch yet), not an error.
     */
    private JsonNode getFileMetadata(String owner, String repo, String path, String ref) {
        try {
            return client.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}?ref={ref}", owner, repo, path, ref)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException notFound) {
            return null;
        }
    }

    /** Decoded text content of a file at a given ref, or null if it doesn't exist there. */
    public String getFileContents(String owner, String repo, String path, String ref) {
        JsonNode meta = getFileMetadata(owner, repo, path, ref);
        if (meta == null) {
            return null;
        }
        String base64 = meta.get("content").asText().replace("\n", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    /** Blob sha of a file at a given ref - required by putFileContents when updating (not creating) a file. */
    public String getFileSha(String owner, String repo, String path, String ref) {
        JsonNode meta = getFileMetadata(owner, repo, path, ref);
        return meta == null ? null : meta.get("sha").asText();
    }

    /** Latest commit sha on a branch - used as the base point for a new branch. */
    public String getBranchHeadSha(String owner, String repo, String branch) {
        JsonNode ref = client.get()
                .uri("/repos/{owner}/{repo}/git/ref/heads/{branch}", owner, repo, branch)
                .retrieve()
                .body(JsonNode.class);
        return ref.at("/object/sha").asText();
    }

    /** Creates a new branch pointing at fromSha. */
    public void createBranch(String owner, String repo, String newBranch, String fromSha) {
        client.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .body(Map.of("ref", "refs/heads/" + newBranch, "sha", fromSha))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Creates or updates a single file on a branch via the Contents API.
     * Pass existingFileSha from getFileSha() when updating a file that
     * already exists on the base branch; pass null when the patch creates
     * a brand-new file.
     */
    public void putFileContents(String owner, String repo, String path, String branch,
                                 String newContent, String message, String existingFileSha) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "message", message,
                "content", Base64.getEncoder().encodeToString(newContent.getBytes(StandardCharsets.UTF_8)),
                "branch", branch
        ));
        if (existingFileSha != null) {
            body.put("sha", existingFileSha);
        }
        client.put()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /** Opens a PR carrying the patch branch, with the evidence report as the description. */
    public JsonNode createPullRequest(String owner, String repo, String headBranch, String baseBranch,
                                       String title, String body) {
        Map<String, Object> payload = Map.of(
                "title", title,
                "head", headBranch,
                "base", baseBranch,
                "body", body
        );
        return client.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    /** Posts the evidence report as a comment on an existing issue/PR. */
    public void commentOnPullRequest(String owner, String repo, int pullNumber, String body) {
        client.post()
                .uri("/repos/{owner}/{repo}/issues/{pullNumber}/comments", owner, repo, pullNumber)
                .body(Map.of("body", body))
                .retrieve()
                .toBodilessEntity();
    }

    public FixPilotProperties.GitHub defaults() {
        return props.getGithub();
    }
}
