// 负责游戏依赖文件的缺失和完整性校验。

package dev.molifox.core

import java.io.IOException
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.CancellationException

/** Shared by installation and launch: never remove an old file before a replacement is verified. */
internal fun artifactProblem(artifact: DownloadArtifact): String? = runCatching {
    val file = artifact.target
    when {
        !file.isRegularFile() -> "文件缺失"
        artifact.size > 0 && file.fileSize() != artifact.size -> "文件大小不符"
        artifact.sha1.isNotBlank() ->
            if (digest(file, artifact.hashAlgorithm).equals(artifact.sha1, true)) null else "文件校验失败"
        file.fileSize() == 0L -> "文件为空"
        file.extension.equals("jar", true) ->
            ZipFile(file.toFile()).use { if (it.entries().hasMoreElements()) null else "JAR 内容为空" }
        file.extension.equals("json", true) -> {
            readObject(file)
            null
        }
        else -> null
    }
}
    .getOrElse { "文件无法读取：${it.message}" }

internal class GameFileVerifier(
    private val network: NetworkClient,
    private val concurrency: Int,
    private val verifyHashes: Boolean = true,
) {
    suspend fun ensure(
        artifacts: List<DownloadArtifact>,
        context: JobContext,
        label: String,
        from: Float,
        to: Float,
    ) {
        val broken =
            artifacts
                .distinctBy { it.target }
                .filter {
                    context.checkCancelled()
                    artifactProblem(if (verifyHashes) it else it.copy(sha1 = "")) != null
                }
        if (broken.isEmpty()) return
        val unavailable = broken.filter { it.url.isBlank() }
        check(unavailable.isEmpty()) {
            "本地游戏文件缺失或损坏，版本未提供下载地址，请从原来源恢复：\n" +
                unavailable.joinToString("\n") { it.target.toString() }
        }
        try {
            network.downloadAll(broken, concurrency, context, label, from, to)
            for (artifact in broken) {
                context.checkCancelled()
                check(artifactProblem(artifact) == null) { "补全后的文件仍无效：${artifact.target}" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val remaining = broken.filter { artifactProblem(it) != null }
            val names = remaining.take(8).joinToString("\n") { it.target.toString() }
            throw IOException("游戏文件自动补全失败，请检查网络或使用修复功能。\n$names\n${error.message}", error)
        }
    }
}
