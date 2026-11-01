<#
.SYNOPSIS
    Capture the review screenshot set for the reworked UI into docs/screenshots/.

.DESCRIPTION
    Drives the app into each state a human reviewer needs to see and takes a validated PNG of it with
    e2e/screenshot.ps1. Everything is best-effort: a state that cannot be reached is reported as
    MISSING and the rest still gets captured, so this never blocks a manual review.

    File names are the ones ui-maps asked for, so a review request maps 1:1 onto a file:

      new-main-map        peek detent, joystick off (the default state)
      new-joystick        joystick pad visible (captured while the pad is pressed)
      new-tools-sheet     half-expanded tools panel (基站/WIFI/步数/GNSS/更新频率/独立模拟)
      new-route           full-expanded route planner
      new-placecard       long-press place card
      new-tapchip         single-tap chip (撤销/收藏/加入路线), captured inside its ~4 s lifetime
      new-search          search surface with a coordinate query typed
      new-layers          图层 sheet with the tile presets
      new-dark-mode       the peek state in system dark mode

    Timings that matter: the map backend needs ~3 s to fall back from the SDK to raster tiles, so
    every state settles >= 5 s after launch; the tap chip only lives ~4 s, so it is polled, not slept.

    Usage:
      .\e2e\capture_screens.ps1
      .\e2e\capture_screens.ps1 -Only new-joystick,new-dark-mode

.OUTPUTS
    Writes PNGs and prints one line per shot; exits 0 even when some states are missing (a missing
    state is a finding, not a harness failure) unless -Strict is given.
#>
[CmdletBinding()]
param(
    # string[] so both `-Only new-a,new-b` and `-Only "new-a,new-b"` work (a [string] parameter
    # cannot bind the array form and PowerShell fails with an argument-transformation error)
    [string[]]$Only = @(),
    [switch]$Strict,
    [int]$SettleMs = 1500
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')
. (Join-Path $PSScriptRoot 'lib\bridge.ps1')
. (Join-Path $PSScriptRoot 'ui_gesture.ps1')
. (Join-Path $PSScriptRoot 'lib\checks_ui.ps1')

$selected = @($Only | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
function Test-Pick([string]$Id) { return ($selected.Count -eq 0 -or $selected -contains $Id) }

$shots = [System.Collections.Generic.List[object]]::new()

function Save-Shot {
    param([Parameter(Mandatory)][string]$Name)
    try {
        $path = & (Join-Path $PSScriptRoot 'screenshot.ps1') -Name $Name -Quiet
        $shots.Add([pscustomobject]@{ Name = $Name; Status = 'OK'; Path = $path })
        Write-Ok "captured $path"
        return $path
    }
    catch {
        $shots.Add([pscustomobject]@{ Name = $Name; Status = 'FAILED'; Path = "$_" })
        Write-Warn2 "capture failed for ${Name}: $_"
        return $null
    }
}

function Save-Missing {
    param([string]$Name, [string]$Why)
    # A screenshot the reviewer cannot see is a design review that cannot happen, so a missing state
    # always carries the actual screen tree (and a dump file) with it.
    $snapshot = ''
    try { $snapshot = Format-UiSnapshot -Dump (Get-UiXml) -Max 16 } catch { $snapshot = '<no dump>' }
    $records = Join-Path $PSScriptRoot 'out'
    if (-not (Test-Path $records)) { New-Item -ItemType Directory -Force -Path $records | Out-Null }
    $file = Join-Path $records ("missing-$Name.txt")
    [System.IO.File]::WriteAllText($file, "state: $Name`nreason: $Why`nscreen: $snapshot`n", (New-Object System.Text.UTF8Encoding($false)))
    $shots.Add([pscustomobject]@{ Name = $Name; Status = 'MISSING'; Path = "$Why | dump: $file" })
    Write-Warn2 "state '$Name' not reachable: $Why"
    Write-Host "        screen: $snapshot" -ForegroundColor DarkGray
}

# The gesture flow keeps dismissing surfaces with BACK, and two BACKs in a row exit the activity —
# that is how a capture round ends up photographing the launcher. Every state therefore re-asserts
# the app is in front (and relaunches it if not) before it touches the screen.
function Ensure-Foreground {
    param([int]$SettleSec = 4)
    $appPkg = Get-ModulePackageName
    if (Test-PackageForeground -Package $appPkg) { return $true }
    Write-Warn2 "app not in front (backend: $(Get-TopActivity)); relaunching"
    Start-App -Package $appPkg
    Start-Sleep -Seconds $SettleSec
    return (Test-PackageForeground -Package $appPkg)
}

function Close-TopSurface {
    # one BACK closes a sheet; then make sure we did not walk out of the app
    Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 900
    Ensure-Foreground | Out-Null
}

Select-AndroidDevice -Wait | Out-Null
Wait-PackageService -TimeoutSec 120 | Out-Null
$screen = Get-ScreenSize
$mapX = [int]($screen.Width / 2)
$mapY = [int]($screen.Height * 0.42)

$pkg = Get-ModulePackageName
Force-Stop $pkg
Start-App -Package $pkg
Start-Sleep -Seconds 6          # SDK -> raster fallback takes ~3 s; never capture the blank view
# a known simulated position makes the screenshots comparable between rounds
try {
    Send-SetConfig -Config @{
        mode = 'STATIC'; speed = 'WALK'; updateIntervalMs = 1000; followHeading = $false
        gpsJitterMeters = 0.0; altitudeJitterMeters = 0.0
    } | Out-Null
    Send-Start | Out-Null
    Send-SetPosition -Lat 31.230416 -Lon 121.473701 | Out-Null
}
catch { Write-Warn2 "could not put the engine on the reference point: $_" }
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- main map (peek, joystick off)
if (Test-Pick 'new-main-map') {
    Start-Sleep -Milliseconds $SettleMs
    Save-Shot -Name 'new-main-map' | Out-Null
}

# ---------------------------------------------------------------- joystick (pressed)
if (Test-Pick 'new-joystick') {
    Ensure-Foreground | Out-Null
    $pad = Ensure-JoystickPad
    if ($pad) {
        # mid-press: the thumb offset + the direction indicator are what the review needs to see
        $press = Start-JoystickPress -Direction 'up' -Bounds $pad
        Save-Shot -Name 'new-joystick' | Out-Null
        Stop-JoystickPress -Points $press
    }
    else { Save-Missing -Name 'new-joystick' -Why 'joystick pad not found after tapping e2e.fab.joystick (need the >=300px pad; the FAB icon is only 61px)' }
}

# ---------------------------------------------------------------- tools sheet (half detent)
if (Test-Pick 'new-tools-sheet') {
    $opened = $false
    $fab = Get-UiElement -Name 'fabTools' -Fresh
    if ($fab) {
        Invoke-UiTapPoint -X $fab.Cx -Y $fab.Cy
        Start-Sleep -Milliseconds $SettleMs
        $opened = [bool](Get-UiElement -Name 'toolsPanel' -Fresh)
    }
    if (-not $opened) {
        $peek = Get-UiElement -Name 'peek' -Fresh
        if ($peek) {
            Invoke-UiSwipeDirection -Bounds $peek -Direction up -DistanceDp 420 -DurationMs 500 | Out-Null
            Start-Sleep -Milliseconds $SettleMs
            $opened = [bool](Get-UiElement -Name 'toolsPanel' -Fresh)
        }
    }
    if ($opened) { Save-Shot -Name 'new-tools-sheet' | Out-Null }
    else { Save-Missing -Name 'new-tools-sheet' -Why 'no tools panel (e2e.fab.tools, then peek swipe-up both failed)' }
}

# ---------------------------------------------------------------- route planner (full detent)
if (Test-Pick 'new-route') {
    $dump = Open-RoutePlanner
    if ($dump) { Save-Shot -Name 'new-route' | Out-Null }
    else { Save-Missing -Name 'new-route' -Why 'route planner never appeared (e2e.route.planner)' }
    Close-TopSurface
}

# ---------------------------------------------------------------- place card (long press)
if (Test-Pick 'new-placecard') {
    Ensure-Foreground | Out-Null
    Close-TopSurface
    Invoke-LongPress -X $mapX -Y $mapY -DurationMs 1100
    Start-Sleep -Milliseconds $SettleMs
    $card = Get-UiElement -Name 'placeCard' -Fresh -Optional
    if ($card) { Save-Shot -Name 'new-placecard' | Out-Null }
    else {
        $coord = @(Get-UiNodes -Dump (Get-UiXml) | Where-Object { $_.Text -and $_.Text -match '^\s*-?\d{1,3}\.\d{3,}' }) | Select-Object -First 1
        if ($coord) { Save-Shot -Name 'new-placecard' | Out-Null }
        else { Save-Missing -Name 'new-placecard' -Why 'long press produced neither e2e.placecard nor a coordinate label' }
    }
    Close-TopSurface
}

# ---------------------------------------------------------------- tap chip (single tap, ~4 s life)
if (Test-Pick 'new-tapchip') {
    Ensure-Foreground | Out-Null
    Invoke-AdbShell "input tap $mapX $mapY" -AllowFailure | Out-Null
    $chip = $null
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        $chip = Get-UiElement -Name 'tapChip' -Fresh -Optional
        if ($chip) { break }
        Start-Sleep -Milliseconds 250
    }
    if ($chip) { Save-Shot -Name 'new-tapchip' | Out-Null }
    else { Save-Missing -Name 'new-tapchip' -Why 'e2e.tapchip never appeared within 5 s of a single tap' }
    Close-TopSurface
}

# ---------------------------------------------------------------- search with an inline suggestion card
# The pill is no longer a modal: typing opens an inline card under it (e2e.search.suggestions) and the
# keyboard stays up — capture exactly that state, IME included.
if (Test-Pick 'new-search') {
    Ensure-Foreground | Out-Null
    $bar = Get-UiElement -Name 'searchbar' -Fresh
    if ($bar) {
        Invoke-UiTapPoint -X $bar.Cx -Y $bar.Cy
        Start-Sleep -Milliseconds 900
        Set-UiText -Value 'wangfujing' | Out-Null
        $card = Wait-UiBounds -Id 'e2e.search.suggestions' -TimeoutSec 6
        Start-Sleep -Milliseconds $SettleMs
        Save-Shot -Name 'new-search' | Out-Null
        if (-not $card) { Write-Warn2 'suggestion card (e2e.search.suggestions) not seen before the shot' }
        Close-TopSurface
    }
    else { Save-Missing -Name 'new-search' -Why 'search bar not found (e2e.searchbar)' }
}

# ---------------------------------------------------------------- favourite marker (E13 evidence)
# Favourites are data-driven, so they only appear after saving one: tap the peek ♡ and the selected
# favourite expands into a label chip next to the position marker.
if (Test-Pick 'new-favorite-marker') {
    Ensure-Foreground | Out-Null
    $fav = Get-UiElement -Name 'actionFav' -Fresh
    if ($fav) {
        Invoke-UiTapPoint -X $fav.Cx -Y $fav.Cy
        Start-Sleep -Seconds 2
        Save-Shot -Name 'new-favorite-marker' | Out-Null
    }
    else { Save-Missing -Name 'new-favorite-marker' -Why 'peek favourite button (e2e.action.favorite) not found' }
}

# ---------------------------------------------------------------- layers sheet
if (Test-Pick 'new-layers') {
    Ensure-Foreground | Out-Null
    $fab = Get-UiElement -Name 'fabLayers' -Fresh -Optional
    if ($fab) {
        Invoke-UiTapPoint -X $fab.Cx -Y $fab.Cy
        Start-Sleep -Milliseconds $SettleMs
        Save-Shot -Name 'new-layers' | Out-Null
        Close-TopSurface
    }
    else { Save-Missing -Name 'new-layers' -Why 'layer FAB not found (e2e.fab.layer)' }
}

# ---------------------------------------------------------------- dark mode
if (Test-Pick 'new-dark-mode') {
    $previous = Get-DarkMode
    Set-DarkMode -Dark $true | Out-Null
    Start-Sleep -Seconds 2
    Force-Stop $pkg
    Start-App -Package $pkg
    Start-Sleep -Seconds 6
    Save-Shot -Name 'new-dark-mode' | Out-Null
    if ($null -ne $previous) { Set-DarkMode -Dark ([bool]$previous) | Out-Null }
    else { Set-DarkMode -Dark $false | Out-Null }
    Start-Sleep -Seconds 2
}

# ---------------------------------------------------------------- summary
Write-Host ''
Write-Host 'screenshot set' -ForegroundColor Magenta
Write-Host '==============' -ForegroundColor Magenta
foreach ($s in $shots) {
    $color = switch ($s.Status) { 'OK' { 'Green' } 'MISSING' { 'Yellow' } default { 'Red' } }
    Write-Host ("  {0,-8} {1,-18} {2}" -f $s.Status, $s.Name, $s.Path) -ForegroundColor $color
}
$missing = @($shots | Where-Object { $_.Status -ne 'OK' }).Count
if ($Strict -and $missing -gt 0) { exit 1 }
exit 0
