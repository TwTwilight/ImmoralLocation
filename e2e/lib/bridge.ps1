# e2e/lib/bridge.ps1 — command channels between the host and the ImmoralLocation engine.
#
# The app<->framework protocol is `com.immoral.location.core.protocol`:
#   * action  com.immoral.location.action.COMMAND      (app -> framework, system_server)
#   * extra   com.immoral.location.extra.PAYLOAD       (Codec JSON)
#   * reply   com.immoral.location.action.REPLY        (framework -> app)
#   * Codec uses classDiscriminator "cmd" and encodeDefaults = true, e.g.
#       {"cmd":"SetPosition","point":{"lat":39.9,"lon":116.4,"alt":50.0}}
#
# Three channels exist, in decreasing order of preference:
#
#   (a) broadcast  — `am broadcast ... -p <package>`  (needs the system_server receiver to be up)
#   (b) prefs      — write the app's SharedPreferences keys the framework polls every 250 ms:
#                    sim_seq (Int, bumped), sim_cmd (Codec JSON), sim_config (Codec JSON of
#                    SimulationConfig), sim_active (Bool)
#   (c) ui         — uiautomator taps, used for the acceptance checks of the real apps and for
#                    favourites (收藏/快速返回), which are pure UI features
#
# Everything here only *sends*; assertions read the verifier's VERIFY lines (lib/verifier.ps1)
# or the framework log (tag LSPosedFramework).

. (Join-Path $PSScriptRoot 'common.ps1')

$script:BridgeAction = 'com.immoral.location.action.COMMAND'
$script:BridgeReply = 'com.immoral.location.action.REPLY'
$script:BridgeExtra = 'com.immoral.location.extra.PAYLOAD'
$script:BcastReady = $false
$script:ChannelFallbacks = 0   # incremented whenever a command silently degrades to the prefs channel

function Get-ModulePackageName {
    # `pm path` is cheaper and far more reliable than `pm list packages` right after an install /
    # when the package service is churning; fall back to the list form only if it says nothing.
    foreach ($attempt in 1..3) {
        foreach ($candidate in @('io.github.twtwilight.immorallocation.debug', 'com.immoral.location')) {
            $r = Invoke-AdbShell "pm path $candidate" -AllowFailure
            if (($r.Output -join '') -match '^package:') { return $candidate }
        }
        foreach ($candidate in @('io.github.twtwilight.immorallocation.debug', 'com.immoral.location')) {
            if (Test-PackageInstalled $candidate) { return $candidate }
        }
        Start-Sleep -Seconds 2
    }
    throw 'ImmoralLocation is not installed (expected io.github.twtwilight.immorallocation.debug)'
}

# ---------------------------------------------------------------------------------------------
# Codec-shaped payload builders.  Keep the key names byte-identical to :core Protocol.kt.
# ---------------------------------------------------------------------------------------------
function New-BridgeCommand {
    param(
        [Parameter(Mandatory)][string]$Cmd,
        [hashtable]$Fields = @{}
    )
    $payload = [ordered]@{ cmd = $Cmd }
    foreach ($key in $Fields.Keys) { $payload[$key] = $Fields[$key] }
    return ($payload | ConvertTo-Json -Compress -Depth 10)
}

function ConvertTo-BridgeJson($Value) {
    return ($Value | ConvertTo-Json -Compress -Depth 10)
}

# (a) broadcast channel.
# Returns an object with Delivered=$true when the command apparently reached a receiver
# (best effort: `am broadcast` prints "Broadcast completed: result=0" only when a receiver ran).
# Uploads the device-side sender script (once per session).
function Initialize-BroadcastSender {
    if ($script:BcastReady) { return }
    $local = Join-Path $RepoRoot 'e2e\tools\bcast.sh'
    if (-not (Test-Path $local)) { throw "missing $local" }
    # ship it with LF endings; a CRLF shebang breaks `sh script.sh` on Android
    $text = (Get-Content $local -Raw) -replace "`r`n", "`n"
    $tmp = Join-Path $OutDir 'bcast.sh'
    [System.IO.File]::WriteAllText($tmp, $text, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Adb push $tmp '/data/local/tmp/e2e_bcast.sh' | Out-Null
    Invoke-AdbRoot 'chmod 755 /data/local/tmp/e2e_bcast.sh' -AllowFailure | Out-Null
    $script:BcastReady = $true
}

# (a) broadcast channel — the authoritative one.
#
# The JSON payload NEVER goes through a shell string: it is written byte-exact to a file, pushed,
# and handed to `am broadcast` by e2e/tools/bcast.sh on the device. Inline quoting silently strips
# the double quotes (the JSON then fails Codec.decodeCommand and is dropped), which is by far the
# most expensive trap in this harness.
#
# The receiver is registered inside system_server (uid 1000), so by default the broadcast is NOT
# package-scoped: `-p <our app package>` makes the framework filter the intent away from the
# system uid and the command is never delivered. Pass -Package only for diagnostics.
function Send-BroadcastCommand {
    param(
        [Parameter(Mandatory)][string]$Payload,
        [string]$Package = '',
        [ValidateSet('auto', 'root', 'shell')][string]$As = 'auto',
        [switch]$Quiet
    )
    Initialize-BroadcastSender
    $local = Join-Path $OutDir 'payload.json'
    [System.IO.File]::WriteAllText($local, $Payload, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Adb push $local '/data/local/tmp/e2e_payload.json' | Out-Null

    $cmd = "/data/local/tmp/e2e_bcast.sh /data/local/tmp/e2e_payload.json"
    if ($Package) { $cmd += " $Package" }
    $deviceCmd = 'sh ' + $cmd
    # The receiver is guarded by a signature permission, which `shell` does not hold; root does.
    $useRoot = ($As -eq 'root') -or ($As -eq 'auto' -and (Test-Root))
    $r = if ($useRoot) { Invoke-AdbRoot $deviceCmd -AllowFailure } else { Invoke-AdbShell $deviceCmd -AllowFailure }
    $text = ($r.Output -join "`n")
    if (-not $Quiet) {
        $via = if ($useRoot) { 'root' } else { 'shell' }
        Write-Step "broadcast[$via] $($Payload.Substring(0, [Math]::Min(80, $Payload.Length)))"
    }
    $denied = $text -match 'SecurityException|Permission Denial|not allowed to send broadcast'
    $sentBytes = 0
    if ($text -match 'sending (\d+) bytes') { $sentBytes = [int]$matches[1] }
    return [pscustomobject]@{
        Delivered = ($text -match 'Broadcast completed' -and $text -notmatch 'result=-1' -and -not $denied)
        Bytes     = $sentBytes
        Denied    = $denied
        Output    = $text
    }
}

# (b) SharedPreferences channel.
# The framework engine polls these keys; bumping sim_seq is what makes it apply sim_cmd.
function Get-PrefsPath {
    param([string]$Package)
    $dir = "/data/user/0/$Package/shared_prefs"
    $r = Invoke-AdbRoot "ls $dir 2>/dev/null" -AllowFailure
    $files = @($r.Output | ForEach-Object { $_.Trim() } | Where-Object { $_ -like '*.xml' })
    if ($files.Count -eq 0) { return $null }
    # Prefer a file that already carries the engine keys, else the first one.
    foreach ($f in $files) {
        $probe = Invoke-AdbRoot "grep -l sim_cmd '$dir/$f' 2>/dev/null" -AllowFailure
        if (($probe.Output -join '').Trim()) { return "$dir/$f" }
    }
    return "$dir/$($files[0])"
}

function Get-PrefsXml {
    param([string]$Path)
    $r = Invoke-AdbRoot "cat '$Path'" -AllowFailure
    $text = ($r.Output -join "`n")
    if (-not $text.Trim()) { return $null }
    # strip a UTF-8 BOM / leading blank lines added by the shell round-trip
    $text = $text.TrimStart([char]0xFEFF, [char]0x00EF, [char]0x00BB, [char]0x00BF).TrimStart()
    if ($text -notmatch '^<\?xml|^<map') {
        $idx = $text.IndexOf('<?xml')
        if ($idx -lt 0) { $idx = $text.IndexOf('<map') }
        if ($idx -lt 0) { return $null }
        $text = $text.Substring($idx)
    }
    return $text
}

function Set-XmlChild {
    param([System.Xml.XmlDocument]$Doc, [string]$Name, [string]$Type, [string]$Value)
    # SharedPreferences entries look like <string name="sim_seq">…</string>: the element NAME is the
    # type and the key lives in the name attribute, so select by attribute, never by element name.
    $node = $Doc.SelectSingleNode("/map/*[@name='$Name']")
    if ($null -eq $node) {
        $tag = switch ($Type) {
            'int' { 'int' } 'boolean' { 'boolean' } 'long' { 'long' } 'float' { 'float' } default { 'string' }
        }
        $node = $Doc.CreateElement($tag)
        $node.SetAttribute('name', $Name)
        $Doc.DocumentElement.AppendChild($node) | Out-Null
    }
    # <string> entries carry the value as element text, <int>/<boolean> as a value attribute.
    if ($node.HasAttribute('value')) { $node.SetAttribute('value', $Value) }
    else { $node.InnerText = $Value }
}

function Get-XmlChildValue {
    param([System.Xml.XmlDocument]$Doc, [string]$Name)
    $node = $Doc.SelectSingleNode("/map/*[@name='$Name']")
    if ($null -eq $node) { return $null }
    if ($node.HasAttribute('value')) { return $node.GetAttribute('value') }
    return $node.InnerText
}

function Remove-DuplicatePreferenceNodes {
    param([System.Xml.XmlDocument]$Doc)
    $seen = @{}
    foreach ($node in @($Doc.DocumentElement.ChildNodes)) {
        if ($node.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        $name = $node.GetAttribute('name')
        if (-not $name) { continue }
        if ($seen.ContainsKey($name)) {
            $Doc.DocumentElement.RemoveChild($node) | Out-Null
            Write-Warn2 "removed duplicate prefs node '$name'"
        }
        else { $seen[$name] = $true }
    }
}

# <string> entries keep their payload in the element text; a stray value="" attribute (left by an
# older revision of this helper) makes SharedPreferences return the wrong thing, so drop it.
function Reset-StringPreferenceAttributes {
    param([System.Xml.XmlDocument]$Doc)
    foreach ($node in @($Doc.DocumentElement.ChildNodes)) {
        if ($node.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        if ($node.LocalName -ne 'string') { continue }
        if ($node.HasAttribute('value') -and $node.InnerText.Trim()) {
            $node.RemoveAttribute('value')
        }
    }
}

function Send-PrefsCommand {
    param(
        [Parameter(Mandatory)][string]$Payload,
        [string]$ConfigPayload = '',
        [string]$Package = '',
        [switch]$Inactive
    )
    if (-not $Package) { $Package = Get-ModulePackageName }
    $path = Get-PrefsPath -Package $Package
    $xmlText = if ($path) { Get-PrefsXml -Path $path } else { $null }
    if (-not $xmlText) {
        Write-Warn2 "no prefs file found under /data/user/0/$Package/shared_prefs — launching the app once"
        Invoke-AdbShell "monkey -p $Package -c android.intent.category.LAUNCHER 1" -AllowFailure | Out-Null
        Start-Sleep -Seconds 3
        $path = Get-PrefsPath -Package $Package
        $xmlText = if ($path) { Get-PrefsXml -Path $path } else { $null }
    }
    if (-not $xmlText) {
        throw "cannot reach SharedPreferences of $Package (does the app create prefs on first launch?)"
    }

    $doc = New-Object System.Xml.XmlDocument
    $doc.PreserveWhitespace = $false
    $doc.LoadXml($xmlText)
    if (-not $doc.DocumentElement) {
        $doc.LoadXml('<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map />')
    }
    if ($doc.DocumentElement.LocalName -ne 'map') { throw "unexpected prefs root <$($doc.DocumentElement.Name)> in $path" }

    $seq = 0
    $seqRaw = Get-XmlChildValue -Doc $doc -Name 'sim_seq'
    if ($seqRaw) { [int]::TryParse("$seqRaw".Trim(), [ref]$seq) | Out-Null }
    $seq++
    Remove-DuplicatePreferenceNodes -Doc $doc
    Reset-StringPreferenceAttributes -Doc $doc
    Set-XmlChild $doc 'sim_seq' 'int' "$seq"
    Set-XmlChild $doc 'sim_cmd' 'string' $Payload
    if ($ConfigPayload) { Set-XmlChild $doc 'sim_config' 'string' $ConfigPayload }
    Set-XmlChild $doc 'sim_active' 'boolean' $(if ($Inactive) { 'false' } else { 'true' })

    $tmp = Join-Path $OutDir 'bridge_prefs.xml'
    # Android's SharedPreferences parser chokes on a BOM, so write UTF-8 without one.
    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Encoding = New-Object System.Text.UTF8Encoding($false)
    $settings.Indent = $true
    $writer = [System.Xml.XmlWriter]::Create($tmp, $settings)
    try { $doc.Save($writer) } finally { $writer.Dispose() }
    $remote = '/data/local/tmp/e2e_bridge_prefs.xml'
    Invoke-Adb push $tmp $remote | Out-Null
    $owner = (Invoke-AdbRoot "stat -c '%u:%g' '$path'" -AllowFailure).Output |
        Where-Object { $_ -match '^\d+:\d+$' } | Select-Object -First 1
    if (-not $owner) { $owner = 'root:root' }
    $install = "cp $remote '$path' && chown $owner '$path' && chmod 660 '$path' && grep -c sim_cmd '$path'"
    $r = Invoke-AdbRoot $install -AllowFailure
    if (($r.Output -join "`n") -notmatch '^[1-9]') {
        throw "failed to write prefs $path`n$($r.Output -join "`n")"
    }
    Write-Step "prefs sim_seq=$seq sim_cmd=$($Payload.Substring(0, [Math]::Min(80, $Payload.Length)))"
    return [pscustomobject]@{ Delivered = $true; Seq = $seq; Path = $path }
}

# One entry point the matrix scripts use: try broadcast unless told otherwise.
function Send-EngineCommand {
    param(
        [Parameter(Mandatory)][string]$Payload,
        [string]$ConfigPayload = '',
        [ValidateSet('auto', 'broadcast', 'prefs')][string]$Channel = 'auto',
        [string]$Package = '',
        [switch]$Inactive
    )
    switch ($Channel) {
        'broadcast' { return Send-BroadcastCommand -Payload $Payload -Package $Package }
        'prefs' { return Send-PrefsCommand -Payload $Payload -ConfigPayload $ConfigPayload -Package $Package -Inactive:$Inactive }
        default {
            $r = Send-BroadcastCommand -Payload $Payload -Package $Package -Quiet
            if ($r.Delivered) { Write-Step "broadcast accepted: $Payload" ; return $r }
            Write-Warn2 'broadcast channel unavailable (receiver down?) — falling back to SharedPreferences'
            # Counted so the run can report it: a silent degradation to a different control channel is
            # exactly the kind of invisible failure that turns a harness problem into a "product" red row.
            $script:ChannelFallbacks = 1 + [int]$script:ChannelFallbacks
            return Send-PrefsCommand -Payload $Payload -ConfigPayload $ConfigPayload -Package $Package -Inactive:$Inactive
        }
    }
}

# ---------------------------------------------------------------------------------------------
# typed helpers
# ---------------------------------------------------------------------------------------------
function Send-Ping { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'Ping') -Channel $Channel }
function Send-GetState { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'GetState') -Channel $Channel }
function Send-Start { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'Start') -Channel $Channel }
function Send-Stop { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'Stop') -Channel $Channel -Inactive }

function Send-SetPosition {
    param([double]$Lat, [double]$Lon, [double]$Alt = 50.0, [string]$Channel = 'auto')
    $p = New-BridgeCommand 'SetPosition' @{ point = [ordered]@{ lat = $Lat; lon = $Lon; alt = $Alt } }
    return Send-EngineCommand -Payload $p -Channel $Channel
}

function Send-SetJoystick {
    param([double]$X, [double]$Y, [string]$Channel = 'auto')
    $p = New-BridgeCommand 'SetJoystick' @{ x = [double]$X; y = [double]$Y }
    return Send-EngineCommand -Payload $p -Channel $Channel
}

function Send-ReleaseJoystick { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'ReleaseJoystick') -Channel $Channel }
function Send-SetHeading { param([double]$Deg, [string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'SetHeading' @{ deg = [double]$Deg }) -Channel $Channel }

function Send-SetConfig {
    param([hashtable]$Config, [string]$Channel = 'auto')
    $payload = New-BridgeCommand 'SetConfig' @{ config = $Config }
    $configJson = ConvertTo-BridgeJson $Config
    return Send-EngineCommand -Payload $payload -ConfigPayload $configJson -Channel $Channel
}

function Send-StartRoute {
    param(
        [Parameter(Mandatory)][array]$Points,   # array of @{lat=..;lon=..;alt=..}
        [double]$SpeedMps = 2.0,
        [bool]$Repeat = $false,
        [string]$Channel = 'auto'
    )
    $plan = [ordered]@{
        points   = @($Points | ForEach-Object {
                [ordered]@{ lat = [double]$_.lat; lon = [double]$_.lon; alt = [double]($_.alt ?? 50.0) }
            })
        repeat   = $Repeat
        speedMps = [double]$SpeedMps
    }
    return Send-EngineCommand -Payload (New-BridgeCommand 'StartRoute' @{ plan = $plan }) -Channel $Channel
}

function Send-PlayRoute { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'PlayRoute') -Channel $Channel }
function Send-PauseRoute { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'PauseRoute') -Channel $Channel }
function Send-StopRoute { param([string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'StopRoute') -Channel $Channel }
function Send-ResetSteps { param([long]$Steps = 0, [string]$Channel = 'auto') Send-EngineCommand -Payload (New-BridgeCommand 'ResetSteps' @{ steps = $Steps }) -Channel $Channel }

# ---------------------------------------------------------------------------------------------
# (c) UI channel — uiautomator dump + tap
# ---------------------------------------------------------------------------------------------
function Get-UiDump {
    param([string]$Remote = '/data/local/tmp/e2e_ui.xml')
    Invoke-AdbShell "uiautomator dump $Remote" -AllowFailure | Out-Null
    $local = Join-Path $OutDir 'ui.xml'
    $pull = Invoke-Adb pull $Remote $local
    if ($pull.Code -ne 0 -or -not (Test-Path $local)) { return $null }
    return [xml](Get-Content $local -Raw)
}

# Finds a node by exact text (preferred), substring, or resource-id.
# Exact matching matters: '收藏' also appears inside the map hint "点击地图快速切换位置 · 长按添加收藏",
# and an earlier revision happily tapped that hint instead of the navigation tab.
function Get-UiNodeBounds {
    param(
        [System.Xml.XmlDocument]$Dump,
        [string]$Text = '',
        [string]$ResourceId = '',
        [switch]$Exact,
        [switch]$AnyText
    )
    if (-not $Dump) { return $null }
    foreach ($mode in @('exact', 'substring')) {
        foreach ($node in $Dump.SelectNodes('//node')) {
            $t = $node.GetAttribute('text')
            $id = $node.GetAttribute('resource-id')
            if ($ResourceId -and $id -ne $ResourceId) { continue }
            if ($ResourceId -and -not $Text) {
                # id-only lookup
            }
            elseif ($Text) {
                if ($mode -eq 'exact' -and $t -ne $Text) { continue }
                if ($mode -eq 'substring' -and $Exact) { continue }
                if ($mode -eq 'substring' -and $t -notlike "*$Text*") { continue }
            }
            elseif (-not $AnyText) { continue }
            $b = $node.GetAttribute('bounds')
            if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                $left = [int]$matches[1]; $top = [int]$matches[2]
                $right = [int]$matches[3]; $bottom = [int]$matches[4]
                if ($right -le $left -or $bottom -le $top) { continue }
                return [pscustomobject]@{
                    Text   = $t
                    Id     = $id
                    Clickable = $node.GetAttribute('clickable')
                    Left   = $left
                    Top    = $top
                    Right  = $right
                    Bottom = $bottom
                    Cx     = [int](($left + $right) / 2)
                    Cy     = [int](($top + $bottom) / 2)
                }
            }
        }
    }
    return $null
}

function Invoke-UiTap {
    param([string]$Text = '', [string]$ResourceId = '', [System.Xml.XmlDocument]$Dump, [switch]$Exact)
    if (-not $Dump) { $Dump = Get-UiDump }
    $b = Get-UiNodeBounds -Dump $Dump -Text $Text -ResourceId $ResourceId -Exact:$Exact
    if (-not $b) { throw "ui element not found (text='$Text' id='$ResourceId' exact=$Exact)" }
    Invoke-AdbShell "input tap $($b.Cx) $($b.Cy)" -AllowFailure | Out-Null
    Write-Step "tapped '$($b.Text)' at $($b.Cx),$($b.Cy)"
    return $b
}

function Test-UiText {
    param([string]$Text, [System.Xml.XmlDocument]$Dump, [switch]$Exact)
    if (-not $Dump) { $Dump = Get-UiDump }
    if (-not $Dump) { return $false }
    return [bool](Get-UiNodeBounds -Dump $Dump -Text $Text -Exact:$Exact)
}

function Start-App {
    param([string]$Package = '', [string]$Activity = '')
    if (-not $Package) { $Package = Get-ModulePackageName }
    if (-not $Activity) { $Activity = "$Package/com.immoral.location.ui.MainActivity" }
    Invoke-AdbShell "am start -W -n $Activity" -AllowFailure | Out-Null
    Start-Sleep -Seconds 2
}

# Finds a tappable node by exact text, substring text, or content-desc (in that order).
function Find-UiTarget {
    param([System.Xml.XmlDocument]$Dump, [Parameter(Mandatory)][string[]]$Names)
    if (-not $Dump) { return $null }
    foreach ($name in $Names) {
        $b = Get-UiNodeBounds -Dump $Dump -Text $name -Exact
        if ($b) { return [pscustomobject]@{ Bounds = $b; How = "exact:$name" } }
    }
    foreach ($name in $Names) {
        $b = Get-UiNodeBounds -Dump $Dump -Text $name
        if ($b) { return [pscustomobject]@{ Bounds = $b; How = "text:$name" } }
    }
    foreach ($node in $Dump.SelectNodes('//node')) {
        $desc = $node.GetAttribute('content-desc')
        if (-not $desc) { continue }
        foreach ($name in $Names) {
            if ($desc -notlike "*$name*") { continue }
            if ($node.GetAttribute('bounds') -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                return [pscustomobject]@{
                    Bounds = [pscustomobject]@{
                        Text = $desc
                        Cx   = [int](([int]$matches[1] + [int]$matches[3]) / 2)
                        Cy   = [int](([int]$matches[2] + [int]$matches[4]) / 2)
                    }
                    How = "desc:$desc"
                }
            }
        }
    }
    return $null
}

# Navigates to a named screen WITHOUT hard-coding the current information architecture.
# The UI moved from "5 bottom tabs" to "map + tools sheet" during the project, so 收藏/设置 may live
# behind a tools sheet or an overflow control: look for the destination, and if it is not on screen,
# open a tools/overflow surface and look again. Returns the resulting dump + the path taken.
function Open-UiScreen {
    param(
        [Parameter(Mandatory)][string[]]$Names,
        [string[]]$Openers = @('工具', '更多', '菜单', 'Tools', 'More'),
        [int]$Rounds = 3,
        [int]$SettleMs = 1500
    )
    $path = @()
    $dump = Get-UiDump
    for ($round = 0; $round -lt $Rounds; $round++) {
        if (-not $dump) { $dump = Get-UiDump }
        $target = Find-UiTarget -Dump $dump -Names $Names
        if ($target) {
            Invoke-AdbShell "input tap $($target.Bounds.Cx) $($target.Bounds.Cy)" -AllowFailure | Out-Null
            $path += $target.How
            Write-Step ("open '{0}' via {1}" -f ($Names -join '|'), ($path -join ' > '))
            Start-Sleep -Milliseconds $SettleMs
            return [pscustomobject]@{ Dump = (Get-UiDump); Path = ($path -join ' > ') }
        }
        $opener = Find-UiTarget -Dump $dump -Names $Openers
        if ($opener) {
            Invoke-AdbShell "input tap $($opener.Bounds.Cx) $($opener.Bounds.Cy)" -AllowFailure | Out-Null
            $path += "opener>" + $opener.How
            Start-Sleep -Milliseconds $SettleMs
            $dump = Get-UiDump
        }
        else {
            Start-Sleep -Milliseconds $SettleMs
            $dump = $null
        }
    }
    return [pscustomobject]@{ Dump = (Get-UiDump); Path = ($path -join ' > ') }
}

