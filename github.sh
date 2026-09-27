# From the red run's GitHub Actions page: the run ID is in the URL,
# the commit sha is shown on the same page.
$body = @{
    action = "completed"
    workflow_run = @{
        id = "<real-run-id>"
        conclusion = "failure"
        head_sha = "<seeded-bug-sha>"
        head_branch = "main"
    }
    repository = @{
        name = $env:GITHUB_REPO
        owner = @{ login = $env:GITHUB_OWNER }
    }
} | ConvertTo-Json -Depth 5

$response = curl.exe -X POST http://localhost:8080/webhooks/github -H "Content-Type: application/json" -d $body | ConvertFrom-Json