package com.fixpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility (single):
 * - Bind fixpilot.* from application.yml into typed config, so every
 *   service asks Spring for a value instead of reading the environment
 *   itself. Notably includes sandbox.max-fix-retries: that cap is set here,
 *   in Iteration 2, even though nothing reads it until the fix/verify loop
 *   in InvestigationOrchestrator - it's a lot easier to bound a loop before
 *   it exists than to remember to add a cap after it's already looping.
 */
@ConfigurationProperties(prefix = "fixpilot")
public class FixPilotProperties {

    private final GitHub github = new GitHub();
    private final Groq groq = new Groq();
    private final Sandbox sandbox = new Sandbox();

    public GitHub getGithub() { return github; }
    public Groq getGroq() { return groq; }
    public Sandbox getSandbox() { return sandbox; }

    public static class GitHub {
        private String token;
        private String owner;
        private String repo;
        private String apiBaseUrl;

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    }

    public static class Groq {
        private String apiKey;
        private String apiBaseUrl;
        private String model;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    public static class Sandbox {
        private String workdir;
        private String dockerImage;
        private int timeoutSeconds = 120;
        private int maxFixRetries = 2;

        public String getWorkdir() { return workdir; }
        public void setWorkdir(String workdir) { this.workdir = workdir; }
        public String getDockerImage() { return dockerImage; }
        public void setDockerImage(String dockerImage) { this.dockerImage = dockerImage; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxFixRetries() { return maxFixRetries; }
        public void setMaxFixRetries(int maxFixRetries) { this.maxFixRetries = maxFixRetries; }
    }
}
