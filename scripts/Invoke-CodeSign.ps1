<#
.SYNOPSIS
    Authenticode-signs a file with the project's certificate.

.DESCRIPTION
    Both the launcher and the self-extracting archive are signed through this
    one script, so they always carry the same signature, timestamp authority
    and digest algorithm.

    signtool.exe is not on PATH outside a Developer Command Prompt, so the
    newest copy under the Windows SDK is located instead of assuming one.

.PARAMETER Path
    The file to sign.

.PARAMETER Keystore
    A PFX or P12 holding the code-signing certificate.

.PARAMETER Password
    The password for that keystore.

.PARAMETER TimestampUrl
    RFC 3161 timestamp authority. A timestamp keeps the signature valid after
    the certificate expires, so it is worth the dependency on a network call.

.PARAMETER Skip
    Pass 'true' to do nothing. The build sets this when no keystore was given,
    which is the normal case for a local build.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $Path,
    [string] $Keystore,
    [string] $Password,
    [string] $TimestampUrl = 'http://timestamp.digicert.com',
    [string] $Skip = 'false'
)

$ErrorActionPreference = 'Stop'

if ($Skip -eq 'true') {
    Write-Host "Signing skipped for $(Split-Path -Leaf $Path) (no keystore configured)."
    exit 0
}

if (-not (Test-Path -LiteralPath $Path)) {
    throw "Nothing to sign: $Path does not exist."
}
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    throw 'Signing was requested but no keystore was supplied (-Dsign.keystore=...).'
}
if (-not (Test-Path -LiteralPath $Keystore)) {
    throw "The signing keystore does not exist: $Keystore"
}

function Find-SignTool {
    $onPath = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    # Prefer the newest SDK, and within it a build of this machine's own
    # architecture so it runs without emulation.
    $arch = switch ($env:PROCESSOR_ARCHITECTURE) {
        'AMD64' { 'x64' }
        'ARM64' { 'arm64' }
        default { 'x86' }
    }
    foreach ($root in @("${env:ProgramFiles(x86)}\Windows Kits\10\bin", "$env:ProgramFiles\Windows Kits\10\bin")) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        $found = Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "$arch\signtool.exe" } |
            Where-Object { Test-Path -LiteralPath $_ } |
            Select-Object -First 1
        if ($found) { return $found }
    }
    throw 'signtool.exe was not found on PATH or under the Windows 10/11 SDK. Install the SDK signing tools, or put signtool on PATH.'
}

$signtool = Find-SignTool
Write-Host "Signing $Path"
Write-Host "  with $signtool"

$arguments = [System.Collections.Generic.List[string]]::new()
$arguments.AddRange([string[]] @('sign', '/fd', 'SHA256', '/f', $Keystore))
if (-not [string]::IsNullOrEmpty($Password)) {
    $arguments.AddRange([string[]] @('/p', $Password))
}
$arguments.AddRange([string[]] @('/tr', $TimestampUrl, '/td', 'SHA256', $Path))

& $signtool @arguments
if ($LASTEXITCODE -ne 0) {
    throw "signtool failed with exit code $LASTEXITCODE."
}

Write-Host "Signed $(Split-Path -Leaf $Path)."
