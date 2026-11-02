<#
.SYNOPSIS
    Activate the ImmoralLocation LSPosed module from the host, without opening the LSPosed manager UI.

.DESCRIPTION
    LSPosed keeps its whole state in one SQLite database:

        /data/adb/lspd/config/modules_config.db

    and the device has no `sqlite3` binary, so activation is done offline:

        1. stop lspd                (so the WAL is checkpointed and the file is consistent)
        2. pull  modules_config.db  (adb pull => binary safe)
        3. patch it on the host     (e2e/tools/lsposed_db.py, pure python sqlite3)
        4. push it back             (root:root, mode 600, stale -wal/-shm removed)
        5. restart lspd             (daemon respawn, no reboot needed for app scopes)
        6. optional reboot          (REQUIRED for the `system` scope: the framework hook only
                                     loads into a fresh system_server)

    Scope naming gotcha: the LSPosed *manager UI* shows the framework entry as "android", but in
    the database the row is app_pkg_name = 'system'.  This script accepts either spelling in
    -Packages and always stores 'system'.

    The script is idempotent: rows are inserted or left untouched, never duplicated, and a copy
    of the previous database is kept in e2e/out/lsposed/.

.PARAMETER Packages
    Comma separated scope list.  Special values: android|framework|system_server => 'system'.
    Default: android + com.android.phone (OS components only — the module never hooks app processes; com.android.phone hosts PhoneInterfaceManager, without it cell spoofing is silently dead).

.PARAMETER ModulePackage
    Module applicationId.  Default: autodetect (io.github.twtwilight.immorallocation.debug, else com.immoral.location).

.PARAMETER Users
    Android user ids to enable.  Default 0.

.PARAMETER NoReboot
    Patch + restart lspd, but do not reboot.  App-process scopes become active as soon as the
    scoped app restarts; the `system` scope stays dormant until the next system_server start.

.PARAMETER Prune
    Delete scope rows that are not listed in -Packages (the module scope becomes exactly the
    requested list instead of a superset).

.PARAMETER VerifyOnly
    Do not modify anything: pull the database, print the verification result, read logcat.

.PARAMETER Dump
    Print the database contents (modules / modules_state / scope) before finishing.

.EXAMPLE
    .\e2e\activate_module.ps1
    Activates the default scope (framework + our app + verifier) and reboots.

.EXAMPLE
    .\e2e\activate_module.ps1 -Packages android,io.github.twtwilight.immorallocation.debug,com.yunzhi.tiyu -NoReboot
    Adds a test app and skips the reboot (framework scope not yet live).

.EXAMPLE
    .\e2e\activate_module.ps1 -VerifyOnly -Dump
    Read-only check of what LSPosed currently has, e.g. after a reboot.

.OUTPUTS
    Exit code 0 = activated and verified, 1 = failure, 2 = database mismatch.

.NOTES
    Requires: adb, root (KernelSU `su -c`), python on PATH, LSPosed 2.x installed as a Zygisk
    module with its daemon files under /data/adb/modules/zygisk_lsposed/.
#>
[CmdletBinding()]
param(
    # Framework-only by product decision: the module never hooks a third-party app process, so the
    # default scope is exactly the framework ("android" is stored as "system"). Add app packages
    # explicitly only for a deliberate experiment, and say why in the report.
    [string]$Packages = 'android,com.android.phone',
    [string]$ModulePackage = '',
    [int[]]$Users = @(0),
    [string]$Serial = '',
    [switch]$NoReboot,
    [switch]$Prune,
    [switch]$VerifyOnly,
    [switch]$Dump,
    [int]$ReadyTimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')

$remoteDb = '/data/adb/lspd/config/modules_config.db'
$lsposedModuleDir = '/data/adb/modules/zygisk_lsposed'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$workDir = Join-Path $OutDir 'lsposed'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$localDb = Join-Path $workDir 'modules_config.db'
$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) { throw 'python not found on PATH (needed by e2e/tools/lsposed_db.py)' }
$dbTool = Join-Path $RepoRoot 'e2e\tools\lsposed_db.py'

function Show-Python {
    param([string[]]$pyArgs)
    & $python @pyArgs | ForEach-Object { Write-Host "  $_" }
    return $LASTEXITCODE
}

function Get-ModulePackage {
    # Debug first (that is what the matrix installs), then the release id, so a device carrying the
    # shipping artefact can be activated without hand-passing -ModulePackage.
    foreach ($candidate in @(
            'io.github.twtwilight.immorallocation.debug',
            'io.github.twtwilight.immorallocation',
            'com.immoral.location')) {
        if (Test-PackageInstalled $candidate) { return $candidate }
    }
    throw 'ImmoralLocation is not installed on the device - install app-debug.apk or app-release.apk first'
}

function Stop-Lspd {
    Invoke-AdbRoot 'killall lspd' -AllowFailure | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        $r = Invoke-AdbRoot 'ps -A -o PID,NAME | grep -w lspd' -AllowFailure
        if (-not (($r.Output -join '').Trim())) { return }
        Start-Sleep -Milliseconds 300
    }
    Write-Warn2 'lspd is still running after killall'
}

function Start-Lspd {
    # post-fs-data.sh runs exactly this at boot; doing it by hand restarts the daemon without a reboot.
    Invoke-AdbRoot "nohup $lsposedModuleDir/daemon >/dev/null 2>&1 &" -AllowFailure | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
        $r = Invoke-AdbRoot 'ps -A -o PID,NAME | grep -w lspd' -AllowFailure
        if (($r.Output -join '').Trim()) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-LsposedEvidence {
    # tag-filtered inside logcatd, so this is cheap enough to poll
    return @(Get-LsposedLog -TailLines 4000 | Where-Object {
            $_ -match 'ImmoralLocation|com\.immoral\.location|LSPosed.*module'
        })
}

function Get-ActivationLine {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $line = Get-LsposedEvidence | Where-Object { $_ -match 'ImmoralLocation' } | Select-Object -Last 1
        if ($line) { return $line }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    return $null
}

# ------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'ImmoralLocation - LSPosed activation' -ForegroundColor Magenta
Write-Host '===================================' -ForegroundColor Magenta

Select-AndroidDevice -Serial $Serial -Wait | Out-Null
$summary = Get-DeviceSummary
Write-Step ("device {0} ({1}) Android {2} / SDK {3}" -f $summary.serial, $summary.model, $summary.release, $summary.sdk)
if (-not (Test-Root)) { throw 'root is required (KernelSU `su -c id` must print uid=0)' }

if ($ModulePackage) {
    if (-not (Test-PackageInstalled $ModulePackage)) { throw "module package $ModulePackage is not installed" }
}
else {
    $ModulePackage = Get-ModulePackage
}
$apkPath = Get-PackageApkPath $ModulePackage
Write-Step "module $ModulePackage -> $apkPath"

$scopeList = @($Packages -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$resolvedScope = @($scopeList | ForEach-Object {
        if ($_ -match '^(?i)(android|framework|system_server)$') { 'system' } else { $_ }
    } | Select-Object -Unique)
Write-Step ("scope: {0}" -f ($resolvedScope -join ', '))
foreach ($pkg in $resolvedScope) {
    if ($pkg -eq 'system') { continue }
    if (-not (Test-PackageInstalled $pkg)) { Write-Warn2 "scope package $pkg is not installed (row will be inert)" }
}
if ($resolvedScope -contains 'system' -and $NoReboot) {
    Write-Warn2 '-NoReboot given: the "system" scope is NOT live until the next system_server start'
}

# ------------------------------------------------------------------------------------------------
# read-only mode
# ------------------------------------------------------------------------------------------------
if ($VerifyOnly) {
    Write-Step 'verify-only: pulling current database'
    Invoke-AdbRoot "cp $remoteDb /data/local/tmp/e2e_modules_config.db" | Out-Null
    Invoke-Adb pull '/data/local/tmp/e2e_modules_config.db' $localDb 2>&1 | Out-Null
    $checkArgs = @($dbTool, 'check', '--db', $localDb, '--module', $ModulePackage, '--apk', $apkPath,
        '--packages', ($resolvedScope -join ','))
    foreach ($u in $Users) { $checkArgs += @('--users', "$u") }
    $check = Show-Python $checkArgs
    if ($Dump) { Show-Python @($dbTool, 'dump', '--db', $localDb) | Out-Null }
    Write-Step 'logcat evidence:'
    Get-LsposedEvidence | Select-Object -Last 25 | ForEach-Object { Write-Host "  $_" }
    exit $check
}

# ------------------------------------------------------------------------------------------------
# 1. stop daemon, 2. pull, 3. patch, 4. push, 5. restart
# ------------------------------------------------------------------------------------------------
Write-Step 'stopping lspd so the database is quiescent'
Stop-Lspd

Write-Step 'pulling modules_config.db'
Invoke-AdbRoot "cp $remoteDb /data/local/tmp/e2e_modules_config.db" | Out-Null
$pull = Invoke-Adb pull '/data/local/tmp/e2e_modules_config.db' $localDb
if ($pull.Code -ne 0) { throw "adb pull failed: $($pull.Output -join "`n")" }
$backup = Join-Path $workDir "modules_config.$stamp.db"
Copy-Item $localDb $backup -Force
Write-Step "backup: $backup ($((Get-Item $localDb).Length) bytes)"

Write-Step 'patching database on the host'
$pyArgs = @($dbTool, 'apply', '--db', $localDb, '--module', $ModulePackage, '--apk', $apkPath,
    '--packages', ($resolvedScope -join ','), '--enabled')
foreach ($u in $Users) { $pyArgs += @('--users', "$u") }
if ($Prune) { $pyArgs += '--prune' }
if ((Show-Python $pyArgs) -ne 0) { throw 'lsposed_db.py apply failed' }

Write-Step 'pushing database back'
Invoke-Adb push $localDb '/data/local/tmp/e2e_modules_config.db' | Out-Null
$pushScript = @(
    "cp /data/local/tmp/e2e_modules_config.db $remoteDb",
    "rm -f $remoteDb-wal $remoteDb-shm",
    "chown root:root $remoteDb",
    "chmod 600 $remoteDb",
    "ls -l $remoteDb"
) -join ' && '
$r = Invoke-AdbRoot $pushScript
if ($r.Code -ne 0) { throw "failed to install database: $($r.Output -join "`n")" }
$r.Output | Where-Object { $_ -match 'modules_config' } | ForEach-Object { Write-Host "  $_" }

Write-Step 'restarting lspd'
if (-not (Start-Lspd)) { throw 'lspd did not restart' }
$r = Invoke-AdbRoot 'ps -A -o PID,NAME | grep -w lspd'
Write-Ok ("lspd running: " + (($r.Output -join ' ').Trim()))

# ------------------------------------------------------------------------------------------------
# 6. reboot if the framework scope must go live
# ------------------------------------------------------------------------------------------------
$frameworkLine = $null
if (-not $NoReboot) {
    Reboot-Device -TimeoutSec $ReadyTimeoutSec
    Write-Step 'collecting activation evidence (logcat tag LSPosed / [ImmoralLocation])'
    $frameworkLine = Get-ActivationLine -TimeoutSec 90
}
else {
    Write-Step 'collecting activation evidence (app scopes only, no reboot)'
    $frameworkLine = Get-ActivationLine -TimeoutSec 15
}

# ------------------------------------------------------------------------------------------------
# final verification: pull the database again and compare with what we asked for
# ------------------------------------------------------------------------------------------------
Write-Step 'verifying database state on the device'
Invoke-AdbRoot "cp $remoteDb /data/local/tmp/e2e_modules_config.db" | Out-Null
Invoke-Adb pull '/data/local/tmp/e2e_modules_config.db' $localDb 2>&1 | Out-Null
$verifyArgs = @($dbTool, 'check', '--db', $localDb, '--module', $ModulePackage, '--apk', $apkPath,
    '--packages', ($resolvedScope -join ','))
foreach ($u in $Users) { $verifyArgs += @('--users', "$u") }
$check = Show-Python $verifyArgs
if ($Dump) { Show-Python @($dbTool, 'dump', '--db', $localDb) | Out-Null }

Write-Host ''
if ($check -ne 0) {
    Write-Err2 'activation FAILED (database does not match the request)'
    exit 2
}
if ($frameworkLine) {
    Write-Ok 'hook loaded in system_server (framework scope live):'
    Write-Host "  $frameworkLine"
}
elseif (-not $NoReboot) {
    Write-Warn2 'database is correct but no [ImmoralLocation] line appeared in logcat.'
    Write-Warn2 'Check the hook APK (assets/xposed_init, XposedEntry) and: adb logcat -s LSPosed'
}
Write-Host ''
Write-Ok 'LSPosed activation complete'
Write-Host ''
Write-Host 'How to verify by hand:' -ForegroundColor Magenta
Write-Host '  adb logcat -s LSPosedFramework:I | Select-String ImmoralLocation'
Write-Host '  adb shell su -c "cat /data/adb/lspd/log/*"'
Write-Host "  python e2e/tools/lsposed_db.py dump --db $localDb"
Write-Host ''
exit 0

