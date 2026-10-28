// 负责把单文件下载的实际字节与完成状态汇入后台任务。

package dev.molifox.core

import kotlin.io.path.fileSize

internal suspend fun NetworkClient.downloadTracked(
    context: JobContext,
    artifact: DownloadArtifact,
    progress: (Long, Long) -> Unit = { _, _ -> },
) {
    context.beginTransfers(1)
    download(artifact) { bytes, total ->
        context.transfer(artifact.target.toString(), bytes, total)
        progress(bytes, total)
    }
    val size = artifact.target.fileSize()
    context.transfer(artifact.target.toString(), size, size, completed = true)
}
