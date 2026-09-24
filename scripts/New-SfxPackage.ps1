<#
.SYNOPSIS
    Builds a single-file, self-extracting Windows archive that unpacks the
    application together with its bundled Java runtime and starts it.

.DESCRIPTION
    The archive is built with IExpress, which ships with Windows itself, so the
    build needs no third-party packer and works offline. IExpress produces an
    ordinary PE executable, which means the result can be Authenticode-signed
    with the same certificate as the launcher.

    IExpress flattens whatever it is given into one temporary folder, so it
    cannot carry the runtime's directory tree directly. The payload is
    therefore a single zip, and a small bootstrap script unpacks it to a
    permanent folder and launches the application. Because the zip is already
    compressed, the cabinet around it is stored rather than compressed: that
    saves roughly a minute of build time and a second run of the same data at
    extraction time, for a few hundred kilobytes of size.

    Two details of IExpress that are easy to get wrong and are handled here:

      * The 32-bit IExpress under SysWOW64 is used deliberately. IExpress
        stamps out a copy of its own wextract stub, so building with the
        System32 copy on an ARM64 machine yields an ARM64 archive that will not
        open on the x64 machines this payload targets. The 32-bit stub runs
        everywhere.

      * The bootstrap is invoked as ".\sfx-bootstrap.cmd". A machine with
        NoDefaultCurrentDirectoryInExePath set does not search the working
        directory, and the bare name fails there with "not recognized" while
        the archive still reports success.

.PARAMETER DistDir
    The staged distribution: launcher, jar, bundled runtime.

.PARAMETER OutFile
    The self-extracting archive to write.

.PARAMETER WorkDir
    Scratch directory for the payload zip and the IExpress directive file.

.PARAMETER BootstrapTemplate
    The bootstrap script, with @NAME@ tokens still in place.

.PARAMETER PayloadPlatform
    The javacpp platform the jar's native libraries were built for, for example
    windows-x86_64. The bundled runtime is checked against it, because a
    runtime of the wrong architecture produces an archive that installs
    perfectly and then fails to start.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $DistDir,
    [Parameter(Mandatory = $true)][string] $OutFile,
    [Parameter(Mandatory = $true)][string] $WorkDir,
    [Parameter(Mandatory = $true)][string] $BootstrapTemplate,
    [string] $AppName = 'CctvDiscovery',
    [string] $AppVersion = '0.0.0',
    [string] $AppExe = 'CctvDiscovery.exe',
    [string] $FriendlyName = 'CCTV Discovery',
    [string] $PayloadPlatform = 'windows-x86_64',
    [string] $SignKeystore,
    [string] $SignPassword,
    [string] $SkipSigning = 'true'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- helpers ---

function Get-PeMachine {
    param([string] $File)
    $stream = [System.IO.File]::OpenRead($File)
    try {
        $header = New-Object byte[] 0x40
        if ($stream.Read($header, 0, $header.Length) -lt $header.Length) { return 'unknown' }
        $peOffset = [BitConverter]::ToInt32($header, 0x3C)
        $stream.Position = $peOffset + 4
        $machineBytes = New-Object byte[] 2
        if ($stream.Read($machineBytes, 0, 2) -lt 2) { return 'unknown' }
        switch ([BitConverter]::ToUInt16($machineBytes, 0)) {
            0x014C  { 'x86' }
            0x8664  { 'x86-64' }
            0xAA64  { 'ARM64' }
            0x01C4  { 'ARM' }
            default { 'unknown' }
        }
    } finally {
        $stream.Dispose()
    }
}

function Assert-NoSedMetacharacters {
    param([string] $Value, [string] $What)
    # A per cent sign opens a substitution in an IExpress directive file and a
    # quote ends a value, so either one would corrupt the package silently.
    if ($Value -match '[%"]') {
        throw "$What contains a character IExpress cannot carry (a per cent sign or a quote): $Value"
    }
}

function Format-Size {
    param([long] $Bytes)
    '{0:N1} MB' -f ($Bytes / 1MB)
}

# ------------------------------------------------------------ validation ---

$DistDir = (Resolve-Path -LiteralPath $DistDir).Path
$BootstrapTemplate = (Resolve-Path -LiteralPath $BootstrapTemplate).Path

$launcher = Join-Path $DistDir $AppExe
$javaExe = Join-Path $DistDir 'runtime\bin\java.exe'
foreach ($required in @($launcher, $javaExe)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "The distribution is incomplete: $required is missing. Run the package phase first."
    }
}

$expectedMachine = switch -Wildcard ($PayloadPlatform) {
    'windows-x86_64' { 'x86-64' }
    'windows-x86'    { 'x86' }
    'windows-arm64'  { 'ARM64' }
    default          { $null }
}
$runtimeMachine = Get-PeMachine $javaExe
if ($expectedMachine -and $runtimeMachine -ne $expectedMachine) {
    throw @"
The bundled runtime does not match the native libraries in the jar.

  runtime\bin\java.exe is $runtimeMachine
  the jar carries $PayloadPlatform natives, which need $expectedMachine

jlink copies the architecture of whichever JDK builds it, so this happens when
the build JDK is not the one the archive targets. Point the runtime at a
matching JDK, for example:

  mvn package "-Druntime.jdk=C:\path\to\jdk-25-$expectedMachine"
"@
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$WorkDir = (Resolve-Path -LiteralPath $WorkDir).Path
$outDirectory = Split-Path -Parent $OutFile
New-Item -ItemType Directory -Force -Path $outDirectory | Out-Null
$OutFile = Join-Path (Resolve-Path -LiteralPath $outDirectory).Path (Split-Path -Leaf $OutFile)

$stagingDir = Join-Path $WorkDir 'payload'
$payloadZip = Join-Path $stagingDir 'payload.zip'
$bootstrap = Join-Path $stagingDir 'sfx-bootstrap.cmd'
$directives = Join-Path $WorkDir 'sfx.sed'

foreach ($path in @($DistDir, $WorkDir, $OutFile)) {
    Assert-NoSedMetacharacters -Value $path -What 'A build path'
}
Assert-NoSedMetacharacters -Value $FriendlyName -What 'The friendly name'

# -------------------------------------------------------------- payload ----

if (Test-Path -LiteralPath $stagingDir) { Remove-Item -LiteralPath $stagingDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null

Write-Host "Compressing $DistDir"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$timer = [System.Diagnostics.Stopwatch]::StartNew()
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $DistDir, $payloadZip, [System.IO.Compression.CompressionLevel]::Optimal, $false)
$timer.Stop()
Write-Host ("  payload.zip is {0} ({1:N0}s)" -f (Format-Size (Get-Item $payloadZip).Length), $timer.Elapsed.TotalSeconds)

$script = (Get-Content -LiteralPath $BootstrapTemplate -Raw).
    Replace('@APP_NAME@', $AppName).
    Replace('@APP_VERSION@', $AppVersion).
    Replace('@APP_EXE@', $AppExe)
if ($script -match '@[A-Z_]+@') {
    throw "The bootstrap template still has an unfilled token: $($Matches[0])"
}
# cmd.exe reads batch files as bytes; ASCII with CRLF is what it expects.
[System.IO.File]::WriteAllText($bootstrap, ($script -replace "`r?`n", "`r`n"), [System.Text.Encoding]::ASCII)

# ------------------------------------------------------------ directives ---

$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=0
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
CompressionType=NONE
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$OutFile
FriendlyName=$FriendlyName $AppVersion
AppLaunched=cmd /c .\sfx-bootstrap.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="sfx-bootstrap.cmd"
FILE1="payload.zip"
[SourceFiles]
SourceFiles0=$stagingDir
[SourceFiles0]
%FILE0%=
%FILE1%=
"@
[System.IO.File]::WriteAllText($directives, ($sed -replace "`r?`n", "`r`n"), [System.Text.Encoding]::ASCII)

# --------------------------------------------------------------- package ---

# SysWOW64 holds the 32-bit build on both x64 and ARM64 Windows; see the note
# at the top about the stub's architecture.
$iexpress = @(
    (Join-Path $env:SystemRoot 'SysWOW64\iexpress.exe'),
    (Join-Path $env:SystemRoot 'System32\iexpress.exe')
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $iexpress) {
    throw 'iexpress.exe was not found. It ships with Windows; on a trimmed installation it may have been removed.'
}
if ($iexpress -notlike '*SysWOW64*') {
    Write-Warning 'Falling back to the 64-bit IExpress: the archive will only open on this machine''s architecture.'
}

if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }

Write-Host "Packaging with $iexpress"
$timer = [System.Diagnostics.Stopwatch]::StartNew()
$process = Start-Process -FilePath $iexpress -ArgumentList '/N', '/Q', $directives -PassThru -Wait
$timer.Stop()
if ($process.ExitCode -ne 0) {
    throw "IExpress failed with exit code $($process.ExitCode). The directive file is at $directives."
}
if (-not (Test-Path -LiteralPath $OutFile)) {
    throw "IExpress reported success but produced no file at $OutFile."
}

# Signing comes last: Authenticode covers the whole file, cabinet included, so
# the archive has to be finished before the certificate goes on. The launcher
# inside the payload was already signed before it was zipped.
& (Join-Path $PSScriptRoot 'Invoke-CodeSign.ps1') `
    -Path $OutFile -Keystore $SignKeystore -Password $SignPassword -Skip $SkipSigning

$archive = Get-Item -LiteralPath $OutFile
Write-Host ""
Write-Host "Self-extracting archive: $($archive.FullName)"
Write-Host ("  size    $(Format-Size $archive.Length) ({0:N0}s to build)" -f $timer.Elapsed.TotalSeconds)
Write-Host "  stub    $(Get-PeMachine $archive.FullName)"
Write-Host "  payload $PayloadPlatform, runtime $runtimeMachine"
Write-Host "  unpacks to %LOCALAPPDATA%\Programs\$AppName\$AppVersion and starts $AppExe"
Write-Host "  /T:<dir> /C unpacks without starting anything"
