// 更新助手只接收本次校验过的同目录暂存文件；失败时保留旧启动器并写出原因。
package dev.molifox.core

import java.nio.file.Path

internal object UpdateReplacementScripts {
    fun windows(target: Path, staged: Path, pids: List<Long>, sha256: String): String {
        val waits = pids.joinToString("\n") { "while (Get-Process -Id $it -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 200 }" }
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}target = '${ps(target)}'
            ${'$'}staged = '${ps(staged)}'
            try {
                $waits
                if ((Get-FileHash -LiteralPath ${'$'}staged -Algorithm SHA256).Hash -ne '$sha256') { throw 'Update digest mismatch' }
                ${'$'}replaced = ${'$'}false
                for (${'$'}attempt = 0; ${'$'}attempt -lt 30; ${'$'}attempt++) {
                    try {
                        [IO.File]::Replace(${'$'}staged, ${'$'}target, "${'$'}target.previous", ${'$'}true)
                        ${'$'}replaced = ${'$'}true
                        break
                    } catch {
                        if (${'$'}attempt -eq 29) { throw }
                        Start-Sleep -Milliseconds 500
                    }
                }
                if (-not ${'$'}replaced) { throw 'Update replacement failed' }
                Start-Process -FilePath ${'$'}target -WorkingDirectory ([IO.Path]::GetDirectoryName(${'$'}target))
                Remove-Item -LiteralPath ${'$'}PSCommandPath -Force
            } catch {
                Write-Error -ErrorRecord ${'$'}_ -ErrorAction Continue
                exit 1
            } finally {
                if (Test-Path -LiteralPath ${'$'}staged) { Remove-Item -LiteralPath ${'$'}staged -Force }
            }
        """.trimIndent()
    }

    fun linux(target: Path, staged: Path, pids: List<Long>, sha256: String): String = """
        #!/bin/sh
        set -eu
        target='${sh(target)}'
        staged='${sh(staged)}'
        trap 'rm -f -- "${'$'}staged"' 0
        for pid in ${pids.joinToString(" ")}; do
            while kill -0 "${'$'}pid" 2>/dev/null; do sleep 1; done
        done
        actual=${'$'}(sha256sum -- "${'$'}staged")
        [ "${'$'}{actual%% *}" = '$sha256' ] || { echo 'Update digest mismatch' >&2; exit 1; }
        chmod +x -- "${'$'}staged"
        cp -p -- "${'$'}target" "${'$'}target.previous"
        mv -f -- "${'$'}staged" "${'$'}target"
        cd -- "${'$'}(dirname -- "${'$'}target")"
        setsid "${'$'}target" </dev/null &
        rm -f -- "${'$'}0"
    """.trimIndent() + "\n"

    private fun ps(path: Path): String = path.toAbsolutePath().toString().replace("'", "''")

    private fun sh(path: Path): String = path.toAbsolutePath().toString().replace("'", "'\\''")
}
