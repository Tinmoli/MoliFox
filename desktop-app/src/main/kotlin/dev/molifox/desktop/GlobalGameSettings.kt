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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DEFAULT_EXTRA_JVM_ARGS
import dev.molifox.core.GcPreset
import dev.molifox.core.LauncherSettings
import dev.molifox.core.LauncherState
import dev.molifox.core.ProcessPriority
import dev.molifox.core.VersionIsolationPolicy
import dev.molifox.core.effectiveVersionIsolationPolicy
import dev.molifox.core.withVersionIsolationPolicy

private const val GLOBAL_MEMORY_STEP_MB = 256
private const val GLOBAL_MINIMUM_MEMORY_MB = 512

/** 游戏设置：内存、版本隔离、游戏窗口、高级参数与游戏目录。 */
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
    // 「启动后最小化」也会写同一个字段：已保存值被其它入口改动时把草稿同步过来，
    // 否则旧草稿会在下一次编辑时把它覆盖回去。
    LaunchedEffect(settings.advancedLaunch) {
        if (advancedDraft != settings.advancedLaunch) advancedDraft = settings.advancedLaunch
    }
    SaveSettingsDraft(
        advancedDraft,
        settings.advancedLaunch,
        save = { pending -> writer.commit { it.copy(advancedLaunch = pending) } },
        flush = { pending -> runCatching { applySetting(actions) { it.copy(advancedLaunch = pending) } } },
    )
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
                ) {
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
                    MemoryAllocationBar(actions.controller.platform, memory.floatValue.toInt(), settings.autoMemory)
                },
            ),
        ),
    )
    cards.add(
        SettingCard(
            strings.t("settings.game.isolation.title"),
            "",
            listOf(
                settingControl(strings.t("settings.game.isolation.title")) {
                    GlobalIsolationDropdown(settings.effectiveVersionIsolationPolicy) { policy ->
                        writer.commit { it.withVersionIsolationPolicy(policy) }
                    }
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
                    settingControl(strings.t("settings.game.advanced.gc")) {
                        GcPresetRow(
                            GcPreset.from(settings.gcPreset),
                            { preset -> applySetting(actions) { it.copy(gcPreset = preset.value) } },
                        )
                    },
                    settingControl(strings.t("settings.game.advanced.jvm")) {
                        SettingTextArea(
                            settings.extraJvmArgs.ifBlank { DEFAULT_EXTRA_JVM_ARGS },
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
                collapsible = true,
                startExpanded = false,
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
    return cards
}

@Composable
private fun GlobalIsolationDropdown(
    selected: VersionIsolationPolicy,
    onSelect: (VersionIsolationPolicy) -> Unit,
) {
    val strings = LocalStrings.current
    val choices = listOf(VersionIsolationPolicy.Disabled, VersionIsolationPolicy.Modded, VersionIsolationPolicy.All)
    SimpleDropdown(
        selected.name,
        choices.map { it.name to strings.t("settings.game.isolation.policy.${it.name}") },
        { value -> onSelect(VersionIsolationPolicy.valueOf(value)) },
        label = strings.t("settings.game.isolation.title"),
        selectedText = strings.t("settings.game.isolation.policy.${selected.name}"),
        descriptions =
            (choices + selected).associate {
                val key = if (it in choices) it.name else "Legacy"
                it.name to strings.t("settings.game.isolation.help.$key")
            },
    )
}

/** 内存按 256 MB 步进并限制在有效范围内：滑块与数字输入共用。 */
internal fun steppedMemory(
    value: Float,
    maximum: Int,
): Int =
    (value.toInt() / GLOBAL_MEMORY_STEP_MB * GLOBAL_MEMORY_STEP_MB)
        .coerceIn(GLOBAL_MINIMUM_MEMORY_MB, maximum)
