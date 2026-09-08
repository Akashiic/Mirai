<#
.SYNOPSIS
Copies the Minecraft log and every Mirai .log rotation into a new local ZIP.
.DESCRIPTION
Read-only source access; nothing is uploaded. OutputDirectory defaults to the
current directory. The ZIP preserves logs/latest.log and logs/akashic-mirai/*.log.
manifest.json records copied SHA-256, byte counts and source metadata before/after.
Open logs are read with ReadWrite/Delete sharing, up to their length when opened.
This is NOT an atomic snapshot: a running game can append, truncate or rotate logs.
Changed files are flagged. Missing/blocked files or a changed rotation inventory
fail the collection; an incomplete ZIP is retained with complete=false when possible.
Complete means all initially present Mirai .log files plus logs/latest.log were
copied; it cannot recover rotations already removed. Source reparse points are
rejected, but these checks do not guarantee safety against concurrent path replacement.
.EXAMPLE
powershell -NoProfile -File .\scripts\collect-mirai-logs.ps1 -GameDirectory "C:\Games\MyModpack" -OutputDirectory "C:\Logs"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$GameDirectory,
    [string]$OutputDirectory = (Get-Location).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

function Get-PlainSourceItem([string]$Path, [bool]$Directory) {
    $info = Get-Item -LiteralPath $Path -Force
    if (($info.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Source reparse points (junctions/symlinks) are not supported: $Path"
    }
    if ([bool]$info.PSIsContainer -ne $Directory) { throw "Unexpected source path type: $Path" }
    return $info
}

function Get-SourceMetadata([string]$Path) {
    $info = Get-PlainSourceItem $Path $false
    return [ordered]@{ size_bytes = $info.Length; last_write_time_utc = $info.LastWriteTimeUtc.ToString('o') }
}

function Get-MiraiFiles([string]$Directory) {
    [void](Get-PlainSourceItem $Directory $true)
    $files = @(Get-ChildItem -LiteralPath $Directory -File -Filter '*.log' -Force | Sort-Object Name)
    foreach ($file in $files) { [void](Get-PlainSourceItem $file.FullName $false) }
    return $files
}

$game = Get-PlainSourceItem $GameDirectory $true
$logsDirectory = Join-Path $game.FullName 'logs'
[void](Get-PlainSourceItem $logsDirectory $true)
$mainLog = Join-Path $game.FullName 'logs/latest.log'
$miraiDirectory = Join-Path $game.FullName 'logs/akashic-mirai'
if (-not [System.IO.File]::Exists($mainLog)) { throw 'Missing required source: logs/latest.log' }
[void](Get-PlainSourceItem $mainLog $false)
if (-not [System.IO.Directory]::Exists($miraiDirectory)) { throw 'Missing required source directory: logs/akashic-mirai' }
$rotations = @(Get-MiraiFiles $miraiDirectory)
if ($rotations.Count -eq 0) { throw 'No .log files found in logs/akashic-mirai; collection is incomplete.' }
$sources = @([pscustomobject]@{ Path = $mainLog; Entry = 'logs/latest.log' })
$sources += @($rotations | ForEach-Object { [pscustomobject]@{ Path = $_.FullName; Entry = 'logs/akashic-mirai/' + $_.Name } })
$initialNames = @($rotations | ForEach-Object { $_.Name })

function Assert-SourceDirectories {
    [void](Get-PlainSourceItem $game.FullName $true)
    [void](Get-PlainSourceItem $logsDirectory $true)
    [void](Get-PlainSourceItem $miraiDirectory $true)
}

$output = [System.IO.Path]::GetFullPath($OutputDirectory)
[void][System.IO.Directory]::CreateDirectory($output)
$name = 'Akashic-Mirai-logs-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N') + '.zip'
$archivePath = Join-Path $output $name
$manifest = [ordered]@{
    schema = 1
    created_utc = [DateTime]::UtcNow.ToString('o')
    complete = $false
    completeness_scope = 'All initially present Mirai .log files plus logs/latest.log; older missing rotations cannot be recovered.'
    atomic_snapshot = $false
    copy_policy = 'Each file is read up to its handle length at open; concurrent changes may be detected, never ruled out.'
    source_set_changed = $false
    files = @()
    errors = @()
}
$archiveStream = $null
$archive = $null
try {
    $archiveStream = [System.IO.File]::Open($archivePath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    $archive = New-Object System.IO.Compression.ZipArchive($archiveStream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    $buffer = New-Object byte[] 1048576
    foreach ($source in $sources) {
        $item = [ordered]@{ path = $source.Entry; copied = $false; source_before = $null; source_after = $null; handle_length_at_open = $null; copied_bytes = 0L; sha256 = $null; altered_during_copy = $false; error = $null }
        $inputStream = $null; $entryStream = $null; $hash = $null
        try {
            Assert-SourceDirectories
            $item.source_before = Get-SourceMetadata $source.Path
            $sharing = [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
            $inputStream = [System.IO.File]::Open($source.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, $sharing)
            $item.handle_length_at_open = $inputStream.Length
            $entry = $archive.CreateEntry($source.Entry, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            $hash = [System.Security.Cryptography.SHA256]::Create()
            $remaining = $item.handle_length_at_open
            while ($remaining -gt 0) {
                $count = $inputStream.Read($buffer, 0, [int][Math]::Min($buffer.Length, $remaining))
                if ($count -eq 0) { throw 'Source became shorter while copying.' }
                $entryStream.Write($buffer, 0, $count)
                [void]$hash.TransformBlock($buffer, 0, $count, $buffer, 0)
                $item.copied_bytes += $count
                $remaining -= $count
            }
            [void]$hash.TransformFinalBlock((New-Object byte[] 0), 0, 0)
            $item.sha256 = [BitConverter]::ToString($hash.Hash).Replace('-', '').ToLowerInvariant()
            $entryStream.Dispose(); $entryStream = $null
            Assert-SourceDirectories
            $item.source_after = Get-SourceMetadata $source.Path
            $item.altered_during_copy = ($item.source_before.size_bytes -ne $item.source_after.size_bytes) -or
                ($item.source_before.last_write_time_utc -ne $item.source_after.last_write_time_utc) -or
                ($item.source_before.size_bytes -ne $item.handle_length_at_open)
            $item.copied = $true
        }
        catch {
            $item.error = $_.Exception.Message
            $item.altered_during_copy = $true
            $manifest.errors += $source.Entry + ': ' + $_.Exception.Message
        }
        finally {
            if ($entryStream) { $entryStream.Dispose() }
            if ($inputStream) { $inputStream.Dispose() }
            if ($hash) { $hash.Dispose() }
        }
        $manifest.files += $item
    }
    try {
        Assert-SourceDirectories
        $finalNames = @(Get-MiraiFiles $miraiDirectory | ForEach-Object { $_.Name })
        $manifest.source_set_changed = [bool](@(Compare-Object $initialNames $finalNames).Count)
        if ($manifest.source_set_changed) { $manifest.errors += 'Mirai rotation filenames changed during collection. Run the collector again.' }
        if (-not [System.IO.File]::Exists($mainLog)) { $manifest.errors += 'logs/latest.log disappeared during collection.' }
    }
    catch { $manifest.errors += 'Unable to verify the final source inventory: ' + $_.Exception.Message }
    $manifest.complete = ($manifest.errors.Count -eq 0)
    $manifest.finished_utc = [DateTime]::UtcNow.ToString('o')
    $entry = $archive.CreateEntry('manifest.json')
    $entryStream = $entry.Open()
    try {
        $json = $manifest | ConvertTo-Json -Depth 8
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $entryStream.Write($bytes, 0, $bytes.Length)
    }
    finally { $entryStream.Dispose() }
}
catch { throw "Collection failed; do not use the partial archive '$archivePath' as a complete collection. $($_.Exception.Message)" }
finally {
    if ($archive) { $archive.Dispose() }
    if ($archiveStream) { $archiveStream.Dispose() }
}

if (-not $manifest.complete) { throw "Collection incomplete. Partial archive: $archivePath. $($manifest.errors -join ' | ')" }
if (@($manifest.files | Where-Object { $_.altered_during_copy }).Count -gt 0) {
    Write-Warning 'Source files changed during copying. Check altered_during_copy in manifest.json; this is not an atomic snapshot.'
}
Write-Output $archivePath
