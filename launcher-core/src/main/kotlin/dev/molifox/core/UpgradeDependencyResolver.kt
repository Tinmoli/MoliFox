// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 依赖补齐：升级安装完成后，按模组声明的依赖 id 精确解析并下载缺失项（仅解析一层）。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * 受控依赖补齐：只处理 Fabric / Quilt 声明、只解析一层、按项目标识精确查找；
 * 找不到的依赖只记日志，不阻塞升级——新实例的其余内容照常就绪。
 */
internal class UpgradeDependencyResolver(
    private val store: LauncherStore,
    private val modInstaller: ModUpdateInstaller,
    private val metadataReader: ModMetadataReader,
    private val sources: List<ModUpdateSource>,
) {
    /** 补齐新实例 mods 目录里缺失的前置依赖；环境类声明（minecraft/fabricloader 等）跳过。 */
    suspend fun completeDependencies(
        created: GameInstance,
        mods: Path,
        targetGameVersion: String,
        context: JobContext,
    ) {
        val known = readKnownModIds(mods).toMutableSet()
        val missing =
            readRequiredDependencies(mods, known)
                .filter { it !in NON_MOD_DEPENDENCIES }
        if (missing.isEmpty()) return
        // 依赖解析用目标游戏版本过滤候选；加载器与实例相同。
        val pseudo = store.instance(created.id).copy(gameVersion = targetGameVersion)
        for (id in missing) {
            context.checkCancelled()
            currentCoroutineContext().ensureActive()
            if (id in known) continue
            if (!resolveDependency(pseudo, id, mods)) {
                store.log(I18n.t("instA2.upgrade.dependencyMissing", id), true)
            } else {
                known += id
            }
        }
    }

    /** 已安装模组声明的 id 集合：依赖是否缺失按它判断。 */
    private fun readKnownModIds(mods: Path): Set<String> = listModMetadata(mods).flatMap { it.ids }.toSet()

    /** Fabric / Quilt 声明里的依赖 id；Forge 系声明格式不同，依赖补齐不覆盖。 */
    private fun readRequiredDependencies(
        mods: Path,
        known: Set<String>,
    ): List<String> =
        listModMetadata(mods)
            .filter { it.loader.startsWith("Fabric") || it.loader.startsWith("Quilt") }
            .flatMap { it.entries }
            .flatMap { it.dependencies }
            .mapNotNull { declaration ->
                declaration.substringBefore(' ').trim().takeIf { id -> id.isNotBlank() && id !in known }
            }.distinct()

    private fun listModMetadata(mods: Path): List<ModMetadata> =
        runCatching { Files.list(mods).use { it.toList() } }
            .getOrElse { emptyList() }
            .filter { path ->
                path.isRegularFile() && !Files.isSymbolicLink(path) &&
                    path.fileName.toString().endsWith(".jar", true)
            }.map { metadataReader.read(it) }

    private suspend fun resolveDependency(
        instance: GameInstance,
        id: String,
        mods: Path,
    ): Boolean = sources.firstNotNullOfOrNull { resolveDependencyFrom(it, instance, id, mods) } != null

    /** 单个内容源的解析：成功返回已安装文件名，该源没有可用版本时返回 null。 */
    private suspend fun resolveDependencyFrom(
        source: ModUpdateSource,
        instance: GameInstance,
        id: String,
        mods: Path,
    ): String? {
        val project = runCatching { source.projectBySlug(id, instance) }.getOrNull() ?: return null
        val pool =
            runCatching { source.versions(project, instance) }
                .getOrNull()
                .orEmpty()
                .filter { compatible(it, instance) && it.versionType.equals("release", true) }
        val best = pool.maxWithOrNull { left, right -> compareModVersions(left.number, right.number) }
        val candidate = best?.let { candidateOf(it) } ?: return null
        val installed = runCatching { modInstaller.downloadCandidate(candidate, mods) }.getOrNull() ?: return null
        store.log(I18n.t("instA2.upgrade.dependencyInstalled", project.title, candidate.versionNumber))
        return installed
    }
}
