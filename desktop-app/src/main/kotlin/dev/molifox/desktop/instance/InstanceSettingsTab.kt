// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例设置页签：名称与图标、Java 与内存、基本信息、目录与隔离、版本信息与安装区。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceMemoryPolicy
import dev.molifox.core.IsolationSource
import dev.molifox.core.JavaRuntime
import dev.molifox.core.LauncherSettings
import dev.molifox.core.VersionIsolation
import dev.molifox.core.applyInstanceMemoryPolicy
import dev.molifox.core.instanceMemoryPolicy
import dev.molifox.core.parseInstanceLaunchNumberInput
import dev.molifox.core.validateInstanceDirectoryName
import dev.molifox.core.validateInstanceLaunchOptions
import dev.molifox.desktop.CompactSettingsSection
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.InstanceArtwork
import dev.molifox.desktop.InstanceInstallSections
import dev.molifox.desktop.JavaPickerRow
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.ScrollColumn
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.SettingRow
import dev.molifox.desktop.SimpleDropdown
import dev.molifox.desktop.Tag
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.packSourceOf

/** 设置页签：自动保存的编辑值由页面持有，这里只负责渲染与回写。 */
@Composable
internal fun InstanceSettingsTab(
    instance: GameInstance,
    actions: UiActions,
    settings: LauncherSettings,
    draft: GameInstance,
    onDraftChange: (GameInstance) -> Unit,
    onNameChange: (String) -> Unit,
    onCommitName: () -> Unit,
    nameNotice: String?,
    memoryMb: Int,
    onMemoryMbChange: (Int) -> Unit,
    initialMemory: String,
    onInitialMemoryChange: (String) -> Unit,
    width: String,
    onWidthChange: (String) -> Unit,
    height: String,
    onHeightChange: (String) -> Unit,
    javaRuntimes: List<JavaRuntime>,
    javaDetecting: Boolean,
    javaSaveError: String?,
    onOpenJavaPicker: () -> Unit,
    saveError: String?,
    advancedValid: Boolean,
    onAdvancedValidChange: (Boolean) -> Unit,
    onEditIcon: () -> Unit,
    onResetLaunchSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val instanceBusy = actions.controller.isInstanceBusy(instance.id)
    val memoryPolicy = instanceMemoryPolicy(draft)
    // 只有手动分配会持久化显式值；继承全局/自动分配一律按「没有显式值」解析。
    val savedMemoryMb = if (memoryPolicy == InstanceMemoryPolicy.Manual) memoryMb else 0
    val widthValue = parseInstanceLaunchNumberInput(width)
    val heightValue = parseInstanceLaunchNumberInput(height)
    // 只有真的改名才会移动目录，因此只在名称变化时校验目录名；旧显示名里的特殊字符不影响其它设置。
    val nameError =
        if (draft.name.trim() != instance.name) {
            runCatching { validateInstanceDirectoryName(draft.name.trim()) }
                .exceptionOrNull()
                ?.message
        } else {
            null
        }
    // 实例启动设置与启动路径共用同一套校验；非法值给出内联错误且不会被写入。
    val launchError: String? =
        runCatching {
            validateInstanceLaunchOptions(
                memoryMb = savedMemoryMb,
                width = widthValue ?: -1,
                height = heightValue ?: -1,
                windowMode = draft.windowMode,
                extraJvmArgs = draft.extraJvmArgs,
                extraGameArgs = draft.extraGameArgs,
                totalMemoryMb = actions.controller.platform.totalMemoryMb,
            )
        }.exceptionOrNull()
            ?.message
    ScrollColumn(modifier) {
        // 名称与图标放在设置页最上方：图标在左、名称在右，与其余设置卡同款排版。
        CompactSettingsSection(
            "名称与图标",
            "名称离开输入框时生效并重命名实例目录；图标默认跟随原版或加载器，整合包自带图标优先。",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                InstanceArtwork(instance, actions, Modifier.size(40.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompactTextField(
                        draft.name,
                        onNameChange,
                        Modifier.fillMaxWidth(),
                        label = "实例名称（离开输入框时同时重命名目录）",
                        onFocusChange = { focused ->
                            if (!focused) onCommitName()
                        },
                    )
                    val nameHint = nameError ?: nameNotice
                    if (nameHint != null) {
                        Text(
                            nameHint,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SecondaryButton(
                        { onEditIcon() },
                        enabled = !instanceBusy,
                    ) {
                        Text("更换实例图标…", fontSize = 12.sp)
                    }
                }
            }
        }
        CompactSettingsSection(
            "Java 与内存",
            "实例可单独指定 Java；内存数值与窗口在下方「启动设置」。",
        ) {
            // 紧凑行：继承时右侧显示全局实际解析到的运行时，点击打开抽屉。
            JavaPickerRow(
                value = draft.javaPath,
                runtimes = javaRuntimes,
                inheritedPath = settings.javaPath,
                ignoreCase =
                    actions.controller.platform.osName == "windows",
                detecting = javaDetecting,
                onClick = { onOpenJavaPicker() },
            )
            javaSaveError?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SimpleDropdown(
                when (memoryPolicy) {
                    InstanceMemoryPolicy.Inherit -> "inherit"
                    InstanceMemoryPolicy.Automatic -> "automatic"
                    InstanceMemoryPolicy.Manual -> "manual"
                },
                listOf(
                    "inherit" to "继承全局内存策略",
                    "automatic" to "自动分配",
                    "manual" to "手动分配",
                ),
                { mode ->
                    val next =
                        applyInstanceMemoryPolicy(
                            draft.copy(
                                memoryMb =
                                    if (
                                        memoryPolicy ==
                                        InstanceMemoryPolicy.Manual
                                    ) {
                                        memoryMb
                                    } else {
                                        0
                                    },
                            ),
                            when (mode) {
                                "automatic" ->
                                    InstanceMemoryPolicy.Automatic
                                "manual" -> InstanceMemoryPolicy.Manual
                                else -> InstanceMemoryPolicy.Inherit
                            },
                            settings.memoryMb,
                        )
                    onDraftChange(next)
                    onMemoryMbChange(next.memoryMb)
                },
            )
            if (memoryPolicy == InstanceMemoryPolicy.Automatic) {
                MutedText("启动时根据系统可用内存分配；切回手动分配时按全局最大值重新设置。")
            }
        }
        InstanceLaunchSettingsSection(
            instance = instance,
            actions = actions,
            settings = settings,
            draft = draft,
            onDraftChange = onDraftChange,
            memoryMb = memoryMb,
            onMemoryMbChange = onMemoryMbChange,
            initialMemory = initialMemory,
            onInitialMemoryChange = onInitialMemoryChange,
            width = width,
            onWidthChange = onWidthChange,
            height = height,
            onHeightChange = onHeightChange,
            launchError = launchError,
        )
        CompactSettingsSection("基本信息") {
            OutlinedTextField(
                draft.notes,
                { onDraftChange(draft.copy(notes = it.take(2000))) },
                Modifier.fillMaxWidth(),
                label = { Text("版本备注") },
                minLines = 2,
                maxLines = 4,
            )
        }
        CompactSettingsSection("目录与隔离", "目录名就是实例名；名称离开输入框后自动生效。") {
            val isolationSource =
                actions.controller.store.isolationSource(draft)
            val isolationEvidence =
                actions.controller.store.isolationEvidence(draft)
            SettingRow(
                "版本隔离",
                when (isolationSource) {
                    IsolationSource.Inferred ->
                        actions.controller.store.isolationTitle(draft) +
                            "：目录内有 " +
                            (isolationEvidence ?: "游戏数据") +
                            "，按独立目录处理；选择下拉项可覆盖"
                    IsolationSource.Explicit ->
                        when (draft.isolation) {
                            VersionIsolation.Enabled ->
                                "使用实例自己的目录，存档、Mod 与其他实例互不影响"
                            VersionIsolation.Disabled ->
                                "与其他关闭隔离的实例共用游戏根目录"
                            VersionIsolation.Inherit -> ""
                        }
                    IsolationSource.GlobalDefault ->
                        "跟随全局设置（当前为" +
                            (
                                if (settings.isolateVersions) {
                                    "版本隔离"
                                } else {
                                    "共用游戏目录"
                                }
                            ) +
                            "）"
                },
            ) {
                Box(Modifier.width(190.dp)) {
                    SimpleDropdown(
                        draft.isolation.name,
                        VersionIsolation.entries.map {
                            it.name to
                                if (
                                    it == VersionIsolation.Inherit &&
                                    isolationSource ==
                                    IsolationSource.Inferred
                                ) {
                                    "继承全局设置（自动推断为版本隔离）"
                                } else {
                                    it.title
                                }
                        },
                        { key ->
                            onDraftChange(
                                draft.copy(
                                    isolation =
                                        VersionIsolation.valueOf(
                                            key,
                                        ),
                                ),
                            )
                        },
                    )
                }
            }
            SettingRow(
                "实际游戏目录",
                runCatching {
                    actions.controller.store
                        .gamePath(draft)
                        .toString()
                }.getOrDefault("目录暂不可用"),
            ) {}
        }
        CompactSettingsSection("版本信息") {
            SettingRow("Minecraft", instance.gameVersion) {
                Tag(instance.loader.title)
            }
            SettingRow("包来源", packSourceOf(instance)) {
                Text(instance.versionId, fontSize = 12.sp)
            }
            Text(
                "创建于 " +
                    instance.createdAt.take(10) +
                    " · 游玩 " +
                    instance.playTimeSeconds / 60 +
                    " 分钟",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton({
                actions.run { actions.controller.openFolder(instance.id) }
            }) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    null,
                    Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text("打开游戏目录", fontSize = 12.sp)
            }
        }
        InstanceAdvancedLaunchSection(
            instance = instance,
            actions = actions,
            settings = settings,
            draft = draft,
            onDraftChange = onDraftChange,
            onAdvancedValidChange = onAdvancedValidChange,
        )
        InstanceInstallSections(instance, actions)
        TextButton({
            onResetLaunchSettings()
        }) {
            Text("恢复全局启动设置", fontSize = 12.sp)
        }
        val problem =
            launchError
                ?: saveError
                ?: if (!advancedValid) {
                    "高级启动参数未通过校验。"
                } else {
                    null
                }
        if (problem != null) {
            Text(
                problem,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}
