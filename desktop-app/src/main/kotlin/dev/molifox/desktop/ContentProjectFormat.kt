// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责数据包项目里“数据包 zip / 模组 jar”的格式判断与目标存档可用性。

package dev.molifox.desktop

import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion

/**
 * 数据包项目里同一版本可能提供数据包 zip 或模组 jar。加载器里明确没有 datapack 的版本
 * 按模组安装；旧数据缺少加载器时保持原行为，仍按数据包处理。
 */
internal fun contentVersionInstallKind(
    hit: ContentHit,
    version: ContentVersion,
): String =
    if (hit.type == "datapack" && version.loaders.isNotEmpty() &&
        version.loaders.none { it.equals("datapack", true) }
    ) {
        "mod"
    } else {
        hit.type
    }

/** 数据包项目当前是否按“数据包”安装；选中模组版或未选版本时为 false。 */
internal fun contentTargetSaveEnabled(
    hit: ContentHit,
    chosen: ContentVersion?,
): Boolean =
    hit.type == "datapack" &&
        chosen != null &&
        contentVersionInstallKind(hit, chosen) == "datapack"

/** 版本行上的格式标签，帮助用户在数据包 zip 和模组 jar 之间选择。 */
internal fun contentVersionFormatLabel(
    hit: ContentHit,
    version: ContentVersion,
): String? =
    if (hit.type != "datapack") {
        null
    } else if (contentVersionInstallKind(hit, version) == "datapack") {
        tr("dialogs.content.format.datapack")
    } else {
        tr("dialogs.content.format.mod")
    }
