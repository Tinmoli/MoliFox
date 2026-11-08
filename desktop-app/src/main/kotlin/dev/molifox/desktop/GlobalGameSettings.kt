// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将游戏启动选项集中在同一分组，避免与启动器外观设置混杂。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherSettings
import dev.molifox.core.LauncherState
import dev.molifox.core.ProcessPriority
import dev.molifox.core.automaticMemoryMb
import kotlinx.coroutines.delay

private const val GLOBAL_MEMORY_STEP_MB = 256
private const val GLOBAL_MINIMUM_MEMORY_MB = 512

/** 全局游戏设置：内存、游戏窗口、高级参数、游戏目录与版本隔离。 */
@Composable
internal fun gameSettingCards(
    state: LauncherState,
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
    maximum: Int,
    advancedRevision: Int,
    advancedValid: Boolean,
    onAdvancedValid: (Boolean) -> Unit,
): List<SettingCard> {
    val strings = LocalStrings.current
    val cards = mutableListOf<SettingCard>()
    // 高级参数草稿：编辑器每敲一个字符就回调一次，停手 300ms 再写设置；
    // 还原默认（advancedRevision 变化）时按新的已保存值重建，编辑器内部文本一并重建。
    var advancedDraft by remember(advancedRevision) { mutableStateOf(settings.advancedLaunch) }
    val pendingAdvanced by rememberUpdatedState(advancedDraft)
    val savedAdvanced by rememberUpdatedState(settings.advancedLaunch)
    LaunchedEffect(advancedDraft) {
        val pending = advancedDraft
        if (pending == settings.advancedLaunch) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        writer.commit { it.copy(advancedLaunch = pending) }
    }
    // 「启动后最小化」也会写同一个字段：已保存值被其它入口改动时把草稿同步过来，
    // 否则旧草稿会在下一次编辑时把它覆盖回去。
    LaunchedEffect(settings.advancedLaunch) {
        if (advancedDraft != settings.advancedLaunch) advancedDraft = settings.advancedLaunch
    }
    // 离开设置页时补写还没到 300ms 的编辑，最后敲的几个字符不会丢。
    DisposableEffect(Unit) {
        onDispose {
            if (pendingAdvanced != savedAdvanced) {
                runCatching { applySetting(actions) { it.copy(advancedLaunch = pendingAdvanced) } }
            }
        }
    }
    cards.add(
        SettingCard(
            strings.t("settings.game.memory.title"),
            strings.t("settings.game.memory.detail"),
            listOf(
                settingRow(
                    strings.t("settings.game.memory.auto.title"),
                    strings.t("settings.game.memory.auto.detail"),
                ) {
                    CompactSwitch(
                        settings.autoMemory,
                        { enabled -> writer.commit { it.copy(autoMemory = enabled) } },
                        Modifier.semantics {
                            contentDescription = strings.t("settings.game.memory.auto.switch")
                        },
                    )
                },
                settingField(
                    strings.t("settings.game.memory.max.title"),
                    strings.t(
                        "settings.game.memory.max.detail",
                        actions.controller.platform.totalMemoryMb / 1024,
                    ),
                ) {
                    if (settings.autoMemory) {
                        val estimate =
                            automaticMemoryMb(
                                actions.controller.platform.totalMemoryMb,
                                actions.controller.platform.availableMemoryMb,
                            )
                        MutedText(strings.t("settings.game.memory.estimate", estimate))
                    }
                    // 拖动只改本地值，停手 300ms 才写设置；初始内存跟着最大值一起收窄。
                    val memory =
                        rememberSliderDraft(settings.memoryMb.toFloat()) { dragged ->
                            val value = steppedMemory(dragged, maximum)
                            writer.commit {
                                it.copy(
                                    memoryMb = value,
                                    initialMemoryMb = minOf(it.initialMemoryMb, value),
                                )
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompactSlider(
                            memory.floatValue,
                            { memory.floatValue = steppedMemory(it, maximum).toFloat() },
                            Modifier.weight(1f).semantics {
                                contentDescription = strings.t("settings.game.memory.slider")
                            },
                            valueRange = 512f..maximum.toFloat(),
                            enabled = !settings.autoMemory,
                        )
                        Tag(gigabytesFromMebibytes(memory.floatValue))
                    }
                    MutedText(strings.t("settings.game.memory.hint"))
                },
            ),
        ),
    )
    cards.add(
        SettingCard(
            strings.t("settings.game.window.title"),
            strings.t("settings.game.window.detail"),
            listOf(
                settingRow(
                    strings.t("settings.game.window.fullscreen.title"),
                    strings.t("settings.game.window.fullscreen.detail"),
                ) {
                    CompactSwitch(
                        settings.fullscreen,
                        { value -> writer.commit { it.copy(fullscreen = value) } },
                    )
                },
                settingField(
                    strings.t("settings.game.window.size.title"),
                    if (settings.fullscreen) {
                        strings.t("settings.game.window.size.fullscreen")
                    } else {
                        strings.t("settings.game.window.size.range")
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveNumberField(
                            settings.width,
                            strings.t("settings.game.window.width"),
                            640..7680,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(width = value) }
                        }
                        LiveNumberField(
                            settings.height,
                            strings.t("settings.game.window.height"),
                            480..4320,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(height = value) }
                        }
                    }
                },
                settingRow(
                    strings.t("settings.game.window.minimize.title"),
                    strings.t("settings.game.window.minimize.detail"),
                ) {
                    CompactSwitch(
                        settings.advancedLaunch.launcherVisibility == "minimize",
                        { value ->
                            writer.commit {
                                it.copy(
                                    advancedLaunch =
                                        it.advancedLaunch.copy(
                                            launcherVisibility = if (value) "minimize" else "keep",
                                        ),
                                )
                            }
                        },
                    )
                },
                settingRow(
                    strings.t("settings.game.window.priority.title"),
                    strings.t("settings.game.window.priority.detail"),
                ) {
                    Box(Modifier.width(120.dp)) {
                        SimpleDropdown(
                            settings.processPriority.name,
                            ProcessPriority.entries.map { it.name to it.title },
                            { value ->
                                ProcessPriority.entries.find { it.name == value }?.let { priority ->
                                    writer.commit { it.copy(processPriority = priority) }
                                }
                            },
                            enabled = actions.controller.platform.osName == "windows",
                        )
                    }
                },
            ),
        ),
    )
    // 显隐读已保存设置：勾选「显示高级参数」后立即出现（该开关本身也是即时保存）。
    if (settings.pages.showAdvancedLaunchOptions) {
        cards.add(
            SettingCard(
                strings.t("settings.game.advanced.title"),
                strings.t("settings.game.advanced.detail"),
                listOf(
                    settingControl(strings.t("settings.game.advanced.jvm")) {
                        SettingTextArea(
                            settings.extraJvmArgs,
                            { value -> applySetting(actions) { it.copy(extraJvmArgs = value) } },
                            strings.t("settings.game.advanced.jvm"),
                            strings.t("settings.game.advanced.jvm.hint"),
                        )
                    },
                    settingControl(strings.t("settings.game.advanced.editor")) {
                        key(advancedRevision) {
                            AdvancedLaunchEditor(
                                advancedDraft,
                                actions.controller.platform.osName,
                                { value -> advancedDraft = value },
                                onAdvancedValid,
                            )
                        }
                        if (!advancedValid) {
                            Text(
                                strings.t("settings.game.advanced.invalid"),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                        }
                    },
                    settingControl(strings.t("settings.game.advanced.preview")) {
                        val launcherBusy = rememberLauncherBusy(actions)
                        SecondaryButton(
                            {
                                state.data.selectedInstanceId?.let {
                                    actions.previewLaunch(it)
                                }
                            },
                            enabled =
                                state.data.selectedInstanceId != null && !launcherBusy,
                        ) {
                            Text(strings.t("settings.game.advanced.previewAction"))
                        }
                    },
                ),
            ),
        )
    }
    cards.add(
        SettingCard(
            strings.t("settings.game.directory.title"),
            strings.t("settings.game.directory.detail"),
            listOf(
                settingControl(strings.t("settings.game.directory.current")) {
                    GameDirectoryControls(state, actions)
                },
            ),
        ),
    )
    cards.add(
        SettingCard(
            strings.t("settings.game.isolation.title"),
            "",
            listOf(
                settingRow(
                    strings.t("settings.game.isolation.enable.title"),
                    strings.t("settings.game.isolation.enable.detail"),
                ) {
                    CompactSwitch(
                        settings.isolateVersions,
                        { value -> writer.commit { it.copy(isolateVersions = value) } },
                    )
                },
                settingNote(strings.t("settings.game.isolation.note")),
            ),
        ),
    )
    return cards
}

/** 内存按 256 MB 步进并限制在有效范围内：滑块与数字输入共用。 */
internal fun steppedMemory(
    value: Float,
    maximum: Int,
): Int =
    (value.toInt() / GLOBAL_MEMORY_STEP_MB * GLOBAL_MEMORY_STEP_MB)
        .coerceIn(GLOBAL_MINIMUM_MEMORY_MB, maximum)
