# Removes approved project build artifacts and caches after validating every target and descendant.
param([switch]$IncludeDependencyCache)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$manifestFile = Join-Path $projectRoot 'docs\validation\closeout-cleanup-manifest.json'
$manifest = Get-Content -LiteralPath $manifestFile -Encoding UTF8 -Raw | ConvertFrom-Json
if ([IO.Path]::GetFullPath($manifest.root) -ne $projectRoot) { throw 'The cleanup manifest belongs to another workspace.' }
$protected = @('MoliFox', '.git', '.tooling', '.work\ui-detail', 'docs', 'gradle', 'tools', 'MoliFox.exe', 'MoliFox-linux-x64.run')
$targets = @($manifest.targets | Where-Object { $_.group -eq 'A' -or ($IncludeDependencyCache -and $_.group -eq 'B') })
foreach ($name in @('.work\RunFailureRegression.ps1', '.work\RemoveConfirmedCode.py')) {
    $targets += [pscustomobject]@{path=(Join-Path $projectRoot $name); group='A'; reason='Temporary script created by this closeout run.'}
}

function Assert-OwnedPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if (-not $absolute.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Outside workspace: $absolute" }
    foreach ($name in $protected) {
        $guard = Join-Path $projectRoot $name
        if ($absolute -eq $guard -or $absolute.StartsWith($guard + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "Protected path: $absolute" }
    }
    $parent = $absolute
    while ($parent -and $parent -ne $projectRoot) {
        if (Test-Path -LiteralPath $parent) {
            $attributes = [IO.File]::GetAttributes($parent)
            if ($attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked cleanup path: $parent" }
        }
        $parent = [IO.Path]::GetDirectoryName($parent)
    }
    return $absolute
}

function Assert-OrdinaryTree([string]$Path) {
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($Path)
    while ($pending.Count) {
        $current = $pending.Pop()
        if (-not (Test-Path -LiteralPath $current)) { continue }
        $attributes = [IO.File]::GetAttributes($current)
        if ($attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked descendant: $current" }
        if ($attributes -band [IO.FileAttributes]::Directory) {
            foreach ($child in [IO.Directory]::EnumerateFileSystemEntries($current)) { $pending.Push($child) }
        }
    }
}

# Validate the complete set before deleting the first target.
foreach ($target in $targets) {
    $absolute = Assert-OwnedPath $target.path
    Assert-OrdinaryTree $absolute
}
$results = @()
foreach ($target in $targets) {
    $absolute = Assert-OwnedPath $target.path
    $status = 'already-absent'
    $failure = $null
    if (Test-Path -LiteralPath $absolute) {
        Assert-OrdinaryTree $absolute
        for ($attempt = 0; $attempt -lt 3; $attempt++) {
            try {
                Remove-Item -LiteralPath $absolute -Recurse -Force
                $status = 'removed'
                $failure = $null
                break
            } catch {
                $failure = $_.Exception.Message
                $status = 'failed'
                if ($attempt -lt 2) { Start-Sleep -Milliseconds (200 * ($attempt + 1)) }
            }
        }
    }
    $results += [pscustomobject]@{path=$absolute; status=$status; error=$failure}
}
$cacheRoot = Join-Path $projectRoot '.gradle-home'
if ($IncludeDependencyCache -and (Test-Path -LiteralPath $cacheRoot)) {
    $null = Assert-OwnedPath $cacheRoot
    if ([IO.Directory]::GetFileSystemEntries($cacheRoot).Length -eq 0) { Remove-Item -LiteralPath $cacheRoot -Force }
}
$output = Join-Path $projectRoot 'docs\validation\closeout-cleanup-result.json'
$previous = $null
if (Test-Path -LiteralPath $output) {
    $last = Get-Content -LiteralPath $output -Encoding UTF8 -Raw | ConvertFrom-Json
    $previous = [ordered]@{updated=$last.updated; status=$last.status; removed=@($last.targets | Where-Object {$_.status -eq 'removed'}).Count; failed=@($last.targets | Where-Object {$_.status -eq 'failed'}).Count}
}
$report = [ordered]@{
    updated=[DateTime]::UtcNow.ToString('o')
    dependencyCacheIncluded=[bool]$IncludeDependencyCache
    status=$(if (@($results | Where-Object {$_.status -eq 'failed'}).Count) {'partial'} else {'completed'})
    targets=$results
    previousRun=$previous
}
[IO.File]::WriteAllText($output, ($report | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output ("Cleanup {0}: {1} removed, {2} absent, {3} failed." -f $report.status, @($results | Where-Object {$_.status -eq 'removed'}).Count, @($results | Where-Object {$_.status -eq 'already-absent'}).Count, @($results | Where-Object {$_.status -eq 'failed'}).Count)
if ($report.status -ne 'completed') { exit 1 }
