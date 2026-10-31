// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的目录、兼容性与提示计算：只做纯计算，不渲染界面。

package dev.molifox.desktop

import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance
import dev.molifox.core.compatibleContentVersion
import java.nio.file.Path

private const val MAX_VERSION_COMPONENTS = 4

internal fun contentChannelTitle(channel: String) =
    when (channel) {
        "release" -> "正式版"
        "beta" -> "测试版"
        "alpha" -> "预览版"
        else -> channel
    }

/** 资源类型对应的实例内目录名；与核心安装事务接受的三个目录保持一致。 */
internal fun contentFolderForType(type: String): String =
    when (type) {
        "resourcepack" -> "resourcepacks"
        "shader" -> "shaderpacks"
        "datapack" -> "datapacks"
        "world" -> "saves"
        else -> "mods"
    }

/** 默认下载目标：模组 / 资源包 / 光影落在实例内容目录，数据包落在所选存档的 datapacks。 显示给用户的就是最终会被写入的路径。 */
internal fun contentDestination(
    instanceRoot: Path,
    type: String,
    worldName: String = "",
): Path =
    if (type == "datapack") {
        instanceRoot.resolve("saves").resolve(worldName).resolve("datapacks")
    } else {
        instanceRoot.resolve(contentFolderForType(type))
    }

/**
 * 用户确认的文件夹 → 实例内的资源子目录名（mods / resourcepacks / shaderpacks）。 不在实例内、或者不是这三个目录之一时返回
 * null：安装仍走默认目录，不写入用户没有确认的位置。
 */
internal fun contentFolderOf(
    instanceRoot: Path?,
    destination: Path?,
): String? {
    if (instanceRoot == null || destination == null) return null
    val root = runCatching { instanceRoot.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val target = runCatching { destination.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val relative = runCatching { root.relativize(target) }.getOrNull() ?: return null
    if (relative.nameCount != 1) return null
    return relative.toString().takeIf { it in setOf("mods", "resourcepacks", "shaderpacks") }
}

/** 下载目标默认值：有实例时是它的内容目录，数据包则落到所选存档的 datapacks。 */
internal fun contentDefaultDestination(
    instanceRoot: Path?,
    type: String,
    worldName: String,
): Path? =
    instanceRoot
        ?.let { root ->
            runCatching {
                if (type == "datapack") {
                    contentDestination(root, type, worldName)
                } else {
                    contentDestination(root, type)
                }
            }.getOrNull()
        }?.toAbsolutePath()
        ?.normalize()

/** 整合包自带整套组件，不按实例加载器筛选；其余类型才检查与实例的兼容性。 */
internal fun isUnusableFor(
    version: ContentVersion,
    target: GameInstance?,
    kind: String,
): Boolean {
    if (target == null || kind == "modpack") return false
    return !compatibleContentVersion(version, target, kind)
}

/** 所选文件能否装进目标实例：整合包先预览，其余类型必须与实例版本和加载器兼容。 */
internal fun contentVersionCompatible(
    chosen: ContentVersion?,
    hit: ContentHit,
    target: GameInstance?,
): Boolean =
    chosen != null &&
        (
            hit.type == "modpack" ||
                target?.let { compatibleContentVersion(chosen, it, hit.type) } == true
        )

/** 底部提示：优先说明用户下一步要做什么，再说明下载流程。 */
internal fun contentInstallHint(
    hit: ContentHit,
    target: GameInstance?,
    compatible: Boolean,
): String =
    if (hit.type == "modpack") {
        "将先预览整合包，不会立即安装。"
    } else if (hit.type == "world" && target == null) {
        "请先在「安装到」里选择游戏实例。"
    } else if (target == null) {
        "先选实例或点「下载」挑一个保存文件夹。"
    } else if (!compatible) {
        "所选文件不兼容此实例的游戏版本或加载器。"
    } else if (hit.type == "world") {
        "世界会解压到实例的 saves/，同名世界不会覆盖。"
    } else if (hit.type == "datapack") {
        "数据包需要安装到具体存档。"
    } else {
        "下载前会确认目标文件夹，并检查前置与文件冲突。"
    }

/** 下载按钮的可用条件：版本已选、没有任务在跑、数据包还需要一个具体存档、世界需要一个已安装的实例。 */
internal fun contentDownloadEnabled(
    loading: Boolean,
    busy: Boolean,
    chosen: ContentVersion?,
    target: GameInstance?,
    compatible: Boolean,
    hit: ContentHit,
    worldName: String,
): Boolean =
    !loading &&
        !busy &&
        chosen != null &&
        (target == null || compatible) &&
        (hit.type != "world" || target != null) &&
        (hit.type != "datapack" || (target != null && worldName.isNotBlank()))

/** 版本列表排序：发布时间降序（最新文件排在最上面）；时间相同或缺失时，按版本号里的数字段降序回退， 避免字符串日期格式不一致时列表变成「最旧在上」。 */
internal val newestVersionFirst: Comparator<ContentVersion> =
    compareByDescending<ContentVersion> { it.published }
        .thenComparator { left, right -> compareVersionNumbers(right.number, left.number) }

private fun compareVersionNumbers(
    left: String,
    right: String,
): Int {
    val a = versionNumberSegments(left)
    val b = versionNumberSegments(right)
    repeat(maxOf(a.size, b.size)) { index ->
        val diff = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return left.compareTo(right)
}

private fun versionNumberSegments(value: String): List<Int> =
    Regex("[0-9]+")
        .findAll(value)
        .mapNotNull { it.value.toIntOrNull() }
        .take(MAX_VERSION_COMPONENTS)
        .toList()
