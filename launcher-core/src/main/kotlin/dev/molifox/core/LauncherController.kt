// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面操作的服务协调与状态更新。

package dev.molifox.core

import dev.molifox.core.data.instance.FileInstanceWorkspaceRepository
import dev.molifox.core.domain.instance.InstanceWorkspaceQueries
import dev.molifox.core.platform.files.NioFileSystemAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readAttributes
import kotlin.io.path.writeText

class LauncherController
    @JvmOverloads
    constructor(
        val platform: PlatformServices = DesktopPlatform(),
        dataDirectory: Path = platform.defaultDataDirectory,
        updateApiBase: String = "https://api.github.com/repos/Tinmoli/MoliFox",
        networkClient: NetworkClient? = null,
    ) : AutoCloseable {
        private val lockChannel =
            FileChannel.open(
                dataDirectory.apply { createStorageDirectory(this) }.resolve("instance.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
        private val lock: FileLock =
            try {
                lockChannel.tryLock() ?: error("MoliFox 已在运行，请切换到已有窗口")
            } catch (failure: Exception) {
                lockChannel.close()
                throw failure
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store =
            try {
                LauncherStore(platform, dataDirectory)
            } catch (failure: Exception) {
                scope.cancel()
                lock.release()
                lockChannel.close()
                throw failure
            }
        val state = store.state
        val network =
            networkClient ?: NetworkClient(
                { store.data.settings.downloadSource },
                {
                    // 代理设置每次请求时读取；配置无效时直连。
                    val proxy = store.data.settings
                    if (
                        proxy.effectiveProxyMode() != "manual" ||
                        proxy.proxyHost.isBlank() ||
                        proxy.proxyPort !in 1..65535
                    ) {
                        null
                    } else {
                        runCatching {
                            Proxy(
                                if (proxy.proxyType ==
                                    "socks"
                                ) {
                                    Proxy.Type.SOCKS
                                } else {
                                    Proxy.Type.HTTP
                                },
                                InetSocketAddress(proxy.proxyHost, proxy.proxyPort),
                            )
                        }.getOrNull()
                    }
                },
                { store.data.settings.effectiveProxyMode() == "system" },
                downloadSegments = { store.data.settings.downloadSegments },
                downloadAttempts = { store.data.settings.downloadAttempts },
                requestTimeoutSeconds = { store.data.settings.networkTimeoutSeconds },
                maxConcurrent = { store.data.settings.maxConcurrent },
                mirrorConcurrency = { store.data.settings.mirrorConcurrency },
                // 源被禁用、限流与并发预算调整都要在启动器日志里留一行。
                logger = { message, error -> store.log(message, error) },
            )
        val jobs = JobQueue(store, scope)
        val updater = GitHubUpdater(store, network, updateApiBase)
        val java = JavaService(store, network)
        val accounts = AccountService(store, network, scope)
        val skins = SkinService(store, network, accounts::credentials)
        val installer = MinecraftInstaller(store, network, java)
        val directories = GameDirectoryService(store)
        val icons = InstanceIcons(store)
        val screenshots = ScreenshotManager(store)
        val content = ContentService(store, network, installer)
        val tools = InstanceTools(store)
        val deletion = InstanceDeletionService(store)
        val saves = SaveManager(store)
        val instanceWorkspace =
            InstanceWorkspaceQueries(
                FileInstanceWorkspaceRepository(
                    store::instancePath,
                    NioFileSystemAccess(),
                ) { path, failure ->
                    if (failure !is NoSuchFileException) {
                        store.log("无法读取目录项 $path：${failure.message}", error = true)
                    }
                },
            )
        val datapacks = DatapackManager(store)

        suspend fun modIconBytes(jar: Path): ByteArray? =
            withContext(Dispatchers.IO) {
                readModIcon(jar)
                    .onFailure {
                        store.log("无法读取模组图标 $jar：${it.message}", true)
                    }.getOrNull()
            }

        suspend fun serverStatus(address: String): ServerStatus? =
            withContext(Dispatchers.IO) {
                pingAddress(address)
            }

        suspend fun instanceDirectorySize(id: String): Long? =
            withContext(Dispatchers.IO) {
                val taskContext = kotlinx.coroutines.currentCoroutineContext()
                dev.molifox.core.platform.files
                    .directoryByteSize(
                        store.versionGamePath(store.instance(id)),
                    ) {
                        taskContext.ensureActive()
                    }.onFailure { store.log("无法统计实例目录大小：${it.message}", true) }
                    .getOrNull()
            }

        private val renamer = InstanceRename(store)
        private val migration = IsolationMigration(store)
        val game = GameLauncher(store, installer, java, accounts, scope)
        val busy: Boolean
            get() = jobs.globallyBusy

        fun isInstanceBusy(id: String): Boolean =
            jobs.conflicts(taskResourcesForInstances(store, id)) ||
                (
                    !store.instance(id).installed &&
                        state.value.jobs.any {
                            it.status in setOf(JobStatus.Queued, JobStatus.Running) &&
                                it.resources.any { resource -> resource.startsWith("new-pack:") }
                        }
                )

        init {
            // 打开文件夹失败时要写日志并提示用户；DesktopPlatform 本身不持有 store，由这里注入。
            (platform as? DesktopPlatform)?.let { desktop ->
                desktop.onLog = { message -> store.log(message) }
                desktop.onNotice = { message -> store.notify(message) }
            }
            directories.refreshVersionMetadata()
            // 启动时以 versions/ 扫描为准重建实例列表。
            store.refreshInstances()
            store.log("欢迎使用 MoliFox · ${platform.osName} / ${platform.architecture}")
            if (store.data.settings.autoCheckUpdates) checkForUpdates(false)
            refreshJavaPrompt()
        }

        /** 界面在窗口尺寸变化时上报当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
        fun reportLauncherWindowSize(
            widthPx: Int,
            heightPx: Int,
        ) {
            if (widthPx > 0 && heightPx > 0) store.launcherWindowSize = widthPx to heightPx
        }

        fun checkForUpdates(manual: Boolean) {
            scope.launch { updater.check(manual) }
        }

        fun downloadUpdate(): String {
            val release = requireNotNull(state.value.update.release) { "请先检查更新" }
            return jobs.enqueue("更新 MoliFox ${release.version}", setOf("launcher-update")) {
                updater.download(release, this)
            }
        }

        fun perform(action: suspend () -> Unit): Job =
            scope.launch {
                try {
                    action()
                } catch (_: CancellationException) {
                } catch (error: Exception) {
                    store.log(error.message ?: "操作失败", true)
                    store.notify(error.message ?: "操作失败")
                }
            }

        fun requireIdle(vararg instanceIds: String) {
            if (instanceIds.isEmpty()) {
                check(!jobs.busy && !game.isRunning) { "此操作会修改游戏目录，请等待游戏或后台任务结束后再操作" }
                return
            }
            val resources = taskResourcesForInstances(store, *instanceIds)
            check(
                instanceIds.none(::isInstanceBusy) &&
                    state.value.runningGames.none {
                        taskResourcesConflict(
                            resources,
                            taskResourcesForInstances(store, it.instanceId),
                        )
                    },
            ) {
                "此实例或其共用文件正在被使用，请等待相关游戏或任务结束"
            }
        }

        fun install(
            request: InstallRequest,
            gameDirectoryId: String? = store.data.activeGameDirectoryId,
        ): String {
            val instance = installer.createInstance(request, gameDirectoryId)
            return jobs.enqueue(
                "安装 ${instance.name}",
                taskResourcesForInstances(store, instance.id),
                prepare = { NewInstallationCleanup(store).register(instance, it) },
            ) {
                network.resetSourceHealth()
                // 新安装：目录名就是实例名，开工前再确认它没有被别的目录或实例占用。
                installer.install(instance.id, this, freshInstall = true)
                // 安装完成后版本 JSON 已落盘，让列表从扫描结果而不是内存记录更新。
                store.refreshInstances()
            }
        }

        fun repair(id: String): String {
            requireIdle(id)
            val instance = store.instance(id)
            return jobs.enqueue("修复 ${instance.name}", taskResourcesForInstances(store, id)) {
                network.resetSourceHealth()
                if (instance.packFile.isNotBlank()) {
                    content.importPack(Path.of(instance.packFile), this, instance.id)
                } else {
                    installer.install(id, this)
                }
                store.refreshInstances()
            }
        }

        fun launch(
            id: String,
            target: GameLaunchTarget? = null,
        ): String {
            val instance = store.instance(id)
            // 损坏/缺失 JSON 的目录仍显示在列表里，但绝不尝试启动或隐式改写它。
            require(!instance.broken) { "此实例无法识别：" + instance.brokenReason }
            require(instance.installed) { "此实例尚未安装完成，请先修复" }
            require(store.data.activeAccountId != null) { "请先添加并选择游戏账户" }
            return jobs.enqueue(
                "启动 ${store.instance(id).name}",
                taskResourcesForInstances(store, id),
            ) {
                game.launch(id, this, target)
            }
        }

        fun selectInstance(id: String) {
            val instance = store.instance(id)
            store.mutate {
                it.copy(selectedInstanceId = id, activeGameDirectoryId = instance.gameDirectoryId)
            }
            refreshJavaPrompt()
        }

        /**
         * 启动检查：当前选中实例缺少兼容 Java 时，给出「下载并安装 / 选择已有 / 使用系统 Java / 取消」入口。
         * 已显式指定 Java（实例或全局）、用户点过不再提示、或已有满足要求的候选时都不弹窗。
         */
        fun refreshJavaPrompt() {
            scope.launch { evaluateJavaPrompt() }
        }

        private suspend fun evaluateJavaPrompt() {
            val selectedId = store.data.selectedInstanceId
            val existing = state.value.javaPrompt
            if (existing != null && existing.instanceId != selectedId) {
                store.updateState { it.copy(javaPrompt = null) }
            }
            val instance =
                selectedId?.let { id -> store.data.instances.find { it.id == id } }
                    ?: return
            if (!instance.installed ||
                instance.suppressJavaPrompt ||
                instance.javaPath.ifBlank { store.data.settings.javaPath }.isNotBlank()
            ) {
                if (state.value.javaPrompt != null) store.updateState { it.copy(javaPrompt = null) }
                return
            }
            val profile =
                runCatching {
                    VersionResolver(store.minecraftRoot(instance), platform)
                        .resolve(instance.versionId)
                }.getOrNull() ?: return
            val requirement = JavaRequirement.from(profile)
            val candidates = java.detect().filter { it.usable }
            if (candidates.any { requirement.problem(it, platform.architecture) == null }) {
                if (state.value.javaPrompt != null) store.updateState { it.copy(javaPrompt = null) }
                return
            }
            val gameVersion = instance.gameVersion.ifBlank { profile.id }
            store.updateState {
                it.copy(
                    javaPrompt =
                        JavaPrompt(
                            instanceId = instance.id,
                            instanceName = instance.name,
                            gameVersion = gameVersion,
                            requiredMajor = requiredJavaMajor(gameVersion, profile.requiredJava),
                            candidates = candidates,
                        ),
                )
            }
        }

        /** [remember] 为 true 时把「不再提示」写进实例；只影响提示，不影响手动选择的 Java。 */
        fun dismissJavaPrompt(remember: Boolean) {
            val prompt = state.value.javaPrompt ?: return
            if (remember) {
                store.updateInstance(prompt.instanceId) { it.copy(suppressJavaPrompt = true) }
            }
            store.updateState { it.copy(javaPrompt = null) }
        }

        /** 从提示弹窗直接安装缺少的主版本；进度进入任务队列，成功后清除提示。 */
        fun installJavaForPrompt(): String {
            val prompt = state.value.javaPrompt ?: error("当前没有需要安装的 Java")
            return jobs.enqueue(
                "安装 Java ${prompt.requiredMajor}",
                setOf("java:${prompt.requiredMajor}"),
            ) {
                val runtime = java.install(prompt.requiredMajor, this)
                store.updateState { it.copy(javaPrompt = null) }
                store.notify("已安装 ${runtime.displayName}，启动时会自动使用")
            }
        }

        /** 用户从提示弹窗选择已有 Java：校验兼容后写进实例设置（显式路径优先于自动选择）。 */
        fun chooseJavaForPrompt(path: String) =
            perform {
                val prompt = state.value.javaPrompt ?: error("当前没有需要处理的 Java")
                val runtime =
                    java.inspect(path, verifyRunnable = true)
                        ?: error("这个文件不是可运行的 Java：$path")
                val instance = store.instance(prompt.instanceId)
                runCatching {
                    VersionResolver(
                        store.minecraftRoot(instance),
                        platform,
                    ).resolve(instance.versionId)
                }.getOrNull()
                    ?.let { profile ->
                        val requirement = JavaRequirement.from(profile)
                        val problem = requirement.problem(runtime, platform.architecture)
                        require(problem == null) { "$problem，不能用于 ${instance.name}" }
                    }
                store.updateInstance(instance.id) {
                    it.copy(javaPath = path, suppressJavaPrompt = false)
                }
                java.invalidate()
                store.updateState { it.copy(javaPrompt = null) }
                store.notify("已使用 ${runtime.displayName}：${runtime.path}")
            }

        /**
         * 移除 Java 条目：启动器安装的删除目录与清单；用户手动添加的只删记录、不动文件；
         * 系统 Java 只读。被移除路径若仍是全局/实例选择，一并清回「自动」，避免留下失效选择。
         */
        fun removeJavaRuntime(runtime: JavaRuntime) =
            perform {
                requireIdle()

                fun samePath(
                    left: String,
                    right: String,
                ): Boolean {
                    if (left.isBlank() || right.isBlank()) return false
                    return runCatching {
                        val a = Path.of(left).toAbsolutePath().normalize()
                        val b = Path.of(right).toAbsolutePath().normalize()
                        a.toString().equals(b.toString(), ignoreCase = platform.osName == "windows")
                    }.getOrDefault(false)
                }

                fun forgetPath() {
                    store.mutate { data ->
                        data.copy(
                            settings =
                                data.settings.copy(
                                    javaPath =
                                        if (samePath(data.settings.javaPath, runtime.path)) {
                                            ""
                                        } else {
                                            data.settings.javaPath
                                        },
                                    customJavaPaths =
                                        data.settings.customJavaPaths.filterNot {
                                            samePath(it, runtime.path)
                                        },
                                ),
                            instances =
                                data.instances.map {
                                    if (samePath(it.javaPath, runtime.path)) {
                                        it.copy(javaPath = "")
                                    } else {
                                        it
                                    }
                                },
                        )
                    }
                    java.invalidate()
                }
                when {
                    runtime.managed -> {
                        val removed = java.remove(runtime)
                        forgetPath()
                        store.notify(
                            "已移除 ${removed.displayName}（释放 ${"%.0f MB".format(
                                runtime.sizeBytes / 1048576f,
                            )}）",
                        )
                    }
                    runtime.source == JavaRuntimeSource.Custom -> {
                        forgetPath()
                        store.notify("已从列表移除 ${runtime.path}（只删记录，不删文件）")
                    }
                    else -> error("系统安装的 Java 只读，不能移除")
                }
                refreshJavaPrompt()
            }

        fun deleteServer(
            instanceId: String,
            address: String,
        ): ServerDeletion {
            requireIdle(instanceId)
            return synchronized(store) { ServerListEditor(store).delete(instanceId, address) }
        }

        fun selectGameDirectory(id: String?) {
            directories.select(id)
            store.refreshInstances()
        }

        fun refreshGameDirectory() {
            requireIdle()
            directories.refresh()
            store.refreshInstances()
        }

        /** 实例页重新可见时调用；签名没变就不做全量扫描。 */
        fun refreshInstancesIfChanged(): Boolean = store.refreshInstancesIfChanged()

        /**
         * 启动时自动发现：按启动器自身位置（[LauncherLocation]，不是 user.dir）解析旁边的 `.minecraft`、启动器自身即 `.minecraft`/游戏根、以及
         * `MoliFox/` 旁的目录，只读登记现有版本。 已登记目录不会重复添加，也不会覆盖用户的选择；没有发现时不产生任何日志或提示。
         */
        fun discoverStartupGameDirectories(): Int {
            val discovered =
                directories.discover(
                    StartupGameRoots.resolve(LauncherLocation.locate()),
                )
            store.refreshInstances()
            return discovered
        }

        fun removeGameDirectory(id: String) {
            requireIdle()
            directories.remove(id)
            store.refreshInstances()
        }

        fun updateInstance(
            id: String,
            name: String,
            memoryMb: Int,
            javaPath: String,
            isolation: VersionIsolation = store.instance(id).isolation,
        ) {
            saveInstanceConfiguration(
                store
                    .instance(id)
                    .copy(
                        name = name,
                        memoryMb = memoryMb,
                        javaPath = javaPath,
                        isolation = isolation,
                    ),
            )
        }

        /**
         * 重命名实例：移动真实版本目录并同步同名 JSON/JAR、配置 `id` 与版本引用。 校验在动磁盘前完成（非法字符、目录或记录重名、
         * 其他实例的基础版本、运行/任务占用），失败回滚并抛中文错误。
         */
        fun renameInstance(
            instanceId: String,
            newName: String,
        ): GameInstance {
            requireIdle(instanceId)
            val oldFolder = store.instance(instanceId).instanceFolderName()
            val renamed = renamer.rename(instanceId, newName)
            // 设置表按目录名索引：目录改名后把隔离、Java、图标、收藏和最近游玩搬到新键。
            store.renameInstanceSettings(oldFolder, newName)
            store.refreshInstances()
            return store.instance(newName)
        }

        /**
         * 写入实例设置（内存、窗口、参数、备注等）。[notify] 为 false 时不弹「版本设置已保存」提示，
         * 供设置页的自动保存使用，避免每次改动都刷一条提示。
         */
        fun saveInstanceConfiguration(
            configuration: GameInstance,
            notify: Boolean = true,
        ) {
            requireIdle(configuration.id)
            require(configuration.name.isNotBlank() && configuration.name.length <= 80) {
                "实例名称需要 1–80 个字符"
            }
            validateInstanceLaunchOptions(
                memoryMb = configuration.memoryMb,
                width = configuration.width,
                height = configuration.height,
                windowMode = configuration.windowMode,
                extraJvmArgs = configuration.extraJvmArgs,
                extraGameArgs = configuration.extraGameArgs,
                totalMemoryMb = platform.totalMemoryMb,
            )
            require(
                configuration.initialMemoryMb == 0 ||
                    configuration.initialMemoryMb in
                    128..(
                        if (
                            configuration.autoMemory == true ||
                            (
                                configuration.autoMemory == null &&
                                    configuration.memoryMb == 0 &&
                                    store.data.settings.autoMemory
                            )
                        ) {
                            maxOf(512, platform.totalMemoryMb - 512)
                        } else {
                            configuration.memoryMb.takeIf { it > 0 }
                                ?: store.data.settings.memoryMb
                        }
                    ),
            ) {
                "初始内存需为 0（继承）或在 128 MB 到最大内存之间"
            }
            require(configuration.notes.length <= 2000) { "备注最多 2000 字" }
            configuration.advancedLaunch?.let {
                validateAdvancedLaunchSettings(
                    it,
                    platform.osName,
                )
            }
            val previousJavaPath = store.instance(configuration.id).javaPath
            store.updateInstance(configuration.id) {
                it.copy(
                    name = configuration.name.trim(),
                    memoryMb = configuration.memoryMb,
                    autoMemory = configuration.autoMemory,
                    javaPath = configuration.javaPath,
                    isolation = configuration.isolation,
                    initialMemoryMb = configuration.initialMemoryMb,
                    width = configuration.width,
                    height = configuration.height,
                    windowMode = configuration.windowMode,
                    extraJvmArgs = configuration.extraJvmArgs,
                    extraGameArgs = configuration.extraGameArgs,
                    advancedLaunch = configuration.advancedLaunch,
                    notes = configuration.notes,
                    replaceSaveOnRestore = configuration.replaceSaveOnRestore,
                )
            }
            if (previousJavaPath != configuration.javaPath) {
                java.invalidate()
                refreshJavaPrompt()
            }
            if (notify) store.notify("版本设置已保存")
        }

        /** 升级既有实例的加载器小版本（同加载器、同游戏版本）；模组、存档与配置不动。 */
        fun upgradeInstanceLoader(
            instanceId: String,
            loaderVersion: String,
        ): String {
            requireIdle(instanceId)
            val instance = store.instance(instanceId)
            return jobs.enqueue(
                "升级加载器 ${instance.name}",
                taskResourcesForInstances(store, instanceId),
            ) {
                installer.upgradeLoader(instanceId, loaderVersion, this)
            }
        }

        suspend fun previewLaunch(instanceId: String): LaunchPlan {
            val instance = store.instance(instanceId)
            val profile =
                VersionResolver(store.minecraftRoot(instance), platform).resolve(instance.versionId)
            val javaPath =
                instance.javaPath
                    .ifBlank { store.data.settings.javaPath }
                    .ifBlank { "自动选择 Java ${profile.requiredJava}" }
            val account = store.data.accounts.find { it.id == store.data.activeAccountId }
            val plan =
                LaunchPlanner(store)
                    .plan(
                        instance,
                        profile,
                        JavaRuntime(javaPath, profile.requiredJava, "预览", platform.architecture),
                        GameCredentials(
                            account?.name ?: "Player",
                            account?.uuid ?: "<玩家 UUID>",
                            "<访问令牌>",
                        ),
                        prepareFiles = false,
                    )
            return plan.copy(environment = environmentForDiagnostics(plan.environment))
        }

        /** 切换内容（模组/整合包等）下载源；CurseForge 走设置里的公共镜像，无需 API Key。 */
        fun setContentDownloadSource(source: String) {
            require(source in listOf("modrinth", "curseforge")) { "未知的内容下载源" }
            store.mutate { it.copy(settings = it.settings.copy(contentDownloadSource = source)) }
            store.notify(
                if (source == "curseforge") "内容下载源已切换为 CurseForge" else "内容下载源已切换为 Modrinth",
            )
        }

        fun toggleFavorite(id: String) {
            store.updateInstance(id) { it.copy(favorite = !it.favorite) }
        }

        /** 删除实例会永久删除的绝对路径（版本文件夹与游戏文件夹）；无法解析时为空，仅用于对话框展示。 */
        fun deletionTargets(instanceId: String): List<Path> =
            runCatching {
                deletion.plan(instanceId).targets
            }.getOrElse { emptyList() }

        /** 删除实例时会因其他实例真实使用而保留的路径与原因；只用于对话框展示。 */
        fun deletionPreserved(instanceId: String): List<String> =
            runCatching {
                deletion.plan(instanceId).preserved
            }.getOrElse { emptyList() }

        /**
         * 删除前就会被拒绝的原因（例如版本目录仍被另一个实例使用）；为空表示可以尝试删除。
         *
         * 确认框据此提前说明「这次删除会被阻止」，而不是让用户点下去才发现；真正执行时 [deleteInstance]
         * 会再校验一次，保证记录与文件不会被拆散。
         */
        fun deletionBlockReason(instanceId: String): String? =
            runCatching { deletion.plan(instanceId) }
                .exceptionOrNull()
                ?.let { failure ->
                    val message = failure.message
                    if (message.isNullOrBlank()) "无法删除实例" else message
                }

        /** 删除实例会一并删除的存档名单（名称 + 上次修改时间），只用于确认框展示。 */
        fun deletionWorlds(instanceId: String): List<InstanceDeletionWorld> =
            runCatching {
                deletion.worlds(instanceId)
            }.getOrElse { emptyList() }

        /** 实例是否有独立游戏目录；为真时存档、资源包、Mod 都在会被删除的目录里。 */
        fun hasSeparateGameDirectory(instanceId: String): Boolean =
            runCatching {
                val instance = store.instance(instanceId)
                store.gamePath(instance).toAbsolutePath().normalize() !=
                    store.minecraftRoot(instance).toAbsolutePath().normalize()
            }.getOrDefault(false)

        /**
         * 删除实例：先处理实例自己的文件目录，全部真正删除后才移除启动器记录——不提供「只清记录」。
         *
         * [permanent] 为 true（界面上按住 Shift
         * 确认）时永久删除，否则移入系统回收站；回收站不可用时自动回退永久删除， 结果里的 [InstanceDeletionReport.movedToTrash] 会说明实际去向。
         * 实例正在运行、路径无法解析、目标是符号链接或不在实例自己目录下时返回失败，磁盘完全不动； 版本目录仍被其他实例
         * 使用时返回「无法删除：版本目录仍被实例「X」使用；请先处理该实例」并原样保留记录与文件； 删除中途失败时返回失败，已处理的部分不回滚。
         */
        suspend fun deleteInstance(
            instanceId: String,
            permanent: Boolean = false,
        ): Result<InstanceDeletionReport> =
            withContext(Dispatchers.IO) {
                try {
                    require(!game.isRunning(instanceId)) {
                        "实例正在运行，请先退出游戏再删除"
                    }
                    requireIdle(instanceId)
                    val instance = store.instance(instanceId)
                    val plan = deletion.plan(instanceId)
                    plan.preserved.forEach { store.log("删除实例时保留：$it") }
                    // delete() 自己会核对每个目标是否真的消失；它成功返回才允许删记录。
                    val report = deletion.delete(plan, permanent)
                    store.mutate { data ->
                        val instances = data.instances.filter { it.id != instanceId }
                        // 回退优先同一游戏目录里的实例，开始页与实例列表都不会跳到别的目录。
                        val fallback =
                            instances.firstOrNull {
                                it.gameDirectoryId == instance.gameDirectoryId
                            } ?: instances.firstOrNull()
                        data.copy(
                            instances = instances,
                            selectedInstanceId =
                                if (data.selectedInstanceId == instanceId) {
                                    fallback?.id
                                } else {
                                    data.selectedInstanceId
                                },
                        )
                    }
                    // 删除成功后立刻以磁盘为准刷新一次：目录若仍被保留（共用/删除失败）就不会从列表消失。
                    store.refreshInstances()
                    icons.releaseUnused(instance.iconId)
                    icons.releaseUnused(instance.packIconId)
                    // 只报告真实发生的事：删了哪些、释放多少、哪些因共用而保留，不再把「保留」写成「已删除」。
                    val outcome =
                        when {
                            report.removedPaths.isEmpty() -> "实例目录已不存在，仅移除失效记录"
                            report.preserved.isNotEmpty() ->
                                "已删除 ${report.removedPaths.size} 项；保留 ${report.preserved.size} 项共用目录"
                            report.movedToTrash ->
                                "版本文件夹与游戏文件夹已移入回收站（${report.removedPaths.size} 项）"
                            else ->
                                "版本文件夹与游戏文件夹已永久删除（${report.removedPaths.size} 项）"
                        }
                    val kept =
                        report.preserved
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString("；") { it }
                            ?.let { "；保留原因：$it" }
                            .orEmpty()
                    val freed =
                        if (report.removedPaths.isNotEmpty()) {
                            "，释放 ${report.freedBytes} 字节"
                        } else {
                            "，释放 0 字节"
                        }
                    store.log("已删除实例记录：${instance.name}（$outcome$freed$kept）")
                    Result.success(report)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (blocked: InstanceDeletionBlockedException) {
                    // 共用版本目录等「整次拒绝」：日志与界面用同一句话，不写「已删除实例记录」。
                    store.log(blocked.message ?: "无法删除实例", true)
                    Result.failure(blocked)
                } catch (failure: Exception) {
                    store.log("删除实例失败：${failure.message ?: "未知错误"}", true)
                    Result.failure(failure)
                }
            }

        fun saveSettings(input: LauncherSettings) {
            val settings = normalizedSettings(input)
            validateSettings(
                settings = settings,
                current = store.data.settings,
                instancesEmpty = store.data.instances.isEmpty(),
                osName = platform.osName,
                totalMemoryMb = platform.totalMemoryMb,
                isIdle = ::requireIdle,
            )
            applySettings(settings)
        }

        private fun applySettings(settings: LauncherSettings) {
            applyDownloadSettings(settings)
            val javaPathChanged = settings.javaPath != store.data.settings.javaPath
            store.mutate {
                it.copy(
                    settings =
                        settings.copy(
                            proxyEnabled =
                                settings.effectiveProxyMode() == "manual",
                        ),
                )
            }
            if (javaPathChanged) applyLaunchSettings()
            store.notify("设置已保存")
        }

        private fun applyDownloadSettings(settings: LauncherSettings) {
            if (settings.downloadCachePath != store.data.settings.downloadCachePath) {
                store.directories.cache(settings.downloadCachePath).initialize()
            }
            // 换源后重新评估健康度；测速顺序只属于正在执行的单次任务。
            if (
                settings.downloadSource != store.data.settings.downloadSource ||
                settings.versionListSource != store.data.settings.versionListSource
            ) {
                network.resetSourceHealth()
            }
        }

        private fun applyLaunchSettings() {
            java.invalidate()
            refreshJavaPrompt()
        }

        /**
         * 返回账户皮肤纹理的本地缓存路径（MoliFox/cache/skins/<uuid>.png）。 离线账户或未配置皮肤时为 null；在线账户首次调用会从 Mojang 官方 CDN
         * 下载。
         */
        suspend fun accountSkin(
            account: Account,
            refresh: Boolean = false,
        ): Path? = skins.texture(account, refresh)

        fun openFolder(
            id: String,
            subfolder: String = "",
        ) {
            require(
                subfolder in
                    listOf(
                        "",
                        "mods",
                        "saves",
                        "logs",
                        "resourcepacks",
                        "shaderpacks",
                        "screenshots",
                        "config",
                        "schematics",
                        "crash-reports",
                        "datapacks",
                    ),
            )
            val target = store.instancePath(id).resolve(subfolder)
            try {
                require(Files.readAttributes(target, BasicFileAttributes::class.java).isDirectory) {
                    "目标不是文件夹：$target"
                }
            } catch (_: NoSuchFileException) {
                target.createDirectories()
            }
            platform.openFolder(target)
        }

        fun installJava(
            major: Int,
            imageType: String? = null,
        ): String {
            require(imageType == null || imageType in listOf("jre", "jdk")) { "Java 类型无效" }
            val typeLabel = imageType?.uppercase().orEmpty()
            return jobs.enqueue(
                "安装 Java $major" + if (typeLabel.isBlank()) "" else "（$typeLabel）",
                setOf("java:$major"),
            ) {
                java.install(major, this, imageType)
            }
        }

        /** 「自动」安装：按当前实例的游戏版本换算主版本（26.x→25、1.20.5+→21、1.17–1.20.4→17、更老→8）。 */
        fun installAutoJava(imageType: String? = null): String {
            val instance =
                store.data.selectedInstanceId?.let { id ->
                    store.data.instances.find { it.id == id }
                }
            val profile =
                instance?.let {
                    runCatching {
                        VersionResolver(store.minecraftRoot(it), platform).resolve(it.versionId)
                    }.getOrNull()
                }
            val gameVersion = instance?.gameVersion?.ifBlank { profile?.id.orEmpty() }.orEmpty()
            val major =
                when {
                    gameJavaMajor(gameVersion) != null ->
                        requiredJavaMajor(gameVersion, profile?.requiredJava ?: 8)
                    profile != null -> profile.requiredJava
                    else -> 21
                }
            return installJava(major, imageType)
        }

        fun installContent(
            hit: ContentHit,
            id: String?,
        ): String {
            if (hit.type != "modpack") requireIdle(requireNotNull(id) { "请选择实例" })
            val resources =
                if (hit.type == "modpack") {
                    setOf("new-pack:${UUID.randomUUID()}")
                } else {
                    taskResourcesForInstances(store, requireNotNull(id) { "请选择实例" })
                }
            val directoryId = store.data.activeGameDirectoryId
            return jobs.enqueue("安装 ${hit.title}", resources) {
                content.installContent(hit, id, this, directoryId)
            }
        }

        fun installContent(plan: ContentInstallPlan): String {
            requireIdle(plan.instanceId)
            return jobs.enqueue(
                "安装 ${plan.title}",
                taskResourcesForInstances(store, plan.instanceId),
            ) {
                content.installPlan(plan, this)
            }
        }

        /** 更新单个模组：下载校验通过后才替换旧文件；任务中心可取消，失败时旧文件保持原样。 */
        fun updateMod(
            instanceId: String,
            item: ModUpdateItem,
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue(
                "更新模组 ${item.title}",
                taskResourcesForInstances(store, instanceId),
            ) {
                content.modUpdates.apply(instanceId, item, this)
            }
        }

        fun preparePack(
            hit: ContentHit,
            versionId: String = "",
            onReady: (PackPreview) -> Unit,
        ): String =
            jobs.enqueue("准备 ${hit.title}", setOf("pack-download:${hit.id}:$versionId")) {
                val archive = content.downloadPack(hit, this, versionId)
                onReady(content.previewPack(archive))
            }

        fun installDatapack(
            hit: ContentHit,
            versionId: String,
            instanceId: String,
            worldName: String,
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue(
                "安装数据包 ${hit.title}",
                taskResourcesForInstances(store, instanceId),
            ) {
                content.installDatapack(hit, versionId, instanceId, worldName, this)
            }
        }

        fun importPack(
            file: Path,
            optionalFiles: Set<String>? = null,
            expectedHash: String? = null,
        ): String {
            val directoryId = store.data.activeGameDirectoryId
            return jobs.enqueue("导入 ${file.fileName}", setOf("new-pack:${UUID.randomUUID()}")) {
                content.importPack(
                    file,
                    this,
                    optionalFiles = optionalFiles,
                    expectedHash = expectedHash,
                    gameDirectoryId = directoryId,
                )
            }
        }

        fun exportMrpack(
            plan: MrpackExportPlan,
            destination: Path,
            selected: Set<String> = plan.defaultSelection,
            permittedLocalFiles: Set<String> = emptySet(),
            format: PackExportFormat = PackExportFormat.Modrinth,
            options: PackExportOptions = PackExportOptions(),
        ): String {
            requireIdle(plan.instanceId)
            return jobs.enqueue(
                "导出${if (format.importable) "整合包" else "完整备份"} ${plan.metadata.name}",
                taskResourcesForInstances(store, plan.instanceId),
            ) {
                content.exportMrpack(
                    plan,
                    destination,
                    this,
                    selected,
                    permittedLocalFiles,
                    format,
                    options,
                )
            }
        }

        fun exportInstance(
            id: String,
            destination: Path,
        ): String {
            requireIdle(id)
            return jobs.enqueue(
                "导出 ${store.instance(id).name}",
                taskResourcesForInstances(store, id),
            ) {
                content.exportInstance(id, destination)
            }
        }

        /**
         * 按预览计划原地升级整合包；[expectedArchiveSha256] 防止预览后归档被替换。 需改游戏版本或加载器时由调用方确认后置
         * [allowComponentChange]，任务队列里执行并可取消。
         */
        fun upgradePack(
            instanceId: String,
            archive: Path,
            expectedArchiveSha256: String,
            allowComponentChange: Boolean = false,
            policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue(
                "升级整合包 ${store.instance(instanceId).name}",
                taskResourcesForInstances(store, instanceId),
            ) {
                content.upgradePack(
                    instanceId,
                    archive,
                    expectedArchiveSha256,
                    allowComponentChange,
                    this,
                    policy,
                )
            }
        }

        /** 在后台任务里复制实例；进度与取消沿用任务队列。 */
        fun copyInstance(
            sourceId: String,
            name: String? = null,
            copySaves: Boolean = true,
            copyMods: Boolean = true,
        ): String {
            requireIdle(sourceId)
            return jobs.enqueue(
                "复制 ${store.instance(sourceId).name}",
                taskResourcesForInstances(store, sourceId),
            ) {
                tools.copyInstance(sourceId, name, copySaves, copyMods, this)
            }
        }

        /** 整理实例目录（默认删除空目录）；重复模组等只报告。 */
        fun tidyInstance(
            id: String,
            removeEmptyDirs: Boolean = true,
        ): TidyReport {
            requireIdle(id)
            return tools.tidyInstance(id, removeEmptyDirs)
        }

        fun verifyInstance(id: String): VerifyReport = tools.verifyInstance(id)

        /** 列出实例 `saves/` 下的存档；不跟随符号链接，损坏或不可读的子项由实现跳过。 */
        fun listSaves(id: String): List<SaveEntry> = saves.listSaves(id)

        /** 列出实例的存档备份，按创建时间从新到旧。 */
        fun listSaveBackups(id: String): List<SaveBackupInfo> = saves.listBackups(id)

        /** 在后台任务里备份一个存档；备份成功后原存档保留，进度在日志页。 */
        fun backupSave(
            instanceId: String,
            saveName: String,
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue("备份存档 $saveName", taskResourcesForInstances(store, instanceId)) {
                saves.backupSave(instanceId, saveName, this)
            }
        }

        /**
         * 在后台任务里恢复备份。默认按非破坏性复制恢复成同级新世界；实例开启「恢复时替换原世界」时 先备份当前世界再替换，旧世界改名为「（替换前 HH-mm）」保留。
         */
        fun restoreSave(
            instanceId: String,
            backupId: String,
        ): String {
            requireIdle(instanceId)
            val replace = store.instance(instanceId).replaceSaveOnRestore
            return jobs.enqueue("恢复存档 $backupId", taskResourcesForInstances(store, instanceId)) {
                if (replace) {
                    saves.replaceWorldWithBackup(instanceId, backupId, this)
                } else {
                    saves.restoreSave(instanceId, backupId, this)
                }
            }
        }

        /** 切换实例设置「恢复时替换原世界」（默认关闭），立即写入配置。 */
        fun setReplaceSaveOnRestore(
            instanceId: String,
            enabled: Boolean,
        ) {
            val instance = store.instance(instanceId)
            if (instance.replaceSaveOnRestore == enabled) return
            saveInstanceConfiguration(instance.copy(replaceSaveOnRestore = enabled))
        }

        /** 列出某个世界 `datapacks/` 的内容与启用状态；level.dat 无法解析时返回只读清单。 */
        fun listDatapacks(
            instanceId: String,
            worldName: String,
        ): DatapackListing = datapacks.list(instanceId, worldName)

        /** 把本地 `.zip` 安装到世界的 `datapacks/`，校验 pack.mcmeta 与 pack_format。 */
        fun installDatapackFile(
            instanceId: String,
            worldName: String,
            archive: Path,
        ): String = datapacks.install(instanceId, worldName, archive)

        /** 启用/禁用数据包；写回 level.dat 的 Data.Packs，世界被打开时抛错。 */
        fun setDatapackEnabled(
            instanceId: String,
            worldName: String,
            name: String,
            enabled: Boolean,
        ) {
            datapacks.setEnabled(instanceId, worldName, name, enabled)
        }

        /** 删除数据包（移入回收站）。 */
        fun deleteDatapack(
            instanceId: String,
            worldName: String,
            name: String,
        ): Boolean = datapacks.delete(instanceId, worldName, name)

        /** 打开某个世界的 `datapacks/` 文件夹。 */
        fun openWorldDatapacks(
            instanceId: String,
            worldName: String,
        ) {
            val world = worldPath(instanceId, worldName)
            val directory = world.resolve("datapacks")
            if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS)) directory.createDirectories()
            platform.openFolder(directory)
        }

        /** 世界目录必须是 `saves/` 的直接子目录，防止把任意外部路径交给系统打开。 */
        private fun worldPath(
            instanceId: String,
            worldName: String,
        ): Path {
            require(worldName.isNotBlank() && worldName.length <= 80) { "世界名称无效" }
            require('/' !in worldName && '\\' !in worldName && ':' !in worldName) {
                "世界名称无效：$worldName"
            }
            val saves = store.instancePath(instanceId).resolve("saves")
            val world =
                runCatching { safePath(saves, worldName) }.getOrElse {
                    error("世界名称不安全：$worldName")
                }
            require(
                world.parent.toAbsolutePath().normalize() == saves.toAbsolutePath().normalize(),
            ) {
                "只能打开 saves 目录下的世界：$worldName"
            }
            require(world.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "找不到世界存档：$worldName" }
            return world
        }

        /** 在后台任务里把存档复制到另一个实例；同名目标自动加序号，从不覆盖。 */
        fun copySaveToInstance(
            sourceInstanceId: String,
            saveName: String,
            targetInstanceId: String,
            rename: String? = null,
        ): String {
            requireIdle(sourceInstanceId, targetInstanceId)
            return jobs.enqueue(
                "复制存档 $saveName",
                taskResourcesForInstances(store, sourceInstanceId, targetInstanceId),
            ) {
                saves.copySaveToInstance(sourceInstanceId, saveName, targetInstanceId, rename, this)
            }
        }

        /** 删除一个存档备份：移入回收站，不做不可恢复的硬删除。 */
        fun deleteSaveBackup(
            instanceId: String,
            backupId: String,
        ): Boolean {
            requireIdle(instanceId)
            return saves.deleteBackup(instanceId, backupId)
        }

        /** 删除一个存档：移入回收站，不做不可恢复的硬删除。 */
        fun deleteSave(
            instanceId: String,
            saveName: String,
        ): Boolean {
            requireIdle(instanceId)
            return saves.deleteSave(instanceId, saveName)
        }

        /** 在后台任务里导入外部存档（`.zip` 或世界文件夹）；重名加序号，无效输入不写盘。 */
        fun importSave(
            instanceId: String,
            source: Path,
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue(
                "导入存档 ${source.fileName}",
                taskResourcesForInstances(store, instanceId),
            ) {
                saves.importSave(instanceId, source, this)
            }
        }

        /** 列出实例的 `resourcepacks/` 或 `shaderpacks/` 内容。 */
        fun listPacks(
            instanceId: String,
            folder: PackFolder,
        ): List<LocalPackEntry> = content.listPacks(instanceId, folder)

        /** 在后台任务里导入资源包/光影（`.zip` 或文件夹）；重名加序号，失败不留残留。 */
        fun importPacks(
            instanceId: String,
            folder: PackFolder,
            sources: List<Path>,
        ): String {
            requireIdle(instanceId)
            return jobs.enqueue("导入${folder.title}", taskResourcesForInstances(store, instanceId)) {
                content.importPacks(instanceId, folder, sources, this)
            }
        }

        /** 删除一个资源包/光影（移入回收站）。 */
        fun deletePack(
            instanceId: String,
            folder: PackFolder,
            filename: String,
        ): Boolean {
            requireIdle(instanceId)
            return content.deletePack(instanceId, folder, filename)
        }

        /** 预览把实例整体移动到另一个游戏目录；只读。 */
        fun planInstanceMove(
            instanceId: String,
            destinationGameDirectory: Path,
        ): IsolationPlan = migration.planInstanceMove(instanceId, destinationGameDirectory)

        /** 在后台任务里把实例整体移动到另一个游戏目录（复制、校验、切记录、删源）。 */
        fun moveInstance(
            instanceId: String,
            destinationGameDirectory: Path,
        ): String {
            requireIdle()
            val plan = migration.planInstanceMove(instanceId, destinationGameDirectory)
            require(plan.blockers.isEmpty()) { plan.blockers.first() }
            return jobs.enqueue("移动 ${store.instance(instanceId).name}") {
                migration.moveInstance(instanceId, destinationGameDirectory, this)
            }
        }

        fun importExisting(source: Path): String {
            requireIdle()
            return jobs.enqueue("识别游戏目录") {
                directories.add(source)
                store.refreshInstances()
            }
        }

        fun exportLogs(destination: Path) {
            destination.writeText(
                store.state.value.logs.joinToString("\n") {
                    "${it.time} [${it.level.label}] ${it.message}"
                },
            )
        }

        /** 打开 `logs/` 目录，便于直接查看 launcher.log 与每次启动的游戏输出。 */
        fun openLogsFolder() {
            platform.openFolder(store.directories.logs)
        }

        /** 清空日志页列表；磁盘日志保留，导出与诊断不受影响。 */
        fun clearLogs() = store.clearLogs()

        fun exportDiagnostics(destination: Path) {
            exportDiagnosticBundle(store, destination)
            store.notify("诊断包已导出")
        }

        override fun close() {
            accounts.cancelLogin()
            jobs.cancelAll()
            network.close()
            scope.cancel()
            lock.release()
            lockChannel.close()
        }
    }
