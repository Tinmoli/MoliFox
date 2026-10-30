// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将游戏启动选项集中在同一分组，避免与启动器外观设置混杂。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.add
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
            "内存分配",
            "每个实例都可以覆盖这里的设置。",
            listOf(
                settingRow("自动分配内存", "启动时根据剩余物理内存重新计算，保留系统与游戏本地内存空间。") {
                    CompactSwitch(
                        settings.autoMemory,
                        { enabled -> writer.commit { it.copy(autoMemory = enabled) } },
                        Modifier.semantics { contentDescription = "自动分配内存开关" },
                    )
                },
                settingField(
                    "最大内存",
                    "物理内存总量约 " +
                        actions.controller.platform.totalMemoryMb / 1024 +
                        " GB；拖动滑块按 256 MB 步进",
                ) {
                    if (settings.autoMemory) {
                        val estimate =
                            automaticMemoryMb(
                                actions.controller.platform.totalMemoryMb,
                                actions.controller.platform.availableMemoryMb,
                            )
                        MutedText("当前预计分配 $estimate MB；实际值在启动游戏时计算，初始内存会限制在最大值以内。")
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
                            Modifier.weight(1f).semantics { contentDescription = "全局内存滑块" },
                            valueRange = 512f..maximum.toFloat(),
                            enabled = !settings.autoMemory,
                        )
                        Tag("%.1f GB".format(memory.floatValue / 1024f))
                    }
                    MutedText("原版通常从 2–4 GB 开始；大型整合包按需要调整。初始内存不能超过最大内存。")
                },
            ),
        ),
    )
    cards.add(
        SettingCard(
            "游戏窗口",
            "「外观 → 默认游戏窗口」选择自定义分辨率时生效。",
            listOf(
                settingRow("全屏启动", "关闭时使用下方的窗口分辨率") {
                    CompactSwitch(
                        settings.fullscreen,
                        { value -> writer.commit { it.copy(fullscreen = value) } },
                    )
                },
                settingField(
                    "窗口分辨率",
                    if (settings.fullscreen) "全屏启动时忽略这里的宽高。" else "范围 640–7680 × 480–4320 像素。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveNumberField(
                            settings.width,
                            "窗口宽度（px）",
                            640..7680,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(width = value) }
                        }
                        LiveNumberField(
                            settings.height,
                            "窗口高度（px）",
                            480..4320,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(height = value) }
                        }
                    }
                },
                settingRow("启动后最小化", "游戏退出后恢复启动器窗口") {
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
                    "游戏进程优先级",
                    "仅 Windows 生效；优先级越高越不容易被系统降速，超高可能影响其他程序响应。",
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
                "高级参数",
                "通常留空即可，内存请使用上方专用设置。",
                listOf(
                    settingControl("附加 JVM 参数") {
                        SettingTextArea(
                            settings.extraJvmArgs,
                            { value -> applySetting(actions) { it.copy(extraJvmArgs = value) } },
                            "附加 JVM 参数",
                            "每行一个参数，留空使用启动器默认值。",
                        )
                    },
                    settingControl("启动参数编辑器") {
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
                                "启动参数里有尚未保存的无效输入，修正后会自动保存。",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                        }
                    },
                    settingControl("启动参数预览") {
                        SecondaryButton(
                            {
                                state.data.selectedInstanceId?.let {
                                    actions.previewLaunch(it)
                                }
                            },
                            enabled =
                                state.data.selectedInstanceId != null && !actions.controller.busy,
                        ) {
                            Text("查看已保存启动参数")
                        }
                    },
                ),
            ),
        )
    }
    cards.add(
        SettingCard(
            "游戏目录",
            "直接使用已有文件夹，也可以选择空目录作为安装目标。",
            listOf(settingControl("当前游戏目录") { GameDirectoryControls(state, actions) }),
        ),
    )
    cards.add(
        SettingCard(
            "版本隔离",
            "",
            listOf(
                settingRow("启用版本隔离", "默认值：开启时使用游戏目录下 versions/实例名；关闭时共用游戏根目录。") {
                    CompactSwitch(
                        settings.isolateVersions,
                        { value -> writer.commit { it.copy(isolateVersions = value) } },
                    )
                },
                settingNote("这里是全局默认值；实例设置里的「版本隔离」可选继承全局、强制开启或强制共用。切换后不删除或合并原目录里的存档、模组与配置。"),
            ),
        ),
    )
    return cards
}

/** 内存按 256 MB 步进并限制在有效范围内：滑块与数字输入共用。 */
internal fun steppedMemory(
    value: Float,
    maximum: Int,
): Int = (value.toInt() / 256 * 256).coerceIn(512, maximum)
