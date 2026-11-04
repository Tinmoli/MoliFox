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
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
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
import dev.molifox.desktop.LocalStrings
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
    val strings = LocalStrings.current
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
        InstanceNameSection(
            instance = instance,
            actions = actions,
            draft = draft,
            onNameChange = onNameChange,
            onCommitName = onCommitName,
            nameHint = nameError ?: nameNotice,
            instanceBusy = instanceBusy,
            onEditIcon = onEditIcon,
        )
        InstanceJavaMemorySection(
            actions = actions,
            settings = settings,
            draft = draft,
            memoryPolicy = memoryPolicy,
            memoryMb = memoryMb,
            javaRuntimes = javaRuntimes,
            javaDetecting = javaDetecting,
            javaSaveError = javaSaveError,
            onDraftChange = onDraftChange,
            onMemoryMbChange = onMemoryMbChange,
            onOpenJavaPicker = onOpenJavaPicker,
        )
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
        CompactSettingsSection(strings.t("instA1.settings.basicInfo")) {
            OutlinedTextField(
                draft.notes,
                { onDraftChange(draft.copy(notes = it.take(2000))) },
                Modifier.fillMaxWidth(),
                label = { Text(strings.t("instA1.settings.notes")) },
                minLines = 2,
                maxLines = 4,
            )
        }
        InstanceIsolationSection(actions, settings, draft, onDraftChange)
        InstanceVersionSection(instance, actions)
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
            Text(strings.t("instA1.settings.resetLaunch"), fontSize = 12.sp)
        }
        InstanceSettingsProblem(launchError, saveError, advancedValid)
        Spacer(Modifier.height(6.dp))
    }
}

/** 名称与图标：图标在左、名称在右，与其余设置卡同款排版。 */
@Composable
private fun InstanceNameSection(
    instance: GameInstance,
    actions: UiActions,
    draft: GameInstance,
    onNameChange: (String) -> Unit,
    onCommitName: () -> Unit,
    nameHint: String?,
    instanceBusy: Boolean,
    onEditIcon: () -> Unit,
) {
    val strings = LocalStrings.current
    CompactSettingsSection(
        strings.t("instA1.settings.nameAndIcon"),
        strings.t("instA1.settings.nameAndIcon.detail"),
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
                    label = strings.t("instA1.settings.nameLabel"),
                    onFocusChange = { focused ->
                        if (!focused) onCommitName()
                    },
                    imeAction = ImeAction.Done,
                    onImeAction = onCommitName,
                    onSubmit = onCommitName,
                )
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
                    Text(strings.t("instA1.settings.changeIcon"), fontSize = 12.sp)
                }
            }
        }
    }
}

/** Java 与内存：继承时右侧显示全局实际解析到的运行时。 */
@Composable
private fun InstanceJavaMemorySection(
    actions: UiActions,
    settings: LauncherSettings,
    draft: GameInstance,
    memoryPolicy: InstanceMemoryPolicy,
    memoryMb: Int,
    javaRuntimes: List<JavaRuntime>,
    javaDetecting: Boolean,
    javaSaveError: String?,
    onDraftChange: (GameInstance) -> Unit,
    onMemoryMbChange: (Int) -> Unit,
    onOpenJavaPicker: () -> Unit,
) {
    val strings = LocalStrings.current
    CompactSettingsSection(
        strings.t("instA1.settings.javaAndMemory"),
        strings.t("instA1.settings.javaAndMemory.detail"),
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
                "inherit" to strings.t("instA1.settings.memoryPolicy.inherit"),
                "automatic" to strings.t("instA1.settings.memoryPolicy.automatic"),
                "manual" to strings.t("instA1.settings.memoryPolicy.manual"),
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
            MutedText(strings.t("pages.instance.launch.memoryAutoHint"))
        }
    }
}

/** 目录与隔离：目录名就是实例名；隔离来源与覆盖方式都在这里说明。 */
@Composable
private fun InstanceIsolationSection(
    actions: UiActions,
    settings: LauncherSettings,
    draft: GameInstance,
    onDraftChange: (GameInstance) -> Unit,
) {
    val strings = LocalStrings.current
    CompactSettingsSection(
        strings.t("instA1.settings.foldersAndIsolation"),
        strings.t("instA1.settings.foldersAndIsolation.detail"),
    ) {
        val isolationSource =
            actions.controller.store.isolationSource(draft)
        val isolationEvidence =
            actions.controller.store.isolationEvidence(draft)
        SettingRow(
            strings.t("settings.game.isolation.title"),
            when (isolationSource) {
                IsolationSource.Inferred ->
                    strings.t(
                        "instA1.settings.isolation.inferred",
                        actions.controller.store.isolationTitle(draft),
                        isolationEvidence ?: strings.t("instA1.settings.isolation.gameData"),
                    )
                IsolationSource.Explicit ->
                    when (draft.isolation) {
                        VersionIsolation.Enabled ->
                            strings.t("instA1.settings.isolation.enabled")
                        VersionIsolation.Disabled ->
                            strings.t("instA1.settings.isolation.disabled")
                        VersionIsolation.Inherit -> ""
                    }
                IsolationSource.GlobalDefault ->
                    strings.t(
                        "instA1.settings.isolation.globalDefault",
                        if (settings.isolateVersions) {
                            strings.t("core.versionIsolation.enabled")
                        } else {
                            strings.t("core.versionIsolation.disabled")
                        },
                    )
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
                                strings.t("instA1.settings.isolation.inheritInferred")
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
            strings.t("instA1.settings.actualGameFolder"),
            runCatching {
                actions.controller.store
                    .gamePath(draft)
                    .toString()
            }.getOrDefault(strings.t("instA1.settings.folderUnavailable")),
        ) {}
    }
}

/** 版本信息：Minecraft 版本、包来源、创建时间与打开目录。 */
@Composable
private fun InstanceVersionSection(
    instance: GameInstance,
    actions: UiActions,
) {
    val strings = LocalStrings.current
    CompactSettingsSection(strings.t("instA1.settings.versionInfo")) {
        SettingRow("Minecraft", instance.gameVersion) {
            Tag(instance.loader.title)
        }
        SettingRow(strings.t("instA1.settings.packSource"), packSourceOf(instance)) {
            Text(instance.versionId, fontSize = 12.sp)
        }
        Text(
            strings.t(
                "instA1.settings.createdAtPlayTime",
                instance.createdAt.take(10),
                instance.playTimeSeconds / 60,
            ),
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
            Text(strings.t("pages.instances.menu.openFolder"), fontSize = 12.sp)
        }
    }
}

/** 启动设置的问题：内联校验错误优先，其次保存错误，最后高级参数校验。 */
@Composable
private fun InstanceSettingsProblem(
    launchError: String?,
    saveError: String?,
    advancedValid: Boolean,
) {
    val strings = LocalStrings.current
    val problem =
        launchError
            ?: saveError
            ?: if (!advancedValid) {
                strings.t("instA1.settings.advancedInvalid")
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
}
