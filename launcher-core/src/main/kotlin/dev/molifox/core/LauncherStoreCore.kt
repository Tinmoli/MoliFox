// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置持久化、实例路径和界面状态。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private const val MAX_CONFIG_BACKUPS = 3

/**
 * 配置与运行时状态的唯一持有者：载入、备份与迁移都在这里完成，读写方法在 [LauncherStoreActions]。
 *
 * 拆分只改变方法所在的位置；字段、初始化顺序与同步语义都与原实现一致。
 */
internal class LauncherStoreCore(
    val platform: PlatformServices,
    val dataDirectory: Path,
) {
    val directories = LauncherDirectories(dataDirectory)
    internal val config = directories.configuration
    internal val backup = directories.previousConfiguration

    val mutableState: MutableStateFlow<LauncherState>

    /** 上一轮扫描签名：界面重新可见时只做一次廉价的目录名/mtime 比较，不启动轮询。 */
    @Volatile internal var lastScanSignature: String? = null

    /** 旧配置里选中的实例目录名；首轮扫描把它映射到新的稳定 id。 */
    internal var migrationSelectedFolder: String? = null

    /** 安装中的目标目录：只在内存里，扫描时跳过它们的空目录。 */
    internal val pendingInstalls = mutableMapOf<String, PendingInstall>()

    /** 载入时发现 CurseForge 服务地址为空/非法，初始化完成后补一条日志。 */
    internal var curseForgeBaseFallback = false

    /** 旧 id（安装时随机生成、或上一次扫描前使用）到扫描稳定 id 的别名，仅本会话有效。 */
    internal val instanceIdAliases = mutableMapOf<String, String>()

    /** 推断日志每个实例只写一次；重扫时证据不变就不重复刷屏。 */
    internal val inferenceLogged = mutableSetOf<String>()

    /** 游戏日志量大，界面列表按固定间隔批量刷新，避免每行都触发整页重组。 */
    internal val logFlusher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "molifox-log-flush").apply { isDaemon = true }
        }

    /** 界面在窗口尺寸变化时上报的当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    @Volatile internal var launcherWindowSize: Pair<Int, Int>? = null

    /** 载入时恢复出来的配置说明；为 null 表示没有发生恢复。 */
    internal var recoveryMessage: String? = null

    /** 旧配置里的实例数组：只在启动迁移时读一次，随后丢弃。 */
    internal var legacyRecords: List<GameInstance> = emptyList()

    val data: LauncherData
        get() = mutableState.value.data

    val minecraftRoot: Path
        get() = Path.of(data.settings.gameRoot)

    init {
        directories.initialize()
        val defaults =
            LauncherData(
                settings =
                    LauncherSettings(
                        // 默认游戏目录 = 启动器所在目录下的 .minecraft（数据目录默认是启动器旁的 MoliFox/）。
                        // 数据目录本身就在 .minecraft 里、或这一层就是游戏根时直接用这一层，不再套一层。
                        gameRoot =
                            defaultGameRoot(dataDirectory.parent ?: dataDirectory).toString(),
                        proxyMode = "system",
                        memoryMb =
                            minOf(4096, maxOf(1024, platform.totalMemoryMb / 4 / 256 * 256)),
                        autoMemory = true,
                    ),
            )
        val loaded =
            if (config.exists()) {
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
                            "launcher.damaged-${System.currentTimeMillis()}-" +
                                "${UUID.randomUUID()}.json",
                        ),
                    )
                    pruneDamagedConfigurations()
                    runCatching { readData(backup) }
                        .getOrElse { defaults }
                        .also { recoveryMessage = "原配置已保留，已恢复备份或默认配置。" }
                }
            } else {
                defaults
            }
        if (config.exists() && recoveryMessage == null) {
            Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING)
        }
        directories.cache().initialize()
        if (loaded.settings.downloadCachePath.isNotBlank()) {
            directories.cache(loaded.settings.downloadCachePath).initialize()
        }
        val legacy = if (config.exists()) legacyInstances(config) else emptyList()
        legacyRecords = legacy
        val migrated = migrateLegacyInstances(loaded, legacy)
        if (!config.exists() || recoveryMessage != null || legacy.isNotEmpty()) {
            writeJson(config, migrated) // 迁移后立刻写回：配置里不再有实例列表。
        }
        mutableState = MutableStateFlow(LauncherState(migrated))
    }

    /** 载入完成后的公开读写实现；声明在 init 之后，构造期间不会被调用。 */
    val actions: LauncherStoreActions = LauncherStoreActions(this)

    private fun readData(path: Path): LauncherData {
        val json = readObject(path)
        // schemaVersion 2 保持不变：旧配置的 instances 会被迁移，新配置不再写这个字段。
        require(
            json.int("schemaVersion") == 2 && json.has("accounts") && json.has("settings"),
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
            if (raw.has("versionListSource")) {
                normalizedDownloadSource(raw.string("versionListSource"))
            } else {
                legacyFileSource
            }
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
                ),
        )
    }

    /** 旧配置里的实例数组；只用于一次性迁移，之后配置不再写这个字段。 */
    private fun legacyInstances(path: Path): List<GameInstance> =
        runCatching {
            readObject(path).getAsJsonArray("instances")?.mapNotNull { element ->
                runCatching { gson.fromJson(element, GameInstance::class.java) }.getOrNull()
            } ?: emptyList()
        }.getOrDefault(emptyList())

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
        for (record in legacy) migrateLegacyInstance(record, data, settings)
        return data.copy(instances = emptyList(), instanceSettings = settings)
    }

    /** 迁移单条旧实例：只保留磁盘上仍存在的目录设置，其余记录直接丢弃。 */
    private fun migrateLegacyInstance(
        record: GameInstance,
        data: LauncherData,
        settings: MutableMap<String, InstanceSettings>,
    ) {
        val key = record.instanceSettingsKey()
        val root =
            record.gameDirectoryId
                ?.let { id -> data.gameDirectories.find { it.id == id }?.path }
                ?: data.settings.gameRoot
        if (key.isBlank() || root.isBlank()) return
        if (record.id == data.selectedInstanceId) migrationSelectedFolder = key
        val folder = runCatching { Path.of(root).resolve("versions").resolve(key) }.getOrNull()
        if (folder != null && folder.isDirectory() && !settings.containsKey(key)) {
            settings[key] = InstanceSettings.of(record)
        }
    }

    private fun pruneDamagedConfigurations() {
        directories.configurationBackups
            .listDirectoryEntries("launcher.damaged-*.json")
            .sortedByDescending { it.name }
            .drop(MAX_CONFIG_BACKUPS)
            .forEach { Files.deleteIfExists(it) }
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
