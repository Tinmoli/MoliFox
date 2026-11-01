// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按步骤安装游戏版本：元数据、基础依赖、加载器配置与发布。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 元数据之后基线依赖的进度起点。 */
private const val BASE_DEPENDENCIES_PROGRESS_START = 0.02f

/** 加载器阶段的起点，也是带加载器时基线依赖的终点。 */
private const val LOADER_PROGRESS_START = 0.70f

/** 加载器阶段的终点，也是发布后依赖下载的起点。 */
private const val LOADER_PROGRESS_END = 0.96f

/** 没有加载器时，基线依赖与发布后依赖都直接跳到这个进度。 */
private const val SKIPPED_LOADER_PROGRESS = 0.90f

/** 发布后依赖下载的终点。 */
private const val FINAL_DEPENDENCIES_PROGRESS_END = 0.99f

/** 基线版本的准备结果：解析好的配置，以及目标配置是否要自包含。 */
private class BaseProfile(
    val profile: ResolvedProfile,
    val selfContained: Boolean,
)

/**
 * 全新实例直接以实例目录为版本目录：基础 JSON 就写成实例 JSON，客户端 JAR 也落到实例目录。
 * 这样不再先造一个 `versions/<游戏版本>` 中间目录；加载器配置随后合并覆盖。
 */
private suspend fun prepareDirectBaseProfile(
    network: NetworkClient,
    metadata: ReleaseVersion,
    target: Path,
): BaseProfile {
    val id = target.fileName.toString()
    safeFilename(id)
    Files.createDirectories(target)
    val jsonPath = target.resolve("$id.json")
    network.download(DownloadArtifact(metadata.url, jsonPath, metadata.sha1.orEmpty()))
    val json = JsonParser.parseString(Files.readString(jsonPath)).asJsonObject
    require(json.string("inheritsFrom").isBlank()) {
        I18n.t("miscA2.install.parentVersionRequired")
    }
    json.addProperty("id", id)
    if (json.has("jar")) json.addProperty("jar", id)
    val profile = ResolvedProfile(id, id, json)
    require(profile.mainClass.isNotBlank()) { I18n.t("miscA2.install.unsupportedVersion") }
    return BaseProfile(profile, true)
}

/** 取消安装时回收本任务新建的中间版本目录并结束「安装中」标记。 */
private fun registerInstallCleanup(
    context: JobContext,
    instanceId: String,
    createdVersions: Set<Path>,
    provisioner: InstanceProvisioner,
    store: LauncherStore,
    folderName: String,
) {
    context.onFinished(
        "install-versions:$instanceId",
        cancel = {
            runCatching {
                provisioner.reclaimCreatedVersionDirectories(createdVersions, instanceId, context)
            }
            runCatching { store.endPendingInstall(folderName) }
        },
        complete = {},
    )
}

/** 安装失败时回收中间目录、结束安装中标记，并按需放弃新实例。 */
private suspend fun rollbackFailedInstall(
    provisioner: InstanceProvisioner,
    store: LauncherStore,
    instance: GameInstance,
    instanceId: String,
    folderName: String,
    freshTarget: Boolean,
    discardOnFailure: Boolean,
    createdVersions: Set<Path>,
    context: JobContext,
) {
    provisioner.reclaimCreatedVersionDirectories(createdVersions, instanceId, context)
    store.endPendingInstall(folderName)
    if (freshTarget && discardOnFailure) {
        provisioner.discardFailedFreshInstall(instance, folderName)
    }
}

/** 安装流水线：把一次安装拆成可回收的步骤，任何一步失败都按原有顺序清理。 */
internal class MinecraftInstallPipeline(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val catalog: MinecraftVersionCatalog,
    private val loaders: LoaderProfileFetcher,
    private val provisioner: InstanceProvisioner,
    private val publisher: InstanceProfilePublisher,
    private val assetBaseUrl: String,
) {
    suspend fun install(
        instanceId: String,
        context: JobContext,
        freshInstall: Boolean = false,
        progressEnd: Float = 1f,
        discardOnFailure: Boolean = true,
        refreshAfterInstall: Boolean = true,
    ) {
        require(progressEnd in 0f..1f) { I18n.t("miscA2.install.invalidProgressEnd") }
        val instance = store.instance(instanceId)
        val folderName = instance.instanceFolderName()
        val freshTarget = freshInstall && folderName.isNotBlank()
        beginInstallation(instance, instanceId, context)
        // 本任务新建的中间版本目录（基础版本目录等）：成功、失败和取消都只回收这些。
        val createdVersions = linkedSetOf<Path>()
        registerInstallCleanup(
            context,
            instanceId,
            createdVersions,
            provisioner,
            store,
            folderName,
        )
        context.progress(0f, I18n.t("miscA2.install.fetchingMetadata"))
        try {
            installVersionContent(instance, createdVersions, freshInstall, context, progressEnd)
            store.updateInstance(instanceId) { it.copy(installed = true) }
            store.endPendingInstall(folderName)
        } catch (expected: Throwable) {
            // 失败不保留中间目录，否则重试时会把它们当成「已有基础版本」永久留下。
            if (expected !is CancellationException) {
                rollbackFailedInstall(
                    provisioner,
                    store,
                    instance,
                    instanceId,
                    folderName,
                    freshTarget,
                    discardOnFailure,
                    createdVersions,
                    context,
                )
            }
            throw expected
        }
        provisioner.reclaimCreatedVersionDirectories(createdVersions, instanceId, context)
        // 目录里已有有效配置、中间目录也回收完毕：重新扫描让实例按确定性 id、真实版本和加载器收敛。
        if (refreshAfterInstall) store.refreshInstances()
        context.progress(progressEnd, I18n.t("settings.java.install.done"))
    }

    private fun beginInstallation(
        instance: GameInstance,
        instanceId: String,
        context: JobContext,
    ) {
        if (instance.installed) {
            context.onFinished(
                "installation-status:$instanceId",
                cancel = { store.updateInstance(instanceId) { it.copy(installed = true) } },
                complete = {},
            )
        }
        store.updateInstance(instanceId) { it.copy(installed = false) }
    }

    /** 版本文件本身：外部版本直接补齐依赖，受管版本走完整的合成流程。 */
    private suspend fun installVersionContent(
        instance: GameInstance,
        createdVersions: MutableSet<Path>,
        freshInstall: Boolean,
        context: JobContext,
        progressEnd: Float,
    ) {
        val root = store.minecraftRoot(instance)
        if (instance.externalProfile) {
            val resolved = VersionResolver(root, store.platform).resolve(instance.versionId)
            installDependencies(resolved, context, root, 0f, progressEnd)
        } else {
            installManagedVersion(
                instance,
                root,
                createdVersions,
                freshInstall,
                context,
                progressEnd,
            )
        }
        context.checkCancelled()
        // 扫描到的外部版本仍只写一个指向原目录的配置，不复制、不改写原版本。
        if (instance.externalProfile) rebuildExternalProfile(instance, context)
    }

    private suspend fun installManagedVersion(
        instance: GameInstance,
        root: Path,
        createdVersions: MutableSet<Path>,
        freshInstall: Boolean,
        context: JobContext,
        progressEnd: Float,
    ) {
        val metadata =
            catalog.versions().find { it.id == instance.gameVersion }
                ?: error(I18n.t("miscA2.install.versionNotInCatalog"))
        val ancient = metadata.type in setOf("old_alpha", "old_beta")
        require(!ancient || instance.loader == LoaderType.Vanilla) {
            I18n.t("miscA2.install.ancientVersionVanillaOnly")
        }
        val target = store.versionGamePath(instance)
        if (freshInstall) provisioner.requireNewInstanceDirectory(root, target)
        val base =
            if (freshInstall) {
                prepareDirectBaseProfile(network, metadata, target)
            } else {
                prepareBaseProfile(metadata, instance, target, root, createdVersions, context)
            }
        val hasLoader = instance.loader != LoaderType.Vanilla
        // 加载器安装阶段要留出预算：否则基础依赖做完进度条已经到 98%，
        // 之后的加载器安装会让进度停在原地（或回退）。
        installDependencies(
            base.profile,
            context,
            root,
            BASE_DEPENDENCIES_PROGRESS_START * progressEnd,
            (if (hasLoader) LOADER_PROGRESS_START else SKIPPED_LOADER_PROGRESS) * progressEnd,
        )
        installLoaderAndPublish(
            instance = instance,
            base = base.profile,
            target = target,
            root = root,
            selfContained = base.selfContained,
            hasLoader = hasLoader,
            context = context,
            progressEnd = progressEnd,
        )
    }

    /**
     * 下载基础版本 JSON 并解析，同时记录新建的目录。
     *
     * 基础版本目录本来就存在（或另一个实例在用）时保留它，实例用 `inheritsFrom` 引用；
     * 只有本任务新建的基础目录才会在发布后回收，所以这里必须记录「之前是否存在」。
     */
    private suspend fun prepareBaseProfile(
        metadata: ReleaseVersion,
        instance: GameInstance,
        target: Path,
        root: Path,
        createdVersions: MutableSet<Path>,
        context: JobContext,
    ): BaseProfile {
        val baseId = safeFilename(metadata.id)
        val baseFolder = safePath(root.resolve("versions"), baseId)
        val baseExisted = baseFolder.exists(NOFOLLOW_LINKS)
        if (!baseExisted) {
            createdVersions.add(baseFolder)
            context.beforeDirectoryCreation(baseFolder)
        }
        val sharesBaseDirectory = taskPathKey(target) == taskPathKey(baseFolder)
        val hasLoader = instance.loader != LoaderType.Vanilla
        require(!(sharesBaseDirectory && baseExisted && hasLoader)) {
            I18n.t("miscA2.install.sharedBaseDirectory", baseId)
        }
        val jsonPath = baseFolder.resolve("$baseId.json")
        network.download(DownloadArtifact(metadata.url, jsonPath, metadata.sha1.orEmpty()))
        val base = VersionResolver(root, store.platform).resolve(baseId)
        require(base.mainClass.isNotBlank()) { I18n.t("miscA2.install.unsupportedVersion") }
        return BaseProfile(base, !baseExisted || sharesBaseDirectory)
    }

    /** 取加载器配置、发布目标配置，再补齐发布后才知道的依赖。 */
    private suspend fun installLoaderAndPublish(
        instance: GameInstance,
        base: ResolvedProfile,
        target: Path,
        root: Path,
        selfContained: Boolean,
        hasLoader: Boolean,
        context: JobContext,
        progressEnd: Float,
    ) {
        context.checkCancelled()
        val loader = fetchLoaderProfile(instance, base, context)
        context.checkCancelled()
        // 发布：目标目录里一份完整配置，不再留下指向兄弟目录的存根。
        publisher.publishInstanceProfile(
            instance = instance,
            base = base,
            loader = loader,
            target = target,
            root = root,
            selfContained = selfContained,
            context = context,
        )
        val versionId = target.fileName.toString()
        store.updateInstance(instance.id) { it.copy(versionId = versionId) }
        context.checkCancelled()
        installDependencies(
            VersionResolver(root, store.platform).resolve(versionId),
            context,
            root,
            (if (hasLoader) LOADER_PROGRESS_END else SKIPPED_LOADER_PROGRESS) * progressEnd,
            FINAL_DEPENDENCIES_PROGRESS_END * progressEnd,
        )
    }

    private suspend fun fetchLoaderProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
    ): JsonObject? =
        when (instance.loader) {
            LoaderType.Vanilla -> null
            LoaderType.Fabric,
            LoaderType.Quilt,
            LoaderType.LegacyFabric,
            -> loaders.fetchFabricProfile(instance, context, LOADER_PROGRESS_START, LOADER_PROGRESS_END)
            LoaderType.OptiFine ->
                loaders.optiFine.fetchProfile(
                    instance,
                    base,
                    context,
                    LOADER_PROGRESS_START,
                    LOADER_PROGRESS_END,
                )
            LoaderType.LiteLoader ->
                loaders.liteLoader.fetchProfile(
                    instance,
                    context,
                    LOADER_PROGRESS_START,
                    LOADER_PROGRESS_END,
                )
            LoaderType.Forge,
            LoaderType.NeoForge,
            LoaderType.Cleanroom,
            -> loaders.fetchForgeProfile(instance, base, context, LOADER_PROGRESS_START, LOADER_PROGRESS_END)
        }

    /** 外部版本目录里缺配置时补一份指向原目录的存根，不复制、不改写原版本。 */
    private suspend fun rebuildExternalProfile(
        instance: GameInstance,
        context: JobContext,
    ) {
        val current = store.instance(instance.id)
        val folder = store.versionGamePath(current)
        val name = folder.fileName.toString()
        val file = folder.resolve("$name.json")
        if (
            name != current.versionId &&
            name != current.gameVersion &&
            !file.isRegularFile(NOFOLLOW_LINKS)
        ) {
            context.writeOutput(
                file,
            ) { VersionGameDirectories(store).writeProfile(current) }
        }
    }

    /**
     * 补齐一个版本的库、客户端、资源与索引。
     *
     * 进度在**开始前**按「已知文件数 + 字节数」登记（资源索引先取，资源数量不再是未知数），
     * 因此进度条的分母只算一次，不会出现「下到一半总量翻倍」或「只推进一个阶段」。
     * 顺序保持 版本 JSON → 库 → 资源；并发只发生在同一个阶段内部。
     */
    suspend fun installDependencies(
        profile: ResolvedProfile,
        context: JobContext,
        root: Path,
        from: Float = 0f,
        to: Float = 1f,
        verifyHashes: Boolean = true,
    ) {
        val progress = InstallProgress(context, from, to)
        val verifier =
            GameFileVerifier(network, store.data.settings.downloadThreads, verifyHashes, progress)
        progress.begin(InstallPhase.VERIFY)
        val resolver = VersionResolver(root, store.platform)
        val artifacts =
            resolver
                .libraries(profile)
                .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
                .toMutableList()
        artifacts += ensureClientArtifact(profile, root, verifyHashes)
        val optional = optionalLoggingArtifacts(profile, root)
        val indexPath = prepareAssetIndex(profile, root, verifier, context)
        val assets = indexPath?.let { assetArtifacts(root, it, assetBaseUrl) } ?: emptyList()
        // 资源数量与总字节现在已知：把库和资源的校验 + 下载总量一次登记进进度模型，
        // 再按 库 → 资源 的顺序校验并下载。分母固定后阶段之间不会假收尾。
        verifier.reserve(InstallPhase.LIBRARIES, artifacts)
        verifier.reserve(InstallPhase.ASSETS, assets)
        verifier.ensure(
            artifacts,
            context,
            InstallPhase.LIBRARIES,
            optional = optional,
            warn = { store.log(it, level = LogLevel.Warn) },
        )
        verifier.ensure(assets, context, InstallPhase.ASSETS)
        progress.finish()
    }

    /** 客户端 JAR 的下载描述：现有文件完好时不需要地址，缺失地址才回查真实版本 JSON。 */
    private suspend fun ensureClientArtifact(
        profile: ResolvedProfile,
        root: Path,
        verifyHashes: Boolean,
    ): DownloadArtifact {
        val client = profile.json.obj("downloads").obj("client")
        val clientPath = root.resolve("versions/${profile.jarId}/${profile.jarId}.jar")
        var clientArtifact =
            DownloadArtifact(
                client.string("url"),
                clientPath,
                client.string("sha1"),
                client.get("size")?.asLong ?: 0,
            )
        // 现有客户端 JAR 完好时不需要地址（外部/整合包版本常常没有 downloads 段）；
        // 只有确实要下载且 JSON 没给地址时，才回查真实版本 JSON。
        if (
            clientArtifact.url.isBlank() &&
            artifactProblem(
                if (verifyHashes) clientArtifact else clientArtifact.copy(sha1 = ""),
            ) != null
        ) {
            clientArtifact = clientArtifact.copy(url = publisher.clientJarUrl(profile, root))
        }
        return clientArtifact
    }

    /** 资源索引先下载：资源文件的数量与总字节是进度分母的一部分。 */
    private suspend fun prepareAssetIndex(
        profile: ResolvedProfile,
        root: Path,
        verifier: GameFileVerifier,
        context: JobContext,
    ): Path? {
        val index = profile.json.obj("assetIndex")
        if (index.string("url").isBlank()) return null
        val indexPath = safePath(root.resolve("assets/indexes"), "${index.string("id")}.json")
        verifier.ensure(
            listOf(
                DownloadArtifact(
                    index.string("url"),
                    indexPath,
                    index.string("sha1"),
                    index.get("size")?.asLong ?: 0,
                ),
            ),
            context,
            InstallPhase.LIBRARIES,
        )
        return indexPath
    }
}

/** 资源对象按哈希分桶存放：前缀目录是哈希前两位。 */
private fun assetArtifacts(
    root: Path,
    indexPath: Path,
    assetBaseUrl: String,
): List<DownloadArtifact> {
    val objects = readObject(indexPath).obj("objects")
    return objects.entrySet().map { (_, value) ->
        val item = value.asJsonObject
        val hash = item.string("hash")
        require(hash.matches(Regex("[0-9a-fA-F]{40}"))) { I18n.t("miscA2.install.invalidAssetHash") }
        DownloadArtifact(
            "$assetBaseUrl/${hash.take(2)}/$hash",
            root.resolve("assets/objects/${hash.take(2)}/$hash"),
            hash,
            item.get("size")?.asLong ?: 0,
        )
    }
}

/** 日志配置是可选文件：缺失只影响日志格式，不能中止整个安装。 */
private fun optionalLoggingArtifacts(
    profile: ResolvedProfile,
    root: Path,
): List<DownloadArtifact> {
    val logging =
        profile.json
            .obj("logging")
            .obj("client")
            .obj("file")
    val id = logging.string("id")
    if (logging.string("url").isBlank() || id.isBlank()) return emptyList()
    return listOf(
        DownloadArtifact(
            logging.string("url"),
            safePath(root.resolve("assets/log_configs"), id),
            logging.string("sha1"),
        ),
    )
}
