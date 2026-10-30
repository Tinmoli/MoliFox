param(
    [Parameter(Mandatory=$true)][string]$Gradle,
    [Parameter(Mandatory=$true)][string]$JvmHome,
    [string]$Distribution = 'archlinux',
    [ValidateSet('Vanilla','Fabric')][string]$Loader = 'Fabric',
    [string]$GameVersion = '1.20.1',
    [string]$LoaderVersion = '0.16.14',
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$RunName = 'linux-gameSmoke',
    [string]$PreparedFrom = '',
    [switch]$OnlySelectedLoader
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$testRoot = Join-Path $projectRoot ".work\tests\$RunName"
$libraryRoot = Join-Path $projectRoot '.tooling\linux-desktop'
$classpathFile = Join-Path $projectRoot '.work\build-tmp\game-smoke-classpath.txt'
foreach ($directory in @((Join-Path $projectRoot '.work'), (Join-Path $projectRoot '.work\tests'), $testRoot)) {
    if ((Test-Path -LiteralPath $directory) -and ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Linked test directory: $directory" }
}
if (Test-Path -LiteralPath $testRoot) { throw 'A previous Linux check still exists; inspect it before starting another.' }
if (-not (Test-Path -LiteralPath (Join-Path $libraryRoot 'packages.json'))) { throw 'Prepare project-local Linux libraries first.' }
New-Item -ItemType Directory -Path (Join-Path $testRoot 'tmp') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $testRoot 'home') -Force | Out-Null

function Convert-ToLinuxPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute -notmatch '^([A-Za-z]):\\') { throw 'A local Windows drive is required.' }
    '/mnt/' + $Matches[1].ToLowerInvariant() + $absolute.Substring(2).Replace('\','/')
}
function Invoke-Linux([string[]]$Command) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& wsl.exe -d $Distribution --exec @Command 2> (Join-Path $testRoot 'wsl-stderr.txt'))
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($exitCode -ne 0) {
        $detail = ($output | Select-Object -Last 20) -join "`n"
        throw "Linux command failed: $($Command[0]) ($exitCode): $detail"
    }
    $output
}
$result = 'failed'
try {
    if ($PreparedFrom) {
        $previous = [IO.Path]::GetFullPath($PreparedFrom)
        if (-not $previous.StartsWith($projectRoot + '\.work\tests\', [StringComparison]::OrdinalIgnoreCase) -or $previous -eq $testRoot -or ((Get-Item -LiteralPath $previous -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Only an owned previous test can seed this check.' }
        New-Item -ItemType Directory -Path (Join-Path $testRoot '.minecraft') -Force | Out-Null
        foreach ($folder in @('assets','libraries','versions')) { Copy-Item -LiteralPath (Join-Path $previous ".minecraft\$folder") -Destination (Join-Path $testRoot '.minecraft') -Recurse }
        Copy-Item -LiteralPath (Join-Path $previous 'runtime') -Destination $testRoot -Recurse
        $instance = Get-Content -LiteralPath (Join-Path $previous 'prepared-instance.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $instance.id = [guid]::NewGuid().ToString()
        $instance.loader = $Loader
        $instance.loaderVersion = if ($Loader -eq 'Vanilla') { '' } else { $LoaderVersion }
        $instance.versionId = if ($Loader -eq 'Vanilla') { $GameVersion } else { "molifox-fabric-$GameVersion-$LoaderVersion" }
        if (-not (Test-Path -LiteralPath (Join-Path $testRoot ".minecraft\versions\$($instance.versionId)\$($instance.versionId).json"))) { throw 'The copied game profile is missing.' }
        [IO.File]::WriteAllText((Join-Path $testRoot 'prepared-instance.json'), ($instance | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
        $deadline = [DateTime]::UtcNow.AddMinutes(3)
        while (Test-Path -LiteralPath $previous) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Previous GUI check did not finish; a second window was not started.' }
            Start-Sleep -Seconds 1
        }
    }
    $env:JAVA_HOME = $JvmHome
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
    $env:TEMP = Join-Path $projectRoot '.work\build-tmp'
    $env:TMP = $env:TEMP
    $env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$($env:TEMP)"
    & $Gradle :launcher-core:writeGameSmokeClasspath --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Linux harness compilation failed.' }
    $classpath = [IO.File]::ReadAllText($classpathFile).Trim()
    if (-not $PreparedFrom) {
        & (Join-Path $JvmHome 'bin\java.exe') '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Djava.net.useSystemProxies=true' "-Dmolifox.test.dir=$testRoot" "-Dmolifox.game.version=$GameVersion" "-Dmolifox.game.loader=$Loader" "-Dmolifox.game.loaderVersion=$LoaderVersion" -cp $classpath dev.molifox.core.PrepareLinuxGameSmokeKt
        if ($LASTEXITCODE -ne 0) { throw 'Linux artifact preparation failed.' }
    }
    $java = Get-ChildItem -LiteralPath (Join-Path $testRoot 'runtime') -Filter java -Recurse -File | Where-Object { $_.Directory.Name -eq 'bin' } | Select-Object -First 1
    if (-not $java) { throw 'No prepared Linux Java executable.' }
    $linuxJava = Convert-ToLinuxPath $java.FullName
    Invoke-Linux @('/usr/bin/chmod','u+x',$linuxJava) | Out-Null
    $linuxRoot = Convert-ToLinuxPath $testRoot
    $linuxLibraries = Convert-ToLinuxPath $libraryRoot
    $linuxClasspath = (($classpath -split ';' | ForEach-Object { Convert-ToLinuxPath $_ }) -join ':')
    $fontConfig = "<fontconfig><dir>$linuxLibraries/usr/share/fonts</dir><cachedir>$linuxRoot/font-cache</cachedir></fontconfig>"
    [IO.File]::WriteAllText((Join-Path $testRoot 'fonts.conf'), $fontConfig, [Text.UTF8Encoding]::new($false))
    $linuxReport = Convert-ToLinuxPath (Join-Path $projectRoot "build\validation\real-game-linux-$($Loader.ToLowerInvariant()).json")
    # Only this process gets project-local libraries, fonts, caches and software rendering.
    $environment = @("PATH=$linuxLibraries/usr/bin:/usr/sbin:/usr/bin:/sbin:/bin", "LD_LIBRARY_PATH=$linuxLibraries/usr/lib:/usr/lib/wsl/lib", "LIBGL_DRIVERS_PATH=$linuxLibraries/usr/lib/dri", 'LIBGL_ALWAYS_SOFTWARE=true', 'GALLIUM_DRIVER=llvmpipe', "HOME=$linuxRoot/home", "XDG_CACHE_HOME=$linuxRoot/home/.cache", "TMPDIR=$linuxRoot/tmp", "FONTCONFIG_FILE=$linuxRoot/fonts.conf", "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$linuxRoot/tmp")
    $properties = @("-Duser.home=$linuxRoot/home", "-Djava.io.tmpdir=$linuxRoot/tmp", "-Djna.tmpdir=$linuxRoot/tmp", "-Dmolifox.test.dir=$linuxRoot", '-Dmolifox.game.prepared=true', "-Dmolifox.game.javaPath=$linuxJava", "-Dmolifox.game.version=$GameVersion", "-Dmolifox.game.loader=$Loader", "-Dmolifox.game.loaderVersion=$LoaderVersion", '-Dmolifox.game.windowMode=launcher', "-Dmolifox.game.report=$linuxReport")
    if ($OnlySelectedLoader) { $properties += '-Dmolifox.game.includeVanilla=false' }
    Invoke-Linux (@('/usr/bin/env') + $environment + @($linuxJava) + $properties + @('-cp',$linuxClasspath,'dev.molifox.core.RealGameSmokeKt'))
    $result = 'passed'
} finally {
    try {
    $screenshots = Join-Path $testRoot 'screenshots'
    if (Test-Path -LiteralPath $screenshots) {
        Get-ChildItem -LiteralPath $screenshots -Filter 'MoliFox-game-linux-*.png' -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $projectRoot 'docs\screenshots\current') -Force }
    }
    } finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolved.StartsWith($projectRoot + '\.work\tests\', [StringComparison]::OrdinalIgnoreCase) -or ((Get-Item -LiteralPath $testRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe Linux test cleanup path.' }
    Remove-Item -LiteralPath $testRoot -Recurse -Force
    if (Test-Path -LiteralPath $classpathFile) { Remove-Item -LiteralPath $classpathFile -Force }
    [IO.File]::WriteAllText((Join-Path $projectRoot 'build\validation\linux-gameSmoke.md'), "# Linux game smoke`n`nResult: $result`nTemporary data: removed`nEnvironment: WSLg, project-local libraries, software rendering; artifacts prepared on Windows`n", [Text.UTF8Encoding]::new($false))
    }
}
