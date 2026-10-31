// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 写回存档 level.dat 的允许作弊、难度与锁定难度，写入前备份并原子替换。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

private const val MIN_DIFFICULTY = 0
private const val MAX_DIFFICULTY = 3

private val ACTIVE_JOB_STATUSES = setOf(JobStatus.Queued, JobStatus.Running)

/**
 * 存档设置写回：只修改存在的标签，其他 `Data` 内容原样保留。
 *
 * 世界正被游戏打开（`session.lock` 被占用）或实例有任务时拒绝；写入前把原文件备份到
 * `MoliFox/backups/level-dat/<世界名>.dat`，再用临时文件原子替换。返回是否真的写入。
 */
internal class SaveSettingsEditor(
    private val store: LauncherStore,
) {
    fun update(
        instanceId: String,
        saveName: String,
        changes: SaveSettingsChanges,
    ): Boolean {
        require(!changes.isEmpty) { "没有需要保存的设置" }
        val world = requireEditableWorld(instanceId, saveName)
        val level = world.resolve("level.dat")
        val before = Files.readAllBytes(level)
        val document =
            runCatching { readNbtDocument(level) }.getOrElse { failure ->
                throw IllegalArgumentException("level.dat 无法解析：${failure.message ?: "格式错误"}；未写入")
            }
        val data = document.root.compound("Data") ?: NbtCompound(emptyMap())
        val updated = withChanges(data, changes)
        if (updated == data) return false
        val root = NbtCompound(document.root.entries + ("Data" to updated))
        val bytes = encodeNbt(document.copy(root = root))
        backupLevelDat(store, world, before)
        replaceLevelDat(level, before, bytes)
        store.log("已修改存档设置：$saveName（${changeSummary(changes)}）")
        return true
    }

    private fun requireEditableWorld(
        instanceId: String,
        saveName: String,
    ): Path {
        store.instance(instanceId)
        requireSaveGameStopped(store, instanceId)
        require(!hasActiveInstanceJobs(instanceId)) {
            "实例有任务进行中，暂时不能修改存档设置"
        }
        val world = saveSourcePath(savesDirectoryOf(store, instanceId), saveName)
        require(world.isDirectory()) { "只有世界文件夹可以修改设置" }
        require(!sessionLockHeld(world)) {
            "世界「${world.fileName}」正在游戏中打开，请先退出游戏"
        }
        return world
    }

    /** 与实例目录共享资源的排队/运行任务（下载、安装、备份等）都会阻塞写入。 */
    private fun hasActiveInstanceJobs(instanceId: String): Boolean {
        val resources = taskResourcesForInstances(store, instanceId)
        return store.state.value.jobs.any { job ->
            job.status in ACTIVE_JOB_STATUSES && job.resources.any(resources::contains)
        }
    }

    private fun withChanges(
        data: NbtCompound,
        changes: SaveSettingsChanges,
    ): NbtCompound {
        val entries = LinkedHashMap(data.entries)
        changes.allowCommands?.let { entries.writeByteIfPresent(data, "allowCommands", it) }
        changes.difficulty?.let { difficulty ->
            require(difficulty in MIN_DIFFICULTY..MAX_DIFFICULTY) { "难度取值无效：$difficulty" }
            entries.writeByteIfPresent(data, "Difficulty", difficulty)
        }
        changes.difficultyLocked?.let { entries.writeByteIfPresent(data, "DifficultyLocked", it) }
        return data.copy(entries = entries)
    }

    /** 只在原标签存在时替换，避免给旧世界凭空添加字段。 */
    private fun MutableMap<String, NbtTag>.writeByteIfPresent(
        data: NbtCompound,
        key: String,
        value: Boolean,
    ) = writeByteIfPresent(data, key, if (value) 1 else 0)

    private fun MutableMap<String, NbtTag>.writeByteIfPresent(
        data: NbtCompound,
        key: String,
        value: Int,
    ) {
        if (data[key] !is NbtByte) return
        this[key] = NbtByte(value.toByte())
    }

    private fun changeSummary(changes: SaveSettingsChanges): String =
        buildList {
            changes.allowCommands?.let { add("允许作弊=${if (it) "开" else "关"}") }
            changes.difficulty?.let { add("难度=$it") }
            changes.difficultyLocked?.let { add("锁定难度=${if (it) "开" else "关"}") }
        }.joinToString("，")
}
