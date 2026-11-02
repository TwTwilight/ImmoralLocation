# e2e/lib/checks_ui.ps1 — the UI/UX-rework acceptance checks (matrix ids 12..17).
#
# Dot-source AFTER common/bridge/verifier/report and ui_gesture.ps1; run_matrix.ps1 does that. The
# checks below are intentionally kept out of run_matrix.ps1 because they are the only ones that put
# the app in the foreground and drive it with real touches, i.e. they need the accessibility tree and
# therefore a different failure mode (selector missing) than the engine-level checks 1..11.
#
# Shared state they read from run_matrix.ps1's scope (all of it already exists there):
#   $Channel, $script:VerifierPackage, Get-ModulePackageName, Set-EngineConfig,
#   Invoke-SetPositionAndVerify, Force-Stop, Start-App, Ensure-VerifierStream, $POINT_A/$POINT_B/$POINT_C
#
# Selector policy: every widget is looked up by (1) a `testTag` exposed as resource-id
# (`e2e.<thing>`, requires `testTagsAsResourceId = true` in the app root), then (2) a content
# description from res/values/strings.xml resolved at runtime by Get-AppString. Nothing matches on
# a hard-coded Chinese literal, so copy changes in app sources degrade to SKIP, not to a false PASS.

. (Join-Path $PSScriptRoot 'common.ps1')
if (-not (Get-Command Get-UiBounds -ErrorAction SilentlyContinue)) {
    . (Join-Path $PSScriptRoot '..\ui_gesture.ps1')
}

# ------------------------------------------------------------------------------------------------
# selectors
# ------------------------------------------------------------------------------------------------
$script:UiIds = @{
    searchbar  = @('e2e.searchbar')
    joystick   = @('e2e.joystick', 'ui:joystick')
    sheet      = @('e2e.sheet')
    sheetHandle = @('e2e.sheet.handle')
    peek       = @('e2e.peek')
    snackbar   = @('e2e.snackbar')
    fabTools   = @('e2e.fab.tools')
    fabLayers  = @('e2e.fab.layer')
    fabJoystick = @('e2e.fab.joystick')
    fabLocate  = @('e2e.fab.locate')
    actionPrimary = @('e2e.action.primary')
    actionRoute = @('e2e.action.route')
    actionFav  = @('e2e.action.favorite')
    tapChip    = @('e2e.tapchip')
    tapUndo    = @('e2e.tap.undo')
    tapSave    = @('e2e.tap.save')
    tapRoute   = @('e2e.tap.route')
    searchSuggestions = @('e2e.search.suggestions')
    peekCollapsed = @('e2e.peek.collapsed')
    peekCollapse = @('e2e.peek.collapse')
    # S1 moved the favourites entry point: the 收藏 surface is now opened from the peek card's
    # “收藏 N ›” link, tagged by ui-polish specifically so this check survives the rework.
    peekFavorites = @('e2e.peek.favorites')
    toolCustomSpeed = @('e2e.tool.customspeed')
    toolsPanel = @('e2e.sheet.tools')
    placeCard  = @('e2e.placecard')
    placeMove  = @('e2e.place.move')
    placeNavigate = @('e2e.place.navigate')
    placeRoute = @('e2e.place.route')
    placeSave  = @('e2e.place.save')
    routePlanner = @('e2e.route.planner')
    routeStart = @('e2e.route.start')
    routeSave  = @('e2e.route.save')
    routeDest  = @('e2e.route.dest')
    routeOrigin = @('e2e.route.origin')
    routeAddWaypoint = @('e2e.route.addwaypoint')
    routeSearch = @('e2e.route.search')
    routeSearchPlace0 = @('e2e.route.search.place.0')
    routeClear = @('e2e.route.clear')
    routePlanning = @('e2e.route.planning')
    routeResult = @('e2e.route.result')
    routeNotice = @('e2e.route.notice')
    routeModeWalk = @('e2e.route.mode.walk')
    routeModeBike = @('e2e.route.mode.bike')
    routeModeDrive = @('e2e.route.mode.drive')
}
# Tile preset labels shown by the 图层 sheet (TilePreset.labelRes in app/.../map/TilePresets.kt).
$script:TilePresetKeys = @('tile_preset_amap', 'tile_preset_tencent', 'tile_preset_tianditu', 'tile_preset_osm', 'tile_preset_custom')
$script:SpeedKeys = @(
    @{ name = 'walk'; key = 'speed_walk'; text = '步行' },
    @{ name = 'run'; key = 'speed_run'; text = '跑步' },
    @{ name = 'bike'; key = 'speed_bike'; text = '骑车' },
    @{ name = 'drive'; key = 'speed_drive'; text = '驾车' },
    @{ name = 'plane'; key = 'speed_plane'; text = '飞机' },
    @{ name = 'custom'; key = 'speed_custom'; text = '自定义' }
)
$script:AmapPackage = 'com.autonavi.minimap'
$script:BaiduPackage = 'com.baidu.BaiduMap'
$script:YunzhiPackage = 'com.yunzhi.tiyu'

# ------------------------------------------------------------------------------------------------
# helpers
# ------------------------------------------------------------------------------------------------

# Runs a check body and turns any unexpected exception into a FAIL row instead of aborting the run.
function Invoke-Guarded {
    param([Parameter(Mandatory)][string]$Id, [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Body)
    try {
        & $Body | Out-Null
    }
    catch {
        Add-Result -Id $Id -Name $Name -Status 'FAIL' -Detail ("unexpected error: " + $_)
    }
}

# True when a source line is a comment.  Needed by the source greps below: the app deliberately
# *documents* that it no longer uses Toast / that the "未配置 Key" banner is gone, and those
# comments must not be mistaken for the forbidden construct.
function Test-CommentLine {
    param([string]$Line)
    $t = $Line.Trim()
    if (-not $t) { return $true }
    return ($t.StartsWith('//') -or $t.StartsWith('*') -or $t.StartsWith('/*') -or
        $t.StartsWith('<!--') -or $t.StartsWith('*/'))
}

# Selector misses are collected centrally so ONE report lists every tag that did not resolve on the
# device — the UI owner can then fix them in a single pass instead of one per run.
$script:UiMisses = [System.Collections.Generic.List[object]]::new()

function Add-UiMiss {
    param([Parameter(Mandatory)][string]$Where, [Parameter(Mandatory)][string]$Selector, [string]$Detail = '')
    foreach ($m in $script:UiMisses) {
        if ($m.Where -eq $Where -and $m.Selector -eq $Selector) { return }
    }
    $script:UiMisses.Add([pscustomobject]@{ Where = $Where; Selector = $Selector; Detail = $Detail })
    Write-Warn2 "selector miss [$Where] $Selector"
}

# Writes e2e/out/ui-misses.txt and returns the miss rows, so the matrix can publish one summary row.
function Export-UiMissReport {
    param([string]$Path = '')
    if (-not $Path) { $Path = Join-Path $OutDir 'ui-misses.txt' }
    $lines = @("# selectors that did not resolve on the device ($(Get-Date -Format o))", '')
    foreach ($m in $script:UiMisses) {
        $lines += ("{0}  <-  {1}" -f $m.Selector, $m.Where)
        if ($m.Detail) { $lines += ("    screen: " + $m.Detail) }
    }
    if ($script:UiMisses.Count -eq 0) { $lines += '(none)' }
    [System.IO.File]::WriteAllLines($Path, $lines, (New-Object System.Text.UTF8Encoding($false)))
    return @($script:UiMisses)
}

function Get-TopActivity {
    $r = Invoke-AdbShell 'dumpsys activity activities' -AllowFailure
    foreach ($line in $r.Output) {
        if ($line -match 'topResumedActivity=.*?\s([\w.]+)/([\w.$]+)') { return "$($matches[1])/$($matches[2])" }
        if ($line -match 'ResumedActivity:.*?\s([\w.]+)/([\w.$]+)') { return "$($matches[1])/$($matches[2])" }
    }
    $w = Invoke-AdbShell 'dumpsys window' -AllowFailure
    foreach ($line in $w.Output) {
        if ($line -match 'mCurrentFocus=Window\{.*?\s([\w.]+)/([\w.$]+)\}') { return "$($matches[1])/$($matches[2])" }
    }
    return ''
}

function Test-PackageForeground {
    param([Parameter(Mandatory)][string]$Package)
    $top = Get-TopActivity
    return ($top -like "$Package/*")
}

# Nearest fix among all providers, and its distance to the requested point.
function Get-NearestFix {
    param([double]$Lat, [double]$Lon)
    $fix = Get-DumpsysFix
    $best = $null
    foreach ($k in @($fix.Keys)) {
        $d = Get-GeoDistanceMeters -Lat1 $Lat -Lon1 $Lon -Lat2 $fix[$k].lat -Lon2 $fix[$k].lon
        if ($null -eq $best -or $d -lt $best.DistanceMeters) {
            $best = [pscustomobject]@{ Provider = $k; Lat = $fix[$k].lat; Lon = $fix[$k].lon; DistanceMeters = [Math]::Round($d, 1) }
        }
    }
    return $best
}

# The app's own peek row is the third oracle for "did the simulated position move".
#
# Why it exists: on some builds both `dumpsys location`'s per-provider last fix and the verifier
# stream go stale (the engine moves — its tick log says `moved=7.4782m` — while both read 0 m).
# The peek row is what the *product* shows the user, it is refreshed from `EngineState` every push,
# and it is a plain Compose TextView, so `uiautomator dump` sees it. 4 decimals ≈ 11 m, which is
# ample for a multi-second drag at DRIVE speed.
function Get-PeekCoordinate {
    param([int]$Attempts = 6, [int]$DelayMs = 400)
    for ($i = 0; $i -lt $Attempts; $i++) {
        $xml = Get-UiXml
        $m = [regex]::Matches(("$xml"), 'text="(-?\d{1,3}\.\d{3,}\s*,\s*-?\d{1,3}\.\d{3,})"')
        if ($m.Count -gt 0) {
            $parts = $m[0].Groups[1].Value -split ','
            $lat = [double]::Parse($parts[0].Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
            $lon = [double]::Parse($parts[1].Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
            if ($lat -ne 0 -or $lon -ne 0) {
                return [pscustomobject]@{ Lat = $lat; Lon = $lon; Raw = $m[0].Groups[1].Value }
            }
        }
        Start-Sleep -Milliseconds $DelayMs
    }
    return $null
}

# Location samples that prove movement: the injected stream when it is alive, any sample otherwise.
function Get-MoveSamples {    param([long]$AfterTs = 0, [long]$UntilTs = 0)
    $s = @(Get-LiveLocationSamples -AfterTs $AfterTs)
    if ($s.Count -lt 2) { $s = @(Get-AnyLocationSamples -AfterTs $AfterTs) }
    if ($UntilTs -gt 0) { $s = @($s | Where-Object { [long]$_.ts -le $UntilTs }) }
    return @($s | Where-Object {
            $_.lat -ne 0 -or $_.lon -ne 0
        } | Sort-Object -Property ts)
}

# Distance from a point to a polyline (+ how far along the polyline the projection is).
# Equirectangular local frame per segment: ample precision at the few-hundred-metre scale used here.
function Get-PointToPolyline {
    param([double]$Lat, [double]$Lon, [Parameter(Mandatory)][array]$Points)
    if ($Points.Count -lt 2) { return [pscustomobject]@{ DistanceMeters = -1.0; AlongMeters = 0.0; Progress = 0.0; TotalMeters = 0.0 } }
    $segs = [System.Collections.Generic.List[object]]::new()
    $cum = 0.0
    for ($i = 1; $i -lt $Points.Count; $i++) {
        $a = $Points[$i - 1]
        $b = $Points[$i]
        $len = Get-GeoDistanceMeters -Lat1 $a.lat -Lon1 $a.lon -Lat2 $b.lat -Lon2 $b.lon
        $segs.Add([pscustomobject]@{ A = $a; B = $b; Length = $len; Start = $cum })
        $cum += $len
    }
    $total = $cum
    $bestDist = $null
    $bestAlong = 0.0
    foreach ($s in $segs) {
        $cosLat = [Math]::Cos($s.A.lat * [Math]::PI / 180.0)
        $vx = ($s.B.lon - $s.A.lon) * 111320.0 * $cosLat
        $vy = ($s.B.lat - $s.A.lat) * 110540.0
        $px = ($Lon - $s.A.lon) * 111320.0 * $cosLat
        $py = ($Lat - $s.A.lat) * 110540.0
        $den = $vx * $vx + $vy * $vy
        $t = if ($den -le 0.0) { 0.0 } else { ($px * $vx + $py * $vy) / $den }
        $t = [Math]::Max(0.0, [Math]::Min(1.0, $t))
        $dx = $px - $vx * $t
        $dy = $py - $vy * $t
        $dist = [Math]::Sqrt($dx * $dx + $dy * $dy)
        if ($null -eq $bestDist -or $dist -lt $bestDist) {
            $bestDist = $dist
            $bestAlong = $s.Start + $t * $s.Length
        }
    }
    $progress = if ($total -gt 0.5) { $bestAlong / $total } else { 0.0 }
    return [pscustomobject]@{
        DistanceMeters = [Math]::Round($bestDist, 1)
        AlongMeters    = [Math]::Round($bestAlong, 1)
        Progress       = [Math]::Round($progress, 4)
        TotalMeters    = [Math]::Round($total, 1)
    }
}

# Resolves the first matching selector of a logical UI element; returns $null when nothing matches.
# `-Optional` suppresses the selector-miss bookkeeping: use it for lookups that are *expected* to fail
# (retry loops, "is this surface open yet?" probes, the joystick being off by spec) so that
# e2e/out/ui-misses.txt only ever lists genuine mismatches.
function Get-UiElement {
    param([Parameter(Mandatory)][string]$Name, [System.Xml.XmlDocument]$Dump, [switch]$Fresh, [switch]$Optional)
    $ids = @()
    if ($script:UiIds.ContainsKey($Name)) { $ids = $script:UiIds[$Name] }
    $dump = if ($Dump) { $Dump } else { Get-UiXml -Fresh:$Fresh }
    if (-not $dump) { return $null }
    foreach ($id in $ids) {
        $b = @(Get-UiBounds -Id $id -Dump $dump)
        if ($b.Count -gt 0) { return $b[0] }
    }
    switch ($Name) {
        'searchbar' { $b = @(Get-UiBounds -Text (Get-AppString -Key 'search_hint' -Default '搜索') -Dump $dump); if ($b.Count -gt 0) { return $b[0] } }
        'joystick' { return Get-JoystickBounds -Dump $dump }
        'toolsPanel' {
            foreach ($t in @((Get-AppString -Key 'action_tools' -Default '工具'), (Get-AppString -Key 'tools_title' -Default '工具'))) {
                $b = @(Get-UiBounds -Text $t -Exact -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
            }
        }
        'fabJoystick' {
            foreach ($t in @((Get-AppString -Key 'mode_joystick' -Default '摇杆'), (Get-AppString -Key 'joystick_title' -Default '摇杆控制'))) {
                $b = @(Get-UiBounds -Desc $t -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
                $b = @(Get-UiBounds -Text $t -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
            }
        }
        'fabLayers' {
            foreach ($t in @((Get-AppString -Key 'map_layer' -Default '图层'))) {
                $b = @(Get-UiBounds -Desc $t -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
            }
        }
        'actionRoute' {
            foreach ($t in @((Get-AppString -Key 'action_route' -Default '路线'), (Get-AppString -Key 'route_title' -Default '路线模拟'))) {
                $b = @(Get-UiBounds -Text $t -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
            }
        }
        'routeStart' {
            foreach ($t in @((Get-AppString -Key 'action_start' -Default '开始模拟'), (Get-AppString -Key 'route_start' -Default '开始模拟'))) {
                $b = @(Get-UiBounds -Text $t -Dump $dump)
                if ($b.Count -gt 0) { return $b[0] }
            }
        }
    }
    $selector = if ($ids.Count -gt 0) { ($ids -join ' | ') } else { $Name }
    if (-not $Optional) {
        Add-UiMiss -Where "Get-UiElement:$Name" -Selector $selector -Detail (Format-UiSnapshot -Dump $dump -Max 10)
    }
    return $null
}

# Starts the app and waits until its own activity really is the top activity.  The extra settle
# time is deliberate: the map backend needs ~3 s to fall back from the SDK to raster tiles, and a
# screenshot or bounds query taken earlier sees a blank map view.
function Start-UiApp {
    param([int]$TimeoutSec = 20, [int]$SettleSec = 5)
    $pkg = Get-ModulePackageName
    Start-App -Package $pkg
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PackageForeground -Package $pkg) {
            Start-Sleep -Seconds $SettleSec
            return $true
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# Puts the engine in a known, app-independent state while the UI is in the foreground.
function Set-UiTestState {
    param([hashtable]$Config = @{}, [hashtable]$Point = $null)
    if (-not $Point) { $Point = $POINT_A }
    $base = @{ mode = 'STATIC'; speed = 'WALK'; followHeading = $false; fixedBearingDeg = 0.0; updateIntervalMs = 500 }
    foreach ($k in $Config.Keys) { $base[$k] = $Config[$k] }
    Set-EngineConfig $base | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Send-SetPosition -Lat $Point.lat -Lon $Point.lon -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2
}

# ------------------------------------------------------------------------------------------------
# engine config from the hook's own location bundle
# ------------------------------------------------------------------------------------------------
# The hook stamps every injected fix with `immoral.*` extras (stepSim/wifiSim/cellSim/gpsSignalSim/
# independent/cadence/steps). That makes the engine's real configuration readable from `dumpsys
# location` without guessing, which is what the pre-run hygiene gate below uses.
function Get-EngineConfigFlags {
    $r = Invoke-AdbShell 'dumpsys location' -AllowFailure
    $flags = @{}
    foreach ($line in $r.Output) {
        if ($line -notmatch 'immoral\.') { continue }
        foreach ($m in [regex]::Matches($line, 'immoral\.(\w+)=([^,}\s\]]+)')) {
            $flags[$m.Groups[1].Value] = $m.Groups[2].Value
        }
        break   # the first fix line with the bundle is the most recent one
    }
    return $flags
}

# Opens the joystick pad via the FAB and returns its bounds (>=300px, i.e. the real 152dp pad).
# Handles the first-run "切换到其他 App 时保持摇杆" onboarding sheet, which otherwise covers the peek row
# and makes every later selector look missing.
function Ensure-JoystickPad {
    param([int]$Tries = 2)
    $pad = Get-JoystickBounds -Fresh
    if ($pad) { return $pad }
    for ($i = 0; $i -lt $Tries; $i++) {
        $dump = Get-UiXml
        $fab = Get-UiElement -Name 'fabJoystick' -Dump $dump -Optional
        if (-not $fab) { $fab = Get-UiElement -Name 'fabJoystick' -Fresh -Optional }
        if (-not $fab) { return $null }
        Invoke-UiTapPoint -X $fab.Cx -Y $fab.Cy
        Start-Sleep -Seconds 2
        $d2 = Get-UiXml
        foreach ($t in @('稍后再说', '以后再说', '关闭', '取消', '知道了')) {
            $b = @(Get-UiBounds -Text $t -Dump $d2)
            if ($b.Count -gt 0) {
                Write-Step "dismissing the floating-joystick onboarding ('$t')"
                Invoke-UiTapPoint -X $b[0].Cx -Y $b[0].Cy
                Start-Sleep -Seconds 1
                $d2 = Get-UiXml
                break
            }
        }
        $pad = Get-JoystickBounds -Dump $d2
        if ($pad) { return $pad }
    }
    return $null
}

# 0. pre-run hygiene: the simulation toggles must be OFF and the motion defaults must be the ones the
#    acceptance numbers assume.  A teammate's leftover 步频/独立模拟 toggle (or an app-side re-push)
#    otherwise silently changes what every later check measures.
function Invoke-ConfigHygieneCheck {
    param([hashtable]$Point = $null, [switch]$NoUi)
    if (-not $Point) { $Point = @{ lat = 31.230416; lon = 121.473701 } }
    # Do NOT force-stop OUR app: it is the process that owns the foreground service and keeps the
    # simulation alive. Force-stopping it stops the engine, the injected fixes stop being refreshed,
    # `LocationManagerService` ages out the last location and this gate then reports "not injecting"
    # while measuring only its own side effect (that false alarm cost a whole device window).
    # Only foreign spoofers are stopped here; ours is brought to the foreground instead.
    foreach ($foreign in @('com.lerist.fakelocation')) {
        if (Test-PackageInstalled $foreign) { Force-Stop $foreign }
    }
    # In engine-only mode (`-SkipUi`) do NOT launch our app: nothing here needs its UI, and bringing it
    # to the foreground only perturbs what the engine-only checks are measuring.
    if (-not $NoUi) { Start-UiApp | Out-Null }
    if (Get-Command Ensure-VerifierStream -ErrorAction SilentlyContinue) { Ensure-VerifierStream -Force | Out-Null }
    else { Start-Verifier -Count 200 -IntervalMs 1000 -Stream }
    Start-Sleep -Seconds 1
    Set-EngineConfig @{
        mode                 = 'STATIC'
        speed                = 'WALK'
        followHeading        = $false
        fixedBearingDeg      = 0.0
        updateIntervalMs     = 1000
        gpsJitterMeters      = 0.0
        altitudeJitterMeters = 0.0
        cellSimulation       = $false
        wifiSimulation       = $false
        gpsSignalSimulation  = $false
        stepSimulation       = $false
        independentMode      = $false
        independentPackages  = @()
    } | Out-Null
    Send-SetPosition -Lat $Point.lat -Lon $Point.lon -Channel $Channel | Out-Null
    Send-Start -Channel $Channel | Out-Null
    # ORACLE: the verifier stream, not `dumpsys`. `dumpsys location` only carries the `immoral.*`
    # bundle for a short while after a fix was delivered to a listener, so it can read empty while the
    # engine is injecting perfectly (observed on device: the gate reported "no bundle" and, seconds
    # later, a real drag moved the injected point 4.4 m). A VERIFY line proves the value travelled the
    # `requestLocationUpdates` path, which is the honest signal for "the hook is injecting".
    $mark = Get-VerifyMark
    $injected = $false
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Seconds 1
        foreach ($s in @(Get-VerifySamples -Kind 'location' -AfterTs $mark)) {
            $d = Get-GeoDistanceMeters -Lat1 $Point.lat -Lon1 $Point.lon -Lat2 $s.lat -Lon2 $s.lon
            if ($d -le 80) { $injected = $true; break }
        }
        if ($injected) { break }
    }
    # best effort only: the toggle names, when the bundle happens to be present at this instant.
    # An absent bundle is NOT a failure — the verifier oracle above already proves the engine is
    # injecting; it only means we cannot read the individual toggle names this second.
    $flags = Get-EngineConfigFlags
    $offKeys = @('stepSim', 'wifiSim', 'cellSim', 'gpsSignalSim', 'independent')
    $bad = @()
    if ($flags.Count -gt 0) {
        foreach ($k in $offKeys) {
            if (-not $flags.ContainsKey($k)) { $bad += "$k=<missing>"; continue }
            if ("$($flags[$k])" -match '^(1|true|True)$') { $bad += "$k=$($flags[$k])" }
        }
    }
    # Verdict: injection proven by the verifier stream. The toggle names come from the bundle when it
    # happens to be present; if it is not, that is reported but is NOT a failure by itself (the oracle
    # above already proves the engine is injecting).
    $ok = $injected -and ($bad.Count -eq 0)
    $flagText = (@($flags.Keys | Sort-Object | ForEach-Object { "$_=$($flags[$_])" }) -join ' ')
    $hookTail = ''
    if (-not $injected) {
        try {
            $hookTail = ' | last hook lines: ' + ((Get-HookLog -TailLines 400 | Select-Object -Last 8) -join ' || ')
        }
        catch { $hookTail = ' | (hook log unavailable)' }
    }
    Test-Result -Id '0-config-hygiene' -Name 'engine is injecting (verifier) and simulation toggles are OFF' -Condition $ok `
        -Detail $(if ($ok) { "verifier injected the test point; bundle toggles: $(if ($flagText) { $flagText } else { 'unavailable at this instant (not a failure — verifier oracle used)' })" } elseif (-not $injected) { 'no VERIFY location within 80 m of the test point after SetConfig+SetPosition+Start' + $hookTail } else { "unexpected ON toggles: $($bad -join ', '); bundle: $flagText" }) `
        -Metrics @{ injected = $injected; flags = $flags; badToggles = $bad }
    return $ok
}

# ------------------------------------------------------------------------------------------------
# 12. joystick direction — screen-aligned: up = north (+lat), right = east (+lon), +/-15 deg
#
# KNOWN LIMITATION (2026-11-01): the four drags are constructed identically, yet on device only some
# of them reach the app — the pad sometimes treats the synthetic `input swipe` as a tap/scroll, in
# particular when the map is still re-centring after the preceding SetPosition. One leg that did
# arrive measured 4.4 m at 277.3 deg for a leftward drag (expected 270, error 7.3), which is why this
# is a gesture-delivery problem and not a direction bug. The fix to implement next is: after starting
# the drag, poll the hook log for `decoded: SetJoystick`, and retry the gesture once (after letting the
# map settle) if the app never emitted it — do not read a 0 m leg as a product failure.
# ------------------------------------------------------------------------------------------------
function Invoke-JoystickDirectionCheck {
    param([double]$HoldSec = 5.0, [double]$BearingToleranceDeg = 15.0)

    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    # fast speed so a 5 s hold produces a displacement that dwarfs any jitter
    Set-UiTestState -Config @{ mode = 'JOYSTICK'; speed = 'DRIVE'; fixedBearingDeg = 0.0 }

    # joystick is opt-in: turn it on via the right-edge FAB (Ensure-JoystickPad also dismisses the
    # first-run floating-joystick onboarding sheet and insists on the real 418px pad)
    $pad = Ensure-JoystickPad
    if (-not $pad) {
        Add-Result -Id '12-joystick-direction' -Name 'joystick drag direction matches on-screen direction' -Status 'SKIP' `
            -Detail ("joystick pad not found in the UI tree — expected testTag 'e2e.joystick' (testTagsAsResourceId) or a visible pad. screen: " + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    Write-Step "joystick pad at $(Format-Rect $pad)"

    $expectations = @(
        @{ dir = 'up'; bearing = 0.0; check = { param($dLat, $dLon) return ($dLat -gt 0) }; label = 'lat+' },
        @{ dir = 'right'; bearing = 90.0; check = { param($dLat, $dLon) return ($dLon -gt 0) }; label = 'lon+' },
        @{ dir = 'down'; bearing = 180.0; check = { param($dLat, $dLon) return ($dLat -lt 0) }; label = 'lat-' },
        @{ dir = 'left'; bearing = 270.0; check = { param($dLat, $dLon) return ($dLon -lt 0) }; label = 'lon-' }
    )
    $metrics = [ordered]@{}
    $failures = @()
    $passCount = 0

    foreach ($exp in $expectations) {
        Send-SetPosition -Lat $POINT_A.lat -Lon $POINT_A.lon -Channel $Channel | Out-Null
        Start-Sleep -Seconds 2
        $fixStart = Get-DumpsysFix
        $peekStart = Get-PeekCoordinate
        $mark = Get-VerifyMark
        # The app is the only honest witness that the synthetic gesture reached the pad: a swallowed
        # swipe produces no `SetJoystick` at all. Count the hook-log occurrences before the press and
        # retry once if the count does not grow (see the KNOWN LIMITATION note above the function).
        $joystickBaseline = @(Get-HookLog -TailLines 200 | Where-Object { $_ -match 'decoded: SetJoystick' }).Count
        $sent = $false
        $attempts = 0
        $drag = $null
        while (-not $sent -and $attempts -lt 2) {
            $attempts++
            $drag = Start-JoystickPress -Direction $exp.dir -Bounds $pad -HoldMs ([int]($HoldSec * 1000))
            for ($w = 0; $w -lt 10; $w++) {
                Start-Sleep -Milliseconds 150
                $now = @(Get-HookLog -TailLines 200 | Where-Object { $_ -match 'decoded: SetJoystick' }).Count
                if ($now -gt $joystickBaseline) { $sent = $true; break }
            }
            if ($sent) {
                Start-Sleep -Milliseconds ([Math]::Max(0, [int]($HoldSec * 1000) - 1500))
            }
            else {
                Stop-JoystickPress -Points $drag
                Start-Sleep -Milliseconds 700
                Send-SetPosition -Lat $POINT_A.lat -Lon $POINT_A.lon -Channel $Channel | Out-Null
                Start-Sleep -Milliseconds 800
            }
        }
        $until = Get-VerifyMark
        Stop-JoystickPress -Points $drag
        Start-Sleep -Milliseconds 800
        $fixEnd = Get-DumpsysFix
        $peekEnd = Get-PeekCoordinate

        $samples = @(Get-MoveSamples -AfterTs $mark -UntilTs $until)
        $track = Measure-Track -Samples $samples
        $dTrack = Measure-DumpsysTrack -Start $fixStart -End $fixEnd
        $meters = $track.meters
        $bearing = $track.bearing
        $dLat = 0.0; $dLon = 0.0
        $source = 'verifier'
        if ($samples.Count -ge 2) {
            $dLat = [double]$samples[-1].lat - [double]$samples[0].lat
            $dLon = [double]$samples[-1].lon - [double]$samples[0].lon
        }
        if ($dTrack.ok -and $dTrack.meters -gt [Math]::Max($meters, 0.5)) {
            $meters = $dTrack.meters
            $bearing = $dTrack.bearing
            $dLat = [double]$dTrack.toLat - [double]$dTrack.fromLat
            $dLon = [double]$dTrack.toLon - [double]$dTrack.fromLon
            $source = "dumpsys:$($dTrack.provider)"
        }
        # Third oracle: what the product itself displays in its peek row. Only used when both
        # streams above are stale, which is a real observed failure mode on this ROM (the engine's
        # tick log said `moved=7.4782m` while verifier + dumpsys both read 0 m).
        if ($meters -lt 2.0 -and $null -ne $peekStart -and $null -ne $peekEnd) {
            $pMeters = Get-GeoDistanceMeters -Lat1 $peekStart.Lat -Lon1 $peekStart.Lon -Lat2 $peekEnd.Lat -Lon2 $peekEnd.Lon
            if ($pMeters -gt $meters) {
                $meters = $pMeters
                $dLat = [double]$peekEnd.Lat - [double]$peekStart.Lat
                $dLon = [double]$peekEnd.Lon - [double]$peekStart.Lon
                $bearing = Get-GeoBearingDegrees -Lat1 $peekStart.Lat -Lon1 $peekStart.Lon -Lat2 $peekEnd.Lat -Lon2 $peekEnd.Lon
                $source = 'peek'
            }
        }
        $bearingDelta = if ($meters -gt 0.5) { [Math]::Abs((Get-AngleDelta -From $exp.bearing -To $bearing)) } else { 999.0 }
        $signOk = & $exp.check ([double]$dLat) ([double]$dLon)
        $ok = ($meters -ge 2.0) -and $signOk -and ($bearingDelta -le $BearingToleranceDeg)

        $metrics[$exp.dir] = [ordered]@{
            sentJoystick  = $sent
            attempts      = $attempts
            meters        = $meters
            dLat          = [Math]::Round($dLat, 7)
            dLon          = [Math]::Round($dLon, 7)
            bearing       = $bearing
            expected      = $exp.bearing
            bearingDelta  = [Math]::Round($bearingDelta, 1)
            signOk        = $signOk
            samples       = $samples.Count
            source        = $source
        }
        Write-Host ("     {0,-5} {1,6:N1} m  bearing {2,7:N1} (expected {3,3:N0})  delta {4,6:N1}  dLat {5,9:N7}  dLon {6,9:N7}  n={7}" -f `
                $exp.dir, $meters, $bearing, $exp.bearing, $bearingDelta, $dLat, $dLon, $samples.Count)
        if ($ok) { $passCount++ }
        else {
            $failures += ("{0}: moved {1} m, bearing {2} vs expected {3} (delta {4}), dLat=$dLat dLon=$dLon" -f `
                    $exp.dir, $meters, $bearing, $exp.bearing, [Math]::Round($bearingDelta, 1))
        }
    }

    Send-ReleaseJoystick -Channel $Channel | Out-Null
    $condition = ($failures.Count -eq 0)
    Test-Result -Id '12-joystick-direction' -Name 'joystick drag direction matches on-screen direction (up=north, right=east, +-15 deg)' `
        -Condition $condition `
        -Detail $(if ($condition) { "all 4 directions within $BearingToleranceDeg deg and with the correct lat/lon sign (pad $(Format-Rect $pad), hold ${HoldSec}s at DRIVE)" } else { "wrong direction: " + ($failures -join '; ') }) `
        -Metrics $metrics
}

# ------------------------------------------------------------------------------------------------
# 13. no Toast — static grep of the app sources + no toast windows while driving the UI
# ------------------------------------------------------------------------------------------------
# Source-only half of check 13; also usable without a device (see e2e/lint.ps1's static mode).
function Get-ToastSourceHits {
    $hits = @()
    $dir = Join-Path $RepoRoot 'app\src'
    if (-not (Test-Path $dir)) { return @() }
    foreach ($file in @(Get-ChildItem -Path $dir -Recurse -File -Include '*.kt', '*.java', '*.xml' -ErrorAction SilentlyContinue)) {
        $lineNo = 0
        foreach ($line in (Get-Content $file.FullName -ErrorAction SilentlyContinue)) {
            $lineNo++
            if (Test-CommentLine $line) { continue }
            if ($line -match 'android\.widget\.Toast|Toast\.makeText|Toast\.LENGTH_|import\s+android\.widget\.Toast') {
                $hits += ("{0}:{1}: {2}" -f $file.FullName.Replace($RepoRoot + '\', ''), $lineNo, $line.Trim())
            }
        }
    }
    return @($hits)
}

# Source-only half of check 20: the "SDK 未配置 Key" banner string must not exist any more.
function Get-KeyBannerSourceHits {
    $hits = @()
    $dir = Join-Path $RepoRoot 'app\src\main'
    if (-not (Test-Path $dir)) { return @() }
    foreach ($file in @(Get-ChildItem -Path $dir -Recurse -File -Include '*.kt', '*.xml' -ErrorAction SilentlyContinue)) {
        $lineNo = 0
        foreach ($line in (Get-Content $file.FullName -ErrorAction SilentlyContinue)) {
            $lineNo++
            if (Test-CommentLine $line) { continue }
            if ($line -match '未配置\s*(Key|key)|Key\s*未配置|未配置\s*SDK') {
                $hits += ("{0}:{1}" -f $file.Name, $lineNo)
            }
        }
    }
    return @($hits)
}

function Invoke-NoToastCheck {
    $hits = @(Get-ToastSourceHits)

    # dynamic half: drive the app and sample the window list for toast windows
    $toastSeen = @()
    try {
        if (Start-UiApp) {
            $dump = Get-UiXml
            $targets = @(
                (Get-UiElement -Name 'fabJoystick' -Dump $dump),
                (Get-UiElement -Name 'fabLocate' -Dump $dump),
                (Get-UiElement -Name 'peek' -Dump $dump)
            )
            for ($i = 0; $i -lt 8; $i++) {
                $t = $targets[$i % [Math]::Max(1, $targets.Count)]
                if ($t) { Invoke-AdbShell "input tap $($t.Cx) $($t.Cy)" -AllowFailure | Out-Null }
                Start-Sleep -Milliseconds 400
                $w = Invoke-AdbShell 'dumpsys window windows' -AllowFailure
                foreach ($line in $w.Output) {
                    if ($line -match '(?i)\btoast\b') { $toastSeen += $line.Trim() }
                }
            }
        }
    }
    catch { Write-Warn2 "toast window sampling failed: $_" }

    $toastSeen = @($toastSeen | Select-Object -Unique)
    $ok = ($hits.Count -eq 0) -and ($toastSeen.Count -eq 0)
    Test-Result -Id '13-no-toast' -Name 'zero Toast usage (sources) and no toast window while driving the UI' -Condition $ok `
        -Detail $(if ($ok) { 'no Toast import/call in app sources, no toast window observed during 8 UI interactions' } else { "source hits: $($hits.Count) [$($hits -join ' | ')]; toast windows: $($toastSeen.Count) [$($toastSeen -join ' | ')]" }) `
        -Metrics @{ sourceHits = $hits.Count; toastWindows = $toastSeen.Count; examples = @($hits | Select-Object -First 5) }
}

# ------------------------------------------------------------------------------------------------
# 14. chrome geometry — snackbar / joystick / sheet / search bar / FABs must not intersect
# ------------------------------------------------------------------------------------------------
function Invoke-ChromeOverlapCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Set-UiTestState -Config @{ mode = 'JOYSTICK'; speed = 'WALK' }

    # The joystick is OFF by default (spec), so the overlap assertion runs twice: the plain peek
    # chrome first, then the hard case with the pad on (pad is anchored bottom-START, FAB stack
    # bottom-END, both 16dp above the sheet — they must still not touch).
    $pairs = @(
        @('joystick', 'sheet'), @('joystick', 'searchbar'), @('joystick', 'fabTools'),
        @('joystick', 'fabLayers'), @('joystick', 'fabJoystick'), @('joystick', 'fabLocate'), @('joystick', 'snackbar'),
        @('sheet', 'searchbar'), @('sheet', 'fabTools'), @('sheet', 'fabLayers'), @('sheet', 'fabLocate'), @('sheet', 'snackbar'),
        @('sheet', 'fabJoystick'), @('sheet', 'fabTools'), @('fabLayers', 'fabJoystick'), @('fabJoystick', 'fabLocate'),
        @('snackbar', 'searchbar'), @('snackbar', 'fabTools'), @('snackbar', 'fabLayers'),
        @('snackbar', 'fabJoystick'), @('snackbar', 'fabLocate')
    )
    $elementNames = @('searchbar', 'sheet', 'joystick', 'fabTools', 'fabLayers', 'fabJoystick', 'fabLocate', 'snackbar')

    $metrics = [ordered]@{}
    $overlaps = @()
    $notes = @()
    $pairChecks = 0

    foreach ($phase in @(
            @{ name = 'pad-off'; wantPad = $false },
            @{ name = 'pad-on'; wantPad = $true }
        )) {
        $collected = [ordered]@{}
        $dump = Get-UiXml
        $pad = Get-JoystickBounds -Dump $dump
        if ($phase.wantPad -and -not $pad) {
            $fab = Get-UiElement -Name 'fabJoystick' -Dump $dump
            if ($fab) {
                Invoke-UiTapPoint -X $fab.Cx -Y $fab.Cy
                Start-Sleep -Seconds 2
                $dump = Get-UiXml
                $pad = Get-JoystickBounds -Dump $dump
            }
        }
        foreach ($name in $elementNames) {
            if ($name -eq 'joystick' -and -not $phase.wantPad) {
                # the pad is off by default: its absence here is the expected state, not a selector miss
                $metrics["$($phase.name).$name"] = 'absent (pad off by spec)'
                continue
            }
            $el = Get-UiElement -Name $name -Dump $dump
            if ($el) {
                $collected[$name] = $el
                $metrics["$($phase.name).$name"] = (Format-Rect $el)
            }
            else {
                $metrics["$($phase.name).$name"] = 'absent'
                if ($name -ne 'joystick' -or $phase.wantPad) { $notes += "$($phase.name):$name missing" }
            }
        }
        foreach ($p in $pairs) {
            $a = $p[0]; $b = $p[1]
            if (-not ($collected.Contains($a) -and $collected.Contains($b))) { continue }
            $pairChecks++
            if (Test-RectOverlap $collected[$a] $collected[$b]) {
                $area = Get-RectOverlapArea $collected[$a] $collected[$b]
                $overlaps += ("[{0}] {1} x {2} (area {3}px^2)" -f $phase.name, $a, $b, $area)
            }
        }
        if ($phase.wantPad -and -not $collected.Contains('joystick')) {
            $overlaps += "[$($phase.name)] joystick pad never appeared after tapping e2e.fab.joystick"
        }
    }

    if ($pairChecks -eq 0) {
        Add-Result -Id '14-chrome-overlap' -Name 'snackbar/joystick/sheet/search bar/FABs do not overlap' -Status 'SKIP' `
            -Detail ('none of the rework testTags are present in the UI tree — expected e2e.searchbar/sheet/joystick/fab.{tools,layer,joystick,locate}/snackbar. ' + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    $ok = ($overlaps.Count -eq 0)
    Test-Result -Id '14-chrome-overlap' -Name 'snackbar/joystick/sheet/search bar/FABs do not overlap (pad off and pad on)' -Condition $ok `
        -Detail $(if ($ok) { "$pairChecks element pairs checked across both chrome states (pad off / pad on), zero intersections" } else { "overlapping chrome: " + ($overlaps -join '; ') + $(if ($notes.Count -gt 0) { " (missing: $($notes -join ', '))" } else { '' }) }) `
        -Metrics $metrics

    # 14b: the six transport cells must be rendered, equally sized and fully inside the sheet
    Invoke-SpeedRowCheck -Dump (Get-UiXml)
}

function Invoke-SpeedRowCheck {
    param([System.Xml.XmlDocument]$Dump)
    if (-not $Dump) { $Dump = Get-UiXml }
    $cells = @()
    foreach ($s in $script:SpeedKeys) {
        $b = @(Get-UiBounds -Id "e2e.speed.$($s.name)" -Dump $Dump)
        if ($b.Count -eq 0) {
            $text = Get-AppString -Key $s.key -Default $s.text
            $b = @(Get-UiBounds -Text $text -Exact -Dump $Dump)
        }
        if ($b.Count -gt 0) { $cells += [pscustomobject]@{ Name = $s.name; Bounds = $b[0] } }
        else { Add-UiMiss -Where 'SpeedRow' -Selector "e2e.speed.$($s.name)" -Detail "no node with that id or the '$($s.text)' label" }
    }
    if ($cells.Count -eq 0) {
        Add-Result -Id '14b-speed-row' -Name 'six transport-mode cells rendered, equal width, not clipped' -Status 'SKIP' `
            -Detail ('no transport cell found (expected testTag e2e.speed.walk|run|bike|drive|plane|custom or the strings speed_walk..speed_custom). ' + (Format-UiSnapshot -Dump $Dump))
        return
    }
    $widths = @($cells | ForEach-Object { $_.Bounds.Width })
    $minW = ($widths | Measure-Object -Minimum).Minimum
    $maxW = ($widths | Measure-Object -Maximum).Maximum
    $spread = if ($minW -gt 0) { $maxW / $minW } else { 99 }
    $screen = Get-ScreenSize
    $offScreen = @($cells | Where-Object { $_.Bounds.Left -lt 0 -or $_.Bounds.Right -gt $screen.Width })
    $ok = ($cells.Count -eq 6) -and ($minW -gt 20) -and ($spread -le 1.25) -and ($offScreen.Count -eq 0)
    Test-Result -Id '14b-speed-row' -Name 'six transport-mode cells rendered, equal width, not clipped' -Condition $ok `
        -Detail ("cells=$($cells.Count)/6 widths=$($widths -join ',') spread=$([Math]::Round($spread,2)) offscreen=$($offScreen.Count)") `
        -Metrics @{ count = $cells.Count; widths = $widths; spread = [Math]::Round($spread, 2) }
}

# ------------------------------------------------------------------------------------------------
# 14c. design-token geometry from docs/UIUX-REWORK.md §2/§4 — measured, not eyeballed
#      search pill 56dp, joystick pad 152dp, horizontal chrome padding 16dp, FAB 56dp
# ------------------------------------------------------------------------------------------------
function Invoke-TokenGeometryCheck {
    param([double]$SearchPillDp = 56.0, [double]$JoystickDp = 152.0, [double]$PaddingDp = 16.0, [double]$ToleranceDp = 8.0)

    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    $screen = Get-ScreenSize
    function ConvertTo-Dp([int]$Px) { return [Math]::Round($Px * 160.0 / $screen.Density, 1) }

    $dump = Get-UiXml
    $bar = Get-UiElement -Name 'searchbar' -Dump $dump
    # the pad is off by default and Get-UiElement would record a miss for it: use the FAB-aware opener
    $pad = Ensure-JoystickPad
    $fabs = [ordered]@{}
    foreach ($n in @('fabTools', 'fabLayers', 'fabJoystick', 'fabLocate')) {
        $el = Get-UiElement -Name $n -Dump $dump
        if ($el) { $fabs[$n] = ConvertTo-Dp $el.Width }
    }

    $barHeightDp = if ($bar) { ConvertTo-Dp $bar.Height } else { -1 }
    $barLeftDp = if ($bar) { ConvertTo-Dp $bar.Left } else { -1 }
    $padDp = if ($pad) { [Math]::Round([Math]::Min($pad.Width, $pad.Height) * 160.0 / $screen.Density, 1) } else { -1 }
    $fabSummary = (@($fabs.Keys | ForEach-Object { "$_=$($fabs[$_])" }) -join ' ')
    # peek detent height is a design-review number the UI owner asked for explicitly
    $peek = Get-UiElement -Name 'peek' -Dump $dump -Optional
    $peekDp = if ($peek) { ConvertTo-Dp $peek.Height } else { -1 }

    $problems = @()
    if ($barHeightDp -lt 0) { $problems += 'search bar (e2e.searchbar) not found' }
    elseif ([Math]::Abs($barHeightDp - $SearchPillDp) -gt $ToleranceDp) { $problems += "search pill height ${barHeightDp}dp != ${SearchPillDp}dp" }
    if ($barLeftDp -ge 0 -and [Math]::Abs($barLeftDp - $PaddingDp) -gt ($ToleranceDp + 4)) { $problems += "search bar left padding ${barLeftDp}dp != ${PaddingDp}dp" }
    if ($padDp -lt 0) { $problems += 'joystick pad not visible (needed for the 152dp check)' }
    elseif ([Math]::Abs($padDp - $JoystickDp) -gt ($ToleranceDp + 4)) { $problems += "joystick pad ${padDp}dp != ${JoystickDp}dp" }

    # only fail on the tokens the spec states explicitly; FAB size is reported for review
    $hardProblems = @($problems | Where-Object { $_ -notmatch 'FAB' })
    $ok = ($hardProblems.Count -eq 0)
    Test-Result -Id '14c-token-geometry' -Name 'design tokens measured on device (search pill 56dp, joystick 152dp, 16dp padding)' -Condition $ok `
        -Detail ("searchPill=${barHeightDp}dp (left ${barLeftDp}dp), joystick=${padDp}dp, peek=${peekDp}dp, FABs: $fabSummary dp @ $($screen.Density)dpi ($($screen.Width)x$($screen.Height)px)" + $(if ($ok) { '' } else { ' — ' + ($hardProblems -join '; ') })) `
        -Metrics @{
            searchPillDp = $barHeightDp; searchLeftDp = $barLeftDp; joystickDp = $padDp; peekHeightDp = $peekDp
            fabToolsDp = $fabs['fabTools']; fabLayersDp = $fabs['fabLayers']
            fabJoystickDp = $fabs['fabJoystick']; fabLocateDp = $fabs['fabLocate']
            density = $screen.Density; pxWidth = $screen.Width; pxHeight = $screen.Height
        }
}

# ------------------------------------------------------------------------------------------------
# 15a. route planning through the UI
# ------------------------------------------------------------------------------------------------
function Invoke-RouteUiCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    Set-UiTestState -Config @{ mode = 'STATIC' }
    $dump = Get-UiXml

    # open the planner: primary action row entry, else the peek row / tools.
    # Measured contract (route-planner, 2026-11-01): picking a destination row plans automatically;
    # `e2e.route.planning` exists only while planning; `e2e.route.start` is disabled until the plan
    # is ready (and enabled even in the straight-line fallback, per UIUX-REWORK §6.6).
    $dump = Open-RoutePlanner
    if (-not $dump) {
        Add-Result -Id '15a-route-ui' -Name 'route planner opens from the peek sheet and can simulate a plan' -Status 'SKIP' `
            -Detail ('route planner surface not reachable from the UI tree (expected testTag e2e.route.planner / action 路线). ' + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    $planner = Get-UiElement -Name 'routePlanner' -Dump $dump

    # destination: coordinates are parsed locally (CoordinateParser runs before any network call),
    # so the search returns exactly one deterministic row tagged `e2e.route.search.place.0` and the
    # tap sets the destination + triggers planning.
    $dest = Get-UiElement -Name 'routeDest' -Dump $dump
    $typed = $false
    $placeTapped = $false
    $coordText = ("{0:F4},{1:F4}" -f $POINT_C.lat, $POINT_C.lon)
    if ($dest) {
        Invoke-UiTapPoint -X $dest.Cx -Y $dest.Cy
        Start-Sleep -Seconds 2
        $searchDump = Get-UiXml
        $fieldHits = @(Get-UiBounds -Id 'e2e.route.search' -Dump $searchDump)
        $fieldNode = if ($fieldHits.Count -gt 0) { $fieldHits[0] } else { @(Get-UiTextFields -Dump $searchDump) | Select-Object -First 1 }
        if ($fieldNode) {
            # ENTER fires KeyboardActions(onSearch) immediately; the debounce would need >=2 chars
            Set-UiText -Value $coordText -Field $fieldNode -Enter | Out-Null
    # A row drawn under the IME is indistinguishable from a broken click target: close the
    # keyboard before any tap that follows typing.
    Close-UiKeyboard
            $place = Wait-UiBounds -Id 'e2e.route.search.place.0' -TimeoutSec 10
            if (-not $place) {
                # fallback: first clickable result row below the search field
                $resDump = Get-UiXml
                $row = @(Get-UiNodes -Dump $resDump | Where-Object {
                        $_.Clickable -and $_.Top -gt $fieldNode.Bottom -and $_.Text -and $_.Text -notmatch '^(最近|搜索|Search)'
                    }) | Select-Object -First 1
                $place = $row
            }
            if ($place) {
                Invoke-UiTapPoint -X $place.Cx -Y $place.Cy
                $placeTapped = $true
                Start-Sleep -Seconds 1
            }
            else {
                Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
                Start-Sleep -Milliseconds 900
            }
        }
    }

    # the tap on a result triggers plan(); `e2e.route.planning` only exists while it runs
    $planningGone = Wait-UiGone -Id 'e2e.route.planning' -TimeoutSec 30
    $dump = Get-UiXml
    $resultCard = Get-UiElement -Name 'routeResult' -Dump $dump
    $notice = Get-UiElement -Name 'routeNotice' -Dump $dump
    $sourceText = (@(Get-UiNodes -Dump $dump | Where-Object { $_.Text -and $_.Text -match '数据来源|道路路径|直线连接|OSRM|高德路径' }) |
            ForEach-Object { $_.Text.Trim() }) -join ' / '
    $startBtn = Get-UiElement -Name 'routeStart' -Dump $dump
    if (-not $startBtn) {
        Add-Result -Id '15a-route-ui' -Name 'route planner opens from the peek sheet and can simulate a plan' -Status 'FAIL' `
            -Detail ("planner reached ($(Format-Rect $planner)) but no e2e.route.start button; dest=$(Format-Rect $dest) typed=$typed planningGone=$planningGone. screen: " + (Format-UiSnapshot -Dump $dump))
        return
    }
    # switch to 驾车 so playback covers a measurable distance in the sample window
    $drive = Get-UiElement -Name 'routeModeDrive' -Dump $dump
    if ($drive) {
        Invoke-UiTapPoint -X $drive.Cx -Y $drive.Cy
        Start-Sleep -Seconds 1
        Wait-UiGone -Id 'e2e.route.planning' -TimeoutSec 30 | Out-Null
    }
    $startReady = Wait-UiEnabled -Id 'e2e.route.start' -TimeoutSec 20
    $moved = 0.0
    if ($startReady) {
        $mark = Get-VerifyMark
        Invoke-UiTapPoint -X $startReady.Cx -Y $startReady.Cy
        Start-Sleep -Seconds 10
        $samples = @(Get-MoveSamples -AfterTs $mark)
        $track = Measure-Track -Samples $samples
        $moved = $track.meters
    }
    $typed = $typed -or $placeTapped -or [bool](@(Get-UiNodes -Dump (Get-UiXml) | Where-Object { $_.Text -and $_.Text -match '\d{2,3}\.\d{3,}' }).Count -gt 0)
    $ok = $typed -and [bool]$startReady -and ($moved -gt 5)
    Test-Result -Id '15a-route-ui' -Name 'route planner opens from the peek sheet and can simulate a plan' -Condition $ok `
        -Detail ("planner=$(Format-Rect $planner) destField=$([bool]$dest) coordTyped=$typed resultCard=$([bool]$resultCard) source='$sourceText' straightLineNotice=$([bool]$notice) planningGone=$planningGone startEnabled=$([bool]$startReady) movedAfterStart=$moved m") `
        -Metrics @{
            planner = (Format-Rect $planner); destField = [bool]$dest; typed = $typed; placeTapped = $placeTapped
            resultCard = [bool]$resultCard; source = $sourceText; notice = [bool]$notice
            planningGone = $planningGone; startEnabled = [bool]$startReady; movedMeters = $moved
        }
}

# Opens the route planner from whatever chrome state the app is in.  Returns the dump afterwards
# (or $null when the surface could not be reached).
function Open-RoutePlanner {
    param([int]$SettleMs = 1500)
    $dump = Get-UiXml
    if (Get-UiElement -Name 'routePlanner' -Dump $dump -Optional) { return $dump }
    $entry = Get-UiElement -Name 'actionRoute' -Dump $dump -Optional
    if ($entry) {
        Invoke-UiTapPoint -X $entry.Cx -Y $entry.Cy
        Start-Sleep -Milliseconds $SettleMs
        $dump = Get-UiXml
        if (Get-UiElement -Name 'routePlanner' -Dump $dump -Optional) { return $dump }
    }
    $peek = Get-UiElement -Name 'peek' -Dump $dump -Optional
    if ($peek) {
        Invoke-UiSwipeDirection -Bounds $peek -Direction up -DistanceDp 400 -DurationMs 500 | Out-Null
        Start-Sleep -Milliseconds $SettleMs
        $dump = Get-UiXml
        if (Get-UiElement -Name 'routePlanner' -Dump $dump -Optional) { return $dump }
        $peek2 = Get-UiElement -Name 'peek' -Dump $dump -Optional
        if ($peek2) {
            Invoke-UiSwipeDirection -Bounds $peek2 -Direction up -DistanceDp 600 -DurationMs 400 | Out-Null
            Start-Sleep -Milliseconds $SettleMs
            $dump = Get-UiXml
        }
    }
    if (Get-UiElement -Name 'routePlanner' -Dump $dump -Optional) { return $dump }
    return $null
}

# ------------------------------------------------------------------------------------------------
# 15c. multi-waypoint planning (途经点): the planner must add fields, not just show one route
# ------------------------------------------------------------------------------------------------
function Invoke-RouteWaypointCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    $dump = Open-RoutePlanner
    if (-not $dump) {
        Add-Result -Id '15c-waypoints' -Name 'route planner supports adding waypoints' -Status 'SKIP' `
            -Detail ('route planner surface not reachable. ' + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    $before = @(Get-UiNodes -Dump $dump | Where-Object { $_.Id -match 'e2e\.route\.waypoint\.\d+' }).Count
    # travel-mode chips live on the planner page, so count them BEFORE opening the picker
    $modes = @(Get-UiNodes -Dump $dump | Where-Object { $_.Id -match 'e2e\.route\.mode\.(walk|bike|drive)' }).Count
    $addBtn = Get-UiElement -Name 'routeAddWaypoint' -Dump $dump
    $after = $before
    $pickerOpened = $false
    if ($addBtn) {
        Invoke-UiTapPoint -X $addBtn.Cx -Y $addBtn.Cy
        Start-Sleep -Seconds 2
        # tapping "+" opens the place picker; a waypoint only exists once a stop is chosen
        $pickerDump = Get-UiXml
        $fieldHits = @(Get-UiBounds -Id 'e2e.route.search' -Dump $pickerDump)
        $fieldNode = if ($fieldHits.Count -gt 0) { $fieldHits[0] } else { @(Get-UiTextFields -Dump $pickerDump) | Select-Object -First 1 }
        $pickerOpened = [bool]$fieldNode
        if ($fieldNode) {
            Set-UiText -Value ("{0:F4},{1:F4}" -f $POINT_B.lat, $POINT_B.lon) -Field $fieldNode -Enter | Out-Null
    # A row drawn under the IME is indistinguishable from a broken click target: close the
    # keyboard before any tap that follows typing.
    Close-UiKeyboard
            $place = Wait-UiBounds -Id 'e2e.route.search.place.0' -TimeoutSec 10
            if ($place) {
                Invoke-UiTapPoint -X $place.Cx -Y $place.Cy
                Start-Sleep -Seconds 2
            }
        }
        $after = @(Get-UiNodes -Dump (Get-UiXml) | Where-Object { $_.Id -match 'e2e\.route\.waypoint\.\d+' }).Count
    }
    $ok = [bool]$addBtn -and $pickerOpened -and ($after -gt $before) -and ($modes -ge 3)
    Test-Result -Id '15c-waypoints' -Name 'route planner supports adding waypoints' -Condition $ok `
        -Detail ("add button=$([bool]$addBtn) picker opened=$pickerOpened waypoint fields $before -> $after, travel-mode chips=$modes") `
        -Metrics @{ addButton = [bool]$addBtn; pickerOpened = $pickerOpened; before = $before; after = $after; modes = $modes }
}

# ------------------------------------------------------------------------------------------------
# 15d. clearing the route returns the planner to its fresh shape
# ------------------------------------------------------------------------------------------------
# Guards the "the primary is rendered while it cannot act" class (P2, removed twice by hand tonight).
# ui-polish's A/B dump proved the shape: with a destination, start/plan/clear are all present; after
# `e2e.route.clear`, `plan` is absent, `clear` is absent and the hint line is back. The same pair also
# produced a RETRACTED finding, which is exactly why this belongs in a check and not in a procedure:
# any future entry point (saved route, GPX import, deep link) can reintroduce the state, and a
# dump-based assertion catches it regardless of how it was reached.
#
# CAPTURE RULE this depends on (docs/E2E.md): when an action shows a snackbar, the snackbar in the
# same frame is the tell that the dump is MID-TRANSITION. Both of tonight's planner "defects" were the
# clear-transition frame read as a settled state, so the layout is judged only after it is gone.
function Invoke-RouteClearCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    $dump = Open-RoutePlanner
    if (-not $dump) {
        Add-Result -Id '15d-route-clear' -Name 'clearing the route returns the planner to its fresh shape' -Status 'SKIP' `
            -Detail ('route planner surface not reachable. ' + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    # --- set a destination so the primary cannot be present for free -----------------------------
    $dest = Get-UiElement -Name 'routeDest' -Dump $dump -Optional
    $destSet = $false
    if ($dest) {
        Invoke-UiTapPoint -X $dest.Cx -Y $dest.Cy
        Start-Sleep -Seconds 2
        $pickerDump = Get-UiXml
        $fieldHits = @(Get-UiBounds -Id 'e2e.route.search' -Dump $pickerDump)
        $fieldNode = if ($fieldHits.Count -gt 0) { $fieldHits[0] } else { @(Get-UiTextFields -Dump $pickerDump) | Select-Object -First 1 }
        if ($fieldNode) {
            Set-UiText -Value ("{0:F4},{1:F4}" -f $POINT_B.lat, $POINT_B.lon) -Field $fieldNode -Enter | Out-Null
            Close-UiKeyboard
            $place = Wait-UiBounds -Id 'e2e.route.search.place.0' -TimeoutSec 10
            if ($place) {
                Invoke-UiTapPoint -X $place.Cx -Y $place.Cy
                Start-Sleep -Seconds 2
                $destSet = $true
            }
        }
    }
    Wait-UiGone -Id 'e2e.route.planning' -TimeoutSec 20 | Out-Null
    $beforeNodes = @(Get-UiNodes -Dump (Get-UiXml))
    $planBefore = @($beforeNodes | Where-Object { $_.Id -eq 'e2e.route.plan' }).Count
    $clearBefore = @($beforeNodes | Where-Object { $_.Id -eq 'e2e.route.clear' }).Count
    # --- clear, then wait out the transition -----------------------------------------------------
    $clearBtn = $beforeNodes | Where-Object { $_.Id -eq 'e2e.route.clear' } | Select-Object -First 1
    $cleared = $false
    $snackState = 'not-cleared'
    if ($clearBtn) {
        Invoke-UiTapPoint -X $clearBtn.Cx -Y $clearBtn.Cy
        # The snackbar IS the transition marker: if it is up, the frame is mid-animation and any layout
        # judged from it is wrong (both of ui-polish's planner "defects" were exactly that). Distinguish
        # "no snackbar appeared" from "appeared and drained" from "still visible" — a bare boolean read
        # as a failure last time round.
        $immediate = @(Get-UiNodes -Dump (Get-UiXml) | Where-Object { $_.Id -eq 'e2e.snackbar' }).Count -gt 0
        if ($immediate) {
            $gone = Wait-UiGone -Id 'e2e.snackbar' -TimeoutSec 10
            $snackState = if ($gone) { 'drained' } else { 'STILL-VISIBLE' }
        }
        else { $snackState = 'none' }
        Start-Sleep -Milliseconds 600
        $cleared = $true
    }
    $afterNodes = @(Get-UiNodes -Dump (Get-UiXml))
    $planAfter = @($afterNodes | Where-Object { $_.Id -eq 'e2e.route.plan' }).Count
    $clearAfter = @($afterNodes | Where-Object { $_.Id -eq 'e2e.route.clear' }).Count
    $hintAfter = @($afterNodes | Where-Object { $_.Text -match '选择终点后即可规划路线' }).Count
    $ok = $destSet -and ($planBefore -gt 0) -and $cleared -and ($planAfter -eq 0) -and ($hintAfter -gt 0)
    $detail = "destination set=$destSet; before clear: plan=$planBefore clear=$clearBefore; " +
    "after clear (post-transition): plan=$planAfter clear=$clearAfter hint=$hintAfter snackbar=$snackState"
    Test-Result -Id '15d-route-clear' -Name 'clearing the route returns the planner to its fresh shape' -Condition $ok `
        -Detail $detail `
        -Metrics @{ destinationSet = $destSet; planBefore = $planBefore; clearBefore = $clearBefore; planAfter = $planAfter; clearAfter = $clearAfter; hintAfter = $hintAfter; snackbar = $snackState }
}

# ------------------------------------------------------------------------------------------------
# 15b. playback follows a polyline and progress increases (engine-level, deterministic geometry)
# ------------------------------------------------------------------------------------------------
function Invoke-RouteFollowCheck {
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream

    # dog-leg: A -> 250 m east -> 250 m north.  A straight-line follower would cut the corner by
    # ~90 m, which is what makes "follows the polyline" a real assertion.
    $eastLon = $POINT_A.lon + 250.0 / (111320.0 * [Math]::Cos($POINT_A.lat * [Math]::PI / 180.0))
    $northLat = $POINT_A.lat + 250.0 / 110540.0
    $poly = @(
        @{ lat = $POINT_A.lat; lon = $POINT_A.lon; alt = 50.0 },
        @{ lat = $POINT_A.lat; lon = $eastLon; alt = 50.0 },
        @{ lat = $northLat; lon = $eastLon; alt = 50.0 }
    )
    $speed = 40.0
    Set-EngineConfig @{ mode = 'ROUTE'; updateIntervalMs = 500; routeSpeedMps = $speed; routeRepeat = $false } | Out-Null
    $mark = Get-VerifyMark
    Send-StartRoute -Points $poly -SpeedMps $speed -Repeat $false -Channel $Channel | Out-Null
    Send-PlayRoute -Channel $Channel | Out-Null
    Send-Start -Channel $Channel | Out-Null

    $deadline = (Get-Date).AddSeconds(30)
    $progressSeries = @()
    $maxOffPolyline = 0.0
    $lastProgress = -1.0
    $monotonic = $true
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $samples = @(Get-MoveSamples -AfterTs $mark)
        $progressSeries = @()
        $maxOffPolyline = 0.0
        foreach ($s in $samples) {
            $pp = Get-PointToPolyline -Lat $s.lat -Lon $s.lon -Points $poly
            $progressSeries += $pp.Progress
            if ($pp.DistanceMeters -gt $maxOffPolyline) { $maxOffPolyline = $pp.DistanceMeters }
        }
        if ($progressSeries.Count -ge 2) { break }
    }
    Send-PauseRoute -Channel $Channel | Out-Null
    Send-StopRoute -Channel $Channel | Out-Null

    $prev = $null
    foreach ($p in $progressSeries) {
        if ($null -ne $prev -and $p -lt ($prev - 0.02)) { $monotonic = $false }
        $prev = $p
    }
    $firstProgress = if ($progressSeries.Count -gt 0) { $progressSeries[0] } else { 0.0 }
    $lastProgress = if ($progressSeries.Count -gt 0) { $progressSeries[-1] } else { 0.0 }
    $totalMeters = (Get-PointToPolyline -Lat $POINT_A.lat -Lon $POINT_A.lon -Points $poly).TotalMeters
    $ok = ($progressSeries.Count -ge 3) -and $monotonic -and ($lastProgress -gt ($firstProgress + 0.2)) -and ($maxOffPolyline -lt 60.0)
    Test-Result -Id '15b-route-follow' -Name 'route playback follows the polyline and progress increases monotonically' -Condition $ok `
        -Detail ("polyline $( [Math]::Round($totalMeters) ) m (dog-leg), samples=$($progressSeries.Count), progress $([Math]::Round($firstProgress,3)) -> $([Math]::Round($lastProgress,3)) monotonic=$monotonic, max off-polyline=$([Math]::Round($maxOffPolyline,1)) m") `
        -Metrics @{ samples = $progressSeries.Count; first = $firstProgress; last = $lastProgress; monotonic = $monotonic; offPolyline = [Math]::Round($maxOffPolyline, 1); totalMeters = [Math]::Round($totalMeters, 1); speed = $speed }
}

# ------------------------------------------------------------------------------------------------
# 16. AMap 3-minute stability (no revert to the real position)
# ------------------------------------------------------------------------------------------------
function Invoke-AmapStabilityCheck {
    param([int]$Seconds = 180, [double]$FakeLat = 39.9087, [double]$FakeLon = 116.3975)

    if (-not (Test-PackageInstalled $script:AmapPackage)) {
        Add-Result -Id '16-amap-stability' -Name 'AMap 3-min stability evidence captured (verdict is visual)' -Status 'SKIP' -Detail "$($script:AmapPackage) is not installed"
        return
    }
    # WHY THIS CHECK LOOKS DIFFERENT FROM THE OTHERS
    # `dumpsys location` reports the framework's copy of the fix — which is OUR injected value. During
    # the real 高德 revert (the app drawing Hangzhou at t+16 s) dumpsys still showed the injected
    # Beijing point, so a dumpsys-based assertion PASSES while the app is visibly wrong. It can only
    # detect a framework-layer break, which is necessary but not sufficient.
    # Therefore: (a) keep the framework assertion, labelled as such, and
    #            (b) collect the *visual* evidence the verdict actually rests on — hook-loc's harness
    #                (hook/harness/amap_verify.ps1) cold-starts 高德, pans/zooms and screenshots at
    #                fixed marks; the PNGs are then reviewed by eye and recorded in docs/ACCEPTANCE.md C1.
    $frameworkOk = $false
    Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Send-SetPosition -Lat $FakeLat -Lon $FakeLon -Channel $Channel | Out-Null
    Start-Sleep -Seconds 4
    $fix = Get-NearestFix -Lat $FakeLat -Lon $FakeLon
    if ($fix -and $fix.DistanceMeters -le 50) { $frameworkOk = $true }

    $harness = Join-Path $RepoRoot 'hook\harness\amap_verify.ps1'
    $evidenceDir = Join-Path $RepoRoot 'docs\screenshots\amap-verify'
    if (-not (Test-Path $harness)) {
        Add-Result -Id '16-amap-stability' -Name 'AMap 3-min stability evidence captured (verdict is visual)' -Status 'FAIL' `
            -Detail "missing $harness (the screenshot harness this check delegates to); frameworkOk=$frameworkOk"
        return
    }
    Write-Step "collecting 高德 visual evidence via hook/harness/amap_verify.ps1 for ${Seconds}s"
    $harnessOk = $true
    try {
        & $harness -Seconds $Seconds -Lat $FakeLat -Lon $FakeLon -OutDir $evidenceDir 2>&1 |
            Where-Object { $_ -notmatch '^\s*$' } | Select-Object -Last 3 | ForEach-Object { Write-Step "  $_" }
    }
    catch {
        $harnessOk = $false
        Write-Warn2 "amap_verify.ps1 failed: $_"
    }

    # assert that the EVIDENCE is complete and usable, not that the app behaved (that is the review)
    $expected = @(@(4, 12, 20, 30, 45, 60, 90, 120, 150, 180) | Where-Object { $_ -le $Seconds })
    $pngs = @(Get-ChildItem -Path $evidenceDir -Filter 'amap_t*.png' -ErrorAction SilentlyContinue | Sort-Object Name)
    $redexpected = $null
    $tooSmall = @($pngs | Where-Object { $_.Length -lt 20000 })
    $missing = @($expected | Where-Object {
            $t = $_; -not ($pngs | Where-Object { $_.BaseName -match "amap_t\s*$t$" })
        })
    # Identical frames mean the app stopped changing (a dialog in front, a frozen surface): six copies
    # of the same screenshot are not six samples of stability. A real run has ~10 distinct frames.
    $hashes = @($pngs | ForEach-Object { (Get-FileHash $_.FullName -Algorithm SHA256).Hash })
    $distinct = @($hashes | Select-Object -Unique).Count
    $frozen = ($pngs.Count -gt 3) -and ($distinct -le [Math]::Ceiling($pngs.Count * 0.6))
    $hookLog = Join-Path $evidenceDir 'logs\amap_verify_hook.log'
    $redactions = 0
    if (Test-Path $hookLog) { $redactions = @(Get-Content $hookLog | Where-Object { $_ -match 'wifi identity redacted' }).Count }
    # the harness also writes a machine-readable summary; prefer it when present (hook-loc's contract)
    $summaryJson = Join-Path $evidenceDir 'amap_verify.json'
    $summarySamples = -1
    $summaryForeground = $null
    $summaryRedactions = -1
    if (Test-Path $summaryJson) {
        try {
            $j = Get-Content $summaryJson -Raw | ConvertFrom-Json
            if ($j.PSObject.Properties['samples']) { $summarySamples = @($j.samples).Count }
            if ($j.PSObject.Properties['foregroundOk']) { $summaryForeground = [bool]$j.foregroundOk }
            if ($j.PSObject.Properties['wifiRedactions']) { $summaryRedactions = [int]$j.wifiRedactions }
        }
        catch { Write-Warn2 "could not parse ${summaryJson}: $_" }
    }
    $foreground = Test-PackageForeground -Package $script:AmapPackage

    $evidenceOk = $harnessOk -and ($pngs.Count -ge [Math]::Max(3, $expected.Count - 1)) -and
        ($tooSmall.Count -eq 0) -and ($missing.Count -eq 0) -and $foreground
    $verdict = 'PENDING VISUAL REVIEW — open the PNGs in ' + $evidenceDir + ' and record the verdict in docs/ACCEPTANCE.md C1'
    Test-Result -Id '16-amap-stability' -Name 'AMap 3-min stability evidence captured (verdict is visual)' -Condition $evidenceOk `
        -Detail ("${Seconds}s: screenshots=$($pngs.Count)/$($expected.Count) missing=[$($missing -join ',')] tooSmall=$($tooSmall.Count) foreground=$foreground " +
            "framework-injection-consistent=$frameworkOk (necessary, NOT sufficient) wifiRedactionLines=$redactions " +
            "json[samples=$summarySamples foregroundOk=$summaryForeground redactions=$summaryRedactions] | $verdict") `
        -Metrics @{
            seconds = $Seconds; screenshots = $pngs.Count; missing = $missing; tooSmall = $tooSmall.Count
            frameworkConsistent = $frameworkOk; foreground = $foreground; redactionLines = $redactions
            jsonSamples = $summarySamples; jsonForegroundOk = $summaryForeground; jsonRedactions = $summaryRedactions
            evidenceDir = $evidenceDir; verdict = 'visual-review-required'
        }
}

# ------------------------------------------------------------------------------------------------
# 16b. the visual verdict, recorded as data (so ACCEPTANCE.md can cite a machine-readable result)
# ------------------------------------------------------------------------------------------------
function Set-AmapVisualVerdict {
    param([Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'PENDING')][string]$Verdict,
        [string]$FirstBadFrame = '', [string]$Note = '')
    $path = Join-Path $OutDir 'amap-visual-verdict.json'
    $obj = [ordered]@{
        verdict        = $Verdict
        firstBadFrame  = $FirstBadFrame
        note           = $Note
        evidenceDir    = 'docs/screenshots/amap-verify'
        reviewedAt     = (Get-Date).ToString('o')
        reviewedBy     = 'qa-e2e'
    }
    $obj | ConvertTo-Json -Depth 4 | Set-Content -Path $path -Encoding utf8
    return $obj
}

# ------------------------------------------------------------------------------------------------
# 17. Baidu Maps + 云运动 smoke
# ------------------------------------------------------------------------------------------------
function Invoke-ThirdPartySmokeCheck {
    param([double]$FakeLat = 39.9042, [double]$FakeLon = 116.4074, [double]$ToleranceMeters = 1500.0)

    Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Send-SetPosition -Lat $FakeLat -Lon $FakeLon -Channel $Channel | Out-Null
    Start-Sleep -Seconds 4

    $apps = @(
        @{ id = 'baidu'; pkg = $script:BaiduPackage; name = 'Baidu Maps' },
        @{ id = 'yunzhi'; pkg = $script:YunzhiPackage; name = '云运动' }
    )
    foreach ($app in $apps) {
        $checkId = "17-third-party-$($app.id)"
        if (-not (Test-PackageInstalled $app.pkg)) {
            Add-Result -Id $checkId -Name "$($app.name) smoke: launches and shows the simulated position" -Status 'SKIP' -Detail "$($app.pkg) is not installed"
            continue
        }
        Clear-Logcat
        $start = Invoke-AdbShell "monkey -p $($app.pkg) -c android.intent.category.LAUNCHER 1" -AllowFailure
        Start-Sleep -Seconds 8
        $foreground = Test-PackageForeground -Package $app.pkg
        $fix = Get-NearestFix -Lat $FakeLat -Lon $FakeLon
        $crash = @(Get-Logcat -Tag 'AndroidRuntime' -TailLines 4000 | Where-Object { $_ -match 'FATAL EXCEPTION' -and $_ -match [regex]::Escape($app.pkg) })
        $shot = $null
        # the call operator runs screenshot.ps1 in its own scope; dot-sourcing it here would clobber
        # the matrix' own variables and re-run its entry point in our scope.
        try { $shot = & (Join-Path $RepoRoot 'e2e\screenshot.ps1') -Name "acceptance-$($app.id)" -Quiet } catch { Write-Warn2 "screenshot failed: $_" }
        $ok = $foreground -and ($fix -and $fix.DistanceMeters -le $ToleranceMeters) -and ($crash.Count -eq 0)
        Test-Result -Id $checkId -Name "$($app.name) smoke: launches and shows the simulated position" -Condition $ok `
            -Detail ("foreground=$foreground fix=$(if ($fix) { "$($fix.DistanceMeters) m via $($fix.Provider)" } else { 'none' }) crash=$($crash.Count) screenshot=$shot") `
            -Metrics @{ foreground = $foreground; distanceMeters = $(if ($fix) { $fix.DistanceMeters } else { -1 }); crashes = $crash.Count; screenshot = $shot }
    }
}

# ------------------------------------------------------------------------------------------------
# 12b. heading-follow (朝向): with followHeading=true the joystick vector is rotated by the device
#      heading, so "stick up" must move along the heading — engine-level, no UI needed.
# ------------------------------------------------------------------------------------------------
function Invoke-JoystickHeadingCheck {
    param([double]$HeadingDeg = 90.0, [double]$HoldSec = 4.0, [double]$ToleranceDeg = 15.0)
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream
    Set-EngineConfig @{ mode = 'JOYSTICK'; speed = 'DRIVE'; followHeading = $true; updateIntervalMs = 500 } | Out-Null
    Send-Start -Channel $Channel | Out-Null
    Invoke-SetPositionAndVerify -Lat $POINT_A.lat -Lon $POINT_A.lon | Out-Null
    Send-SetHeading -Deg $HeadingDeg -Channel $Channel | Out-Null
    Start-Sleep -Seconds 2

    $fixStart = Get-DumpsysFix
    $mark = Get-VerifyMark
    $deadline = (Get-Date).AddSeconds($HoldSec)
    while ((Get-Date) -lt $deadline) {
        # x=0, y=+1 = "stick up"; with heading 90 that must become east
        Send-SetJoystick -X 0.0 -Y 1.0 -Channel $Channel | Out-Null
        Start-Sleep -Milliseconds 150
    }
    $until = Get-VerifyMark
    Send-ReleaseJoystick -Channel $Channel | Out-Null
    Start-Sleep -Milliseconds 800
    $fixEnd = Get-DumpsysFix

    $samples = @(Get-MoveSamples -AfterTs $mark -UntilTs $until)
    $track = Measure-Track -Samples $samples
    $meters = $track.meters
    $bearing = $track.bearing
    $source = 'verifier'
    $dTrack = Measure-DumpsysTrack -Start $fixStart -End $fixEnd
    if ($dTrack.ok -and $dTrack.meters -gt [Math]::Max($meters, 0.5)) {
        $meters = $dTrack.meters; $bearing = $dTrack.bearing; $source = "dumpsys:$($dTrack.provider)"
    }
    $delta = if ($meters -gt 1.0) { [Math]::Abs((Get-AngleDelta -From $HeadingDeg -To $bearing)) } else { 999.0 }
    $ok = ($meters -ge 2.0) -and ($delta -le $ToleranceDeg)
    Test-Result -Id '12b-follow-heading' -Name 'followHeading=true rotates the joystick vector by the device heading' -Condition $ok `
        -Detail ("heading=$HeadingDeg deg, stick up, moved $meters m at bearing $bearing deg (delta $([Math]::Round($delta,1)), source $source, samples $($samples.Count))") `
        -Metrics @{ heading = $HeadingDeg; meters = $meters; bearing = $bearing; delta = [Math]::Round($delta, 1); source = $source; samples = $samples.Count }
    Set-EngineConfig @{ followHeading = $false } | Out-Null
}

# ------------------------------------------------------------------------------------------------
# 18. update interval (更新频率) — the delivered cadence must follow SimulationConfig.updateIntervalMs
# ------------------------------------------------------------------------------------------------
function Invoke-UpdateIntervalCheck {
    Force-Stop (Get-ModulePackageName)
    Ensure-VerifierStream
    Send-SetPosition -Lat $POINT_A.lat -Lon $POINT_A.lon -Channel $Channel | Out-Null

    $gaps = [ordered]@{}
    foreach ($ms in @(1000, 3000)) {
        Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = $ms } | Out-Null
        Send-Start -Channel $Channel | Out-Null
        Send-SetPosition -Lat $POINT_A.lat -Lon $POINT_A.lon -Channel $Channel | Out-Null
        Start-Sleep -Seconds 2
        $mark = Get-VerifyMark
        Start-Sleep -Seconds 12
        $samples = @(Get-MoveSamples -AfterTs $mark)
        $delays = @()
        for ($i = 1; $i -lt $samples.Count; $i++) {
            $delays += ([double]$samples[$i].ts - [double]$samples[$i - 1].ts) / 1000.0
        }
        $median = if ($delays.Count -gt 0) { ($delays | Sort-Object)[[int]($delays.Count / 2)] } else { -1 }
        $gaps["${ms}ms"] = [Math]::Round($median, 2)
        Write-Host ("     updateIntervalMs={0,-5} samples={1,2}  median gap {2} s" -f $ms, $samples.Count, [Math]::Round($median, 2))
        Start-Sleep -Seconds 1
    }
    $fast = [double]$gaps['1000ms']
    $slow = [double]$gaps['3000ms']
    $ok = ($fast -gt 0) -and ($slow -gt 0) -and ($slow -ge ($fast * 1.3)) -and ($fast -le 2.5)
    Test-Result -Id '18-update-interval' -Name 'delivered cadence follows updateIntervalMs (1000 ms vs 3000 ms)' -Condition $ok `
        -Detail ("median sample gap: 1000ms -> $fast s, 3000ms -> $slow s (ratio $([Math]::Round($slow / [Math]::Max($fast, 0.01), 2)))") `
        -Metrics @{ fast = $fast; slow = $slow; ratio = [Math]::Round($slow / [Math]::Max($fast, 0.01), 2) }
}

# ------------------------------------------------------------------------------------------------
# 20. key-less map fallback (地图 Key-less 回退) — no "SDK 未配置 Key" banner, map still on screen
# ------------------------------------------------------------------------------------------------
function Invoke-KeylessMapCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 3
    $dump = Get-UiXml
    $bannerHits = @(Get-UiNodes -Dump $dump | Where-Object {
            $_.Text -and ($_.Text -match '未配置' -or $_.Text -match '(?i)sdk\s*未' -or $_.Text -match '(?i)map\s*key' -or $_.Text -match '请配置')
        })
    # a banner is only a banner when it is reachable without opening a settings surface; capture the
    # strings that would be shown instead of guessing from source code
    $sourceHits = @(Get-KeyBannerSourceHits)
    $shot = $null
    try { $shot = & (Join-Path $RepoRoot 'e2e\screenshot.ps1') -Name 'acceptance-keyless-map' -Quiet } catch { Write-Warn2 "screenshot failed: $_" }

    # Measured on device (2026-11-01, four-arm A/B): the in-app map IS the 高德 backend —
    # `AmapHost: amap map loaded (tiles rendered)` with the key the AAR ships — and osmdroid is only
    # the fallback. So the requirement is still "map renders + layer choice is reachable" (a key banner
    # or a dead surface is what must fail), but do not read this check as "the product runs osmdroid".
    $presets = @()
    $fabLayers = Get-UiElement -Name 'fabLayers' -Dump $dump
    if ($fabLayers) {
        Invoke-UiTapPoint -X $fabLayers.Cx -Y $fabLayers.Cy
        Start-Sleep -Seconds 2
        $layerDump = Get-UiXml
        foreach ($key in $script:TilePresetKeys) {
            $label = Get-AppString -Key $key
            if (-not $label) { continue }
            $b = @(Get-UiBounds -Text $label -Dump $layerDump)
            if ($b.Count -gt 0) { $presets += $key }
        }
        try { & (Join-Path $RepoRoot 'e2e\screenshot.ps1') -Name 'acceptance-keyless-layers' -Quiet | Out-Null } catch { }
        Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
    }
    $screen = Get-ScreenSize
    $mapSurface = @(Get-UiNodes -Dump $dump | Where-Object { $_.Width -ge ($screen.Width * 0.8) -and $_.Height -ge ($screen.Height * 0.5) })
    $ok = ($bannerHits.Count -eq 0) -and ($sourceHits.Count -eq 0) -and ($presets.Count -ge 3) -and ($mapSurface.Count -gt 0)
    Test-Result -Id '20-keyless-map' -Name 'map renders without a key banner and the 图层 sheet offers the tile presets' -Condition $ok `
        -Detail ("banner on screen=$($bannerHits.Count) [$(($bannerHits | ForEach-Object { $_.Text }) -join ' | ')] banner in sources=$($sourceHits.Count), tile presets in 图层=$($presets.Count) [$($presets -join ',')], full-screen map surfaces=$($mapSurface.Count), screenshot=$shot" + $(if (-not $ok) { "; screen: " + (Format-UiSnapshot -Dump $dump) } else { '' })) `
        -Metrics @{ screenHits = $bannerHits.Count; sourceHits = $sourceHits.Count; tilePresets = $presets.Count; mapSurfaces = $mapSurface.Count; screenshot = $shot }
}

# ------------------------------------------------------------------------------------------------
# 21. long-press place card (长按落针卡片)
# ------------------------------------------------------------------------------------------------
function Invoke-PlaceCardCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Start-Sleep -Seconds 2
    Set-UiTestState -Config @{ mode = 'STATIC' }
    $screen = Get-ScreenSize
    # press in the empty middle of the map (below the search bar, above the peek sheet)
    $x = [int]($screen.Width / 2)
    $y = [int]($screen.Height * 0.42)
    Invoke-LongPress -X $x -Y $y -DurationMs 1100
    Start-Sleep -Seconds 2
    $dump = Get-UiXml
    $card = Get-UiElement -Name 'placeCard' -Dump $dump
    $coordNode = @(Get-UiNodes -Dump $dump | Where-Object { $_.Text -and $_.Text -match '^\s*-?\d{1,3}\.\d{3,}' }) | Select-Object -First 1
    $ok = [bool]($card -or $coordNode)
    $shot = $null
    if ($ok) {
        try { $shot = & (Join-Path $RepoRoot 'e2e\screenshot.ps1') -Name 'acceptance-place-card' -Quiet } catch { }
    }
    Test-Result -Id '21-place-card' -Name 'long-press on the map opens the Material You place card' -Condition $ok `
        -Detail ("long-press at $x,$y -> card=$(if ($card) { Format-Rect $card } else { 'absent' }) coords=$(if ($coordNode) { $coordNode.Text } else { 'none' }) screenshot=$shot" + $(if (-not $ok) { "; screen: " + (Format-UiSnapshot -Dump $dump) } else { '' })) `
        -Metrics @{ card = [bool]$card; coords = $(if ($coordNode) { $coordNode.Text } else { '' }); screenshot = $shot }
    # dismiss whatever opened
    Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
}

# ------------------------------------------------------------------------------------------------
# 22. single-tap place chip (e2e.tapchip + 撤销 / 收藏 / 加入路线), visible for ~4 s after the tap
# ------------------------------------------------------------------------------------------------
function Invoke-TapChipCheck {
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Set-UiTestState -Config @{ mode = 'STATIC' }
    $screen = Get-ScreenSize
    $x = [int]($screen.Width / 2)
    $y = [int]($screen.Height * 0.42)
    $t0 = Get-Date
    Invoke-AdbShell "input tap $x $y" -AllowFailure | Out-Null
    # the chip lives ~4 s: poll fast, do not wait a fixed second and miss it
    $chip = $null
    $deadline = (Get-Date).AddSeconds(6)
    while ((Get-Date) -lt $deadline) {
        $dump = Get-UiXml
        $chip = Get-UiElement -Name 'tapChip' -Dump $dump
        if ($chip) { break }
        Start-Sleep -Milliseconds 300
    }
    $actions = @()
    if ($chip) {
        $dump = Get-UiXml
        foreach ($n in @('tapUndo', 'tapSave', 'tapRoute')) {
            if (Get-UiElement -Name $n -Dump $dump) { $actions += $n }
        }
        try { & (Join-Path $RepoRoot 'e2e\screenshot.ps1') -Name 'acceptance-tapchip' -Quiet | Out-Null } catch { }
    }
    $latencyMs = [int]((New-TimeSpan -Start $t0 -End (Get-Date)).TotalMilliseconds)
    $ok = [bool]$chip -and ($actions.Count -ge 2)
    Test-Result -Id '22-tapchip' -Name 'single tap on the map shows the place chip with 撤销/收藏/加入路线' -Condition $ok `
        -Detail ("tap at $x,$y -> chip=$(if ($chip) { Format-Rect $chip } else { 'absent' }) actions=[$($actions -join ',')] latency=${latencyMs}ms" + $(if (-not $ok) { "; screen: " + (Format-UiSnapshot -Dump (Get-UiXml)) } else { '' })) `
        -Metrics @{ chip = [bool]$chip; actions = $actions; latencyMs = $latencyMs }
    Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
}

# ------------------------------------------------------------------------------------------------
# 25. coordinate search (坐标搜索) — the pill is an inline search, not a modal
# ------------------------------------------------------------------------------------------------
function Invoke-CoordinateSearchCheck {
    param([double]$Lat = 39.908692, [double]$Lon = 116.397532, [double]$ToleranceMeters = 1500.0)
    if (-not (Start-UiApp)) { throw "the app did not come to the foreground (top=$(Get-TopActivity))" }
    Set-UiTestState -Config @{ mode = 'STATIC' }
    $bar = Get-UiElement -Name 'searchbar' -Fresh
    if (-not $bar) {
        Add-Result -Id '25-coordinate-search' -Name 'coordinate search moves the simulated position' -Status 'SKIP' `
            -Detail ('search bar not found (e2e.searchbar). ' + (Format-UiSnapshot -Dump (Get-UiXml)))
        return
    }
    $query = ("{0:F6},{1:F6}" -f $Lat, $Lon)
    Invoke-UiTapPoint -X $bar.Cx -Y $bar.Cy
    Start-Sleep -Milliseconds 800
    Set-UiText -Value $query | Out-Null
    # A row drawn under the IME is indistinguishable from a broken click target: close the
    # keyboard before any tap that follows typing.
    Close-UiKeyboard
    $card = Wait-UiBounds -Id 'e2e.search.suggestions' -TimeoutSec 8
    Start-Sleep -Milliseconds 800
    $dump = Get-UiXml
    # the coordinate query produces exactly one deterministic row; tap the first clickable text row
    # inside the suggestions card (ui-maps' inline card, tag e2e.search.suggestions)
    $row = $null
    if ($card) {
        $row = @(Get-UiNodes -Dump $dump | Where-Object {
                $_.Clickable -and $_.Text -and
                $_.Top -ge $card.Top -and $_.Bottom -le ($card.Bottom + 40) -and
                $_.Text -notmatch '^\s*$'
            }) | Select-Object -First 1
    }
    $fixBefore = Get-NearestFix -Lat $Lat -Lon $Lon
    if ($row) {
        Invoke-UiTapPoint -X $row.Cx -Y $row.Cy
        Start-Sleep -Seconds 3
    }
    $fix = Get-NearestFix -Lat $Lat -Lon $Lon
    $ok = [bool]$card -and [bool]$row -and ($fix -and $fix.DistanceMeters -le $ToleranceMeters)
    Test-Result -Id '25-coordinate-search' -Name 'coordinate search moves the simulated position' -Condition $ok `
        -Detail ("query='$query' suggestionsCard=$([bool]$card) resultRow=$(if ($row) { $row.Text } else { 'none' }) " +
            "nearestFix=$(if ($fix) { "$($fix.DistanceMeters) m via $($fix.Provider)" } else { 'none' }) (before: $(if ($fixBefore) { "$($fixBefore.DistanceMeters) m" } else { 'none' }))") `
        -Metrics @{ query = $query; card = [bool]$card; row = $(if ($row) { $row.Text } else { '' }); distanceMeters = $(if ($fix) { $fix.DistanceMeters } else { -1 }) }
    Close-UiKeyboard
}

# ------------------------------------------------------------------------------------------------
# dispatcher
# ------------------------------------------------------------------------------------------------
function Invoke-ReworkChecks {
    param([scriptblock]$Selector = $null, [int]$AmapSeconds = 180)

    $isSelected = {
        param([string]$Id)
        if (-not $Selector) { return $true }
        return [bool](& $Selector $Id)
    }

    if (& $isSelected '12') {
        Write-Step '12) joystick direction (up=north, right=east, +-15 deg)'
        Invoke-Guarded -Id '12-joystick-direction' -Name 'joystick drag direction' -Body { Invoke-JoystickDirectionCheck }
        Write-Step '12b) followHeading rotates the joystick vector by the device heading'
        Invoke-Guarded -Id '12b-follow-heading' -Name 'joystick follows heading' -Body { Invoke-JoystickHeadingCheck }
    }
    if (& $isSelected '13') {
        Write-Step '13) no Toast (sources + windows)'
        Invoke-Guarded -Id '13-no-toast' -Name 'no Toast' -Body { Invoke-NoToastCheck }
    }
    if (& $isSelected '14') {
        Write-Step '14) chrome geometry: snackbar / joystick / sheet / search bar / FABs'
        Invoke-Guarded -Id '14-chrome-overlap' -Name 'chrome overlap' -Body { Invoke-ChromeOverlapCheck }
        Write-Step '14c) design tokens measured on device (56dp pill / 152dp pad / 16dp padding)'
        Invoke-Guarded -Id '14c-token-geometry' -Name 'design tokens' -Body { Invoke-TokenGeometryCheck }
    }
    if (& $isSelected '15') {
        Write-Step '15a) route planner (UI)'
        Invoke-Guarded -Id '15a-route-ui' -Name 'route planner UI' -Body { Invoke-RouteUiCheck }
        Write-Step '15c) multi-waypoint planning'
        Invoke-Guarded -Id '15c-waypoints' -Name 'route waypoints' -Body { Invoke-RouteWaypointCheck }
        Write-Step '15b) route playback follows the polyline'
        Invoke-Guarded -Id '15b-route-follow' -Name 'route follows polyline' -Body { Invoke-RouteFollowCheck }
        Write-Step '15d) clearing the route returns the fresh planner shape'
        Invoke-Guarded -Id '15d-route-clear' -Name 'route clear shape' -Body { Invoke-RouteClearCheck }
    }
    # `-Only 15d` should be able to run the clear-shape assertion alone (it is the one an app change is
    # most likely to break), without dragging the whole 15 family behind it.
    elseif (& $isSelected '15d') {
        Write-Step '15d) clearing the route returns the fresh planner shape'
        Invoke-Guarded -Id '15d-route-clear' -Name 'route clear shape' -Body { Invoke-RouteClearCheck }
    }
    if (& $isSelected '16') {
        Write-Step "16) AMap stability for ${AmapSeconds}s"
        Invoke-Guarded -Id '16-amap-stability' -Name 'AMap stability' -Body { Invoke-AmapStabilityCheck -Seconds $AmapSeconds }
    }
    if (& $isSelected '17') {
        Write-Step '17) third-party smoke: 百度地图 + 云运动'
        Invoke-Guarded -Id '17-third-party-baidu' -Name 'third-party smoke' -Body { Invoke-ThirdPartySmokeCheck }
    }
    if (& $isSelected '18') {
        Write-Step '18) update interval (更新频率)'
        Invoke-Guarded -Id '18-update-interval' -Name 'update interval' -Body { Invoke-UpdateIntervalCheck }
    }
    if (& $isSelected '20') {
        Write-Step '20) key-less map fallback'
        Invoke-Guarded -Id '20-keyless-map' -Name 'key-less map' -Body { Invoke-KeylessMapCheck }
    }
    if (& $isSelected '21') {
        Write-Step '21) long-press place card'
        Invoke-Guarded -Id '21-place-card' -Name 'place card' -Body { Invoke-PlaceCardCheck }
    }
    if (& $isSelected '22') {
        Write-Step '22) single-tap place chip'
        Invoke-Guarded -Id '22-tapchip' -Name 'tap chip' -Body { Invoke-TapChipCheck }
    }
    if (& $isSelected '25') {
        Write-Step '25) coordinate search (坐标搜索)'
        Invoke-Guarded -Id '25-coordinate-search' -Name 'coordinate search' -Body { Invoke-CoordinateSearchCheck }
    }

    # one row for the whole selector audit, plus e2e/out/ui-misses.txt for the UI owner
    $misses = @(Export-UiMissReport)
    if ($misses.Count -gt 0) {
        Add-Result -Id 'harness-selectors' -Name 'every e2e.* selector resolved on the device' -Status 'WARN' `
            -Detail ("$($misses.Count) selector(s) did not resolve — see e2e/out/ui-misses.txt: " + (($misses | ForEach-Object { $_.Selector }) -join ', ')) `
            -Metrics @{ misses = @($misses | ForEach-Object { "$($_.Where) <- $($_.Selector)" }) }
    }
    else {
        Add-Result -Id 'harness-selectors' -Name 'every e2e.* selector resolved on the device' -Status 'PASS' `
            -Detail 'all e2e.* selectors resolved (e2e/out/ui-misses.txt: none)'
    }
}
