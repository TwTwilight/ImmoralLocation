# e2e/lib/checks_release.ps1 — release-artifact checks that need NO device (matrix ids 23/24).
#
# These exist because two product properties are invisible to every on-device check:
#
#   * **包体积**: the release APK must stay small (debug is ~47 MB because of Compose debug info and
#     no R8; release was measured at ~10 MB after the locale-filter change). A regression here is a
#     product regression the user notices, and it is cheap to catch in CI.
#   * **模块完整性**: R8 must keep `com.immoral.location.hook.**` and the APK must still carry
#     `assets/xposed_init` pointing at `XposedEntry`. If a future proguard/shrinker change strips the
#     hook classes, the module installs fine and silently does nothing — the worst failure mode this
#     project has. Asserting it on the built artifact (not on the source) is the only honest check.
#
# Both run on the built APKs, so they work on a CI runner and on a dev box, offline.

. (Join-Path $PSScriptRoot 'common.ps1')

$script:ReleaseApkGlob = 'app\build\outputs\apk\release'
$script:DebugApkPath = 'app\build\outputs\apk\debug\app-debug.apk'
$script:ReleaseMapping = 'app\build\outputs\mapping\release\mapping.txt'
# Tightened after the arm64-only ABI policy landed and the 高德 `assets/cfg` pack became a release-time
# exclusion (measured 6.24 MiB with `-PimmoralAmapAssets` default). The budget exists to catch a
# regression, not to rubber-stamp growth.
$script:MaxReleaseMb = 6.5

# Reads a zip entry out of an APK without unzipping it to disk.
function Get-ApkEntryText {
    param([Parameter(Mandatory)][string]$Apk, [Parameter(Mandatory)][string]$EntryName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $EntryName } | Select-Object -First 1
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Get-ApkEntryNames {
    param([Parameter(Mandatory)][string]$Apk)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try { return @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
}

# 23. module integrity on the RELEASE artifact: xposed_init present + points at XposedEntry + R8
#     kept the hook package (checked through the mapping file, which lists kept names verbatim).
function Invoke-ModuleIntegrityCheck {
    $releaseDir = Join-Path $RepoRoot $script:ReleaseApkGlob
    $apk = @(Get-ChildItem -Path $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    if ($apk.Count -eq 0) {
        Add-Result -Id '23-module-integrity' -Name 'release APK keeps the Xposed entry point and hook classes' -Status 'SKIP' `
            -Detail "no release APK found in app/build/outputs/apk/release — run .\gradlew.bat :app:assembleRelease"
        return
    }
    $apkPath = $apk[0].FullName
    $entries = @(Get-ApkEntryNames -Apk $apkPath)
    $initText = Get-ApkEntryText -Apk $apkPath -EntryName 'assets/xposed_init'
    $hasInit = [bool]($initText -or ($entries -contains 'assets/xposed_init'))
    $initPointsAtEntry = ($initText -and $initText -match 'XposedEntry')

    $mappingPath = Join-Path $RepoRoot $script:ReleaseMapping
    $hookClasses = @()
    if (Test-Path $mappingPath) {
        $hookClasses = @(Get-Content $mappingPath | Where-Object { $_ -match '^com\.immoral\.location\.hook\.[\w$.]+ ->' } |
                Select-Object -First 5)
    }
    $mappingOk = ($hookClasses.Count -gt 0)
    # the mapping only proves the class existed before shrinking; also prove the dex still carries it
    $dexHit = $false
    foreach ($name in @($entries | Where-Object { $_ -match '^classes\d*\.dex$' })) {
        $bytes = $null
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue | Out-Null
            $zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
            $entry = $zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1
            $stream = $entry.Open()
            $mem = New-Object System.IO.MemoryStream
            $stream.CopyTo($mem)
            $stream.Dispose(); $zip.Dispose()
            $bytes = $mem.ToArray(); $mem.Dispose()
        }
        catch { continue }
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        if ($text -match 'com/immoral/location/hook/XposedEntry') { $dexHit = $true; break }
    }

    $ok = $hasInit -and $initPointsAtEntry -and $mappingOk -and $dexHit
    Test-Result -Id '23-module-integrity' -Name 'release APK keeps the Xposed entry point and hook classes' -Condition $ok `
        -Detail ("$(Split-Path -Leaf $apkPath): assets/xposed_init=$hasInit ($($initText -replace '\s+', ' ')) pointsAtXposedEntry=$initPointsAtEntry mappingHookClasses=$($hookClasses.Count) dexContainsXposedEntry=$dexHit") `
        -Metrics @{ apk = $apkPath; xposedInit = $hasInit; initText = "$initText".Trim(); mappingHookClasses = $hookClasses.Count; dexContainsHook = $dexHit }
}

# 24. 包体积: release size against a budget, with the debug size for contrast.
function Invoke-ReleaseSizeCheck {
    param([double]$MaxMb = $script:MaxReleaseMb)
    $releaseDir = Join-Path $RepoRoot $script:ReleaseApkGlob
    $apk = @(Get-ChildItem -Path $releaseDir -Filter '*.apk' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    if ($apk.Count -eq 0) {
        Add-Result -Id '24-apk-size' -Name 'release APK stays within the size budget' -Status 'SKIP' `
            -Detail 'no release APK built (run .\gradlew.bat :app:assembleRelease)'
        return
    }
    $releaseMb = [Math]::Round($apk[0].Length / 1MB, 2)
    $debugPath = Join-Path $RepoRoot $script:DebugApkPath
    $debugMb = if (Test-Path $debugPath) { [Math]::Round((Get-Item $debugPath).Length / 1MB, 2) } else { -1 }
    $ok = ($releaseMb -le $MaxMb)
    Test-Result -Id '24-apk-size' -Name 'release APK stays within the size budget' -Condition $ok `
        -Detail ("release {0:N2} MB (budget {1:N1} MB) vs debug {2:N2} MB — $(Split-Path -Leaf $apk[0].Name)" -f $releaseMb, $MaxMb, $debugMb) `
        -Metrics @{ releaseMb = $releaseMb; debugMb = $debugMb; budgetMb = $MaxMb; apk = $apk[0].FullName }
}

function Invoke-ReleaseChecks {
    param([scriptblock]$Selector = $null)
    $isSelected = {
        param([string]$Id)
        if (-not $Selector) { return $true }
        return [bool](& $Selector $Id)
    }
    if (& $isSelected '23') {
        Write-Step '23) release module integrity (xposed_init + hook classes survive R8)'
        try { Invoke-ModuleIntegrityCheck | Out-Null }
        catch { Add-Result -Id '23-module-integrity' -Name 'release module integrity' -Status 'FAIL' -Detail "$_" }
    }
    if (& $isSelected '24') {
        Write-Step '24) release APK size budget'
        try { Invoke-ReleaseSizeCheck | Out-Null }
        catch { Add-Result -Id '24-apk-size' -Name 'release APK size' -Status 'FAIL' -Detail "$_" }
    }
}

