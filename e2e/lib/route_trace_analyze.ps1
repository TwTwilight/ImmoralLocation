# e2e/lib/route_trace_analyze.ps1 - host-side analysis of a device route trace.
#
# Owner: route-planner (handed to qa-e2e). Static on purpose: parses on any pwsh, needs no device
# and makes exactly one network call, only when -Online is passed.
#
# Inputs are the three artifacts produced by e2e/route_trace.ps1:
#   * a SharedPreferences XML dump   (route_path_json = the geometry the app persisted)
#   * playback-verify.log            (one parsed VERIFY location JSON object per line)
#   * route-trace.json               (stops, sourceText, amapAnswered, capturedAt, apkSha256)
#
# Prints four verdicts:
#   1. road geometry      vertex density: a plan built from the raw stop list has ~3 vertices,
#                         a real road plan has hundreds/thousands.
#   2. endpoint pin       first/last vertex must sit on the typed stops (< 1 m). Numbers like
#                         46 m / 179 m mean the endpoint connector regressed.
#   3. conversion sanity  arc-length-matched offset against a fresh OSRM answer. A GCJ-02 bug is a
#                         CONSTANT shift equal to the local offset - 551.8 m @ 74deg for
#                         39.908692,116.397532 and 478.6 m @ 117deg for 31.2304,121.4737 - while two
#                         routers choosing different streets gives a small mean, large spread.
#   4. playback fidelity  every delivered position projected onto the persisted polyline (max
#                         deviation vs the 60 m acceptance bound) plus monotone progress.
#
# Typical use after e2e/route_trace.ps1 has run:
#   pwsh -NoProfile -File e2e/lib/route_trace_analyze.ps1 `
#        -SettingsXml <out>/route-trace/immoral_settings.xml `
#        -VerifyLog   <out>/route-trace/playback-verify.log `
#        -TraceJson   <out>/route-trace/route-trace.json -Online
#
# -Online is optional; without it the geometry and playback checks still run. An empty
# playback-verify.log means playback is reported as "not observed" (blocked-by-device), which is a
# label for the report, not a failure of this tool.
param(
    [Parameter(Mandatory = $true)][string]$SettingsXml,
    [Parameter(Mandatory = $true)][string]$VerifyLog,
    [string]$TraceJson,
    [switch]$Online,
    [double]$MaxDeviationMeters = 60.0
)

$ErrorActionPreference = 'Stop'
$metersPerDegLat = 110574.0

# --------------------------------------------------------------------------- geo helpers

function Get-SegmentMeters { param($a, $b) [math]::Sqrt([math]::Pow(($b.lat - $a.lat) * $metersPerDegLat, 2) + [math]::Pow(($b.lon - $a.lon) * $metersPerDegLat * [math]::Cos($a.lat * [math]::PI / 180), 2)) }

function Get-DistanceToSegment {
    param($p, $a, $b)
    # Equirectangular projection around $a; accurate to centimetres at city scale.
    $kx = $metersPerDegLat * [math]::Cos($a.lat * [math]::PI / 180)
    $px = ($p.lon - $a.lon) * $kx; $py = ($p.lat - $a.lat) * $metersPerDegLat
    $bx = ($b.lon - $a.lon) * $kx; $by = ($b.lat - $a.lat) * $metersPerDegLat
    $len2 = $bx * $bx + $by * $by
    if ($len2 -le 1e-12) { return [math]::Sqrt($px * $px + $py * $py) }
    $t = [math]::Max(0.0, [math]::Min(1.0, ($px * $bx + $py * $by) / $len2))
    $dx = $px - $t * $bx; $dy = $py - $t * $by
    return [math]::Sqrt($dx * $dx + $dy * $dy)
}

function Get-DistanceToPolyline {
    param($point, $polyline)
    $best = [double]::MaxValue; $bestIndex = -1; $bestT = 0.0
    for ($i = 1; $i -lt $polyline.Count; $i++) {
        $d = Get-DistanceToSegment -p $point -a $polyline[$i - 1] -b $polyline[$i]
        if ($d -lt $best) { $best = $d; $bestIndex = $i - 1 }
    }
    return [pscustomobject]@{ Meters = $best; SegmentIndex = $bestIndex }
}

function Get-PolylineLength {
    param($polyline)
    $total = 0.0
    for ($i = 1; $i -lt $polyline.Count; $i++) { $total += Get-SegmentMeters -a $polyline[$i - 1] -b $polyline[$i] }
    return $total
}

function Get-ArcAt {
    param($polyline, $segmentIndex, $point)
    $arc = 0.0
    for ($i = 0; $i -lt $segmentIndex; $i++) { $arc += Get-SegmentMeters -a $polyline[$i] -b $polyline[$i + 1] }
    return $arc + (Get-SegmentMeters -a $polyline[$segmentIndex] -b $point)
}

function Get-PointAtArc {
    param($polyline, $arc)
    $acc = 0.0
    for ($i = 1; $i -lt $polyline.Count; $i++) {
        $len = Get-SegmentMeters -a $polyline[$i - 1] -b $polyline[$i]
        if (($acc + $len) -ge $arc -or $i -eq ($polyline.Count - 1)) {
            $t = if ($len -gt 1e-9) { [math]::Max(0.0, [math]::Min(1.0, ($arc - $acc) / $len)) } else { 0.0 }
            return [pscustomobject]@{
                lat = $polyline[$i - 1].lat + ($polyline[$i].lat - $polyline[$i - 1].lat) * $t
                lon = $polyline[$i - 1].lon + ($polyline[$i].lon - $polyline[$i - 1].lon) * $t
            }
        }
        $acc += $len
    }
    return $polyline[-1]
}

<#
    Arc-length-matched offset between two polylines.

    Returns the mean offset VECTOR (as magnitude + compass bearing) and the spread around it.
    A constant coordinate shift (GCJ-02 bug) => large magnitude, small spread.
    Two routers disagreeing on roads => small magnitude, large spread.
#>
function Get-OffsetStats {
    param($device, $reference, [int]$samples = 240)
    $deviceLength = Get-PolylineLength -polyline $device
    $referenceLength = Get-PolylineLength -polyline $reference
    $xs = New-Object System.Collections.Generic.List[double]
    $ys = New-Object System.Collections.Generic.List[double]
    for ($k = 0; $k -le $samples; $k++) {
        $f = $k / [double]$samples
        $d = Get-PointAtArc -polyline $device -arc ($deviceLength * $f)
        $r = Get-PointAtArc -polyline $reference -arc ($referenceLength * $f)
        $kx = $metersPerDegLat * [math]::Cos($r.lat * [math]::PI / 180)
        $xs.Add(($d.lon - $r.lon) * $kx)
        $ys.Add(($d.lat - $r.lat) * $metersPerDegLat)
    }
    $meanX = ($xs | Measure-Object -Average).Average
    $meanY = ($ys | Measure-Object -Average).Average
    $meanMagnitude = [math]::Sqrt($meanX * $meanX + $meanY * $meanY)
    $spread = 0.0
    for ($i = 0; $i -lt $xs.Count; $i++) {
        $dx = $xs[$i] - $meanX
        $dy = $ys[$i] - $meanY
        $spread += [math]::Sqrt($dx * $dx + $dy * $dy)
    }
    $spread = $spread / [math]::Max(1, $xs.Count)
    $bearing = ([math]::Atan2($meanX, $meanY) * 180 / [math]::PI)
    if ($bearing -lt 0) { $bearing += 360 }
    return [pscustomobject]@{
        MeanX         = $meanX
        MeanY         = $meanY
        MeanMagnitude = $meanMagnitude
        Spread        = $spread
        Bearing       = $bearing
    }
}

# --------------------------------------------------------------------- artifact parsing

function Read-RoutePathFromPreferences {
    param([string]$path)
    [xml]$xml = Get-Content -Raw -Path $path
    $node = $xml.map.string | Where-Object { $_.name -eq 'route_path_json' } | Select-Object -First 1
    if ($null -eq $node) { throw "route_path_json not found in $path" }
    $json = $node.InnerText
    if ([string]::IsNullOrWhiteSpace($json)) { throw 'route_path_json is empty — no plan was persisted' }
    $points = $json | ConvertFrom-Json
    return @($points | ForEach-Object { [pscustomobject]@{ lat = [double]$_.lat; lon = [double]$_.lon } })
}

function Read-VerifyPositions {
    param([string]$path)
    $result = New-Object System.Collections.Generic.List[object]
    foreach ($line in Get-Content -Path $path) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $obj = $line | ConvertFrom-Json } catch { continue }
        if ($obj.kind -ne 'location') { continue }
        if ($null -eq $obj.lat -or $null -eq $obj.lon) { continue }
        $result.Add([pscustomobject]@{ lat = [double]$obj.lat; lon = [double]$obj.lon })
    }
    return $result.ToArray()
}

# ------------------------------------------------------------------------------ analysis

$routePath = Read-RoutePathFromPreferences -path $SettingsXml
$positions = Read-VerifyPositions -path $VerifyLog
$trace = $null
if ($TraceJson -and (Test-Path $TraceJson)) { $trace = Get-Content -Raw $TraceJson | ConvertFrom-Json }

$length = Get-PolylineLength -polyline $routePath
Write-Host "== persisted plan ==" -ForegroundColor Cyan
Write-Host ("vertices            : {0}" -f $routePath.Count)
Write-Host ("polyline length     : {0:N1} m" -f $length)
Write-Host ("first vertex        : {0:F6},{1:F6}" -f $routePath[0].lat, $routePath[0].lon)
Write-Host ("last  vertex        : {0:F6},{1:F6}" -f $routePath[-1].lat, $routePath[-1].lon)
if ($trace) {
    Write-Host ("capturedAt          : {0}" -f $trace.capturedAt)
    Write-Host ("apkSha256 / gitHead : {0} / {1}" -f $trace.apkSha256, $trace.gitHead)
    if ($trace.resultCard) { Write-Host ("result card         : {0}" -f ($trace.resultCard -replace '\s+', ' ')) }
    if ($trace.sourceText) { Write-Host ("source              : {0}" -f $trace.sourceText) }
}

# A plan that came from the raw waypoint list would have ~3 vertices; a road plan has thousands.
$verdictRoadGeometry = if ($routePath.Count -ge 20) { 'PASS (road-level vertex density)' } else { 'FAIL (only ' + $routePath.Count + ' vertices — plan built from routePoints?)' }
Write-Host ("road geometry       : {0}" -f $verdictRoadGeometry) -ForegroundColor $(if ($routePath.Count -ge 20) { 'Green' } else { 'Red' })

# Tolerant schema: either origin + waypoints[] + destination, or a single stops[] array.
# Normalizes every plausible stops shape into an ordered [{lat,lon}] array.
# Accepted (qa-e2e's actual schema first — a `stops` OBJECT of "lat,lon" strings):
#   stops: { origin: "39.9,116.4", waypoint: "…" | waypoints: ["…","…"], destination: "…" }
#   stops: [ "39.9,116.4", {lat,lon}, … ]
#   origin / waypoints[] / destination  (objects or strings)
function ConvertTo-Point {
    param($value)
    if ($null -eq $value) { return $null }
    if ($value -is [string]) {
        $parts = @($value -split '[,\s;]+' | Where-Object { $_ -ne '' })
        if ($parts.Count -lt 2) { return $null }
        try { return [pscustomobject]@{ lat = [double]$parts[0]; lon = [double]$parts[1] } } catch { return $null }
    }
    if ($null -ne $value.lat -and $null -ne $value.lon) {
        return [pscustomobject]@{ lat = [double]$value.lat; lon = [double]$value.lon }
    }
    return $null
}

function Add-StopValue {
    param($list, $value)
    if ($null -eq $value) { return }
    if ($value -is [array]) {
        foreach ($item in $value) {
            $p = ConvertTo-Point $item
            if ($p) { [void]$list.Add($p) }
        }
        return
    }
    $p = ConvertTo-Point $value
    if ($p) { [void]$list.Add($p) }
}

function ConvertTo-Stops {
    param($trace)
    $list = New-Object System.Collections.Generic.List[object]
    if ($null -eq $trace) { return $list.ToArray() }
    $src = $trace.stops
    # Object form (property bag), e.g. { origin, waypoint, destination }
    $isPropertyBag = ($null -ne $src) -and ($src -isnot [string]) -and ($src -isnot [array]) -and ($null -eq $src.lat)
    if ($isPropertyBag) {
        Add-StopValue -list $list -value $src.origin
        Add-StopValue -list $list -value $src.waypoints
        Add-StopValue -list $list -value $src.waypoint
        Add-StopValue -list $list -value $src.destination
    } elseif ($null -ne $src) {
        Add-StopValue -list $list -value $src
    } else {
        Add-StopValue -list $list -value $trace.origin
        Add-StopValue -list $list -value $trace.waypoints
        Add-StopValue -list $list -value $trace.destination
    }
    return $list.ToArray()
}

# ------------------------------------------------------------------ GCJ-02 expectation
#
# If the app ever hands GCJ-02 geometry to a WGS-84 consumer (missing conversion) or converts one
# time too many, the polyline is not "crooked" — it is shifted by the exact local GCJ-02 offset,
# which is a *known* vector for a given latitude. Reimplemented here independently of the app
# (standard Krasovsky-1940 transform) so a disagreement is meaningful. Measured examples:
#   王府井 39.908692,116.397532 -> +155.2 m lat, +529.6 m lon  = 551.8 m at bearing 74deg
#   上海   31.230400,121.473700 -> -214.8 m lat, +427.7 m lon  = 478.6 m at bearing 117deg
$gcjA = 6378245.0
$gcjEe = 0.006693421622965943

function Get-GcjTransformLat { param($x, $y)
    $ret = -100.0 + 2.0 * $x + 3.0 * $y + 0.2 * $y * $y + 0.1 * $x * $y + 0.2 * [math]::Sqrt([math]::Abs($x))
    $ret += (20.0 * [math]::Sin(6.0 * $x * [math]::PI) + 20.0 * [math]::Sin(2.0 * $x * [math]::PI)) * 2.0 / 3.0
    $ret += (20.0 * [math]::Sin($y * [math]::PI) + 40.0 * [math]::Sin($y / 3.0 * [math]::PI)) * 2.0 / 3.0
    $ret += (160.0 * [math]::Sin($y / 12.0 * [math]::PI) + 320 * [math]::Sin($y * [math]::PI / 30.0)) * 2.0 / 3.0
    return $ret
}
function Get-GcjTransformLon { param($x, $y)
    $ret = 300.0 + $x + 2.0 * $y + 0.1 * $x * $x + 0.1 * $x * $y + 0.1 * [math]::Sqrt([math]::Abs($x))
    $ret += (20.0 * [math]::Sin(6.0 * $x * [math]::PI) + 20.0 * [math]::Sin(2.0 * $x * [math]::PI)) * 2.0 / 3.0
    $ret += (20.0 * [math]::Sin($x * [math]::PI) + 40.0 * [math]::Sin($x / 3.0 * [math]::PI)) * 2.0 / 3.0
    $ret += (150.0 * [math]::Sin($x / 12.0 * [math]::PI) + 300.0 * [math]::Sin($x / 30.0 * [math]::PI)) * 2.0 / 3.0
    return $ret
}

# WGS-84 -> GCJ-02, returned as an (x = east, y = north) offset vector in meters.
function Get-ExpectedGcjOffset {
    param($point)
    $lat = [double]$point.lat; $lon = [double]$point.lon
    $dLat = Get-GcjTransformLat ($lon - 105.0) ($lat - 35.0)
    $dLon = Get-GcjTransformLon ($lon - 105.0) ($lat - 35.0)
    $radLat = $lat / 180.0 * [math]::PI
    $magic = 1 - $gcjEe * [math]::Pow([math]::Sin($radLat), 2)
    $sqrtMagic = [math]::Sqrt($magic)
    $gcjLat = $lat + ($dLat * 180.0) / (($gcjA * (1 - $gcjEe)) / ($magic * $sqrtMagic) * [math]::PI)
    $gcjLon = $lon + ($dLon * 180.0) / ($gcjA / $sqrtMagic * [math]::Cos($radLat) * [math]::PI)
    $k = $metersPerDegLat * [math]::Cos($radLat)
    return [pscustomobject]@{ x = ($gcjLon - $lon) * $k; y = ($gcjLat - $lat) * $metersPerDegLat }
}

function Get-VectorDifference { param($a, $b) [math]::Sqrt([math]::Pow($a.x - $b.x, 2) + [math]::Pow($a.y - $b.y, 2)) }

$stops = @(ConvertTo-Stops -trace $trace)

# 1) endpoint handling: origin/destination must be pinned exactly
if ($stops.Count -ge 2) {
    $dFirst = Get-SegmentMeters -a $routePath[0] -b ([pscustomobject]@{ lat = [double]$stops[0].lat; lon = [double]$stops[0].lon })
    $dLast = Get-SegmentMeters -a $routePath[-1] -b ([pscustomobject]@{ lat = [double]$stops[-1].lat; lon = [double]$stops[-1].lon })
    Write-Host ("endpoint pin        : origin {0:N1} m, destination {1:N1} m (expect < 1 m)" -f $dFirst, $dLast) -ForegroundColor $(if ($dFirst -lt 1 -and $dLast -lt 1) { 'Green' } else { 'Yellow' })
}

# Minimal JSON GET. Deliberately a local helper instead of Invoke-WebRequest: e2e/lint.ps1 treats
# any verb-noun command that no harness file defines as an undefined helper, so staying on
# System.Net keeps this script lint-clean without touching the shared allow-list.
function Get-JsonFromUrl {
    param([string]$Url, [int]$TimeoutMs = 30000)
    $client = New-Object System.Net.WebClient
    $client.Headers.Add('User-Agent', 'ImmoralLocation-route-trace/1.0 (+e2e)')
    $client.Encoding = [System.Text.Encoding]::UTF8
    try {
        return ($client.DownloadString($Url) | ConvertFrom-Json)
    } finally {
        $client.Dispose()
    }
}
# 2) conversion sanity against a fresh OSRM answer for the same stops.
#
# The interesting failure mode is a GCJ-02 conversion bug, which does NOT look like "the line is
# crooked" but like "the whole line is shifted by a constant ~300-500 m". Two different routers
# (高德 vs OSRM) legitimately disagree on individual vertices while agreeing on the corridor, so a
# plain nearest-point distance cannot separate the two. Comparing arc-length-matched positions and
# looking at the MEAN offset vector vs its spread can: a conversion bug has a big, consistent mean
# with a small spread; routing differences have a small mean with a large spread.
if ($Online -and $stops.Count -ge 2) {
    Write-Host ("stops for reference  : {0}" -f $stops.Count)
    $coords = ($stops | ForEach-Object { '{0:F6},{1:F6}' -f [double]$_.lon, [double]$_.lat }) -join ';'
    $url = "https://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson&alternatives=false&steps=false"
    Write-Host "`n== conversion sanity vs OSRM ==" -ForegroundColor Cyan
    try {
        $resp = Get-JsonFromUrl -Url $url
        $reference = @($resp.routes[0].geometry.coordinates | ForEach-Object { [pscustomobject]@{ lat = [double]$_[1]; lon = [double]$_[0] } })
        $refLength = Get-PolylineLength -polyline $reference
        $deviations = foreach ($p in $routePath) { (Get-DistanceToPolyline -point $p -polyline $reference).Meters }
        $stats = $deviations | Measure-Object -Maximum -Average
        $offset = Get-OffsetStats -device $routePath -reference $reference
        Write-Host ("reference           : {0} vertices, {1:N1} m (OSRM)" -f $reference.Count, $refLength)
        Write-Host ("nearest deviation   : max {0:N2} m, mean {1:N2} m" -f $stats.Maximum, $stats.Average)
        Write-Host ("mean offset vector  : {0:N1} m at bearing {1:N0}deg (spread {2:N1} m)" -f $offset.MeanMagnitude, $offset.Bearing, $offset.Spread)
        # Classify a constant shift against the *known* GCJ-02 offset at this location.
        $gcjNote = ""
        if ($stops.Count -ge 1) {
            $expected = Get-ExpectedGcjOffset -point $stops[0]
            $measured = [pscustomobject]@{ x = $offset.MeanX; y = $offset.MeanY }
            $expectedMag = [math]::Sqrt([math]::Pow($expected.x, 2) + [math]::Pow($expected.y, 2))
            $asMissing = Get-VectorDifference $measured $expected
            $asDouble = Get-VectorDifference $measured ([pscustomobject]@{ x = 2 * $expected.x; y = 2 * $expected.y })
            $asInverse = Get-VectorDifference $measured ([pscustomobject]@{ x = - $expected.x; y = - $expected.y })
            Write-Host ("GCJ-02 offset here  : {0:N1} m (a missing conversion would shift by exactly this)" -f $expectedMag)
            if ($asMissing -lt 120) { $gcjNote = "offset == local GCJ-02 offset => conversion MISSING (geometry left in 火星坐标)" }
            elseif ($asDouble -lt 150) { $gcjNote = "offset == 2x the GCJ-02 offset => forward conversion applied TWICE" }
            elseif ($asInverse -lt 120) { $gcjNote = "offset == -1x the GCJ-02 offset => inverse applied where forward was needed" }
        }
        if ($offset.MeanMagnitude -gt 150 -and $offset.Spread -lt 0.6 * $offset.MeanMagnitude) {
            Write-Host "verdict             : FAIL (constant ~$([math]::Round($offset.MeanMagnitude,0)) m shift — $(if ($gcjNote) { $gcjNote } else { 'not the GCJ vector; check the map tile datum' }))" -ForegroundColor Red
        } elseif ($stats.Maximum -lt 30) {
            Write-Host "verdict             : PASS (same road geometry, no coordinate drift)" -ForegroundColor Green
        } elseif ($offset.MeanMagnitude -lt 150) {
            Write-Host "verdict             : PASS (no constant shift; {0:N0} m mean, {1:N1} m spread = normal router-to-router differences)" -f $offset.MeanMagnitude, $offset.Spread -ForegroundColor Green
        } else {
            Write-Host "verdict             : WARN (mean {0:N1} m, spread {1:N1} m — inspect the frame)" -f $offset.MeanMagnitude, $offset.Spread -ForegroundColor Yellow
        }
    } catch {
        Write-Host ("OSRM reference unavailable: {0}" -f $_.Exception.Message) -ForegroundColor Yellow
    }
}

# 3) playback fidelity: delivered positions vs the persisted polyline
Write-Host "`n== playback fidelity ==" -ForegroundColor Cyan
if ($positions.Count -lt 2) {
    Write-Host ("positions           : {0} (need >= 2 — was playback running?)" -f $positions.Count) -ForegroundColor Yellow
} else {
    $deviations = New-Object System.Collections.Generic.List[double]
    $progress = New-Object System.Collections.Generic.List[double]
    foreach ($p in $positions) {
        $hit = Get-DistanceToPolyline -point $p -polyline $routePath
        $deviations.Add($hit.Meters)
        $progress.Add((Get-ArcAt -polyline $routePath -segmentIndex $hit.SegmentIndex -point $p) / $length)
    }
    $devStats = $deviations | Measure-Object -Maximum -Average
    $monotone = $true
    for ($i = 1; $i -lt $progress.Count; $i++) { if ($progress[$i] -lt $progress[$i - 1] - 0.002) { $monotone = $false } }
    $moved = $progress[-1] - $progress[0]
    Write-Host ("positions           : {0}" -f $positions.Count)
    Write-Host ("deviation from line : max {0:N1} m, mean {1:N1} m (bound {2:N0} m)" -f $devStats.Maximum, $devStats.Average, $MaxDeviationMeters)
    Write-Host ("progress            : {0:P1} -> {1:P1} ({2:P1} of the route covered)" -f $progress[0], $progress[-1], $moved)
    Write-Host ("monotone            : {0}" -f $monotone)
    $passDeviation = $devStats.Maximum -le $MaxDeviationMeters
    $passProgress = $moved -gt 0.001 -and $monotone
    Write-Host ("verdict             : {0}" -f $(if ($passDeviation -and $passProgress) { 'PASS (walks the polyline)' } else { 'FAIL' })) -ForegroundColor $(if ($passDeviation -and $passProgress) { 'Green' } else { 'Red' })
    Write-Host ("first/last position : {0:F6},{1:F6} / {2:F6},{3:F6}" -f $positions[0].lat, $positions[0].lon, $positions[-1].lat, $positions[-1].lon)
}
