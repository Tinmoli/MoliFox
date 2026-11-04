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
import dev.molifox.desktop.MoliTextButton as TextButton
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
    val strings = LocalStrings.current
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
            error = failure.message ?: strings.t("dialogs.saveInfo.loadFailed")
        } catch (failure: IllegalStateException) {
            details = null
            error = failure.message ?: strings.t("dialogs.saveInfo.loadFailed")
        } catch (failure: IOException) {
            details = null
            error = failure.message ?: strings.t("dialogs.saveInfo.readFailed")
        } finally {
            loading = false
        }
    }
    ScrollColumn(modifier.fillMaxWidth()) {
        when {
            loading ->
                Panel(Modifier.fillMaxWidth()) {
                    Text(
                        strings.t("dialogs.saveInfo.loading"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(strings.t("dialogs.saveInfo.loadFailed"), error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ retry++ }) { Text(strings.t("dialogs.common.retry")) }
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
    val strings = LocalStrings.current
    val clipboard = LocalClipboardManager.current
    Panel(Modifier.fillMaxWidth(), title = strings.t("dialogs.saveInfo.worldSection")) {
        DetailLine(strings.t("dialogs.saveInfo.levelName"), details.levelName)
        DetailLine(strings.t("dialogs.saveInfo.gameVersion"), versionText(details))
        details.seed?.let { seed ->
            DetailLine(
                strings.t("dialogs.saveInfo.seed"),
                seed.toString(),
                action = {
                    TextButton({ clipboard.setText(AnnotatedString(seed.toString())) }) {
                        Text(strings.t("dialogs.common.copy"), fontSize = 11.sp)
                    }
                },
            )
        }
        details.spawn?.let { spawn ->
            DetailLine(
                strings.t("dialogs.saveInfo.spawn"),
                "${spawn.x.toInt()} / ${spawn.y.toInt()} / ${spawn.z.toInt()}",
            )
        }
        DetailLine(strings.t("dialogs.saveInfo.lastPlayed"), formatSaveTime(details.lastPlayed))
        DetailLine(strings.t("dialogs.saveInfo.gameMode"), gameModeText(details))
        details.difficulty?.let { DetailLine(strings.t("dialogs.saveInfo.difficulty"), difficultyText(it)) }
        details.allowCommands?.let {
            DetailLine(
                strings.t("dialogs.saveInfo.allowCommands"),
                if (it) strings.t("dialogs.common.yes") else strings.t("dialogs.common.no"),
            )
        }
        details.playTicks?.let { DetailLine(strings.t("dialogs.saveInfo.playTime"), formatPlayTicks(it)) }
        if (details.hardcore) {
            DetailLine(strings.t("dialogs.saveInfo.hardcore"), strings.t("dialogs.saveInfo.hardcoreYes"))
        }
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
            tr("dialogs.saveInfo.versionWithData", details.versionName, details.versionId)
        details.versionName != null -> details.versionName.orEmpty()
        details.versionId != null -> tr("dialogs.saveInfo.dataVersion", details.versionId)
        else -> tr("dialogs.saveInfo.versionUnknown")
    }

private fun gameModeText(details: SaveDetails): String {
    val mode =
        when (details.gameMode) {
            0 -> tr("dialogs.saveInfo.mode.survival")
            1 -> tr("dialogs.saveInfo.mode.creative")
            2 -> tr("dialogs.saveInfo.mode.adventure")
            3 -> tr("dialogs.saveInfo.mode.spectator")
            else -> tr("dialogs.common.unknown")
        }
    return mode + if (details.hardcore) tr("dialogs.saveInfo.hardcoreSuffix") else ""
}

private fun difficultyText(value: Int): String = difficultyLabel(value)

/** `Data.Time` 每 20 刻约一秒；只用于展示量级，不宣称与实际游玩时长完全一致。 */
private fun formatPlayTicks(ticks: Long): String {
    val totalMinutes = ticks / TICKS_PER_SECOND / SECONDS_PER_MINUTE
    if (totalMinutes < 1) return tr("dialogs.saveInfo.playtime.lessThanMinute")
    val days = totalMinutes / (HOURS_PER_DAY * MINUTES_PER_HOUR)
    val hours = totalMinutes % (HOURS_PER_DAY * MINUTES_PER_HOUR) / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return buildString {
        if (days > 0) append(tr("dialogs.saveInfo.playtime.days", days))
        if (hours > 0) append(tr("dialogs.saveInfo.playtime.hours", hours))
        if (minutes > 0) append(tr("dialogs.saveInfo.playtime.minutes", minutes))
    }.trim()
}
