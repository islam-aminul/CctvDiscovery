<#
.SYNOPSIS
  Refreshes the bundled IEEE MAC vendor database.

.DESCRIPTION
  Downloads the IEEE MA-L (oui.csv), MA-M (mam.csv) and MA-S (oui36.csv)
  registries and writes src/main/resources/oui/ieee-oui.tsv.gz with one
  "<hex prefix><TAB><organisation>" line per assignment. Run it from the
  repository root before a release, then rebuild.
#>
[CmdletBinding()]
param(
    [string]$Output
)
$ErrorActionPreference = 'Stop'
if (-not $Output) {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $Output = Join-Path $scriptDir '..\src\main\resources\oui\ieee-oui.tsv.gz'
}
$sources = @(
    'https://standards-oui.ieee.org/oui/oui.csv',
    'https://standards-oui.ieee.org/oui28/mam.csv',
    'https://standards-oui.ieee.org/oui36/oui36.csv'
)
$entries = [System.Collections.Generic.SortedDictionary[string,string]]::new([StringComparer]::Ordinal)
foreach ($url in $sources) {
    Write-Host "Downloading $url"
    $tmp = New-TemporaryFile
    try {
        # curl.exe ships with Windows 10+; the IEEE server rejects the
        # Windows PowerShell 5.1 web client.
        & curl.exe -fsSL --retry 5 --retry-all-errors --retry-delay 5 -A 'CctvDiscovery-OUI-Updater/2.0' -o $tmp $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url (curl exit $LASTEXITCODE)" }
        $rows = Import-Csv -Path $tmp -Encoding UTF8
        foreach ($row in $rows) {
            $prefix = ($row.Assignment -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
            $org = ($row.'Organization Name' -replace '\s+', ' ').Trim()
            if ($prefix.Length -ge 6 -and $org) { $entries[$prefix] = $org }
        }
        Write-Host ("  {0} rows" -f $rows.Count)
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}
if ($entries.Count -lt 40000) { throw "Only $($entries.Count) entries downloaded; refusing to overwrite." }
$fullPath = [System.IO.Path]::GetFullPath($Output)
$file = [System.IO.File]::Create($fullPath)
try {
    $gzip = [System.IO.Compression.GZipStream]::new($file, [System.IO.Compression.CompressionLevel]::Optimal)
    $writer = [System.IO.StreamWriter]::new($gzip, [System.Text.UTF8Encoding]::new($false))
    $writer.NewLine = "`n"
    $writer.WriteLine("# IEEE MA-L/MA-M/MA-S registry, generated $(Get-Date -Format 'yyyy-MM-dd')")
    foreach ($kv in $entries.GetEnumerator()) { $writer.WriteLine("$($kv.Key)`t$($kv.Value)") }
    $writer.Dispose()
} finally {
    $file.Dispose()
}
Write-Host "Wrote $($entries.Count) prefixes to $fullPath"
