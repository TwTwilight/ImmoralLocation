# e2e/lib/common.ps1 — shared adb helpers for the ImmoralLocation acceptance harness.
#
# Dot-source it:  . "$PSScriptRoot\..\lib\common.ps1"   (or the absolute path)
#
# Conventions
#   * every helper writes progress with Write-Step (cyan) and never pollutes the pipeline
#     unless it returns a value on purpose;
#   * device output is trimmed and returned as an array of lines, so callers can filter;
#   * exit codes: helpers throw, the entry scripts translate failures into exit 1/2.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:OutDir = Join-Path $script:RepoRoot 'e2e\out'
$script:AdbSerial = if ($env:ANDROID_SERIAL) { $env:ANDROID_SERIAL } else { '' }

if (-not (Test-Path $script:OutDir)) { New-Item -ItemType Directory -Force -Path $script:OutDir | Out-Null }

# ---------------------------------------------------------------------------------------------
# output helpers
# ---------------------------------------------------------------------------------------------
function Write-Step([string]$Message) {
    Write-Host "[e2e] $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[ ok] $Message" -ForegroundColor Green
}

function Write-Warn2([string]$Message) {
    Write-Host "[warn] $Message" -ForegroundColor Yellow
}

function Write-Err2([string]$Message) {
    Write-Host "[fail] $Message" -ForegroundColor Red
}

# ---------------------------------------------------------------------------------------------
# adb plumbing
# ---------------------------------------------------------------------------------------------
function Select-AndroidDevice {
    param([string]$Serial, [switch]$Wait)
    $devices = @((& adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\s+device\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
    if ($Serial) {
        if ($devices -notcontains $Serial) { throw "device $Serial not attached (available: $($devices -join ', '))" }
        $script:AdbSerial = $Serial
        return $Serial
    }
    if ($script:AdbSerial -and $devices -contains $script:AdbSerial) { return $script:AdbSerial }
    if ($devices.Count -eq 0) {
        if ($Wait) {
            Write-Step 'waiting for a device...'
            & adb wait-for-device | Out-Null
            return Select-AndroidDevice -Serial $Serial -Wait:$Wait
        }
        throw 'no adb device attached (enable USB debugging or start the emulator)'
    }
    $script:AdbSerial = $devices[0]
    if ($devices.Count -gt 1) { Write-Warn2 "multiple devices, using $($script:AdbSerial)" }
    return $script:AdbSerial
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $prefix = @()
    if ($script:AdbSerial) { $prefix = @('-s', $script:AdbSerial) }
    $out = & adb @prefix @Args 2>&1
    $code = $LASTEXITCODE
    return [pscustomobject]@{ Code = $code; Output = @($out | ForEach-Object { "$_" }) }
}

function Invoke-AdbShell {
    param([string]$Command, [switch]$AllowFailure)
    $r = Invoke-Adb shell $Command
    if (-not $AllowFailure -and $r.Code -ne 0) {
        throw "adb shell failed ($($r.Code)): $Command`n$($r.Output -join "`n")"
    }
    return $r
}

# Runs a command as root.  The device uses KernelSU, `su -c` works for the shell user.
function Invoke-AdbRoot {
    param([string]$Command, [switch]$AllowFailure)
    $escaped = $Command.Replace('"', '\"')
    $remote = 'su -c "' + $escaped + '"'
    $r = Invoke-Adb shell $remote
    if (-not $r.Code -and -not $AllowFailure) {
        $text = ($r.Output -join "`n")
        if ($text -match 'Permission denied|not found|No such file|inaccessible') {
            throw "root command failed: $Command`n$text"
        }
    }
    return $r
}

function Test-Root {
    # `su -c` occasionally fails transiently (adb hiccup, KernelSU busy); a single false negative
    # used to flip a whole command onto the unprivileged shell user mid-run.
    for ($i = 0; $i -lt 3; $i++) {
        $r = Invoke-AdbRoot 'id' -AllowFailure
        if ((($r.Output -join ' ') -match 'uid=0')) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

function Get-DeviceProp([string]$Name) {
    $r = Invoke-AdbShell "getprop $Name" -AllowFailure
    return (($r.Output -join "`n").Trim())
}

function Wait-ForBoot {
    param([int]$TimeoutSec = 300)
    Write-Step 'waiting for boot_completed...'
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $lastBeat = Get-Date
    $lastAdbReset = Get-Date
    $resets = 0
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-AdbShell 'getprop sys.boot_completed' -AllowFailure
        if ((($r.Output -join '').Trim()) -eq '1') {
            # boot_completed=1 arrives before the framework settles; give package manager a moment.
            Start-Sleep -Seconds 3
            Write-Ok 'device booted'
            return $true
        }
        if (((Get-Date) - $lastBeat).TotalSeconds -ge 10) {
            # Visible heartbeat: a silent wait is indistinguishable from a hung adb, and that cost a
            # 20-minute stall once. Never poll a background job around this - the dots are the progress.
            Write-Host ("  ... still waiting for boot ({(0:N0}s elapsed))" -f ((Get-Date) - ($deadline.AddSeconds(-$TimeoutSec))).TotalSeconds)
            $lastBeat = Get-Date
        }
        # adb can wedge while the device is booting: recreate the server rather than waiting it out.
        if ($resets -lt 2 -and ((Get-Date) - $lastAdbReset).TotalSeconds -ge 45) {
            Write-Warn2 'no boot signal yet - restarting the adb server once'
            Restart-AdbServer -TimeoutSec 60 | Out-Null
            $resets++
            $lastAdbReset = Get-Date
        }
        Start-Sleep -Seconds 2
    }
    throw "device did not finish booting within $TimeoutSec s"
}

function Restart-AdbServer {
    param([int]$TimeoutSec = 180)
    & adb kill-server 2>&1 | Out-Null
    Start-Sleep -Milliseconds 800
    & adb start-server 2>&1 | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $ok = (& adb devices) | Where-Object { $_ -match '\s+device\s*$' }
        if ($ok) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Reboot-Device {
    param([int]$TimeoutSec = 300)
    Write-Step 'rebooting device (framework scope only takes effect after a system_server restart)'
    Invoke-Adb reboot | Out-Null
    Start-Sleep -Seconds 5
    # adb server can wedge while the device is down; recreate it, then wait.
    if (-not (Restart-AdbServer -TimeoutSec $TimeoutSec)) { throw 'device did not come back after reboot' }
    Wait-ForBoot -TimeoutSec $TimeoutSec | Out-Null
}

# ---------------------------------------------------------------------------------------------
# packages
# ---------------------------------------------------------------------------------------------
function Test-PackageInstalled([string]$Package) {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $r = Invoke-AdbShell "pm list packages $Package" -AllowFailure
        $text = ($r.Output -join "`n")
        if ($text -match "Can't find service|Failure \[" -or $text -match '^cmd: ') {
            Start-Sleep -Seconds 3          # system_server is (re)starting
            continue
        }
        # (?m) matters: `$` is end-of-string by default, so `pm list packages com.android.phone`
        # (which also returns …phone.overlay.common, …phone.auto_generated_…_rro) never matched and the
        # harness printed "scope package com.android.phone is not installed (row will be inert)" on
        # every run while the row was patched and the phone process was demonstrably hooked.
        return ($text -match ("(?m)^package:" + [regex]::Escape($Package) + "\s*$"))
    }
    return $false
}

# After a reboot or a system_server restart the package service comes up later than adb itself.
function Wait-PackageService {
    param([int]$TimeoutSec = 150)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-AdbShell 'pm path android' -AllowFailure
        if (($r.Output -join '') -match '^package:') { return $true }
        Start-Sleep -Seconds 2
    }
    throw "package manager did not become available within $TimeoutSec s"
}

function Get-PackageApkPath([string]$Package) {
    $r = Invoke-AdbRoot "pm path $Package" -AllowFailure
    $line = ($r.Output | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    if (-not $line) {
        $r2 = Invoke-AdbShell "pm path $Package" -AllowFailure
        $line = ($r2.Output | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    }
    if (-not $line) { throw "package $Package is not installed" }
    return ($line -replace '^package:', '').Trim()
}

function Install-Apk {
    param([string]$Apk, [switch]$Reinstall, [string[]]$GrantPermissions = @(
            'android.permission.ACCESS_FINE_LOCATION',
            'android.permission.ACCESS_COARSE_LOCATION',
            'android.permission.ACTIVITY_RECOGNITION',
            'android.permission.READ_PHONE_STATE',
            'android.permission.POST_NOTIFICATIONS'
        ))
    if (-not (Test-Path $Apk)) { throw "apk not found: $Apk" }
    Write-Step "installing $(Split-Path -Leaf $Apk)"
    # one flat argument list: splatting several arrays after a literal silently reorders the args
    $installArgs = [System.Collections.Generic.List[string]]::new()
    $installArgs.AddRange([string[]]@('install', '-r', '-g'))
    if ($Reinstall) { $installArgs.Add('-d') }
    $installArgs.Add($Apk)
    $r = Invoke-Adb -Args $installArgs.ToArray()
    if ($r.Code -ne 0 -or ($r.Output -join "`n") -match 'Failure') {
        throw "adb install failed: $($r.Output -join "`n")"
    }
}

function Grant-Permissions {
    param([string]$Package, [string[]]$Permissions = @(
            'android.permission.ACCESS_FINE_LOCATION',
            'android.permission.ACCESS_COARSE_LOCATION',
            'android.permission.ACTIVITY_RECOGNITION',
            'android.permission.READ_PHONE_STATE',
            'android.permission.POST_NOTIFICATIONS'
        ),
        [string[]]$AppOps = @(
            'android:mock_location',
            'android:coarse_location',
            'android:fine_location',
            'android:activity_recognition'
        ))
    foreach ($p in $Permissions) {
        Invoke-AdbShell "pm grant $Package $p" -AllowFailure | Out-Null
    }
    foreach ($op in $AppOps) {
        Invoke-AdbShell "appops set $Package $op allow" -AllowFailure | Out-Null
    }
}

function Force-Stop([string]$Package) {
    Invoke-AdbShell "am force-stop $Package" -AllowFailure | Out-Null
}

# ---------------------------------------------------------------------------------------------
# logcat
# ---------------------------------------------------------------------------------------------
function Clear-Logcat {
    Invoke-AdbShell 'logcat -b main -b system -c' -AllowFailure | Out-Null
}

# Reads logcat since a marker.  With -Clear it starts a fresh buffer first.
# `-s TAG:V` filtering happens inside logcatd, so this stays fast even on a busy device.
function Get-Logcat {
    param([string[]]$Tag, [int]$TailLines = 20000, [switch]$Clear)
    if ($Clear) { Clear-Logcat }
    $args = @('-d')
    if ($Tag) { $args += @('-s'); $args += @($Tag | ForEach-Object { "${_}:V" }) }
    $args += @('-t', "$TailLines")
    $r = Invoke-Adb @('logcat') @args
    return @($r.Output)
}

# LSPosed mirrors `XposedBridge.log` output with these prefixes:
#   LSPosedFramework: (system)[com.pkg,XposedBridge,...] [ImmoralLocation] message   <- system_server
#   LSPosed         : (app)[com.pkg,...] [ImmoralLocation] message                   <- scoped app
$script:LsposedLogTags = @('LSPosedFramework', 'LSPosed', 'Xposed', 'XposedBridge')

function Get-LsposedLog {
    param([int]$TailLines = 20000)
    return @(Get-Logcat -Tag $script:LsposedLogTags -TailLines $TailLines)
}

function Get-HookLog {
    param([string]$Pattern = 'ImmoralLocation', [int]$TailLines = 20000)
    return @(Get-LsposedLog -TailLines $TailLines | Where-Object { $_ -match $Pattern })
}

# True when the framework layer announced itself from system_server in this boot.
# Tolerant of buffer roll: also accepts the prefs-channel / ticker / Fakes lines.
function Test-FrameworkHookLive {
    param([int]$TailLines = 40000)
    $hits = @(Get-LsposedLog -TailLines $TailLines | Where-Object {
            $_ -match 'ImmoralLocation' -and
            ($_ -match '\(system\)' -or $_ -match 'ticker started' -or $_ -match 'Fakes:' -or $_ -match 'loadPackage android')
        })
    return [pscustomobject]@{ Live = ($hits.Count -gt 0); Count = $hits.Count; Last = ($hits | Select-Object -Last 1) }
}

function Get-VerifyLines {
    param([string]$Kind, [int]$Limit = 500)
    $raw = Get-Logcat -Tag 'ImmoralVerifier' -TailLines 40000
    $items = foreach ($line in $raw) {
        $idx = $line.IndexOf('VERIFY ')
        if ($idx -lt 0) { continue }
        $json = $line.Substring($idx + 7).Trim()
        try { $obj = $json | ConvertFrom-Json } catch { continue }
        if ($Kind -and $obj.kind -ne $Kind) { continue }
        $obj
    }
    return @($items | Select-Object -Last $Limit)
}

# ---------------------------------------------------------------------------------------------
# misc
# ---------------------------------------------------------------------------------------------
function Get-DeviceSummary {
    return [ordered]@{
        serial = $script:AdbSerial
        model  = Get-DeviceProp 'ro.product.model'
        sdk    = Get-DeviceProp 'ro.build.version.sdk'
        release = Get-DeviceProp 'ro.build.version.release'
        abi    = Get-DeviceProp 'ro.product.cpu.abi'
    }
}

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $out = & $Exe @Arguments 2>&1
    return [pscustomobject]@{ Code = $LASTEXITCODE; Output = @($out | ForEach-Object { "$_" }) }
}
