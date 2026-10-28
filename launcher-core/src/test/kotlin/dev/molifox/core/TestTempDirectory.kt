// 负责创建并回收测试专用临时目录。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path

/** 在系统临时目录下建一个测试目录，块结束后无论成败都递归删除。 */
internal fun <T> withTempDirectory(prefix: String, block: (Path) -> T): T {
    val directory = Files.createTempDirectory(prefix)
    try {
        return block(directory)
    } finally {
        runCatching {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
