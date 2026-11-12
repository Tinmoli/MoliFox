// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把单文件下载的实际字节与完成状态汇入后台任务。

package dev.molifox.core

import kotlinx.coroutines.withContext
import kotlin.io.path.fileSize

internal suspend fun NetworkClient.downloadTracked(
    context: JobContext,
    artifact: DownloadArtifact,
    urls: List<String> = listOf(artifact.url),
    progress: (Long, Long) -> Unit = { _, _ -> },
) = withContext(TransferTrafficContext(context)) {
    context.beginTransfers(1)
    downloadFrom(artifact, urls) { bytes, total ->
        context.transfer(artifact.target.toString(), bytes, total)
        progress(bytes, total)
    }
    val size = artifact.target.fileSize()
    context.transfer(artifact.target.toString(), size, size, completed = true)
}
