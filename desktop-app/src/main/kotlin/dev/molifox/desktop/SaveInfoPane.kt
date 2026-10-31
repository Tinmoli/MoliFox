// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 存档详情的信息页：展示 level.dat 字段并触发设置区。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.SaveDetails
import dev.molifox.core.SaveSettingsChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException

/** `Data.Time` 的换算单位：20 刻/秒、60 秒/分钟、60 分钟/小时、24 小时/天。 */
private const val TICKS_PER_SECOND = 20
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/** 存档信息区；设置区在 [SaveSettingsCard]。保存协程由弹窗持有，切页签不会取消写入。 */
@Composable
internal fun SaveInfoPane(
    instance: GameInstance,
    saveName: String,
    actions: UiActions,
    canEdit: Boolean,
    saving: Boolean,
    saveError: String?,
    saveDone: Boolean,
    onDismissNotice: () -> Unit,
    onSave: (SaveSettingsChanges) -> Unit,
    reload: Int,
    modifier: Modifier = Modifier,
) {
    var retry by remember(saveName) { mutableIntStateOf(0) }
    var details by remember(saveName) { mutableStateOf<SaveDetails?>(null) }
    var loading by remember(saveName) { mutableStateOf(true) }
    var error by remember(saveName) { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, saveName, reload, retry) {
        loading = true
        error = null
        try {
            details =
                withContext(Dispatchers.IO) {
                    actions.controller.saves.readDetails(instance.id, saveName)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            details = null
            error = failure.message ?: "无法读取存档详情"
        } catch (failure: IllegalStateException) {
            details = null
            error = failure.message ?: "无法读取存档详情"
        } catch (failure: IOException) {
            details = null
            error = failure.message ?: "读取存档文件失败"
        } finally {
            loading = false
        }
    }
    ScrollColumn(modifier.fillMaxWidth()) {
        when {
            loading ->
                Panel(Modifier.fillMaxWidth()) {
                    Text(
                        "正在读取存档信息…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取存档详情", error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ retry++ }) { Text("重试") }
                    }
                }
            details != null ->
                details?.let { current ->
                    SaveInfoCard(current)
                    SaveSettingsCard(
                        details = current,
                        canEdit = canEdit,
                        saving = saving,
                        saveError = saveError,
                        saveDone = saveDone,
                        onDismissNotice = onDismissNotice,
                        onSave = onSave,
                    )
                }
        }
    }
}

@Composable
private fun SaveInfoCard(details: SaveDetails) {
    val clipboard = LocalClipboardManager.current
    Panel(Modifier.fillMaxWidth(), title = "世界信息") {
        DetailLine("世界名", details.levelName)
        DetailLine("游戏版本", versionText(details))
        details.seed?.let { seed ->
            DetailLine(
                "种子",
                seed.toString(),
                action = {
                    TextButton({ clipboard.setText(AnnotatedString(seed.toString())) }) {
                        Text("复制", fontSize = 11.sp)
                    }
                },
            )
        }
        details.spawn?.let { spawn ->
            DetailLine("出生点", "${spawn.x.toInt()} / ${spawn.y.toInt()} / ${spawn.z.toInt()}")
        }
        DetailLine("最后游玩", formatSaveTime(details.lastPlayed))
        DetailLine("游戏模式", gameModeText(details))
        details.difficulty?.let { DetailLine("难度", difficultyText(it)) }
        details.allowCommands?.let { DetailLine("允许作弊", if (it) "是" else "否") }
        details.playTicks?.let { DetailLine("游玩时长", formatPlayTicks(it)) }
        if (details.hardcore) DetailLine("硬核", "是（死亡后无法复活）")
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

private fun versionText(details: SaveDetails): String =
    when {
        details.versionName != null && details.versionId != null ->
            "${details.versionName}（${details.versionId}）"
        details.versionName != null -> details.versionName.orEmpty()
        details.versionId != null -> "数据版本 ${details.versionId}"
        else -> "未知（旧存档或缺少 Version 标签）"
    }

private fun gameModeText(details: SaveDetails): String {
    val mode =
        when (details.gameMode) {
            0 -> "生存"
            1 -> "创造"
            2 -> "冒险"
            3 -> "旁观"
            else -> "未知"
        }
    return mode + if (details.hardcore) "（硬核）" else ""
}

private fun difficultyText(value: Int): String =
    DIFFICULTY_LABELS.firstOrNull { it.first == value }?.second ?: "未知"

/** `Data.Time` 每 20 刻约一秒；只用于展示量级，不宣称与实际游玩时长完全一致。 */
private fun formatPlayTicks(ticks: Long): String {
    val totalMinutes = ticks / TICKS_PER_SECOND / SECONDS_PER_MINUTE
    if (totalMinutes < 1) return "不到 1 分钟"
    val days = totalMinutes / (HOURS_PER_DAY * MINUTES_PER_HOUR)
    val hours = totalMinutes % (HOURS_PER_DAY * MINUTES_PER_HOUR) / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return buildString {
        if (days > 0) append("$days 天 ")
        if (hours > 0) append("$hours 小时 ")
        if (minutes > 0) append("$minutes 分钟")
    }.trim()
}
