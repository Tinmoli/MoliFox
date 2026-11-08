// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容安装入口。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentInstallFlowActions(
    core: ContentServiceCore,
) : ContentExportActions(core) {
    internal suspend fun installContent(
        hit: ContentHit,
        instanceId: String?,
        context: JobContext,
        gameDirectoryId: String?,
    ) {
        if (hit.type == "modpack") {
            importPack(downloadPack(hit, context, ""), context, gameDirectoryId = gameDirectoryId)
        } else {
            val instance =
                instanceId?.let { store.instance(it) }
                    ?: error(I18n.t("contentA1.install.noInstanceSelected"))
            require(instance.installed) { I18n.t("contentA1.install.instanceNotInstalled") }
            require(hit.type != "mod" || instance.loader != LoaderType.Vanilla) {
                I18n.t("contentA1.install.vanillaCannotLoadMods")
            }
            installPlan(planContent(hit, instance.id), context)
        }
    }
}
