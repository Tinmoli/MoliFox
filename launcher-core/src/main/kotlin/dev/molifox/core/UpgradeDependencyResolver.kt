// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 依赖收尾：升级安装完成后补齐缺失的前置依赖，并和解已装模组之间声明的依赖版本约束。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * 受控依赖收尾：只处理 Fabric / Quilt 声明、只解析一层、按项目标识精确查找；
 * 找不到的依赖或无法和解的版本冲突只记日志，不阻塞升级——新实例的其余内容照常就绪。
 */
internal class UpgradeDependencyResolver(
    private val store: LauncherStore,
    private val modInstaller: ModUpdateInstaller,
    private val metadataReader: ModMetadataReader,
    private val sources: List<ModUpdateSource>,
) {
    /** 先补齐缺失的前置依赖，再核对已装模组之间声明的依赖版本约束。 */
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
        if (missing.isNotEmpty()) {
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
        reconcileDependencyVersions(created, mods, targetGameVersion, context)
    }

    /** 已安装模组声明与内嵌 jar 提供的 id 集合：依赖是否缺失按它判断。 */
    private fun readKnownModIds(mods: Path): Set<String> = listModMetadata(mods).flatMap { it.second.knownIds }.toSet()

    /** Fabric / Quilt 声明里的依赖 id；Forge 系声明格式不同，依赖收尾不覆盖。 */
    private fun readRequiredDependencies(
        mods: Path,
        known: Set<String>,
    ): List<String> =
        listModMetadata(mods)
            .filter { it.second.loader.startsWith("Fabric") || it.second.loader.startsWith("Quilt") }
            .flatMap { it.second.entries }
            .flatMap { it.dependencies }
            .mapNotNull { declaration ->
                declaration.substringBefore(' ').trim().takeIf { id -> id.isNotBlank() && id !in known }
            }.distinct()

    private fun listModMetadata(mods: Path): List<Pair<String, ModMetadata>> =
        runCatching { Files.list(mods).use { it.toList() } }
            .getOrElse { emptyList() }
            .filter { path ->
                path.isRegularFile() && !Files.isSymbolicLink(path) &&
                    path.fileName.toString().endsWith(".jar", true)
            }.map { it.fileName.toString() to metadataReader.read(it) }

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

    /**
     * 依赖版本和解：模组升级按「目标版本下最新」逐个选版，彼此声明的依赖约束只有下载后读 jar 才知道。
     * 这里把已装模组声明的约束逐条核对；被要求的依赖版本不符时，换装目标版本下满足全部约束的最高版本；
     * 找不到就按「依赖未补齐」同样的口径记错误日志，不静默也不阻塞其余内容。
     */
    private suspend fun reconcileDependencyVersions(
        created: GameInstance,
        mods: Path,
        targetGameVersion: String,
        context: JobContext,
    ) {
        if (targetGameVersion.isBlank()) return
        val metadata = listModMetadata(mods)
        if (metadata.isEmpty()) return
        val required = declaredConstraints(metadata)
        if (required.isEmpty()) return
        val installed = installedMods(metadata)
        val pseudo = store.instance(created.id).copy(gameVersion = targetGameVersion)
        for ((id, constraints) in required) {
            context.checkCancelled()
            currentCoroutineContext().ensureActive()
            val current = installed[id] ?: continue
            if (constraints.all { minecraftConstraintMatches(it, current.version) }) continue
            replaceDependency(pseudo, id, constraints, current, mods)
        }
    }

    /** 换装一个被约束冲突的依赖：先取得满足约束的版本，新文件落盘后才移除旧文件；失败保留原状并记日志。 */
    private suspend fun replaceDependency(
        instance: GameInstance,
        id: String,
        constraints: List<String>,
        current: InstalledDependency,
        mods: Path,
    ) {
        val replacement = satisfyingVersion(instance, id, constraints)
        if (
            replacement == null ||
            compareModVersions(replacement.number, current.version) == 0
        ) {
            store.log(unresolvedMessage(id, current.version, constraints), true)
            return
        }
        val candidate = candidateOf(replacement)
        if (candidate == null) {
            store.log(unresolvedMessage(id, current.version, constraints), true)
            return
        }
        runCatching {
            // 换装目标与旧文件同名时先移除（downloadCandidate 要求目标不存在）；升级失败会整体回滚，旧实例不受影响。
            val sameName = candidate.filename.equals(current.filename, true)
            if (sameName) Files.deleteIfExists(mods.resolve(current.filename))
            val installedName = modInstaller.downloadCandidate(candidate, mods)
            if (!sameName) Files.deleteIfExists(mods.resolve(current.filename))
            installedName
        }.onSuccess {
            store.log(I18n.t("instA2.upgrade.dependencyConflictResolved", id, current.version, replacement.number))
        }.onFailure { error ->
            if (error is CancellationException) throw error
            store.log(unresolvedMessage(id, current.version, constraints), true)
        }
    }

    /** 目标版本 + 加载器 + 正式版过滤后，再按声明约束筛，取最高版本；单个源失败交给下一个源。 */
    private suspend fun satisfyingVersion(
        instance: GameInstance,
        id: String,
        constraints: List<String>,
    ): RemoteModVersion? =
        sources.firstNotNullOfOrNull { source -> satisfyingVersionFrom(source, instance, id, constraints) }

    private suspend fun satisfyingVersionFrom(
        source: ModUpdateSource,
        instance: GameInstance,
        id: String,
        constraints: List<String>,
    ): RemoteModVersion? {
        val project = runCatching { source.projectBySlug(id, instance) }.getOrNull() ?: return null
        return runCatching {
            source
                .versions(project, instance)
                // 和解按声明约束精确选版，约束会点名 beta（如 sodium 0.9.3-beta.3）：
                // 这里不能只认 release，否则满足约束的版本被滤光，换装必然失败。
                .filter { compatible(it, instance) }
                .filter { version -> constraints.all { minecraftConstraintMatches(it, version.number) } }
                .maxWithOrNull { left, right -> compareModVersions(left.number, right.number) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }
    }

    /** 已装模组：mod id → 版本与所在文件；同一 jar 声明多个 id 时都指向该文件。 */
    private fun installedMods(metadata: List<Pair<String, ModMetadata>>): Map<String, InstalledDependency> =
        metadata
            .flatMap { (filename, meta) ->
                meta.entries.map { entry -> entry.id to InstalledDependency(entry.version, filename) }
            }.filter { (id, mod) -> id.isNotBlank() && mod.version.isNotBlank() }
            .toMap()

    /** 各模组声明的依赖版本约束：被依赖 id → 约束列表；环境类 id 不参与，与依赖补齐同一口径。 */
    private fun declaredConstraints(metadata: List<Pair<String, ModMetadata>>): Map<String, List<String>> =
        metadata
            .filter { it.second.loader.startsWith("Fabric") || it.second.loader.startsWith("Quilt") }
            .flatMap { it.second.entries }
            .flatMap { it.dependencies }
            .mapNotNull { declaration ->
                val id = declaration.substringBefore(' ').trim()
                // 声明串来自 JsonElement.toString()，字符串值带着 JSON 引号；约束按版本号比较前先剥掉。
                val constraint = declaration.substringAfter(' ', "").trim().removeSurrounding("\"")
                (id to constraint)
                    .takeIf { (dep, value) -> dep.isNotBlank() && value.isNotBlank() && dep !in NON_MOD_DEPENDENCIES }
            }.groupBy(keySelector = { it.first }, valueTransform = { it.second })

    private fun unresolvedMessage(
        id: String,
        version: String,
        constraints: List<String>,
    ): String = I18n.t("instA2.upgrade.dependencyConflictUnresolved", id, version, constraints.joinToString("；"))
}

/** 已安装依赖的现状：版本与所在文件，用于约束核对与换装。 */
private data class InstalledDependency(
    val version: String,
    val filename: String,
)
