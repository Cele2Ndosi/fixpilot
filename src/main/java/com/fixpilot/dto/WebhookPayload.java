package com.fixpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of GitHub's "workflow_run" webhook event. We only care about failed runs,
 * so anything else Github sends is ignored via JsonIgnoreProperties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookPayload {

    private String action;

    @JsonProperty("workflow_run")
    private WorkflowRun workflowRun;

    private Repository repository;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public WorkflowRun getWorkflowRun() { return workflowRun; }
    public void setWorkflowRun(WorkflowRun workflowRun) { this.workflowRun = workflowRun; }
    public Repository getRepository() { return repository; }
    public void setRepository(Repository repository) { this.repository = repository; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkflowRun {
        private String id;
        private String conclusion;
        @JsonProperty("head_sha")
        private String headSha;
        @JsonProperty("head_branch")
        private String headBranch;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getConclusion() { return conclusion; }
        public void setConclusion(String conclusion) { this.conclusion = conclusion; }
        public String getHeadSha() { return headSha; }
        public void setHeadSha(String headSha) { this.headSha = headSha; }
        public String getHeadBranch() { return headBranch; }
        public void setHeadBranch(String headBranch) { this.headBranch = headBranch; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Repository {
        private String name;
        private Owner owner;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Owner getOwner() { return owner; }
        public void setOwner(Owner owner) { this.owner = owner; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Owner {
        private String login;

        public String getLogin() { return login; }
        public void setLogin(String login) { this.login = login; }
    }
}
