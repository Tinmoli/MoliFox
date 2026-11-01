// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面操作的服务协调与状态更新。

package dev.molifox.core

import dev.molifox.core.domain.instance.InstanceWorkspaceQueries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * 桌面入口：状态、服务与生命周期都在 [LauncherControllerCore]，方法体分散在 Launcher*Actions 的继承链里。
 *
 * 调用点仍是 `controller.launch(...)` 这样的成员调用，公开签名与行为保持不变。
 */
class LauncherController
    internal constructor(
        internal val core: LauncherControllerCore,
    ) : AutoCloseable,
        LauncherRuntimeApi by core.actions,
        LauncherJavaApi by core.actions,
        LauncherInstanceApi by core.actions,
        LauncherDirectoryApi by core.actions,
        LauncherSettingsApi by core.actions,
        LauncherDeletionApi by core.actions,
        LauncherContentApi by core.actions,
        LauncherToolApi by core.actions,
        LauncherSaveApi by core.actions,
        LauncherDatapackApi by core.actions,
        LauncherQueryApi by core.actions {
        @JvmOverloads
        constructor(
            platform: PlatformServices = DesktopPlatform(),
            dataDirectory: Path = platform.defaultDataDirectory,
            updateApiBase: String = "https://api.github.com/repos/Tinmoli/MoliFox",
            networkClient: NetworkClient? = null,
        ) : this(LauncherControllerCore(platform, dataDirectory, updateApiBase, networkClient))

        init {
            core.startup()
        }

        val platform: PlatformServices
            get() = core.platform

        /** 桌面层注册的优雅退出回调；自动更新替换前调用，确保联机与后台任务先收尾。 */
        var exitHandler: (() -> Unit)?
            get() = core.exitHandler
            set(value) {
                core.exitHandler = value
            }

        val scope: CoroutineScope
            get() = core.scope

        val store: LauncherStore
            get() = core.store

        val state: StateFlow<LauncherState>
            get() = core.state

        val network: NetworkClient
            get() = core.network

        val jobs: JobQueue
            get() = core.jobs

        val updater: GitHubUpdater
            get() = core.updater

        val java: JavaService
            get() = core.java

        val accounts: AccountService
            get() = core.accounts

        val skins: SkinService
            get() = core.skins

        val installer: MinecraftInstaller
            get() = core.installer

        val directories: GameDirectoryService
            get() = core.directories

        val icons: InstanceIcons
            get() = core.icons

        val screenshots: ScreenshotManager
            get() = core.screenshots

        val content: ContentService
            get() = core.content

        val tools: InstanceTools
            get() = core.tools

        val deletion: InstanceDeletionService
            get() = core.deletion

        val saves: SaveManager
            get() = core.saves

        val instanceWorkspace: InstanceWorkspaceQueries
            get() = core.instanceWorkspace

        val datapacks: DatapackManager
            get() = core.datapacks

        val game: GameLauncher
            get() = core.game

        val busy: Boolean
            get() = core.busy

        fun install(
            request: InstallRequest,
            gameDirectoryId: String? = core.store.data.activeGameDirectoryId,
        ): String = core.actions.install(request, gameDirectoryId)

        /** 关闭崩溃原因弹窗；下一次启动失败会重新写入新的诊断结果。 */
        fun dismissLaunchIssue() {
            core.store.updateState { it.copy(launchIssue = null) }
        }

        fun updateInstance(
            id: String,
            name: String,
            memoryMb: Int,
            javaPath: String,
            isolation: VersionIsolation = core.store.instance(id).isolation,
        ) {
            saveInstanceConfiguration(
                core.store
                    .instance(id)
                    .copy(
                        name = name,
                        memoryMb = memoryMb,
                        javaPath = javaPath,
                        isolation = isolation,
                    ),
            )
        }

        override fun close() = core.shutdown()
    }
