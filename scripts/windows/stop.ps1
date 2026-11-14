# Stops every HPG container. Launched by stop-hpg.bat. Data is kept (the database volume is not removed).
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
& docker compose -f deploy/docker-compose.yml --profile '*' down
if ($LASTEXITCODE -ne 0) { Write-Host 'Could not stop the containers. Is Docker Desktop running?' -ForegroundColor Red; exit 1 }
Write-Host 'All containers stopped.' -ForegroundColor Green
