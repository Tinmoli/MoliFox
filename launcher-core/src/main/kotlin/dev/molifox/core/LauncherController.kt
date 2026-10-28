package dev.molifox.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.*
import kotlinx.coroutines.*

class LauncherController(
    val platform: PlatformServices = DesktopPlatform(),
    dataDirectory: Path = platform.defaultDataDirectory,
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
        NetworkClient(
            { store.data.settings.downloadSource },
            {
                // 代理设置每次请求时读取；配置无效时直连。
                val proxy = store.data.settings
                if (
                    proxy.effectiveProxyMode() != "manual" ||
                        proxy.proxyHost.isBlank() ||
                        proxy.proxyPort !in 1..65535
                )
                    null
                else
                    runCatching {
                        Proxy(
                            if (proxy.proxyType == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                            InetSocketAddress(proxy.proxyHost, proxy.proxyPort),
                        )
                    }
                        .getOrNull()
            },
            { store.data.settings.effectiveProxyMode() == "system" },
        )
    val jobs = JobQueue(store, scope)
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
    private val migration = IsolationMigration(store)
    val game = GameLauncher(store, installer, java, accounts, scope)
    val busy: Boolean
        get() = jobs.busy || game.isRunning

    init {
        directories.refreshVersionMetadata()
        store.log("欢迎使用 MoliFox · ${platform.osName} / ${platform.architecture}")
        // 启动时自动检查更新；仅在用户同时开启开关并配置清单地址时执行。
        if (
            store.data.settings.autoCheckUpdates &&
                store.data.settings.updateManifestUrl.isNotBlank()
        )
            checkForUpdates(false)
    }

    /** 界面在窗口尺寸变化时上报当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    fun reportLauncherWindowSize(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) store.launcherWindowSize = widthPx to heightPx
    }

    /**
     * 检查启动器更新：读取更新清单（HTTPS JSON，含 version/notes 字段）并与当前版本比较。
     * 未配置清单地址时如实提示，不伪造检查结果；手动检查始终弹出提示，自动检查仅在新版本时提示。
     */
    fun checkForUpdates(manual: Boolean) {
        scope.launch {
            val url = store.data.settings.updateManifestUrl
            if (url.isBlank()) {
                val message = "未配置更新清单地址，无法检查更新"
                store.log(message)
                if (manual) store.notify(message)
                return@launch
            }
            try {
                val manifest = network.json(url).asJsonObject
                val latest = manifest.string("version")
                val notes = manifest.string("notes")
                val message =
                    when {
                        latest.isBlank() -> "更新清单缺少 version 字段"
                        latest == LAUNCHER_VERSION -> "已是最新版本 $LAUNCHER_VERSION"
                        else ->
                            "发现新版本 $latest（当前 $LAUNCHER_VERSION）" +
                                if (notes.isBlank()) "" else "：$notes"
                    }
                store.log(message)
                if (manual || (latest.isNotBlank() && latest != LAUNCHER_VERSION))
                    store.notify(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "检查更新失败：" + (error.message ?: "网络错误")
                store.log(message, true)
                if (manual) store.notify(message)
            }
        }
    }

    fun perform(action: suspend () -> Unit): Job = scope.launch {
        try {
            action()
        } catch (_: CancellationException) {} catch (error: Exception) {
            store.log(error.message ?: "操作失败", true)
            store.notify(error.message ?: "操作失败")
        }
    }

    fun requireIdle() {
        check(!busy) { "请等待游戏或下载任务结束后再操作" }
    }

    fun install(
        request: InstallRequest,
        gameDirectoryId: String? = store.data.activeGameDirectoryId,
    ): String {
        requireIdle()
        val instance = installer.createInstance(request, gameDirectoryId)
        return jobs.enqueue("安装 ${instance.name}") { installer.install(instance.id, this) }
    }

    fun repair(id: String): String {
        requireIdle()
        val instance = store.instance(id)
        return jobs.enqueue("修复 ${instance.name}") {
            if (instance.packFile.isNotBlank())
                content.importPack(Path.of(instance.packFile), this, instance.id)
            else installer.install(id, this)
        }
    }

    fun launch(id: String): String {
        requireIdle()
        require(store.instance(id).installed) { "此实例尚未安装完成，请先修复" }
        require(store.data.activeAccountId != null) { "请先添加并选择游戏账户" }
        return jobs.enqueue("启动 ${store.instance(id).name}") { game.launch(id, this) }
    }

    fun selectInstance(id: String) {
        val instance = store.instance(id)
        store.mutate {
            it.copy(selectedInstanceId = id, activeGameDirectoryId = instance.gameDirectoryId)
        }
    }

    fun selectGameDirectory(id: String?) {
        requireIdle()
        directories.select(id)
    }

    fun refreshGameDirectory() {
        requireIdle()
        directories.refresh()
    }

    /**
     * 启动时自动发现：按启动器自身位置（[LauncherLocation]，不是 user.dir）解析旁边的
     * `.minecraft`、启动器自身即 `.minecraft`/游戏根、以及 `MoliFox/` 旁的目录，只读登记现有版本。
     * 已登记目录不会重复添加，也不会覆盖用户的选择；没有发现时不产生任何日志或提示。
     */
    fun discoverStartupGameDirectories(): Int =
        directories.discover(StartupGameRoots.resolve(LauncherLocation.locate()))

    fun removeGameDirectory(id: String) {
        requireIdle()
        directories.remove(id)
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
                .copy(name = name, memoryMb = memoryMb, javaPath = javaPath, isolation = isolation)
        )
    }

    fun saveInstanceConfiguration(configuration: GameInstance) {
        requireIdle()
        require(configuration.name.isNotBlank() && configuration.name.length <= 80) {
            "实例名称需要 1–80 个字符"
        }
        require(
            configuration.memoryMb == 0 ||
                configuration.memoryMb in 512..maxOf(512, platform.totalMemoryMb - 512)
        ) {
            "实例内存设置无效"
        }
        require(
            configuration.initialMemoryMb == 0 ||
                configuration.initialMemoryMb in
                    128..(if (
                            configuration.autoMemory == true ||
                                (configuration.autoMemory == null &&
                                    configuration.memoryMb == 0 &&
                                    store.data.settings.autoMemory)
                        )
                            maxOf(512, platform.totalMemoryMb - 512)
                        else
                            configuration.memoryMb.takeIf { it > 0 }
                                ?: store.data.settings.memoryMb)
        ) {
            "初始内存需为 0（继承）或在 128 MB 到最大内存之间"
        }
        require(configuration.width == 0 || configuration.width in 640..7680) { "游戏窗口宽度无效" }
        require(configuration.height == 0 || configuration.height in 480..4320) { "游戏窗口高度无效" }
        require(configuration.windowMode in listOf("inherit", "windowed", "fullscreen")) {
            "窗口模式无效"
        }
        require(configuration.notes.length <= 2000) { "备注最多 2000 字" }
        validateExtraJvmArguments(configuration.extraJvmArgs)
        configuration.advancedLaunch?.let { validateAdvancedLaunchSettings(it, platform.osName) }
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
                advancedLaunch = configuration.advancedLaunch,
                notes = configuration.notes,
            )
        }
        store.notify("版本设置已保存")
    }

    /** 更换既有实例的模组加载器及版本；复用安装流程补齐依赖，可从日志页取消任务。 */
    fun changeInstanceLoader(
        instanceId: String,
        loader: LoaderType,
        loaderVersion: String,
    ): String {
        requireIdle()
        val instance = store.instance(instanceId)
        return jobs.enqueue("更新加载器 ${instance.name}") {
            installer.changeLoader(instanceId, loader, loaderVersion, this)
        }
    }

    fun restoreInstanceComponents(instanceId: String): String {
        requireIdle()
        return jobs.enqueue("回退组件 ${store.instance(instanceId).name}") {
            installer.restoreComponents(instanceId, this)
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

    /** 切换内容（模组/整合包等）下载源；CurseForge 在 API Key 开通前仅可选中、检索会提示。 */
    fun setContentDownloadSource(source: String) {
        require(source in listOf("modrinth", "curseforge")) { "未知的内容下载源" }
        store.mutate { it.copy(settings = it.settings.copy(contentDownloadSource = source)) }
        store.notify(
            if (source == "curseforge") "已选择 CurseForge：检索待 API Key 开通后开放" else "内容下载源已切换为 Modrinth"
        )
    }

    fun toggleFavorite(id: String) {
        store.updateInstance(id) { it.copy(favorite = !it.favorite) }
    }

    /** 「删除实例」会永久删除的绝对路径（版本文件夹与游戏文件夹）；无法解析或只清记录时为空，仅用于对话框展示。 */
    fun deletionTargets(instanceId: String): List<Path> =
        runCatching { deletion.plan(instanceId).targets }.getOrElse { emptyList() }

    /** 删除实例会一并删除的存档名单（名称 + 上次修改时间），只用于确认框展示。 */
    fun deletionWorlds(instanceId: String): List<InstanceDeletionWorld> =
        runCatching { deletion.worlds(instanceId) }.getOrElse { emptyList() }

    /** 实例是否有独立游戏目录；为真时存档、资源包、Mod 都在会被删除的目录里。 */
    fun hasSeparateGameDirectory(instanceId: String): Boolean =
        runCatching {
                val instance = store.instance(instanceId)
                store.gamePath(instance).toAbsolutePath().normalize() !=
                    store.minecraftRoot(instance).toAbsolutePath().normalize()
            }
            .getOrDefault(false)

    /**
     * 删除实例：先清除启动器记录，再把实例自己的版本文件夹与游戏文件夹移到回收站或永久删除。
     *
     * [deleteFiles] 为 false 时只清记录，保留游戏文件（界面不再提供入口）。
     * [permanent] 为 true（界面上按住 Shift 确认）时永久删除，否则移入系统回收站；回收站不可用时自动回退永久删除，
     * 结果里的 [InstanceDeletionReport.movedToTrash] 会说明实际去向。
     * 实例正在运行、路径无法解析、目标是符号链接或不在实例自己目录下时返回失败，磁盘完全不动；
     * 删除中途失败时返回失败，已处理的部分不回滚。
     */
    suspend fun deleteInstance(
        instanceId: String,
        deleteFiles: Boolean = true,
        permanent: Boolean = false,
    ): Result<InstanceDeletionReport> =
        withContext(Dispatchers.IO) {
            try {
                require(store.state.value.runningInstanceId != instanceId) {
                    "实例正在运行，请先退出游戏再删除"
                }
                requireIdle()
                val instance = store.instance(instanceId)
                val plan =
                    if (deleteFiles) deletion.plan(instanceId)
                    else InstanceDeletionPlan(emptyList())
                plan.preserved.forEach { store.log("删除实例时保留：$it") }
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
                            if (data.selectedInstanceId == instanceId) fallback?.id
                            else data.selectedInstanceId,
                    )
                }
                icons.releaseUnused(instance.iconId)
                icons.releaseUnused(instance.packIconId)
                val mode =
                    when {
                        !deleteFiles -> "仅清除记录，游戏文件保留"
                        report.movedToTrash -> "版本文件夹与游戏文件夹已移入回收站"
                        else -> "版本文件夹与游戏文件夹已永久删除"
                    }
                store.log("已删除实例：${instance.name}（$mode，共 ${report.freedBytes} 字节）")
                Result.success(report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                store.log("删除实例失败：${failure.message ?: "未知错误"}", true)
                Result.failure(failure)
            }
        }

    fun saveSettings(settings: LauncherSettings) {
        validateAdvancedLaunchSettings(settings.advancedLaunch, platform.osName)
        if (settings.isolateVersions != store.data.settings.isolateVersions) requireIdle()
        require(
            Path.of(settings.gameRoot).isAbsolute && Path.of(settings.gameRoot).parent != null
        ) {
            "请选择专用的游戏文件夹"
        }
        if (settings.gameRoot != store.data.settings.gameRoot) {
            requireIdle()
            require(store.data.instances.isEmpty()) { "已有实例时不能直接切换目录，请先导出并删除实例" }
        }
        require(settings.memoryMb in 512..maxOf(512, platform.totalMemoryMb - 512)) {
            "内存需在 512 MB 到物理内存减 512 MB 之间"
        }
        require(settings.width in 640..7680 && settings.height in 480..4320) { "游戏窗口尺寸无效" }
        require(settings.initialMemoryMb in 128..settings.memoryMb) { "初始内存需在 128 MB 到最大内存之间" }
        require(settings.themeMode in listOf("", "light", "dark", "system")) { "主题模式无效" }
        require(settings.backgroundFit in listOf("crop", "fit", "stretch")) { "背景适配模式无效" }
        require(
            settings.backgroundAlignment in
                listOf(
                    "topLeft",
                    "topCenter",
                    "topRight",
                    "centerLeft",
                    "center",
                    "centerRight",
                    "bottomLeft",
                    "bottomCenter",
                    "bottomRight",
                )
        ) {
            "背景对齐位置无效"
        }
        require(settings.uiScale.isFinite() && settings.uiScale in .85f..1.2f) {
            "界面缩放需在 85%–120% 之间"
        }
        require(settings.backgroundOpacity.isFinite() && settings.backgroundOpacity in 0f..1f) {
            "背景浓度需在 0–100% 之间"
        }
        require(settings.surfaceOpacity.isFinite() && settings.surfaceOpacity in .6f..1f) {
            "面板不透明度需在 60%–100% 之间"
        }
        require(settings.backgroundBlur.isFinite() && settings.backgroundBlur in 0f..40f) {
            "磨砂强度需在 0–40 之间"
        }
        require(settings.fontScale.isFinite() && settings.fontScale in .85f..1.25f) {
            "字号需在 85%–125% 之间"
        }
        require(settings.fontChoice.length <= 2048 && settings.customFonts.size <= 12) { "字体设置无效" }
        require(
            settings.pages.home.recentCount in 1..12 &&
                settings.pages.versions.view in listOf("list", "grid") &&
                settings.pages.versions.sort in listOf("recent", "name", "created") &&
                settings.pages.logs.defaultTab in listOf("tasks", "logs") &&
                settings.pages.logs.fontSize in 10..18
        ) {
            "页面设置无效"
        }
        require(
            settings.maxConcurrent in 1..32 &&
                settings.downloadSource in listOf("auto", "official", "bmclapi") &&
                settings.versionListSource in listOf("auto", "official", "bmclapi") &&
                settings.contentDownloadSource in listOf("modrinth", "curseforge")
        ) {
            "下载设置无效"
        }
        require(
            settings.accentColor == "sakura" ||
                Regex("^#[0-9A-Fa-f]{6}$").matches(settings.accentColor) ||
                settings.accentColor in listOf("ocean", "mint", "amber", "lavender", "graphite")
        ) {
            "主题颜色无效"
        }
        require(settings.language == "zh-CN") { "当前版本仅提供简体中文界面" }
        require(settings.gameWindowMode in listOf("launcher", "fullscreen", "custom")) {
            "游戏窗口模式无效"
        }
        require(settings.proxyType in listOf("http", "socks")) { "代理类型无效" }
        require(settings.proxyMode in listOf("", "system", "direct", "manual")) { "代理模式无效" }
        if (settings.effectiveProxyMode() == "manual") {
            require(
                settings.proxyHost.isNotBlank() &&
                    !settings.proxyHost.contains(':') &&
                    settings.proxyPort in 1..65535
            ) {
                "代理地址或端口无效"
            }
        }
        require(
            settings.updateManifestUrl.isEmpty() ||
                runCatching {
                        val uri = URI(settings.updateManifestUrl)
                        uri.scheme == "https" && uri.userInfo == null
                    }
                    .getOrDefault(false)
        ) {
            "更新清单地址必须是 HTTPS 链接"
        }
        if (settings.downloadCachePath.isNotBlank()) {
            require(Path.of(settings.downloadCachePath).isAbsolute) { "下载缓存文件夹必须是绝对路径" }
        }
        require(settings.curseForgeApiKey.length <= 512) { "CurseForge API Key 过长" }
        require(
            settings.microsoftClientId.isBlank() ||
                runCatching { UUID.fromString(settings.microsoftClientId) }.isSuccess
        ) {
            "Microsoft 客户端 ID 格式无效"
        }
        validateExtraJvmArguments(settings.extraJvmArgs)
        if (settings.downloadCachePath != store.data.settings.downloadCachePath)
            store.directories.cache(settings.downloadCachePath).initialize()
        store.mutate {
            it.copy(
                settings = settings.copy(proxyEnabled = settings.effectiveProxyMode() == "manual")
            )
        }
        store.notify("设置已保存")
    }

    /**
     * 返回账户皮肤纹理的本地缓存路径（MoliFox/cache/skins/<uuid>.png）。 离线账户或未配置皮肤时为 null；在线账户首次调用会从 Mojang 官方 CDN
     * 下载。
     */
    suspend fun accountSkin(account: Account, refresh: Boolean = false): Path? =
        skins.texture(account, refresh)

    fun openFolder(id: String, subfolder: String = "") {
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
                )
        )
        platform.openFolder(store.instancePath(id).resolve(subfolder).apply { createDirectories() })
    }

    fun installJava(major: Int): String =
        jobs.enqueue("安装 Java $major") { java.install(major, this) }

    fun installContent(hit: ContentHit, id: String?): String {
        requireIdle()
        return jobs.enqueue("安装 ${hit.title}") { content.installContent(hit, id, this) }
    }

    fun installContent(plan: ContentInstallPlan): String {
        requireIdle()
        return jobs.enqueue("安装 ${plan.title}") { content.installPlan(plan, this) }
    }

    fun preparePack(
        hit: ContentHit,
        versionId: String = "",
        onReady: (PackPreview) -> Unit,
    ): String {
        requireIdle()
        return jobs.enqueue("准备 ${hit.title}") {
            val archive = content.downloadPack(hit, this, versionId)
            onReady(content.previewPack(archive))
        }
    }

    fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
    ): String {
        requireIdle()
        return jobs.enqueue("安装数据包 ${hit.title}") {
            content.installDatapack(hit, versionId, instanceId, worldName, this)
        }
    }

    fun importPack(
        file: Path,
        optionalFiles: Set<String>? = null,
        expectedHash: String? = null,
    ): String {
        requireIdle()
        return jobs.enqueue("导入 ${file.fileName}") {
            content.importPack(
                file,
                this,
                optionalFiles = optionalFiles,
                expectedHash = expectedHash,
            )
        }
    }

    fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        selected: Set<String> = plan.defaultSelection,
        permittedLocalFiles: Set<String> = emptySet(),
        format: PackExportFormat = PackExportFormat.Modrinth,
    ): String {
        requireIdle()
        return jobs.enqueue("导出标准整合包 ${plan.metadata.name}") {
            content.exportMrpack(plan, destination, this, selected, permittedLocalFiles, format)
        }
    }

    fun exportInstance(id: String, destination: Path): String {
        requireIdle()
        return jobs.enqueue("导出 ${store.instance(id).name}") {
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
        requireIdle()
        return jobs.enqueue("升级整合包 ${store.instance(instanceId).name}") {
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
        requireIdle()
        return jobs.enqueue("复制 ${store.instance(sourceId).name}") {
            tools.copyInstance(sourceId, name, copySaves, copyMods, this)
        }
    }

    /** 整理实例目录（默认删除空目录）；重复模组等只报告。 */
    fun tidyInstance(id: String, removeEmptyDirs: Boolean = true): TidyReport {
        requireIdle()
        return tools.tidyInstance(id, removeEmptyDirs)
    }

    fun verifyInstance(id: String): VerifyReport = tools.verifyInstance(id)

    /** 列出实例 `saves/` 下的存档；不跟随符号链接，损坏或不可读的子项由实现跳过。 */
    fun listSaves(id: String): List<SaveEntry> = saves.listSaves(id)

    /** 列出实例的存档备份，按创建时间从新到旧。 */
    fun listSaveBackups(id: String): List<SaveBackupInfo> = saves.listBackups(id)

    /** 在后台任务里备份一个存档；备份成功后原存档保留，进度在日志页。 */
    fun backupSave(instanceId: String, saveName: String): String {
        requireIdle()
        return jobs.enqueue("备份存档 $saveName") { saves.backupSave(instanceId, saveName, this) }
    }

    /** 在后台任务里恢复备份；[overwrite] 为 false 时目标已存在会被拒绝，为 true 时先自动备份现有存档。 */
    fun restoreSave(instanceId: String, backupId: String, overwrite: Boolean = false): String {
        requireIdle()
        return jobs.enqueue("恢复存档 $backupId") {
            saves.restoreSave(instanceId, backupId, overwrite, this)
        }
    }

    /** 在后台任务里把存档复制到另一个实例；同名目标自动加序号，从不覆盖。 */
    fun copySaveToInstance(
        sourceInstanceId: String,
        saveName: String,
        targetInstanceId: String,
        rename: String? = null,
    ): String {
        requireIdle()
        return jobs.enqueue("复制存档 $saveName") {
            saves.copySaveToInstance(sourceInstanceId, saveName, targetInstanceId, rename, this)
        }
    }

    /** 删除一个存档备份：移入回收站，不做不可恢复的硬删除。 */
    fun deleteSaveBackup(instanceId: String, backupId: String): Boolean {
        requireIdle()
        return saves.deleteBackup(instanceId, backupId)
    }

    /** 删除一个存档：移入回收站，不做不可恢复的硬删除。 */
    fun deleteSave(instanceId: String, saveName: String): Boolean {
        requireIdle()
        return saves.deleteSave(instanceId, saveName)
    }

    /** 预览切换隔离方式要搬运的文件；只读，不修改磁盘。 */
    fun planIsolationChange(
        instanceId: String,
        isolate: Boolean,
        copySaves: Boolean = true,
        moveSource: Boolean = false,
    ): IsolationPlan = migration.planIsolation(instanceId, isolate, copySaves, moveSource)

    /** 在后台任务里切换隔离方式；冲突会直接拒绝，失败或取消时回滚文件与实例记录。 */
    fun changeIsolation(
        instanceId: String,
        isolate: Boolean,
        copySaves: Boolean = true,
        moveSource: Boolean = false,
    ): String {
        requireIdle()
        val plan = migration.planIsolation(instanceId, isolate, copySaves, moveSource)
        require(plan.blockers.isEmpty()) { plan.blockers.first() }
        return jobs.enqueue("切换隔离 ${store.instance(instanceId).name}") {
            migration.changeIsolation(instanceId, isolate, copySaves, moveSource, this)
        }
    }

    /** 预览把实例整体移动到另一个游戏目录；只读。 */
    fun planInstanceMove(instanceId: String, destinationGameDirectory: Path): IsolationPlan =
        migration.planInstanceMove(instanceId, destinationGameDirectory)

    /** 在后台任务里把实例整体移动到另一个游戏目录（复制、校验、切记录、删源）。 */
    fun moveInstance(instanceId: String, destinationGameDirectory: Path): String {
        requireIdle()
        val plan = migration.planInstanceMove(instanceId, destinationGameDirectory)
        require(plan.blockers.isEmpty()) { plan.blockers.first() }
        return jobs.enqueue("移动 ${store.instance(instanceId).name}") {
            migration.moveInstance(instanceId, destinationGameDirectory, this)
        }
    }

    fun importExisting(source: Path): String {
        requireIdle()
        return jobs.enqueue("识别游戏目录") { directories.add(source) }
    }

    fun exportLogs(destination: Path) {
        destination.writeText(
            store.state.value.logs.joinToString("\n") {
                "${it.time} [${if (it.isError) "ERROR" else if (it.isGame) "GAME" else "INFO"}] ${it.message}"
            }
        )
    }

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
