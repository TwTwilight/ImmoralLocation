# Static guard for a defect class neither the compiler nor the layout checks can see:
# a string resource that carries a format placeholder (%1$...) being read with stringResource(id) and no
# argument. Android returns the raw text, so the user is shown "= %1$.1f km/h" instead of a value.
# StatusSheet shipped exactly that (docs/UIUX-AUDIT.md 34).
param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$stringsPath = Join-Path $root 'app/src/main/res/values/strings.xml'

function Get-FormatStrings([string]$xml) {
    $found = @{}
    foreach ($m in [regex]::Matches($xml, '<string name="([^"]+)"[^>]*>([^<]*)</string>')) {
        if ($m.Groups[2].Value -match '%\d+\$') { $found[$m.Groups[1].Value] = $m.Groups[2].Value }
    }
    return $found
}

# Every stringResource(R.string.NAME) with no second argument, across all Kotlin sources.
function Get-UnformattedUses([string]$source) {
    $uses = @()
    foreach ($m in [regex]::Matches($source, 'stringResource\(\s*R\.string\.([A-Za-z0-9_]+)\s*\)')) {
        $uses += $m.Groups[1].Value
    }
    return $uses
}

if ($SelfTest) {
    # The detector must fire on the exact shape that shipped, and stay quiet on the corrected one.
    $sample = '<string name="label_kmh">~ %1$.1f km/h</string><string name="plain">hi</string>'
    $fmt = Get-FormatStrings $sample
    $bad = Get-UnformattedUses 'label = stringResource(R.string.label_kmh), other = stringResource(R.string.plain)'
    $hits = @($bad | Where-Object { $fmt.ContainsKey($_) })
    if ($hits.Count -ne 1 -or $hits[0] -ne 'label_kmh') { throw "self-test failed: expected [label_kmh], got [$($hits -join ',')]" }
    $good = Get-UnformattedUses 'label = stringResource(R.string.plain)'
    $hits2 = @($good | Where-Object { $fmt.ContainsKey($_) })
    if ($hits2.Count -ne 0) { throw "self-test failed: plain string flagged" }
    'string check self-test OK'
    exit 0
}

$formatStrings = Get-FormatStrings (Get-Content -LiteralPath $stringsPath -Raw)
$violations = @()
$files = Get-ChildItem -Path (Join-Path $root 'app/src/main/kotlin') -Recurse -Filter *.kt
foreach ($f in $files) {
    $text = Get-Content -LiteralPath $f.FullName -Raw
    foreach ($name in (Get-UnformattedUses $text)) {
        if ($formatStrings.ContainsKey($name)) {
            $line = ($text.Substring(0, $text.IndexOf("R.string.$name")).Split("`n").Count)
            $violations += [pscustomobject]@{ File = $f.Name; Line = $line; Name = $name; Value = $formatStrings[$name] }
        }
    }
}

if ($violations.Count -gt 0) {
    foreach ($v in $violations) {
        "[string] {0}:{1} reads R.string.{2} with no argument, but it is a format string: {3}" -f $v.File, $v.Line, $v.Name, $v.Value
    }
    "string check FAILED: $($violations.Count) format string(s) rendered raw"
    exit 1
}
"string check OK: $($formatStrings.Count) format string(s), all used with arguments"
exit 0
