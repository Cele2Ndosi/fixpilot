$id = "<id-from-response>"
while ($true) {
    curl.exe -s "http://localhost:8080/investigations/$id" | ConvertFrom-Json | Select-Object status
    Start-Sleep -Seconds 2
}