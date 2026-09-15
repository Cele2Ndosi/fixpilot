package com.fixpilot.dto;

import java.util.List;

/** Output of GroqService.triage(). verdict is one of PRODUCT_BUG, FLAKY_TEST, INFRA_ISSUE, or UNCERTAIN (parse-failure fallback). */
public record TriageResult(
        String verdict,          // "PRODUCT_BUG" | "FLAKY_TEST" | "INFRA_ISSUE"
        int confidencePercent,
        String reasoning,
        List<String> failingTests
) {}
