// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例设置页签的「启动设置」：内存、窗口、全屏与额外启动参数。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceMemoryPolicy
import dev.molifox.core.LauncherSettings
import dev.molifox.core.MIN_INSTANCE_MEMORY_MB
import dev.molifox.core.effectiveGameMemory
import dev.molifox.core.instanceLaunchNumberField
import dev.molifox.core.instanceMemoryLimitMb
import dev.molifox.core.instanceMemoryPolicy
import dev.molifox.core.instanceMemorySliderValue
import dev.molifox.core.normalizeInstanceLaunchNumberInput
import dev.molifox.core.parseInstanceLaunchNumberInput
import dev.molifox.desktop.CompactSettingsSection
import dev.molifox.desktop.CompactSlider
import dev.molifox.desktop.CompactSwitch
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.NumberField
import dev.molifox.desktop.SettingRow
import dev.molifox.desktop.Tag
import dev.molifox.desktop.UiActions

/** 1 GB = 1024 MB，只用于内存标签的换算显示。 */
private const val MEGABYTES_PER_GIGABYTE = 1024f

/** 启动设置卡片：最大内存滑块、窗口宽高、全屏与两段额外参数输入。 */
@Composable
internal fun InstanceLaunchSettingsSection(
    instance: GameInstance,
    actions: UiActions,
    settings: LauncherSettings,
    draft: GameInstance,
    onDraftChange: (GameInstance) -> Unit,
    memoryMb: Int,
    onMemoryMbChange: (Int) -> Unit,
    initialMemory: String,
    onInitialMemoryChange: (String) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    launchError: String?,
) {
    val memoryPolicy = instanceMemoryPolicy(draft)
    val memoryLimit = instanceMemoryLimitMb(actions.controller.platform.totalMemoryMb)
    // 只有手动分配会持久化显式值；继承全局/自动分配一律按「没有显式值」解析。
    val savedMemoryMb = if (memoryPolicy == InstanceMemoryPolicy.Manual) memoryMb else 0
    val memoryAllocation =
        effectiveGameMemory(
            settings,
            draft.copy(memoryMb = savedMemoryMb),
            actions.controller.platform.totalMemoryMb,
            actions.controller.platform.availableMemoryMb,
            actions.controller.platform.architecture,
        )
    val effectiveMemory = memoryAllocation.maximumMb
    val widthValue = parseInstanceLaunchNumberInput(width)
    val heightValue = parseInstanceLaunchNumberInput(height)
    CompactSettingsSection(
        "启动设置",
        "最大内存只在「手动分配」时可调；窗口宽高留空表示跟随全局，参数留空表示不追加。",
    ) {
        val fullscreenNow = resolveFullscreenNow(draft, settings)
        val memoryLabel = memoryPolicyLabel(memoryPolicy, effectiveMemory, memoryMb)
        val memoryHint = memoryPolicyHint(memoryPolicy)
        // 非手动策略滑块只显示解析后的有效值，不接受拖动。
        val sliderValue = sliderMemoryMb(memoryPolicy, memoryMb, effectiveMemory, memoryLimit)
        val memoryRange =
            MIN_INSTANCE_MEMORY_MB.toFloat()..memoryLimit.toFloat()
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompactSlider(
                sliderValue,
                { raw ->
                    val next =
                        instanceMemorySliderValue(
                            raw,
                            actions.controller.platform.totalMemoryMb,
                        )
                    onMemoryMbChange(next)
                    if (narrowsInitialMemory(initialMemory, next)) {
                        onInitialMemoryChange(next.toString())
                    }
                },
                Modifier.weight(1f).semantics {
                    contentDescription = "实例最大内存滑块"
                },
                valueRange = memoryRange,
                enabled = memoryPolicy == InstanceMemoryPolicy.Manual,
            )
            Tag(memoryLabel)
        }
        MutedText(memoryHint)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                value = width,
                label = "窗口宽度（px）",
                onChange = {
                    onWidthChange(normalizeInstanceLaunchNumberInput(it))
                },
                modifier = Modifier.weight(1f),
                placeholder = "—",
                hint =
                    "留空表示跟随全局（当前 ${settings.width} px）",
                onFocusChange = { focused ->
                    windowFieldFallback(focused, widthValue, instance.width)?.let(onWidthChange)
                },
            )
            NumberField(
                value = height,
                label = "窗口高度（px）",
                onChange = {
                    onHeightChange(normalizeInstanceLaunchNumberInput(it))
                },
                modifier = Modifier.weight(1f),
                placeholder = "—",
                hint =
                    "留空表示跟随全局（当前 ${settings.height} px）",
                onFocusChange = { focused ->
                    windowFieldFallback(focused, heightValue, instance.height)?.let(onHeightChange)
                },
            )
        }
        SettingRow("全屏", "关闭时按窗口尺寸显示") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (draft.windowMode != "inherit") {
                    TextButton({
                        onDraftChange(draft.copy(windowMode = "inherit"))
                    }) {
                        Text("跟随全局", fontSize = 11.sp)
                    }
                }
                CompactSwitch(
                    checked = fullscreenNow,
                    onCheckedChange = { checked ->
                        onDraftChange(
                            draft.copy(windowMode = windowModeAfterToggle(checked)),
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            draft.extraJvmArgs,
            { onDraftChange(draft.copy(extraJvmArgs = it.take(4096))) },
            Modifier.fillMaxWidth().onFocusChanged { focus ->
                // 非法 JVM 参数不写盘；离开输入框时回退到上一次有效值。
                argsRevertValue(
                    focus.isFocused,
                    instance.extraJvmArgs,
                    extraArgumentError(draft.extraJvmArgs, ""),
                )?.let { original ->
                    onDraftChange(draft.copy(extraJvmArgs = original))
                }
            },
            label = { Text("额外 JVM 参数（留空跟随全局）") },
            placeholder = {
                Text(
                    "-XX:+UseG1GC",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            },
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
            minLines = 2,
            maxLines = 4,
        )
        OutlinedTextField(
            draft.extraGameArgs,
            { onDraftChange(draft.copy(extraGameArgs = it.take(4096))) },
            Modifier.fillMaxWidth().onFocusChanged { focus ->
                // 非法游戏参数不写盘；离开输入框时回退到上一次有效值。
                argsRevertValue(
                    focus.isFocused,
                    instance.extraGameArgs,
                    extraArgumentError("", draft.extraGameArgs),
                )?.let { original ->
                    onDraftChange(draft.copy(extraGameArgs = original))
                }
            },
            label = { Text("额外游戏参数（追加在标准参数之后）") },
            placeholder = {
                Text(
                    "--skipMultiplayerWarning",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            },
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
            minLines = 2,
            maxLines = 4,
        )
        launchError?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 「跟随全局」时按全局窗口模式与全屏开关解析当前是否全屏。 */
private fun resolveFullscreenNow(
    draft: GameInstance,
    settings: LauncherSettings,
): Boolean =
    when (draft.windowMode) {
        "fullscreen" -> true
        "windowed" -> false
        else ->
            when (settings.gameWindowMode) {
                "fullscreen" -> true
                "launcher" -> false
                else -> settings.fullscreen
            }
    }

/** 内存策略标签：手动分配同时给出 GB 换算。 */
private fun memoryPolicyLabel(
    policy: InstanceMemoryPolicy,
    effectiveMemory: Int,
    memoryMb: Int,
): String =
    when (policy) {
        InstanceMemoryPolicy.Inherit ->
            "跟随全局（当前 $effectiveMemory MB）"
        InstanceMemoryPolicy.Automatic ->
            "自动分配（当前 $effectiveMemory MB）"
        InstanceMemoryPolicy.Manual ->
            "$memoryMb MB（%.1f GB）".format(memoryMb / MEGABYTES_PER_GIGABYTE)
    }

/** 内存策略说明：解释滑块为什么可调或只读。 */
private fun memoryPolicyHint(policy: InstanceMemoryPolicy): String =
    when (policy) {
        InstanceMemoryPolicy.Inherit ->
            "当前继承全局内存策略；切换到「手动分配」后才能调整。"
        InstanceMemoryPolicy.Automatic ->
            "启动时根据系统可用内存分配；切回手动分配时按全局最大值重新设置。"
        InstanceMemoryPolicy.Manual ->
            "拖动滑块按 256 MB 步进；初始内存会自动收窄到最大值以内。"
    }

/** 滑块显示值：手动分配取实例值，其它策略显示解析后的有效值。 */
private fun sliderMemoryMb(
    policy: InstanceMemoryPolicy,
    memoryMb: Int,
    effectiveMemory: Int,
    memoryLimit: Int,
): Float =
    if (policy == InstanceMemoryPolicy.Manual) {
        memoryMb
            .coerceIn(
                MIN_INSTANCE_MEMORY_MB,
                memoryLimit,
            ).toFloat()
    } else {
        effectiveMemory
            .coerceIn(MIN_INSTANCE_MEMORY_MB, memoryLimit)
            .toFloat()
    }

/** 初始内存比新上限大时一起收窄，避免出现「初始值大于最大值」。 */
private fun narrowsInitialMemory(
    initialMemory: String,
    next: Int,
): Boolean = (initialMemory.toIntOrNull() ?: 0) > next

/** 窗口宽高失焦校验：非法时回退到实例上一次有效值，合法时返回 null。 */
private fun windowFieldFallback(
    focused: Boolean,
    parsed: Int?,
    current: Int,
): String? =
    if (!focused && invalidWindowField(parsed)) {
        instanceLaunchNumberField(current)
    } else {
        null
    }

/** 额外参数失焦校验：非法时回退到实例上一次有效值，合法时返回 null。 */
private fun argsRevertValue(
    focused: Boolean,
    original: String,
    error: String?,
): String? =
    if (!focused && error != null) {
        original
    } else {
        null
    }

/** 全屏开关对应的窗口模式。 */
private fun windowModeAfterToggle(checked: Boolean): String =
    if (checked) {
        "fullscreen"
    } else {
        "windowed"
    }
