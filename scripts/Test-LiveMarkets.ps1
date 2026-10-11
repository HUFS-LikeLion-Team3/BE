param(
    [ValidateSet('KOSPI', 'US10Y', 'USDKRW', 'GOLD', 'WTI')]
    [string[]]$Targets = @('KOSPI', 'US10Y', 'USDKRW', 'GOLD', 'WTI'),
    [switch]$StartServer,
    [switch]$InspectProvider
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (($Targets | Where-Object { $_ -ne 'US10Y' }) -and [string]::IsNullOrWhiteSpace($env:TWELVE_DATA_API_KEY)) {
    throw 'Set TWELVE_DATA_API_KEY in this PowerShell. Do not send the key in chat.'
}
$taskPreviousOptIn = $env:FINSIGHT_LIVE_MARKETS_TEST
$taskPreviousTargets = $env:FINSIGHT_LIVE_TARGETS
Push-Location -LiteralPath $taskRoot
try {
    if ($InspectProvider) {
        $taskHeaders = @{ Authorization = 'apikey ' + $env:TWELVE_DATA_API_KEY }
        foreach ($taskCode in $Targets) {
            if ($taskCode -eq 'US10Y') { continue }
            $taskSymbol = switch ($taskCode) {
                'KOSPI' { 'KS11' }
                'USDKRW' { 'USD/KRW' }
                'GOLD' { 'XAU/USD' }
                'WTI' { 'WTI/USD' }
            }
            Write-Host ('Provider inspection: ' + $taskCode)
            if ($taskCode -eq 'KOSPI') {
                Write-Host 'Searching provider reference data for KOSPI (catalog listing does not guarantee price access).'
                try {
                    $taskSearch = Invoke-RestMethod -Uri 'https://api.twelvedata.com/symbol_search?symbol=KOSPI&outputsize=10' -Headers $taskHeaders -TimeoutSec 15
                    if ($taskSearch.data) {
                        $taskSearch.data | Select-Object symbol, instrument_name, instrument_type, exchange, mic_code, country | Format-Table -AutoSize
                    } else { Write-Host 'No KOSPI candidates returned by symbol_search.' }
                } catch { Write-Host 'KOSPI symbol_search failed; no raw response or API key logged.' }
                continue
            }
            $taskUri = 'https://api.twelvedata.com/time_series?symbol=' + [Uri]::EscapeDataString($taskSymbol) + '&interval=1day&outputsize=2'
            $taskRequests = @([pscustomobject]@{ Label = 'Latest bars without date bounds'; Uri = $taskUri })
            if ($taskCode -in @('GOLD', 'USDKRW')) {
                $taskSydneyDate = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'AUS Eastern Standard Time').Date
                $taskBaseUri = 'https://api.twelvedata.com/time_series?symbol=' + [Uri]::EscapeDataString($taskSymbol) + '&interval=1day&outputsize=15&order=desc&dp=6'
                $taskRequests = @(
                    [pscustomobject]@{ Label = 'Latest 15 bars without date bounds'; Uri = $taskBaseUri },
                    [pscustomobject]@{ Label = 'Same date bounds as Java'; Uri = $taskBaseUri + '&start_date=' + $taskSydneyDate.AddDays(-14).ToString('yyyy-MM-dd') + '&end_date=' + $taskSydneyDate.AddDays(1).ToString('yyyy-MM-dd') }
                )
            }
            foreach ($taskRequest in $taskRequests) {
            Write-Host $taskRequest.Label
            try {
                $taskBody = Invoke-RestMethod -Uri $taskRequest.Uri -Headers $taskHeaders -TimeoutSec 15
                if ($taskBody.status -eq 'error') {
                    $taskMessage = [string]$taskBody.message
                    if ($env:TWELVE_DATA_API_KEY) { $taskMessage = $taskMessage.Replace($env:TWELVE_DATA_API_KEY, '[REDACTED]') }
                    Write-Host ('Provider error ' + $taskBody.code + ': ' + $taskMessage)
                } else {
                    $taskBody.meta | Select-Object symbol, interval, type, exchange, mic_code, exchange_timezone, currency_base, currency_quote | Format-List
                    $taskBody.values | Select-Object datetime, close | Format-Table
                }
            } catch {
                $taskMessage = $_.Exception.Message
                if ($_.ErrorDetails.Message) {
                    try { $taskErrorBody = $_.ErrorDetails.Message | ConvertFrom-Json; if ($taskErrorBody.message) { $taskMessage = [string]$taskErrorBody.message } } catch { }
                }
                if ($env:TWELVE_DATA_API_KEY) { $taskMessage = $taskMessage.Replace($env:TWELVE_DATA_API_KEY, '[REDACTED]') }
                Write-Host $taskMessage
            }
            }
        }
        return
    }
    $env:FINSIGHT_LIVE_MARKETS_TEST = 'true'
    $env:FINSIGHT_LIVE_TARGETS = $Targets -join ','
    Write-Host ('Verifying actual providers for: ' + $env:FINSIGHT_LIVE_TARGETS)
    $taskStartedAt = [DateTime]::UtcNow
    & .\gradlew.bat test --tests 'com.finsight.market.data.LiveMarketsTests' --rerun-tasks
    $taskExitCode = $LASTEXITCODE
    $taskXmlPath = Join-Path $taskRoot 'build/test-results/test/TEST-com.finsight.market.data.LiveMarketsTests.xml'
    if ((Test-Path -LiteralPath $taskXmlPath) -and (Get-Item -LiteralPath $taskXmlPath).LastWriteTimeUtc -ge $taskStartedAt) {
        [xml]$taskXml = Get-Content -LiteralPath $taskXmlPath -Raw
        foreach ($taskCase in $taskXml.testsuite.testcase) {
            if ($taskCase.failure) { Write-Host ([string]$taskCase.failure.message) }
        }
        $taskReport = Join-Path $taskRoot 'build/reports/markets-live.json'
        if (Test-Path -LiteralPath $taskReport) { Get-Content -LiteralPath $taskReport }
    }
    if ($taskExitCode -ne 0) { throw 'Some live markets failed. See the per-market results above.' }
    if ($StartServer) {
        Write-Host 'Starting FinSight with actual market metadata (Bearer login token required for API calls).'
        & .\gradlew.bat bootRun --args='--spring.profiles.active=market-data'
        if ($LASTEXITCODE -ne 0) { throw 'Server startup failed. Check DB configuration and port 8080.' }
    }
} finally {
    $env:FINSIGHT_LIVE_MARKETS_TEST = $taskPreviousOptIn
    $env:FINSIGHT_LIVE_TARGETS = $taskPreviousTargets
    Pop-Location
}
