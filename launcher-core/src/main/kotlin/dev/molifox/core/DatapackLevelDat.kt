// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读写 level.dat 里的 Data.Packs：启用/禁用切换、删除后的条目清理与滚动备份。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path

/** `level.dat` 里 `Data.Packs.Enabled/Disabled` 的字符串标签类型号。 */
private const val NBT_TAG_STRING = 8

/** 新建 `Enabled` 列表时补上的原版数据包标识，避免只写一个数据包把原版数据包关掉。 */
private const val VANILLA_PACK_ID = "vanilla"

/** `Data.Packs.Enabled`/`Disabled` 两个字符串列表；两条列表都没命中 = 原版默认启用。 */
internal data class DatapackPackLists(
    val enabled: List<String>,
    val disabled: List<String>,
) {
    /** 该数据包的开关状态：Enabled 命中为启用，Disabled 命中为禁用，都没命中按原版默认启用。 */
    fun enabledOf(name: String): Boolean =
        when {
            enabled.any { matchesPackId(it, name) } -> true
            disabled.any { matchesPackId(it, name) } -> false
            else -> true
        }
}

/** 数据包标识匹配：同时接受 `file/<名称>` 与裸名称（手改过的 level.dat 常见）。 */
internal fun matchesPackId(
    id: String,
    name: String,
): Boolean = id == name || id == "file/$name"

/**
 * `level.dat` 的 Packs 列表读写：写入前备份原文件，临时文件 + 原子改名，并复核文件未被其他程序改动。
 *
 * 解析失败时抛中文错误且不写文件，调用方据此退回只读。
 */
internal class DatapackLevelDat(
    private val store: LauncherStore,
) {
    /** 读 `Data.Packs`；`level.dat` 不存在或无法解析时返回 null，界面只展示不提供开关。 */
    fun readPacks(world: Path): DatapackPackLists? =
        runCatching { packsOf(readNbtDocument(world.resolve("level.dat"))) }.getOrNull()

    /** 启用或禁用 [name]；状态未变化时返回 false，真正写入时返回 true。 */
    fun toggle(
        world: Path,
        name: String,
        enabled: Boolean,
    ): Boolean {
        val level = world.resolve("level.dat")
        val before =
            runCatching { Files.readAllBytes(level) }.getOrElse {
                throw IllegalArgumentException("无法读取 level.dat，数据包开关暂时只读")
            }
        val document =
            runCatching { readNbtDocument(level) }.getOrElse {
                throw IllegalArgumentException("level.dat 无法解析，数据包开关暂时只读；未修改任何文件")
            }
        val current = packsOf(document)
        val next = nextLists(current, name, enabled)
        if (next == current) return false
        val root = withPacks(document.root, next.enabled, next.disabled)
        val bytes = encodeNbt(document.copy(root = root))
        backupLevelDat(store, world, before)
        replaceLevelDat(level, before, bytes)
        return true
    }

    /** 从 level.dat 的列表里移除一个数据包条目；level.dat 解析失败时跳过，不算删除失败。 */
    fun forget(
        world: Path,
        name: String,
    ) {
        val level = world.resolve("level.dat")
        val before = runCatching { Files.readAllBytes(level) }.getOrNull() ?: return
        val document = runCatching { readNbtDocument(level) }.getOrNull() ?: return
        val current = packsOf(document)
        val enabled = current.enabled.filterNot { matchesPackId(it, name) }
        val disabled = current.disabled.filterNot { matchesPackId(it, name) }
        if (enabled == current.enabled && disabled == current.disabled) return
        val bytes = encodeNbt(document.copy(root = withPacks(document.root, enabled, disabled)))
        if (Files.readAllBytes(level).contentEquals(before)) replaceLevelDat(level, before, bytes)
    }

    /** 读取 `Data.Packs` 的两个字符串列表；结构异常时抛错。 */
    private fun packsOf(document: NbtDocument): DatapackPackLists {
        val data = document.root.compound("Data") ?: NbtCompound(emptyMap())
        val packs = data.compound("Packs") ?: NbtCompound(emptyMap())
        return DatapackPackLists(packStrings(packs, "Enabled"), packStrings(packs, "Disabled"))
    }

    private fun packStrings(
        packs: NbtCompound,
        key: String,
    ): List<String> {
        val list = packs.list(key) ?: return emptyList()
        require(list.elementType == NBT_TAG_STRING || list.elements.isEmpty()) {
            "level.dat 的 Packs.$key 不是字符串列表，未修改文件"
        }
        return list.elements.map { element ->
            require(element is NbtString) { "level.dat 的 Packs.$key 含有非字符串项，未修改文件" }
            element.value
        }
    }

    /** 只替换 `Data.Packs.Enabled`/`Disabled` 两个列表，其他标签原样保留。 */
    private fun withPacks(
        root: NbtCompound,
        enabled: List<String>,
        disabled: List<String>,
    ): NbtCompound {
        val data = root.compound("Data") ?: NbtCompound(emptyMap())
        val packs = data.compound("Packs") ?: NbtCompound(emptyMap())
        // 新建 Enabled 时补 vanilla：只写一个数据包名会让原版数据包被当成未选中。
        val enabledIds =
            if (packs.list("Enabled") == null && VANILLA_PACK_ID !in enabled) {
                listOf(VANILLA_PACK_ID) + enabled
            } else {
                enabled
            }
        val updated =
            NbtCompound(
                packs.entries +
                    ("Enabled" to NbtList(enabledIds.map(::NbtString), NBT_TAG_STRING)) +
                    ("Disabled" to NbtList(disabled.map(::NbtString), NBT_TAG_STRING)),
            )
        return NbtCompound(
            root.entries + ("Data" to NbtCompound(data.entries + ("Packs" to updated))),
        )
    }

    /** 按目标状态算出两个新列表：其他条目（`vanilla`、其他数据包）原样保留。 */
    private fun nextLists(
        current: DatapackPackLists,
        name: String,
        enabled: Boolean,
    ): DatapackPackLists {
        val existingId =
            (current.enabled + current.disabled).firstOrNull { matchesPackId(it, name) }
                ?: "file/$name"
        return if (enabled) {
            DatapackPackLists(
                enabled =
                    if (current.enabled.any { matchesPackId(it, name) }) {
                        current.enabled
                    } else {
                        current.enabled + existingId
                    },
                disabled = current.disabled.filterNot { matchesPackId(it, name) },
            )
        } else {
            DatapackPackLists(
                enabled = current.enabled.filterNot { matchesPackId(it, name) },
                disabled =
                    if (current.disabled.any { matchesPackId(it, name) }) {
                        current.disabled
                    } else {
                        current.disabled + existingId
                    },
            )
        }
    }
}
