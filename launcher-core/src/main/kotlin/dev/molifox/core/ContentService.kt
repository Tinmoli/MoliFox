// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容搜索、安装和整合包管理的服务入口。

package dev.molifox.core

import java.nio.file.Path

/**
 * 内容服务入口：搜索、计划、整合包与导出各自独立，本类只持有同一份会话状态。
 *
 * 方法体分散在 [ContentSearchActions] 起的继承链里，调用点仍是 `content.search(...)` 这样的成员调用，
 * 公开签名与行为保持不变。
 */
class ContentService
    internal constructor(
        internal val core: ContentServiceCore,
    ) : ContentSearchApi by core.actions,
        ContentPackDownloadApi by core.actions,
        ContentLocalApi by core.actions,
        ContentPackImportApi by core.actions,
        ContentPackUpgradeApi by core.actions,
        ContentExportApi by core.actions {
        constructor(
            store: LauncherStore,
            network: NetworkClient,
            installer: MinecraftInstaller,
            api: String = "https://api.modrinth.com/v2",
        ) : this(ContentServiceCore(store, network, installer, api))

        val catalog: ContentCatalog
            get() = core.catalog

        // 模组更新：指纹优先匹配 Modrinth，CurseForge 走现有公共镜像后端（跟随设置），不需要 API Key。
        val modUpdates: ModUpdateService
            get() = core.modUpdates

        // 资源包与光影的更新检查：与模组同样的内容源与镜像设置，只读，不下载文件。
        val packUpdates: PackUpdateService
            get() = core.packUpdates

        suspend fun installContent(
            hit: ContentHit,
            instanceId: String?,
            context: JobContext,
            gameDirectoryId: String? = core.store.data.activeGameDirectoryId,
        ) = core.actions.installContent(hit, instanceId, context, gameDirectoryId)

        /** 下载世界包并解压到实例的 `saves/`：世界目录名取自包内包装目录或 `level.dat`。 */
        suspend fun installWorld(
            hit: ContentHit,
            versionId: String,
            instanceId: String,
            context: JobContext,
        ) = core.actions.installWorld(hit, versionId, instanceId, context)

        /**
         * 导入整合包：[repairId] 非空时修复既有实例，否则新建实例；[optionalFiles] 为空表示按
         * 整合包记录的默认选择，[expectedHash] 用于确认归档未被替换。
         */
        suspend fun importPack(
            archive: Path,
            context: JobContext,
            repairId: String? = null,
            optionalFiles: Set<String>? = null,
            expectedHash: String? = null,
            gameDirectoryId: String? = core.store.data.activeGameDirectoryId,
        ) = core.actions.importPack(
            archive,
            context,
            repairId,
            optionalFiles,
            expectedHash,
            gameDirectoryId,
        )
    }
