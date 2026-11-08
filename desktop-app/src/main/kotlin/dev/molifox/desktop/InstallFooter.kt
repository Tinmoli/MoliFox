// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 安装页底部：禁用原因、安装位置与开始安装按钮的判定。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.InstallRequest
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 底部：禁用原因、安装位置与开始安装按钮。 */
@Composable
internal fun InstallFooter(
    state: LauncherState,
    actions: UiActions,
    loading: Boolean,
    error: String?,
    selected: String,
    loaderLoading: Boolean,
    loader: LoaderType,
    loaderVersion: String,
    name: String,
    onInstalled: () -> Unit,
) {
    val strings = LocalStrings.current
    // busy 是快照值：挂在 state 上重算，任务开始/结束时禁用原因与按钮才会刷新。
    val launcherBusy = rememberLauncherBusy(actions)
    val blocked =
        installBlockedReason(
            launcherBusy,
            loading,
            error,
            selected,
            loaderLoading,
            loader,
            loaderVersion,
        )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                selected.ifBlank { strings.t("deskA.installFooter.noVersion") } + "  /  " + loader.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // 按钮灰掉时把缺项直接写在旁边，避免“点了没反应”的猜谜。
                blocked ?: strings.t("deskA.installFooter.readyHint"),
                fontSize = 11.sp,
                color =
                    if (blocked != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                strings.t(
                    "deskA.installFooter.installTo",
                    state.data.gameDirectories
                        .find { it.id == state.data.activeGameDirectoryId }
                        ?.path
                        ?: state.data.settings.gameRoot,
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        InstallLaunchButton(
            actions = actions,
            loading = loading,
            error = error,
            selected = selected,
            loaderLoading = loaderLoading,
            loader = loader,
            loaderVersion = loaderVersion,
            name = name,
            onInstalled = onInstalled,
        )
    }
}

/** 开始安装：先提交安装任务，再回到实例列表。 */
@Composable
private fun InstallLaunchButton(
    actions: UiActions,
    loading: Boolean,
    error: String?,
    selected: String,
    loaderLoading: Boolean,
    loader: LoaderType,
    loaderVersion: String,
    name: String,
    onInstalled: () -> Unit,
) {
    val strings = LocalStrings.current
    val launcherBusy = rememberLauncherBusy(actions)
    PrimaryButton(
        strings.t("dialogs.content.install.start"),
        Icons.Outlined.Download,
        installAllowed(launcherBusy, loading, error, selected, loaderLoading, loader, loaderVersion),
    ) {
        actions.run {
            actions.controller.install(
                InstallRequest(
                    installInstanceName(name, selected, loader),
                    selected,
                    loader,
                    loaderVersion,
                ),
            )
            withContext(Dispatchers.Main) { onInstalled() }
        }
    }
}

/** 实例名留空时按「版本 + 加载器」生成，与界面提示一致。 */
private fun installInstanceName(
    name: String,
    selected: String,
    loader: LoaderType,
): String = name.ifBlank { selected + loaderSuffix(loader) }

/** 开始安装按钮是否可用：与 [installBlockedReason] 的判定保持一致。 */
private fun installAllowed(
    busy: Boolean,
    loading: Boolean,
    error: String?,
    selected: String,
    loaderLoading: Boolean,
    loader: LoaderType,
    loaderVersion: String,
): Boolean =
    !busy &&
        !loading &&
        error == null &&
        selected.isNotBlank() &&
        !loaderLoading &&
        (loader == LoaderType.Vanilla || loaderVersion.isNotBlank())

/** 开始安装按钮的禁用原因；返回 null 表示当前可以安装。 */
private fun installBlockedReason(
    busy: Boolean,
    loading: Boolean,
    error: String?,
    selected: String,
    loaderLoading: Boolean,
    loader: LoaderType,
    loaderVersion: String,
): String? =
    when {
        busy -> tr("deskA.installFooter.blocked.busy")
        loading -> tr("deskA.installFooter.blocked.loadingVersions")
        error != null -> tr("deskA.installFooter.blocked.versionListUnavailable")
        selected.isBlank() -> tr("deskA.installFooter.blocked.noVersion")
        loaderLoading -> tr("deskA.installFooter.blocked.loadingLoaders")
        loader != LoaderType.Vanilla && loaderVersion.isBlank() -> tr("deskA.installFooter.blocked.noLoaderVersion")
        else -> null
    }
