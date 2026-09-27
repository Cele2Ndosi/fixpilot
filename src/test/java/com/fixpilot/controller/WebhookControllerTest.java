package com.fixpilot.controller;

import com.fixpilot.model.Investigation;
import com.fixpilot.repository.InvestigationRepository;
import com.fixpilot.service.InvestigationOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies two things a live webhook test would only catch expensively:
 * that WebhookPayload actually deserializes GitHub's real field names
 * (workflow_run, head_sha, etc.), and that the completed+failure filter
 * doesn't accidentally start an investigation for a passing run.
 */
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvestigationRepository repository;

    @MockBean
    private InvestigationOrchestrator orchestrator;

    private static final String FAILED_RUN_PAYLOAD = """
            {
              "action": "completed",
              "workflow_run": {
                "id": "555",
                "conclusion": "failure",
                "head_sha": "abc123",
                "head_branch": "main"
              },
              "repository": {
                "name": "demo-app",
                "owner": { "login": "acme" }
              }
            }
            """;

    private static final String SUCCESSFUL_RUN_PAYLOAD =
            FAILED_RUN_PAYLOAD.replace("\"conclusion\": \"failure\"", "\"conclusion\": \"success\"");

    @Test
    void failedCompletedRunStartsAnInvestigation() throws Exception {
        mockMvc.perform(post("/webhooks/github")
                        .contentType("application/json")
                        .content(FAILED_RUN_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith("investigation started")));

        verify(orchestrator).run(any(Investigation.class));
    }

    @Test
    void successfulRunIsIgnored() throws Exception {
        mockMvc.perform(post("/webhooks/github")
                        .contentType("application/json")
                        .content(SUCCESSFUL_RUN_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(content().string("ignored: not a completed+failed run"));

        verify(orchestrator, never()).run(any());
    }
}
