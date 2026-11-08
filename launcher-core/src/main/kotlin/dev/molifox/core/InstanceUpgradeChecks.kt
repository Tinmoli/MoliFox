// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 升级校验的只读统计：迁移条目在源与目标实例中的落盘文件数。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** 迁移复制与校验共用的跳过规则：用户单独取消的子路径 + 启动器私有/临时文件（锁文件始终跳过）。 */
internal fun migrationEntrySkipped(
    relative: String,
    excluded: Set<String>,
): Boolean = exportPathExcluded(relative, excluded) || excludedExportPath(relative, allowLogs = true)

/** 「修改」页签升级第 5 步（校验并完成）使用的只读统计。 */
internal class InstanceUpgradeChecks {
    /**
     * 统计一个迁移条目在 [root] 实例下已落盘的文件数：顶层文件记 1，目录递归统计；
     * 链接、启动器临时文件与被单独取消的子路径不计，与复制端规则一致。
     */
    fun countFiles(
        root: Path,
        name: String,
        excluded: Set<String>,
    ): Int {
        val entry = root.resolve(name)
        if (entry.isRegularFile()) return 1
        if (!Files.isDirectory(entry)) return 0
        return Files.walk(entry).use { stream ->
            stream
                .filter { path ->
                    path.isRegularFile() && !Files.isSymbolicLink(path) &&
                        !migrationEntrySkipped("$name/" + entry.relativize(path).joinToString("/"), excluded)
                }.count()
                .toInt()
        }
    }
}
