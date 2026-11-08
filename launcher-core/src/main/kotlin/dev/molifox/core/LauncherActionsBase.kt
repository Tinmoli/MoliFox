// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 桌面入口各职责共用的状态入口。

package dev.molifox.core

import dev.molifox.core.domain.instance.InstanceWorkspaceQueries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** 各职责共用的状态入口：方法体直接沿用原来的 `store`、`scope` 等名字。 */
internal abstract class LauncherActionsBase(
    protected val core: LauncherControllerCore,
) {
    protected val platform: PlatformServices get() = core.platform

    protected val scope: CoroutineScope get() = core.scope

    protected val store: LauncherStore get() = core.store

    protected val state: StateFlow<LauncherState> get() = core.state

    protected val network: NetworkClient get() = core.network

    protected val jobs: JobQueue get() = core.jobs

    protected val updater: GitHubUpdater get() = core.updater

    protected val java: JavaService get() = core.java

    protected val accounts: AccountService get() = core.accounts

    protected val skins: SkinService get() = core.skins

    protected val installer: MinecraftInstaller get() = core.installer

    protected val directories: GameDirectoryService get() = core.directories

    protected val icons: InstanceIcons get() = core.icons

    protected val screenshots: ScreenshotManager get() = core.screenshots

    protected val content: ContentService get() = core.content

    protected val tools: InstanceTools get() = core.tools

    protected val deletion: InstanceDeletionService get() = core.deletion

    protected val saves: SaveManager get() = core.saves

    protected val instanceWorkspace: InstanceWorkspaceQueries get() = core.instanceWorkspace

    protected val datapacks: DatapackManager get() = core.datapacks

    protected val renamer: InstanceRename get() = core.renamer

    protected val migration: IsolationMigration get() = core.migration

    protected val game: GameLauncher get() = core.game
}
