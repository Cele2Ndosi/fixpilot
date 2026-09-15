package com.fixpilot.dto;

/** Final validation numbers for a VERIFIED investigation, built entirely from real SandboxService.TestRunResult data - nothing here is estimated. */
public record VerificationResult(
        boolean reproductionPassedBeforeFix,
        boolean reproductionPassedAfterFix,
        int relatedTestsPassed,
        int relatedTestsTotal,
        int fullSuitePassed,
        int fullSuiteTotal,
        boolean verified
) {}
