# Removes only recycled files whose original location belongs to this project's test directory.
param([string]$TestDirectory = '')
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$testsRoot = Join-Path $projectRoot '.work\tests'
$requestedRoot = if ($TestDirectory) { [IO.Path]::GetFullPath($TestDirectory).TrimEnd('\') } else { $testsRoot }
if ($requestedRoot -ne $testsRoot -and -not $requestedRoot.StartsWith($testsRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Recycle cleanup must be limited to this project test directory.'
}
$userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$recycleRoot = Join-Path (Join-Path ([IO.Path]::GetPathRoot($projectRoot)) '$Recycle.Bin') $userSid
if (-not (Test-Path -LiteralPath $recycleRoot)) { return }
foreach ($directory in @((Split-Path -Parent $recycleRoot), $recycleRoot)) {
    if ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Recycle cleanup does not accept linked directories.'
    }
}

function Remove-OwnedRecycleItem([string]$Path, [string]$OwnedRoot) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute -ne $OwnedRoot -and -not $absolute.StartsWith($OwnedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Recycle item escaped its verified directory.'
    }
    $entry = Get-Item -LiteralPath $absolute -Force
    if ($entry.PSIsContainer -and -not ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        foreach ($child in @(Get-ChildItem -LiteralPath $absolute -Force)) {
            Remove-OwnedRecycleItem -Path $child.FullName -OwnedRoot $OwnedRoot
        }
    }
    Remove-Item -LiteralPath $absolute -Force
}

$shell = New-Object -ComObject Shell.Application
try {
    $recycle = $shell.Namespace(10)
    if ($null -eq $recycle) { throw 'The current user recycle bin is unavailable.' }
    $ownedItems = @($recycle.Items() | Where-Object {
        $originalDirectory = [string]$_.ExtendedProperty('System.Recycle.DeletedFrom')
        $originalDirectory.Equals($requestedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            $originalDirectory.StartsWith($requestedRoot + '\', [StringComparison]::OrdinalIgnoreCase)
    })
    foreach ($item in $ownedItems) {
        $absolute = [IO.Path]::GetFullPath([string]$item.Path)
        $name = [IO.Path]::GetFileName($absolute)
        if ((Split-Path -Parent $absolute) -ne $recycleRoot -or $name -notmatch '^\$R[A-Za-z0-9]+(?:\..+)?$') {
            throw 'Unexpected path for an owned recycle item.'
        }
        $metadata = Join-Path $recycleRoot ('$I' + $name.Substring(2))
        if (-not (Test-Path -LiteralPath $metadata -PathType Leaf)) {
            throw 'Recycle metadata is missing; ownership cannot be confirmed.'
        }
        $metadataText = [Text.Encoding]::Unicode.GetString([IO.File]::ReadAllBytes($metadata))
        if ($metadataText.IndexOf($requestedRoot + '\', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw 'Recycle metadata does not confirm the original test location.'
        }
        Remove-OwnedRecycleItem -Path $absolute -OwnedRoot $absolute
        Remove-OwnedRecycleItem -Path $metadata -OwnedRoot $metadata
    }
    Write-Output "Removed $($ownedItems.Count) project test items from the recycle bin."
} finally {
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell)
}
