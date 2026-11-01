<#
.SYNOPSIS
    One-shot setup for device testing: build, install both APKs, activate the LSPosed module,
    verify the framework layer is live.

.DESCRIPTION
    Steps:
      1. `gradlew :app:assembleDebug :verifier:assembleDebug` (unless -SkipBuild)
      2. adb install -r -g for app-debug.apk and verifier-debug.apk
      3. e2e/activate_module.ps1 with the requested scope (reboot is REQUIRED for the framework
         scope; pass -NoReboot when the device already runs the module and you only reinstalled)
      4. read-only verification + logcat evidence

    Use this after pulling new sources.  Use `run_matrix.ps1` afterwards for the acceptance matrix.

.PARAMETER Packages
    LSPosed scope list, comma separated.  "android" is stored as "system" in the LSPosed db.

.PARAMETER NoReboot
    Skip the reboot (app-level scopes only).  The framework layer will NOT pick up a new APK.

.PARAMETER SkipBuild
    Install existing APKs from app/build/outputs and verifier/build/outputs.

.EXAMPLE
    .\e2e\install.ps1

.EXAMPLE
    .\e2e\install.ps1 -NoReboot -SkipBuild
#>
[CmdletBinding()]
param(
    [string]$Packages = 'android,com.android.phone',
    [switch]$NoReboot,
    [switch]$SkipBuild,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')

Select-AndroidDevice -Serial $Serial -Wait | Out-Null
$info = Get-DeviceSummary
Write-Step ("device {0} ({1}) Android {2} / SDK {3}" -f $info.serial, $info.model, $info.release, $info.sdk)

$appApk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$verifierApk = Join-Path $RepoRoot 'verifier\build\outputs\apk\debug\verifier-debug.apk'

# The verifier module is optional: the hook work dropped it from settings.gradle. Build/install it only
# when it is actually part of the build, otherwise keep whatever verifier is already on the device.
$settingsText = if (Test-Path (Join-Path $RepoRoot 'settings.gradle.kts')) { Get-Content (Join-Path $RepoRoot 'settings.gradle.kts') -Raw } else { '' }
$hasVerifierModule = $settingsText -match '(?m)^\s*include\(\s*":verifier"'

if (-not $SkipBuild) {
    $env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'Y:\Users\mbcloud\scoop\apps\openjdk17\current' }
    $targets = @(':app:assembleDebug')
    if ($hasVerifierModule) { $targets += ':verifier:assembleDebug' }
    Write-Step ("building {0}" -f ($targets -join ' '))
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    & $gradlew @targets '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
}

Install-Apk -Apk $appApk -Reinstall | Out-Null
if ($hasVerifierModule -and (Test-Path $verifierApk)) {
    Install-Apk -Apk $verifierApk -Reinstall | Out-Null
}
elseif (Test-Path $verifierApk) {
    # Module no longer in the build: reuse the last built APK so the device keeps a verifier.
    Install-Apk -Apk $verifierApk -Reinstall | Out-Null
}
else {
    Write-Warn2 'verifier APK not available in this tree — keeping the verifier already installed on the device'
}
Grant-Permissions -Package 'com.immoral.location.verifier'
Invoke-AdbShell 'appops set com.immoral.location.verifier android:mock_location allow' -AllowFailure | Out-Null
Grant-Permissions -Package 'io.github.twtwilight.immorallocation.debug'

# The injected `Location` carries the `immoral.*` extras only while this property is set: they are
# the harness's cheapest proof of what the engine is doing (toggles, cadence, steps), and they are a
# detectability leak in production (any app that receives a fix can read them). Default off in the
# module, switched on here for the acceptance runs.
Invoke-AdbShell 'su -c "setprop persist.immoral.extras 1"' -AllowFailure | Out-Null

$activate = Join-Path $PSScriptRoot 'activate_module.ps1'
# Explicit named arguments. Splatting @('-Packages', $Packages) where $Packages is a comma-joined list
# lands the list on the positional $ModulePackage parameter ("module package android,com.android.phone
# is not installed"); named binding is unambiguous.
if ($NoReboot) { & $activate -Packages $Packages -NoReboot }
else { & $activate -Packages $Packages }
if ($LASTEXITCODE -ne 0) { throw 'activation failed' }

Write-Step 'collecting evidence'
$lines = @(Get-HookLog -Pattern 'ImmoralLocation' -TailLines 2000)
if ($lines.Count -gt 0) {
    Write-Ok "$($lines.Count) [ImmoralLocation] log line(s); last:"
    $lines | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
}
else {
    Write-Warn2 'no [ImmoralLocation] lines yet — check `adb logcat -s LSPosedFramework:I`'
}

Write-Host ''
Write-Ok 'device ready. Next: .\e2e\run_matrix.ps1'
exit 0


