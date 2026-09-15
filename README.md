# FixPilot

An AI agent that investigates a failing CI pipeline, decides whether it's a
real product bug, a flaky test, or an infrastructure issue, reproduces the
failure in an isolated sandbox, generates the smallest fix it can, verifies
that fix by rerunning the test suite, and opens a PR with the evidence -
root cause, patch, before/after test results - instead of just claiming
"this should work."

See `fixpilot-build-guide.md` for the iteration-by-iteration build plan this
code follows.

## Architecture

```text
GitHub Actions (your seeded demo repo)
        |  workflow_run: completed, conclusion: failure
        v
WebhookController -----------------------> InvestigationRepository (in-memory)
        |
        v
InvestigationOrchestrator
        |
        +--> GitHubService   (diff, commit history, logs, previous runs, PR/branch/commit)
        +--> SandboxService  (isolated Docker checkout: reproduce, apply patch, rerun tests)
        +--> GroqService     (triage, root cause, patch generation)
        |
        v
Investigation (accumulates Triage -> RootCause -> Patch -> Verification)
        |
        v
EvidenceReport.render() ------------------> PR body / GET /investigations/{id}/report
```

**Golden rule:** only `InvestigationOrchestrator` calls `GitHubService`,
`GroqService`, and `SandboxService`. Controllers only ever touch the
orchestrator and the repository.

## Prerequisites

- Java 17+ and Maven
- Docker (used by `SandboxService` to run the target repo's test suite in
  isolation - `--rm --network none`, fresh container per run)
- A GitHub personal access token with `repo` + `workflow` scopes, for a demo
  repository you control
- A [Groq](https://console.groq.com) API key

## Configuration

Copy `.env.example` to `.env` (or export these directly) before running:

```bash
export GITHUB_TOKEN=ghp_xxx
export GITHUB_OWNER=your-org-or-username
export GITHUB_REPO=fixpilot-demo-app
export GROQ_API_KEY=gsk_xxx
# optional, defaults shown:
export GROQ_MODEL=llama-3.3-70b-versatile
export SANDBOX_WORKDIR=/tmp/fixpilot-sandboxes
export SANDBOX_IMAGE=node:20-bullseye
```

`SANDBOX_IMAGE` and the `npm ci && npm test` command baked into
`SandboxService.runTestsInContainer` assume a Node test suite - point it at
whatever your seeded demo app actually uses, and adjust that command if it
isn't `npm test`.

## Running it

```bash
mvn spring-boot:run
```

The app starts on `:8080`. Two endpoint groups:

- `POST /webhooks/github` - point your demo repo's webhook here (Settings ->
  Webhooks -> Add webhook, content type `application/json`, event: "Workflow
  runs"). For local development, expose it with `ngrok http 8080` and use the
  tunnel URL.
- `GET /investigations` / `GET /investigations/{id}` / `GET
  /investigations/{id}/report` - check status and pull up the evidence
  report without leaving your browser, as a demo-day backup to the live PR.

## Known scope cuts (deliberate, not accidental)

- **In-memory storage.** `InvestigationRepository` is a `ConcurrentHashMap`.
  Fine for a single demo run; swap it for a JPA repository if you need
  restarts to survive.
- **Test-count parsing assumes Jest or Mocha output** (see
  `SandboxService.parseTestCounts`). The pass/fail boolean is always correct
  regardless (it's the container's exit code); only the "47/47" style counts
  in the evidence report depend on this parser matching your test runner.
- **One AI provider, no fallback.** If Groq is rate-limited mid-demo, have a
  screen recording of a prior successful run ready (see the build guide's
  final iteration).
