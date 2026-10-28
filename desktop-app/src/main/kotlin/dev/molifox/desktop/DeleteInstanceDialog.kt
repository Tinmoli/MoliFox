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
 * 文案与操作参考 PCL：标题「删除实例」，正文「你确定要删除实例「名称」吗？」；实例有独立游戏目录时追加 「该实例的存档、资源包、Mod 等文件也将被一并删除！」并列出最多 6 个存档（名称 +
 * 上次修改时间）。 默认移入系统回收站；点击「删除」的瞬间读取 Shift
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
    val running = liveState.runningInstanceId == instance.id
    // 只做展示：解析失败或没有可删目录时由核心给出明确的中文失败原因或只清记录。
    val targets = remember(instance.id) { actions.controller.deletionTargets(instance.id) }
    val separate =
        remember(instance.id) { actions.controller.hasSeparateGameDirectory(instance.id) }
    val worlds = remember(instance.id) { actions.controller.deletionWorlds(instance.id) }
    val shownWorlds = worlds.take(6)
    val close: () -> Unit = { if (!busy) onDismiss() }
    Modal("删除实例", onDismiss = close) {
        Text("你确定要删除实例「" + instance.name + "」吗？", fontSize = 13.sp)
        if (separate)
            Text(
                "该实例的存档、资源包、Mod 等文件也将被一并删除！",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        if (separate && shownWorlds.isNotEmpty()) {
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
        } else {
            Text(
                "没有可删除的实例文件夹，只会清除启动器记录。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    val result = actions.controller.deleteInstance(instance.id, true, permanent)
                    withContext(Dispatchers.Swing) {
                        busy = false
                        result
                            .onSuccess { report ->
                                val message =
                                    when {
                                        report.removedPaths.isEmpty() ->
                                            "已删除实例：" + instance.name + "（仅清除记录）"
                                        report.movedToTrash -> "已移入回收站：" + instance.name
                                        else ->
                                            "已永久删除：" +
                                                instance.name +
                                                "（释放 " +
                                                deletionSizeText(report.freedBytes) +
                                                "）"
                                    }
                                actions.controller.store.notify(message)
                                onDeleted()
                            }
                            .onFailure { failure ->
                                val message = failure.message ?: "删除实例失败"
                                error = message
                                actions.controller.store.notify("删除实例失败：" + message)
                            }
                    }
                }
            },
            onCancel = close,
            danger = true,
            enabled = !running,
            busy = busy,
            hint = if (running) "实例正在运行，请先退出游戏" else "",
        )
        Text(
            "按住 Shift 点击「删除」将永久删除，不进回收站。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
