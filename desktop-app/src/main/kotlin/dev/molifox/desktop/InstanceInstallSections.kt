// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例当前安装与加载器维护选项。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.LoaderType
import dev.molifox.core.LoaderVersion
import dev.molifox.core.loaderUpgradeCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal fun packSourceOf(instance: GameInstance): String =
    when {
        instance.packFile.isNotBlank() -> "整合包：" + instance.packFile.substringAfterLast('/')
        instance.externalProfile -> "外部目录导入"
        else -> "启动器安装"
    }

/** 设置页签的「当前安装」与「升级加载器」卡片：只展示当前组件，并提供同加载器的小版本升级。 */
@Composable
internal fun ColumnScope.InstanceInstallSections(
    instance: GameInstance,
    actions: UiActions,
) {
    var versions by remember(instance.id) { mutableStateOf<List<LoaderVersion>>(emptyList()) }
    var loadingVersions by remember(instance.id) { mutableStateOf(false) }
    var versionError by remember(instance.id) { mutableStateOf<String?>(null) }
    var versionRetry by remember(instance.id) { mutableIntStateOf(0) }
    // 外部实例能否合并进原 JSON（继承链缺失等）由核心预检给出具体原因；只读，不改文件。
    var externalBlocker by remember(instance.id) { mutableStateOf<String?>(null) }
    // 只有已安装、非整合包且有加载器的实例才能升级；外部实例额外要求原 JSON 可安全合并。
    val upgradeable =
        instance.installed && instance.packFile.isBlank() && instance.loader != LoaderType.Vanilla
    val canUpgrade = upgradeable && externalBlocker == null
    LaunchedEffect(
        instance.id,
        instance.externalProfile,
        instance.versionId,
        instance.loaderVersion,
    ) {
        externalBlocker =
            if (instance.externalProfile) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        actions.controller.installer.externalLoaderUpgradeBlocker(instance)
                    }.getOrElse { it.message ?: "外部版本配置无法读取" }
                }
            } else {
                null
            }
    }
    LaunchedEffect(
        instance.id,
        instance.loader,
        instance.loaderVersion,
        instance.gameVersion,
        canUpgrade,
        externalBlocker,
        versionRetry,
    ) {
        versions = emptyList()
        versionError = null
        if (!canUpgrade || instance.loaderVersion.isBlank()) return@LaunchedEffect
        loadingVersions = true
        try {
            versions =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.loaderVersions(
                        instance.loader,
                        instance.gameVersion,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            versionError = failure.message
        } finally {
            loadingVersions = false
        }
    }
    val candidates = loaderUpgradeCandidates(instance, versions)
    SettingsSection("当前安装") {
        SettingRow("游戏版本", instance.gameVersion) {
            InstanceArtwork(instance, actions, Modifier.size(24.dp))
        }
        SettingRow(
            "模组加载器",
            if (instance.loader == LoaderType.Vanilla) {
                "原版（未安装加载器）"
            } else {
                instance.loader.title + " " + instance.loaderVersion
            },
        ) {}
        Text(
            "版本 ID：" + instance.versionId,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    actions.run { actions.controller.repair(instance.id) }
                    actions.taskQueued()
                },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("修复游戏", fontSize = 12.sp)
            }
        }
    }
    SettingsSection("升级加载器", "只升级同一加载器的版本；游戏版本、模组与存档保持不变。") {
        when {
            instance.broken && instance.externalProfile ->
                Text(
                    (instance.brokenReason.ifBlank { "外部版本配置无法识别" }) +
                        "。请先在原目录补齐该版本，再进行升级。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.loader == LoaderType.Vanilla ->
                Text(
                    "原版实例没有可升级的加载器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.packFile.isNotBlank() ->
                Text(
                    "整合包的加载器由包清单定义，请通过整合包更新维护。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.externalProfile && externalBlocker != null ->
                Text(
                    externalBlocker ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            !upgradeable ->
                Text(
                    "请先完成游戏安装，再升级加载器。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            instance.loaderVersion.isBlank() ->
                Text(
                    "无法识别当前加载器版本，请先修复游戏。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                SettingRow("当前加载器", instance.loader.title + " " + instance.loaderVersion) {}
                val latest = candidates.firstOrNull()
                when {
                    loadingVersions ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在获取" + instance.loader.title + "版本列表",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    versionError != null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                versionError.orEmpty(),
                                Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton({ versionRetry++ }) { Text("重试", fontSize = 12.sp) }
                        }
                    versions.isEmpty() ->
                        Text(
                            "该游戏版本暂无可用的 " + instance.loader.title + " 版本",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    latest == null ->
                        Text(
                            "已是此游戏版本下最新的 " +
                                instance.loader.title +
                                " " +
                                instance.loaderVersion,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        SettingRow(
                            "可升级至",
                            latest.version + if (latest.stable) "" else "（测试版）",
                        ) {
                            PrimaryButton(
                                "升级",
                                Icons.Outlined.ArrowUpward,
                                !actions.controller.isInstanceBusy(instance.id),
                            ) {
                                actions.run {
                                    actions.controller.upgradeInstanceLoader(
                                        instance.id,
                                        latest.version,
                                    )
                                }
                                actions.taskQueued()
                            }
                        }
                }
            }
        }
    }
}
