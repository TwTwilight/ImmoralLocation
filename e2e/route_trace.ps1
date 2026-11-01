<#
.SYNOPSIS
    Drive the route planner through a real three-stop plan and capture the three artifacts another
    engineer needs to audit the geometry without touching the phone.

.DESCRIPTION
    Requested by route-planner to settle two device-only questions:
      * is the polyline WCJ-02/WGS-84 consistent (converted exactly once, at render)?
      * does the rendered line sit on the rendered roads?
    The static analysis is theirs; this script produces the evidence.

    Steps (all through testTags, so a copy change cannot break it):
      1. put the engine on 王府井 (39.908692,116.397532)
      2. open the planner (peek → 路线)
      3. origin / waypoint (故宫 39.916345,116.39715) / destination (北海公园 39.9254,116.3831)
         each: tap field → type coordinates → ENTER → tap e2e.route.search.place.0
      4. walk profile, wait for e2e.route.planning to disappear and e2e.route.start to be enabled
      5. Artifact A: screenshot with the polyline drawn
      6. Artifact B: the persisted settings XML (route_path_json / route_profile are the plan the map
         and the engine both use)
      7. tap start, stream VERIFY location lines for ~15 s, Artifact C: those log lines + a screenshot
         taken during playback

    Artifacts land in e2e/out/route-trace/ (plus docs/screenshots/new-route-polyline.png and
    new-route-playing.png so the images are reviewable like the rest of the set).

.PARAMETER Seconds
    Playback observation window for artifact C.

.EXAMPLE
    .\e2e\route_trace.ps1
#>
[CmdletBinding()]
param(
    [int]$Seconds = 16,
    [string]$Origin = '39.908692,116.397532',      # 王府井
    [string]$Waypoint = '39.916345,116.39715',      # 故宫
    [string]$Destination = '39.9254,116.3831',      # 北海公园
    [double]$OriginLat = 39.908692,
    [double]$OriginLon = 116.397532
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')
. (Join-Path $PSScriptRoot 'lib\bridge.ps1')
. (Join-Path $PSScriptRoot 'lib\verifier.ps1')
. (Join-Path $PSScriptRoot 'ui_gesture.ps1')
. (Join-Path $PSScriptRoot 'lib\checks_ui.ps1')

$traceDir = Join-Path $OutDir 'route-trace'
New-Item -ItemType Directory -Force -Path $traceDir | Out-Null
$pkg = Get-ModulePackageName
$artifacts = [ordered]@{}

function Write-Trace([string]$Message) { Write-Host "[trace] $Message" -ForegroundColor Cyan }

# Picks the destination field, types a coordinate query, presses IME-Search and selects the single
# deterministic result row.  Returns $true when a place row was tapped.
function Set-RouteStop {
    param([Parameter(Mandatory)][string]$FieldName, [Parameter(Mandatory)][string]$Coords)
    # Do NOT relaunch when the app is already in front: `am start` would resume MainActivity and can
    # reset the sheet/planner state between stops.
    $appPkg = Get-ModulePackageName
    if (-not (Test-PackageForeground -Package $appPkg)) {
        if (-not (Start-UiApp)) { throw 'app not in the foreground' }
    }
    $dump = Open-RoutePlanner
    if (-not $dump) { throw 'route planner not reachable' }
    $field = Get-UiElement -Name $FieldName -Dump $dump
    if (-not $field) { throw "field $FieldName not found" }
    Invoke-UiTapPoint -X $field.Cx -Y $field.Cy
    Start-Sleep -Seconds 2
    $searchDump = Get-UiXml
    $hits = @(Get-UiBounds -Id 'e2e.route.search' -Dump $searchDump)
    $node = if ($hits.Count -gt 0) { $hits[0] } else { @(Get-UiTextFields -Dump $searchDump) | Select-Object -First 1 }
    if (-not $node) { throw 'no search text field after tapping the stop' }
    Set-UiText -Value $Coords -Field $node -Enter | Out-Null
    $place = Wait-UiBounds -Id 'e2e.route.search.place.0' -TimeoutSec 12
    if (-not $place) { throw "no result row for $Coords" }
    Invoke-UiTapPoint -X $place.Cx -Y $place.Cy
    Start-Sleep -Seconds 2
    Write-Trace "stop $FieldName = $Coords"
    return $true
}

Write-Trace '1) engine on 王府井'
Force-Stop $pkg
Set-EngineConfig @{ mode = 'STATIC'; updateIntervalMs = 1000 } | Out-Null
Send-Start -Channel 'auto' | Out-Null
Send-SetPosition -Lat $OriginLat -Lon $OriginLon | Out-Null
Start-Sleep -Seconds 3

Write-Trace '2-4) walk profile FIRST, then origin / waypoint / destination'
# Profile before destination, per route-planner: setProfile() re-plans when a result already exists, so
# destination-then-profile fires a second plan and makes the "planning gone + start enabled" gate racy.
Start-UiApp | Out-Null
$dump = Open-RoutePlanner
if (-not $dump) { throw 'route planner not reachable' }
$walk = Get-UiElement -Name 'routeModeWalk' -Dump $dump
if ($walk) { Invoke-UiTapPoint -X $walk.Cx -Y $walk.Cy; Start-Sleep -Seconds 1 }
else { Write-Warn2 'walk profile chip not found; keeping the default profile' }
Set-RouteStop -FieldName 'routeOrigin' -Coords $Origin | Out-Null
Set-RouteStop -FieldName 'routeAddWaypoint' -Coords $Waypoint | Out-Null
$waypointSeen = [bool](Wait-UiBounds -Id 'e2e.route.waypoint.0' -TimeoutSec 8)
Set-RouteStop -FieldName 'routeDest' -Coords $Destination | Out-Null

# Race-free ready gate: planning absent AND start enabled in two consecutive dumps >=1 s apart.
$planningGone = Wait-UiGone -Id 'e2e.route.planning' -TimeoutSec 40
$stable = $false
for ($i = 0; $i -lt 6; $i++) {
    $d1 = Get-UiXml
    $planningHere = @(Get-UiBounds -Id 'e2e.route.planning' -Dump $d1).Count -gt 0
    $startNode = Get-UiElement -Name 'routeStart' -Dump $d1 -Optional
    $startEnabledNow = ($startNode -and $startNode.Enabled)
    Start-Sleep -Milliseconds 1200
    $d2 = Get-UiXml
    $planningStill = @(Get-UiBounds -Id 'e2e.route.planning' -Dump $d2).Count -gt 0
    $startNode2 = Get-UiElement -Name 'routeStart' -Dump $d2 -Optional
    $startEnabledAgain = ($startNode2 -and $startNode2.Enabled)
    if ((-not $planningHere) -and (-not $planningStill) -and $startEnabledNow -and $startEnabledAgain) {
        $stable = $true
        break
    }
}
$startReady = if ($stable) { Get-UiElement -Name 'routeStart' -Fresh } else { $null }
$dump = Get-UiXml
$resultCard = Get-UiElement -Name 'routeResult' -Dump $dump -Optional
$notice = Get-UiElement -Name 'routeNotice' -Dump $dump -Optional
$planButton = Get-UiElement -Name 'routePlan' -Dump $dump -Optional
$sourceText = (@(Get-UiNodes -Dump $dump | Where-Object { $_.Text -and $_.Text -match '数据来源|道路路径|直线连接|OSRM|高德路径' }) |
        ForEach-Object { $_.Text.Trim() }) -join ' / '
# The result card carries 距离 / 预计时长 / 途经点; capture its text verbatim so the ETA profile fix
# (walk must read ~35 min for this 2.9 km route, not the server's car-based ~5 min) is checkable.
$resultCardText = ''
if ($resultCard) {
    $resultCardText = (@(Get-UiNodes -Dump $dump | Where-Object {
                $_.Text -and $_.Top -ge $resultCard.Top -and $_.Bottom -le $resultCard.Bottom -and
                $_.Left -ge $resultCard.Left -and $_.Right -le $resultCard.Right
            }) | ForEach-Object { $_.Text.Trim() }) -join ' | '
}
Write-Trace "planning ready: planningGone=$planningGone startEnabled=$([bool]$startReady) result=$([bool]$resultCard) planButton=$([bool]$planButton) source='$sourceText' straightLine=$([bool]$notice) waypointNode=$waypointSeen"
Write-Trace "result card: $resultCardText"

Write-Trace '5) Artifact A: screenshot with the polyline'
$artifactA = & (Join-Path $PSScriptRoot 'screenshot.ps1') -Name 'new-route-polyline' -Quiet
$artifacts['artifactA_mapWithPolyline'] = $artifactA

Write-Trace '6) Artifact B: persisted settings XML (route_path_json / route_profile)'
$prefsPath = Join-Path $traceDir 'immoral_settings.xml'
$remotePrefs = '/data/local/tmp/immoral_settings.xml'
$gotPrefs = $false
foreach ($attempt in @(
        "run-as $pkg cat /data/data/$pkg/shared_prefs/immoral_settings.xml",
        "su -c `"cat /data/data/$pkg/shared_prefs/immoral_settings.xml`""
    )) {
    $r = Invoke-AdbShell $attempt -AllowFailure
    $text = ($r.Output -join "`n")
    if ($text -match '<map' -or $text -match '<string') {
        [System.IO.File]::WriteAllText($prefsPath, $text, (New-Object System.Text.UTF8Encoding($false)))
        $gotPrefs = $true
        break
    }
}
if (-not $gotPrefs) { Write-Warn2 "could not read $pkg prefs (run-as and su both failed)" }
$artifacts['artifactB_prefs'] = if ($gotPrefs) { $prefsPath } else { 'unavailable' }
if ($gotPrefs) {
    $routePathLen = 0
    if ((Get-Content $prefsPath -Raw) -match 'name="route_path_json"') { $routePathLen = 1 }
    Write-Trace "prefs saved ($([Math]::Round((Get-Item $prefsPath).Length/1KB)) KB, contains route_path_json=$([bool]$routePathLen))"
}

Write-Trace "7) Artifact C: start playback, stream VERIFY for ${Seconds}s"
if (-not $startReady) { $startReady = Wait-UiEnabled -Id 'e2e.route.start' -TimeoutSec 10 }
$mark = Get-VerifyMark
$samples = @()
if ($startReady) {
    Invoke-UiTapPoint -X $startReady.Cx -Y $startReady.Cy
    # ~12 s in: the marker is a few hundred metres along the line by then, which is visually
    # distinguishable from "still at the start" (route-planner's request)
    Start-Sleep -Seconds 12
    $artifactCShot = & (Join-Path $PSScriptRoot 'screenshot.ps1') -Name 'new-route-playing' -Quiet
    $artifacts['artifactC_playingScreenshot'] = $artifactCShot
    $deadline = (Get-Date).AddSeconds([Math]::Max(4, $Seconds - 12))
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $samples = @(Get-MoveSamples -AfterTs $mark)
    }
}
else { Write-Warn2 'start button never became enabled' }

$verifyFile = Join-Path $traceDir 'playback-verify.log'
$samples | ForEach-Object { ($_ | ConvertTo-Json -Compress -Depth 6) } |
    Set-Content -Path $verifyFile -Encoding utf8
$artifacts['artifactC_verifySamples'] = $verifyFile
$track = Measure-Track -Samples $samples
$first = if ($samples.Count -gt 0) { $samples[0] } else { $null }
$last = if ($samples.Count -gt 0) { $samples[-1] } else { $null }

$summary = [ordered]@{
    capturedAt       = (Get-Date).ToString('o')
    # bind the trace to the exact artifact it describes (the analyzer compares against OSRM on the host)
    apkSha256        = $(if (Test-Path (Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk')) { (Get-FileHash (Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk') -Algorithm SHA256).Hash } else { '' })
    gitHead          = ((& git rev-parse --short HEAD 2>$null) -join '').Trim()
    stops            = [ordered]@{ origin = $Origin; waypoint = $Waypoint; destination = $Destination }
    waypointNodeSeen = $waypointSeen
    planningGone     = $planningGone
    startEnabled     = [bool]$startReady
    resultCard       = [bool]$resultCard
    straightLine     = [bool]$notice
    sourceText       = $sourceText
    # Do NOT force OSRM: an AMap-sourced plan is the strong evidence for the GCJ-02 ⇄ WGS-84 question,
    # because it is the only path that converts coordinates. Just record which backend answered.
    amapAnswered     = ($sourceText -like '*高德*')
    playback         = [ordered]@{
        samples    = $samples.Count
        meters     = $track.meters
        seconds    = $track.seconds
        firstLat   = if ($first) { $first.lat } else { $null }
        firstLon   = if ($first) { $first.lon } else { $null }
        lastLat    = if ($last) { $last.lat } else { $null }
        lastLon    = if ($last) { $last.lon } else { $null }
    }
    artifacts        = $artifacts
}
$summaryPath = Join-Path $traceDir 'route-trace.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding utf8

Write-Host ''
Write-Ok "route trace complete -> $summaryPath"
Write-Host 'artifacts:' -ForegroundColor Magenta
foreach ($k in $artifacts.Keys) { Write-Host ("  {0,-32} {1}" -f $k, $artifacts[$k]) }
Write-Host ("  playback samples: {0} over {1}s ({2} m)" -f $samples.Count, $track.seconds, $track.meters)
if ($samples.Count -lt 5) { Write-Warn2 'fewer than 5 VERIFY samples: check the verifier stream before trusting artifact C' }
exit 0
