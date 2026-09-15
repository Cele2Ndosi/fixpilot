package com.fixpilot.dto;

/** Output of GroqService.generatePatch(). appliedCleanly is set by the orchestrator after SandboxService attempts `git apply`, not by Groq itself. */
public record PatchResult(
        String unifiedDiff,
        String explanation,
        boolean appliedCleanly,
        int attemptNumber
) {}
