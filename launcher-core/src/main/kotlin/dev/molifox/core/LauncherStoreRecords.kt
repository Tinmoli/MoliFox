// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置写入、实例记录与运行日志。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private const val MAX_VISIBLE_LOG_LINES = 600
private const val LOG_ROTATION_BYTES = 8 * 1024 * 1024

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
        val keys =
            requested.instances
                .map { it.instanceSettingsKey() }
                .filter { it.isNotBlank() }
                .toSet()
        val next =
            requested.copy(
                instanceSettings =
                    (
                        requested.instanceSettings +
                            requested.instances.associate {
                                it.instanceSettingsKey() to InstanceSettings.of(it)
                            }
                    ).filterKeys { it in keys },
            )
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
            resolveInstance(current, id) ?: error("找不到游戏实例")
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
        resolveInstance(core.data, id) ?: error("找不到游戏实例")

    override fun instanceSettings(name: String): InstanceSettings? =
        core.data.instanceSettings[name]

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
                message = redactSecrets(message).take(8000),
                isError = error || resolved.ordinal >= LogLevel.Error.ordinal,
                isGame = game,
                level = resolved,
            )
        core.mutableState.value =
            core.mutableState.value.copy(
                logs = (core.mutableState.value.logs + line).takeLast(MAX_VISIBLE_LOG_LINES),
            )
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

    /** 只清空界面列表；`logs/launcher.log` 保留，诊断包仍能拿到完整历史。 */
    @Synchronized
    override fun clearLogs() {
        updateState { it.copy(logs = emptyList()) }
    }

    override fun notify(message: String) {
        updateState { it.copy(toast = message) }
    }

    override fun dismissNotification() {
        updateState { it.copy(toast = null) }
    }
}
