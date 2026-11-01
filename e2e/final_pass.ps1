<#
.SYNOPSIS
    One-command acceptance pass: build from the tree, install, activate the LSPosed module, run the
    matrix, capture the screenshot set, and print every artifact with its hash.

.DESCRIPTION
    This is the sequence the acceptance report is produced from; it exists so the run is reproducible
    by anyone (and so the "single self-consistent run on one build" requirement is auditable):

      1. build `:app:assembleDebug` + `:verifier:assembleDebug` from the current tree and record the
         APK SHA256 (the report binds its numbers to this hash and to `gitHead`);
      2. `install.ps1 -SkipBuild` → install → `activate_module.ps1` → reboot → verify the framework
         hook is live (a stale `modules.apk_path` silently disables the module);
      3. pre-run hygiene (`0-config-hygiene`) — aborts if a leftover 步频/WIFI/基站/GPS/独立模拟 toggle
         is ON, because every following number would describe a mutated configuration;
      4. the matrix (engine `1–11` + UI `12–22` + release `23/24`), or a fast UI-only subset;
      5. the review screenshot set (`capture_screens.ps1`);
      6. optionally the third-party window (`16` 高德 visual evidence + `17` 百度/云运动).

.PARAMETER Scope
    `ui` (default) = hygiene + 12..22; `full` = every check including the engine half and the 3-minute
    高德 window; `engine` = 1..11 only.

.PARAMETER SkipBuild
    Reuse the APKs already in `app/build/outputs` / `verifier/build/outputs` (still installs them).

.PARAMETER NoCapture
    Skip the screenshot set.

.PARAMETER AmapSeconds
    Length of the 高德 window when Scope is `full` (acceptance: 180).

.EXAMPLE
    .\e2e\final_pass.ps1 -Scope full
#>
[CmdletBinding()]
param(
    [ValidateSet('ui', 'full', 'engine')][string]$Scope = 'ui',
    [switch]$SkipBuild,
    [switch]$NoCapture,
    [int]$AmapSeconds = 180
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')
# the probe and the matrix talk to the engine over the bridge; without this the liveness step dies
# with "Send-SetConfig is not recognized" and reports "module NOT active" while it is fine.
. (Join-Path $PSScriptRoot 'lib\bridge.ps1')
. (Join-Path $PSScriptRoot 'lib\verifier.ps1')

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $OutDir "final-$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$artifacts = [ordered]@{}

function Write-Phase([string]$Text) {
    Write-Host ''
    Write-Host "=== $Text" -ForegroundColor Magenta
}

# ------------------------------------------------------------------------------------------------
Write-Phase '1) build from the current tree'
$gitHead = ((& git rev-parse --short HEAD 2>$null) -join '').Trim()
$gitDirty = @((& git status --porcelain 2>$null)).Count
Write-Step "gitHead=$gitHead dirtyFiles=$gitDirty"

if (-not $SkipBuild) {
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    & $gradlew ':app:assembleDebug' ':verifier:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
}
$appApk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $appApk)) { throw "missing $appApk" }
$apkHash = (Get-FileHash $appApk -Algorithm SHA256).Hash
$apkMb = [Math]::Round((Get-Item $appApk).Length / 1MB, 2)
Write-Ok "app-debug.apk $apkMb MB sha256=$apkHash"
$artifacts['apk'] = $appApk
$artifacts['apkSha256'] = $apkHash

# The APK must be newer than every source file, otherwise the "numbers describe this tree" claim is
# false. This caught a stale install during the checkpoint round.
$newer = @(Get-ChildItem (Join-Path $RepoRoot 'app\src'), (Join-Path $RepoRoot 'core\src'), (Join-Path $RepoRoot 'hook\src') `
        -Recurse -File -Include *.kt, *.xml -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -gt (Get-Item $appApk).LastWriteTime })
if ($newer.Count -gt 0) {
    Write-Warn2 "$($newer.Count) source file(s) are NEWER than the APK — rebuild before trusting the results:"
    $newer | Select-Object -First 5 | ForEach-Object { Write-Host "    $($_.LastWriteTime.ToString('HH:mm:ss')) $($_.Name)" }
}

# ------------------------------------------------------------------------------------------------
Write-Phase '2) install + activate + reboot'
& (Join-Path $PSScriptRoot 'install.ps1') -SkipBuild 2>&1 |
    Tee-Object -FilePath (Join-Path $logDir 'install.log') | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { throw 'install/activate failed' }

# ------------------------------------------------------------------------------------------------
Write-Phase '3) hook liveness probe (lspd log + functional injection)'
# `logcat -d | grep -c ImmoralLocation` is NOT a liveness signal: the buffer rolls, and a scoped app
# that has not run yet produces no lines. The lspd log says whether the module was loaded into a
# process at all; the `immoral.*` bundle in the framework fix says whether it is actually injecting.
$lspdText = ''
try {
    $r = Invoke-AdbRoot 'grep -i immoral /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -20' -AllowFailure
    $lspdText = (($r.Output -join "`n")).Trim()
}
catch { Write-Warn2 "lspd log probe failed: $_" }
$lspdOk = ($lspdText -match 'EngineRuntime booted|hook targets resolved|captured GNSS status listener|captured location')
if ($lspdOk) {
    Write-Ok 'lspd log shows the module loaded:'
    $lspdText -split "`n" | Select-Object -Last 3 | ForEach-Object { Write-Host "    $_" }
}
else { Write-Warn2 'no [ImmoralLocation] activity in /data/adb/lspd/log/modules_*.log — the module may not be loaded' }

$probeOk = $false
try {
    Send-SetConfig -Config @{ mode = 'STATIC'; speed = 'WALK'; updateIntervalMs = 1000; followHeading = $false } 2>$null | Out-Null
    Send-Start 2>$null | Out-Null
    Send-SetPosition -Lat 31.230416 -Lon 121.473701 2>$null | Out-Null
    Start-Sleep -Seconds 3
    $dumpLoc = Invoke-AdbShell 'dumpsys location' -AllowFailure
    $probeOk = [bool](($dumpLoc.Output -join "`n") -match 'immoral\.')
}
catch { Write-Warn2 "functional probe failed: $_" }
if ($probeOk) { Write-Ok 'hook is injecting (immoral.* bundle present in the framework fix)' }
else { Write-Warn2 'no immoral.* bundle in dumpsys location' }

# Hard gate. A matrix run on a device whose module is not injecting produces a report full of
# "0 m / 0 samples" rows that look like product failures and waste a whole device window. Abort.
if (-not $probeOk) {
    Write-Err2 'ABORT: the module is not injecting; a matrix run now would produce meaningless numbers.'
    Write-Err2 'Fix the activation (activate_module.ps1 + reboot, module apk_path must match the installed APK) and re-run.'
    $summaryAbort = [ordered]@{
        aborted       = 'hook not injecting (no immoral.* bundle in dumpsys location)'
        at            = (Get-Date).ToString('o')
        gitHead       = $gitHead
        apkSha256     = $apkHash
        lspdLoaded    = $lspdOk
        installLog    = (Join-Path $logDir 'install.log')
    }
    $summaryAbort | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $logDir 'final-pass.json') -Encoding utf8
    exit 2
}

# ------------------------------------------------------------------------------------------------
Write-Phase '4) acceptance matrix'
$only = switch ($Scope) {
    'ui' { '0,12,13,14,15,18,20,21,22,25' }
    'engine' { '0,1,2,3,4,5,6,7,8,9,10,11' }
    'full' { '' }
}
$matrixArgs = @{ KeepVerifier = $false; AmapSeconds = $AmapSeconds }
if ($only) { $matrixArgs['Only'] = $only }
if ($Scope -eq 'engine') { $matrixArgs['SkipUi'] = $true }
& (Join-Path $PSScriptRoot 'run_matrix.ps1') @matrixArgs 2>&1 |
    Tee-Object -FilePath (Join-Path $logDir 'matrix.log') | Select-Object -Last 40
$matrixExit = $LASTEXITCODE
$artifacts['matrixLog'] = (Join-Path $logDir 'matrix.log')
$artifacts['matrixReport'] = if ($only) { Join-Path $OutDir ("report-" + ($only -replace '[^0-9a-zA-Z]+', '-') + ".json") } else { Join-Path $OutDir 'report.json' }

# ------------------------------------------------------------------------------------------------
if (-not $NoCapture) {
    Write-Phase '5) review screenshot set'
    & (Join-Path $PSScriptRoot 'capture_screens.ps1') 2>&1 |
        Tee-Object -FilePath (Join-Path $logDir 'screens.log') | Select-Object -Last 16
    $artifacts['screensLog'] = (Join-Path $logDir 'screens.log')
    $artifacts['screenshots'] = (Join-Path $RepoRoot 'docs\screenshots')
}

# ------------------------------------------------------------------------------------------------
if ($Scope -eq 'full') {
    Write-Phase '6) third-party window (高德 visual evidence + 百度/云运动)'
    & (Join-Path $PSScriptRoot 'run_matrix.ps1') -Only '16,17' -AmapSeconds $AmapSeconds 2>&1 |
        Tee-Object -FilePath (Join-Path $logDir 'thirdparty.log') | Select-Object -Last 20
    $artifacts['thirdPartyLog'] = (Join-Path $logDir 'thirdparty.log')
    $artifacts['amapEvidence'] = (Join-Path $RepoRoot 'docs\screenshots\amap-verify')
}

# ------------------------------------------------------------------------------------------------
Write-Phase 'summary'
# Stamp the APK that is ACTUALLY installed, not just the one on disk: another teammate's build can
# replace app/build/outputs/... mid-run, and a report that binds numbers to a hash nobody installed is
# worse than no stamp at all.
$installedPath = ''
$installedHash = ''
try {
    $installedPath = (Get-PackageApkPath (Get-ModulePackageName)).Trim()
    if ($installedPath) {
        $r = Invoke-AdbRoot "sha256sum '$installedPath'" -AllowFailure
        if (($r.Output -join ' ') -match '([0-9a-fA-F]{64})') { $installedHash = $matches[1].ToLowerInvariant() }
    }
}
catch { Write-Warn2 "could not hash the installed APK: $_" }
$apkMatches = ($installedHash -and ($installedHash -eq $apkHash.ToLowerInvariant()))
if ($installedHash) {
    if ($apkMatches) { Write-Ok "installed APK matches the built APK (sha256 $installedHash)" }
    else {
        Write-Warn2 "installed APK ($installedHash) DIFFERS from the built APK ($apkHash)"
        Write-Warn2 'another build replaced the artifact; treat the numbers below as bound to the installed hash'
    }
}

$summary = [ordered]@{
    startedWith   = $stamp
    scope         = $Scope
    gitHead       = $gitHead
    gitDirtyFiles = $gitDirty
    apkSha256     = $apkHash
    installedApkPath = $installedPath
    installedApkSha256 = $installedHash
    apkMatchesBuilt = $apkMatches
    apkMb         = $apkMb
    lspdLoaded    = $lspdOk
    hookProbe     = $probeOk
    matrixExit    = $matrixExit
    artifacts     = $artifacts
}
$summaryPath = Join-Path $logDir 'final-pass.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding utf8
Write-Ok "final pass summary: $summaryPath"
foreach ($k in $artifacts.Keys) { Write-Host ("  {0,-16} {1}" -f $k, $artifacts[$k]) }
if ($matrixExit -ne 0) { Write-Warn2 "matrix reported failures (exit $matrixExit) — see $($artifacts['matrixReport'])" }
exit $matrixExit
