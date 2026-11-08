// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 存档详情的设置区：允许作弊、难度与锁定难度。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.SaveDetails
import dev.molifox.core.SaveSettingsChanges
import dev.molifox.desktop.MoliButton as Button

/** 难度档位（0–3，对应 level.dat 数值）；显示文案随语言变化，不能做成静态标签表。 */
private const val DIFFICULTY_HARD = 3

internal val DIFFICULTY_LEVELS = listOf(0, 1, 2, 3)

/** 难度档位文案：写回仍用数值，这里只负责显示。 */
internal fun difficultyLabel(value: Int): String =
    when (value) {
        0 -> tr("dialogs.saveSettings.difficulty.peaceful")
        1 -> tr("dialogs.saveSettings.difficulty.easy")
        2 -> tr("dialogs.saveSettings.difficulty.normal")
        DIFFICULTY_HARD -> tr("dialogs.saveSettings.difficulty.hard")
        else -> tr("dialogs.common.unknown")
    }

/** 存档设置卡片；只写回 level.dat 里真实存在的字段。 */
@Composable
internal fun SaveSettingsCard(
    details: SaveDetails,
    canEdit: Boolean,
    saving: Boolean,
    saveError: String?,
    saveDone: Boolean,
    onDismissNotice: () -> Unit,
    onSave: (SaveSettingsChanges) -> Unit,
) {
    val strings = LocalStrings.current
    var allowCommands by remember(details) { mutableStateOf(details.allowCommands) }
    var difficulty by remember(details) { mutableStateOf(details.difficulty) }
    var locked by remember(details) { mutableStateOf(details.difficultyLocked) }
    val changes = changesBetween(details, allowCommands, difficulty, locked)
    Panel(Modifier.fillMaxWidth(), title = strings.t("dialogs.saveSettings.title")) {
        if (settingsWritable(details)) {
            AllowCommandsControl(details, allowCommands, canEdit, saving) { allowCommands = it }
            DifficultyControl(details, difficulty, canEdit, saving) { difficulty = it }
            DifficultyLockedControl(details, locked, canEdit, saving) { locked = it }
            SaveActions(canEdit, saving, saveError, saveDone, changes, onDismissNotice, onSave)
        } else {
            Text(
                strings.t("dialogs.saveSettings.notWritable"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AllowCommandsControl(
    details: SaveDetails,
    value: Boolean?,
    canEdit: Boolean,
    saving: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    if (details.allowCommands == null) return
    SettingLine(
        strings.t("dialogs.saveInfo.allowCommands"),
        strings.t("dialogs.saveSettings.allowCommandsHint"),
    ) {
        CompactSwitch(
            checked = value ?: false,
            onCheckedChange = onChange,
            enabled = canEdit && !saving,
        )
    }
}

@Composable
private fun DifficultyControl(
    details: SaveDetails,
    value: Int?,
    canEdit: Boolean,
    saving: Boolean,
    onChange: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    if (details.difficulty == null) return
    Text(strings.t("dialogs.saveSettings.difficulty"), fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DIFFICULTY_LEVELS.forEach { level ->
            FilterChip(
                selected = value == level,
                onClick = { onChange(level) },
                enabled = canEdit && !saving,
                label = { Text(difficultyLabel(level), fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun DifficultyLockedControl(
    details: SaveDetails,
    value: Boolean?,
    canEdit: Boolean,
    saving: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    if (details.difficultyLocked == null) return
    val hardcore = details.hardcore
    SettingLine(
        strings.t("dialogs.saveSettings.lockDifficulty"),
        if (hardcore) {
            strings.t("dialogs.saveSettings.lockHardcoreHint")
        } else {
            strings.t("dialogs.saveSettings.lockHint")
        },
    ) {
        CompactSwitch(
            checked = if (hardcore) true else value ?: false,
            onCheckedChange = onChange,
            enabled = canEdit && !saving && !hardcore,
        )
    }
}

@Composable
private fun SaveActions(
    canEdit: Boolean,
    saving: Boolean,
    saveError: String?,
    saveDone: Boolean,
    changes: SaveSettingsChanges?,
    onDismissNotice: () -> Unit,
    onSave: (SaveSettingsChanges) -> Unit,
) {
    val strings = LocalStrings.current
    Text(
        strings.t("dialogs.saveSettings.backupNote"),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    saveError?.let {
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
    if (saveDone) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.t("dialogs.saveSettings.saved"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            MoliTextButton(onDismissNotice) { Text(strings.t("dialogs.common.gotIt"), fontSize = 11.sp) }
        }
    }
    Button(
        onClick = { changes?.let(onSave) },
        enabled = canEdit && !saving && changes != null,
    ) {
        Text(
            if (saving) strings.t("dialogs.saveSettings.saving") else strings.t("dialogs.saveSettings.save"),
            fontSize = 12.sp,
        )
    }
}

private fun settingsWritable(details: SaveDetails): Boolean =
    details.allowCommands != null || details.difficulty != null || details.difficultyLocked != null

/** 只有与当前值不同、且原字段存在的项才写回；没有变化返回 null。 */
private fun changesBetween(
    details: SaveDetails,
    allowCommands: Boolean?,
    difficulty: Int?,
    locked: Boolean?,
): SaveSettingsChanges? {
    val changes =
        SaveSettingsChanges(
            allowCommands = allowCommands.takeIf { it != details.allowCommands },
            difficulty = difficulty.takeIf { it != details.difficulty },
            difficultyLocked = locked.takeIf { it != details.difficultyLocked },
        )
    return changes.takeUnless { it.isEmpty }
}

@Composable
private fun SettingLine(
    title: String,
    detail: String,
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}
