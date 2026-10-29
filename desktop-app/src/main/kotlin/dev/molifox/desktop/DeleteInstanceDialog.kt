// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除确认和 Shift 永久删除选择。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 删除实例的字节数：只用于提示与对话框展示，不参与业务计算。 */
private fun deletionSizeText(bytes: Long): String =
    when {
        bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 存档「上次修改」时间，本地时区；读不到修改时间时如实说明。 */
private val deletionTimeFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun deletionTimeText(modified: Instant?): String =
    modified?.let { deletionTimeFormat.format(it) } ?: "时间未知"

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
    Modal("删除实例", onDismiss = close) {
        Text("你确定要删除实例「" + instance.name + "」吗？", fontSize = 13.sp)
        Text(
            "将删除 versions/" + versionName + " 及其中的存档与模组；共享的 libraries/assets 不受影响。",
            fontSize = 13.sp,
        )
        if (blockReason != null) {
            Text(blockReason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            Text(
                "版本目录被其他实例共用时不能删除；请先处理占用它的实例。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (separate && blockReason == null)
            Text(
                "该实例的存档、资源包、Mod 等文件也将被一并删除！",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        if (separate && blockReason == null && shownWorlds.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "将一并删除的存档（" + worlds.size + " 个，最多列出 6 个）：",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shownWorlds.forEach { world ->
                    Text(
                        "· " + world.name + " · 上次修改 " + deletionTimeText(world.modified),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (worlds.size > shownWorlds.size)
                    Text(
                        "…另有 " + (worlds.size - shownWorlds.size) + " 个存档",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
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
                "没有可删除的实例文件夹，只会移除失效的实例记录。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (preserved.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "以下内容仍被其他实例使用，将保留不删：",
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = "删除",
            onConfirm = {
                // 点击瞬间的 Shift 状态决定去向：按住 = 永久删除，否则移入回收站。
                val permanent = windowInfo.keyboardModifiers.isShiftPressed
                busy = true
                error = null
                actions.run {
                    val result = actions.controller.deleteInstance(instance.id, permanent = permanent)
                    withContext(Dispatchers.Swing) {
                        busy = false
                        result
                            .onSuccess { report ->
                                // 提示必须和实际结果一致：保留了什么就明说，删除的文件数/字节如实报告。
                                val removed = report.removedPaths.size
                                val removedText =
                                    if (removed == 0) "未删除文件"
                                    else
                                        "已删除 " +
                                            removed +
                                            " 项（" +
                                            if (report.movedToTrash) "已移入回收站"
                                            else "释放 " + deletionSizeText(report.freedBytes) +
                                                "）"
                                val keptText =
                                    if (report.preserved.isEmpty()) ""
                                    else "；保留 " + report.preserved.size + " 项共用目录"
                                val message =
                                    if (removed == 0)
                                        "已删除实例：" + instance.name + "（目录已不存在，仅移除记录）"
                                    else
                                        "已删除实例：" +
                                            instance.name +
                                            "（" +
                                            removedText +
                                            keptText +
                                            "）"
                                actions.controller.store.notify(message)
                                onDeleted()
                            }
                            .onFailure { failure ->
                                val message = failure.message ?: "删除实例失败"
                                error = message
                                actions.controller.store.notify(
                                    if (message.startsWith("无法删除")) message
                                    else "删除实例失败：" + message
                                )
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
                    running -> "实例正在运行，请先退出游戏"
                    blockReason != null -> "版本目录仍被其他实例使用，无法删除"
                    else -> ""
                },
        )
        Text(
            "按住 Shift 点击「删除」将永久删除，不进回收站。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
