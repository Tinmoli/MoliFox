// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除确认和 Shift 永久删除选择。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceDeletionBlockedException
import dev.molifox.core.InstanceDeletionReport
import dev.molifox.core.InstanceDeletionWorld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 「删除实例」确认框（实例列表三点菜单与实例详情「管理」共用）。
 *
 * 标题「删除实例」，正文先说明「将删除 versions/<名字> 及其中的存档与模组；
 * 共享的 libraries/assets 不受影响」。版本目录仍被其他实例使用时提前展示拒绝原因并禁用「删除」，
 * 不会删到一半才失败。有独立游戏目录时追加「该实例的存档、资源包、Mod 等文件也将被一并删除！」并列出
 * 最多 6 个存档（名称 + 上次修改时间）。 默认移入系统回收站；点击「删除」的瞬间读取 Shift
 * 状态（[androidx.compose.ui.platform.WindowInfo.keyboardModifiers]）， 按住 Shift
 * 则永久删除，按钮下方常驻说明。成功后提示「已移入回收站：名称」或「已永久删除：名称（释放 X MB）」。
 */
@Composable
fun DeleteInstanceDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = onDismiss,
) {
    val liveState by actions.controller.state.collectAsState()
    // 组合期拿到窗口信息，点击回调里再读键盘修饰键，保证按的是点击那一刻的 Shift。
    val windowInfo = LocalWindowInfo.current
    var busy by remember(instance.id) { mutableStateOf(false) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    val running = liveState.runningGames.any { it.instanceId == instance.id }
    // 只做展示：解析失败或没有可删目录时由核心给出明确的中文失败原因或只清记录。
    val targets = remember(instance.id) { actions.controller.deletionTargets(instance.id) }
    // 共用版本目录时整次删除会被拒绝：必须提前说明，而不是点下去才报错。
    val blockReason = remember(instance.id) { actions.controller.deletionBlockReason(instance.id) }
    // 因其他实例真实使用而保留的目录：必须提前说明，不能删完再声称已经删除。
    val preserved = remember(instance.id) { actions.controller.deletionPreserved(instance.id) }
    val separate =
        remember(instance.id) { actions.controller.hasSeparateGameDirectory(instance.id) }
    val worlds = remember(instance.id) { actions.controller.deletionWorlds(instance.id) }
    val shownWorlds = worlds.take(6)
    val versionName =
        instance.gameDirectoryName.ifBlank {
            instance.versionId.ifBlank { instance.gameVersion }
        }
    val close: () -> Unit = { if (!busy) onDismiss() }
    // 独立游戏目录且未被共用时存档会一起删除：「一并删除」与存档名单两处提示共用同一个判断。
    val showWorlds = separate && blockReason == null
    val strings = LocalStrings.current
    Modal(strings.t("dialogs.delete.title"), onDismiss = close) {
        Text(strings.t("dialogs.delete.confirmMessage", instance.name), fontSize = 13.sp)
        Text(
            strings.t("dialogs.delete.targetMessage", versionName),
            fontSize = 13.sp,
        )
        DeletionBlockedNotice(blockReason)
        SeparateDirectoryNotice(showWorlds)
        DeletionWorldsNotice(showWorlds, worlds, shownWorlds)
        DeletionTargetsNotice(targets, blockReason)
        PreservedDirectoriesNotice(preserved)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = strings.t("dialogs.delete.confirm"),
            onConfirm = {
                // 点击瞬间的 Shift 状态决定去向：按住 = 永久删除，否则移入回收站。
                val permanent = windowInfo.keyboardModifiers.isShiftPressed
                busy = true
                error = null
                actions.run {
                    val result =
                        actions.controller.deleteInstance(
                            instance.id,
                            permanent = permanent,
                        )
                    withContext(Dispatchers.Swing) {
                        busy = false
                        result
                            .onSuccess { report ->
                                actions.controller.store.notify(
                                    deletionSuccessMessage(instance.name, report),
                                )
                                onDeleted()
                            }.onFailure { failure ->
                                val message = failure.message ?: tr("dialogs.delete.failed")
                                error = message
                                actions.controller.store.notify(deletionFailureMessage(failure))
                            }
                    }
                }
            },
            onCancel = close,
            danger = true,
            enabled = !running && blockReason == null,
            busy = busy,
            hint =
                when {
                    running -> strings.t("dialogs.delete.hintRunning")
                    blockReason != null -> strings.t("dialogs.delete.hintBlocked")
                    else -> ""
                },
        )
        Text(
            strings.t("dialogs.delete.shiftNote"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 版本目录被其他实例共用：提前说明这次删除会被拒绝，并禁用「删除」。 */
@Composable
private fun DeletionBlockedNotice(blockReason: String?) {
    if (blockReason == null) return
    Text(blockReason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
    Text(
        LocalStrings.current.t("dialogs.delete.blockedNote"),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 有独立游戏目录时的额外警告：存档、资源包、Mod 都会被一并删除。 */
@Composable
private fun SeparateDirectoryNotice(show: Boolean) {
    if (!show) return
    Text(
        LocalStrings.current.t("dialogs.delete.separateWarning"),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 会被一并删除的存档名单：名称 + 上次修改时间，最多列出 6 个，其余只报数量。 */
@Composable
private fun DeletionWorldsNotice(
    show: Boolean,
    worlds: List<InstanceDeletionWorld>,
    shownWorlds: List<InstanceDeletionWorld>,
) {
    if (!show || shownWorlds.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            LocalStrings.current.t("dialogs.delete.worldsTitle", worlds.size),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shownWorlds.forEach { world ->
            Text(
                LocalStrings.current.t(
                    "dialogs.delete.worldLine",
                    world.name,
                    formatSaveTime(world.modified, tr("dialogs.delete.timeUnknown")),
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (worlds.size > shownWorlds.size) {
            Text(
                LocalStrings.current.t("dialogs.delete.moreWorlds", worlds.size - shownWorlds.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 会被删除的绝对路径；没有目标且未被阻止时说明只移除失效记录。 */
@Composable
private fun DeletionTargetsNotice(
    targets: List<Path>,
    blockReason: String?,
) {
    if (targets.isNotEmpty()) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                targets.forEach { path ->
                    Text(
                        path.toString(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else if (blockReason == null) {
        Text(
            LocalStrings.current.t("dialogs.delete.noTargets"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 因其他实例真实使用而保留的目录：删除完成后的提示必须与这里的名单一致。 */
@Composable
private fun PreservedDirectoriesNotice(preserved: List<String>) {
    if (preserved.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            LocalStrings.current.t("dialogs.delete.preservedTitle"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preserved.forEach { reason ->
            Text(
                "· " + reason,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 删除结果摘要（删除项数与去向）；文案与磁盘上的实际结果一一对应。 */
private fun removalSummary(report: InstanceDeletionReport): String {
    val removed = report.removedPaths.size
    if (removed == 0) return tr("dialogs.delete.nothingRemoved")
    val moved =
        if (report.movedToTrash) {
            tr("dialogs.delete.movedToTrash")
        } else {
            tr("dialogs.delete.freedSpace", byteSizeText(report.freedBytes))
        }
    return tr("dialogs.delete.removedSummary", removed, moved)
}

/** 成功提示：保留了什么就明说，删除的文件数/字节如实报告。 */
private fun deletionSuccessMessage(
    name: String,
    report: InstanceDeletionReport,
): String {
    if (report.removedPaths.isEmpty()) {
        return tr("dialogs.delete.successMissingDir", name)
    }
    val kept =
        if (report.preserved.isEmpty()) {
            ""
        } else {
            tr("dialogs.delete.keptDirs", report.preserved.size)
        }
    return tr("dialogs.delete.success", name, removalSummary(report), kept)
}

/** 失败提示：核心给出的「无法删除…」原因原样提示，其余前面补上动作说明。 */
private fun deletionFailureMessage(failure: Throwable): String =
    if (failure is InstanceDeletionBlockedException) {
        failure.message ?: tr("dialogs.delete.failed")
    } else {
        tr("dialogs.delete.failedWith", failure.message ?: tr("dialogs.delete.failed"))
    }
