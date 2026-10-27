param([string]$Distribution = 'archlinux')
$ErrorActionPreference = 'Stop'

# The existing WSL distribution is read-only here. Libraries are unpacked into the project.
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$libraryRoot = Join-Path $projectRoot '.tooling\linux-desktop'
$temporary = Join-Path $projectRoot '.work\build-tmp\linux-desktop-setup'
foreach ($directory in @((Join-Path $projectRoot '.tooling'), (Join-Path $projectRoot '.work'), (Join-Path $projectRoot '.work\build-tmp'), $libraryRoot, $temporary)) {
    if ((Test-Path -LiteralPath $directory) -and ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Linked dependency directory: $directory" }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

function Convert-ToLinuxPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute -notmatch '^([A-Za-z]):\\') { throw 'A local Windows drive is required' }
    '/mnt/' + $Matches[1].ToLowerInvariant() + $absolute.Substring(2).Replace('\', '/')
}

function Invoke-Linux([string[]]$Command) {
    $errorFile = Join-Path $temporary 'wsl-stderr.txt'
    $savedPreference = $ErrorActionPreference
    try {
        # WSL can emit an advisory on stderr even when the command succeeds.
        $ErrorActionPreference = 'Continue'
        $output = @(& wsl.exe -d $Distribution --exec @Command 2> $errorFile)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($exitCode -ne 0) {
        $detail = Get-Content -LiteralPath $errorFile -Raw -ErrorAction SilentlyContinue
        throw "Linux dependency command failed: $($Command[0]) ($exitCode): $detail"
    }
    $output
}

$manifestFile = Join-Path $libraryRoot 'packages.json'
$records = @{}
if (Test-Path -LiteralPath $manifestFile) {
    $previousRecords = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($record in $previousRecords) { $records[[string]$record.name] = $record }
}
try {
    $targets = @('libx11','libxrender','libxtst','libxi','libxext','libglvnd','mesa','alsa-lib','fontconfig','ttf-dejavu','xdotool')
    $lines = @(Invoke-Linux (@('/usr/sbin/pacman','-Sp','--needed','--print-format','%r,%n,%v,%l') + $targets))
    foreach ($line in $lines) {
        $fields = ([string]$line).Split(',', 4)
        if ($fields.Count -ne 4) { continue }
        $repository, $name, $version, $url = $fields
        if ($repository -notin @('core','extra') -or $name -notmatch '^[a-z0-9+_.-]+$') { throw 'Unexpected repository metadata' }
        if ($name -in @('glibc','filesystem','pacman')) { throw "Linux base system needs updating before desktop tests: $name. The system was not changed." }
        if ($records.ContainsKey($name) -and $records[$name].version -eq $version) { continue }
        if ($version -notmatch '^[a-zA-Z0-9+:_.-]+$') { throw 'Unexpected package version' }
        $description = (Invoke-Linux @('/usr/sbin/bsdtar','-xOf',"/var/lib/pacman/sync/$repository.db","$name-$version/desc")) -join "`n"
        $checksum = [regex]::Match($description, '(?m)^%SHA256SUM%\r?\n([a-fA-F0-9]{64})').Groups[1].Value
        $filename = [regex]::Match($description, '(?m)^%FILENAME%\r?\n([^\r\n]+)').Groups[1].Value
        if (-not $checksum -or $filename -notmatch '^[a-zA-Z0-9+:_.-]+\.pkg\.tar\.(zst|xz)$') { throw "Incomplete checksum metadata: $name" }
        $archive = Join-Path $temporary "$name.pkg.tar.zst"
        $escapedFilename = [Uri]::EscapeDataString($filename)
        Write-Output "Preparing Linux library: $name $version"
        $downloadUrl = "https://geo.mirror.pkgbuild.com/$repository/os/x86_64/$escapedFilename"
        try {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $archive -UseBasicParsing -TimeoutSec 180
        } catch {
            $downloadUrl = "https://archive.archlinux.org/packages/$($name.Substring(0,1))/$name/$escapedFilename"
            Invoke-WebRequest -Uri $downloadUrl -OutFile $archive -UseBasicParsing -TimeoutSec 180
        }
        if (-not (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.Equals($checksum, [StringComparison]::OrdinalIgnoreCase)) { throw "Linux library checksum failed: $name" }
        Invoke-Linux @('/usr/sbin/bsdtar','--no-same-owner','--no-same-permissions','-xf',(Convert-ToLinuxPath $archive),'-C',(Convert-ToLinuxPath $libraryRoot)) | Out-Null
        Remove-Item -LiteralPath $archive -Force
        $records[$name] = [pscustomobject]@{name=$name;version=$version;sha256=$checksum;source=$downloadUrl}
        $pending = "$manifestFile.pending"
        [IO.File]::WriteAllText($pending, (ConvertTo-Json -InputObject @($records.Values | Sort-Object name) -Depth 4), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $pending -Destination $manifestFile -Force
    }
    foreach ($metadata in @('.PKGINFO','.BUILDINFO','.MTREE','.INSTALL')) {
        $file = Join-Path $libraryRoot $metadata
        if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
    }
    Write-Output "Linux desktop dependencies ready: $($records.Count) packages inside .tooling/linux-desktop"
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    if (-not $resolved.StartsWith($projectRoot + '\.work\build-tmp\', [StringComparison]::OrdinalIgnoreCase) -or ((Get-Item -LiteralPath $temporary -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe setup cleanup path' }
    Remove-Item -LiteralPath $temporary -Recurse -Force
}
