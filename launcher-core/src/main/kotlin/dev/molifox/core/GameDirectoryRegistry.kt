// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏目录的路径归一化、幂等键与实例合并规则。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** 目录解析与合并规则；状态写入与流程编排留在 GameDirectoryService。 */
internal class GameDirectoryRegistry(
    private val store: LauncherStore,
) {
    /** 用户手动选择：含 versions 的目录、含 .minecraft 的上级目录，或空目录（作为安装目标）。 */
    fun resolveRoot(source: Path): Path {
        val sourcePath = source.toAbsolutePath().normalize()
        val root =
            when {
                sourcePath.resolve("versions").isDirectory() -> sourcePath
                sourcePath.resolve(".minecraft/versions").isDirectory() ->
                    sourcePath.resolve(".minecraft")
                sourcePath.resolve(".minecraft").isDirectory() -> sourcePath.resolve(".minecraft")
                sourcePath.isDirectory() -> sourcePath
                else -> error(I18n.t("instA1.gameDirectory.selectVersionsFolder"))
            }.toRealPath()
        require(root.parent != null) { I18n.t("instA1.gameDirectory.selectDedicatedFolder") }
        return root
    }

    /** 幂等键：真实路径优先，其次规范化绝对路径；Windows 下按大小写不敏感比较。 */
    fun registered(root: Path): GameDirectory? =
        store.data.gameDirectories.find {
            sameDirectory(
                Path.of(it.path),
                root,
            )
        }

    fun sameDirectory(
        left: Path,
        right: Path,
    ): Boolean {
        val a = runCatching { left.toRealPath() }.getOrElse { left.toAbsolutePath().normalize() }
        val b = runCatching { right.toRealPath() }.getOrElse { right.toAbsolutePath().normalize() }
        return if (store.platform.osName == "windows") {
            a.toString().equals(b.toString(), ignoreCase = true)
        } else {
            a == b
        }
    }

    /** 新建目录记录：`.minecraft` 用上级目录名，其他目录用自己的目录名。 */
    fun newDirectory(root: Path): GameDirectory =
        GameDirectory(
            name =
                if (root.fileName.toString() == ".minecraft") {
                    root.parent?.fileName?.toString() ?: ".minecraft"
                } else {
                    root.fileName.toString()
                },
            path = root.toString(),
        )

    /** 组件基础配置不重复显示为实例；保留已有实例自己的设置和目录名。 */
    fun mergeScannedInstances(
        allInstances: List<GameInstance>,
        represented: List<GameInstance>,
    ): List<GameInstance> =
        allInstances.filter { candidate ->
            represented.none { original ->
                original.versionId == candidate.versionId ||
                    original.gameDirectoryName.ifBlank { original.versionId } ==
                    candidate.versionId
            }
        } +
            allInstances.filter { candidate ->
                represented.any { it.versionId == candidate.versionId }
            }

    /** 已登记的实例用扫描结果刷新安装状态与版本信息；托管目录保留原记录。 */
    fun refreshedInstances(
        current: List<GameInstance>,
        instances: List<GameInstance>,
    ): List<GameInstance> =
        current.mapNotNull { original ->
            if (original.managedGameDirectory && !original.externalProfile) {
                return@mapNotNull original
            }
            instances
                .find { it.versionId == original.versionId }
                ?.let { scanned ->
                    original.copy(
                        installed = scanned.installed,
                        gameVersion = scanned.gameVersion,
                        loader = scanned.loader,
                        loaderVersion = scanned.loaderVersion,
                    )
                }
        }

    /** 磁盘上有、记录里还没有的版本。 */
    fun freshInstances(
        current: List<GameInstance>,
        instances: List<GameInstance>,
    ): List<GameInstance> = instances.filter { candidate -> current.none { it.versionId == candidate.versionId } }

    /** 选中实例仍存在就保留，否则退回该目录的第一个实例。 */
    fun selectedCandidate(
        selectedInstanceId: String?,
        candidates: List<GameInstance>,
    ): String? =
        selectedInstanceId?.takeIf { id -> candidates.any { it.id == id } }
            ?: candidates.firstOrNull()?.id
}
