# Puts every HPG endpoint into Postman. Launched by import-postman.bat (double-click it).
#
# 1. Prepares the collection (all endpoints, bodies, auth, tests) and an "HPG local" environment that holds
#    the gateway URL and the operations client secret from deploy\env\.env.
# 2. With a Postman API key (asked for, or $env:POSTMAN_API_KEY) it creates or updates both directly in
#    your Postman account. Without one it opens Postman and the folder, so you can drag the two files in.
#
#   import-postman.bat          import into Postman
#   import-postman.bat run      also run the whole collection from the command line (newman, needs Node.js)
param([string]$Mode = '')

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $root 'postman\HPG.postman_collection.json'
$outDir = Join-Path $root 'postman\out'
$collectionName = 'HPG - Healthcare Payment Gateway'
$environmentName = 'HPG local'
$utf8 = New-Object System.Text.UTF8Encoding $false

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host ""; Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $source)) { Fail "Missing $source. Run this from the project folder." }

# ---------------------------------------------------------------- 1. Settings from deploy\env\.env
Step 'Reading settings'
$settings = @{}
$envFile = Join-Path $root 'deploy\env\.env'
if (Test-Path $envFile) {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim('"') }
    }
}
$opsSecret = $settings['HPG_OPS_CLIENT_SECRET']
if (-not $opsSecret) {
    Write-Host 'deploy\env\.env has no HPG_OPS_CLIENT_SECRET yet (start the stack once with start-hpg.bat).' -ForegroundColor Yellow
    Write-Host 'The environment is created without it; operations requests will get 401 until you set opsClientSecret.' -ForegroundColor Yellow
    $opsSecret = ''
}
$baseUrl = if ($env:HPG_BASE_URL) { $env:HPG_BASE_URL.TrimEnd('/') } else { 'http://localhost:8080' }
Write-Host "Gateway: $baseUrl"

# ---------------------------------------------------------------- 2. Files to import
Step 'Preparing the collection and environment'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
# File uploads (settlement CSV) point at the samples in this folder.
$repoPath = $root.Replace('\', '/')
$collectionJson = [IO.File]::ReadAllText($source, $utf8).Replace('__REPO__', $repoPath)
$collectionFile = Join-Path $outDir 'HPG.postman_collection.json'
[IO.File]::WriteAllText($collectionFile, $collectionJson, $utf8)

$values = @(
    @{ key = 'baseUrl'; value = $baseUrl; type = 'default'; enabled = $true },
    @{ key = 'opsClientId'; value = 'ops-console'; type = 'default'; enabled = $true },
    @{ key = 'opsClientSecret'; value = $opsSecret; type = 'secret'; enabled = $true },
    @{ key = 'webhookUrl'; value = 'http://hook-receiver:8080/hpg/webhooks'; type = 'default'; enabled = $true },
    @{ key = 'patientEmail'; value = 'asha.rao@example.com'; type = 'default'; enabled = $true },
    @{ key = 'patientPhone'; value = '+919800012345'; type = 'default'; enabled = $true }
)
$environment = [ordered]@{ name = $environmentName; values = $values }
$environmentJson = $environment | ConvertTo-Json -Depth 5
$environmentFile = Join-Path $outDir 'HPG-local.postman_environment.json'
[IO.File]::WriteAllText($environmentFile, $environmentJson, $utf8)

$count = ([regex]::Matches($collectionJson, '"request":\s*\{')).Count
Write-Host "Collection: $count requests  ->  $collectionFile"
Write-Host "Environment: $environmentName  ->  $environmentFile  (contains the ops secret; git-ignored)"

# ---------------------------------------------------------------- 3. Into Postman
$apiKey = $env:POSTMAN_API_KEY
if (-not $apiKey) {
    Write-Host ""
    Write-Host 'To put it straight into your Postman account, paste a Postman API key'
    Write-Host '(Postman > your avatar > Settings > API keys > Generate API Key), or press Enter to import by hand.'
    $secure = Read-Host 'Postman API key' -AsSecureString
    $apiKey = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

if ($apiKey) {
    Step 'Uploading to Postman'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $api = 'https://api.getpostman.com'
    $headers = @{ 'X-Api-Key' = $apiKey }
    $workspace = if ($env:POSTMAN_WORKSPACE_ID) { "?workspace=$($env:POSTMAN_WORKSPACE_ID)" } else { '' }

    function Send($method, $path, $json) {
        $bytes = $utf8.GetBytes($json)
        Invoke-RestMethod -Method $method -Uri "$api$path" -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
    }
    try {
        $existing = (Invoke-RestMethod -Uri "$api/collections$workspace" -Headers $headers).collections |
            Where-Object { $_.name -eq $collectionName } | Select-Object -First 1
        $body = '{"collection":' + $collectionJson + '}'
        if ($existing) {
            Send 'Put' "/collections/$($existing.uid)" $body | Out-Null
            Write-Host "Updated collection '$collectionName'." -ForegroundColor Green
        } else {
            Send 'Post' "/collections$workspace" $body | Out-Null
            Write-Host "Created collection '$collectionName'." -ForegroundColor Green
        }

        $existingEnv = (Invoke-RestMethod -Uri "$api/environments$workspace" -Headers $headers).environments |
            Where-Object { $_.name -eq $environmentName } | Select-Object -First 1
        $envBody = '{"environment":' + $environmentJson + '}'
        if ($existingEnv) {
            Send 'Put' "/environments/$($existingEnv.uid)" $envBody | Out-Null
            Write-Host "Updated environment '$environmentName'." -ForegroundColor Green
        } else {
            Send 'Post' "/environments$workspace" $envBody | Out-Null
            Write-Host "Created environment '$environmentName'." -ForegroundColor Green
        }
        Write-Host ""
        Write-Host "Done. In Postman pick the '$environmentName' environment (top right), open '$collectionName'"
        Write-Host 'and send requests in order, or click Run to run them all.'
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -eq 401) { Write-Host 'Postman refused the API key (401). Check it and run this again.' -ForegroundColor Red }
        else { Write-Host "Upload failed: $($_.Exception.Message)" -ForegroundColor Red }
        Write-Host 'You can still import the two files by hand (see below).'
        $apiKey = $null
    }
}

if (-not $apiKey) {
    Step 'Import by hand'
    Write-Host 'In Postman: File > Import (or Ctrl+O), then drag in both files from the folder that opens:'
    Write-Host "  $collectionFile"
    Write-Host "  $environmentFile"
    Write-Host "Then pick the '$environmentName' environment (top right) and send requests in order, or click Run."
    $postman = Join-Path $env:LOCALAPPDATA 'Postman\Postman.exe'
    if (Test-Path $postman) { Start-Process $postman }
    Start-Process explorer.exe "/select,`"$collectionFile`""
}

# ---------------------------------------------------------------- 4. Optional: run everything now
if ($Mode -eq 'run') {
    Step 'Running the whole collection with newman'
    if (-not (Get-Command npx -ErrorAction SilentlyContinue)) { Fail 'Node.js is needed for newman: https://nodejs.org' }
    & npx --yes newman@6 run $collectionFile -e $environmentFile --delay-request 200 --reporters cli
    if ($LASTEXITCODE -ne 0) { Fail 'Some requests failed; see the table above.' }
    Write-Host 'All requests passed.' -ForegroundColor Green
}
