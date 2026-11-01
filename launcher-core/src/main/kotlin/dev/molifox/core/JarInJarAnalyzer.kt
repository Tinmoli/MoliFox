// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责编排嵌入依赖的只读分析：列出模组 → 有界扫描（缓存）→ 选择可加载节点 → 组装报告。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val PROGRESS_INTERVAL_NANOS = 150_000_000L

/**
 * 嵌入依赖只读分析入口。
 *
 * 只读：不下载、不安装、不解压到实例目录，也不改动画面的文件操作；扫描失败或超限时把报告标成
 * 不完整，调用方据此不再把「缺失」当成定论。缓存为会话级内存 LRU，安装/更新/删除后由调用方
 * 通过 [invalidate] / [clearCache] 主动失效（ContentService 接线时使用）。
 */
class JarInJarAnalyzer
    internal constructor(
        private val limits: JarInJarLimits,
    ) {
        constructor() : this(JarInJarLimits())

        private val cache = JarInJarCache()

        /** 某个模组文件被替换或删除后失效它的扫描结果。 */
        fun invalidate(path: Path) = cache.invalidate(path)

        /** 整合包导入/修复等批量写入后清空内存缓存。 */
        fun clearCache() = cache.clear()

        /** 列表刷新后保留仍存在的模组；供后续接线在模组页刷新时调用。 */
        fun pruneCache(keep: Collection<Path>) = cache.prune(keep)

        suspend fun analyze(
            instanceId: String,
            gameVersion: String,
            loader: LoaderType,
            mods: List<ModFile>,
            modsDirectory: Path,
            temporaryDirectory: Path,
            isCancelled: () -> Boolean = { false },
            onProgress: ((scanned: Int, total: Int) -> Unit)? = null,
        ): JarInJarReport =
            withContext(Dispatchers.IO) {
                analyzeBlocking(
                    instanceId,
                    gameVersion,
                    loader,
                    mods,
                    modsDirectory,
                    temporaryDirectory,
                    isCancelled,
                    onProgress,
                )
            }

        private fun analyzeBlocking(
            instanceId: String,
            gameVersion: String,
            loader: LoaderType,
            mods: List<ModFile>,
            modsDirectory: Path,
            temporaryDirectory: Path,
            isCancelled: () -> Boolean,
            onProgress: ((scanned: Int, total: Int) -> Unit)?,
        ): JarInJarReport {
            val scanned =
                scanHosts(
                    mods,
                    modsDirectory,
                    loader.jarInJarLoader(),
                    temporaryDirectory,
                    isCancelled,
                    onProgress,
                )
            val nodesByHost = JarInJarSelection.loadableByHost(scanned.hosts, gameVersion)
            val index = JarInJarIndex(scanned.hosts, nodesByHost, scanned.incomplete)
            return JarInJarReport(
                instanceId = instanceId,
                gameVersion = gameVersion,
                loader = loader.title,
                hosts = scanned.hosts,
                rows = index.rows,
                warnings = index.warnings,
                conflicts = index.conflicts,
                incomplete = scanned.incomplete,
                incompleteNote = scanned.note,
            )
        }

        private fun scanHosts(
            mods: List<ModFile>,
            modsDirectory: Path,
            preferred: JarInJarLoader,
            temporaryDirectory: Path,
            isCancelled: () -> Boolean,
            onProgress: ((scanned: Int, total: Int) -> Unit)?,
        ): JarInJarScanOutcome {
            val scanner = JarInJarScanner(temporaryDirectory, limits)
            val budget = JarInJarScanBudget(limits)
            val hosts = mutableListOf<JarInJarHost>()
            val pending = mutableListOf<PendingCacheWrite>()
            var lastEmit = 0L
            var cancelled = false
            mods.forEachIndexed { index, mod ->
                if (cancelled || isCancelled()) {
                    cancelled = true
                    return@forEachIndexed
                }
                val path = modsDirectory.resolve(mod.filename)
                val cached = cache.get(path, mod.size, mod.modified)
                val scan =
                    cached ?: scanner.scan(path, preferred, isCancelled, budget).also { fresh ->
                        if (fresh.complete) {
                            pending += PendingCacheWrite(path, mod.size, mod.modified, fresh)
                        }
                    }
                hosts += hostFrom(mod, scan)
                lastEmit = emitProgress(index + 1, mods.size, onProgress, lastEmit)
            }
            if (isCancelled()) cancelled = true
            // 取消时不写缓存，避免把半途结果固化成结论；不完整宿主本来就不会进入 pending。
            if (!cancelled) {
                pending.forEach { cache.put(it.path, it.size, it.modified, it.scan) }
            }
            val incomplete = cancelled || !budget.complete || hosts.any { !it.complete }
            return JarInJarScanOutcome(hosts, incomplete, incompleteNote(cancelled, budget))
        }

        private fun hostFrom(
            mod: ModFile,
            scan: JarInJarHostScan,
        ): JarInJarHost {
            val primary = scan.declaration.mods.firstOrNull()
            val siblings =
                scan.declaration.mods
                    .drop(1)
                    .map { JarInJarProvided(it.id, it.version) }
            return JarInJarHost(
                filename = mod.filename,
                enabled = mod.enabled,
                loader = scan.declaration.loader,
                modId = primary?.id.orEmpty(),
                name = primary?.name.orEmpty(),
                version = primary?.version.orEmpty(),
                dependencies =
                    scan.declaration.mods
                        .flatMap { it.dependencies }
                        .distinct(),
                conflicts =
                    scan.declaration.mods
                        .flatMap { it.conflicts }
                        .distinct(),
                provides = scan.declaration.mods.flatMap { it.provides } + siblings,
                embedded = scan.embedded,
                complete = scan.complete,
                note = scan.note,
            )
        }

        private fun emitProgress(
            scanned: Int,
            total: Int,
            onProgress: ((scanned: Int, total: Int) -> Unit)?,
            lastEmit: Long,
        ): Long {
            if (onProgress == null) return lastEmit
            val now = System.nanoTime()
            if (scanned < total && now - lastEmit < PROGRESS_INTERVAL_NANOS) return lastEmit
            onProgress(scanned, total)
            return now
        }

        private fun incompleteNote(
            cancelled: Boolean,
            budget: JarInJarScanBudget,
        ): String {
            val reasons = mutableListOf<String>()
            if (cancelled) reasons += I18n.t("diagA1.jarInJar.scanCancelled")
            budget.failureText.takeIf(String::isNotBlank)?.let(reasons::add)
            return reasons.distinct().joinToString("；")
        }
    }

/** 一次列表扫描的中间结果：不完整时报告只展示保守结论。 */
private class JarInJarScanOutcome(
    val hosts: List<JarInJarHost>,
    val incomplete: Boolean,
    val note: String,
)

private class PendingCacheWrite(
    val path: Path,
    val size: Long,
    val modified: Long,
    val scan: JarInJarHostScan,
)
