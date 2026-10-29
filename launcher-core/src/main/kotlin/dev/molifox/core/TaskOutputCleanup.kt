// 记录任务生成的文件和新建目录，在取消且写入停止后回收本次产物。

package dev.molifox.core

import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

internal class TaskOutputCleanup(private val task: JobContext) {
    private val journal = TaskDownloadJournal(task)
    private val directories = ConcurrentHashMap.newKeySet<Path>()

    fun beforeDirectoryCreation(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !current.exists(NOFOLLOW_LINKS)) {
            directories.add(current)
            current = current.parent
        }
    }

    fun track(path: Path): () -> Unit {
        val normalized = path.toAbsolutePath().normalize()
        val existed = normalized.exists(NOFOLLOW_LINKS)
        beforeDirectoryCreation(normalized.parent)
        return {
            if (!existed && normalized.isRegularFile(NOFOLLOW_LINKS)) journal.completed(normalized)
        }
    }

    fun write(path: Path, action: () -> Unit) {
        val completed = track(path)
        try {
            action()
        } finally {
            completed()
        }
    }

    suspend fun cleanFiles() = journal.clean { _, action -> action() }

    fun cleanEmptyDirectories() {
        for (path in directories.sortedByDescending { it.nameCount }) {
            if (!path.isDirectory(NOFOLLOW_LINKS) || path.toRealPath() != path) continue
            try {
                Files.deleteIfExists(path)
            } catch (_: DirectoryNotEmptyException) {
                // Existing content or another task's files keep their parent directory alive.
            }
        }
    }
}
