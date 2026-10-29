// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置持久化、实例路径和界面状态。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LauncherStore(
    val platform: PlatformServices,
    val dataDirectory: Path = platform.defaultDataDirectory,
) {
    val directories = LauncherDirectories(dataDirectory)
    val recentUsage: RecentUsage by lazy { RecentUsage(this) }
    private val config = directories.configuration
    private val mutableState: MutableStateFlow<LauncherState>
    val state: StateFlow<LauncherState>
        get() = mutableState.asStateFlow()

    val data: LauncherData
        get() = mutableState.value.data

    val minecraftRoot: Path
        get() = Path.of(data.settings.gameRoot)

    /** 下载缓存目录：用户自定义 downloadCachePath，为空时使用数据目录下的 cache/。 */
    val cacheDirectory: Path
        get() =
            data.settings.downloadCachePath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: dataDirectory.resolve("cache")

    val downloadDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).downloads

    val metadataDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).metadata

    val temporaryDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).temporary

    /** 界面在窗口尺寸变化时上报的当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    @Volatile var launcherWindowSize: Pair<Int, Int>? = null

    fun minecraftRoot(instance: GameInstance): Path =
        instance.gameDirectoryId?.let { directoryId ->
            Path.of(
                data.gameDirectories.find { it.id == directoryId }?.path
                    ?: error("此版本的游戏目录已移除，请重新选择目录")
            )
        } ?: minecraftRoot

    /**
     * 实例的有效隔离状态。显式选择永远优先；未选择时先用目录证据推断（外部启动器的配置
     * 读不到，只看 `versions/<名字>/` 里有没有自己的数据），再回落到全局开关。true 时工作目录是
     * `versions/<实例目录>`，false 时是游戏根。实例覆盖优先、未覆盖才用全局默认。
     */
    fun isolated(instance: GameInstance): Boolean =
        when (isolationSource(instance)) {
            IsolationSource.Explicit -> instance.isolation == VersionIsolation.Enabled
            IsolationSource.Inferred -> true
            IsolationSource.GlobalDefault -> data.settings.isolateVersions
        }

    /**
     * 有效隔离值的来源：显式设置（版本隔离 / 共用游戏目录）> 目录推断 > 全局默认。
     * 「继承全局设置」不是显式选择，因此仍允许推断；用户保存具体项后就会覆盖推断。
     */
    fun isolationSource(instance: GameInstance): IsolationSource =
        when (instance.isolation) {
            VersionIsolation.Enabled,
            VersionIsolation.Disabled -> IsolationSource.Explicit
            VersionIsolation.Inherit ->
                if (isolationEvidence(instance) != null) IsolationSource.Inferred
                else IsolationSource.GlobalDefault
        }

    /** 推断依据的目录条目名（saves、mods 等）；显式设置或没有证据时为 null。 */
    fun isolationEvidence(instance: GameInstance): String? =
        if (instance.isolation != VersionIsolation.Inherit) null
        else
            runCatching {
                    InstanceCatalog.gameDataMarker(
                        versionGamePath(instance)
                    )
                }
                .getOrNull()

    /** 界面展示用：推断值明确标注为「自动推断」，不冒充用户选择。 */
    fun isolationTitle(instance: GameInstance): String =
        when (isolationSource(instance)) {
            IsolationSource.Explicit ->
                if (instance.isolation == VersionIsolation.Enabled) "版本隔离" else "共用游戏目录"
            IsolationSource.Inferred -> "版本隔离（自动推断）"
            IsolationSource.GlobalDefault ->
                if (data.settings.isolateVersions) "版本隔离（全局默认）" else "共用游戏目录（全局默认）"
        }

    /** 推断日志每个实例只写一次；重扫时证据不变就不重复刷屏。 */
    private val inferenceLogged = mutableSetOf<String>()

    private val backup = directories.previousConfiguration

    /** 上一轮扫描签名：界面重新可见时只做一次廉价的目录名/mtime 比较，不启动轮询。 */
    @Volatile private var lastScanSignature: String? = null

    /** 旧配置里选中的实例目录名；首轮扫描把它映射到新的稳定 id。 */
    private var migrationSelectedFolder: String? = null

    /** 安装中的目标目录：只在内存里，扫描时跳过它们的空目录。 */
    private val pendingInstalls = mutableMapOf<String, PendingInstall>()

    /** 载入时发现 CurseForge 服务地址为空/非法，初始化完成后补一条日志。 */
    private var curseForgeBaseFallback = false

    /** 旧 id（安装时随机生成、或上一次扫描前使用）到扫描稳定 id 的别名，仅本会话有效。 */
    private val instanceIdAliases = mutableMapOf<String, String>()

    @Synchronized
    fun beginPendingInstall(name: String, gameVersion: String, loaderId: String = "") {
        if (name.isBlank()) return
        pendingInstalls[name] = PendingInstall(name, gameVersion, loaderId)
    }

    @Synchronized
    fun endPendingInstall(name: String) {
        pendingInstalls.remove(name)
    }

    @Synchronized
    fun pendingInstall(name: String): PendingInstall? = pendingInstalls[name]

    /** 安装器可提前算出的稳定实例 id；与扫描使用的是同一个函数。 */
    fun instanceIdFor(folder: String, gameDirectoryId: String?): String =
        InstanceCatalog.instanceId(gameDirectoryId, folder)

    init {
        directories.initialize()
        var recoveryMessage: String? = null
        val defaults =
            LauncherData(
                settings =
                    LauncherSettings(
                        // 默认游戏目录 = 启动器所在目录下的 .minecraft（数据目录默认是启动器旁的 MoliFox/）。
                        // 数据目录本身就在 .minecraft 里、或这一层就是游戏根时直接用这一层，不再套一层。
                        gameRoot =
                            defaultGameRoot(dataDirectory.parent ?: dataDirectory).toString(),
                        proxyMode = "system",
                        memoryMb = minOf(4096, maxOf(1024, platform.totalMemoryMb / 4 / 256 * 256)),
                        autoMemory = true,
                    )
            )
        val loaded =
            if (config.exists())
                try {
                    readData(config)
                } catch (denied: java.nio.file.AccessDeniedException) {
                    throw denied
                } catch (denied: SecurityException) {
                    throw denied
                } catch (_: Exception) {
                    Files.copy(
                        config,
                        directories.configurationBackups.resolve(
                            "launcher.damaged-${System.currentTimeMillis()}-${UUID.randomUUID()}.json"
                        ),
                    )
                    pruneDamagedConfigurations()
                    runCatching { readData(backup) }
                        .getOrElse { defaults }
                        .also { recoveryMessage = "原配置已保留，已恢复备份或默认配置。" }
                }
            else defaults
        if (config.exists() && recoveryMessage == null)
            Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING)
        directories.cache().initialize()
        if (loaded.settings.downloadCachePath.isNotBlank())
            directories.cache(loaded.settings.downloadCachePath).initialize()
        val legacy = if (config.exists()) legacyInstances(config) else emptyList()
        val migrated = migrateLegacyInstances(loaded, legacy)
        if (!config.exists() || recoveryMessage != null || legacy.isNotEmpty())
            writeJson(config, migrated) // 迁移后立刻写回：配置里不再有实例列表。
        mutableState = MutableStateFlow(LauncherState(migrated))
        if (curseForgeBaseFallback)
            log("CurseForge 服务地址无效，已使用默认镜像：$DEFAULT_CURSE_FORGE_BASE_URL", true)
        refreshInstances()
        // 已删目录留下的设置键保持惰性，不因一次启动就删掉；下一次保存时由 mutate 统一清理。
        recoveryMessage?.let { log(it, true) }
        if (legacy.isNotEmpty()) {
            val current = data
            val legacyKeys =
                legacy.map { it.instanceSettingsKey() }.filter { it.isNotBlank() }.toSet()
            // 迁移只为磁盘上仍存在的目录写入设置键；这里按最终设置表计数。
            val keptKeys =
                legacyKeys.filter { key -> current.instanceSettings.containsKey(key) }
            log(
                "实例列表迁移：保留 ${keptKeys.size} 个实例设置，跳过 ${legacyKeys.size - keptKeys.size} 个已不存在的目录；" +
                    "此后以 versions/ 扫描为准"
            )
        }
    }

    private fun readData(path: Path): LauncherData {
        val json = readObject(path)
        // schemaVersion 2 保持不变：旧配置的 instances 会被迁移，新配置不再写这个字段。
        require(
            json.int("schemaVersion") == 2 && json.has("accounts") && json.has("settings")
        ) {
            "配置格式无效"
        }
        val value = gson.fromJson(json, LauncherData::class.java)
        require(value.settings.gameRoot.isNotBlank() && Path.of(value.settings.gameRoot).isAbsolute)
        // 已删除的 mirror 取值读入即按 official 处理；后续任意一次保存会把新值写回配置。
        // 旧配置只有一个 downloadSource：拆成「版本列表源」与「文件下载源」时两边都沿用它的值。
        val raw = runCatching { json.getAsJsonObject("settings") }.getOrNull() ?: JsonObject()
        val legacyFileSource = normalizedDownloadSource(raw.string("downloadSource"))
        val versionListSource =
            if (raw.has("versionListSource")) normalizedDownloadSource(raw.string("versionListSource"))
            else legacyFileSource
        // 版本隔离默认开启：旧配置里没有这个键表示用户从未选择，按开启读入；显式保存的 false 原样保留。
        val isolateVersions =
            if (raw.has("isolateVersions")) value.settings.isolateVersions else true
        val rawCurseForgeBase = value.settings.curseForgeBaseUrl.orEmpty()
        val curseForgeBase = normalizedCurseForgeBaseUrl(rawCurseForgeBase)
        curseForgeBaseFallback =
            curseForgeBase != rawCurseForgeBase.trim().trimEnd('/')
        return value.copy(
            settings =
                value.settings.copy(
                    isolateVersions = isolateVersions,
                    javaDownloadSource = value.settings.effectiveJavaDownloadSource,
                    downloadSource = normalizedDownloadSource(value.settings.downloadSource),
                    versionListSource = versionListSource,
                    mirrorConcurrency =
                        normalizedMirrorConcurrency(value.settings.mirrorConcurrency),
                    // 旧配置没有这个字段（或手改成了非法值）时直接显示默认镜像。
                    curseForgeBaseUrl = curseForgeBase,
                )
        )
    }

    @Synchronized
    fun mutate(block: (LauncherData) -> LauncherData) {
        val current = data
        val requested = block(current)
        // 唯一的持久实例数据是这张按目录名索引的设置表；任何一次保存都顺手同步并清掉已不存在的键。
        val keys = requested.instances.map { it.instanceSettingsKey() }.filter { it.isNotBlank() }.toSet()
        val next =
            requested.copy(
                instanceSettings =
                    (requested.instanceSettings +
                            requested.instances.associate {
                                it.instanceSettingsKey() to InstanceSettings.of(it)
                            })
                        .filterKeys { it in keys }
            )
        if (config.isRegularFile()) Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING)
        writeJson(config, next)
        mutableState.value = mutableState.value.copy(data = next)
    }

    @Synchronized
    internal fun updateState(block: (LauncherState) -> LauncherState) {
        mutableState.value = block(mutableState.value)
    }

    fun updateInstance(id: String, block: (GameInstance) -> GameInstance) = mutate { current ->
        val target =
            resolveInstance(current, id) ?: error("找不到游戏实例")
        current.copy(instances = current.instances.map { if (it.id == target.id) block(it) else it })
    }

    /** 按 id 查；也接受目录名/显示名和本会话已知的旧 id，方便扫描结果和旧调用方混用。 */
    fun instance(id: String): GameInstance = resolveInstance(data, id) ?: error("找不到游戏实例")

    private fun resolveInstance(source: LauncherData, id: String): GameInstance? =
        source.instances.find { it.id == id }
            ?: instanceIdAliases[id]?.let { alias -> source.instances.find { it.id == alias } }
            ?: source.instances.find { it.instanceSettingsKey() == id }
            ?: source.instances.find { it.name == id }

    fun instanceSettings(name: String): InstanceSettings? = data.instanceSettings[name]

    /** 重命名后把设置键从旧目录名搬到新目录名；实例 id 与选中项不变。 */
    @Synchronized
    fun renameInstanceSettings(oldName: String, newName: String) {
        if (oldName == newName) return
        mutate { current ->
            val moved = (current.instanceSettings[oldName] ?: current.instanceSettings[newName])
            current.copy(
                instances =
                    current.instances.map { instance ->
                        if (instance.instanceSettingsKey() == oldName)
                            instance.copy(
                                name = newName,
                                gameDirectoryName = newName,
                                versionId =
                                    if (instance.versionId == oldName) newName
                                    else instance.versionId,
                            )
                        else instance
                    },
                instanceSettings =
                    if (moved == null) current.instanceSettings
                    else current.instanceSettings - oldName + (newName to moved.copy(displayName = newName)),
            )
        }
    }

    fun instancePath(id: String): Path = gamePath(instance(id))

    fun gamePath(instance: GameInstance): Path {
        require(instance.id.isNotBlank()) { "实例标识无效" }
        if (!isolated(instance)) return minecraftRoot(instance)
        return versionGamePath(instance)
    }

    fun versionGamePath(instance: GameInstance): Path =
        safePath(
            minecraftRoot(instance).resolve("versions"),
            safeFilename(instance.instanceFolderName()),
        )

    /**
     * 重新扫描所有游戏根的 `versions/<目录>/`，重建运行时实例列表。配置只更新选中项，
     * 不写入实例记录；新增目录出现、删除目录消失、损坏 JSON 变成「无法识别」都在这里统一发生。
     *
     * @return 可启动（未损坏）的实例数量
     */
    @Synchronized
    fun refreshInstances(): Int {
        val current = data
        val roots = InstanceCatalog.roots(current)
        val previous = current.instances.associateBy { it.gameDirectoryId to it.instanceSettingsKey() }
        var normalizedIds = 0
        val instances =
            InstanceCatalog.scan(current).mapNotNull { scanned ->
                val key = scanned.instanceSettingsKey()
                val old = previous[scanned.gameDirectoryId to key]
                // 正在安装的空目录没有 JSON，扫描会判成损坏；此时保留内存里的目标实例（若有），
                // 绝不把构造中的目录显示成「无法识别」。安装结束后扫描到有效 JSON 再收敛 id。
                if (scanned.broken && pendingInstalls.containsKey(key)) {
                    return@mapNotNull old?.copy(broken = false, brokenReason = "")
                }
                val settings = current.instanceSettings[key] ?: old?.let(InstanceSettings::of)
                // 有效隔离由 isolationSource 统一解析：显式设置 > 目录证据 > 全局默认。
                // 推断不写进实例记录，避免把推断值当成用户选择持久化。
                val decorated = settings?.applyTo(scanned) ?: scanned
                if (old != null && old.id != scanned.id) {
                    instanceIdAliases[old.id] = scanned.id
                    normalizedIds++
                }
                decorated.copy(id = scanned.id)
            }
        val previousSelected = current.instances.find { it.id == current.selectedInstanceId }
        val selected =
            instances.find { it.id == current.selectedInstanceId }?.id
                ?: previousSelected
                    ?.let { old ->
                        instances
                            .find {
                                it.gameDirectoryId == old.gameDirectoryId &&
                                    it.instanceSettingsKey() == old.instanceSettingsKey()
                            }
                            ?.id
                    }
                ?: migrationSelectedFolder?.let { folder ->
                    instances.find { it.instanceSettingsKey() == folder }?.id
                }
                ?: instances
                    .filter { it.gameDirectoryId == current.activeGameDirectoryId }
                    .firstOrNull()
                    ?.id
                ?: instances.firstOrNull()?.id
        migrationSelectedFolder = null
        lastScanSignature = InstanceCatalog.signature(roots)
        val next = current.copy(instances = instances, selectedInstanceId = selected)
        if (next != current) updateState { it.copy(data = next) }
        // 选中项从旧随机 id / 已删除 id 收敛到扫描结果时写回，下一次启动不会再退回第一个目录。
        if (next.selectedInstanceId != current.selectedInstanceId) writeJson(config, next)
        if (normalizedIds > 0)
            log("实例 id 已规范化为目录稳定值：$normalizedIds 个（目录名仍是唯一身份）")
        // 外部启动器的配置读不到，只按目录证据推断；每个实例每个会话只记一行。
        for (instance in instances) {
            val evidence = isolationEvidence(instance) ?: continue
            val key = "${instance.gameDirectoryId.orEmpty()}/${instance.instanceSettingsKey()}"
            if (inferenceLogged.add(key))
                log("推断版本隔离：${instance.name}（目录内有 $evidence）")
        }
        return instances.count { !it.broken }
    }

    /** 页面重新可见时调用：目录名或版本 JSON 的 mtime/size 没变就不重扫，不建立轮询。 */
    fun refreshInstancesIfChanged(): Boolean {
        val signature = InstanceCatalog.signature(InstanceCatalog.roots(data))
        if (signature == lastScanSignature) return false
        refreshInstances()
        return true
    }

    fun rescanInstances(): Int = refreshInstances()

    /** 旧配置里的实例数组；只用于一次性迁移，之后配置不再写这个字段。 */
    private fun legacyInstances(path: Path): List<GameInstance> =
        runCatching {
                readObject(path).getAsJsonArray("instances")?.mapNotNull { element ->
                    runCatching { gson.fromJson(element, GameInstance::class.java) }.getOrNull()
                } ?: emptyList()
            }
            .getOrDefault(emptyList())

    /**
     * 迁移旧 `LauncherData.instances`：只为磁盘上仍存在的目录保留设置，随后丢弃列表。
     * 旧的显示名、隔离、Java、图标、收藏和最近游玩都落在按目录名索引的表里。
     */
    private fun migrateLegacyInstances(
        data: LauncherData,
        legacy: List<GameInstance>,
    ): LauncherData {
        if (legacy.isEmpty()) return data
        val settings = data.instanceSettings.toMutableMap()
        for (record in legacy) {
            val key = record.instanceSettingsKey()
            if (key.isBlank()) continue
            if (record.id == data.selectedInstanceId) migrationSelectedFolder = key
            val root =
                record.gameDirectoryId
                    ?.let { id -> data.gameDirectories.find { it.id == id }?.path }
                    ?: data.settings.gameRoot
            if (root.isBlank()) continue
            val folder = runCatching { Path.of(root).resolve("versions").resolve(key) }.getOrNull()
            if (folder == null || !folder.isDirectory()) continue
            if (!settings.containsKey(key)) settings[key] = InstanceSettings.of(record)
        }
        return data.copy(instances = emptyList(), instanceSettings = settings)
    }

    /**
     * 启动/发现时用磁盘核对实例设置：`versions/<目录名>` 在所有扫描根里都不存在时，这条记录已没有文件，
     * 从配置中移除并记一行日志；目录还在的一律保留，避免把刚创建、尚未下载完的实例误删。
     *
     * 不判断 `libraries/`、`assets/` 或共用游戏根；返回被清理的实例显示名，没有失效记录时不写配置、不产生日志。
     */
    @Synchronized
    fun pruneMissingInstanceRecords(): Int {
        val current = data
        val roots = InstanceCatalog.roots(current)
        fun directoryExists(key: String): Boolean =
            roots.any { root ->
                runCatching { root.path.resolve("versions").resolve(key).isDirectory() }
                    .getOrDefault(false)
            }
        val missing = current.instanceSettings.filterKeys { !directoryExists(it) }
        if (missing.isEmpty()) return 0
        val keys = missing.keys
        mutate { data ->
            val instances = data.instances.filter { it.instanceSettingsKey() !in keys }
            data.copy(
                instances = instances,
                selectedInstanceId =
                    data.selectedInstanceId?.takeIf { id -> instances.any { it.id == id } }
                        ?: instances
                            .firstOrNull { it.gameDirectoryId == data.activeGameDirectoryId }
                            ?.id
                        ?: instances.firstOrNull()?.id,
            )
        }
        missing.forEach { (key, settings) ->
            log("清理失效实例记录：${settings.displayName.ifBlank { key }}（目录不存在）")
        }
        return missing.size
    }

    /**
     * 写一行运行日志：界面列表与 `logs/launcher.log` 同时落盘。
     * [level] 为空时沿用旧行为（error → ERROR，其余 INFO），已有调用点无需改动；
     * 需要 TRACE / DEBUG / WARN / FATAL 时显式传入，界面筛选只列真实会出现的档位。
     */
    @Synchronized
    fun log(
        message: String,
        error: Boolean = false,
        game: Boolean = false,
        level: LogLevel? = null,
    ) {
        val resolved = level ?: if (error) LogLevel.Error else LogLevel.Info
        val line =
            LogLine(
                message = redactSecrets(message).take(8000),
                isError = error || resolved.ordinal >= LogLevel.Error.ordinal,
                isGame = game,
                level = resolved,
            )
        mutableState.value =
            mutableState.value.copy(logs = (mutableState.value.logs + line).takeLast(600))
        runCatching {
            val file = directories.launcherLog
            if (file.exists() && file.fileSize() > 8 * 1024 * 1024)
                Files.move(
                    file,
                    directories.previousLog,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            Files.writeString(
                file,
                "${line.time} [${line.level.label}] ${line.message}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** 只清空界面列表；`logs/launcher.log` 保留，诊断包仍能拿到完整历史。 */
    @Synchronized
    fun clearLogs() {
        updateState { it.copy(logs = emptyList()) }
    }

    private fun pruneDamagedConfigurations() {
        directories.configurationBackups
            .listDirectoryEntries("launcher.damaged-*.json")
            .sortedByDescending { it.name }
            .drop(3)
            .forEach { Files.deleteIfExists(it) }
    }

    fun notify(message: String) {
        updateState { it.copy(toast = message) }
    }

    fun dismissNotification() {
        updateState { it.copy(toast = null) }
    }
}

/**
 * 默认游戏根：启动器目录旁的 `.minecraft`。若这一层本身就是 `.minecraft`（启动器装在游戏里）， 或已经是 versions/libraries/assets
 * 齐全的游戏根，就直接用这一层，避免再套一层不存在的目录。
 */
private fun defaultGameRoot(launcherDirectory: Path): Path =
    when {
        launcherDirectory.fileName?.toString().equals(".minecraft", ignoreCase = true) ->
            launcherDirectory
        listOf("versions", "libraries", "assets").all {
            launcherDirectory.resolve(it).isDirectory()
        } -> launcherDirectory
        else -> launcherDirectory.resolve(".minecraft")
    }
