param([switch]$StartServer)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($env:TWELVE_DATA_API_KEY)) {
    throw 'Set TWELVE_DATA_API_KEY in this PowerShell first. Do not paste the key into chat.'
}
Push-Location -LiteralPath $taskRoot
$taskPreviousOptIn = $env:FINSIGHT_LIVE_MARKET_TEST
try {
    Write-Host '1. Fetching actual AAPL daily closes from Twelve Data...'
    $taskHeaders = @{ Authorization = 'apikey ' + $env:TWELVE_DATA_API_KEY }
    try {
        $taskSource = Invoke-RestMethod -Uri 'https://api.twelvedata.com/time_series?symbol=AAPL&exchange=NASDAQ&interval=1day&outputsize=2&order=desc&adjust=none&prepost=false&dp=6' -Headers $taskHeaders -TimeoutSec 15
    } catch {
        $taskFailure = $_
        $taskStatus = 'unknown'
        if ($null -ne $taskFailure.Exception.Response) {
            try { $taskStatus = [int]$taskFailure.Exception.Response.StatusCode } catch { }
        }
        $taskDetail = $taskFailure.Exception.Message
        if ($taskFailure.ErrorDetails.Message) {
            try {
                $taskErrorBody = $taskFailure.ErrorDetails.Message | ConvertFrom-Json
                if ($taskErrorBody.message) { $taskDetail = [string]$taskErrorBody.message }
            } catch { }
        }
        $taskDetail = $taskDetail.Replace($env:TWELVE_DATA_API_KEY, '[REDACTED]')
        $taskDetail = $taskDetail -replace '(?i)(apikey|api_key|api-key|authorization)\s*[=:]\s*[^\s&"<>]+', '$1=[REDACTED]'
        throw ('Twelve Data request failed. HTTP ' + $taskStatus + '. ' + $taskDetail)
    }
    if ($taskSource.status -eq 'error') {
        throw ('Twelve Data returned error code ' + $taskSource.code + '. Check account permissions or rate limits.')
    }
    if (-not $taskSource.values -or $taskSource.values.Count -lt 2) {
        throw 'Provider did not return two daily closes.'
    }
    $taskSource.values | Select-Object datetime, close | Format-Table
    $taskSource.meta | Select-Object symbol, interval, exchange, mic_code, exchange_timezone | Format-List
    Write-Host '2. Running the real FinSight Java adapter and reference service (no mocks)...'
    $env:FINSIGHT_LIVE_MARKET_TEST = 'true'
    & .\gradlew.bat test --tests 'com.finsight.market.data.LiveMarketDataTests' --rerun-tasks
    if ($LASTEXITCODE -ne 0) {
        $taskReport = Join-Path $taskRoot 'build/test-results/test/TEST-com.finsight.market.data.LiveMarketDataTests.xml'
        if (Test-Path -LiteralPath $taskReport) {
            [xml]$taskXml = Get-Content -LiteralPath $taskReport -Raw
            foreach ($taskCase in $taskXml.testsuite.testcase) {
                if ($taskCase.failure) {
                    Write-Host ([string]$taskCase.failure.message).Replace($env:TWELVE_DATA_API_KEY, '[REDACTED]')
                }
            }
        }
        throw 'FinSight live-data verification failed. See the diagnostic message above.'
    }
    Write-Host '3. FinSight real reference result:'
    Get-Content -LiteralPath (Join-Path $taskRoot 'build/reports/market-data-live.json')
} finally {
    $env:FINSIGHT_LIVE_MARKET_TEST = $taskPreviousOptIn
    Pop-Location
}

if ($StartServer) {
    Push-Location -LiteralPath $taskRoot
    try {
        Write-Host 'Starting FinSight and registering the actual AAPL target.'
        Write-Host 'GET http://localhost:8080/api/v1/market-targets/77777777-7777-4777-8777-777777777777 (FinSight Bearer token required)'
        & .\gradlew.bat bootRun --args='--spring.profiles.active=market-data'
        if ($LASTEXITCODE -ne 0) { throw 'Server startup failed. Check DB environment and port 8080.' }
    } finally {
        Pop-Location
    }
}
