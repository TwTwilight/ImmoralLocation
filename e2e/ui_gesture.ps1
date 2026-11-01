# e2e/ui_gesture.ps1 — screen-level gestures and geometry for the acceptance matrix.
#
# Everything here is built on the two primitives that always work on a rooted-but-headless test
# device, with no instrumentation and no test APK:
#
#   * `uiautomator dump`            -> the accessibility tree, i.e. text / resource-id
#                                      (Compose testTags, when `testTagsAsResourceId = true`) /
#                                      content-desc plus exact pixel bounds per node;
#   * `adb shell input tap|swipe`   -> touch injection;
#   * `adb shell input motionevent` -> explicit DOWN / MOVE / UP, which is what a joystick needs:
#                                      a single continuous *press* whose vector is held while the
#                                      caller measures the engine's movement.
#
# Dot-source it after lib/common.ps1 (it does that itself) and, when available, after lib/bridge.ps1:
#
#   . "$PSScriptRoot\lib\common.ps1"
#   . "$PSScriptRoot\ui_gesture.ps1"
#
# Naming: bridge.ps1 already exports Get-UiDump / Get-UiNodeBounds / Invoke-UiTap (single-result,
# substring-first). This file is the multi-result, bounds-first layer used by the matrix checks:
# Get-UiBounds returns an ARRAY (so overlap assertions are possible) and matches on
# text / resource-id / content-desc with explicit -Exact control.

. (Join-Path $PSScriptRoot 'lib\common.ps1')

$script:UiRemoteDump = '/data/local/tmp/e2e_ui.xml'
$script:UiAppStrings = $null
$script:UiScreenSize = $null
$script:JoystickSwipeProc = $null
# Compose pads render as one node per pad; these are the ids/descriptions we recognise.
$script:JoystickIds = @('e2e.joystick', 'ui:joystick', 'joystick')
$script:JoystickDescs = @('摇杆', 'joystick', 'Joystick', '方向摇杆')

# ------------------------------------------------------------------------------------------------
# accessibility tree
# ------------------------------------------------------------------------------------------------

# Dumps the current hierarchy and returns it parsed.  `uiautomator dump` fails with
# "ERROR: could not get idle state" while an animation/composition is in flight, so retry.
function Get-UiXml {
    param([int]$Retries = 3, [int]$RetryDelayMs = 700, [string]$Remote = '')
    if (-not $Remote) { $Remote = $script:UiRemoteDump }
    $local = Join-Path $OutDir 'ui.xml'
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        $r = Invoke-AdbShell "uiautomator dump $Remote" -AllowFailure
        $text = ($r.Output -join "`n")
        if ($text -match 'ERROR|Exception|Killed') {
            Start-Sleep -Milliseconds $RetryDelayMs
            continue
        }
        $pull = Invoke-Adb pull $Remote $local
        if ($pull.Code -ne 0 -or -not (Test-Path $local)) {
            Start-Sleep -Milliseconds $RetryDelayMs
            continue
        }
        try {
            $xml = [xml](Get-Content $local -Raw)
        }
        catch {
            Start-Sleep -Milliseconds $RetryDelayMs
            continue
        }
        if ($xml -and $xml.SelectNodes('//node').Count -gt 0) { return $xml }
        Start-Sleep -Milliseconds $RetryDelayMs
    }
    return $null
}

function ConvertFrom-UiBoundsString {
    param([string]$Bounds)
    if ($Bounds -match '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') {
        $left = [int]$matches[1]; $top = [int]$matches[2]
        $right = [int]$matches[3]; $bottom = [int]$matches[4]
        if ($right -le $left -or $bottom -le $top) { return $null }
        return [pscustomobject]@{
            Left   = $left
            Top    = $top
            Right  = $right
            Bottom = $bottom
            Width  = $right - $left
            Height = $bottom - $top
            Cx     = [int](($left + $right) / 2)
            Cy     = [int](($top + $bottom) / 2)
        }
    }
    return $null
}

# Flattens the tree into plain objects (StrictMode-safe: every property always exists).
function Get-UiNodes {
    param([System.Xml.XmlDocument]$Dump)
    if (-not $Dump) { return @() }
    $out = [System.Collections.Generic.List[object]]::new()
    foreach ($node in $Dump.SelectNodes('//node')) {
        $rect = ConvertFrom-UiBoundsString -Bounds $node.GetAttribute('bounds')
        if (-not $rect) { continue }
        $out.Add([pscustomobject]@{
                Text      = $node.GetAttribute('text')
                Id        = $node.GetAttribute('resource-id')
                Desc      = $node.GetAttribute('content-desc')
                Class     = $node.GetAttribute('class')
                Package   = $node.GetAttribute('package')
                Clickable = ($node.GetAttribute('clickable') -eq 'true')
                Enabled   = ($node.GetAttribute('enabled') -eq 'true')
                Checked   = ($node.GetAttribute('checked') -eq 'true')
                Selected  = ($node.GetAttribute('selected') -eq 'true')
                Focusable = ($node.GetAttribute('focusable') -eq 'true')
                Scrollable = ($node.GetAttribute('scrollable') -eq 'true')
                Bounds    = $node.GetAttribute('bounds')
                Left      = $rect.Left
                Top       = $rect.Top
                Right     = $rect.Right
                Bottom    = $rect.Bottom
                Width     = $rect.Width
                Height    = $rect.Height
                Cx        = $rect.Cx
                Cy        = $rect.Cy
            })
    }
    return @($out)
}

# All nodes matching a selector.  Matching order: resource-id (exact), then text (exact, then
# substring unless -Exact), then content-desc.  Returns an array; empty when nothing matched.
function Get-UiBounds {
    param(
        [string]$Text = '',
        [string]$Id = '',
        [string]$Desc = '',
        [switch]$Exact,
        [switch]$AnyText,
        [System.Xml.XmlDocument]$Dump,
        [switch]$Fresh
    )
    if (-not $Dump -or $Fresh) { $Dump = Get-UiXml }
    $nodes = @(Get-UiNodes -Dump $Dump)
    if ($nodes.Count -eq 0) { return @() }

    $result = [System.Collections.Generic.List[object]]::new()
    if ($Id) {
        foreach ($n in $nodes) {
            if ($n.Id -eq $Id) { $result.Add($n) }
        }
        # resource-ids are namespaced by the app package; accept a suffix match as a fallback so a
        # tag works whether or not the app prefixes it.
        if ($result.Count -eq 0) {
            foreach ($n in $nodes) {
                if ($n.Id -and $n.Id.EndsWith(":$Id")) { $result.Add($n) }
            }
        }
    }
    if ($Text) {
        foreach ($n in $nodes) {
            if ($result.Count -gt 0 -and -not $AnyText) { break }
            $t = $n.Text
            if (-not $t) { continue }
            $hit = if ($Exact) { $t.Trim() -eq $Text.Trim() } else { $t -like "*$Text*" }
            if ($hit) { $result.Add($n) }
        }
    }
    if ($Desc) {
        foreach ($n in $nodes) {
            if (-not $n.Desc) { continue }
            $hit = if ($Exact) { $n.Desc.Trim() -eq $Desc.Trim() } else { $n.Desc -like "*$Desc*" }
            if ($hit) { $result.Add($n) }
        }
    }
    if (-not $Id -and -not $Text -and -not $Desc) {
        # no selector: return the tree (useful for "what is on screen" diagnostics)
        return $nodes
    }
    # de-duplicate (the same node can match two selector kinds)
    $seen = @{}
    $uniq = [System.Collections.Generic.List[object]]::new()
    foreach ($n in $result) {
        $key = "$($n.Bounds)|$($n.Id)|$($n.Text)|$($n.Desc)"
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        $uniq.Add($n)
    }
    return @($uniq)
}

function Test-UiPresent {
    param([string]$Text = '', [string]$Id = '', [string]$Desc = '', [switch]$Exact,
        [System.Xml.XmlDocument]$Dump)
    $b = @(Get-UiBounds -Text $Text -Id $Id -Desc $Desc -Exact:$Exact -Dump $Dump)
    return ($b.Count -gt 0)
}

# Waits for a selector to appear (e.g. after a sheet animation) or to disappear.
function Wait-UiBounds {
    param([string]$Text = '', [string]$Id = '', [string]$Desc = '', [switch]$Exact,
        [int]$TimeoutSec = 8, [int]$IntervalMs = 500, [switch]$Gone)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $b = @(Get-UiBounds -Text $Text -Id $Id -Desc $Desc -Exact:$Exact -Fresh)
        if ($Gone) { if ($b.Count -eq 0) { return $true } }
        elseif ($b.Count -gt 0) { return $b[0] }
        Start-Sleep -Milliseconds $IntervalMs
    } while ((Get-Date) -lt $deadline)
    if ($Gone) { return $false }
    return $null
}

# Waits until a selector is gone (planning spinners, sheets that dismiss themselves).
function Wait-UiGone {
    param([string]$Id = '', [string]$Text = '', [string]$Desc = '', [int]$TimeoutSec = 20, [int]$IntervalMs = 500)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $b = @(Get-UiBounds -Id $Id -Text $Text -Desc $Desc -Fresh)
        if ($b.Count -eq 0) { return $true }
        Start-Sleep -Milliseconds $IntervalMs
    } while ((Get-Date) -lt $deadline)
    return $false
}

# Waits until a node exists AND reports enabled="true" (buttons that stay disabled while planning).
function Wait-UiEnabled {
    param([Parameter(Mandatory)][string]$Id, [int]$TimeoutSec = 20, [int]$IntervalMs = 500)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $b = @(Get-UiBounds -Id $Id -Fresh)
        if ($b.Count -gt 0 -and $b[0].Enabled) { return $b[0] }
        Start-Sleep -Milliseconds $IntervalMs
    } while ((Get-Date) -lt $deadline)
    return $null
}

# A one-line description of what is on screen, for failure details.
function Format-UiSnapshot {
    param([System.Xml.XmlDocument]$Dump, [int]$Max = 12)
    if (-not $Dump) { return '<no dump>' }
    $parts = @()
    foreach ($n in (Get-UiNodes -Dump $Dump)) {
        $label = if ($n.Text) { $n.Text } elseif ($n.Desc) { "desc=$($n.Desc)" } elseif ($n.Id) { "id=$($n.Id)" } else { '' }
        if (-not $label) { continue }
        $parts += "$label$($n.Bounds)"
        if ($parts.Count -ge $Max) { break }
    }
    if ($parts.Count -eq 0) { return '<empty tree>' }
    return ($parts -join ' | ')
}

# ------------------------------------------------------------------------------------------------
# geometry helpers (overlap assertions)
# ------------------------------------------------------------------------------------------------
function Test-RectOverlap {
    param($A, $B, [int]$Margin = 0)
    if (-not $A -or -not $B) { return $false }
    $overlapX = [Math]::Min($A.Right, $B.Right) - [Math]::Max($A.Left, $B.Left)
    $overlapY = [Math]::Min($A.Bottom, $B.Bottom) - [Math]::Max($A.Top, $B.Top)
    return (($overlapX -gt $Margin) -and ($overlapY -gt $Margin))
}

function Get-RectOverlapArea {
    param($A, $B)
    if (-not $A -or -not $B) { return 0 }
    $w = [Math]::Min($A.Right, $B.Right) - [Math]::Max($A.Left, $B.Left)
    $h = [Math]::Min($A.Bottom, $B.Bottom) - [Math]::Max($A.Top, $B.Top)
    if ($w -le 0 -or $h -le 0) { return 0 }
    return $w * $h
}

function Format-Rect {
    param($R)
    if (-not $R) { return '<none>' }
    return "$($R.Left),$($R.Top)-$($R.Right),$($R.Bottom)"
}

function Get-ScreenSize {
    param([switch]$Fresh)
    if ($script:UiScreenSize -and -not $Fresh) { return $script:UiScreenSize }
    # `wm size` prints "Physical size: 1080x2400" and, after an override, also "Override size:".
    $r = Invoke-AdbShell 'wm size' -AllowFailure
    $text = ($r.Output -join "`n")
    $w = 1080; $h = 2400
    if ($text -match 'Override size:\s*(\d+)x(\d+)') { $w = [int]$matches[1]; $h = [int]$matches[2] }
    elseif ($text -match 'Physical size:\s*(\d+)x(\d+)') { $w = [int]$matches[1]; $h = [int]$matches[2] }
    $d = Invoke-AdbShell 'wm density' -AllowFailure
    $dtext = ($d.Output -join "`n")
    $density = 440
    if ($dtext -match 'Override density:\s*(\d+)') { $density = [int]$matches[1] }
    elseif ($dtext -match 'Physical density:\s*(\d+)') { $density = [int]$matches[1] }
    $script:UiScreenSize = [pscustomobject]@{
        Width = $w; Height = $h; Density = $density
        DpWidth = [int]($w * 160 / $density); DpHeight = [int]($h * 160 / $density)
    }
    return $script:UiScreenSize
}

function Get-DpToPx {
    param([int]$Dp)
    $s = Get-ScreenSize
    return [int][Math]::Round($Dp * $s.Density / 160.0)
}

# ------------------------------------------------------------------------------------------------
# taps / long press / swipes
# ------------------------------------------------------------------------------------------------
function Invoke-UiTapPoint {
    param([int]$X, [int]$Y)
    Invoke-AdbShell "input tap $X $Y" -AllowFailure | Out-Null
    Write-Step "tap $X,$Y"
}

function Tap-ByText {
    param([Parameter(Mandatory)][string]$Text, [switch]$Exact, [System.Xml.XmlDocument]$Dump, [switch]$Quiet)
    $b = @(Get-UiBounds -Text $Text -Exact:$Exact -Dump $Dump)
    if ($b.Count -eq 0) { throw "ui text not found: '$Text' (screen: $(Format-UiSnapshot -Dump ($Dump ?? (Get-UiXml))))" }
    Invoke-AdbShell "input tap $($b[0].Cx) $($b[0].Cy)" -AllowFailure | Out-Null
    if (-not $Quiet) { Write-Step "tap text '$Text' at $($b[0].Cx),$($b[0].Cy)" }
    return $b[0]
}

function Tap-ById {
    param([Parameter(Mandatory)][string]$Id, [System.Xml.XmlDocument]$Dump, [switch]$Quiet)
    $b = @(Get-UiBounds -Id $Id -Dump $Dump)
    if ($b.Count -eq 0) { throw "ui id not found: '$Id' (screen: $(Format-UiSnapshot -Dump ($Dump ?? (Get-UiXml))))" }
    Invoke-AdbShell "input tap $($b[0].Cx) $($b[0].Cy)" -AllowFailure | Out-Null
    if (-not $Quiet) { Write-Step "tap id '$Id' at $($b[0].Cx),$($b[0].Cy)" }
    return $b[0]
}

# Taps whatever comes first: id, exact text, substring text, content-desc.
function Tap-Ui {
    param([string[]]$Ids = @(), [string[]]$Texts = @(), [string[]]$Descs = @(),
        [switch]$Exact, [System.Xml.XmlDocument]$Dump, [switch]$Quiet)
    $dump = if ($Dump) { $Dump } else { Get-UiXml }
    foreach ($id in $Ids) {
        $b = @(Get-UiBounds -Id $id -Dump $dump)
        if ($b.Count -gt 0) {
            Invoke-AdbShell "input tap $($b[0].Cx) $($b[0].Cy)" -AllowFailure | Out-Null
            if (-not $Quiet) { Write-Step "tap id '$id' at $($b[0].Cx),$($b[0].Cy)" }
            return [pscustomobject]@{ Ok = $true; How = "id:$id"; Bounds = $b[0] }
        }
    }
    foreach ($t in $Texts) {
        $b = @(Get-UiBounds -Text $t -Exact:$Exact -Dump $dump)
        if ($b.Count -gt 0) {
            Invoke-AdbShell "input tap $($b[0].Cx) $($b[0].Cy)" -AllowFailure | Out-Null
            if (-not $Quiet) { Write-Step "tap text '$t' at $($b[0].Cx),$($b[0].Cy)" }
            return [pscustomobject]@{ Ok = $true; How = "text:$t"; Bounds = $b[0] }
        }
    }
    foreach ($d in $Descs) {
        $b = @(Get-UiBounds -Desc $d -Dump $dump)
        if ($b.Count -gt 0) {
            Invoke-AdbShell "input tap $($b[0].Cx) $($b[0].Cy)" -AllowFailure | Out-Null
            if (-not $Quiet) { Write-Step "tap desc '$d' at $($b[0].Cx),$($b[0].Cy)" }
            return [pscustomobject]@{ Ok = $true; How = "desc:$d"; Bounds = $b[0] }
        }
    }
    return [pscustomobject]@{ Ok = $false; How = ''; Bounds = $null }
}

# Long press = a tap whose finger stays down: `input swipe x y x y <duration>` never moves, so the
# gesture is recognised as a long press (Compose uses ~500 ms).
function Invoke-LongPress {
    param([int]$X, [int]$Y, [int]$DurationMs = 900)
    Invoke-AdbShell "input swipe $X $Y $X $Y $DurationMs" -AllowFailure | Out-Null
    Write-Step "long-press $X,$Y (${DurationMs}ms)"
}

function Swipe {
    param(
        [Parameter(Mandatory)][int]$X1, [Parameter(Mandatory)][int]$Y1,
        [Parameter(Mandatory)][int]$X2, [Parameter(Mandatory)][int]$Y2,
        [int]$DurationMs = 400
    )
    Invoke-AdbShell "input swipe $X1 $Y1 $X2 $Y2 $DurationMs" -AllowFailure | Out-Null
    Write-Step "swipe $X1,$Y1 -> $X2,$Y2 (${DurationMs}ms)"
    return [pscustomobject]@{ X1 = $X1; Y1 = $Y1; X2 = $X2; Y2 = $Y2; DurationMs = $DurationMs }
}

# Swipe from the middle of a rect towards a direction, `DistanceDp` deep.
function Invoke-UiSwipeDirection {
    param(
        [Parameter(Mandatory)]$Bounds,
        [ValidateSet('up', 'down', 'left', 'right')][string]$Direction,
        [int]$DistanceDp = 200,
        [int]$DurationMs = 400
    )
    $px = Get-DpToPx $DistanceDp
    $dx = 0; $dy = 0
    switch ($Direction) {
        'up' { $dy = -$px } 'down' { $dy = $px } 'left' { $dx = -$px } 'right' { $dx = $px }
    }
    $x2 = [Math]::Max(1, [Math]::Min($Bounds.Cx + $dx, 1079))
    $y2 = [Math]::Max(1, $Bounds.Cy + $dy)
    return Swipe -X1 $Bounds.Cx -Y1 $Bounds.Cy -X2 $x2 -Y2 $y2 -DurationMs $DurationMs
}

# ------------------------------------------------------------------------------------------------
# joystick
# ------------------------------------------------------------------------------------------------
# Finds the joystick pad: explicit -Bounds wins, then testTag ids, then content-desc, then any node
# that is a big square in the lower half of the screen (the pad is 152dp, i.e. very distinctive).
function Get-JoystickBounds {
    param([System.Xml.XmlDocument]$Dump, [switch]$Fresh)
    $dump = if ($Dump) { $Dump } else { Get-UiXml -Fresh:$Fresh }
    if (-not $dump) { return $null }
    # The pad is 152dp (418px at 440dpi). Anything smaller is NOT the pad: the FAB icons carry the
    # same word as a contentDescription ('摇杆'), and an earlier version of this function happily
    # returned the 61px FAB icon, so every "joystick" assertion silently measured the FAB.
    $minPx = 300
    $fabIds = @('e2e.fab.joystick', 'e2e.fab.layers', 'e2e.fab.layer', 'e2e.fab.locate', 'e2e.fab.tools')
    $fabRects = @(Get-UiNodes -Dump $dump | Where-Object { $fabIds -contains $_.Id })
    $isFabNode = {
        param($n)
        if ($fabIds -contains $n.Id) { return $true }
        foreach ($f in $fabRects) {
            if ($n.Left -ge $f.Left -and $n.Right -le $f.Right -and $n.Top -ge $f.Top -and $n.Bottom -le $f.Bottom) { return $true }
        }
        return $false
    }
    foreach ($id in $script:JoystickIds) {
        foreach ($b in @(Get-UiBounds -Id $id -Dump $dump)) {
            if ($b.Width -ge $minPx -and $b.Height -ge $minPx) { return $b }
        }
    }
    foreach ($d in $script:JoystickDescs) {
        foreach ($b in @(Get-UiBounds -Desc $d -Dump $dump)) {
            if ($b.Width -lt $minPx -or $b.Height -lt $minPx) { continue }
            if (& $isFabNode $b) { continue }
            return $b
        }
    }
    $s = Get-ScreenSize
    $nodes = @(Get-UiNodes -Dump $dump | Where-Object {
            $_.Top -gt ($s.Height * 0.4) -and
            $_.Width -ge $minPx -and $_.Height -ge $minPx -and
            [Math]::Abs($_.Width - $_.Height) -le 40 -and
            $_.Width -le ($s.Width * 0.6) -and
            -not (& $isFabNode $_)
        } | Sort-Object -Property Width -Descending)
    if ($nodes.Count -gt 0) { return $nodes[0] }
    return $null
}

# Direction vectors use SCREEN coordinates (y grows downwards): up = (0,-1).
function Get-ScreenDirectionVector {
    param([ValidateSet('up', 'down', 'left', 'right', 'north', 'south', 'east', 'west')][string]$Direction)
    switch ($Direction) {
        'up' { return @{ Dx = 0; Dy = -1 } }
        'north' { return @{ Dx = 0; Dy = -1 } }
        'down' { return @{ Dx = 0; Dy = 1 } }
        'south' { return @{ Dx = 0; Dy = 1 } }
        'left' { return @{ Dx = -1; Dy = 0 } }
        'west' { return @{ Dx = -1; Dy = 0 } }
        'right' { return @{ Dx = 1; Dy = 0 } }
        'east' { return @{ Dx = 1; Dy = 0 } }
    }
}

# Geometry of a joystick drag: from the pad centre to a point `Ratio` of the pad radius away.
function Get-JoystickDragPoints {
    param(
        [ValidateSet('up', 'down', 'left', 'right', 'north', 'south', 'east', 'west')][string]$Direction,
        $Bounds,
        [double]$Ratio = 0.75
    )
    $vec = Get-ScreenDirectionVector -Direction $Direction
    $radius = [Math]::Min($Bounds.Width, $Bounds.Height) / 2.0 * $Ratio
    $fx = $Bounds.Cx + $vec.Dx * $radius
    $fy = $Bounds.Cy + $vec.Dy * $radius
    return [pscustomobject]@{
        FromX     = [int]$Bounds.Cx
        FromY     = [int]$Bounds.Cy
        ToX       = [int][Math]::Round($fx)
        ToY       = [int][Math]::Round($fy)
        Direction = $Direction
        Pad       = $Bounds
        Vector    = $vec
    }
}

# Sends the joystick drag as ONE continuous gesture, in the background, so the caller can measure while
# the vector is held.  `adb shell input motionevent DOWN/MOVE/UP` (several processes) does NOT work
# here: each invocation is a separate input stream, the app receives a cancelled gesture and never
# sends a non-zero joystick vector.  `input swipe` with a long duration is a single gesture and does
# produce a held vector (verified on device: engine 13.9 m/s, latitude increasing).
function Start-JoystickPress {
    param(
        [ValidateSet('up', 'down', 'left', 'right', 'north', 'south', 'east', 'west')][string]$Direction,
        $Bounds,
        [double]$Ratio = 0.75,
        [int]$HoldMs = 5000
    )
    if (-not $Bounds) { $Bounds = Get-JoystickBounds -Fresh }
    if (-not $Bounds) { throw "joystick pad not found in the UI tree (screen: $(Format-UiSnapshot -Dump (Get-UiXml)))" }
    $pts = Get-JoystickDragPoints -Direction $Direction -Bounds $Bounds -Ratio $Ratio
    $prefix = @()
    if ($script:AdbSerial) { $prefix = @('-s', $script:AdbSerial) }
    $args = @($prefix) + @('shell', 'input', 'swipe',
        "$($pts.FromX)", "$($pts.FromY)", "$($pts.ToX)", "$($pts.ToY)", "$HoldMs")
    Write-Step "joystick drag ${Direction}: $($pts.FromX),$($pts.FromY) -> $($pts.ToX),$($pts.ToY) over ${HoldMs}ms"
    $script:JoystickSwipeProc = Start-Process -FilePath 'adb' -ArgumentList $args -PassThru -NoNewWindow `
        -RedirectStandardOutput ([System.IO.Path]::GetTempFileName()) -RedirectStandardError ([System.IO.Path]::GetTempFileName())
    $pts | Add-Member -NotePropertyName StartedAt -NotePropertyValue (Get-Date) -Force
    return $pts
}

# Waits for the gesture to finish (it releases the finger itself, which is the "release joystick").
function Stop-JoystickPress {
    param($Points, [int]$MaxWaitMs = 15000)
    if ($script:JoystickSwipeProc) {
        try { $script:JoystickSwipeProc.WaitForExit($MaxWaitMs) | Out-Null } catch { }
        $script:JoystickSwipeProc = $null
    }
    Write-Step 'joystick gesture finished'
}

# True while the background swipe is still running (the caller samples inside this window).
function Test-JoystickPressActive {
    if (-not $script:JoystickSwipeProc) { return $false }
    return (-not $script:JoystickSwipeProc.HasExited)
}

# Full drag: press, hold for -HoldMs (during which the engine moves), release.
# Returns the drag geometry so the caller can compare it with the measured movement bearing.
function DragJoystick {
    param(
        [ValidateSet('up', 'down', 'left', 'right', 'north', 'south', 'east', 'west')][string]$Direction,
        [int]$HoldMs = 3000,
        [double]$Ratio = 0.75,
        [switch]$KeepPressed,
        [switch]$Quiet
    )
    $pts = Start-JoystickPress -Direction $Direction -Ratio $Ratio
    if ($HoldMs -gt 0) { Start-Sleep -Milliseconds $HoldMs }
    if (-not $KeepPressed) { Stop-JoystickPress -Points $pts }
    if (-not $Quiet) { Write-Step "joystick $Direction held ${HoldMs}ms" }
    return $pts
}

# ------------------------------------------------------------------------------------------------
# text input
# ------------------------------------------------------------------------------------------------
# Compose `TextField`/`OutlinedTextField` appear in the accessibility tree as
# `android.widget.EditText`, so this works without any test tag.
function Get-UiTextFields {
    param([System.Xml.XmlDocument]$Dump, [switch]$Fresh)
    $dump = if ($Dump) { $Dump } else { Get-UiXml -Fresh:$Fresh }
    return @(Get-UiNodes -Dump $dump | Where-Object { $_.Class -match 'EditText' })
}

# Focuses a field (the first empty one by default) and types into it. `input text` bypasses the IME,
# so only ASCII (coordinates, URLs, latin names) is typeable — which is all the harness needs.
function Set-UiText {
    param(
        [Parameter(Mandatory)][string]$Value,
        $Field = $null,
        [System.Xml.XmlDocument]$Dump,
        [switch]$ClearFirst,
        [switch]$Enter
    )
    if (-not $Field) {
        $fields = @(Get-UiTextFields -Dump $Dump)
        if ($fields.Count -eq 0) { throw "no text field on screen (screen: $(Format-UiSnapshot -Dump ($Dump ?? (Get-UiXml))))" }
        $Field = @($fields | Where-Object { -not $_.Text }) | Select-Object -First 1
        if (-not $Field) { $Field = $fields[0] }
    }
    Invoke-AdbShell "input tap $($Field.Cx) $($Field.Cy)" -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 500
    if ($ClearFirst) {
        Invoke-AdbShell 'input keyevent KEYCODE_MOVE_END' -AllowFailure | Out-Null
        for ($i = 0; $i -lt 60; $i++) { Invoke-AdbShell 'input keyevent KEYCODE_DEL' -AllowFailure | Out-Null }
    }
    $escaped = $Value -replace ' ', '%s'
    Invoke-AdbShell "input text '$escaped'" -AllowFailure | Out-Null
    Start-Sleep -Milliseconds 400
    if ($Enter) {
        Invoke-AdbShell 'input keyevent 66' -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 900
    }
    Write-Step "typed '$Value' into field at $($Field.Cx),$($Field.Cy)"
    return $Field
}

# Hides the IME *only* when it is actually showing: a blind BACK would close the surface underneath.
function Close-UiKeyboard {
    $r = Invoke-AdbShell 'dumpsys input_method' -AllowFailure
    $shown = (($r.Output -join "`n") -match 'mInputShown=true')
    if ($shown) {
        Invoke-AdbShell 'input keyevent 4' -AllowFailure | Out-Null
        Start-Sleep -Milliseconds 500
        Write-Step 'IME closed'
        return $true
    }
    return $false
}

# ------------------------------------------------------------------------------------------------
# app resources + appearance
# ------------------------------------------------------------------------------------------------
# Resolves R.string.<Key> from the app sources so the matrix never hard-codes UI copy (the strings
# are Chinese and are owned by ui-maps; a copy change must not break the harness silently).
function Get-AppString {
    param([Parameter(Mandatory)][string]$Key, [string]$Default = '')
    if (-not $script:UiAppStrings) {
        $map = @{}
        $resDir = Join-Path $RepoRoot 'app\src\main\res\values'
        foreach ($file in @(Get-ChildItem -Path $resDir -Filter '*.xml' -ErrorAction SilentlyContinue)) {
            try { $doc = [xml](Get-Content $file.FullName -Raw) } catch { continue }
            foreach ($child in $doc.resources.ChildNodes) {
                if ($child.NodeType -ne 'Element') { continue }
                if ($child.LocalName -ne 'string') { continue }
                $name = $child.GetAttribute('name')
                if (-not $name) { continue }
                $map[$name] = "$($child.InnerText)"
            }
        }
        $script:UiAppStrings = $map
    }
    if ($script:UiAppStrings.ContainsKey($Key)) { return $script:UiAppStrings[$Key] }
    return $Default
}

function Get-AppStrings {
    param([Parameter(Mandatory)][string[]]$Keys)
    $out = @()
    foreach ($k in $Keys) {
        $v = Get-AppString -Key $k
        if ($v) { $out += $v }
    }
    return $out
}

function Get-DarkMode {
    $r = Invoke-AdbShell 'cmd uimode night' -AllowFailure
    $text = ($r.Output -join "`n")
    if ($text -match 'Night mode:\s*yes') { return $true }
    if ($text -match 'Night mode:\s*no') { return $false }
    return $null
}

function Set-DarkMode {
    param([Parameter(Mandatory)][bool]$Dark)
    $value = if ($Dark) { 'yes' } else { 'no' }
    Invoke-AdbShell "cmd uimode night $value" -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
    Write-Step "system dark mode = $value"
    return $Dark
}
