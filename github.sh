$body = @{
    action = "completed"
    workflow_run = @{
        id = "36492619435"
        conclusion = "failure"
        head_sha = "2533b36"
        head_branch = "main"
    }
    repository = @{
        name = "Fixpilot-demo-app"
        owner = @{ login = "Cele2Ndosi" }
    }
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Uri "http://localhost:8080/webhooks/github" -Method Post -Body $body -ContentType "application/json"
$response