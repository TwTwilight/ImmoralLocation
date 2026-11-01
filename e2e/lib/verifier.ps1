# e2e/lib/verifier.ps1 — drive the verifier app and parse its VERIFY logcat stream.
#
# Contract (docs/CONTRACTS.md §8, extended): every line is `VERIFY {json}` on tag ImmoralVerifier.
# Fields used by the matrix: kind, ts (device wall clock ms), provider, lat, lon, alt, acc, speed,
# bearing, mock, ageMs, satellites, usedInFix, maxCn0, count, aps, value, present, ...
#
# The verifier supports a fully headless mode:
#   am start -n com.immoral.location.verifier/.VerifierActivity \
#       --ez autotest true --ei count 10 --ei intervalMs 2000 --ez stream true --ez steps true
# which emits `count` snapshots and a final {"kind":"autotest","phase":"end"} marker.

. (Join-Path $PSScriptRoot 'common.ps1')

$script:VerifierPackage = 'com.immoral.location.verifier'
$script:VerifierActivity = 'com.immoral.location.verifier/.VerifierActivity'

function Install-Verifier {
    param([string]$Apk = '')
    if (-not $Apk) { $Apk = Join-Path $RepoRoot 'verifier\build\outputs\apk\debug\verifier-debug.apk' }
    if (-not (Test-Path $Apk)) { throw "verifier apk not built: $Apk (run .\gradlew.bat :verifier:assembleDebug)" }
    Install-Apk -Apk $Apk -Reinstall | Out-Null
    Grant-Permissions -Package $script:VerifierPackage
    Invoke-AdbShell "appops set $script:VerifierPackage android:mock_location allow" -AllowFailure | Out-Null
    return $true
}

function Stop-Verifier {
    Invoke-AdbShell "am force-stop $script:VerifierPackage" -AllowFailure | Out-Null
}

# Starts the automated snapshot series.  Returns immediately unless -Wait is given.
function Start-Verifier {
    param(
        [int]$Count = 10,
        [int]$IntervalMs = 2000,
        [switch]$Stream,      # also subscribe to requestLocationUpdates (proves the injection path)
        [switch]$LastKnown,   # also probe getLastKnownLocation(gps/network) every second for ~20 s
        [switch]$NoSteps,
        [switch]$Wait,
        [int]$TimeoutSec = 0
    )
    Stop-Verifier
    Start-Sleep -Milliseconds 400
    $cmd = "am start -W -n $script:VerifierActivity --es autotest true --es count $Count --es intervalMs $IntervalMs"
    if ($Stream) { $cmd += ' --es stream true' }
    if ($LastKnown) { $cmd += ' --es lastknown true' }
    if (-not $NoSteps) { $cmd += ' --es steps true' }
    Invoke-AdbShell $cmd -AllowFailure | Out-Null
    Start-Sleep -Seconds 1
    if ($Wait) {
        if ($TimeoutSec -le 0) { $TimeoutSec = [int](($Count * $IntervalMs) / 1000) + 30 }
        Wait-VerifierDone -TimeoutSec $TimeoutSec | Out-Null
    }
}

function Wait-VerifierDone {
    param([int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $lines = Get-Logcat -Tag 'ImmoralVerifier' -TailLines 4000
        foreach ($l in $lines) {
            if ($l -match '"phase":"end"') { return $true }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Parses all VERIFY lines currently in the buffer.
function Get-VerifySamples {
    param(
        [string]$Kind,
        [string]$Source,
        [string]$Provider,
        [long]$AfterTs = 0,
        [int]$TailLines = 40000
    )
    $raw = Get-Logcat -Tag 'ImmoralVerifier' -TailLines $TailLines
    $out = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $raw) {
        $idx = $line.IndexOf('VERIFY ')
        if ($idx -lt 0) { continue }
        $json = $line.Substring($idx + 7).Trim()
        try {
            $obj = $json | ConvertFrom-Json
        } catch {
            # logcat truncates a single line at ~4 KB, and a GNSS status that carries a full
            # constellation (37-53 satellites on this ROM) exceeds that, so the JSON arrives cut
            # mid-object and ConvertFrom-Json throws. The checks only ever read a few scalar fields,
            # so salvage them by name instead of discarding a sample the product really delivered.
            # Evidence: a 4 601 B `gnss_status_callback` line with satellites=53 was silently
            # dropped and made `9-gnss` read "samples=0" while the app was receiving status every
            # second (`gnss: synthetic onSvStatusChanged passed the truth gate (50 so far, …)`).
            # `kind` sits late in the payload, so a truncated line loses it while `source` (which
            # comes first) survives. Derive the kind from the source tag rather than dropping the
            # sample: `gnss_status_callback` is how the verifier names the GNSS status callback.
            $kind = $null
            if ($json -match '"kind"\s*:\s*"([^"]+)"') {
                $kind = $Matches[1]
            } else {
                $srcProbe = [regex]::Match($json, '"source"\s*:\s*"([^"]*)"')
                if (-not $srcProbe.Success) { continue }
                $src = $srcProbe.Groups[1].Value.ToLower()
                $kind = if ($src -like '*gnss*') { 'gnss' }
                        elseif ($src -like '*step*') { 'stepCounter' }
                        elseif ($src -like '*location*') { 'location' }
                        else { continue }
            }
            $obj = [pscustomobject]@{ kind = $kind; truncated = $true }
            foreach ($field in @('source', 'provider', 'satellites', 'usedInFix', 'maxCn0', 'ts')) {
                $m = [regex]::Match($json, '"' + $field + '"\s*:\s*(?:"([^"]*)"|(-?[0-9]+(?:\.[0-9]+)?))')
                if (-not $m.Success) { continue }
                $raw = if ($m.Groups[1].Success) { $m.Groups[1].Value } else { $m.Groups[2].Value }
                $value = $raw
                if ($field -in @('satellites', 'usedInFix', 'ts')) { $value = [long]$raw }
                elseif ($field -eq 'maxCn0') { $value = [double]$raw }
                $obj | Add-Member -NotePropertyName $field -NotePropertyValue $value
            }
            if (-not $obj.PSObject.Properties['ts']) {
                # `ts` sits at the END of the verifier's JSON, so a truncated line never carries it.
                # Keep the sample (the scalar fields above are what any check asserts) and let the
                # AfterTs filter pass it through, because dropping it is exactly the bug this branch
                # exists to fix. Get-VerifyMark ignores ts-less samples for the same reason.
                $obj | Add-Member -NotePropertyName noTs -NotePropertyValue $true
            }
        }
        if ($Kind -and $obj.kind -ne $Kind) { continue }
        if ($Source -and ("$($obj.source)" -notlike "*$Source*")) { continue }
        if ($Provider -and $obj.provider -ne $Provider) { continue }
        if ($AfterTs -gt 0 -and $obj.PSObject.Properties['ts'] -and $obj.ts -le $AfterTs) { continue }
        $out.Add($obj)
    }
    return @($out)
}

# Highest VERIFY timestamp currently in the buffer; use as a cursor before acting.
function Get-VerifyMark {
    $samples = @(Get-VerifySamples -TailLines 20000)
    if ($samples.Count -eq 0) { return [long]0 }
    # ts-less samples are salvaged truncated lines (see Get-VerifySamples); they must not poison the mark.
    $stamped = @($samples | Where-Object { $_.PSObject.Properties['ts'] })
    if ($stamped.Count -eq 0) { return [long]0 }
    return [long](($stamped | Measure-Object -Property ts -Maximum).Maximum)
}

function Get-LastVerifierValue {
    param([Parameter(Mandatory)][string]$Kind, [long]$AfterTs = 0)
    $items = @(Get-VerifySamples -Kind $Kind -AfterTs $AfterTs)
    if ($items.Count -eq 0) { return $null }
    return $items[-1]
}

# only the streaming callbacks (requestLocationUpdates), which is what proves injection
function Get-LiveLocationSamples {
    param([long]$AfterTs = 0, [string]$Provider = '')
    return @(Get-VerifySamples -Kind 'location' -Source 'onLocationChanged' -Provider $Provider -AfterTs $AfterTs)
}

# Any location sample, including last-known (used for a quick "did anything get spoofed" check).
function Get-AnyLocationSamples {
    param([long]$AfterTs = 0, [string]$Provider = '')
    return @(Get-VerifySamples -Kind 'location' -Provider $Provider -AfterTs $AfterTs)
}

# ---------------------------------------------------------------------------------------------
# geodesy (mirrors :core GeoMath so the assertions use the same formulas)
# ---------------------------------------------------------------------------------------------
function Get-GeoDistanceMeters {
    param([double]$Lat1, [double]$Lon1, [double]$Lat2, [double]$Lon2)
    $r = 6371008.8
    $p1 = $Lat1 * [Math]::PI / 180.0
    $p2 = $Lat2 * [Math]::PI / 180.0
    $dp = ($Lat2 - $Lat1) * [Math]::PI / 180.0
    $dl = ($Lon2 - $Lon1) * [Math]::PI / 180.0
    $a = [Math]::Sin($dp / 2) * [Math]::Sin($dp / 2) +
    [Math]::Cos($p1) * [Math]::Cos($p2) * [Math]::Sin($dl / 2) * [Math]::Sin($dl / 2)
    return 2 * $r * [Math]::Atan2([Math]::Sqrt($a), [Math]::Sqrt(1 - $a))
}

function Get-GeoBearingDegrees {
    param([double]$Lat1, [double]$Lon1, [double]$Lat2, [double]$Lon2)
    $p1 = $Lat1 * [Math]::PI / 180.0
    $p2 = $Lat2 * [Math]::PI / 180.0
    $dl = ($Lon2 - $Lon1) * [Math]::PI / 180.0
    $y = [Math]::Sin($dl) * [Math]::Cos($p2)
    $x = [Math]::Cos($p1) * [Math]::Sin($p2) - [Math]::Sin($p1) * [Math]::Cos($p2) * [Math]::Cos($dl)
    $deg = [Math]::Atan2($y, $x) * 180.0 / [Math]::PI
    if ($deg -lt 0) { $deg += 360.0 }
    return $deg
}

function Get-AngleDelta {
    param([double]$From, [double]$To)
    $d = $To - $From
    while ($d -gt 180) { $d -= 360 }
    while ($d -lt -180) { $d += 360 }
    return $d
}

# Distance between the first and the last sample of a series (walked distance along a bearing).
function Measure-Track {
    param([array]$Samples = @())
    $Samples = @($Samples | Where-Object { $null -ne $_ })
    if ($Samples.Count -lt 2) {
        return [pscustomobject]@{
            count = $Samples.Count; meters = 0.0; bearing = 0.0; seconds = 0.0
            fromLat = 0.0; fromLon = 0.0; toLat = 0.0; toLon = 0.0; samples = $Samples
        }
    }
    $first = $Samples[0]
    $last = $Samples[-1]
    $meters = Get-GeoDistanceMeters -Lat1 $first.lat -Lon1 $first.lon -Lat2 $last.lat -Lon2 $last.lon
    $bearing = Get-GeoBearingDegrees -Lat1 $first.lat -Lon1 $first.lon -Lat2 $last.lat -Lon2 $last.lon
    $seconds = ([double]$last.ts - [double]$first.ts) / 1000.0
    return [pscustomobject]@{
        count    = $Samples.Count
        fromLat  = $first.lat
        fromLon  = $first.lon
        toLat    = $last.lat
        toLon    = $last.lon
        meters   = [Math]::Round($meters, 2)
        bearing  = [Math]::Round($bearing, 1)
        seconds  = [Math]::Round($seconds, 2)
        samples  = $Samples
    }
}

# Polls the verifier stream until at least one new location sample arrives (or timeout).
function Wait-LocationSample {
    param([long]$AfterTs, [int]$TimeoutSec = 12, [string]$Provider = '')
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = Get-LiveLocationSamples -AfterTs $AfterTs -Provider $Provider
        if ($s.Count -gt 0) { return $s }
        $s = Get-AnyLocationSamples -AfterTs $AfterTs -Provider $Provider
        if ($s.Count -gt 0) { return $s }
        Start-Sleep -Milliseconds 800
    }
    return @()
}

# Waits until any location sample lands within $ToleranceMeters of the target, WITHOUT sending any
# command (read-only assertion, e.g. "did tapping a favourite really move us back?").
function Wait-PositionNear {
    param(
        [Parameter(Mandatory)][double]$Lat,
        [Parameter(Mandatory)][double]$Lon,
        [double]$ToleranceMeters = 80.0,
        [int]$TimeoutSec = 12
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $last = $null
    while ((Get-Date) -lt $deadline) {
        foreach ($s in @(Get-AnyLocationSamples)) {
            $last = $s
            $d = Get-GeoDistanceMeters -Lat1 $Lat -Lon1 $Lon -Lat2 $s.lat -Lon2 $s.lon
            if ($d -le $ToleranceMeters) {
                return [pscustomobject]@{ Ok = $true; Sample = $s; Distance = [Math]::Round($d, 1) }
            }
        }
        Start-Sleep -Milliseconds 800
    }
    $dist = if ($last) { [Math]::Round((Get-GeoDistanceMeters -Lat1 $Lat -Lon1 $Lon -Lat2 $last.lat -Lon2 $last.lon), 1) } else { -1 }
    return [pscustomobject]@{ Ok = $false; Sample = $last; Distance = $dist }
}

function Test-VerifierInstalled {
    return (Test-PackageInstalled $script:VerifierPackage)
}

# ---- framework-side ground truth ---------------------------------------------------------------
# `dumpsys location` shows the fix the framework itself last produced ("last location=Location[…]"),
# which is authoritative for the *system_server* path.  Needed because the client-side stream to an
# app can be filtered/stale: the engine may be moving while the delivered stream is not.
function Get-DumpsysFix {
    $r = Invoke-AdbShell 'dumpsys location' -AllowFailure
    $fix = @{}
    foreach ($line in $r.Output) {
        $provider = $null
        $lat = 0.0
        $lon = 0.0
        $vel = $null
        $bear = $null
        if ($line -match 'last location=Location\[\s*(\w+)\s+(-?\d+\.\d+),(-?\d+\.\d+)') {
            $provider = $matches[1]
            $lat = [double]$matches[2]
            $lon = [double]$matches[3]
            if ($line -match 'vel=(-?\d+(?:\.\d+)?)') { $vel = [double]$matches[1] }
            if ($line -match 'bear=(\d+(?:\.\d+)?)') { $bear = [double]$matches[1] }
        } elseif ($line -match '^\s*(passive|gps|network|fused)\s+(-?\d+\.\d+),(-?\d+\.\d+)\s*$') {
            # HyperOS prints the provider cache as "<provider> <lat>,<lon>" with no
            # `last location=Location[...]` wrapper. Without this branch every dumpsys snapshot came
            # back empty, which is why check 5 reported "advanced 0 m" for a route the engine tick log
            # showed moving at 6.0 m/s.
            $provider = $matches[1]
            $lat = [double]$matches[2]
            $lon = [double]$matches[3]
        } else {
            continue
        }
        if ($fix.ContainsKey($provider)) { continue }   # first occurrence per provider = most recent
        if ($lat -eq 0 -and $lon -eq 0) { continue }   # placeholder fix, not a real position
        $fix[$provider] = [pscustomobject]@{
            provider = $provider
            lat      = $lat
            lon      = $lon
            vel      = $vel
            bearing  = $bear
            ts       = (Get-Date)
        }
    }
    return $fix
}

# Distance / speed between two Get-DumpsysFix snapshots (same provider when possible).
function Measure-DumpsysTrack {
    param($Start, $End, [string]$Provider = 'gps')
    $empty = [pscustomobject]@{ ok = $false; provider = ''; meters = 0.0; seconds = 0.0; rate = 0.0; bearing = 0.0; vel = $null; fromLat = 0; fromLon = 0; toLat = 0; toLon = 0 }
    if (-not $Start -or -not $End) { return $empty }
    $p = $Provider
    if (-not ($Start.ContainsKey($p) -and $End.ContainsKey($p))) {
        $p = ''
        foreach ($candidate in @('fused', 'gps', 'network', 'passive')) {
            if ($Start.ContainsKey($candidate) -and $End.ContainsKey($candidate)) { $p = $candidate; break }
        }
    }
    if (-not $p) { return $empty }
    $a = $Start[$p]
    $b = $End[$p]
    $meters = Get-GeoDistanceMeters -Lat1 $a.lat -Lon1 $a.lon -Lat2 $b.lat -Lon2 $b.lon
    $seconds = (New-TimeSpan -Start $a.ts -End $b.ts).TotalSeconds
    $bearing = Get-GeoBearingDegrees -Lat1 $a.lat -Lon1 $a.lon -Lat2 $b.lat -Lon2 $b.lon
    $rate = if ($seconds -gt 0.2) { $meters / $seconds } else { 0.0 }
    return [pscustomobject]@{
        ok       = $true
        provider = $p
        fromLat  = $a.lat; fromLon = $a.lon
        toLat    = $b.lat; toLon = $b.lon
        meters   = [Math]::Round($meters, 2)
        seconds  = [Math]::Round($seconds, 2)
        rate     = [Math]::Round($rate, 3)
        bearing  = [Math]::Round($bearing, 1)
        vel      = $b.vel
    }
}
