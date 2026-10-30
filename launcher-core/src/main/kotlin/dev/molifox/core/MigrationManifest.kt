// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责隔离迁移清单的写入与迁移现场目录的清理。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** 迁移清单与备份目录的维护；执行器只负责搬运与回滚。 */
internal object MigrationManifest {
    /** 迁移清单供回滚失败时人工核对；成功或完整回滚后整个目录删除。 */
    fun write(
        directory: Path,
        plan: IsolationPlan,
    ) {
        val text =
            buildString {
                appendLine("MoliFox 迁移清单")
                appendLine("实例：${plan.instanceId}")
                appendLine("方向：${plan.from.title} → ${plan.to.title}")
                appendLine("源目录：${plan.sourceRoot.toAbsolutePath()}")
                appendLine("目标目录：${plan.targetRoot.toAbsolutePath()}")
                appendLine("条目：${plan.entries.size}")
                plan.entries.forEach {
                    appendLine("${it.action} ${it.relativePath} ${it.size} ${it.reason}")
                }
            }
        directory.resolve("isolation-plan.txt").writeText(text)
    }

    fun deleteTree(directory: Path) {
        if (!directory.exists(LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
