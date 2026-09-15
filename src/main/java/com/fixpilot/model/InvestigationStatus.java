package com.fixpilot.model;

/**
 * Every value here is set by InvestigationOrchestrator and nowhere else -
 * it's the one place the pipeline's sequencing is expressed. VERIFIED and
 * UNCERTAIN are the two "successful investigation" outcomes (fixed, or
 * correctly declined to guess); FAILED means the pipeline itself broke
 * (e.g. a GitHub API error), not that the CI failure was hard to diagnose.
 */
public enum InvestigationStatus {
    RECEIVED,
    TRIAGING,
    ANALYZING_ROOT_CAUSE,
    REPRODUCING,
    GENERATING_FIX,
    VERIFYING,
    VERIFIED,
    UNCERTAIN,
    FAILED
}
