// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读取存档 level.dat 中用于详情页展示的世界信息。

package dev.molifox.core

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isDirectory

/** 世界出生点；只有三个坐标都存在时才给出。 */
data class SaveSpawn(
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * 一个存档的展示信息。
 *
 * 字段全部来自 `level.dat` 的 `Data`；旧世界可能缺少部分标签，此时对应字段为 null，界面如实显示未知， 不猜测也不改写。
 */
data class SaveDetails(
    val levelName: String,
    val versionName: String?,
    val versionId: Int?,
    val lastPlayed: Instant?,
    val seed: Long?,
    val spawn: SaveSpawn?,
    /** 原版 GameType：0 生存、1 创造、2 冒险、3 旁观。 */
    val gameMode: Int?,
    val hardcore: Boolean,
    /** 0 和平、1 简单、2 普通、3 困难。 */
    val difficulty: Int?,
    /** null 表示旧存档没有 `DifficultyLocked` 标签，界面不提供该项。 */
    val difficultyLocked: Boolean?,
    val allowCommands: Boolean?,
    /** `Data.Time`，世界走过的刻数；界面按 20 刻/秒换算成时长。 */
    val playTicks: Long?,
)

/** 可通过详情页写回的设置；null 表示这项不改。 */
data class SaveSettingsChanges(
    val allowCommands: Boolean? = null,
    val difficulty: Int? = null,
    val difficultyLocked: Boolean? = null,
) {
    val isEmpty: Boolean
        get() = allowCommands == null && difficulty == null && difficultyLocked == null
}

private const val MIN_DIFFICULTY = 0
private const val MAX_DIFFICULTY = 3
private const val MIN_GAME_MODE = 0
private const val MAX_GAME_MODE = 3

/** 读取一个世界文件夹的 level.dat；压缩包存档没有可写目录，不提供详情。 */
internal class SaveDetailsReader(
    private val store: LauncherStore,
) {
    fun read(
        instanceId: String,
        saveName: String,
    ): SaveDetails {
        store.instance(instanceId)
        val world = worldOf(instanceId, saveName)
        val level = world.resolve("level.dat")
        require(level.toFile().isFile) { "存档没有 level.dat，无法读取详情" }
        val document =
            runCatching { readNbtDocument(level) }.getOrElse { failure ->
                throw IllegalArgumentException("level.dat 无法解析：${failure.message ?: "格式错误"}")
            }
        return detailsOf(world, document)
    }

    private fun worldOf(
        instanceId: String,
        saveName: String,
    ): Path {
        val world = saveSourcePath(savesDirectoryOf(store, instanceId), saveName)
        require(world.isDirectory()) { "压缩包存档不支持详情，请先解压成世界文件夹" }
        return world
    }

    private fun detailsOf(
        world: Path,
        document: NbtDocument,
    ): SaveDetails {
        val data = document.root.compound("Data") ?: NbtCompound(emptyMap())
        val version = data.compound("Version")
        val hardcore = data.byte("hardcore")?.toInt() == 1
        val difficulty = data.byte("Difficulty")?.toInt()
        return SaveDetails(
            levelName = data.string("LevelName") ?: world.fileName.toString(),
            versionName = version?.string("Name"),
            versionId = version?.int("Id"),
            lastPlayed = data.long("LastPlayed")?.let(Instant::ofEpochMilli),
            seed = data.compound("WorldGenSettings")?.long("seed") ?: data.long("RandomSeed"),
            spawn = spawnOf(data),
            gameMode = data.int("GameType")?.takeIf { it in MIN_GAME_MODE..MAX_GAME_MODE },
            hardcore = hardcore,
            difficulty = difficulty?.takeIf { it in MIN_DIFFICULTY..MAX_DIFFICULTY },
            difficultyLocked = data.byte("DifficultyLocked")?.let { it.toInt() == 1 },
            allowCommands = data.byte("allowCommands")?.let { it.toInt() != 0 },
            playTicks = data.long("Time"),
        )
    }

    private fun spawnOf(data: NbtCompound): SaveSpawn? {
        val x = data.int("SpawnX") ?: return null
        val y = data.int("SpawnY") ?: return null
        val z = data.int("SpawnZ") ?: return null
        return SaveSpawn(x.toDouble(), y.toDouble(), z.toDouble())
    }
}
