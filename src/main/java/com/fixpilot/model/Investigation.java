package com.fixpilot.model;

import com.fixpilot.dto.PatchResult;
import com.fixpilot.dto.RootCauseResult;
import com.fixpilot.dto.TriageResult;
import com.fixpilot.dto.VerificationResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Mutable record of one CI-failure investigation from receipt through verification.
 * In-memory for the hackathon build; swap InvestigationRepository for a JPA-backed
 * one later without touching the orchestrator.
 */
public class Investigation {

    private final String id = UUID.randomUUID().toString();
    private final Instant createdAt = Instant.now();

    private String owner;
    private String repo;
    private String headSha;
    private String workflowRunId;
    private String branch;

    private InvestigationStatus status = InvestigationStatus.RECEIVED;

    private TriageResult triage;
    private RootCauseResult rootCause;
    private PatchResult patch;
    private VerificationResult verification;

    private String pullRequestUrl;
    private String errorMessage;

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public InvestigationStatus getStatus() { return status; }
    public void setStatus(InvestigationStatus status) { this.status = status; }

    public TriageResult getTriage() { return triage; }
    public void setTriage(TriageResult triage) { this.triage = triage; }
    public RootCauseResult getRootCause() { return rootCause; }
    public void setRootCause(RootCauseResult rootCause) { this.rootCause = rootCause; }
    public PatchResult getPatch() { return patch; }
    public void setPatch(PatchResult patch) { this.patch = patch; }
    public VerificationResult getVerification() { return verification; }
    public void setVerification(VerificationResult verification) { this.verification = verification; }

    public String getPullRequestUrl() { return pullRequestUrl; }
    public void setPullRequestUrl(String pullRequestUrl) { this.pullRequestUrl = pullRequestUrl; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
