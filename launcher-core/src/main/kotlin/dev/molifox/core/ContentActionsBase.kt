// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容服务各职责共用的状态入口。

package dev.molifox.core

/** 各职责共用的状态入口：方法体直接沿用原来的 `store`、`network` 等名字。 */
internal abstract class ContentActionsBase(
    protected val core: ContentServiceCore,
) {
    protected val store: LauncherStore get() = core.store

    protected val network: NetworkClient get() = core.network

    protected val installer: MinecraftInstaller get() = core.installer

    protected val api: String get() = core.api

    protected val curseForge: CurseForgeBackend get() = core.curseForge

    protected val backend: ContentBackends get() = core.backend

    protected val metadataReader: ModMetadataReader get() = core.metadataReader

    protected val localPacks: LocalPackStore get() = core.localPacks

    protected val installedIcons: InstalledContentIcons get() = core.installedIcons
}
