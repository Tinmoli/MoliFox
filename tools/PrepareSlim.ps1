$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$quality = Join-Path $root '.dev/tooling/quality'
New-Item -ItemType Directory -Force $quality | Out-Null
$env:npm_config_cache = Join-Path $root '.dev/npm-cache'
Copy-Item (Join-Path $root 'config/slim-tools.json') (Join-Path $quality 'package.json')
Copy-Item (Join-Path $root 'config/slim-tools-lock.json') (Join-Path $quality 'package-lock.json')
& npm.cmd ci --prefix $quality --ignore-scripts --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
$tokei = Join-Path $quality 'tokei-12.1.2.exe'
if (-not (Test-Path -LiteralPath $tokei)) {
    Invoke-WebRequest -UseBasicParsing 'https://github.com/XAMPPRocky/tokei/releases/download/v12.1.2/tokei-x86_64-pc-windows-msvc.exe' -OutFile $tokei
}
$expected = 'B1D6C4B18F5FA238BD2C6E47CAA65A7A3E4A1BD0DE6DF0B7C19C8083C941F57B'
if ((Get-FileHash -LiteralPath $tokei -Algorithm SHA256).Hash -ne $expected) {
    throw 'Unexpected tokei binary hash'
}
