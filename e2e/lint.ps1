# e2e/lint.ps1 — static checks for the harness itself, safe to run anywhere (no device, no adb).
#
#   pwsh -NoProfile -File e2e/lint.ps1          # exit 1 on any error
#   pwsh -NoProfile -File e2e/lint.ps1 -Verbose
#
# Three classes of problem are detected, all of which have broken a real run at least once:
#
#   1. Parse errors            — a script that cannot even be parsed (e.g. "$Name:" inside a string,
#                                which PowerShell reads as a scope-qualified variable).
#   2. Undefined-command use   — calling a helper that no lib/*.ps1 defines (the classic: a helper
#                                renamed in lib/common.ps1 but still called from one script).
#   3. Stray path references   — a `docs/...` or `e2e/...` path literal in a script that does not
#                                exist in the repo, so CI can never reference a missing file.
#
# It runs on a plain pwsh (Windows or Linux) and never touches a device, which is what makes it
# usable as a CI gate.
[CmdletBinding()]
param(
    [string]$Root = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = (Resolve-Path $Root).Path

$files = @(Get-ChildItem -Path (Join-Path $Root 'e2e') -Recurse -File -Filter '*.ps1' |
    Where-Object { $_.FullName -notmatch '\\out\\' })
if ($files.Count -eq 0) { Write-Host 'no e2e scripts found' -ForegroundColor Yellow; exit 1 }

$errors = 0
$warnings = 0

function Write-LintError([string]$Where, [string]$Message) {
    Write-Host "  ERROR  $Where :: $Message" -ForegroundColor Red
    $script:errors++
}

function Write-LintWarn([string]$Where, [string]$Message) {
    Write-Host "  WARN   $Where :: $Message" -ForegroundColor Yellow
    $script:warnings++
}

# ------------------------------------------------------------------------------------------------
# 1. parse
# ------------------------------------------------------------------------------------------------
$parsed = @{}
foreach ($f in $files) {
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$tokens, [ref]$parseErrors)
    $rel = $f.FullName.Substring($Root.Length).TrimStart('\', '/')
    if ($parseErrors -and $parseErrors.Count -gt 0) {
        foreach ($e in $parseErrors) {
            Write-LintError $rel ("{0} (line {1})" -f $e.Message, $e.Extent.StartLineNumber)
        }
        continue
    }
    $parsed[$rel] = $ast
}
Write-Host ("parsed {0} script(s)" -f $parsed.Count)

# ------------------------------------------------------------------------------------------------
# 2. undefined commands (script-level functions + built-ins only)
# ------------------------------------------------------------------------------------------------
$defined = @{}
foreach ($kv in $parsed.GetEnumerator()) {
    foreach ($fn in $kv.Value.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
        $defined[$fn.Name.ToLowerInvariant()] = $kv.Key
    }
}

# Commands that legitimately come from outside the harness.
$allowed = @(
    # core cmdlets/modules the harness relies on
    'write-host', 'write-output', 'write-error', 'write-warning', 'write-verbose', 'write-step', 'write-ok',
    'write-warn2', 'write-err2',
    'get-date', 'get-content', 'set-content', 'add-content', 'get-childitem', 'get-item', 'test-path',
    'join-path', 'split-path', 'resolve-path', 'new-item', 'remove-item', 'copy-item', 'move-item',
    'select-object', 'sort-object', 'where-object', 'foreach-object', 'measure-object', 'group-object',
    'convertto-json', 'convertfrom-json', 'out-null', 'out-string', 'out-file', 'select-string',
    'start-sleep', 'start-process', 'stop-process', 'invoke-command', 'set-strictmode', 'get-command',
    'get-variable', 'set-variable', 'new-object', 'add-member', 'select-xml', 'format-table', 'format-list',
    'get-location', 'set-location', 'push-location', 'pop-location', 'test-connection', 'get-process',
    'get-filehash', 'get-itemproperty', 'select-object', 'measure-command',
    'tee-object', 'out-host', 'get-unique', 'compare-object', 'new-guid', 'get-random',
    'add-type', 'new-timespan', 'get-member', 'invoke-expression', 'exit', 'return', 'throw',
    # native tools
    'adb', 'python', 'python3', 'gradlew.bat', 'gradlew', 'git', 'sh'
)

foreach ($kv in $parsed.GetEnumerator()) {
    $rel = $kv.Key
    $ast = $kv.Value
    foreach ($cmd in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] }, $true)) {
        $name = $cmd.GetCommandName()
        if (-not $name) { continue }
        $lower = $name.ToLowerInvariant()
        if ($defined.ContainsKey($lower)) { continue }
        if ($allowed -contains $lower) { continue }
        # external executables: only flag things that look like a harness helper (verb-noun with a dash)
        if ($lower -match '^[a-z]+-[a-z0-9]+$') {
            Write-LintError $rel ("call to undefined helper '{0}' (line {1})" -f $name, $cmd.Extent.StartLineNumber)
        }
    }
}

# ------------------------------------------------------------------------------------------------
# 3. referenced repo paths that do not exist
# ------------------------------------------------------------------------------------------------
foreach ($kv in $parsed.GetEnumerator()) {
    $rel = $kv.Key
    $ast = $kv.Value
    foreach ($str in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst] }, $true)) {
        $v = $str.Value
        if ($v -notmatch '^(app|core|hook|verifier|e2e|docs|gradle)/[A-Za-z0-9_./-]+$') { continue }
        if ($v -match '\.(ps1|py|sh|xml|apk|json|md|kt|db)$') {
            $full = Join-Path $Root ($v -replace '/', '\')
            if (-not (Test-Path $full)) {
                Write-LintWarn $rel ("references a path that does not exist: {0} (line {1})" -f $v, $str.Extent.StartLineNumber)
            }
        }
    }
}

Write-Host ''
# Format-string guard: a placeholder string read with no argument is rendered raw to the user
# (StatusSheet once showed a raw '%1$.1f km/h' - docs/UIUX-AUDIT.md 34).
$stringOut = & (Join-Path $PSScriptRoot 'check_strings.ps1') 2>&1
if ($LASTEXITCODE -ne 0) { $stringOut | ForEach-Object { Write-Host $_ -ForegroundColor Red }; $errors++ }

if ($errors -gt 0) {    Write-Host ("lint FAILED: {0} error(s), {1} warning(s)" -f $errors, $warnings) -ForegroundColor Red
    exit 1
}
Write-Host ("lint OK: 0 errors, {0} warning(s)" -f $warnings) -ForegroundColor Green
exit 0

