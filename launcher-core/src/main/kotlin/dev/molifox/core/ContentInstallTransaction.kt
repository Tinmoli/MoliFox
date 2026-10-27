package dev.molifox.core

import com.google.gson.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.*

internal class ContentInstallTransaction(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
) {
    suspend fun install(plan: ContentInstallPlan, context: JobContext) {
        val instance = store.instance(plan.instanceId)
        require(instance.installed && contentSignature(store, instance) == plan.signature) {
            "实例配置已改变，请重新选择安装内容"
        }
        val root = store.instancePath(instance.id)
        val registryFile = root.resolve(".molifox-content.json")
        require((if (registryFile.exists()) digest(registryFile) else "") == plan.registryHash) {
            "资源列表已改变，请重新准备安装"
        }
        val original = if (registryFile.exists()) registryFile.readBytes() else null
        val registry = if (original == null) JsonObject() else readObject(registryFile)
        for (source in plan.sources) {
            verifyContentSource(store, instance.id, source)
            require(
                !registry.obj(source.projectId).boolean("locked") ||
                    registry.obj(source.projectId).string("versionId") == source.versionId
            ) {
                "锁定版本与本地文件不一致，请先整理或解锁"
            }
            registry.add(
                source.projectId,
                contentSourceRecord(
                    source.copy(
                        locked = source.locked || registry.obj(source.projectId).boolean("locked")
                    )
                ),
            )
        }
        for (file in plan.files) {
            val previous = registry.obj(file.projectId)
            require(!previous.boolean("locked") || previous.string("versionId") == file.versionId) {
                "资源版本已锁定，请先解锁：${file.title}"
            }
        }
        val staging =
            safePath(root, ".molifox-content-${UUID.randomUUID()}").apply { createDirectories() }
        if (original != null) staging.resolve("registry.json").writeBytes(original)
        val backups = linkedMapOf<Path, Path>()
        val installed = mutableListOf<Path>()
        var committed = false
        var cleanStaging = true
        fun backup(file: Path) {
            if (file in backups || !file.exists()) return
            require(file.isRegularFile() && !Files.isSymbolicLink(file)) { "资源目标不是普通文件：$file" }
            val target = safePath(staging, "backup/${backups.size}/${file.fileName}")
            target.parent.createDirectories()
            Files.move(file, target)
            backups[file] = target
        }
        try {
            val downloads =
                plan.files.mapIndexed { index, file ->
                    require(file.folder in setOf("mods", "resourcepacks", "shaderpacks"))
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
            network.downloadAll(
                downloads,
                store.data.settings.maxConcurrent,
                context,
                "下载资源与前置依赖",
                0f,
                .85f,
            )
            require(
                contentSignature(store, store.instance(plan.instanceId)) == plan.signature &&
                    (if (registryFile.exists()) digest(registryFile) else "") == plan.registryHash
            ) {
                "安装期间实例或资源列表发生变化，请重新准备安装"
            }
            context.checkCancelled()
            plan.sources.forEach { verifyContentSource(store, instance.id, it) }
            for ((index, file) in plan.files.withIndex()) {
                context.checkCancelled()
                val previous = registry.obj(file.projectId)
                val previousFolder = previous.string("folder", file.folder)
                require(previousFolder in setOf("mods", "resourcepacks", "shaderpacks")) {
                    "资源登记目录无效"
                }
                val adopted = plan.sources.find { it.projectId == file.projectId }
                val old =
                    adopted?.let { safePath(root.resolve(it.folder), safeFilename(it.filename)) }
                        ?: previous
                            .string("file")
                            .takeIf { it.isNotBlank() }
                            ?.let { safePath(root.resolve(previousFolder), safeFilename(it)) }
                val oldIsDisabled = old?.name?.endsWith(".disabled", true) == true
                require(
                    !oldIsDisabled || !old!!.resolveSibling(contentBaseFilename(old.name)).exists()
                ) {
                    "同一模组存在启用和禁用两个副本，请先整理"
                }
                val disabled =
                    oldIsDisabled ||
                        (old != null && old.resolveSibling("${old.fileName}.disabled").exists())
                require(old == null || !disabled || oldIsDisabled || !old.exists()) {
                    "同一模组存在启用和禁用两个副本，请先整理：${old?.fileName}"
                }
                val filename = file.filename + if (disabled) ".disabled" else ""
                val target = safePath(root.resolve(file.folder), filename)
                require(
                    registry.entrySet().none { (id, value) ->
                        id != file.projectId &&
                            value.isJsonObject &&
                            value.asJsonObject.string("folder") == file.folder &&
                            value.asJsonObject.string("file") == file.filename
                    }
                ) {
                    "此文件名已由其他项目使用：${file.filename}"
                }
                if (
                    target.exists() &&
                        target != old &&
                        target != old?.resolveSibling("${old.fileName}.disabled")
                ) {
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
                Files.move(downloads[index].target, target)
                installed.add(target)
                registry.add(
                    file.projectId,
                    JsonObject().apply {
                        addProperty("file", file.filename)
                        addProperty("folder", file.folder)
                        addProperty("versionId", file.versionId)
                        addProperty("hash", file.hash)
                        addProperty("hashAlgorithm", file.algorithm)
                        addProperty("locked", previous.boolean("locked"))
                        addProperty("title", file.title)
                    },
                )
            }
            context.checkCancelled()
            writeJson(registryFile, registry)
            committed = true
            for (backup in backups.values) runCatching { store.platform.moveToTrash(backup) }
                .onFailure {
                    cleanStaging = false
                    store.log("旧资源副本保留在 $staging", true)
                }
            context.progress(1f, "资源与依赖安装完成")
            store.log("资源安装完成：${plan.title}（${plan.files.size} 个文件）")
        } catch (error: Throwable) {
            if (!committed) {
                val rollback = runCatching {
                    installed.asReversed().forEach { Files.deleteIfExists(it) }
                    for ((target, backup) in backups.entries.toList().asReversed()) {
                        Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    if (original == null) registryFile.deleteIfExists()
                    else registryFile.writeBytes(original)
                }
                if (rollback.isFailure) {
                    cleanStaging = false
                    throw IOException("资源安装未完成，原文件备份保留在 $staging，请先恢复后再操作", error).also {
                        it.addSuppressed(rollback.exceptionOrNull()!!)
                    }
                }
            }
            throw error
        } finally {
            if (cleanStaging) {
                check(
                    staging.parent.toAbsolutePath().normalize() ==
                        root.toAbsolutePath().normalize() &&
                        staging.name.startsWith(".molifox-content-") &&
                        !Files.isSymbolicLink(staging)
                )
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
