package com.fixpilot.dto;

/** Output of GroqService.rootCause() - a specific, evidence-backed hypothesis, not a generic restatement of the triage verdict. */
public record RootCauseResult(
        String summary,
        String suspectedCommitSha,
        String suspectedFile,
        int confidencePercent,
        String evidence
) {}
