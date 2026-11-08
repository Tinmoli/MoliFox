// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的「版本与加载器」卡片：游戏版本升级目标与加载器版本。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
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
import dev.molifox.core.GameInstance
import dev.molifox.core.LoaderVersion
import dev.molifox.core.ReleaseVersion
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MoliTextButton
import dev.molifox.desktop.SettingRow
import dev.molifox.desktop.SettingsSection
import dev.molifox.desktop.SimpleDropdown

/** 版本与加载器卡片：加载中/失败的占位行 + 两个下拉。 */
@Composable
internal fun ModifyVersionsCard(
    instance: GameInstance,
    upgradeMode: Boolean,
    gameVersions: List<ReleaseVersion>,
    supportedGameVersions: Set<String>?,
    pickedGameVersion: String?,
    onPickGameVersion: (String?) -> Unit,
    gameVersionLoading: Boolean,
    gameVersionError: String?,
    onGameVersionRetry: () -> Unit,
    loaderOptions: List<LoaderVersion>,
    selectedLoader: String,
    onPickLoader: (String) -> Unit,
    loaderLoading: Boolean,
    loaderError: String?,
    onLoaderRetry: () -> Unit,
    loaderListEmpty: Boolean,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("pages.instance.modify.versions.title"),
        action = {
            if (upgradeMode) {
                ModifyStateChip(strings.t("pages.instance.modify.badge.newInstance"), warn = false)
            }
        },
    ) {
        when {
            gameVersionLoading || loaderLoading ->
                ModifyLoadingRow(strings.t("pages.instance.install.loadingVersions", instance.loader.title))

            gameVersionError != null -> ErrorRow(gameVersionError.orEmpty(), onGameVersionRetry)

            loaderError != null -> ErrorRow(loaderError.orEmpty(), onLoaderRetry)

            else -> {
                GameVersionRow(instance, gameVersions, supportedGameVersions, pickedGameVersion, onPickGameVersion)
                if (loaderListEmpty && upgradeMode) {
                    Text(
                        strings.t("pages.instance.install.noVersions", instance.loader.title),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LoaderVersionRow(loaderOptions, selectedLoader, onPickLoader)
            }
        }
    }
}

/** 清单加载中的占位行：进度条 + 文案。 */
@Composable
internal fun ModifyLoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 游戏版本行：目标版本下拉（首项就是当前版本号本身）+ 正式版/快照版筛选下拉。 */
@Composable
private fun GameVersionRow(
    instance: GameInstance,
    versions: List<ReleaseVersion>,
    supported: Set<String>?,
    picked: String?,
    onSelect: (String?) -> Unit,
) {
    val strings = LocalStrings.current
    var releaseOnly by remember { mutableStateOf(true) }
    val options =
        remember(versions, instance.gameVersion, supported, releaseOnly) {
            val currentIndex = versions.indexOfFirst { it.id == instance.gameVersion }
            listOf(instance.gameVersion to instance.gameVersion) +
                versions.mapIndexedNotNull { index, version ->
                    when {
                        version.id == instance.gameVersion -> null
                        currentIndex != -1 && index >= currentIndex -> null
                        supported != null && version.id !in supported -> null
                        version.type != (if (releaseOnly) "release" else "snapshot") -> null
                        else -> version.id to version.id
                    }
                }
        }
    SettingRow(
        strings.t("pages.instance.modify.gameVersion"),
        strings.t("pages.instance.modify.gameVersion.detail", instance.gameVersion),
    ) {
        // 下拉必须限宽：SimpleDropdown 内部 fillMaxWidth，不限宽会把行标题挤成一字一行。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(220.dp)) {
                SimpleDropdown(
                    value = picked ?: instance.gameVersion,
                    values = options,
                    onSelect = { value -> onSelect(value.takeIf { it != instance.gameVersion }) },
                    label = strings.t("pages.instance.modify.gameVersion"),
                )
            }
            Box(Modifier.width(110.dp)) {
                SimpleDropdown(
                    value = "release".takeIf { releaseOnly } ?: "snapshot",
                    values =
                        listOf(
                            "release" to strings.t("pages.instance.modify.gameVersion.filter.release"),
                            "snapshot" to strings.t("pages.instance.modify.gameVersion.filter.snapshot"),
                        ),
                    onSelect = { releaseOnly = it == "release" },
                    label = strings.t("pages.instance.modify.gameVersion.filter"),
                )
            }
        }
    }
}

/** 加载器版本下拉：跟随目标游戏版本的候选列表，可升级也可以回退。 */
@Composable
private fun LoaderVersionRow(
    options: List<LoaderVersion>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val strings = LocalStrings.current
    SettingRow(
        strings.t("pages.instance.modify.loaderVersion"),
        strings.t("pages.instance.modify.loaderVersion.detail"),
    ) {
        Box(Modifier.width(180.dp)) {
            SimpleDropdown(
                value = selected,
                values = options.map { it.version to it.version },
                onSelect = onPick,
                label = strings.t("pages.instance.modify.loaderVersion"),
            )
        }
    }
}

/** 可重试的错误行：文本 + 重试按钮。 */
@Composable
private fun ErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )
        MoliTextButton(onRetry) { Text(strings.t("pages.instance.install.retry"), fontSize = 12.sp) }
    }
}
