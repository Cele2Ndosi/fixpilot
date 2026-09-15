package com.fixpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixpilot.config.FixPilotProperties;
import com.fixpilot.dto.PatchResult;
import com.fixpilot.dto.RootCauseResult;
import com.fixpilot.dto.TriageResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Responsibility (single):
 * - Send exactly three kinds of request to Groq's chat-completions endpoint
 *   (triage, root-cause, patch generation) and parse each response into the
 *   matching record from com.fixpilot.dto.
 *
 * No GitHub calls, no Docker calls, and no decision about what happens next
 * belong here - that sequencing lives in InvestigationOrchestrator. If a
 * response fails to parse as JSON twice in a row, this class returns a
 * clearly-marked fallback result rather than throwing, so one bad model
 * response degrades an investigation to UNCERTAIN instead of crashing it.
 */
@Service
public class GroqService {

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public GroqService(RestClient groqRestClient, ObjectMapper mapper, FixPilotProperties props) {
        this.client = groqRestClient;
        this.mapper = mapper;
        this.model = props.getGroq().getModel();
    }

    /** Classifies a failure as a product bug, a flaky test, or an infra issue. */
    public TriageResult triage(String diff, String logs, String previousRunsSummary) {
        String system = """
                You are a CI failure triage engine. Given a commit diff, CI logs, and a
                summary of recent runs on the same branch, decide whether the failure is
                a PRODUCT_BUG, a FLAKY_TEST, or an INFRA_ISSUE.
                Respond with ONLY a JSON object, no markdown fences, no prose, matching
                exactly this shape:
                {"verdict": "PRODUCT_BUG|FLAKY_TEST|INFRA_ISSUE", "confidencePercent": 0-100,
                 "reasoning": "string", "failingTests": ["string"]}
                """;
        String user = "DIFF:\n" + diff + "\n\nLOGS:\n" + truncate(logs) + "\n\nRECENT RUNS:\n" + previousRunsSummary;
        return callAndParse(system, user, TriageResult.class,
                new TriageResult("UNCERTAIN", 0, "Groq response could not be parsed after retry.", List.of()));
    }

    /** Proposes a specific root-cause hypothesis: which commit, which file, why. */
    public RootCauseResult rootCause(String diff, String commitHistorySummary, String logs) {
        String system = """
                You are a root-cause analysis engine for CI failures. Given the diff of the
                suspected commit, recent commit history, and CI logs, identify the single
                most likely root cause.
                Respond with ONLY a JSON object, no markdown fences, no prose, matching
                exactly this shape:
                {"summary": "string", "suspectedCommitSha": "string", "suspectedFile": "string",
                 "confidencePercent": 0-100, "evidence": "string"}
                """;
        String user = "DIFF:\n" + diff + "\n\nCOMMIT HISTORY:\n" + commitHistorySummary
                + "\n\nLOGS:\n" + truncate(logs);
        return callAndParse(system, user, RootCauseResult.class,
                new RootCauseResult("Could not determine root cause.", "", "", 0,
                        "Groq response could not be parsed after retry."));
    }

    /**
     * Generates the smallest patch that could resolve the root cause.
     * previousAttemptFeedback is null on the first attempt; on retries it
     * carries the prior attempt's test output so the model sees what its
     * last guess actually broke, rather than repeating it blind.
     */
    public PatchResult generatePatch(RootCauseResult rootCause, String fileContents,
                                      int attemptNumber, String previousAttemptFeedback) {
        String system = """
                You are a minimal-fix generation engine. Given a root-cause hypothesis and
                the current contents of the suspected file, produce the SMALLEST possible
                fix as a unified diff (git apply -p1 compatible, using a/ and b/ prefixes).
                Respond with ONLY a JSON object, no markdown fences, no prose, matching
                exactly this shape:
                {"unifiedDiff": "string", "explanation": "string"}
                """;
        StringBuilder user = new StringBuilder();
        user.append("ROOT CAUSE:\n").append(rootCause.summary())
                .append("\nEvidence: ").append(rootCause.evidence())
                .append("\n\nFILE (").append(rootCause.suspectedFile()).append("):\n").append(fileContents);
        if (previousAttemptFeedback != null) {
            user.append("\n\nPREVIOUS ATTEMPT FAILED. Test output was:\n").append(truncate(previousAttemptFeedback));
        }

        record RawPatch(String unifiedDiff, String explanation) {}
        RawPatch raw = callAndParse(system, user.toString(), RawPatch.class,
                new RawPatch("", "Groq did not return a usable patch."));
        return new PatchResult(raw.unifiedDiff(), raw.explanation(), false, attemptNumber);
    }

    private <T> T callAndParse(String systemPrompt, String userPrompt, Class<T> targetType, T fallback) {
        String content = chatCompletion(systemPrompt, userPrompt);
        try {
            return mapper.readValue(content, targetType);
        } catch (Exception firstFailure) {
            String retryContent = chatCompletion(
                    systemPrompt + "\nIMPORTANT: your previous reply was not valid JSON. Return RAW JSON ONLY, "
                            + "no markdown code fences, no leading or trailing text.",
                    userPrompt);
            try {
                return mapper.readValue(retryContent, targetType);
            } catch (Exception secondFailure) {
                return fallback;
            }
        }
    }

    private String chatCompletion(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        JsonNode response = client.post()
                .uri("/chat/completions")
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
        return response.at("/choices/0/message/content").asText();
    }

    /**
     * Groq's context window is generous but not infinite, and CI logs from a
     * flaky-test rerun can run to tens of thousands of lines. Keep the tail -
     * the actual failure and stack trace are almost always at the end - and
     * drop the noisy middle rather than truncating from the front.
     */
    private String truncate(String text) {
        int limit = 12_000;
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        return "...[truncated " + (text.length() - limit) + " earlier characters]...\n"
                + text.substring(text.length() - limit);
    }
}
