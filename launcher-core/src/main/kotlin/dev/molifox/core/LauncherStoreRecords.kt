// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置写入、实例记录与运行日志。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private const val MAX_VISIBLE_LOG_LINES = 600
private const val LOG_ROTATION_BYTES = 8 * 1024 * 1024
private const val GAME_LOG_FLUSH_INTERVAL_MILLIS = 100L
private const val GAME_LOG_FLUSH_INTERVAL_NANOS = GAME_LOG_FLUSH_INTERVAL_MILLIS * 1_000_000L

/** 配置与实例记录的读写：所有保存都串行执行。 */
internal interface StoreRecordApi {
    fun mutate(block: (LauncherData) -> LauncherData)

    fun updateInstance(
        id: String,
        block: (GameInstance) -> GameInstance,
    )

    fun instance(id: String): GameInstance

    fun instanceSettings(name: String): InstanceSettings?

    fun renameInstanceSettings(
        oldName: String,
        newName: String,
    )

    fun beginPendingInstall(
        name: String,
        gameVersion: String,
        loaderId: String = "",
    )

    fun endPendingInstall(name: String)

    fun pendingInstall(name: String): PendingInstall?

    fun instanceIdFor(
        folder: String,
        gameDirectoryId: String?,
    ): String
}

/** 运行日志与界面提示。 */
internal interface StoreLogApi {
    fun log(
        message: String,
        error: Boolean = false,
        game: Boolean = false,
        level: LogLevel? = null,
    )

    fun clearLogs()

    fun notify(message: String)

    /** 带流程类型的提示；默认实现只保证文案，测试替身可以忽略类型。 */
    fun notify(
        message: String,
        kind: LauncherNoticeKind,
    ) = notify(message)

    /** 最近一次提示的类型；[dismissNotification] 之后为 null。界面据此判断流程结果，不解析文案。 */
    fun lastNoticeKind(): LauncherNoticeKind? = null

    fun dismissNotification()
}

/** 唯一的持久实例数据是按目录名索引的设置表：任何一次保存都顺手同步并清掉已不存在的键。 */
internal abstract class StoreRecordActions(
    protected val core: LauncherStoreCore,
) : StoreRecordApi {
    @Synchronized
    override fun mutate(block: (LauncherData) -> LauncherData) {
        val current = core.data
        val requested = block(current)
        val next = requested.copy(instanceSettings = syncedInstanceSettings(requested))
        if (core.config.isRegularFile()) {
            Files.copy(core.config, core.backup, StandardCopyOption.REPLACE_EXISTING)
        }
        writeJson(core.config, next)
        core.mutableState.value = core.mutableState.value.copy(data = next)
    }

    @Synchronized
    fun updateState(block: (LauncherState) -> LauncherState) {
        core.mutableState.value = block(core.mutableState.value)
    }

    override fun updateInstance(
        id: String,
        block: (GameInstance) -> GameInstance,
    ) = mutate { current ->
        val target =
            resolveInstance(current, id) ?: error(I18n.t("miscA2.storeRecords.instanceNotFound"))
        current.copy(
            instances =
                current.instances.map {
                    if (it.id ==
                        target.id
                    ) {
                        block(it)
                    } else {
                        it
                    }
                },
        )
    }

    /** 按 id 查；也接受目录名/显示名和本会话已知的旧 id，方便扫描结果和旧调用方混用。 */
    override fun instance(id: String): GameInstance =
        resolveInstance(core.data, id) ?: error(I18n.t("miscA2.storeRecords.instanceNotFound"))

    override fun instanceSettings(name: String): InstanceSettings? = core.data.instanceSettings[name]

    /** 重命名后把设置键从旧目录名搬到新目录名；实例 id 与选中项不变。 */
    @Synchronized
    override fun renameInstanceSettings(
        oldName: String,
        newName: String,
    ) {
        if (oldName == newName) return
        mutate { current ->
            val moved = (current.instanceSettings[oldName] ?: current.instanceSettings[newName])
            current.copy(
                instances =
                    current.instances.map { instance ->
                        if (instance.instanceSettingsKey() == oldName) {
                            instance.copy(
                                name = newName,
                                gameDirectoryName = newName,
                                versionId =
                                    if (instance.versionId == oldName) {
                                        newName
                                    } else {
                                        instance.versionId
                                    },
                            )
                        } else {
                            instance
                        }
                    },
                instanceSettings =
                    if (moved == null) {
                        current.instanceSettings
                    } else {
                        current.instanceSettings - oldName +
                            (newName to moved.copy(displayName = newName))
                    },
            )
        }
    }

    @Synchronized
    override fun beginPendingInstall(
        name: String,
        gameVersion: String,
        loaderId: String,
    ) {
        if (name.isBlank()) return
        core.pendingInstalls[name] = PendingInstall(name, gameVersion, loaderId)
    }

    @Synchronized
    override fun endPendingInstall(name: String) {
        core.pendingInstalls.remove(name)
    }

    @Synchronized
    override fun pendingInstall(name: String): PendingInstall? = core.pendingInstalls[name]

    /** 安装器可提前算出的稳定实例 id；与扫描使用的是同一个函数。 */
    override fun instanceIdFor(
        folder: String,
        gameDirectoryId: String?,
    ): String = InstanceCatalog.instanceId(gameDirectoryId, folder)

    private fun resolveInstance(
        source: LauncherData,
        id: String,
    ): GameInstance? =
        source.instances.find { it.id == id }
            ?: core.instanceIdAliases[id]?.let { alias -> source.instances.find { it.id == alias } }
            ?: source.instances.find { it.instanceSettingsKey() == id }
            ?: source.instances.find { it.name == id }
}

/**
 * 写一行运行日志：界面列表与 `logs/launcher.log` 同时落盘。
 * [level] 为空时沿用旧行为（error → ERROR，其余 INFO），已有调用点无需改动；
 * 需要 TRACE / DEBUG / WARN / FATAL 时显式传入，界面筛选只列真实会出现的档位。
 */
internal abstract class StoreLogActions(
    core: LauncherStoreCore,
) : StoreRecordActions(core),
    StoreLogApi {
    private var nextLogId = 0L
    private val pendingGameLogs = mutableListOf<LogLine>()
    private var lastGameLogFlushNanos = 0L
    private var gameLogFlushScheduled = false

    @Synchronized
    override fun log(
        message: String,
        error: Boolean,
        game: Boolean,
        level: LogLevel?,
    ) {
        val resolved = level ?: if (error) LogLevel.Error else LogLevel.Info
        val line =
            LogLine(
                id = ++nextLogId,
                message = redactSecrets(message).take(8000),
                isError = error || resolved.ordinal >= LogLevel.Error.ordinal,
                isGame = game,
                level = resolved,
            )
        appendLogToUi(line)
        runCatching {
            val file = core.directories.launcherLog
            if (file.exists() && file.fileSize() > LOG_ROTATION_BYTES) {
                Files.move(
                    file,
                    core.directories.previousLog,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Files.writeString(
                file,
                "${line.time} [${line.level.label}] ${line.message}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** 游戏日志按 ~100ms 合并刷新；非游戏日志保持即时可见。 */
    private fun appendLogToUi(line: LogLine) {
        if (!line.isGame) {
            flushPendingGameLogs()
            flushLogs(listOf(line))
            return
        }
        pendingGameLogs += line
        val now = System.nanoTime()
        if (now - lastGameLogFlushNanos >= GAME_LOG_FLUSH_INTERVAL_NANOS) {
            flushPendingGameLogs()
        } else {
            scheduleGameLogFlush()
        }
    }

    private fun flushPendingGameLogs() {
        if (pendingGameLogs.isEmpty()) return
        val batch = pendingGameLogs.toList()
        pendingGameLogs.clear()
        lastGameLogFlushNanos = System.nanoTime()
        flushLogs(batch)
    }

    /** 退出前把尚未刷进界面的游戏日志补上；进程即将结束时不让最后一批只留在磁盘。 */
    @Synchronized
    internal fun flushPendingLogs() {
        gameLogFlushScheduled = false
        flushPendingGameLogs()
    }

    private fun scheduleGameLogFlush() {
        if (gameLogFlushScheduled) return
        gameLogFlushScheduled = true
        core.logFlusher.schedule(
            {
                synchronized(this) {
                    gameLogFlushScheduled = false
                    flushPendingGameLogs()
                }
            },
            GAME_LOG_FLUSH_INTERVAL_MILLIS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    private fun flushLogs(batch: List<LogLine>) {
        core.mutableState.value =
            core.mutableState.value.copy(
                logs = (core.mutableState.value.logs + batch).takeLast(MAX_VISIBLE_LOG_LINES),
            )
    }

    /** 只清空界面列表；`logs/launcher.log` 保留，诊断包仍能拿到完整历史。 */
    @Synchronized
    override fun clearLogs() {
        pendingGameLogs.clear()
        updateState { it.copy(logs = emptyList()) }
    }

    @Volatile
    private var lastNotice: LauncherNoticeKind? = null

    override fun notify(message: String) {
        lastNotice = null
        updateState { it.copy(toast = message) }
    }

    override fun notify(
        message: String,
        kind: LauncherNoticeKind,
    ) {
        lastNotice = kind
        updateState { it.copy(toast = message) }
    }

    override fun lastNoticeKind(): LauncherNoticeKind? = lastNotice

    override fun dismissNotification() {
        lastNotice = null
        updateState { it.copy(toast = null) }
    }
}

/**
 * 保存时同步实例设置表：扫描到的实例一律以内存快照覆盖；不在本次扫描里的键保持惰性，
 * 只有磁盘核对确认 `versions/<键>` 在所有可见扫描根里都不存在时才清理。
 * 扫描根一个都不可见（外接盘未挂载、网络盘断开）时无从核对「真的删了」还是「暂时看不到」，
 * 全部保留：否则一次普通设置保存就会清掉暂时看不到的实例的显示名、隔离、Java 路径与游玩时长。
 */
internal fun syncedInstanceSettings(requested: LauncherData): Map<String, InstanceSettings> {
    val merged =
        requested.instanceSettings +
            requested.instances
                .filter { it.instanceSettingsKey().isNotBlank() }
                .associate { it.instanceSettingsKey() to InstanceSettings.of(it) }
    val scannedKeys =
        requested.instances
            .map { it.instanceSettingsKey() }
            .filter { it.isNotBlank() }
            .toSet()
    val orphans = merged.keys - scannedKeys
    if (orphans.isEmpty()) return merged
    val existingRoots =
        InstanceCatalog.roots(requested).filter { root ->
            runCatching { root.path.isDirectory() }.getOrDefault(false)
        }
    if (existingRoots.isEmpty()) return merged
    val verifiedMissing =
        orphans.filterTo(mutableSetOf()) { key ->
            existingRoots.none { root ->
                runCatching {
                    root.path
                        .resolve("versions")
                        .resolve(key)
                        .isDirectory()
                }.getOrDefault(false)
            }
        }
    if (verifiedMissing.isEmpty()) return merged
    return merged - verifiedMissing
}
