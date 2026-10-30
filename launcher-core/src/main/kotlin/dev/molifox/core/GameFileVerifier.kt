// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏依赖文件的缺失和完整性校验。

package dev.molifox.core

import java.io.IOException
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/** Shared by installation and launch: never remove an old file before a replacement is verified. */
internal fun artifactProblem(artifact: DownloadArtifact): String? =
    runCatching {
        val file = artifact.target
        when {
            !file.isRegularFile() -> "文件缺失"
            artifact.size > 0 && file.fileSize() != artifact.size -> "文件大小不符"
            artifact.sha1.isNotBlank() ->
                if (digest(
                        file,
                        artifact.hashAlgorithm,
                    ).equals(artifact.sha1, true)
                ) {
                    null
                } else {
                    "文件校验失败"
                }
            file.fileSize() == 0L -> "文件为空"
            file.extension.equals("jar", true) ->
                ZipFile(
                    file.toFile(),
                ).use { if (it.entries().hasMoreElements()) null else "JAR 内容为空" }
            file.extension.equals("json", true) -> {
                readObject(file)
                null
            }
            else -> null
        }
    }.getOrElse { "文件无法读取：${it.message}" }

internal class GameFileVerifier(
    private val network: NetworkClient,
    private val concurrency: Int,
    private val verifyHashes: Boolean = true,
    private val progress: InstallProgress? = null,
) {
    private val scheduler = DownloadScheduler(network)

    /**
     * 下载前先按「全部缺失」登记校验与下载的工作量：进度分母一次算清，阶段之间不会
     * 出现「做完库已经 100%」的假收尾。校验后完好的文件再从下载份额里释放。
     */
    fun reserve(
        label: String,
        artifacts: List<DownloadArtifact>,
    ) {
        val unique = artifacts.distinctBy { it.target }
        if (unique.isEmpty()) return
        val bytes = unique.sumOf { it.size.coerceAtLeast(0) }
        progress?.plan(InstallPhase.VERIFY, unique.size, bytes, PROGRESS_VERIFY_BYTES_PER_UNIT)
        progress?.plan(label, unique.size, bytes)
    }

    /**
     * 补齐 [artifacts]（必需）并尽力补齐 [optional]（可选）。
     *
     * 必需文件缺失、损坏或下载失败时抛出中文异常；可选文件（例如日志配置）失败只记
     * 日志并继续，不能让一个可有可无的 404 中止整个安装。
     *
     * 先算出缺失/可修复的清单再下载：取清单失败只记警告，必需文件仍在下载任务里失败。
     *
     * 进度按「文件数 + 字节数」登记：校验阶段每个文件都推进一格，下载阶段单文件内部按
     * 字节推进，所以 5000 个资源也不会看起来卡死。
     */
    suspend fun ensure(
        artifacts: List<DownloadArtifact>,
        context: JobContext,
        label: String,
        optional: List<DownloadArtifact> = emptyList(),
        warn: (String) -> Unit = {},
    ) {
        val unique = artifacts.distinctBy { it.target }
        ensureOptional(optional.distinctBy { it.target }, context, warn)
        if (unique.isEmpty()) return
        progress?.begin(InstallPhase.VERIFY)
        val broken = mutableListOf<DownloadArtifact>()
        for (artifact in unique) {
            context.checkCancelled()
            val problem = artifactProblem(if (verifyHashes) artifact else artifact.copy(sha1 = ""))
            progress?.verified(InstallPhase.VERIFY, artifact)
            if (problem != null) broken += artifact
        }
        // 已经完好的文件不需要下载，把预留给它们的下载份额还回去。
        val brokenTargets = broken.mapTo(HashSet()) { it.target }
        val intact = unique.filter { it.target !in brokenTargets }
        progress?.release(label, intact.size, intact.sumOf { it.size.coerceAtLeast(0) })
        if (broken.isEmpty()) return
        val unavailable = broken.filter { it.url.isBlank() }
        check(unavailable.isEmpty()) {
            "本地游戏文件缺失或损坏，版本未提供下载地址，请从原来源恢复：\n" +
                unavailable.joinToString("\n") { it.target.toString() }
        }
        progress?.begin(label)
        val failures =
            scheduler.run(
                broken,
                context,
                concurrency,
                progress ?: DownloadProgressSink.None,
            )
        context.checkCancelled()
        // 下载完成后逐个复核：既确认内容有效，也让可选的「补全后仍无效」进入同一份报告。
        val remaining =
            broken.filter {
                context.checkCancelled()
                artifactProblem(it) != null
            }
        if (failures.isEmpty() && remaining.isEmpty()) return
        val details =
            if (failures.isNotEmpty()) {
                failures.joinToString("\n") { it.describe() }
            } else {
                remaining.take(8).joinToString("\n") { it.target.toString() }
            }
        throw IOException("游戏文件自动补全失败，请检查网络或使用修复功能。\n$details")
    }

    /**
     * 可选文件：与必需文件用同一套并发调度，失败只警告不抛出；取消仍然向上传播。
     * 一个 404 的日志配置不能拖住整个安装。
     */
    private suspend fun ensureOptional(
        optional: List<DownloadArtifact>,
        context: JobContext,
        warn: (String) -> Unit,
    ) {
        val pending =
            optional.filter {
                context.checkCancelled()
                artifactProblem(if (verifyHashes) it else it.copy(sha1 = "")) != null
            }
        val downloadable = pending.filter { it.url.isNotBlank() }
        pending
            .filter { it.url.isBlank() }
            .forEach { warn("可选文件缺少下载地址，已跳过：${it.target}") }
        if (downloadable.isEmpty()) return
        val failures = scheduler.run(downloadable, context, concurrency)
        failures.forEach { failure ->
            warn("可选文件未补全，已跳过：${failure.target}（${failure.reason}）")
        }
    }
}
