// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责隔离迁移清单的写入与迁移现场目录的清理。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
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
                appendLine(I18n.t("diagA2.migrationManifest.title"))
                appendLine(I18n.t("diagA2.migrationManifest.instance", plan.instanceId))
                appendLine(
                    I18n.t("diagA2.migrationManifest.direction", plan.from.title, plan.to.title),
                )
                appendLine(I18n.t("diagA2.migrationManifest.sourceRoot", plan.sourceRoot.toAbsolutePath()))
                appendLine(I18n.t("diagA2.migrationManifest.targetRoot", plan.targetRoot.toAbsolutePath()))
                appendLine(I18n.t("diagA2.migrationManifest.entries", plan.entries.size))
                plan.entries.forEach {
                    appendLine("${it.action} ${it.relativePath} ${it.size} ${it.reason}")
                }
            }
        directory.resolve("isolation-plan.txt").writeText(text)
    }

    /**
     * 删除迁移暂存/备份目录。
     *
     * 用 [deletionDeleteTree] 而不是裸 `Files.walk`：后者会进入 Windows 目录联接（junction）指向的
     * 真实目录，把用户放在别处的内容一起删掉；[deletionDeleteTree] 只删链接本身、不进入。
     */
    fun deleteTree(directory: Path) {
        deletionDeleteTree(directory)
    }
}
