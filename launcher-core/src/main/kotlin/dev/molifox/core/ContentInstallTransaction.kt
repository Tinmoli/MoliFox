// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装文件的事务写入与回滚。

package dev.molifox.core

import com.google.gson.JsonObject
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

/** 下载阶段在整个安装进度里的上限，剩余区间留给文件落盘与登记。 */
private const val DOWNLOAD_PROGRESS_END = 0.85f

/** 允许写入资源的目标目录；其它目录一律按无效登记拒绝。 */
private val CONTENT_FOLDERS = setOf("mods", "resourcepacks", "shaderpacks")

internal class ContentInstallTransaction(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
) {
    suspend fun install(
        plan: ContentInstallPlan,
        context: JobContext,
    ) {
        val instance = store.instance(plan.instanceId)
        require(instance.installed && contentSignature(store, instance) == plan.signature) {
            "实例配置已改变，请重新选择安装内容"
        }
        ContentInstallRun(store, network, api, plan, context, instance).execute()
    }
}

/** 单次安装的事务状态；每次安装新建一个，避免并发安装共享暂存目录、备份与登记表。 */
private class ContentInstallRun(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
    private val plan: ContentInstallPlan,
    private val context: JobContext,
    private val instance: GameInstance,
) {
    private val root = store.instancePath(instance.id)
    private val registryFile = root.resolve(".molifox-content.json")
    private val backups = linkedMapOf<Path, Path>()
    private val installed = mutableListOf<Path>()
    private lateinit var registry: JsonObject
    private lateinit var staging: Path
    private var original: ByteArray? = null
    private var committed = false
    private var cleanStaging = true

    suspend fun execute() {
        prepare()
        try {
            val downloads = buildDownloads()
            network.downloadAll(
                downloads,
                store.data.settings.maxConcurrent,
                context,
                "下载资源与前置依赖",
                0f,
                DOWNLOAD_PROGRESS_END,
            )
            verifyPlanUnchanged()
            commitFiles(downloads)
            finish()
        } catch (error: IOException) {
            rethrowAfterRollback(error)
        } catch (error: URISyntaxException) {
            rethrowAfterRollback(error)
        } catch (error: IllegalArgumentException) {
            rethrowAfterRollback(error)
        } catch (error: IllegalStateException) {
            rethrowAfterRollback(error)
        } finally {
            removeStaging()
        }
    }

    /** 回滚后把原始失败原样抛出，调用方看到的异常类型与消息保持不变。 */
    private fun rethrowAfterRollback(error: Exception): Nothing {
        rollback(error)
        throw error
    }

    /** 校验实例与资源列表，登记前置来源，并建立本次安装的暂存目录。 */
    private fun prepare() {
        require((if (registryFile.exists()) digest(registryFile) else "") == plan.registryHash) {
            "资源列表已改变，请重新准备安装"
        }
        val stored = if (registryFile.exists()) registryFile.readBytes() else null
        original = stored
        registry = if (stored == null) JsonObject() else readObject(registryFile)
        adoptSources()
        validateLockedFiles()
        staging =
            safePath(root, ".molifox-content-${UUID.randomUUID()}").apply { createDirectories() }
        stored?.let { staging.resolve("registry.json").writeBytes(it) }
    }

    /** 前置依赖沿用原来的锁定状态，安装时按新版本登记。 */
    private fun adoptSources() {
        for (source in plan.sources) {
            verifyContentSource(store, instance.id, source)
            require(
                !registry.obj(source.projectId).boolean("locked") ||
                    registry.obj(source.projectId).string("versionId") == source.versionId,
            ) {
                "锁定版本与本地文件不一致，请先整理或解锁"
            }
            registry.add(
                source.projectId,
                contentSourceRecord(
                    source.copy(
                        locked = source.locked || registry.obj(source.projectId).boolean("locked"),
                    ),
                ),
            )
        }
    }

    /** 目标文件同样受锁定约束：登记表里的锁定版本必须与计划版本一致。 */
    private fun validateLockedFiles() {
        for (file in plan.files) {
            val previous = registry.obj(file.projectId)
            require(!previous.boolean("locked") || previous.string("versionId") == file.versionId) {
                "资源版本已锁定，请先解锁：${file.title}"
            }
        }
    }

    private fun buildDownloads(): List<DownloadArtifact> =
        plan.files.mapIndexed { index, file ->
            require(file.folder in CONTENT_FOLDERS)
            safeFilename(file.filename)
            require(file.algorithm in setOf("SHA-1", "SHA-512"))
            DownloadArtifact(
                contentFileUrl(api, file.url),
                staging.resolve("downloads/$index"),
                file.hash,
                file.size,
                file.algorithm,
            )
        }

    /** 下载期间用户可能改了实例或资源列表；落盘前必须再确认一次，避免写进过期计划。 */
    private suspend fun verifyPlanUnchanged() {
        require(
            contentSignature(store, store.instance(plan.instanceId)) == plan.signature &&
                (if (registryFile.exists()) digest(registryFile) else "") == plan.registryHash,
        ) {
            "安装期间实例或资源列表发生变化，请重新准备安装"
        }
        context.checkCancelled()
        plan.sources.forEach { verifyContentSource(store, instance.id, it) }
    }

    private suspend fun commitFiles(downloads: List<DownloadArtifact>) {
        val placement =
            ContentFilePlacement(root, staging, registry, backups, installed, plan.sources)
        for ((index, file) in plan.files.withIndex()) {
            context.checkCancelled()
            placement.commit(file, downloads[index])
        }
    }

    /** 提交登记表后才算安装成功；旧副本移入回收站失败只保留暂存目录，不回滚。 */
    private suspend fun finish() {
        context.checkCancelled()
        writeJson(registryFile, registry)
        committed = true
        for (backup in backups.values) {
            runCatching { store.platform.moveToTrash(backup) }
                .onFailure {
                    cleanStaging = false
                    store.log("旧资源副本保留在 $staging", true)
                }
        }
        context.progress(1f, "资源与依赖安装完成")
        store.log("资源安装完成：${plan.title}（${plan.files.size} 个文件）")
    }

    /**
     * 未提交时把已落盘的文件与登记的旧副本还原；还原本身失败时保留暂存目录并抛出带说明的
     * IOException，原始失败作为 cause 与 suppressed 一并保留。
     */
    private fun rollback(error: Exception) {
        if (committed) return
        val storedRegistry = original
        val rollback =
            runCatching {
                installed.asReversed().forEach { Files.deleteIfExists(it) }
                for ((target, backup) in backups.entries.toList().asReversed()) {
                    Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING)
                }
                if (storedRegistry == null) {
                    registryFile.deleteIfExists()
                } else {
                    registryFile.writeBytes(storedRegistry)
                }
            }
        if (rollback.isFailure) {
            cleanStaging = false
            throw IOException("资源安装未完成，原文件备份保留在 $staging，请先恢复后再操作", error)
                .also { rollback.exceptionOrNull()?.let(it::addSuppressed) }
        }
    }

    /** 只清理本次安装自己的暂存目录；路径必须仍是实例根目录下的普通目录。 */
    private fun removeStaging() {
        if (!cleanStaging) return
        check(
            staging.parent.toAbsolutePath().normalize() ==
                root.toAbsolutePath().normalize() &&
                staging.name.startsWith(".molifox-content-") &&
                !Files.isSymbolicLink(staging),
        )
        Files.walk(staging).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

/** 把计划里的单个文件放进实例目录：校验冲突、备份旧副本、移动下载结果并登记。 */
private class ContentFilePlacement(
    private val root: Path,
    private val staging: Path,
    private val registry: JsonObject,
    private val backups: MutableMap<Path, Path>,
    private val installed: MutableList<Path>,
    private val sources: List<ContentSource>,
) {
    fun commit(
        file: PlannedContentFile,
        download: DownloadArtifact,
    ) {
        val previous = registry.obj(file.projectId)
        val previousFolder = previous.string("folder", file.folder)
        require(previousFolder in CONTENT_FOLDERS) {
            "资源登记目录无效"
        }
        val old = existingPath(file, previous, previousFolder)
        val disabled = disabledTarget(old)
        val filename = file.filename + if (disabled) ".disabled" else ""
        val target = safePath(root.resolve(file.folder), filename)
        requireTargetFree(file)
        val staleTarget =
            target.exists() &&
                target != old &&
                target != old?.resolveSibling("${old.fileName}.disabled")
        if (staleTarget) {
            require(digest(target, file.algorithm).equals(file.hash, true)) {
                "同名本地文件已存在，未覆盖：$filename"
            }
        }
        old?.let {
            backup(it)
            backup(it.resolveSibling("${it.fileName}.disabled"))
        }
        backup(target)
        target.parent.createDirectories()
        Files.move(download.target, target)
        installed.add(target)
        registry.add(file.projectId, record(file, previous))
    }

    /** 已有副本优先按前置来源定位，其次按登记表里的目录与文件名定位。 */
    private fun existingPath(
        file: PlannedContentFile,
        previous: JsonObject,
        previousFolder: String,
    ): Path? {
        val adopted = sources.find { it.projectId == file.projectId }
        if (adopted != null) {
            return safePath(root.resolve(adopted.folder), safeFilename(adopted.filename))
        }
        return previous
            .string("file")
            .takeIf { it.isNotBlank() }
            ?.let { safePath(root.resolve(previousFolder), safeFilename(it)) }
    }

    /** 判断目标是否应带 `.disabled`，并拒绝启用/禁用两份副本同时存在。 */
    private fun disabledTarget(old: Path?): Boolean {
        if (old == null) return false
        val oldIsDisabled = old.name.endsWith(".disabled", true)
        require(!oldIsDisabled || !old.resolveSibling(contentBaseFilename(old.name)).exists()) {
            "同一模组存在启用和禁用两个副本，请先整理"
        }
        val disabled = oldIsDisabled || old.resolveSibling("${old.fileName}.disabled").exists()
        require(!disabled || oldIsDisabled || !old.exists()) {
            "同一模组存在启用和禁用两个副本，请先整理：${old.fileName}"
        }
        return disabled
    }

    private fun requireTargetFree(file: PlannedContentFile) {
        require(
            registry.entrySet().none { (id, value) ->
                id != file.projectId &&
                    value.isJsonObject &&
                    value.asJsonObject.string("folder") == file.folder &&
                    value.asJsonObject.string("file") == file.filename
            },
        ) {
            "此文件名已由其他项目使用：${file.filename}"
        }
    }

    private fun backup(file: Path) {
        if (file in backups || !file.exists()) return
        require(file.isRegularFile() && !Files.isSymbolicLink(file)) { "资源目标不是普通文件：$file" }
        val target = safePath(staging, "backup/${backups.size}/${file.fileName}")
        target.parent.createDirectories()
        Files.move(file, target)
        backups[file] = target
    }

    private fun record(
        file: PlannedContentFile,
        previous: JsonObject,
    ): JsonObject =
        JsonObject().apply {
            addProperty("file", file.filename)
            addProperty("folder", file.folder)
            addProperty("versionId", file.versionId)
            addProperty("hash", file.hash)
            addProperty("hashAlgorithm", file.algorithm)
            addProperty("locked", previous.boolean("locked"))
            addProperty("title", file.title)
            addProperty("iconUrl", file.iconUrl)
        }
}
