# FixPilot — Corrected Build Guide

This is the revised build plan for the hackathon week. It keeps the original
pitch's iteration-by-iteration, "always-working" philosophy, but fixes the
issues found in review of the original write-up:

| Problem in original plan | Fix applied here |
|---|---|
| Five separate agents (Detective / Flake Hunter / Reproducer / Fixer / Judge) implied five services → inter-service auth/networking eats days you don't have | One Spring Boot orchestrator with five internal **pipeline stages**. Same narrative in the demo, none of the microservice plumbing |
| React dashboard sketched as an early deliverable | Cut entirely until Iteration 8, and even then it's optional — the PR comment *is* the artifact judges screenshot |
| Sandbox execution of AI-generated patches had no isolation boundary specified | Fresh Docker container per run, no persistent volume, from the very first sandbox iteration (Iteration 4) — not bolted on after something breaks |
| Fix-generation retry loop was open-ended in the pitch | Capped by `fixpilot.sandbox.max-fix-retries` from the moment the loop is written, so a bad patch can't hang the demo |
| Confidence-score card in the pitch reads like a mockup with invented numbers | Every field in the score comes from a real `TriageResult` / `RootCauseResult` / `VerificationResult` returned by an earlier stage — there is no decorative layer |
| Benchmark table ("60% → 90%") presented as if already known | Numbers are explicitly deferred to a real run against the seeded repo in the final iteration. Nothing is written down before it's measured |
| "Production" framing implied a GitHub App with OAuth | A personal access token is used for the week. The upgrade path is noted, not built |

**Golden rule kept from the architecture:** only `InvestigationOrchestrator`
is allowed to call `GitHubService`, `GroqService`, and `SandboxService`.
Controllers never call them directly, and the evidence renderer only ever
reads an `Investigation` object — it doesn't make network or process calls
of its own. This is what keeps a mid-week refactor (e.g. swapping Groq
models, or adding a real database) from touching more than one class.

---

## Final architecture (decided up front, not iterated into)

```text
GitHub Actions (seeded repo)
        │  workflow_run: completed, conclusion: failure
        ▼
WebhookController  ─────────────────────►  InvestigationRepository (in-memory)
        │
        ▼
InvestigationOrchestrator
        │
        ├──► GitHubService   (get_diff, get_commit_history, get_logs, get_previous_runs, create_pr)
        ├──► SandboxService  (clone into Docker, run_test, run_related_tests, run_full_suite)
        └──► GroqService     (triage, root_cause, generate_patch)
        │
        ▼
Investigation (accumulates TriageResult → RootCauseResult → PatchResult → VerificationResult)
        │
        ▼
EvidenceReport.render()  ──────────────────►  PR comment / demo terminal
```

Because this is fixed on day one, no service written in an early iteration
gets rewritten later — later iterations only add stages that read from and
write to the same `Investigation` object.

---

## Iteration 1 — Seed the demo repo (no FixPilot code yet)

### Goal
Build the thing FixPilot will investigate *before* writing any agent code.
Without this, every later iteration is untestable.

### What to build
A small sample app (Node/Express or a tiny Spring Boot service both work —
pick whichever you can seed bugs into fastest) with a GitHub Actions
workflow that runs its test suite on every push. Then, on top of a clean
passing baseline commit, add 6–8 commits that each introduce one
deliberate, realistic failure:

- a race condition (order confirmed before payment completes)
- a timeout (dependent service called with too short a deadline)
- an off-by-one / null-check regression
- a genuinely flaky test (uses `sleep`/timing instead of an event wait)
- an infra-style failure (missing env var, wrong port)

### File tree after this iteration
```text
fixpilot-demo-app/
├── src/                     (whatever the sample app needs)
├── test/
├── .github/
│   └── workflows/
│       └── ci.yml
└── package.json  (or pom.xml)
```

### `.github/workflows/ci.yml`
```yaml
name: CI
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - run: npm ci
      - run: npm test
```

### Iteration 1 test checklist
- [ ] Baseline commit: CI is green
- [ ] Each of the 6–8 seeded-bug commits: CI is red, and you know *why* it's
      red (write this down now — it's your ground truth for the benchmark
      in Iteration 10)
- [ ] Repo is public or FixPilot's PAT has access to it

---

## Iteration 2 — Spring Boot skeleton, config, health check

### Goal
A running application before any tool integration exists. This is the
"always-working" anchor for every iteration after it.

### File tree after this iteration
```text
fixpilot/
├── pom.xml
└── src/main/
    ├── java/com/fixpilot/
    │   ├── FixPilotApplication.java
    │   └── config/
    │       ├── FixPilotProperties.java
    │       └── AppConfig.java
    └── resources/
        └── application.yml
```

`pom.xml` pulls in `spring-boot-starter-web` and `spring-boot-starter-validation`
on Spring Boot 3.2 (this version matters — it's what gives you the built-in
`RestClient`, so Iteration 3 doesn't need to pull in WebClient/Reactor just
to call two REST APIs).

`FixPilotProperties` binds `fixpilot.github.*`, `fixpilot.groq.*`, and
`fixpilot.sandbox.*` from `application.yml` — including
`sandbox.max-fix-retries`, set now even though nothing reads it until
Iteration 7, so the cap in the earlier table is never something you have to
remember to add later.

`AppConfig` exposes two `RestClient` beans (`githubRestClient`,
`groqRestClient`), each pre-configured with the right base URL and auth
header, so every later service just asks Spring for a `RestClient` and gets
one that's already authenticated.

### Iteration 2 test checklist
- [ ] `mvn spring-boot:run` starts without errors
- [ ] `GITHUB_TOKEN`, `GITHUB_OWNER`, `GITHUB_REPO`, `GROQ_API_KEY` read
      correctly from environment variables (log them — minus the secrets —
      on startup to confirm)

---

## Iteration 3 — GitHub tool service

### Goal
Every `get_*` tool from the design doc, callable and returning real data,
before any AI or sandbox code exists to consume it.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── service/
        └── GitHubService.java   ← NEW
```

`GitHubService` wraps the GitHub REST API: `getCommitDiff`,
`getCommitHistory`, `getWorkflowRunJobs`, `getJobLogs`, `getPreviousRuns`,
plus `createPullRequest` and `commentOnPullRequest` for later. Every method
returns a `JsonNode` rather than a hand-built DTO — for a week-long build,
modeling every GitHub response shape is time you don't have, and nothing
downstream needs more than a handful of fields out of each response.

> Use a PAT (`repo` + `workflow` scopes), not a GitHub App. A GitHub App
> gets you installation tokens and webhook secret verification "for free,"
> but also costs you the better part of a day on OAuth flows you don't
> need for a single demo repo you control.

### Quick manual test (add temporarily, delete before Iteration 9)
```java
@RestController
class DebugController {
    private final GitHubService gh;
    DebugController(GitHubService gh) { this.gh = gh; }

    @GetMapping("/debug/commits")
    JsonNode commits() {
        return gh.getCommitHistory("your-org", "fixpilot-demo-app", "main", 5);
    }
}
```

### Iteration 3 test checklist
- [ ] `/debug/commits` returns real commit data from the seeded repo
- [ ] `getWorkflowRunJobs` + `getJobLogs` return the actual failure output
      for one of your seeded-bug commits (confirm the log text mentions
      the error you expect)

---

## Iteration 4 — Sandbox tool service

### Goal
Reproduce a seeded failure in an isolated container, on demand, from a
commit SHA. This is the piece the original pitch under-specified — get the
isolation boundary right now, not after a generated patch does something
you didn't expect.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── service/
        └── SandboxService.java   ← NEW
```

`SandboxService.reproduce(sha)`:
1. `git clone` the demo repo into a fresh temp directory under
   `fixpilot.sandbox.workdir` (one directory per investigation ID, never
   reused).
2. `git checkout <sha>`.
3. Run the test suite **inside a Docker container** (`docker run --rm
   --network=none -v <tempdir>:/app -w /app <sandbox-image> npm test`),
   capturing stdout/stderr and the exit code.
4. Delete the temp directory afterward regardless of outcome.

`--network=none` and `--rm` are the two flags doing the safety work here —
no persistent state survives a run, and nothing the test suite (or a later
AI-generated patch) does can reach the network. `SandboxService` also
exposes `applyPatchAndRerun(sha, unifiedDiff)` for Iteration 7, using `git
apply` inside the same container before rerunning tests.

### Iteration 4 test checklist
- [ ] `reproduce(<sha-of-a-seeded-bug>)` fails, with output matching what
      you saw in the real GitHub Actions log for that commit
- [ ] `reproduce(<sha-of-the-baseline-commit>)` passes
- [ ] Confirm the temp directory is gone after the run (no leaked state
      between investigations)

---

## Iteration 5 — Groq client + triage stage

### Goal
Classify a failure as product bug / flaky test / infra issue, using real
Groq output, before building anything more elaborate on top of it.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── service/
        └── GroqService.java   ← NEW
```

`GroqService.triage(diff, logs, previousRuns)` sends one chat-completion
request to Groq (`llama-3.3-70b-versatile`) with a system prompt that
instructs it to return **only** JSON matching `TriageResult` — no
preamble, no markdown fences. Parse the response with Jackson; if parsing
fails, retry once with a stricter "return raw JSON only" reminder appended,
then fall back to `UNCERTAIN` rather than crashing the investigation.

### Iteration 5 test checklist
- [ ] Feed it the diff + logs from your flaky-test commit → verdict comes
      back `FLAKY_TEST`
- [ ] Feed it the diff + logs from your race-condition commit → verdict
      comes back `PRODUCT_BUG`
- [ ] Malformed JSON from the model doesn't crash the endpoint — confirm by
      temporarily truncating a prompt to provoke a bad response

---

## Iteration 6 — Root-cause stage

### Goal
Given a `PRODUCT_BUG` or `FLAKY_TEST` verdict, get a specific hypothesis:
which commit, which file, why.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── service/
        └── GroqService.java   ← EXTENDED (add rootCause method)
```

`GroqService.rootCause(diff, commitHistory, logs)` — same JSON-only
pattern as triage, this time targeting `RootCauseResult`. The prompt
includes the specific commit's diff *and* the last 5–10 commits' messages,
since "what changed recently" is most of what makes the hypothesis
specific instead of generic.

### Iteration 6 test checklist
- [ ] For the race-condition commit, the returned `suspectedCommitSha`
      matches the actual seeded commit
- [ ] `evidence` field references something concrete from the logs/diff,
      not a generic restatement of the verdict

---

## Iteration 7 — Patch generation + verification loop

### Goal
Close the loop: generate a patch, apply it in the sandbox, rerun tests,
retry within the cap, stop cleanly either way. This is the iteration where
"AI thinks this is the bug" becomes "AI proved this is the bug."

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── service/
        ├── GroqService.java          ← EXTENDED (add generatePatch method)
        └── InvestigationOrchestrator.java   ← NEW
```

`GroqService.generatePatch(rootCause, fileContents)` asks for a unified
diff plus a one-paragraph explanation, targeting `PatchResult`.

`InvestigationOrchestrator.run(sha)` is the first place all four stages are
wired together:

```text
triage → if PRODUCT_BUG or FLAKY_TEST:
    rootCause
    reproduce(sha) in sandbox           → must fail (confirms real repro)
    loop (up to fixpilot.sandbox.max-fix-retries):
        patch = generatePatch(...)
        applyPatchAndRerun(sha, patch.unifiedDiff())
        if reproduction passes AND related tests pass AND full suite passes:
            verified = true; break
    else:
        status = UNCERTAIN
```

The retry cap from Iteration 2's config is what stops a persistently wrong
patch from looping — after `max-fix-retries` failed attempts, the
investigation is marked `UNCERTAIN` and handed to a human, which is itself
the correct behavior per the design doc's "Quality Judge" idea. You don't
need a fifth agent to get that outcome; you need a loop with a floor.

### Iteration 7 test checklist
- [ ] Run the full pipeline against the race-condition commit end-to-end
      (call it manually — webhook wiring is Iteration 9) and get a
      `VERIFIED` result with a real patch
- [ ] Run it against a commit you know is genuinely unfixable by a 2-line
      patch (or lower the retry cap to 1 temporarily) and confirm it lands
      on `UNCERTAIN` instead of hanging or throwing

---

## Iteration 8 — Evidence report + PR posting

### Goal
Turn the `Investigation` object into the artifact judges actually read.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    ├── dto/
    │   └── EvidenceReport.java   ← NEW
    └── controller/
        └── InvestigationController.java   ← NEW
```

`EvidenceReport.render(investigation)` — pure function, no I/O, just reads
the `Investigation`'s accumulated `TriageResult` / `RootCauseResult` /
`PatchResult` / `VerificationResult` and produces the markdown report. This
is why the orchestrator writes every stage's result back onto a shared
object instead of just logging strings — the report is a projection of
real state, not a transcript you'd have to reconstruct.

Once verified, the orchestrator calls
`gitHubService.createPullRequest(...)` with a branch containing the
patch, then `commentOnPullRequest(...)` with the rendered report as the
body. `InvestigationController` exposes `GET /investigations/{id}` so you
can pull the same report up in a browser tab during the demo as a backup
to the live GitHub PR.

### Iteration 8 test checklist
- [ ] A real PR appears on the demo repo, with the patch as the diff and
      the evidence report as the description
- [ ] `GET /investigations/{id}` returns the same content as the PR body
- [ ] An `UNCERTAIN` investigation's report clearly says so, rather than
      silently omitting the verification section

---

## Iteration 9 — Webhook wiring end-to-end

### Goal
A push to the seeded repo triggers a real investigation with no manual
step in between — this is what makes the demo a live pipeline instead of a
console app you drive by hand.

### File tree after this iteration
```text
fixpilot/
└── src/main/java/com/fixpilot/
    └── controller/
        └── WebhookController.java   ← NEW
```

`WebhookController` accepts `POST /webhooks/github`, deserializes into
`WebhookPayload`, and — only when `action == "completed"` and
`workflow_run.conclusion == "failure"` — creates an `Investigation`,
saves it via `InvestigationRepository`, and kicks off
`InvestigationOrchestrator.run(...)` **asynchronously** (`@Async`, with a
dedicated `TaskExecutor` bean) so the webhook responds within GitHub's
timeout instead of blocking on the whole pipeline.

For local development, expose your Spring Boot app with a tunnel
(`ngrok http 8080`) and point the demo repo's webhook settings at the
tunnel URL — this avoids needing to actually deploy anywhere before demo
day.

### Iteration 9 test checklist
- [ ] Push one of the seeded-bug commits → webhook fires → investigation
      appears via `GET /investigations` within a few seconds, with status
      moving through the pipeline stages
- [ ] Push the baseline (passing) commit → no investigation is created
      (confirm the conclusion filter works both ways)

---

## Iteration 10 — Benchmark + demo polish

### Goal
Replace every invented number in the original pitch with a measured one,
then cut the demo down to the 4-minute path.

### What to build
A small script (bash or a `@Test`) that, for each of your 6–8 seeded
commits, calls the orchestrator, records time-to-`VERIFIED`-or-`UNCERTAIN`,
whether the triage verdict matched your ground truth from Iteration 1, and
whether verification succeeded. Write the actual results into the table —
don't touch the numbers from the original pitch until this script has run.

### Demo script (rehearse this, not the architecture)
1. Show the seeded repo, CI red on a fresh push.
2. Show the `Investigation` moving through statuses in real time (terminal
   log or the `GET /investigations/{id}` endpoint refreshed).
3. Land on the opened PR: root cause, patch, before/after test counts.
4. One slide: your real benchmark table.

### Iteration 10 test checklist
- [ ] Every number in your benchmark table came from an actual run logged
      this iteration
- [ ] The 4-minute demo has been run start-to-finish at least twice without
      manual intervention
- [ ] A fallback exists (a screen recording of a successful run) in case
      live GitHub/Groq calls are slow or rate-limited on demo day

---

## Final file tree (after Iteration 9)

```text
fixpilot/
├── pom.xml
└── src/main/
    ├── java/com/fixpilot/
    │   ├── FixPilotApplication.java
    │   ├── config/
    │   │   ├── FixPilotProperties.java
    │   │   └── AppConfig.java
    │   ├── controller/
    │   │   ├── WebhookController.java
    │   │   └── InvestigationController.java
    │   ├── dto/
    │   │   ├── WebhookPayload.java
    │   │   ├── TriageResult.java
    │   │   ├── RootCauseResult.java
    │   │   ├── PatchResult.java
    │   │   ├── VerificationResult.java
    │   │   └── EvidenceReport.java
    │   ├── model/
    │   │   ├── Investigation.java
    │   │   └── InvestigationStatus.java
    │   ├── repository/
    │   │   └── InvestigationRepository.java
    │   └── service/
    │       ├── GitHubService.java
    │       ├── GroqService.java
    │       ├── SandboxService.java
    │       └── InvestigationOrchestrator.java
    └── resources/
        └── application.yml

fixpilot-demo-app/            (separate repo — Iteration 1)
├── src/
├── test/
├── .github/workflows/ci.yml
└── package.json
```
