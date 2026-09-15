package com.fixpilot.controller;

import com.fixpilot.dto.EvidenceReport;
import com.fixpilot.model.Investigation;
import com.fixpilot.repository.InvestigationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Responsibility (single):
 * - Read-only access to investigations WebhookController has already
 *   created, for the demo terminal / a manual check. Never triggers a new
 *   investigation and never calls GitHubService, GroqService, or
 *   SandboxService - it only reads what InvestigationOrchestrator wrote.
 */
@RestController
@RequestMapping("/investigations")
public class InvestigationController {

    private final InvestigationRepository repository;

    public InvestigationController(InvestigationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Collection<Investigation> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Investigation> get(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The same markdown report a VERIFIED investigation posts to its PR - useful as a live demo backup. */
    @GetMapping(value = "/{id}/report", produces = "text/markdown")
    public ResponseEntity<String> report(@PathVariable String id) {
        return repository.findById(id)
                .map(investigation -> ResponseEntity.ok(EvidenceReport.render(investigation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
