// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置持久化、实例路径和界面状态。

package dev.molifox.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

/**
 * 启动器的数据入口：配置、实例记录与运行日志都保存一份状态。
 *
 * 读写方法按职责分散在 [StoreRecordApi]、[StoreLogApi]、[StoreScanApi] 与 [StorePathApi] 的委托实现里，
 * 调用点仍是 `store.mutate {}`、`store.log(...)` 这样的成员调用，公开签名与行为保持不变。
 */
class LauncherStore
    internal constructor(
        internal val core: LauncherStoreCore,
    ) : StoreRecordApi by core.actions,
        StoreLogApi by core.actions,
        StoreScanApi by core.actions,
        StorePathApi by core.actions {
        constructor(
            platform: PlatformServices,
            dataDirectory: Path = platform.defaultDataDirectory,
        ) : this(LauncherStoreCore(platform, dataDirectory))

        val platform: PlatformServices
            get() = core.platform

        val dataDirectory: Path
            get() = core.dataDirectory

        val directories: LauncherDirectories
            get() = core.directories

        val recentUsage: RecentUsage by lazy { RecentUsage(this) }

        val state: StateFlow<LauncherState>
            get() = core.mutableState.asStateFlow()

        val data: LauncherData
            get() = core.data

        val minecraftRoot: Path
            get() = core.minecraftRoot

        /** 下载缓存目录：用户自定义 downloadCachePath，为空时使用数据目录下的 cache/。 */
        val cacheDirectory: Path
            get() =
                data.settings.downloadCachePath
                    .takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }
                    ?: dataDirectory.resolve("cache")

        val downloadDirectory: Path
            get() = directories.cache(data.settings.downloadCachePath).downloads

        val metadataDirectory: Path
            get() = directories.cache(data.settings.downloadCachePath).metadata

        val temporaryDirectory: Path
            get() = directories.cache(data.settings.downloadCachePath).temporary

        /** 界面在窗口尺寸变化时上报的当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
        var launcherWindowSize: Pair<Int, Int>?
            get() = core.launcherWindowSize
            set(value) {
                core.launcherWindowSize = value
            }

        /** 内部动作的入口：需要绕过公开方法（或旧内部方法）时直接用它。 */
        internal val actions: LauncherStoreActions
            get() = core.actions

        /** 仅供模块内更新界面状态；界面模块不该直接改状态。 */
        internal fun updateState(block: (LauncherState) -> LauncherState) =
            core.actions.updateState(block)

        /** 保存与状态写入共用的监视器：外部需要在整段操作里独占存储时用它。 */
        internal val lock: Any
            get() = core.actions

        init {
            core.actions.startup()
        }
    }
