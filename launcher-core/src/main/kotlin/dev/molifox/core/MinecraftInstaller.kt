// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责原版游戏和模组加载器的安装维护。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader
import kotlin.io.path.useLines
import kotlin.io.path.walk

internal fun encoded(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

/** 版本列表源：official 使用 Mojang 官方清单，bmclapi 使用国内镜像；独立于文件下载源。 */
internal fun versionManifestUrl(source: String): String =
    if (source == "bmclapi") {
        "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    } else {
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

/** 本启动器生成的版本配置标记：扫描器据此把目录当作受管实例（可修复、可升级加载器）。 */
internal const val MANAGED_PROFILE_MARKER = "molifoxManaged"

class MinecraftInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val java: JavaService,
    // official 端点可注入以便测试；bmclapi 分支固定使用镜像地址。
    private val manifestUrl: String = versionManifestUrl("official"),
    private val assetBaseUrl: String = "https://resources.download.minecraft.net",
    // 加载器元数据端点同样可注入：安装流程会先合成完整配置再落盘，离线测试用本地服务验证这条路径。
    private val fabricMetaBase: String = "https://meta.fabricmc.net",
    private val quiltMetaBase: String = "https://meta.quiltmc.org",
    // Forge 系加载器列表用的 Maven 根地址；默认就是官方地址，测试可指向本地服务。
    private val forgeMavenBase: String = "https://maven.minecraftforge.net",
    private val neoforgeMavenBase: String = "https://maven.neoforged.net",
) {
    @Volatile private var versionsCache: List<ReleaseVersion> = emptyList()

    suspend fun versions(refresh: Boolean = false): List<ReleaseVersion> {
        if (!refresh && versionsCache.isNotEmpty()) return versionsCache
        val cache = store.metadataDirectory.resolve("versions.json")
        val json =
            try {
                network
                    .json(
                        if (store.data.settings.versionListSource == "bmclapi") {
                            versionManifestUrl("bmclapi")
                        } else {
                            manifestUrl
                        },
                        sourceOverride = store.data.settings.versionListSource,
                    ).asJsonObject
                    .also { writeJson(cache, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (cache.exists()) {
                    store.log("网络不可用，使用缓存的游戏版本列表")
                    readObject(cache)
                } else {
                    throw error
                }
            }
        return json
            .array("versions")
            .map { gson.fromJson(it, ReleaseVersion::class.java) }
            .also { versionsCache = it }
    }

    suspend fun loaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> {
        safeFilename(gameVersion)
        return when (loader) {
            LoaderType.Vanilla -> emptyList()
            LoaderType.Fabric,
            LoaderType.Quilt,
            -> {
                val host =
                    if (loader == LoaderType.Fabric) fabricMetaBase else quiltMetaBase
                val apiVersion = if (loader == LoaderType.Fabric) "v2" else "v3"
                network
                    .json("$host/$apiVersion/versions/loader/${encoded(gameVersion)}")
                    .asJsonArray
                    .map { item ->
                        val info = item.asJsonObject.obj("loader")
                        LoaderVersion(
                            info.string("version"),
                            info.boolean(
                                "stable",
                                !Regex("(?i)alpha|beta|rc|snapshot")
                                    .containsMatchIn(info.string("version")),
                            ),
                        )
                    }.sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
            LoaderType.Forge -> {
                val xml =
                    network.text(
                        "$forgeMavenBase/net/minecraftforge/forge/maven-metadata.xml",
                    )
                Regex("<version>([^<]+)</version>")
                    .findAll(xml)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("$gameVersion-") }
                    .map { LoaderVersion(it.removePrefix("$gameVersion-")) }
                    .toList()
                    .sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
            LoaderType.NeoForge -> {
                val legacy = gameVersion == "1.20.1"
                val project = if (legacy) "forge" else "neoforge"
                val parts = gameVersion.split('.')
                val prefix =
                    if (legacy) {
                        "1.20.1-"
                    } else if (parts.first() == "1") {
                        "${parts.getOrElse(1) { "0" }}.${parts.getOrElse(2) { "0" }}."
                    } else {
                        "${parts[0]}.${parts.getOrElse(1) { "0" }}."
                    }
                network
                    .json(
                        "$neoforgeMavenBase/api/maven/versions/releases/net/neoforged/$project",
                    ).asJsonObject
                    .array("versions")
                    .map { it.asString }
                    .filter { it.startsWith(prefix) }
                    .map { LoaderVersion(it, !it.contains("beta") && !it.contains("alpha")) }
                    .sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
        }
    }

    fun validate(request: InstallRequest) {
        require(request.name.isNotBlank() && request.name.length <= 80) { "实例名称需要 1–80 个字符" }
        safeFilename(request.gameVersion)
        if (request.loader != LoaderType.Vanilla) {
            require(request.loaderVersion.isNotBlank()) { "请选择加载器版本" }
            safeFilename(request.loaderVersion)
        }
        // 显示名就是目录名：系统保留名等无法落盘的名字在动磁盘前就报错。
        instanceDirectoryName(request.name.trim())
    }

    /**
     * 登记一个新实例：先确定唯一的实际目录名，目录已存在时直接报错。
     *
     * 不再自动生成 `-2` 之类的第二个目录，也不再让显示名与目录名分叉；安装期间目录登记为
     * 「安装中」，扫描不会把还没有 JSON 的空目录显示成无法识别的实例。
     */
    @Synchronized
    fun createInstance(
        request: InstallRequest,
        gameDirectoryId: String? = store.data.activeGameDirectoryId,
    ): GameInstance {
        validate(request)
        val root =
            gameDirectoryId?.let { id ->
                Path.of(
                    store.data.gameDirectories
                        .first { it.id == id }
                        .path,
                )
            } ?: store.minecraftRoot
        val directoryName = instanceDirectoryName(request.name.trim())
        val folder = safePath(root.resolve("versions"), directoryName)
        require(!folder.exists(NOFOLLOW_LINKS)) {
            "versions/$directoryName 已存在，请换一个实例名称或先处理该目录"
        }
        val windows = store.platform.osName == "windows"
        store.data.instances
            .firstOrNull {
                taskPathKey(store.minecraftRoot(it)) == taskPathKey(root) &&
                    it.instanceFolderName().equals(directoryName, windows)
            }?.let { clash ->
                throw IllegalArgumentException("已有实例「${clash.name}」使用这个名称")
            }
        // 身份就是目录名：id 由目录名和游戏根算出来，和扫描结果是同一个。
        val id = store.instanceIdFor(directoryName, gameDirectoryId)
        val loaderId =
            if (request.loader == LoaderType.Vanilla) {
                ""
            } else {
                "${request.loader.name.lowercase()}-${request.loaderVersion}"
            }
        store.beginPendingInstall(directoryName, request.gameVersion, loaderId)
        try {
            folder.createDirectories()
            val instance =
                GameInstance(
                    id = id,
                    name = directoryName,
                    gameVersion = request.gameVersion,
                    versionId = directoryName,
                    gameDirectoryName = directoryName,
                    loader = request.loader,
                    loaderVersion = request.loaderVersion,
                    gameDirectoryId = gameDirectoryId,
                    managedGameDirectory = true,
                )
            store.mutate {
                it.copy(
                    instances = it.instances + instance,
                    selectedInstanceId =
                        if (it.activeGameDirectoryId == gameDirectoryId) {
                            instance.id
                        } else {
                            it.selectedInstanceId
                        },
                )
            }
            return store.instance(id)
        } catch (failure: Throwable) {
            // 登记失败不留空目录，也不留着「安装中」标记。
            store.endPendingInstall(directoryName)
            runCatching {
                if (folder.isDirectory(NOFOLLOW_LINKS) && folder.listDirectoryEntries().isEmpty()) {
                    Files.deleteIfExists(folder)
                }
            }
            throw failure
        }
    }

    suspend fun install(
        instanceId: String,
        context: JobContext,
        freshInstall: Boolean = false,
    ) {
        val instance = store.instance(instanceId)
        val root = store.minecraftRoot(instance)
        val folderName = instance.instanceFolderName()
        val freshTarget = freshInstall && folderName.isNotBlank()
        if (instance.installed) {
            context.onFinished(
                "installation-status:$instanceId",
                cancel = { store.updateInstance(instanceId) { it.copy(installed = true) } },
                complete = {},
            )
        }
        store.updateInstance(instanceId) { it.copy(installed = false) }
        // 本任务新建的中间版本目录（基础版本目录等）：成功、失败和取消都只回收这些。
        val createdVersions = linkedSetOf<Path>()
        context.onFinished(
            "install-versions:$instanceId",
            cancel = {
                runCatching {
                    reclaimCreatedVersionDirectories(
                        createdVersions,
                        instanceId,
                        context,
                    )
                }
                runCatching { store.endPendingInstall(folderName) }
            },
            complete = {},
        )
        context.progress(0f, "获取 Minecraft 元数据…")
        try {
            if (instance.externalProfile) {
                val resolved = VersionResolver(root, store.platform).resolve(instance.versionId)
                installDependencies(resolved, context, root)
            } else {
                val metadata =
                    versions().find { it.id == instance.gameVersion } ?: error("此游戏版本不存在")
                require(metadata.type !in setOf("old_alpha", "old_beta")) {
                    "当前支持 Minecraft 1.6 及以后的版本，早期版本仍需适配"
                }
                val target = store.versionGamePath(instance)
                if (freshInstall) requireNewInstanceDirectory(root, target)
                val baseId = safeFilename(metadata.id)
                val baseFolder = safePath(root.resolve("versions"), baseId)
                // 基础版本目录本来就存在（或另一个实例在用）时保留它，实例用 inheritsFrom 引用；
                // 只有本任务新建的基础目录才会在发布后回收，所以这里必须记录「之前是否存在」。
                val baseExisted = baseFolder.exists(NOFOLLOW_LINKS)
                if (!baseExisted) {
                    createdVersions.add(baseFolder)
                    context.beforeDirectoryCreation(baseFolder)
                }
                val sharesBaseDirectory = taskPathKey(target) == taskPathKey(baseFolder)
                val hasLoader = instance.loader != LoaderType.Vanilla
                require(!(sharesBaseDirectory && baseExisted && hasLoader)) {
                    "这个实例直接使用原版版本目录 versions/$baseId，加装加载器会改坏共用配置；请先重命名实例或换一个名称"
                }
                val jsonPath = baseFolder.resolve("$baseId.json")
                network.download(DownloadArtifact(metadata.url, jsonPath, metadata.sha1.orEmpty()))
                val base = VersionResolver(root, store.platform).resolve(baseId)
                require(base.mainClass.isNotBlank()) { "此游戏版本尚未支持" }
                // 加载器安装阶段要留出预算：否则基础依赖做完进度条已经到 98%，
                // 之后的加载器安装会让进度停在原地（或回退）。
                installDependencies(base, context, root, .02f, if (hasLoader) .70f else .90f)
                context.checkCancelled()
                val loader =
                    when (instance.loader) {
                        LoaderType.Vanilla -> null
                        LoaderType.Fabric,
                        LoaderType.Quilt,
                        -> fetchFabricProfile(instance, context, .70f, .96f)
                        LoaderType.Forge,
                        LoaderType.NeoForge,
                        ->
                            fetchForgeProfile(
                                instance,
                                base,
                                context,
                                .70f,
                                .96f,
                            )
                    }
                context.checkCancelled()
                // 发布：目标目录里一份完整配置，不再留下指向兄弟目录的存根。
                publishInstanceProfile(
                    instance = instance,
                    base = base,
                    loader = loader,
                    target = target,
                    root = root,
                    selfContained = !baseExisted || sharesBaseDirectory,
                    context = context,
                )
                val versionId = target.fileName.toString()
                store.updateInstance(instanceId) { it.copy(versionId = versionId) }
                context.checkCancelled()
                installDependencies(
                    VersionResolver(root, store.platform).resolve(versionId),
                    context,
                    root,
                    if (hasLoader) .96f else .90f,
                    .99f,
                )
            }
            context.checkCancelled()
            // 扫描到的外部版本仍只写一个指向原目录的配置，不复制、不改写原版本。
            if (instance.externalProfile) {
                val current = store.instance(instanceId)
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
            store.updateInstance(instanceId) { it.copy(installed = true) }
            store.endPendingInstall(folderName)
        } catch (failure: Throwable) {
            // 失败不保留中间目录，否则重试时会把它们当成「已有基础版本」永久留下。
            if (failure !is CancellationException) {
                reclaimCreatedVersionDirectories(createdVersions, instanceId, context)
            }
            if (failure !is CancellationException) {
                store.endPendingInstall(folderName)
                if (freshTarget) discardFailedFreshInstall(instance, folderName)
            }
            throw failure
        }
        reclaimCreatedVersionDirectories(createdVersions, instanceId, context)
        // 目录里已有有效配置、中间目录也回收完毕：重新扫描让实例按确定性 id、真实版本和加载器收敛。
        store.refreshInstances()
        context.progress(1f, "安装完成")
    }

    /** 新安装失败后不留下空目录和记录：它下次会被扫描成「无法识别」的实例。 */
    private fun discardFailedFreshInstall(
        instance: GameInstance,
        folderName: String,
    ) {
        runCatching {
            val folder = store.versionGamePath(instance)
            if (folder.isDirectory(NOFOLLOW_LINKS) && folder.listDirectoryEntries().isEmpty()) {
                Files.deleteIfExists(folder)
            }
        }
        runCatching {
            store.mutate { data ->
                data.copy(
                    instances = data.instances.filter { it.instanceSettingsKey() != folderName },
                    selectedInstanceId =
                        if (data.selectedInstanceId == instance.id) {
                            null
                        } else {
                            data.selectedInstanceId
                        },
                )
            }
        }
    }

    /**
     * 把已安装实例升级到**同一加载器**的更新版本：游戏版本、实例目录名与 `molifoxManaged` 标记都不变。
     *
     * 先备份当前版本配置，再用「真实基础版本 + 新加载器配置」合成一份自包含 JSON 写回原实例目录，
     * 模组、存档与游戏配置都不动；任何一步失败都恢复备份的版本配置和原来的加载器版本。
     */
    suspend fun upgradeLoader(
        instanceId: String,
        loaderVersion: String,
        context: JobContext,
    ) {
        val current = store.instance(instanceId)
        require(current.installed) { "请先完成游戏安装，再升级加载器" }
        require(current.loader != LoaderType.Vanilla) { "原版实例没有可升级的加载器" }
        require(current.packFile.isBlank()) { "整合包的加载器由包清单维护，请通过整合包更新维护" }
        require(current.gameVersion.isNotBlank() && current.versionId.isNotBlank()) {
            "实例缺少游戏版本标识，请先重新识别游戏目录"
        }
        safeFilename(loaderVersion)
        require(compareLoaderVersions(loaderVersion, current.loaderVersion) > 0) {
            "只能升级到 ${current.loader.title} 的更新版本"
        }
        // 外部实例属于原启动器：合并进原 JSON，写前做时间戳备份，失败按字节恢复。
        if (current.externalProfile) {
            upgradeExternalLoader(current, loaderVersion, context)
            return
        }
        val backups = ComponentBackup(store)
        val backupId = backups.capture(current)
        val target = current.copy(loaderVersion = loaderVersion)
        try {
            store.updateInstance(instanceId) {
                it.copy(loaderVersion = loaderVersion, installed = false)
            }
            val root = store.minecraftRoot(current)
            val base = baseProfileForUpgrade(current, context)
            val loader =
                when (current.loader) {
                    LoaderType.Vanilla -> error("原版实例没有可升级的加载器")
                    LoaderType.Fabric,
                    LoaderType.Quilt,
                    -> fetchFabricProfile(target, context, .05f, .90f)
                    LoaderType.Forge,
                    LoaderType.NeoForge,
                    -> fetchForgeProfile(target, base, context, .05f, .90f)
                }
            context.checkCancelled()
            val folder = store.versionGamePath(current)
            publishInstanceProfile(
                instance = target,
                base = base,
                loader = loader,
                target = folder,
                root = root,
                selfContained = true,
                context = context,
            )
            context.checkCancelled()
            installDependencies(
                VersionResolver(root, store.platform).resolve(folder.fileName.toString()),
                context,
                root,
                .90f,
                .99f,
            )
            context.checkCancelled()
            store.updateInstance(instanceId) {
                it.copy(loaderVersion = loaderVersion, installed = true)
            }
            store.refreshInstances()
            backups.discard(backupId)
            context.progress(1f, "${current.loader.title} 已升级到 $loaderVersion")
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    backups.restore(backupId, current)
                    backups.discard(backupId)
                    store.updateInstance(instanceId) {
                        it.copy(
                            loaderVersion = current.loaderVersion,
                            installed = current.installed,
                        )
                    }
                    store.refreshInstances()
                }.onFailure {
                    store.log("升级加载器失败后恢复原版本配置也失败：${it.message}", true)
                }
            }
            throw failure
        }
    }

    /**
     * 外部实例升级前的结构预检：返回不能安全合并的具体原因，null 表示可以升级。
     *
     * 只读：不下载、不写文件。外部 JSON 继承的基础版本在本地缺失时直接说明原因，
     * 界面据此不显示升级按钮。
     */
    fun externalLoaderUpgradeBlocker(instance: GameInstance): String? {
        if (!instance.externalProfile) return null
        val folder =
            runCatching { store.versionGamePath(instance) }
                .getOrElse { return "实例目录不可用：${it.message}" }
        val json = folder.resolve("${folder.fileName}.json")
        if (!json.isRegularFile(NOFOLLOW_LINKS) || json.isSymbolicLink()) {
            return "外部版本配置缺失或不是普通文件：$json"
        }
        val root =
            runCatching { store.minecraftRoot(instance) }.getOrElse { return it.message }
        return try {
            val original = readObject(json)
            val parent = original.string("inheritsFrom").takeIf { it.isNotBlank() }
            if (parent != null) {
                if (parent == folder.fileName.toString()) {
                    return "版本配置继承了自身（$parent），无法安全合并。"
                }
                val baseFile = root.resolve("versions").resolve(parent).resolve("$parent.json")
                if (!baseFile.isRegularFile(NOFOLLOW_LINKS)) {
                    return "它继承的基础版本 $parent 在本地不存在；请先在原目录补齐该版本，再进行升级。"
                }
                // 校验整条 inheritsFrom 链，断链或循环都给出可读原因。
                VersionResolver(root, store.platform).resolve(parent)
            } else if (
                original.string("mainClass").isBlank() && original.array("libraries").isEmpty()
            ) {
                return "版本配置缺少 mainClass 与 libraries，无法安全合并。"
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "外部版本配置无法安全合并：${error.message}"
        }
    }

    /**
     * 升级外部目录实例的加载器：加载器配置合并进原版本 JSON，保留 `id`、`inheritsFrom`
     * 链和其它字段，不写 `molifoxManaged`；写盘前把原 JSON 复制成
     * `<名字>.json.molifox-backup-<yyyyMMdd-HHmmss>`，失败时按字节恢复。
     */
    private suspend fun upgradeExternalLoader(
        current: GameInstance,
        loaderVersion: String,
        context: JobContext,
    ) {
        val folder = store.versionGamePath(current)
        val jsonPath = folder.resolve("${folder.fileName}.json")
        val root = store.minecraftRoot(current)
        val blocker = externalLoaderUpgradeBlocker(current)
        require(blocker == null) { blocker.orEmpty() }
        val original = readObject(jsonPath)
        val parent = original.string("inheritsFrom").takeIf { it.isNotBlank() }
        val base =
            if (parent != null) {
                VersionResolver(root, store.platform).resolve(parent)
            } else {
                VersionResolver(root, store.platform).resolve(current.versionId)
            }
        val target = current.copy(loaderVersion = loaderVersion)
        val loader =
            when (current.loader) {
                LoaderType.Vanilla -> error("原版实例没有可升级的加载器")
                LoaderType.Fabric,
                LoaderType.Quilt,
                -> fetchFabricProfile(target, context, .05f, .60f)
                LoaderType.Forge,
                LoaderType.NeoForge,
                -> fetchForgeProfile(target, base, context, .05f, .60f)
            }
        val merged = mergeExternalLoaderProfile(original, loader)
        context.checkCancelled()
        // 备份不是任务产物：取消/失败都不删除，留在原 JSON 旁供用户核对或手动回退。
        val backup = backupExternalProfile(jsonPath)
        try {
            context.writeOutput(jsonPath) { writeJson(jsonPath, merged) }
            context.checkCancelled()
            installDependencies(
                VersionResolver(root, store.platform).resolve(folder.fileName.toString()),
                context,
                root,
                .60f,
                .99f,
            )
            context.checkCancelled()
            store.updateInstance(current.id) {
                it.copy(loaderVersion = loaderVersion, installed = true)
            }
            store.refreshInstances()
            context.progress(1f, "${current.loader.title} 已升级到 $loaderVersion（原配置已备份）")
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    Files.copy(
                        backup,
                        jsonPath,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    // 只恢复这一个版本 JSON，其它文件与备份都保留。
                    store.refreshInstances()
                }.onFailure {
                    store.log(
                        "外部版本升级失败后恢复备份也失败：${it.message}；备份文件：$backup",
                        true,
                    )
                }
            }
            throw failure
        }
    }

    /** 把外部版本 JSON 原样复制到同目录的时间戳备份；同一秒内重名时追加序号。 */
    private fun backupExternalProfile(jsonPath: Path): Path {
        require(jsonPath.isRegularFile(NOFOLLOW_LINKS) && !jsonPath.isSymbolicLink()) {
            "外部版本配置缺失或不是普通文件：$jsonPath"
        }
        val stamp =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        var backup = jsonPath.resolveSibling("${jsonPath.fileName}.molifox-backup-$stamp")
        var index = 1
        while (backup.exists(NOFOLLOW_LINKS)) {
            backup =
                jsonPath.resolveSibling("${jsonPath.fileName}.molifox-backup-$stamp-$index")
            index++
        }
        Files.copy(jsonPath, backup)
        store.log("外部版本配置已备份：$backup")
        return backup
    }

    /** 升级合成配置用的基础版本：优先用本机已有的版本配置，缺失时按版本清单取官方 JSON。 */
    private suspend fun baseProfileForUpgrade(
        instance: GameInstance,
        context: JobContext,
    ): ResolvedProfile {
        val root = store.minecraftRoot(instance)
        val baseId = safeFilename(instance.gameVersion)
        if (root.resolve("versions/$baseId/$baseId.json").isRegularFile(NOFOLLOW_LINKS)) {
            return VersionResolver(root, store.platform).resolve(baseId)
        }
        context.progress(.02f, "获取 Minecraft ${instance.gameVersion} 元数据…")
        context.checkCancelled()
        val metadata =
            versions().find { it.id == instance.gameVersion } ?: error("此游戏版本不存在")
        val json = network.json(metadata.url).asJsonObject
        require(json.string("mainClass").isNotBlank()) { "此游戏版本尚未支持" }
        return ResolvedProfile(baseId, baseId, json)
    }

    suspend fun resolveForLaunch(
        instance: GameInstance,
        context: JobContext,
    ): ResolvedProfile {
        val resolver = VersionResolver(store.minecraftRoot(instance), store.platform)
        try {
            return resolver.resolve(instance.versionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (instance.externalProfile) {
                throw IOException("外部版本配置缺失或损坏，请修复原版本：${instance.versionId}", error)
            }
            context.progress(0f, "恢复游戏版本配置…")
            install(instance.id, context)
            return resolver.resolve(store.instance(instance.id).versionId)
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
        root: Path = store.minecraftRoot,
        from: Float = 0f,
        to: Float = 1f,
        verifyHashes: Boolean = true,
    ) {
        val progress = InstallProgress(context, from, to)
        val verifier =
            GameFileVerifier(network, store.data.settings.maxConcurrent, verifyHashes, progress)
        progress.begin(InstallPhase.VERIFY)
        val resolver = VersionResolver(root, store.platform)
        val artifacts =
            resolver
                .libraries(profile)
                .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
                .toMutableList()
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
            ) !=
            null
        ) {
            clientArtifact = clientArtifact.copy(url = clientJarUrl(profile, root))
        }
        artifacts += clientArtifact
        // 日志配置是可选文件：老版本或镜像缺这个对象时不能中止整个安装。
        // 它只在启动时被定位，不属于安装必需文件；缺失只影响日志格式。
        val optional =
            profile.json
                .obj("logging")
                .obj("client")
                .obj("file")
                .let { logging ->
                    val id = logging.string("id")
                    if (logging.string("url").isNotBlank() && id.isNotBlank()) {
                        listOf(
                            DownloadArtifact(
                                logging.string("url"),
                                safePath(root.resolve("assets/log_configs"), id),
                                logging.string("sha1"),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                }
        // 资源索引先下载并解析：资源文件的数量与总字节是进度分母的一部分，
        // 必须先知道，否则「下载资源」阶段只能估一个假区间。
        val index = profile.json.obj("assetIndex")
        val indexPath =
            if (index.string("url").isNotBlank()) {
                safePath(root.resolve("assets/indexes"), "${index.string("id")}.json")
            } else {
                null
            }
        if (indexPath != null) {
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
        }
        val assets: List<DownloadArtifact> =
            if (indexPath != null) {
                val objects = readObject(indexPath).obj("objects")
                objects.entrySet().map { (_, value) ->
                    val item = value.asJsonObject
                    val hash = item.string("hash")
                    require(hash.matches(Regex("[0-9a-fA-F]{40}"))) { "资源哈希无效" }
                    DownloadArtifact(
                        "$assetBaseUrl/${hash.take(2)}/$hash",
                        root.resolve("assets/objects/${hash.take(2)}/$hash"),
                        hash,
                        item.get("size")?.asLong ?: 0,
                    )
                }
            } else {
                emptyList()
            }
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

    /**
     * 客户端 JAR 的真实地址：只用版本 JSON 的 `downloads.client.url`。JSON 没给时，
     * 先读本机同名的完整版本配置，再按版本标识回查版本清单里的官方/镜像 JSON，
     * 不从对象存储按名称拼地址（那样只会得到 404）。
     *
     * 只取 `downloads.client.url`，缺失就留空而不是按名称猜路径（猜出来只会 404）；
     * 这里额外通过版本清单找到真实的该版本 JSON。
     */
    internal suspend fun clientJarUrl(
        profile: ResolvedProfile,
        root: Path,
    ): String {
        profile.json.obj("downloads").obj("client").string("url").takeIf { it.isNotBlank() }?.let {
            return it
        }
        val ids = linkedSetOf(profile.jarId, profile.id)
        runCatching { VersionResolver(root, store.platform).minecraftVersion(profile) }
            .getOrNull()
            ?.let { ids += it }
        for (id in ids.filter { it.isNotBlank() }) {
            val file =
                runCatching { safePath(root, "versions/${safeFilename(id)}/$id.json") }.getOrNull()
                    ?: continue
            val url =
                runCatching {
                    readObject(file).obj("downloads").obj("client").string("url")
                }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        for (id in ids.filter { it.isNotBlank() }) {
            val metadata =
                runCatching { versions().find { version -> version.id == id } }.getOrNull()
                    ?: continue
            val url =
                runCatching {
                    network
                        .json(metadata.url)
                        .asJsonObject
                        .obj("downloads")
                        .obj("client")
                        .string("url")
                }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        throw IOException("版本配置缺少客户端 JAR 下载地址，请检查版本 JSON 或使用修复功能：${profile.id}")
    }

    /**
     * 发布目标实例的启动资料：`versions/<实例名>/<实例名>.json`，需要时再复制 `<实例名>.jar`。
     *
     * 基础版本目录由本任务新建（或就是目标目录）时合成**自包含**配置：保留主类、参数规则、库、
     * 资源索引、日志和真实游戏版本，去掉 `inheritsFrom`/`jar`，把基础 JAR 复制成 `<实例名>.jar`，
     * 之后就能安全删掉基础目录；基础目录本来就存在时保留它，配置用 `inheritsFrom` 指向它。
     */
    private fun publishInstanceProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        loader: JsonObject?,
        target: Path,
        root: Path,
        selfContained: Boolean,
        context: JobContext,
    ) {
        val id = target.fileName.toString()
        safeFilename(id)
        val profile =
            if (loader == null) base.json.deepCopy() else mergeVersionProfiles(base.json, loader)
        profile.addProperty("id", id)
        // 真实游戏版本单独记录：Java/资源/加载器匹配和目录扫描（InstanceCatalog）都读它。
        profile.addProperty("_minecraftVersion", instance.gameVersion)
        profile.addProperty("gameVersion", instance.gameVersion)
        // 本启动器生成的配置：扫描时按受管实例处理（可以修复/改加载器），不会被当成外部整合包。
        profile.addProperty(MANAGED_PROFILE_MARKER, true)
        val file = target.resolve("$id.json")
        if (selfContained) {
            profile.remove("inheritsFrom")
            profile.remove("jar")
            // 加载器配置可能带自己的 downloads；真实客户端 JAR 仍然只取基础版本那一份。
            if (!profile
                    .obj(
                        "downloads",
                    ).has("client") && base.json.obj("downloads").has("client")
            ) {
                profile.add("downloads", base.json.obj("downloads").deepCopy())
            }
            context.writeOutput(file) { writeJson(file, profile) }
            copyBaseJar(root, base, target, context)
        } else {
            profile.addProperty("inheritsFrom", base.id)
            // 客户端 JAR 继续由基础版本目录提供，避免配置被误判成自己拥有 JAR。
            profile.remove("jar")
            profile.remove("downloads")
            context.writeOutput(file) { writeJson(file, profile) }
        }
    }

    /** 把基础版本的客户端 JAR 复制成 `<实例名>.jar`；自包含配置删掉基础目录后仍能启动和校验。 */
    private fun copyBaseJar(
        root: Path,
        base: ResolvedProfile,
        target: Path,
        context: JobContext,
    ) {
        val jarId = runCatching { safeFilename(base.jarId) }.getOrNull() ?: return
        val source = root.resolve("versions").resolve(jarId).resolve("$jarId.jar")
        val id = target.fileName.toString()
        val destination = target.resolve("$id.jar")
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize()) return
        if (!source.isRegularFile(NOFOLLOW_LINKS)) return
        context.writeOutput(destination) {
            destination.parent.createDirectories()
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 回收本任务新建的中间版本目录（基础版本目录等）。
     *
     * 只处理本次确认「原本不存在」的目录；目标实例自己的目录永远保留，其它实例仍按实际目录身份、
     * `inheritsFrom` 或 `jar` 引用它时也保留并写日志。同名游戏版本不算引用。
     */
    private fun reclaimCreatedVersionDirectories(
        created: Set<Path>,
        instanceId: String,
        context: JobContext,
    ) {
        if (created.isEmpty()) return
        // 取消时实例记录可能已经被回收，此时没有引用可判断，交给任务产物清理。
        val instance = store.data.instances.find { it.id == instanceId } ?: return
        val versionsRoot =
            store
                .minecraftRoot(instance)
                .toAbsolutePath()
                .normalize()
                .resolve("versions")
        val target = store.versionGamePath(instance).toAbsolutePath().normalize()
        for (directory in created.sortedByDescending { it.nameCount }) {
            val path = directory.toAbsolutePath().normalize()
            if (path == target || path.parent != versionsRoot) continue
            runCatching {
                if (!path.isDirectory(NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(path)
                ) {
                    return@runCatching
                }
                require(path.toRealPath() == path) { "目录已变为链接，未删除" }
                if (!context.canRemoveDownload(path)) {
                    store.log("中间版本目录仍被其他实例引用，已保留：versions/${path.fileName}")
                    return@runCatching
                }
                val paths = Files.walk(path).use { it.toList() }
                require(paths.all { !Files.isSymbolicLink(it) && it.toRealPath() == it }) {
                    "目录包含链接，未删除"
                }
                paths.sortedByDescending { it.nameCount }.forEach(Files::deleteIfExists)
                store.log("已回收安装中间版本目录：versions/${path.fileName}")
            }.onFailure {
                store.log("回收安装中间目录失败：versions/${path.fileName}（${it.message}）", true)
            }
        }
    }

    /** 新安装必须在写盘前确认目标目录可用：显示名就是目录名，被占用时拒绝而不是另造目录。 */
    private fun requireNewInstanceDirectory(
        root: Path,
        target: Path,
    ) {
        require(target.startsWith(root.resolve("versions"))) { "实例目录不在所选游戏根下" }
        if (!target.exists(NOFOLLOW_LINKS)) return
        // 目录由 createInstance 建好时是空的；里面已经有内容说明名字被别的来源占用了。
        require(target.isDirectory(NOFOLLOW_LINKS) && target.listDirectoryEntries().isEmpty()) {
            "versions/${target.fileName} 已被占用，请换一个实例名称"
        }
    }

    /**
     * 取 Fabric/Quilt 的加载器配置，只作为合成输入，不单独写入 `versions/`。
     *
     * 最终落盘的是目标实例目录里的一份完整配置（[publishInstanceProfile]），因此这里不再生成
     * `molifox-fabric-…` 之类的中间版本标识目录。
     */
    private suspend fun fetchFabricProfile(
        instance: GameInstance,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        context.progress(from, "${InstallPhase.LOADER}：${instance.loader.title} 配置…")
        val host = if (instance.loader == LoaderType.Fabric) fabricMetaBase else quiltMetaBase
        val apiVersion = if (instance.loader == LoaderType.Fabric) "v2" else "v3"
        val profile =
            network
                .json(
                    "$host/$apiVersion/versions/loader/${encoded(
                        instance.gameVersion,
                    )}/${encoded(instance.loaderVersion)}/profile/json",
                ).asJsonObject
        context.checkCancelled()
        context.progress(to, "${InstallPhase.LOADER}：${instance.loader.title} 配置已就绪")
        return profile
    }

    private suspend fun fetchForgeProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        fun at(fraction: Float) = from + (to - from) * fraction
        val root = store.minecraftRoot(instance)
        val isForge = instance.loader == LoaderType.Forge
        val project = if (isForge || instance.gameVersion == "1.20.1") "forge" else "neoforge"
        val version =
            if (isForge) {
                "${instance.gameVersion}-${instance.loaderVersion}"
            } else {
                instance.loaderVersion
            }
        val host =
            if (isForge) {
                "https://maven.minecraftforge.net"
            } else {
                "https://maven.neoforged.net/releases"
            }
        val group = if (isForge) "net/minecraftforge" else "net/neoforged"
        val url = "$host/$group/$project/$version/$project-$version-installer.jar"
        val archive =
            store.downloadDirectory.resolve(
                "loaders/${safeFilename(project)}-${safeFilename(version)}-installer.jar",
            )
        context.progress(at(.1f), "${InstallPhase.LOADER}：下载 ${instance.loader.title} 安装程序…")
        val installerHash =
            network
                .text("$url.sha1")
                .trim()
                .substringBefore(' ')
                .trim()
        require(installerHash.matches(Regex("[a-fA-F0-9]{40}"))) { "加载器安装程序缺少有效校验信息" }
        network.downloadTracked(context, DownloadArtifact(url, archive, installerHash))
        val runtime =
            java.resolve(
                base.requiredJava,
                instance.javaPath.ifBlank { store.data.settings.javaPath },
                context,
            )
        val temporaryRoot =
            store.temporaryDirectory.resolve("forge-${UUID.randomUUID()}").apply {
                createDirectories()
            }
        try {
            ZipFile(archive.toFile()).use { zip ->
                val profile =
                    zip.getEntry("install_profile.json")?.let {
                        zip.getInputStream(it).reader().use { reader ->
                            com.google.gson.JsonParser
                                .parseReader(reader)
                                .asJsonObject
                        }
                    } ?: error("加载器安装包缺少 install_profile.json")
                val versionJson =
                    if (profile.has("versionInfo")) {
                        profile.obj("versionInfo")
                    } else {
                        val jsonEntry =
                            zip.getEntry(profile.string("json", "/version.json").removePrefix("/"))
                                ?: error("安装包缺少 version.json")
                        zip.getInputStream(jsonEntry).reader().use {
                            com.google.gson.JsonParser
                                .parseReader(it)
                                .asJsonObject
                        }
                    }
                // 加载器配置只作为合成输入：id/inheritsFrom 由发布阶段按目标实例决定，
                // 不再固定写成 molifox-forge-… 目录。
                versionJson.remove("inheritsFrom")
                versionJson.remove("jar")
                // Extract only installer-owned Maven artifacts, never arbitrary archive paths.
                for ((entry, target) in validateArchive(zip, "maven/", root.resolve("libraries"))) {
                    context.checkCancelled()
                    context.writeOutput(target) {
                        target.parent.createDirectories()
                        zip.getInputStream(entry).use {
                            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
                // Pre-1.13 installers embed the universal JAR outside maven/.
                if (profile.has("versionInfo")) {
                    val embedded =
                        zip.entries().asSequence().firstOrNull {
                            !it.isDirectory &&
                                !it.name.contains('/') &&
                                it.name.endsWith("universal.jar")
                        }
                    val forgeLibrary =
                        versionJson
                            .array("libraries")
                            .map { it.asJsonObject }
                            .find { it.string("name").startsWith("net.minecraftforge:forge:") }
                    if (embedded != null && forgeLibrary != null) {
                        val target =
                            safePath(
                                root.resolve("libraries"),
                                mavenPath(forgeLibrary.string("name")),
                            )
                        context.writeOutput(target) {
                            target.parent.createDirectories()
                            zip.getInputStream(embedded).use {
                                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
                val resolver = VersionResolver(root, store.platform)
                val libraries =
                    profile
                        .array("libraries")
                        .map { it.asJsonObject }
                        .filter { resolver.allowed(it) }
                        .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
                network.downloadAll(
                    libraries,
                    store.data.settings.maxConcurrent,
                    context,
                    "${InstallPhase.LOADER}：下载依赖",
                    at(.2f),
                    at(.5f),
                )
                val variables =
                    mutableMapOf(
                        "SIDE" to "client",
                        "ROOT" to root.toString(),
                        "LIBRARY_DIR" to root.resolve("libraries").toString(),
                        "MINECRAFT_JAR" to
                            root.resolve("versions/${base.jarId}/${base.jarId}.jar").toString(),
                        "MINECRAFT_VERSION" to instance.gameVersion,
                        "INSTALLER" to archive.toString(),
                    )
                for ((key, element) in profile.obj("data").entrySet()) {
                    val value = element.asJsonObject.string("client")
                    variables[key] =
                        if (
                            value.startsWith('/') && zip.getEntry(value.removePrefix("/")) != null
                        ) {
                            val entry = zip.getEntry(value.removePrefix("/"))
                            val target = safePath(temporaryRoot, value.removePrefix("/"))
                            target.parent.createDirectories()
                            zip.getInputStream(entry).use {
                                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                            target.toString()
                        } else {
                            value
                        }
                }

                fun expand(value: String): String {
                    var result = value
                    repeat(12) {
                        result =
                            Regex("\\{([^}]+)}").replace(result) { match ->
                                variables[match.groupValues[1]] ?: match.value
                            }
                    }
                    if (result.startsWith('[') && result.endsWith(']')) {
                        return safePath(
                            root.resolve("libraries"),
                            mavenPath(result.substring(1, result.length - 1)),
                        ).toString()
                    }
                    if (result.startsWith('\'') && result.endsWith('\'')) {
                        result = result.substring(1, result.length - 1)
                    }
                    require(!Regex("\\{[^}]+}").containsMatchIn(result)) { "安装器包含无法解析的变量：$result" }
                    return result
                }
                val processors =
                    profile
                        .array("processors")
                        .map { it.asJsonObject }
                        .filter {
                            !it.has("sides") ||
                                it.array("sides").any { side -> side.asString == "client" }
                        }
                for ((index, processor) in processors.withIndex()) {
                    context.checkCancelled()
                    context.progress(
                        at(.5f + index.toFloat() / maxOf(processors.size, 1) * .45f),
                        "${InstallPhase.LOADER}：处理组件 ${index + 1}/${processors.size}",
                    )
                    val outputs =
                        processor.obj("outputs").entrySet().map {
                            expand(it.key) to expand(it.value.asString)
                        }
                    val reusedOutputs =
                        outputs.isNotEmpty() &&
                            outputs.all { (file, hash) -> isFreshOutput(file, hash) }
                    if (reusedOutputs) {
                        continue
                    }
                    val artifact =
                        safePath(
                            root.resolve("libraries"),
                            mavenPath(processor.string("jar")),
                        )
                    val mainClass =
                        JarFile(artifact.toFile()).use {
                            it.manifest?.mainAttributes?.getValue("Main-Class")
                        } ?: error("加载器处理器没有主程序")
                    val classpath =
                        (
                            processor.array("classpath").map {
                                safePath(
                                    root.resolve("libraries"),
                                    mavenPath(it.asString),
                                ).toString()
                            } + artifact.toString()
                        ).joinToString(store.platform.classpathSeparator)
                    val trackedOutputs =
                        outputs.map { (file, _) ->
                            context.trackOutput(Path.of(file))
                        }
                    try {
                        runJavaProcess(
                            runtime.path,
                            listOf("-cp", classpath, mainClass) +
                                processor.array("args").map { expand(it.asString) },
                            root,
                        ) { line ->
                            store.log(line, level = LogLevel.Trace)
                        }
                    } finally {
                        trackedOutputs.forEach { it() }
                    }
                    outputs.forEach { (file, hash) ->
                        require(
                            Path.of(file).exists() &&
                                (
                                    !hash.matches(Regex("[0-9a-fA-F]{40}")) ||
                                        digest(Path.of(file)).equals(hash, true)
                                ),
                        ) {
                            "加载器处理结果校验失败"
                        }
                    }
                }
                context.progress(to, "${InstallPhase.LOADER}：${instance.loader.title} 配置已就绪")
                return versionJson
            }
        } finally {
            val cacheRoot = store.temporaryDirectory.toAbsolutePath().normalize()
            check(
                temporaryRoot.toAbsolutePath().normalize().parent == cacheRoot &&
                    temporaryRoot.name.startsWith("forge-"),
            )
            if (temporaryRoot.exists() && !Files.isSymbolicLink(temporaryRoot)) {
                Files.walk(temporaryRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}

internal suspend fun runJavaProcess(
    java: String,
    arguments: List<String>,
    directory: Path,
    onLine: (String) -> Unit,
): Unit =
    coroutineScope {
        val process =
            ProcessBuilder(listOf(java) + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start()
        val output =
            launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            }
        try {
            val code =
                withTimeout(
                    20 * 60 * 1000L,
                ) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
            output.join()
            check(code == 0) { "Java 组件处理失败，退出码 $code。详细信息请查看日志" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.cancel()
        }
    }

/** 组件的输出文件已存在且哈希一致时直接复用，避免重复跑 processor。 */
private fun isFreshOutput(
    file: String,
    hash: String,
): Boolean {
    val path = Path.of(file)
    val validHash = hash.matches(Regex("[0-9a-fA-F]{40}"))
    return path.exists() && validHash && digest(path).equals(hash, true)
}
