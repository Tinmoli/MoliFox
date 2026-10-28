// 负责提交存档临时文件，在 Windows 短暂拒绝访问时有限重试。

package dev.molifox.core

import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val SAVE_COMMIT_ATTEMPTS = 4
private const val SAVE_COMMIT_RETRY_MILLIS = 50L

internal fun commitSavePath(
    source: Path,
    target: Path,
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    move: (Path, Path, Boolean) -> Unit = { from, to, atomic ->
        if (atomic) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) else Files.move(from, to)
    },
    pause: (Long) -> Unit = Thread::sleep,
) {
    for (attempt in 0 until SAVE_COMMIT_ATTEMPTS) {
        try {
            try {
                move(source, target, true)
            } catch (_: AtomicMoveNotSupportedException) {
                move(source, target, false)
            }
            return
        } catch (failure: AccessDeniedException) {
            // Windows 文件扫描可能短时持有目录；永久权限错误仍按原错误返回。
            if (!windows || attempt == SAVE_COMMIT_ATTEMPTS - 1) throw failure
            pause(SAVE_COMMIT_RETRY_MILLIS * (attempt + 1))
        }
    }
}
