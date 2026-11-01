# Runs project checks with project-local temporary data and cleans test recycle items on every exit.
param(
    [string]$GradlePath = '',
    [string]$JavaHome = '',
    [string]$Checks = ':launcher-core:test',
    [string[]]$GradleArguments = @('--no-daemon', '--console=plain')
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $GradlePath) { $GradlePath = Join-Path $projectRoot 'gradlew.bat' }
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.dev\gradle-home'
$temporaryRoot = Join-Path $projectRoot '.dev\work\build-tmp'
foreach ($directory in @((Join-Path $projectRoot '.dev'), (Join-Path $projectRoot '.dev\work'), $temporaryRoot)) {
    if ((Test-Path -LiteralPath $directory) -and ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'The check temporary directory must not be linked.'
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
$env:TEMP = $temporaryRoot
$env:TMP = $temporaryRoot
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $temporaryRoot + '"'
$tasks = @($Checks.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($tasks.Count -eq 0) { throw 'At least one check is required.' }
$result = 1
Push-Location -LiteralPath $projectRoot
try {
    & $GradlePath @tasks @GradleArguments
    $result = $LASTEXITCODE
} finally {
    try { & (Join-Path $PSScriptRoot 'CleanTestRecycleBin.ps1') } finally { Pop-Location }
}
exit $result
