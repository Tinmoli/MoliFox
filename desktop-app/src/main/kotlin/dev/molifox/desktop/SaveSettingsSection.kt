// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 存档详情的设置区：允许作弊、难度与锁定难度。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

internal val DIFFICULTY_LABELS =
    listOf(
        0 to "和平",
        1 to "简单",
        2 to "普通",
        3 to "困难",
    )

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
    var allowCommands by remember(details) { mutableStateOf(details.allowCommands) }
    var difficulty by remember(details) { mutableStateOf(details.difficulty) }
    var locked by remember(details) { mutableStateOf(details.difficultyLocked) }
    val changes = changesBetween(details, allowCommands, difficulty, locked)
    Panel(Modifier.fillMaxWidth(), title = "存档设置") {
        if (settingsWritable(details)) {
            AllowCommandsControl(details, allowCommands, canEdit, saving) { allowCommands = it }
            DifficultyControl(details, difficulty, canEdit, saving) { difficulty = it }
            DifficultyLockedControl(details, locked, canEdit, saving) { locked = it }
            SaveActions(canEdit, saving, saveError, saveDone, changes, onDismissNotice, onSave)
        } else {
            Text(
                "这个世界的 level.dat 没有可修改的难度或作弊字段" +
                    "（旧版本或模组改过结构），只能查看。",
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
    if (details.allowCommands == null) return
    SettingLine(
        "允许作弊",
        "写入 level.dat 的 allowCommands；开启后进入世界可以输入命令。",
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
    if (details.difficulty == null) return
    Text("难度", fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DIFFICULTY_LABELS.forEach { (level, label) ->
            FilterChip(
                selected = value == level,
                onClick = { onChange(level) },
                enabled = canEdit && !saving,
                label = { Text(label, fontSize = 11.sp) },
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
    if (details.difficultyLocked == null) return
    val hardcore = details.hardcore
    SettingLine(
        "锁定难度",
        if (hardcore) {
            "硬核世界的难度固定为困难并锁定，不能在这里修改。"
        } else {
            "锁定后游戏内不能再改难度；对应 level.dat 的 DifficultyLocked。"
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
    Text(
        "保存前会把原 level.dat 备份到 MoliFox/backups/level-dat/；写入用临时文件原子替换。",
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
            Text("已保存", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            TextButton(onDismissNotice) { Text("知道了", fontSize = 11.sp) }
        }
    }
    Button(
        onClick = { changes?.let(onSave) },
        enabled = canEdit && !saving && changes != null,
    ) {
        Text(if (saving) "保存中…" else "保存设置", fontSize = 12.sp)
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
