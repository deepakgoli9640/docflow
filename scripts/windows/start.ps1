# Starts the whole HPG stack on Windows. Launched by start-hpg.bat (double-click it).
# Needs only Docker Desktop. JDK 21 is used for the build if it is installed; otherwise the build
# runs inside a Maven container, so nothing else has to be installed.

$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root

$compose = @('compose', '-f', 'deploy/docker-compose.yml',
    '--profile', 'infra', '--profile', 'core', '--profile', 'refund', '--profile', 'transactions',
    '--profile', 'notify', '--profile', 'recon', '--profile', 'dashboard')

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host ""; Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- 1. Docker
Step 'Checking Docker'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail 'Docker is not installed. Install Docker Desktop from https://www.docker.com/products/docker-desktop/ and run this again.'
}
cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) {
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (Test-Path $desktop) {
        Write-Host 'Docker Desktop is not running - starting it (this can take a minute)...'
        Start-Process $desktop
    } else {
        Write-Host 'Docker is not running. Please start Docker Desktop.'
    }
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 5
        cmd /c 'docker info >nul 2>&1'
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
    if ($LASTEXITCODE -ne 0) { Fail 'Docker did not become ready. Start Docker Desktop, wait until it says "running", then try again.' }
}
cmd /c 'docker compose version >nul 2>&1'
if ($LASTEXITCODE -ne 0) { Fail 'Docker Compose v2 is missing. Update Docker Desktop.' }
Write-Host 'Docker is running.'

# ---------------------------------------------------------------- 2. Secrets file
$envFile = Join-Path $root 'deploy\env\.env'
if (Test-Path $envFile) {
    Step 'Using existing deploy\env\.env'
} else {
    Step 'Creating deploy\env\.env with random secrets'
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    function RandBytes($n) { $b = New-Object byte[] $n; $rng.GetBytes($b); return ,$b }
    function RandHex { -join ((RandBytes 24) | ForEach-Object { $_.ToString('x2') }) }

    $hexKeys = 'POSTGRES_PASSWORD', 'MERCHANT_DB_PASSWORD', 'PAYMENT_DB_PASSWORD', 'REFUND_DB_PASSWORD',
               'TRANSACTION_DB_PASSWORD', 'NOTIFICATION_DB_PASSWORD', 'RECON_DB_PASSWORD',
               'HPG_OPS_CLIENT_SECRET', 'HPG_NOTIFICATION_CLIENT_SECRET',
               'GF_SECURITY_ADMIN_PASSWORD', 'KC_BOOTSTRAP_ADMIN_PASSWORD'
    $lines = Get-Content (Join-Path $root 'deploy\env\.env.example') | ForEach-Object {
        $line = $_
        foreach ($k in $hexKeys) { if ($line -match "^$k=") { $line = "$k=$(RandHex)" } }
        if ($line -match '^HPG_ENCRYPTION_KEY=') { $line = 'HPG_ENCRYPTION_KEY=' + [Convert]::ToBase64String((RandBytes 32)) }
        $line
    }
    # LF line endings, no BOM: the file is read by Docker Compose and by the bash scripts.
    [IO.File]::WriteAllText($envFile, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
    Write-Host 'Done (payments use the SIMULATED provider - no real money moves).'
}

# ---------------------------------------------------------------- 3. Build the jars
Step 'Building the services (the first build downloads dependencies and takes several minutes)'
$javaMajor = 0
if (Get-Command java -ErrorAction SilentlyContinue) {
    $v = cmd /c 'java -version 2>&1' | Select-Object -First 1
    if ($v -match '"(\d+)') { $javaMajor = [int]$Matches[1] }
}
if ($javaMajor -ge 21) {
    Write-Host "Using the installed JDK $javaMajor."
    & cmd /c 'mvnw.cmd -B -ntp -DskipTests package'
} else {
    Write-Host 'JDK 21 not found - building inside a Maven container instead.'
    Write-Host 'First run: downloads the Maven image and all dependencies (often 10-20 minutes). Progress follows.'
    & docker run --rm -v "${root}:/src" -v 'hpg-maven-cache:/root/.m2' -w /src `
        maven:3.9.9-eclipse-temurin-21 mvn -B -ntp -DskipTests package
}
if ($LASTEXITCODE -ne 0) { Fail 'The build failed. See the messages above.' }
Write-Host 'Build finished.'

# ---------------------------------------------------------------- 4. Start the stack
Step 'Starting all containers and waiting until they are healthy (a few minutes)'
& docker @compose up -d --build --wait
if ($LASTEXITCODE -ne 0) {
    & docker @compose ps
    Fail 'Some containers did not become healthy. Look at the list above, then: docker compose -f deploy/docker-compose.yml logs <name>'
}

# ---------------------------------------------------------------- 5. Done
$opsSecret = (Get-Content $envFile | Where-Object { $_ -match '^HPG_OPS_CLIENT_SECRET=' }) -replace '^HPG_OPS_CLIENT_SECRET=', ''
Write-Host ""
Write-Host 'The project is running.' -ForegroundColor Green
Write-Host ''
Write-Host '  Dashboard        http://localhost:8090'
Write-Host '  API gateway      http://localhost:8080'
Write-Host '  Swagger (pay)    http://localhost:8082/swagger-ui.html'
Write-Host '  Emails (Mailpit) http://localhost:8025'
Write-Host ''
Write-Host 'Sign in to the dashboard as Operations with:'
Write-Host '  Client ID:     ops-console'
Write-Host "  Client secret: $opsSecret"
Write-Host ''
Write-Host 'To stop everything, double-click stop-hpg.bat.'
Start-Process 'http://localhost:8090'
