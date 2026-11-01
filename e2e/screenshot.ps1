<#
.SYNOPSIS
    Capture the device screen into docs/screenshots/ and print the resulting path.

.DESCRIPTION
    Works headless: it only needs adb (no GUI, no scrcpy, no device interaction), so a teammate who
    cannot touch the phone can ask for a picture and read the PNG from the workspace.

    Capture strategy (in order, each step is validated before it is accepted):

      1. `adb exec-out screencap -p` redirected straight into a file.  PowerShell redirection of a
         native command is byte-preserving on 7.4+, and `Start-Process -RedirectStandardOutput`
         never goes through the pipeline at all, which is what we use here.  This is the fast path
         and it does not touch /sdcard.
      2. `adb shell screencap -p /data/local/tmp/… && adb pull` — binary safe by definition.  Used
         when the exec-out path produced something that is not a valid PNG (CRLF mangling, adb
         version quirks, a partially written buffer).

    Every capture is validated: PNG magic bytes, an IEND chunk at the end, and a plausible size.
    A capture that fails both strategies throws, so a screenshot assertion can never silently
    "pass" with a 0-byte or text file.

.PARAMETER Name
    File name (without directory), with or without `.png`.  Defaults to `screen-<timestamp>`.
    Path separators are rejected; the file always lands directly in -OutDir.

.PARAMETER Destination
    Destination directory.  Default `docs/screenshots` (inside the repo).  Do not name this
    parameter `OutDir`: the dot-sourced `lib/common.ps1` assigns `$script:OutDir` (= e2e/out), and
    at script scope that *is* the parameter variable, so the default silently wins.

.PARAMETER Serial
    adb serial.  Default: auto-detect (ANDROID_SERIAL, else the only attached device).

.PARAMETER KeepDeviceCopy
    Keep /data/local/tmp/e2e_shot.png on the device (debugging).

.PARAMETER Quiet
    Do not write the path to the console; only return it.

.EXAMPLE
    .\e2e\screenshot.ps1 map-peek
    Saves docs/screenshots/map-peek.png and prints its absolute path.

.EXAMPLE
    $p = .\e2e\screenshot.ps1 -Name half-sheet -Quiet
    Silently captures and returns the path (used by capture_screens.ps1).

.OUTPUTS
    System.String — the absolute path of the written PNG.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Name = '',
    [string]$Destination = '',
    [string]$Serial = '',
    [switch]$KeepDeviceCopy,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib\common.ps1')

# ------------------------------------------------------------------------------------------------
# PNG validation / metadata
# ------------------------------------------------------------------------------------------------
$PngMagic = [byte[]](0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

function Test-PngFile {
    param([Parameter(Mandatory)][string]$Path, [int]$MinBytes = 2048)
    if (-not (Test-Path $Path)) { return [pscustomobject]@{ Ok = $false; Reason = 'missing' } }
    $len = (Get-Item $Path).Length
    if ($len -lt $MinBytes) { return [pscustomobject]@{ Ok = $false; Reason = "too small ($len bytes)" } }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    for ($i = 0; $i -lt 8; $i++) {
        if ($bytes[$i] -ne $PngMagic[$i]) {
            return [pscustomobject]@{ Ok = $false; Reason = "bad magic (byte $i = 0x$('{0:X2}' -f $bytes[$i]))" }
        }
    }
    # IEND is the last chunk: "IEND" + CRC, i.e. these 8 bytes close the file.
    $tail = [System.Text.Encoding]::ASCII.GetString($bytes, [Math]::Max(0, $len - 8), 8)
    if ($tail -notmatch 'IEND') { return [pscustomobject]@{ Ok = $false; Reason = "no IEND chunk (tail '$tail')" } }
    return [pscustomobject]@{ Ok = $true; Reason = ''; Bytes = $len; Content = $bytes }
}

# IHDR carries the dimensions as two big-endian int32 at offset 16 and 20.
function Get-PngSize {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    if ($Bytes.Length -lt 24) { return [pscustomobject]@{ Width = 0; Height = 0 } }
    $w = ($Bytes[16] -shl 24) -bor ($Bytes[17] -shl 16) -bor ($Bytes[18] -shl 8) -bor $Bytes[19]
    $h = ($Bytes[20] -shl 24) -bor ($Bytes[21] -shl 16) -bor ($Bytes[22] -shl 8) -bor $Bytes[23]
    return [pscustomobject]@{ Width = $w; Height = $h }
}

# ------------------------------------------------------------------------------------------------
# capture
# ------------------------------------------------------------------------------------------------

# Fast path: exec-out straight into a file through an OS-level redirect (no PowerShell pipeline).
function Invoke-ExecOutCapture {
    param([Parameter(Mandatory)][string]$Target)
    $prefix = @()
    if ($script:AdbSerial) { $prefix = @('-s', $script:AdbSerial) }
    $args = @($prefix) + @('exec-out', 'screencap', '-p')
    if (Test-Path $Target) { Remove-Item $Target -Force }
    # stderr goes to its own file: mixing it into stdout would corrupt the PNG.
    $errFile = "$Target.stderr.txt"
    $p = Start-Process -FilePath 'adb' -ArgumentList $args -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $Target -RedirectStandardError $errFile
    $err = if (Test-Path $errFile) { (Get-Content $errFile -Raw) } else { '' }
    if (Test-Path $errFile) { Remove-Item $errFile -Force }
    return [pscustomobject]@{ Code = $p.ExitCode; Stderr = $err }
}

# Fallback: write on the device, pull byte-exactly.
function Invoke-PullCapture {
    param([Parameter(Mandatory)][string]$Target, [string]$Remote = '/data/local/tmp/e2e_shot.png')
    Invoke-AdbShell "screencap -p $Remote" | Out-Null
    if (Test-Path $Target) { Remove-Item $Target -Force }
    $r = Invoke-Adb pull $Remote $Target
    if (-not $KeepDeviceCopy) { Invoke-AdbShell "rm -f $Remote" -AllowFailure | Out-Null }
    return [pscustomobject]@{ Code = $r.Code; Stderr = ($r.Output -join "`n") }
}

function Get-Screenshot {
    <#
        Capture one frame and return [pscustomobject]@{Path; Bytes; Width; Height; Strategy}.
        `-Path` is the local destination; throws when no valid PNG could be produced.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$Remote = '/data/local/tmp/e2e_shot.png',
        [int]$Retries = 2
    )
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

    $reasons = @()
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        $r = Invoke-ExecOutCapture -Target $Path
        $v = Test-PngFile -Path $Path
        if ($v.Ok) {
            $size = Get-PngSize -Bytes $v.Content
            return [pscustomobject]@{ Path = $Path; Bytes = $v.Bytes; Width = $size.Width; Height = $size.Height; Strategy = 'exec-out' }
        }
        $reasons += "exec-out#$attempt : $($v.Reason) (exit $($r.Code) $($r.Stderr.Trim()))"
    }

    $r = Invoke-PullCapture -Target $Path -Remote $Remote
    $v = Test-PngFile -Path $Path
    if ($v.Ok) {
        $size = Get-PngSize -Bytes $v.Content
        return [pscustomobject]@{ Path = $Path; Bytes = $v.Bytes; Width = $size.Width; Height = $size.Height; Strategy = 'screencap+pull' }
    }
    $reasons += "pull : $($v.Reason) (exit $($r.Code) $($r.Stderr.Trim()))"
    throw ("screenshot failed for '$Path'`n  " + ($reasons -join "`n  "))
}

function Format-ScreenshotName {
    param([string]$Name)
    if (-not $Name) { return 'screen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
    if ($Name -match '[/\\]') { throw "screenshot name must not contain a path separator: '$Name'" }
    $Name = $Name -replace '\.png$', ''
    # keep letters/digits/dot/dash/underscore and CJK; everything else becomes '-'
    $Name = ($Name -replace '[^\p{L}\p{Nd}._-]+', '-').Trim('-')
    if (-not $Name) { $Name = 'screen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
    return $Name
}

# ------------------------------------------------------------------------------------------------
# main
# ------------------------------------------------------------------------------------------------
Select-AndroidDevice -Serial $Serial -Wait | Out-Null

if (-not $Destination) { $Destination = Join-Path $RepoRoot 'docs\screenshots' }
if (-not (Test-Path $Destination)) { New-Item -ItemType Directory -Force -Path $Destination | Out-Null }

$fileBase = Format-ScreenshotName $Name
$target = Join-Path $Destination ($fileBase + '.png')

$shot = Get-Screenshot -Path $target
if (-not $Quiet) {
    Write-Ok ("screenshot {0} ({1}x{2}, {3:N0} bytes, {4})" -f $shot.Path, $shot.Width, $shot.Height, $shot.Bytes, $shot.Strategy)
    Write-Host $shot.Path
}
return $shot.Path
