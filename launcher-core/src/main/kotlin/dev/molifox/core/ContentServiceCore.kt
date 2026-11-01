// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容服务的状态装配。

package dev.molifox.core

/**
 * 内容服务的全部运行时状态：后端、目录、更新与图标仓库都在这里创建一次。
 *
 * 方法体按职责分散在 Content*Actions 的继承链里，本类只保留字段与 [actions]；
 * 字段声明顺序与原先一致，构造时机不变。
 */
internal class ContentServiceCore(
    val store: LauncherStore,
    val network: NetworkClient,
    val installer: MinecraftInstaller,
    val api: String,
) {
    internal val metadataReader = ModMetadataReader()

    // CurseForge 的根地址来自设置（默认社区镜像），不内置任何 API Key。
    internal val curseForge = CurseForgeBackend(store, network)
    internal val backend = ContentBackends(ModrinthBackend(network, api), curseForge)
    val catalog = ContentCatalog(store, network, api, backend, curseForge)

    // 模组更新：指纹优先匹配 Modrinth，CurseForge 走现有公共镜像后端（跟随设置），不需要 API Key。
    val modUpdates =
        ModUpdateService(
            store,
            metadataReader,
            defaultModUpdateSources(network, api, curseForge),
            defaultModDownloader(network, api) { curseForge.api },
        )

    // 资源包与光影的更新：与模组同样的内容源与镜像设置；检查只读，应用更新时才下载。
    val packUpdates =
        PackUpdateService(
            store,
            defaultPackUpdateSources(network, api, curseForge),
            defaultPackUpdateDownloader(network),
        )
    internal val localPacks = LocalPackStore(store)
    internal val installedIcons =
        InstalledContentIcons(store, network, backend, api, identifyUnregistered = true)

    /** 方法实现；只保存对本对象的引用，构造期间不会读写状态。 */
    internal val actions: ContentServiceActions = ContentServiceActions(this)
}
