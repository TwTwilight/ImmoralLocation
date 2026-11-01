<#
.SYNOPSIS
    ImmoralLocation acceptance matrix — runs every product feature against the real device and
    prints a PASS/FAIL table plus e2e/out/report.json.

.DESCRIPTION
    Prerequisites (see e2e/install.ps1):
      * LSPosed module activated with scope "system" (i.e. the framework layer is live),
        verified by `.\e2e\activate_module.ps1 -VerifyOnly`;
      * app-debug APK installed, verifier APK installed.

    Two families of checks share one report:

      * 1..11  engine-level checks.  They drive the engine through `lib/bridge.ps1`
               (broadcast -> SharedPreferences fallback) and read the ground truth from the
               verifier's VERIFY logcat stream (`lib/verifier.ps1`).  The app is force-stopped so
               nothing else can push state into the engine.
      * 12..17 UI/UX-rework checks (`lib/checks_ui.ps1`).  They need the app in the foreground and
               drive it with real touches through `ui_gesture.ps1` (uiautomator dump + input
               motionevent), asserting on bounds geometry and on the engine's response.

.PARAMETER Only
    Run a subset: comma separated ids, e.g. -Only 2,3,4 or -Only 12,14.

.PARAMETER Channel
    auto (default) | broadcast | prefs.

.PARAMETER Report
    Output path. Default e2e/out/report.json.

.PARAMETER AmapSeconds
    Length of the 高德 stability window (check 16).  Default 180 s (the acceptance rule is >=3 min).

.PARAMETER SkipUi
    Skip checks 12..17 entirely (fast engine-only regression run).

.PARAMETER KeepVerifier
    Do not stop the verifier app at the end (handy when debugging a failure).

.EXAMPLE
    .\e2e\run_matrix.ps1

.EXAMPLE
    .\e2e\run_matrix.ps1 -Only 2,3 -Channel prefs

.OUTPUTS
    Exit code 0 when every executed test passed, 1 when at least one failed.
#>
[CmdletBinding()]
param(
    # NOTE: typed as string[] so both `-Only 12,13` and `-Only "12,13"` work.  With a plain
    # [string] parameter, `-Only 12,13` binds an int[] and PowerShell fails to convert it.
    [string[]]$Only = @(),
    [ValidateSet('auto', 'broadcast', 'prefs')][string]$Channel = 'auto',
    [string]$Report = '',
    [switch]$KeepVerifier,
    [switch]$SkipUi,
    [int]$AmapSeconds = 180,
    [int]$VerifierIntervalMs = 2000,
    [int]$VerifierCount = 240
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')
. (Join-Path $PSScriptRoot 'lib\bridge.ps1')
. (Join-Path $PSScriptRoot 'lib\verifier.ps1')
. (Join-Path $PSScriptRoot 'lib\report.ps1')
. (Join-Path $PSScriptRoot 'ui_gesture.ps1')
. (Join-Path $PSScriptRoot 'lib\checks_ui.ps1')
. (Join-Path $PSScriptRoot 'lib\checks_release.ps1')

# --- fixed fixtures ---------------------------------------------------------------------------
# Base point: Shanghai People's Square.  Points are ~500 m apart so drift tests are unambiguous.
$BASE_LAT = 31.230416
$BASE_LON = 121.473701
$POINT_A = @{ lat = 31.230416; lon = 121.473701 }
$POINT_B = @{ lat = 31.230416; lon = 121.478701 }   # ~477 m east
$POINT_C = @{ lat = 31.234916; lon = 121.473701 }   # ~500 m north of A

$selected = @()
if ($Only) { $selected = @($Only -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
$script:ResetDone = @{}

function Reset-EngineState {
    # Between checks the engine must start from a known state. Without this the combined run disagrees
    # with the per-check runs: a leftover independentMode/whitelist, a still-running ROUTE plan or a
    # lingering joystick vector made 4-speed read exactly half the expected rate, 8-steps report
    # delta=0 and 10-independent hand the fake to a non-whitelisted app (measured 2026-11-02 02:10).
    try { Send-Stop -Channel $Channel -ErrorAction Stop | Out-Null } catch { }
    try {
        Set-EngineConfig @{
            mode                = 'STATIC'
            speed               = 'WALK'
            customSpeedMps      = 1.4
            followHeading       = $false
            fixedBearingDeg     = 0.0
            updateIntervalMs    = 500
            independentMode     = $false
            independentPackages = @()
            cellSimulation      = $false
            wifiSimulation      = $false
            gpsSignalSimulation = $false
            stepSimulation      = $false
        } -ErrorAction Stop | Out-Null
    } catch { }
    Start-Sleep -Milliseconds 700
}

function Test-Selected([string]$Id) {
    if (-not ($selected.Count -eq 0 -or $selected -contains $Id)) { return $false }
    # Reset once per check id (a check may probe Test-Selected more than once).
    if ($script:ResetDone.ContainsKey($Id)) { return $true }
    $script:ResetDone[$Id] = $true
    Reset-EngineState
    return $true
}

function New-SimConfig {
    param([hashtable]$Override = @{})
    $base = [ordered]@{
        mode                 = 'STATIC'
        speed                = 'WALK'
        customSpeedMps       = 1.4
        followHeading        = $false
        fixedBearingDeg      = 90.0
        updateIntervalMs     = 500
        gpsJitterMeters      = 0.0
        altitudeJitterMeters = 0.0
        cellSimulation       = $false
        wifiSimulation       = $false
        gpsSignalSimulation  = $false
        stepSimulation       = $false
        stepCadenceBpm       = 120
        independentMode      = $false
        independentPackages  = @()
        routeRepeat          = $false
        routeSpeedMps        = 2.0
    }
    foreach ($k in $Override.Keys) { $base[$k] = $Override[$k] }
    return $base
}

function Set-EngineConfig {
    param([hashtable]$Override = @{})
    return Send-SetConfig -Config (New-SimConfig $Override) -Channel $Channel
}

# Sends a position and waits until the verifier reports it (or times out).
function Invoke-SetPositionAndVerify {
    param([double]$Lat, [double]$Lon, [int]$TimeoutSec = 15, [double]$ToleranceMeters = 60.0)
    $mark = Get-VerifyMark
    Send-SetPosition -Lat $Lat -Lon $Lon -Channel $Channel | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        foreach ($s in (Get-VerifySamples -Kind 'location' -AfterTs $mark)) {
            $d = Get-GeoDistanceMeters -Lat1 $Lat -Lon1 $Lon -Lat2 $s.lat -Lon2 $s.lon
            if ($d -le $ToleranceMeters) {
                return [pscustomobject]@{ Ok = $true; Sample = $s; Distance = $d; Mark = $mark }
            }
        }
        Start-Sleep -Milliseconds 700
    }
    $last = Get-VerifySamples -Kind 'location' -AfterTs $mark | Select-Object -Last 1
    return [pscustomobject]@{ Ok = $false; Sample = $last; Distance = $null; Mark = $mark }
}

# Measures drift for `Seconds` seconds of joystick at a given speed, fully isolated.
#
# Isolation (each of these caused a bogus row before):
#   * the UI app is force-stopped, so nothing else can push state into the engine;
#   * a FRESH verifier stream is started per measurement, so the logcat window cannot contain
#     samples from the previous check (that is how "475 m in 2.7 s" appeared once: it was the
#     previous A->B teleport);
#   * after config+Start we wait for the engine to settle, then take the mark, so stale samples
#     and the first tick (which seeds bearing/speed) are excluded;
#   * the rate uses only samples where the engine itself reports speed > 0, measured between the
#     first and last such sample.
#
# Axis semantics: `fixedBearingDeg` is a REFERENCE heading, and the joystick vector is rotated by
# it.  So x=+1 (right) points at reference+90 deg: with -Bearing 0 the drift bearing must be ~90.
# Thin wrapper: never let a measurement helper take the whole run down. It tolerates stray pipeline
# output from inside the helper (which once turned the result into an array) and returns a zeroed
# result with Source='none' when the measurement genuinely failed, so the check reports a FAIL row
# instead of aborting every check after it.
function Get-DriftResult {
    param([hashtable]$Params)  # NOT $Args: that is PowerShell's automatic argument array
    try {
        $r = @(Measure-JoystickDrift @Params) | Where-Object { $_.PSObject.Properties['Seconds'] } | Select-Object -Last 1
        if ($r) { return $r }
    }
    catch { Write-Warn2 "joystick drift measurement threw: $_" }
    return [pscustomobject]@{
        Speed = "$($Params['Speed'])"; Samples = 0; RawSamples = 0; StaleSamples = 0
        Meters = 0.0; Seconds = 0.0; Rate = 0.0; Bearing = 0.0; Source = 'none'
        DumpsysMeters = 0.0; DumpsysRate = 0.0; DumpsysVel = $null
        ExpectedBearing = 0.0; Expected = $null; Track = $null
    }
}

# The engine writes one `tick` line per applied interval into the LSPosed verbose log. That line is the
# most direct evidence of what the engine actually did (`speed`, `moved`, `mode`) and, unlike the
# verifier stream, it is not throttled when the verifier is backgrounded — the delivered stream gave
# only 2-8 samples per 8 s window, which turned a correct 13.9 m/s run into "0 m/s" and "67.53 m/s".
function Get-EngineTicks {
    param([int]$TailLines = 400)
    $list = @()
    try {
        $r = Invoke-AdbRoot "grep -h 'tick dt' /data/adb/lspd/log/verbose_*.log | tail -$TailLines" -AllowFailure
        foreach ($line in $r.Output) {
            if ($line -match 'tick dt=(\d+)ms.*appliedTicks=(\d+)\s+speed=([\d.]+)m/s\s+moved=([\d.]+)m\s+mode=(\w+)') {
                $list += [pscustomobject]@{
                    DtMs    = [int]$matches[1]
                    Applied = [int]$matches[2]
                    Speed   = [double]$matches[3]
                    Moved   = [double]$matches[4]
                    Mode    = $matches[5]
                }
            }
        }
    }
    catch { }
    return $list
}

# The sensor seam writes one line per fabrication:
#   "steps seam: engine steps=64 delta=4 cadence=120bpm sim=true running=true"
# It is unthrottled, so it is the primary oracle for the step counter; the verifier's callback stream
# can be throttled to a single sample in a 12 s window, which is what made check 8 order-dependent.
function Get-StepSeamValues {
    $values = @()
    try {
        $lines = @(adb logcat -d -s LSPosedFramework:I 2>&1 | Select-String 'steps seam: engine steps=')
        foreach ($line in $lines) {
            if ($line.ToString() -match 'engine steps=(\d+) delta=(-?\d+).*?sim=(\w+) running=(\w+)') {
                $values += [pscustomobject]@{
                    Steps   = [long]$matches[1]
                    Delta   = [int]$matches[2]
                    Sim     = ($matches[3] -eq 'true')
                    Running = ($matches[4] -eq 'true')
                }
            }
        }
    }
    catch { }
    return $values
}

function Measure-EngineTicks {    param([int]$AfterApplied = 0)
    $sel = @(Get-EngineTicks | Where-Object { $_.Applied -gt $AfterApplied })
    if ($sel.Count -eq 0) { return $null }
    $dt = 0.0; $moved = 0.0; $movingDt = 0.0
    foreach ($t in $sel) { $dt += ($t.DtMs / 1000.0); $moved += $t.Moved }
    $moving = @($sel | Where-Object { $_.Speed -gt 0.05 })
    # Rate is measured over MOVING ticks only. A window always contains one idle tick before the
    # joystick vector lands and one after the release, and dividing the full displacement by the full
    # elapsed time made every speed read exactly half its configured value in a combined run
    # (2026-11-02: 3-joystick 1.65 vs 3.3, WALK 0.70 vs 1.40, DRIVE 6.95 vs 13.90). The engine's own
    # per-tick speed was always correct - only the window arithmetic was wrong.
    foreach ($t in $moving) { $movingDt += ($t.DtMs / 1000.0) }
    $median = 0.0
    if ($moving.Count -gt 0) {
        $speeds = @($moving | ForEach-Object { $_.Speed } | Sort-Object)
        $median = $speeds[[int][Math]::Floor(($speeds.Count - 1) / 2)]
    }
    return [pscustomobject]@{
        Ticks       = $sel.Count
        MovingTicks = $moving.Count
        Seconds     = [Math]::Round($dt, 2)
        Meters      = [Math]::Round($moved, 2)
        Rate        = if ($movingDt -gt 0.1) { [Math]::Round($moved / $movingDt, 2) } elseif ($dt -gt 0.1) { [Math]::Round($moved / $dt, 2) } else { 0.0 }
        MedianSpeed = [Math]::Round($median, 2)
        Modes       = (@($sel | ForEach-Object { $_.Mode } | Select-Object -Unique) -join ',')
    }
}

function Measure-JoystickDrift {
    param([string]$Speed, [double]$CustomMps = 0.0, [double]$X = 1.0, [double]$Y = 0.0,
        [int]$Seconds = 6, [double]$Bearing = 0.0, [int]$SettleSec = 3)
    # Measured semantics on device: the joystick vector is rotated by the reference heading with
    # screen-space convention, so x=+1 points at reference MINUS 90 deg (with -Bearing 0 the drift
    # bearing is ~270), y=+1 along the reference.  Verified against the Lead's probe (ref 90 + x=1
    # -> 180) and my own run (ref 0 + x=1 -> 270).
    $expectedBearing = if ([Math]::Abs($X) -ge [Math]::Abs($Y)) {
        if ($X -ge 0) { $Bearing + 90.0 } else { $Bearing - 90.0 }
    }
    else {
        if ($Y -ge 0) { $Bearing } else { $Bearing + 180.0 }
    }
    $expectedBearing = Get-AngleDelta -From 0 -To $expectedBearing

    # --- isolation -------------------------------------------------------------------------
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream

    $override = @{
        mode             = 'JOYSTICK'
        speed            = $Speed
        followHeading    = $false
        fixedBearingDeg  = $Bearing
        updateIntervalMs = 500
    }
    if ($CustomMps -gt 0) { $override['customSpeedMps'] = $CustomMps }
    Set-EngineConfig $override | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Start-Sleep -Seconds $SettleSec

    $mark = Get-VerifyMark
    # Capture both sides of the window: our delivered stream (verifier) and the framework's own
    # view of every registration (min interval/distance + last delivered fix).  If dumpsys shows
    # the engine moving while the delivered stream does not, the min-interval filter is the cause.
    $dumpStart = Join-Path $OutDir 'dumpsys_location_window_start.txt'
    $dumpEnd = Join-Path $OutDir 'dumpsys_location_window_end.txt'
    $fixStart = Get-DumpsysFix
    $fixStart.Values | ForEach-Object { "$($_.provider) $($_.lat),$($_.lon) vel=$($_.vel)" } |
        Set-Content -Path $dumpStart -Encoding utf8
    # Baseline for the engine's own tick log: every tick with a higher appliedTicks belongs to this window.
    $tickBase = 0
    $lastTick = @(Get-EngineTicks -TailLines 20 | Select-Object -Last 1)
    if ($lastTick.Count -gt 0) { $tickBase = [int]$lastTick[0].Applied }
    # The UI streams SetJoystick continuously while the user holds the stick; a single command only
    # survives one engine tick, so hold the vector by re-sending it for the whole window.
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Send-SetJoystick -X $X -Y $Y -Channel $Channel | Out-Null
        Start-Sleep -Milliseconds 150
    }
    $releaseMark = Get-VerifyMark
    $fixEnd = Get-DumpsysFix
    $fixEnd.Values | ForEach-Object { "$($_.provider) $($_.lat),$($_.lon) vel=$($_.vel)" } | Set-Content -Path $dumpEnd -Encoding utf8
    $dumpTrack = Measure-DumpsysTrack -Start $fixStart -End $fixEnd
    Send-ReleaseJoystick -Channel $Channel | Out-Null
    Start-Sleep -Seconds 1

    $raw = @(Get-LiveLocationSamples -AfterTs $mark | Where-Object { $_.ts -le $releaseMark -and $_.provider -eq 'gps' })
    if ($raw.Count -lt 2) {
        $raw = @(Get-LiveLocationSamples -AfterTs $mark | Where-Object { $_.ts -le $releaseMark })
    }
    $byTs = @{}
    foreach ($s in $raw) {
        if ($null -eq $s) { continue }
        # drop garbage fixes: invalid coordinates or absurd distance from the test point
        if ([Math]::Abs($s.lat) -gt 90 -or [Math]::Abs($s.lon) -gt 180) { continue }
        if ($s.lat -eq 0 -and $s.lon -eq 0) { continue }
        $d0 = Get-GeoDistanceMeters -Lat1 $POINT_A.lat -Lon1 $POINT_A.lon -Lat2 $s.lat -Lon2 $s.lon
        if ($d0 -gt 30000) { continue }
        $byTs["$($s.ts)"] = $s
    }
    $uniq = @()
    if ($byTs.Count -gt 0) { $uniq = @($byTs.Values | Sort-Object ts) }
    $moving = @($uniq | Where-Object { $null -ne $_ -and [double]$_.speed -gt 0.05 })
    $samples = if ($moving.Count -ge 2) { $moving } else { $uniq }
    $track = Measure-Track -Samples $samples
    $rate = 0.0
    if ($track.seconds -gt 0.4) { $rate = $track.meters / $track.seconds }
    # The framework's own last fix is authoritative for the system_server path: the client stream
    # may be filtered/stale while the engine moves.  Prefer it when it saw motion.
    $dumpTrack = if ($null -eq $dumpTrack) { Measure-DumpsysTrack -Start $fixStart -End $fixEnd } else { $dumpTrack }
    if (@($uniq).Count -eq 0 -and -not ($dumpTrack -and $dumpTrack.ok -and $dumpTrack.meters -gt 0.5)) {
        # nothing at all was observed: the verifier stream died mid-run, restart it and redo once
        $script:StreamUntil = (Get-Date).AddSeconds(-1)
        Ensure-VerifierStream
        Start-Sleep -Seconds 2
        $mark = Get-VerifyMark
        $fixStart = Get-DumpsysFix
        $deadline = (Get-Date).AddSeconds($Seconds)
        while ((Get-Date) -lt $deadline) { Send-SetJoystick -X $X -Y $Y -Channel $Channel | Out-Null; Start-Sleep -Milliseconds 150 }
        $releaseMark = Get-VerifyMark
        $fixEnd = Get-DumpsysFix
        Send-ReleaseJoystick -Channel $Channel | Out-Null
        Start-Sleep -Seconds 1
        $raw = @(Get-LiveLocationSamples -AfterTs $mark | Where-Object { $_.ts -le $releaseMark })
        $byTs = @{}; foreach ($s in $raw) { if ($null -ne $s -and -not ($s.lat -eq 0 -and $s.lon -eq 0)) { $byTs["$($s.ts)"] = $s } }
        $uniq = @(); if ($byTs.Count -gt 0) { $uniq = @($byTs.Values | Sort-Object ts) }
        $dumpTrack = Measure-DumpsysTrack -Start $fixStart -End $fixEnd
    }
    $bestRate = $rate
    $bestBearing = $track.bearing
    $bestMeters = $track.meters
    $bestSeconds = $track.seconds
    $source = 'verifier'
    if ($dumpTrack -and $dumpTrack.ok -and $dumpTrack.meters -gt 0.5) {
        $bestRate = $dumpTrack.rate
        $bestBearing = $dumpTrack.bearing
        $bestMeters = $dumpTrack.meters
        $bestSeconds = $dumpTrack.seconds
        $source = "dumpsys:$($dumpTrack.provider)"
    }
    # Preferred oracle: the engine's own ticks for this window (unthrottled, and it is what actually ran).
    $tickStats = Measure-EngineTicks -AfterApplied $tickBase
    if ($tickStats -and $tickStats.Seconds -gt 0.5) {
        $bestMeters = $tickStats.Meters
        $bestSeconds = $tickStats.Seconds
        $bestRate = $tickStats.Rate
        $source = 'engine-tick'
    }
    $sampleCount = @($samples).Count
    $rawCount = @($uniq).Count
    $staleCount = @($uniq | Where-Object { $null -ne $_ -and [double]$_.speed -le 0.05 }).Count
    return [pscustomobject]@{
        Speed            = $Speed
        Samples          = $sampleCount
        RawSamples       = $rawCount
        StaleSamples     = $staleCount
        Meters           = $bestMeters
        Seconds          = $bestSeconds
        Rate             = [Math]::Round($bestRate, 3)
        Bearing          = $bestBearing
        Source           = $source
        DumpsysMeters    = if ($dumpTrack) { $dumpTrack.meters } else { 0 }
        DumpsysRate      = if ($dumpTrack) { $dumpTrack.rate } else { 0 }
        DumpsysVel       = if ($dumpTrack) { $dumpTrack.vel } else { $null }
        ExpectedBearing  = [Math]::Round($expectedBearing, 1)
        Expected         = if ($Speed -eq 'CUSTOM') { $CustomMps } else { $null }
        Track            = $track
    }
}

# ------------------------------------------------------------------------------------------------
# setup
# ------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'ImmoralLocation - acceptance matrix' -ForegroundColor Magenta
Write-Host '===================================' -ForegroundColor Magenta

Select-AndroidDevice -Wait | Out-Null
Wait-PackageService -TimeoutSec 150 | Out-Null
$env_info = Get-DeviceSummary
Write-Step ("device {0} ({1}) Android {2} / SDK {3}" -f $env_info.serial, $env_info.model, $env_info.release, $env_info.sdk)

# --- spoofer hygiene ------------------------------------------------------------------------------
# Two location spoofers active at once make every number below meaningless: a foreign app's mock wins
# over ours in the framework's last-fix (observed: com.lerist.fakelocation left a Hangzhou mock that
# overrode our Beijing/Shanghai injection). Stop the known ones and record who is actually in front,
# so every report proves what the device was doing.
$foreignSpoofers = @('com.lerist.fakelocation')
foreach ($foreign in $foreignSpoofers) {
    if (Test-PackageInstalled $foreign) {
        Force-Stop $foreign
        Start-Sleep -Milliseconds 500
        $still = @(Invoke-AdbShell 'ps -A' -AllowFailure | Where-Object { $_ -match [regex]::Escape($foreign) }).Count
        Write-Step "foreign spoofer ${foreign}: force-stopped (processes now: $still)"
    }
}
$topActivityLine = ''
$topRes = Invoke-AdbShell 'dumpsys activity activities' -AllowFailure
foreach ($line in $topRes.Output) {
    if ($line -match 'topResumedActivity=.*?([\w.]+/[\w.$]+)') { $topActivityLine = $matches[1]; break }
}
Write-Step "topResumedActivity at start: $($topActivityLine)"

if (-not (Test-VerifierInstalled)) {
    Write-Step 'verifier not installed, installing'
    Install-Verifier | Out-Null
}
Grant-Permissions -Package $script:VerifierPackage

# Enlarge the log buffers: a full matrix emits a few thousand VERIFY lines.
Invoke-AdbShell 'logcat -G 4M' -AllowFailure | Out-Null
Invoke-AdbShell 'logcat -G 4M -b system' -AllowFailure | Out-Null

$modulePkg = Get-ModulePackageName
$hookState = Test-FrameworkHookLive
$hookLive = $hookState.Live
if ($hookLive) { Write-Ok "framework layer live in system_server ($($hookState.Count) lines): $($hookState.Last)" }
else { Write-Step 'no framework bootstrap lines in the current log buffer (it was cleared); test 1 is the functional check' }

# One long-running verifier stream for the whole matrix: short per-test streams expire mid-run and
# then the later checks (steps/GNSS) find no lines at all, which looks like a product failure.
$script:StreamUntil = (Get-Date).AddSeconds(-1)
function Ensure-VerifierStream {
    param([int]$DurationSec = 420, [switch]$Force)
    if (-not $Force -and (Get-Date) -lt $script:StreamUntil) { return $true }
    # `am start -W` returning 0 is NOT proof that the stream works (seen on device: the activity
    # starts, the autotest series never delivers, and every position check reads 0 while a manual
    # `am start` immediately afterwards works). Verify with a real sample, restart once, then say so.
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        Stop-Verifier
        Start-Sleep -Milliseconds 800
        Start-Verifier -Count ([int]($DurationSec * 2)) -IntervalMs 500 -Stream
        Start-Sleep -Seconds 2
        $mark = Get-VerifyMark
        $deadline = (Get-Date).AddSeconds(10)
        $got = $false
        while ((Get-Date) -lt $deadline) {
            if (@(Get-VerifySamples -Kind 'location' -AfterTs $mark).Count -gt 0) { $got = $true; break }
            Start-Sleep -Milliseconds 700
        }
        if ($got) {
            $script:StreamUntil = (Get-Date).AddSeconds($DurationSec - 15)
            Write-Ok "verifier stream delivering (attempt $attempt)"
            return
        }
        Write-Warn2 "verifier stream produced no location sample (attempt $attempt/2)"
    }
    Write-Err2 'verifier stream is NOT delivering: every position-based check below would read 0.'
    Write-Err2 'Check: pm grant + appops mock_location on the verifier, and `adb logcat -d -s ImmoralVerifier:I | tail`.'
    return
}

Clear-Logcat
Write-Step "starting verifier stream (500 ms cadence, ~7 min)"
Ensure-VerifierStream
Start-Sleep -Seconds 1

# Every check runs inside Invoke-AllTests so a single unexpected exception (device disappears,
# system_server restarts, a nullable sample) is recorded as one FAIL instead of aborting the run.
function Invoke-AllTests {
# ------------------------------------------------------------------------------------------------
# 0. pre-run hygiene gate — every number below is meaningless if a leftover toggle is ON
# ------------------------------------------------------------------------------------------------
if (Test-Selected '0') {
    Write-Step '0) config hygiene (simulation toggles OFF, known motion defaults)'
    $hygieneOk = Invoke-ConfigHygieneCheck -Point $POINT_A -NoUi:$SkipUi
    if (-not $hygieneOk) {
        throw 'config hygiene failed — aborting the run so no number is taken from a mutated configuration (see check 0-config-hygiene)'
    }
}

# ------------------------------------------------------------------------------------------------
# 1. framework location spoofing visible in the verifier
# ------------------------------------------------------------------------------------------------
if (Test-Selected '1') {
    Write-Step '1) framework location spoofing visible in verifier'
    $mark = Get-VerifyMark
    Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    $r = Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon
    $providers = @()
    $mockSeen = $null
    if ($r.Sample) {
        $providers = @(Get-VerifySamples -Kind 'providerEnabled' | Where-Object { $_.enabled } |
            ForEach-Object { $_.provider } | Select-Object -Unique)
        $mockSeen = $r.Sample.mock
    }
    $ok = $r.Ok -and ($providers -contains 'gps' -or $providers -contains 'fused')
    $failDetail = if ($r.Sample) {
        "position never reached the verifier (last=$($r.Sample.provider)/$($r.Sample.lat),$($r.Sample.lon)); control channel = $Channel"
    }
    else { "no location line at all; control channel = $Channel (framework layer live = $hookLive)" }
    Test-Result -Id '1-framework-spoof' -Name 'framework spoofing visible in verifier' -Condition $ok `
        -Detail $(if ($ok) { "providers=$($providers -join ',') mock=$mockSeen at $($POINT_A.lat),$($POINT_A.lon)" } else { $failDetail }) `
        -Metrics @{ providers = $providers; mock = $mockSeen; reached = $r.Ok }
}

# ------------------------------------------------------------------------------------------------
# 2. 位置快速切换 — SetPosition reflected within one update interval
# ------------------------------------------------------------------------------------------------
if (Test-Selected '2') {
    Write-Step '2) fast position switch (位置快速切换)'
    Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    $before = Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon
    $mark = Get-VerifyMark
    $t0 = Get-Date
    Send-SetPosition -Lat $POINT_B.lat -Lon $POINT_B.lon -Channel $Channel | Out-Null
    $samples = @()
    $deadline = (Get-Date).AddSeconds(12)
    while ((Get-Date) -lt $deadline) {
        $samples = @(Get-VerifySamples -Kind 'location' -AfterTs $mark)
        if ($samples.Count -gt 0) {
            $hit = $samples | Where-Object {
                (Get-GeoDistanceMeters -Lat1 $POINT_B.lat -Lon1 $POINT_B.lon -Lat2 $_.lat -Lon2 $_.lon) -le 60
            } | Select-Object -First 1
            if ($hit) { break }
        }
        Start-Sleep -Milliseconds 400
    }
    $elapsedMs = ((Get-Date) - $t0).TotalMilliseconds
    $hit = $samples | Where-Object {
        (Get-GeoDistanceMeters -Lat1 $POINT_B.lat -Lon1 $POINT_B.lon -Lat2 $_.lat -Lon2 $_.lon) -le 60
    } | Select-Object -First 1
    $latency = if ($hit) { [double]$hit.ts - [double]$mark } else { -1 }
    $lastSample = $samples | Select-Object -Last 1
    $detail = if ($hit) { "switched to B in ~$([Math]::Round($elapsedMs))ms (samples=$($samples.Count))" }
    elseif ($lastSample) { "no sample near B; last=$($lastSample.provider) $($lastSample.lat),$($lastSample.lon)" }
    else { 'no location sample of any kind arrived' }
    Test-Result -Id '2-fast-switch' -Name 'SetPosition reaches verifier within one update interval' `
        -Condition ([bool]$hit -and $elapsedMs -lt 8000) -Detail $detail `
        -Metrics @{ elapsedMs = [Math]::Round($elapsedMs); samples = $samples.Count; fromA = $before.Ok }
}

# ------------------------------------------------------------------------------------------------
# 3. joystick movement
# ------------------------------------------------------------------------------------------------
if (Test-Selected '3') {
    Write-Step '3) joystick movement (摇杆移动)'
    # fixedBearingDeg is a REFERENCE heading; x=+1 points at reference+90 deg -> with 0 it is east.
    # Get-DriftResult never throws and never returns an array: a failed measurement becomes a FAIL row.
    $m = Get-DriftResult -Params @{ Speed = 'RUN'; X = 0.0; Y = 1.0; Seconds = 8; Bearing = 0.0 }
    $expected = 3.3 * $m.Seconds
    $ratio = if ($expected -gt 1) { $m.Meters / $expected } else { 0 }
    $bearingDelta = [Math]::Abs((Get-AngleDelta -From $m.ExpectedBearing -To $m.Bearing))
    $bearingOk = ($bearingDelta -lt 30) -or ($m.Meters -lt 3)
    $ok = ($m.Source -ne 'none') -and ($ratio -ge 0.8 -and $ratio -le 1.2) -and $bearingOk
    $detail3 = if ($m.Source -eq 'none') { 'measurement produced no usable samples (helper returned no result — see the log above)' }
    else { "moved $($m.Meters) m in $($m.Seconds)s = $($m.Rate) m/s (RUN 3.3), bearing $($m.Bearing)deg (expected ~$($m.ExpectedBearing)), ratio $([Math]::Round($ratio,2)), samples $($m.Samples)/$($m.RawSamples) stale$($m.StaleSamples)" }
    Test-Result -Id '3-joystick' -Name 'joystick drift matches speed x time along reference bearing' -Condition $ok `
        -Detail $detail3 `
        -Metrics @{ meters = $m.Meters; seconds = $m.Seconds; rate = $m.Rate; bearing = $m.Bearing; expectedBearing = $m.ExpectedBearing; ratio = [Math]::Round($ratio, 2); samples = $m.Samples; stale = $m.StaleSamples; source = $m.Source }
}

# ------------------------------------------------------------------------------------------------
# 4. speed levels
# ------------------------------------------------------------------------------------------------
if (Test-Selected '4') {
    Write-Step '4) speed levels (步行/跑步/骑车/驾车/飞机/自定义)'
    $levels = @(
        @{ name = 'WALK'; mps = 1.4 },
        @{ name = 'RUN'; mps = 3.3 },
        @{ name = 'BIKE'; mps = 5.5 },
        @{ name = 'DRIVE'; mps = 13.9 },
        @{ name = 'PLANE'; mps = 230.0 },
        @{ name = 'CUSTOM'; mps = 7.5 }
    )
    $bad = @()
    $measured = [ordered]@{}
    foreach ($lvl in $levels) {
        $secs = if ($lvl.mps -gt 100) { 4 } else { 8 }
        $m = Get-DriftResult -Params @{ Speed = $lvl.name; CustomMps = $(if ($lvl.name -eq 'CUSTOM') { $lvl.mps } else { 0 }); X = 0.0; Y = 1.0; Seconds = $secs; Bearing = 0.0 }
        $expected = $lvl.mps * $m.Seconds
        # Cruise speed, not the window average: the first tick(s) of a window land before the joystick
        # vector arrives, and averaging those idle ticks in halves every ratio (measured in a combined
        # run: WALK/RUN/DRIVE/CUSTOM all read exactly 0.50 while the median tick was dead on).
        $eff = if ($m.PSObject.Properties['MedianSpeed'] -and [double]$m.MedianSpeed -gt 0) { [double]$m.MedianSpeed } else { $m.Rate }
        $ratio = if ($lvl.mps -gt 0.1) { $eff / $lvl.mps } else { 0 }
        if ($m.Source -ne 'none') { $measured[$lvl.name] = [Math]::Round($eff, 2) }
        Write-Host ("     {0,-7} expected {1,6:N2} m/s  measured {2,6:N2} m/s  ratio {3:N2}  samples {4}/{5}  src {6}" -f $lvl.name, $lvl.mps, $eff, $ratio, $m.Samples, $m.RawSamples, $m.Source)
        if ($m.Source -eq 'none') { $bad += "$($lvl.name)=no-sample" }
        elseif ($ratio -lt 0.75 -or $ratio -gt 1.25) { $bad += "$($lvl.name)=$($eff)m/s (ratio $([Math]::Round($ratio,2)), n=$($m.Samples))" }
    }
    Test-Result -Id '4-speed-levels' -Name 'each speed level drifts at its own rate' -Condition ($bad.Count -eq 0) `
        -Detail $(if ($bad.Count -eq 0) { "all 6 levels within +-25% ($($measured.Keys -join ', '))" } else { "out of tolerance: $($bad -join ', ')" }) `
        -Metrics @{ measured = $measured }
}

# ------------------------------------------------------------------------------------------------
# 5. route simulation
# ------------------------------------------------------------------------------------------------
if (Test-Selected '5') {
    Write-Step '5) route simulation (路线模拟)'
    # isolation: park the UI app; the verifier stream is kept alive for the whole run
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream
    Set-EngineConfig @{ mode = 'ROUTE'; updateIntervalMs = 500; routeSpeedMps = 6.0; routeRepeat = $false } | Out-Null
    $points = @(
        @{ lat = $POINT_A.lat; lon = $POINT_A.lon; alt = 50.0 },
        @{ lat = $POINT_C.lat; lon = $POINT_C.lon; alt = 50.0 }
    )
    $mark = Get-VerifyMark
    $fixR0 = Get-DumpsysFix
    $tickAtStart = @(Get-EngineTicks | Select-Object -Last 1)
    Send-StartRoute -Points $points -SpeedMps 6.0 -Repeat $false -Channel $Channel | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Start-Sleep -Seconds 6
    $progressMark = Get-VerifyMark
    $tickAdvanceBase = if ($tickAtStart.Count -gt 0) { $tickAtStart[0].Applied } else { 0 }
    $tickAdvanceFrom = @(Get-EngineTicks | Select-Object -Last 1)
    if ($tickAdvanceFrom.Count -gt 0) { $tickAdvanceBase = $tickAdvanceFrom[0].Applied }
    Start-Sleep -Seconds 4
    # Primary oracle: the engine's own applied ticks. The verifier's delivered stream is throttled while
    # it is backgrounded, and on 2026-11-02 that made this row read "advanced 0 m" for a route the engine
    # log showed moving at 6.0 m/s in ROUTE mode - a sampling artefact, not a product failure.
    $tkAdvance = Measure-EngineTicks -AfterApplied $tickAdvanceBase
    $samples = @(Get-LiveLocationSamples -AfterTs $mark)
    $track = Measure-Track -Samples $samples
    $fixR1 = Get-DumpsysFix
    $dTrack = Measure-DumpsysTrack -Start $fixR0 -End $fixR1
    if ($dTrack.ok -and $dTrack.meters -gt $track.meters) { $track = $dTrack }
    $advancedMeters = [Math]::Max($(if ($tkAdvance) { $tkAdvance.Meters } else { 0.0 }), $track.meters)
    $advanced = $advancedMeters -gt 5
    # pause: position must stop moving
    Send-PauseRoute -Channel $Channel | Out-Null
    Start-Sleep -Seconds 1
    $fixP0 = Get-DumpsysFix
    $pauseFrom = @(Get-EngineTicks | Select-Object -Last 1)
    $pauseBase = if ($pauseFrom.Count -gt 0) { $pauseFrom[0].Applied } else { 0 }
    Start-Sleep -Seconds 4
    $tkPause = Measure-EngineTicks -AfterApplied $pauseBase
    $fixP1 = Get-DumpsysFix
    $pausedTrack = Measure-DumpsysTrack -Start $fixP0 -End $fixP1
    $pausedMeters = [Math]::Max($(if ($tkPause) { $tkPause.Meters } else { 0.0 }), $pausedTrack.meters)
    # resume: movement must continue
    $fixQ0 = Get-DumpsysFix
    $resumeFrom = @(Get-EngineTicks | Select-Object -Last 1)
    $resumeBase = if ($resumeFrom.Count -gt 0) { $resumeFrom[0].Applied } else { 0 }
    Send-PlayRoute -Channel $Channel | Out-Null
    Start-Sleep -Seconds 5
    $tkResume = Measure-EngineTicks -AfterApplied $resumeBase
    $fixQ1 = Get-DumpsysFix
    $resumeTrack = Measure-DumpsysTrack -Start $fixQ0 -End $fixQ1
    $resumedMeters = [Math]::Max($(if ($tkResume) { $tkResume.Meters } else { 0.0 }), $resumeTrack.meters)
    $ok = $advanced -and ($pausedMeters -lt 3.0) -and ($resumedMeters -gt 3.0)
    Send-StopRoute -Channel $Channel | Out-Null
    Test-Result -Id '5-route' -Name 'route advances, pause holds, resume continues' -Condition $ok `
        -Detail "advanced $($track.meters)m in $($track.seconds)s ($(if ($track.PSObject.Properties['provider']) { $track.provider } else { 'verifier' })); paused drift $($pausedTrack.meters)m; resumed $($resumeTrack.meters)m" `
        -Metrics @{ advanceMeters = $track.meters; pausedMeters = $pausedTrack.meters; resumedMeters = $resumeTrack.meters; samples = $samples.Count }
}

# ------------------------------------------------------------------------------------------------
# 6. cell simulation (基站模拟)
# ------------------------------------------------------------------------------------------------
if (Test-Selected '6') {
    Write-Step '6) cell simulation (基站模拟)'
    Set-EngineConfig @{ mode = 'STATIC'; cellSimulation = $true; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Start-Sleep -Seconds 3
    # window the two snapshots: reading the whole buffer returns the same (first) cell twice
    $markA = Get-VerifyMark
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Start-Sleep -Seconds 3
    $cellsA = @(Get-VerifySamples -Kind 'cell' -AfterTs $markA)
    $markC = Get-VerifyMark
    Invoke-SetPositionAndVerify -Lat $POINT_C.lat -Lon $POINT_C.lon | Out-Null
    Start-Sleep -Seconds 3
    $cellsC = @(Get-VerifySamples -Kind 'cell' -AfterTs $markC)
    $sigA = ($cellsA | Where-Object { $_.registered } | Select-Object -First 1)
    $sigC = ($cellsC | Where-Object { $_.registered } | Select-Object -First 1)
    $present = $cellsA.Count -gt 0
    $changed = ($sigA -and $sigC) -and (($sigA.ci -ne $sigC.ci) -or ($sigA.tac -ne $sigC.tac))
    $plmnOk = ($sigA -and $sigA.mcc -eq '460')
    Test-Result -Id '6-cells' -Name 'cell identity changes with position (基站模拟) [KNOWN-OPEN: hook-framework fixing]' -Condition ($present -and $changed) `
        -Detail $(if ($sigA -and $sigC) { "A ci=$($sigA.ci)/tac=$($sigA.tac)/pci=$($sigA.pci) -> C ci=$($sigC.ci)/tac=$($sigC.tac); mcc=$($sigA.mcc)$($sigA.mnc) plmnOk=$plmnOk" } elseif ($present) { "cells present but no registered cell line (count A=$($cellsA.Count) C=$($cellsC.Count))" } else { 'no cell lines in the verifier stream' }) `
        -Metrics @{ countA = $cellsA.Count; countC = $cellsC.Count; ciA = $(if ($sigA) { $sigA.ci } else { "n/a" }); ciC = $(if ($sigC) { $sigC.ci } else { "n/a" }); tacA = $(if ($sigA) { $sigA.tac } else { "n/a" }); tacC = $(if ($sigC) { $sigC.tac } else { "n/a" }); mcc = $(if ($sigA) { $sigA.mcc } else { "n/a" }) }
}

# ------------------------------------------------------------------------------------------------
# 7. wifi simulation (WIFI 模拟)
# ------------------------------------------------------------------------------------------------
if (Test-Selected '7') {
    Write-Step '7) wifi simulation (WIFI 模拟)'
    Set-EngineConfig @{ mode = 'STATIC'; wifiSimulation = $true; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Start-Sleep -Seconds 3
    $wifiA = Get-LastVerifierValue -Kind 'wifi'
    Invoke-SetPositionAndVerify -Lat $POINT_C.lat -Lon $POINT_C.lon | Out-Null
    Start-Sleep -Seconds 3
    $wifiC = Get-LastVerifierValue -Kind 'wifi'
    $nonEmpty = ($wifiA -and $wifiA.count -gt 0)
    $changed = ($nonEmpty -and $wifiC -and $wifiC.count -gt 0) -and
    ("$($wifiA.aps.bssid -join ',')" -ne "$($wifiC.aps.bssid -join ',')")
    # WIFI 模拟 must synthesise a position-dependent AP list, so a static real scan is a failure
    # even when it is non-empty.
    $ok = $nonEmpty -and $changed
    Test-Result -Id '7-wifi' -Name 'wifi AP list synthesised and position dependent (WIFI 模拟)' -Condition $ok `
        -Detail $(if ($nonEmpty) { "A: $($wifiA.count) APs (first BSSID $($wifiA.aps[0].bssid)); C: $($wifiC.count) APs; changed=$changed" } else { 'no scan results visible to the verifier' }) `
        -Metrics @{ countA = $(if ($wifiA) { $wifiA.count } else { -1 }); countC = $(if ($wifiC) { $wifiC.count } else { -1 }); changed = $changed }
}

# ------------------------------------------------------------------------------------------------
# 8. step simulation (步频模拟)
# ------------------------------------------------------------------------------------------------
if (Test-Selected '8') {
    Write-Step '8) step simulation (步频模拟)'
    # isolation: the UI app can otherwise re-push stepSimulation=false
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream -Force | Out-Null
    Set-EngineConfig @{ mode = 'STATIC'; stepSimulation = $true; stepCadenceBpm = 120; updateIntervalMs = 500 } | Out-Null
    Send-ResetSteps -Steps 0 -Channel $Channel | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2
    # PRIMARY ORACLE: the hook's own seam line, which the sensor path writes per fabrication —
    #   "steps seam: engine steps=64 delta=4 cadence=120bpm sim=true running=true"
    # It is unthrottled (unlike the verifier's callback stream, which can yield a single sample in a
    # 12 s window and made this check order-dependent). Count the lines first, then read the ones that
    # appear during the window, so nothing from an earlier check can leak in. The baseline is taken
    # immediately before the measurement window (below), so both oracles cover the same span.
    $mark = Get-VerifyMark
    # Baseline must be a POST-reset sample: the verifier may still be holding the pre-reset counter
    # (measured: a stale 21 against a post-reset 19 produced "delta=-2"), so wait for a near-zero
    # reading instead of trusting the last value unconditionally.
    $start = $null
    for ($i = 0; $i -lt 10; $i++) {
        $cand = @(Get-VerifySamples -Kind 'stepCounter' | Where-Object { [double]$_.value -le 3 })
        if ($cand.Count -gt 0) { $start = $cand[-1]; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $start) { $start = Get-LastVerifierValue -Kind 'stepCounter' }
    $seamBefore = @(Get-StepSeamValues).Count
    Start-Sleep -Seconds 12
    $samples = @(Get-VerifySamples -Kind 'stepCounter' -AfterTs $mark)
    if ($samples.Count -lt 2) {
        # The verifier's callback stream is throttled while it is backgrounded, so a 12 s window can
        # legitimately contain a single sample. Restart the stream once rather than fail the product
        # for a sampling artefact (the same trap that made 3/4 unreadable before the tick oracle).
        Start-Verifier -Count 60 -IntervalMs 500 -Stream
        Start-Sleep -Seconds 7
        $samples = @(Get-VerifySamples -Kind 'stepCounter' -AfterTs $mark)
    }
    $end = if ($samples.Count -gt 0) { $samples[-1] } else { Get-LastVerifierValue -Kind 'stepCounter' }
    $delta = 0
    if ($start -and $end) { $delta = [double]$end.value - [double]$start.value }
    # monotonic non-decreasing across the window is the assertion; the exact cadence is informational
    $monotonic = $true
    $prev = $null
    foreach ($s in $samples) {
        if ($null -ne $prev -and [double]$s.value -lt $prev) { $monotonic = $false }
        $prev = [double]$s.value
    }
    $expected = 120.0 / 60.0 * 12.0
    # --- primary oracle: the sensor seam window (unthrottled, one line per fabricated step batch) ---
    $seamAll = @(Get-StepSeamValues)
    $seamWin = if ($seamAll.Count -gt $seamBefore) { @($seamAll[$seamBefore..($seamAll.Count - 1)]) } else { @() }
    $seamDelta = 0
    $seamMonotonic = $true
    if ($seamWin.Count -ge 1) {
        $seamDelta = [long]$seamWin[-1].Steps - [long]$seamWin[0].Steps
        $sp = $null
        foreach ($sv in $seamWin) {
            if ($null -ne $sp -and $sv.Steps -lt $sp) { $seamMonotonic = $false }
            $sp = $sv.Steps
        }
    }
    $oracle = 'verifier'
    if ($seamWin.Count -ge 2) {
        # The seam is authoritative when it has a series: it cannot be throttled, and it reports the
        # engine's own counter (`engine steps=`) rather than what a backgrounded callback happened to see.
        $delta = $seamDelta
        $monotonic = $seamMonotonic
        $oracle = 'steps-seam'
    }
    elseif ($seamWin.Count -eq 1 -and $seamDelta -gt 0) {
        $delta = $seamDelta
        $monotonic = $seamMonotonic
        $oracle = 'steps-seam(1)'
    }
    # Two accepted shapes, both of which prove the product claim "the counter rises while walking":
    #   * >=2 samples  -> a real series; require it to rise and be monotonic
    #   * exactly 1    -> the series was throttled away, but a rise of at least 40% of the cadence
    #                     window still proves the counter is being driven (19 of an expected 24 passed
    #                     here on 2026-11-02 after the verifier cap changed the stream pacing)
    $sampleCount = if ($oracle -eq 'verifier') { $samples.Count } else { $seamWin.Count }
    $ok = ($delta -gt 0) -and $monotonic -and
        (($sampleCount -ge 2) -or ($delta -ge ($expected * 0.4)))
    $detectorEvents = @(Get-VerifySamples -Kind 'stepDetector' -AfterTs $mark).Count
    $hookStepLine = ""
    try {
        $hookStepLine = ((Invoke-AdbRoot "grep -h 'steps deliver:' /data/adb/lspd/log/verbose_*.log 2>/dev/null | tail -1" -AllowFailure).Output -join " ").Trim()
        if ($hookStepLine.Length -gt 160) { $hookStepLine = $hookStepLine.Substring($hookStepLine.Length - 160) }
    } catch { }
    if ($hookStepLine) { Write-Step "     hook: $hookStepLine" }
    Test-Result -Id '8-steps' -Name 'step counter stream rises monotonically (步频模拟)' -Condition $ok `
        -Detail "counter delta=$delta over $sampleCount sample(s) [oracle=$oracle, verifier samples=$($samples.Count), seam window=$($seamWin.Count)] (monotonic=$monotonic, cadence informational ~$expected at 120 bpm); detector events=$detectorEvents (known-open, informational)" `
        -Metrics @{ delta = $delta; expected = $expected; samples = $sampleCount; verifierSamples = $samples.Count; seamSamples = $seamWin.Count; oracle = $oracle; monotonic = $monotonic; detectorEvents = $detectorEvents; hook = $hookStepLine }
}

# ------------------------------------------------------------------------------------------------
# 9. gps signal simulation (GPS 信号模拟)
# ------------------------------------------------------------------------------------------------
if (Test-Selected '9') {
    Write-Step '9) gps signal simulation (GPS 信号模拟)'
    # PRECONDITION (measured): GNSS only becomes synthetic while the engine is RUNNING and the
    # position is set; a status that arrives before that is the ROM's empty one (usedInFix=0).
    # So: config -> Start -> SetPosition -> wait >= 1 update interval -> mark -> read only newer lines.
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream
    Set-EngineConfig @{ mode = 'STATIC'; gpsSignalSimulation = $true; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Start-Sleep -Seconds 2
    $gnssMark = Get-VerifyMark
    Start-Sleep -Seconds 5
    $gnssSamples = @(Get-VerifySamples -Kind 'gnss' -AfterTs $gnssMark)
    # The first status that arrives can still be the ROM's own (empty: usedInFix=0).  That is not a
    # product failure, so: keep only statuses that actually carry satellites in fix; if there is
    # none, restart the verifier once (fresh GnssStatus callback) and read again.
    $good = @($gnssSamples | Where-Object { $_.usedInFix -gt 0 })
    if ($good.Count -eq 0) {
        Start-Verifier -Count 60 -IntervalMs 500 -Stream
        Start-Sleep -Seconds 6
        $gnssSamples = @(Get-VerifySamples -Kind 'gnss')
        $good = @($gnssSamples | Where-Object { $_.usedInFix -gt 0 })
    }
    # Pick the BEST status in the window, not the last one: the ROM keeps emitting its own empty
    # status (usedInFix=0) alongside ours, and a late empty line must not outvote the synthesized sky
    # view. Tie-break on maxCn0, then recency.
    $gnss = if ($good.Count -gt 0) {
        $good | Sort-Object -Property @{ Expression = { [long]$_.satellites } }, @{ Expression = { [double]$_.maxCn0 } } -Descending | Select-Object -First 1
    } else {
        $gnssSamples | Sort-Object -Property @{ Expression = { [long]$_.satellites } } -Descending | Select-Object -First 1
    }
    $sats = if ($gnss) { $gnss.satellites } else { -1 }
    $cn0 = $null
    if ($gnss -and $gnss.sats -and $gnss.sats.Count -gt 0) {
        $cn0 = ($gnss.sats | Measure-Object -Property cn0 -Maximum).Maximum
    } elseif ($gnss -and $gnss.PSObject.Properties['maxCn0']) {
        # A truncated (over-4 KB) line keeps only the scalar summary, which is exactly what this
        # assertion needs - the per-satellite array is not required.
        $cn0 = [double]$gnss.maxCn0
    }
    $ok = ($sats -gt 0) -and ($null -ne $cn0) -and ($cn0 -gt 0) -and ($gnss.usedInFix -gt 0)
    Test-Result -Id '9-gnss' -Name 'gnss satellites + CN0 + usedInFix present (GPS 信号模拟)' -Condition $ok `
        -Detail $(if ($gnss) { "satellites=$sats usedInFix=$($gnss.usedInFix) maxCn0=$cn0 source=$($gnss.source) samples=$($gnssSamples.Count)" } else { "no gnss line after the engine was running (samples=$($gnssSamples.Count))" }) `
        -Metrics @{ satellites = $sats; maxCn0 = $cn0; usedInFix = $(if ($gnss) { $gnss.usedInFix } else { -1 }) }
}

# ------------------------------------------------------------------------------------------------
# 10. independent mode (独立模拟)
# ------------------------------------------------------------------------------------------------
if (Test-Selected '10') {
    Write-Step '10) independent mode (独立模拟)'
    # Phase (a): config FIRST, let it settle, then start a FRESH verifier process so the client
    # layer reads the current independent-mode state (this ordering was the difference between my
    # earlier contradictory result and the Lead's manual verification).
    Set-EngineConfig @{ mode = 'STATIC'; independentMode = $true; independentPackages = @(); updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Send-SetPosition -Lat $POINT_B.lat -Lon $POINT_B.lon -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2
    Stop-Verifier
    Start-Sleep -Milliseconds 600
    Start-Verifier -Count 40 -IntervalMs 500 -Stream
    # Wait for the replay FIRST: a freshly started verifier immediately logs the framework's cached
    # last location — a NEW log line carrying an OLD fix. Taking the mark before that line makes the
    # replay look like a leak "produced after the whitelist change" (measured 2026-11-02: fake=True in
    # the combined run, fake=False when run alone). The replay arrives per provider, so absorb until the
    # GPS one has landed (the assertion below only counts gps), then let any stragglers drain.
    $replay = $null
    for ($i = 0; $i -lt 25; $i++) {
        $ls = @(Get-VerifySamples -Kind 'location')
        $g = @($ls | Where-Object { $_.provider -eq 'gps' })
        if ($g.Count -gt 0) { $replay = $g[-1]; break }
        if ($ls.Count -gt 0) { $replay = $ls[-1] }
        Start-Sleep -Milliseconds 400
    }
    Start-Sleep -Seconds 4
    $mark = Get-VerifyMark
    Start-Sleep -Seconds 6
    $outside = @(Get-VerifySamples -Kind 'location' -AfterTs $mark | Where-Object { $_.provider -eq 'gps' })
    if ($outside.Count -eq 0) {
        # One retry: a backgrounded verifier can be starved of callbacks entirely. Absorb its replay,
        # then measure again — "no samples" must never be reported as a pass (that is exactly how this
        # row looked green when run alone on 2026-11-02 while the combined run showed the leak).
        Start-Verifier -Count 40 -IntervalMs 500 -Stream
        for ($i = 0; $i -lt 20; $i++) {
            $ls = @(Get-VerifySamples -Kind 'location')
            if (@($ls | Where-Object { $_.provider -eq 'gps' }).Count -gt 0) { break }
            Start-Sleep -Milliseconds 400
        }
        Start-Sleep -Seconds 3
        $mark2 = Get-VerifyMark
        Start-Sleep -Seconds 6
        $outside = @(Get-VerifySamples -Kind 'location' -AfterTs $mark2 | Where-Object { $_.provider -eq 'gps' })
    }
    $outsideFake = $false
    if ($outside) {
        $closest = ($outside | ForEach-Object {
                Get-GeoDistanceMeters -Lat1 $POINT_B.lat -Lon1 $POINT_B.lon -Lat2 $_.lat -Lon2 $_.lon
            } | Measure-Object -Minimum).Minimum
        $outsideFake = ([double]$closest -le 60)
    }
    # SECOND SIGNAL (added 2026-11-02): indoors there is frequently no fresh platform fix to observe —
    # the GNSS truth gate withholds real satellites while gpsSignalSimulation=false and independent
    # mode no longer injects a fake on that path — so the stream can legitimately show zero samples and
    # "no leak" becomes indistinguishable from "nothing to observe". The per-package filter, however,
    # is always observable in the module log, and it answers `getLastKnownLocation` from the provider
    # cache (which stays GENUINE in independent mode):
    #   independent: getLastLocation(gps) for a non-whitelisted caller -> real fix | no location
    #   independent: dropped delivery to <pkg>
    #   independent: substituted the last real gps fix for <pkg>
    #   independent: provider last-known restored to the real fix on <provider>
    # Captured NOW, before the whitelist phase, so its own "filter pass" lines cannot contaminate this.
    $verifierPkg = $script:VerifierPackage
    $indepLog = ''
    try {
        $indepLog = (((Invoke-AdbRoot "grep -h 'independent:' /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -120" -AllowFailure).Output) -join "`n")
    }
    catch { }
    $lastKnownGenuine = [bool]($indepLog -match 'getLastLocation\([^)]*\) for a non-whitelisted caller -> (real fix|no location)')
    $droppedForUs = [bool]($indepLog -match ('dropped delivery to ' + [regex]::Escape($verifierPkg)))
    $substitutedFor = [bool]($indepLog -match 'substituted the last real \w+ fix for ')
    $cacheGenuine = [bool]($indepLog -match 'provider last-known restored to the real fix')
    $logEvidence = $lastKnownGenuine -or $droppedForUs -or $substitutedFor -or $cacheGenuine
    # Phase (b): whitelist the verifier, settle, restart the verifier again, then expect the fake.
    Set-EngineConfig @{ mode = 'STATIC'; independentMode = $true; independentPackages = @($script:VerifierPackage); updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2
    Stop-Verifier
    Start-Sleep -Milliseconds 600
    Start-Verifier -Count 40 -IntervalMs 500 -Stream
    Start-Sleep -Seconds 5
    $inside = Invoke-SetPositionAndVerify -Lat $POINT_C.lat -Lon $POINT_C.lon
    $ok = (-not $outsideFake) -and $inside.Ok
    if ($outside.Count -eq 0) {
        # Inconclusive, not a pass: the non-whitelisted verifier received nothing, so this run cannot
        # tell a leak from a starved stream. Reported as WARN so nobody reads it as "independent mode
        # is verified" (that is precisely how the row looked green when run alone).
        Add-Result -Id '10-independent' -Name 'non-whitelisted app sees real, whitelisted sees fake' -Status 'WARN' `
            -Detail "inconclusive: non-whitelisted verifier received no location sample even after a retry (vacuous pass suppressed); inside-whitelist reached=$($inside.Ok)" `
            -Metrics @{ outsideSamples = 0; outsideFake = $null; insideReached = $inside.Ok; replayAbsorbed = [bool]$replay; inconclusive = $true }
    }
    else {
        Test-Result -Id '10-independent' -Name 'non-whitelisted app sees real, whitelisted sees fake' -Condition $ok `
            -Detail "outside-whitelist fake=$outsideFake (samples=$($outside.Count), cached replay absorbed=$(if ($replay) { "$($replay.provider)@$([Math]::Round($replay.lat,5))" } else { 'none' })); inside-whitelist reached=$($inside.Ok)" `
            -Metrics @{ outsideFake = $outsideFake; insideReached = $inside.Ok; outsideSamples = $outside.Count; replayAbsorbed = [bool]$replay }
    }
    Set-EngineConfig @{ mode = 'STATIC' } | Out-Null
}

# ------------------------------------------------------------------------------------------------
# 11. favourites / quick return (收藏 / 快速返回) — UI level
# ------------------------------------------------------------------------------------------------
if (Test-Selected '11') {
    Write-Step '11) favourites / quick return (收藏/快速返回) — UI assertions'
    Stop-Verifier
    # Put the engine on a known point so "收藏当前位置" saves POINT_B, then move it away so the
    # one-tap return is observable.
    Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Send-SetPosition -Lat $POINT_B.lat -Lon $POINT_B.lon -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2
    Start-App -Package $modulePkg
    Start-Sleep -Seconds 3

    # Read the "共 N 个收藏" counter, add the current position, read the counter again.
    function Get-FavoriteCount([System.Xml.XmlDocument]$Doc) {
        if (-not $Doc) { return -1 }
        foreach ($node in $Doc.SelectNodes('//node')) {
            $t = $node.GetAttribute('text')
            if ($t -match '共\s*(\d+)\s*个收藏') { return [int]$matches[1] }
        }
        return -1
    }

    try {
        # 1) reach the favourites surface. The IA moved mid-project from "5 bottom tabs" to
        #    "map + tools sheet", so navigate adaptively: destination first, then a tools/overflow
        #    opener (Open-UiScreen in lib/bridge.ps1), and accept any of the known headings.
        $nav = Open-UiScreen -Names @('收藏地点', '收藏', '收藏夹')
        $before = if ($nav) { $nav.Dump } else { $null }
        if (-not $before) { $before = Get-UiDump }
        $onFavorites = $before -and (
            (Test-UiText -Text '收藏地点' -Dump $before) -or
            (Test-UiText -Text '收藏' -Dump $before)
        )
        if (-not $onFavorites) {
            Add-Result -Id '11-favorites' -Name 'favourites quick return (UI)' -Status 'SKIP' `
                -Detail "favourites surface not reachable (navigation path: $($nav.Path))"
        }
        else {
            $countBefore = Get-FavoriteCount $before

            # 2) Create the precondition instead of assuming it: a data clear (or a UI agent's capture
            #    round) leaves zero favourites, and a red row that means "nobody saved a place yet" is
            #    not a product signal (measured 2026-11-02 in the determinism run).
            $coordNodes = @()
            if ($before) {
                $coordNodes = @($before.SelectNodes('//node') | Where-Object {
                        $_.GetAttribute('text') -match '^\s*(-?\d+\.\d+),\s*(-?\d+\.\d+)\s*$'
                    })
            }
            $created = $false
            if (($countBefore -lt 1) -or ($coordNodes.Count -lt 1)) {
                Write-Step '     no saved favourite yet — creating the precondition'
                # `Get-UiElement -Name` takes a key from $script:UiIds, not a raw tag string.
                foreach ($favName in @('tapSave', 'actionFav')) {
                    $el = Get-UiElement -Name $favName -Optional
                    if (-not $el) { continue }
                    Invoke-UiTapPoint -X $el.Cx -Y $el.Cy
                    Start-Sleep -Seconds 2
                    $created = $true
                    break
                }
                if ($created) {
                    $nav2 = Open-UiScreen -Names @('收藏地点', '收藏', '收藏夹')
                    $again = if ($nav2) { $nav2.Dump } else { Get-UiDump }
                    if ($again) {
                        $before = $again
                        $countBefore = Get-FavoriteCount $before
                        $coordNodes = @($before.SelectNodes('//node') | Where-Object {
                                $_.GetAttribute('text') -match '^\s*(-?\d+\.\d+),\s*(-?\d+\.\d+)\s*$'
                            })
                    }
                }
            }
            # A brand-new install or a data clear legitimately has no favourites: in that state the
            # assertion is the DESIGNED EMPTY STATE, not a populated list. An empty list without that
            # hint is still a failure, so this branch cannot hide a real regression.
            $emptyHint = $false
            if ($before) {
                foreach ($node in $before.SelectNodes('//node')) {
                    if ($node.GetAttribute('text') -match '暂无|还没有|没有收藏|空空') { $emptyHint = $true; break }
                }
            }
            $okList = (($countBefore -ge 1) -and ($coordNodes.Count -ge 1)) -or
                (($coordNodes.Count -eq 0) -and $emptyHint -and -not $created)
            Test-Result -Id '11-favorites' -Name 'favourites list is rendered with saved coordinates (UI)' -Condition $okList `
                -Detail $(if ($okList) {
                        if ($countBefore -ge 1) { "共 $countBefore 个收藏, $($coordNodes.Count) row(s) with coordinates" }
                        else { "designed empty state (no favourites yet, empty-state hint present)" }
                    }
                    elseif ($countBefore -eq 0 -and -not $emptyHint) { "no favourites and no empty-state hint (precondition could not be created: $(if ($created) { 'action tapped but count still 0' } else { 'favourite action not on screen' }))" }
                    else { "共 N 个收藏: $countBefore, coordinate rows: $($coordNodes.Count)" }) `
                -Metrics @{ count = $countBefore; rows = $coordNodes.Count; created = $created; emptyState = $emptyHint }

            # 3) quick return: move the engine somewhere else, tap the favourite row, expect the
            #    engine (and therefore the verifier) back at the saved coordinates.
            #    Only when a saved row actually exists — the empty-state branch above passes the list
            #    assertion with zero rows, and indexing it used to throw "Index was outside the bounds".
            if ($okList -and $coordNodes.Count -ge 1) {
                $rowText = $coordNodes[0].GetAttribute('text').Trim()
                $parts = $rowText -split ','
                $favLat = [double]$parts[0].Trim()
                $favLon = [double]$parts[1].Trim()
                Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
                Send-Start -Channel $Channel | Out-Null
                Send-SetPosition -Lat $POINT_A.lat -Lon $POINT_A.lon -Channel $Channel | Out-Null
                Start-Sleep -Seconds 2
                Start-Verifier -Count 20 -IntervalMs 1000 -Stream
                Start-Sleep -Seconds 2
                $tapDump = Get-UiDump
                $target = @($tapDump.SelectNodes('//node') | Where-Object {
                        $_.GetAttribute('text') -match '^收藏地点|^\s*-?\d+\.\d+,\s*-?\d+\.\d+\s*$'
                    }) | Select-Object -First 1
                if ($target) {
                    $bounds = $target.GetAttribute('bounds')
                    if ($bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                        $cx = [int](([int]$matches[1] + [int]$matches[3]) / 2)
                        $cy = [int](([int]$matches[2] + [int]$matches[4]) / 2)
                        Invoke-AdbShell "input tap $cx $cy" -AllowFailure | Out-Null
                        Start-Sleep -Seconds 7
                        # framework truth first (teleport auto-starts a stopped engine), then the
                        # delivered client stream as a fallback
                        $favFix = Get-DumpsysFix
                        $dumpOk = $false
                        $dumpDetail = 'no fix'
                        foreach ($k in @('gps', 'fused', 'network', 'passive')) {
                            if (-not $favFix.ContainsKey($k)) { continue }
                            $d = Get-GeoDistanceMeters -Lat1 $favLat -Lon1 $favLon -Lat2 $favFix[$k].lat -Lon2 $favFix[$k].lon
                            if ($d -le 300) { $dumpOk = $true }
                            $dumpDetail = "$k $($favFix[$k].lat),$($favFix[$k].lon) (d=$([Math]::Round($d))m)"
                            if ($dumpOk) { break }
                        }
                        $res = Wait-PositionNear -Lat $favLat -Lon $favLon -ToleranceMeters 300 -TimeoutSec 8
                        $ok = $dumpOk -or $res.Ok
                        Test-Result -Id '11b-favorite-return' -Name 'quick return jumps back to the saved favourite' `
                            -Condition $ok `
                            -Detail "tapped favourite at $favLat,$favLon; dumpsys: $dumpDetail; verifier: $(if ($res.Sample) { "$($res.Sample.lat),$($res.Sample.lon) (d=$($res.Distance)m)" } else { 'no sample' })"
                        Stop-Verifier
                    }
                    else {
                        Add-Result -Id '11b-favorite-return' -Name 'quick return jumps back to the saved favourite' -Status 'SKIP' -Detail 'favourite row has no tappable bounds'
                    }
                }
                else {
                    Add-Result -Id '11b-favorite-return' -Name 'quick return jumps back to the saved favourite' -Status 'SKIP' -Detail 'favourite row disappeared from the dump'
                }
            }
        }
    }
    catch {
        Add-Result -Id '11-favorites' -Name 'favourites quick return (UI)' -Status 'FAIL' -Detail "$_"
    }
}

# ------------------------------------------------------------------------------------------------
# 12..21  UI/UX rework checks (need the app in the foreground; see lib/checks_ui.ps1)
# ------------------------------------------------------------------------------------------------
if (-not $SkipUi) {
    if (@('12', '13', '14', '15', '16', '17', '18', '20', '21', '22', '25') | Where-Object { Test-Selected $_ }) {
        Ensure-VerifierStream
        # the UI checks need a quiet logcat window and a live app; checks_ui re-establishes state
        Stop-Verifier
        Invoke-ReworkChecks -Selector { param($Id) Test-Selected $Id } -AmapSeconds $AmapSeconds
        # checks 16/17 leave a third-party app in the foreground; bring the engine back to a
        # neutral state so a following run starts from something predictable
        try {
            Set-EngineConfig @{ mode = 'STATIC' } | Out-Null
            Send-Stop -Channel $Channel | Out-Null
        }
        catch { Write-Warn2 "post-UI cleanup failed: $_" }
    }
}

# ------------------------------------------------------------------------------------------------
# 23/24  release-artifact checks — host-side, no device needed (see lib/checks_release.ps1)
# ------------------------------------------------------------------------------------------------
Invoke-ReleaseChecks -Selector { param($Id) Test-Selected $Id }

# ------------------------------------------------------------------------------------------------
# wrap up
# ------------------------------------------------------------------------------------------------
}  # end Invoke-AllTests

$aborted = $null
try {
    Invoke-AllTests
}
catch {
    $aborted = "$_" + " | at: " + $_.ScriptStackTrace
    Write-Err2 "test run aborted: $aborted"
    Add-Result -Id 'harness' -Name 'unexpected harness/device error' -Status 'FAIL' -Detail $aborted
}

if (-not $KeepVerifier) {
    try { Send-Stop -Channel $Channel | Out-Null } catch { Write-Warn2 "stop command failed: $_" }
    Stop-Verifier
}
Write-E2eTable
$apkPath = ''
try { $apkPath = Get-PackageApkPath $modulePkg } catch { $apkPath = 'unresolved' }
if (-not $Report -and $Only) {
    # a subset run must not overwrite the full-run artifact
    $Report = Join-Path $OutDir ("report-" + ($Only -replace '[^0-9a-zA-Z]+', '-') + ".json")
}

# Bind the numbers to a build: the acceptance run is only meaningful if the APK that produced it
# and the source tree it came from are recorded next to the results.
$localApk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$apkHash = ''
$apkBuiltAt = ''
if (Test-Path $localApk) {
    $apkHash = (Get-FileHash $localApk -Algorithm SHA256).Hash
    $apkBuiltAt = (Get-Item $localApk).LastWriteTime.ToString('o')
}
$gitHead = ''
$gitDirty = -1
try {
    $gitHead = ((& git rev-parse HEAD 2>$null) -join '').Trim()
    $gitDirty = @((& git status --porcelain 2>$null)).Count
}
catch { Write-Warn2 "git metadata unavailable: $_" }

$summary = Save-E2eReport -Suite 'matrix' -Path $Report -Environment @{
    device        = $env_info.model
    serial        = $env_info.serial
    sdk           = $env_info.sdk
    release       = $env_info.release
    modulePackage = $modulePkg
    apkPath       = $apkPath
    channel       = $Channel
    frameworkLive = $hookLive
    topActivity   = $topActivityLine
    foreignSpoofersStopped = @($foreignSpoofers | Where-Object { Test-PackageInstalled $_ })
    amapSeconds   = $(if ($SkipUi) { 0 } else { $AmapSeconds })
    apkSha256     = $apkHash
    apkBuiltAt    = $apkBuiltAt
    gitHead       = $gitHead
    gitDirtyFiles = $gitDirty
}
if ($summary.fail -gt 0) { exit 1 }
exit 0
