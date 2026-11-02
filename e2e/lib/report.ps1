# e2e/lib/report.ps1 — PASS/FAIL accumulation and e2e/out/report.json emission.

$script:E2eResults = [System.Collections.Generic.List[object]]::new()
$script:E2eStartedAt = (Get-Date).ToString('o')

# Which control channel a row was measured through. bridge.ps1 increments $script:ChannelFallbacks
# whenever a command degrades to the SharedPreferences path; a prefs-fallback run must never be read as
# a broadcast-channel run, so the stamp lives on EVERY row and cannot be absorbed by a PASS.
function Get-ChannelStamp {
    $fb = 0
    $v = Get-Variable -Name ChannelFallbacks -Scope Script -ErrorAction SilentlyContinue
    if ($v -and $null -ne $v.Value) { $fb = [int]$v.Value }
    if ($fb -gt 0) { return "prefs-fallback($fb)" }
    return 'broadcast'
}

function Add-Result {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Name,
        [ValidateSet('PASS', 'FAIL', 'SKIP', 'WARN')][string]$Status,
        [string]$Detail = '',
        [hashtable]$Metrics = @{}
    )
    $entry = [pscustomobject]@{
        id      = $Id
        name    = $Name
        status  = $Status
        detail  = $Detail
        metrics = $Metrics
        channel = (Get-ChannelStamp)
        ts      = (Get-Date).ToString('o')
    }
    $script:E2eResults.Add($entry)
    switch ($Status) {
        'PASS' { Write-Host ("  PASS  {0,-42} {1}" -f $Id, $Detail) -ForegroundColor Green }
        'FAIL' { Write-Host ("  FAIL  {0,-42} {1}" -f $Id, $Detail) -ForegroundColor Red }
        'SKIP' { Write-Host ("  SKIP  {0,-42} {1}" -f $Id, $Detail) -ForegroundColor DarkGray }
        'WARN' { Write-Host ("  WARN  {0,-42} {1}" -f $Id, $Detail) -ForegroundColor Yellow }
    }
    return $entry
}

function Test-Result {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Condition,
        [string]$Detail = '',
        [string]$FailDetail = '',
        [hashtable]$Metrics = @{}
    )
    if ($Condition) { return Add-Result -Id $Id -Name $Name -Status 'PASS' -Detail $Detail -Metrics $Metrics }
    $msg = if ($FailDetail) { $FailDetail } else { $Detail }
    return Add-Result -Id $Id -Name $Name -Status 'FAIL' -Detail $msg -Metrics $Metrics
}

function Get-ResultSummary {
    $pass = @($script:E2eResults | Where-Object { $_.status -eq 'PASS' }).Count
    $fail = @($script:E2eResults | Where-Object { $_.status -eq 'FAIL' }).Count
    $skip = @($script:E2eResults | Where-Object { $_.status -eq 'SKIP' }).Count
    $warn = @($script:E2eResults | Where-Object { $_.status -eq 'WARN' }).Count
    return [pscustomobject]@{ pass = $pass; fail = $fail; skip = $skip; warn = $warn; total = $script:E2eResults.Count }
}

function Write-E2eTable {
    Write-Host ''
    Write-Host '============================================' -ForegroundColor Magenta
    Write-Host ' ImmoralLocation acceptance matrix' -ForegroundColor Magenta
    Write-Host '============================================' -ForegroundColor Magenta
    $script:E2eResults | ForEach-Object {
        $color = switch ($_.status) {
            'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'DarkGray' } default { 'Yellow' }
        }
        Write-Host (" {0,-5} | {1,-40} | {2}" -f $_.status, $_.id, $_.detail) -ForegroundColor $color
    }
    $s = Get-ResultSummary
    Write-Host '--------------------------------------------' -ForegroundColor Magenta
    Write-Host (" {0} passed, {1} failed, {2} skipped, {3} warned" -f $s.pass, $s.fail, $s.skip, $s.warn)
}

function Save-E2eReport {
    param([string]$Path = '', [string]$Suite = 'matrix', [hashtable]$Environment = @{})
    if (-not $Path) { $Path = Join-Path $OutDir 'report.json' }
    $s = Get-ResultSummary
    $report = [ordered]@{
        suite       = $Suite
        startedAt   = $script:E2eStartedAt
        finishedAt  = (Get-Date).ToString('o')
        environment = $Environment
        summary     = [ordered]@{ pass = $s.pass; fail = $s.fail; skip = $s.skip; warn = $s.warn; total = $s.total }
        results     = @($script:E2eResults)
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -Path $Path -Encoding utf8
    Write-Host ''
    Write-Host "report: $Path" -ForegroundColor Cyan
    return $s
}
