package com.fixpilot.controller;

import com.fixpilot.dto.WebhookPayload;
import com.fixpilot.model.Investigation;
import com.fixpilot.repository.InvestigationRepository;
import com.fixpilot.service.InvestigationOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility (single):
 * - Recognize a completed, failed GitHub Actions run and start an
 *   investigation for it. Nothing else.
 *
 * This class does no GitHub, Groq, or Docker work itself - it builds an
 * Investigation and hands it to InvestigationOrchestrator, which runs on
 * its own thread pool (see AsyncConfig), so this endpoint returns almost
 * immediately regardless of how long the pipeline takes.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final InvestigationRepository repository;
    private final InvestigationOrchestrator orchestrator;

    public WebhookController(InvestigationRepository repository, InvestigationOrchestrator orchestrator) {
        this.repository = repository;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/webhooks/github")
    public String handleWebhook(@RequestBody WebhookPayload payload) {
        if (payload.getWorkflowRun() == null) {
            return "ignored: not a workflow_run event";
        }
        boolean isCompletedFailure = "completed".equals(payload.getAction())
                && "failure".equals(payload.getWorkflowRun().getConclusion());
        if (!isCompletedFailure) {
            return "ignored: not a completed+failed run";
        }

        Investigation investigation = new Investigation();
        investigation.setOwner(payload.getRepository().getOwner().getLogin());
        investigation.setRepo(payload.getRepository().getName());
        investigation.setHeadSha(payload.getWorkflowRun().getHeadSha());
        investigation.setWorkflowRunId(payload.getWorkflowRun().getId());
        investigation.setBranch(payload.getWorkflowRun().getHeadBranch());
        repository.save(investigation);

        log.info("Starting investigation {} for {}/{}@{}", investigation.getId(),
                investigation.getOwner(), investigation.getRepo(), investigation.getHeadSha());

        orchestrator.run(investigation);
        return "investigation started: " + investigation.getId();
    }
}
