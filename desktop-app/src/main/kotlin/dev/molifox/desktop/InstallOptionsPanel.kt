// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 安装选项卡片：实例名、加载器按钮组、加载器版本状态与隔离提示。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import dev.molifox.core.LoaderVersion

/** 右侧安装选项卡片：实例名、加载器与隔离提示。 */
@Composable
internal fun RowScope.InstallOptionsPanel(
    state: LauncherState,
    actions: UiActions,
    selected: String,
    name: String,
    onNameChange: (String) -> Unit,
    loader: LoaderType,
    onLoaderChange: (LoaderType) -> Unit,
    loaders: List<LoaderVersion>,
    loaderVersion: String,
    onLoaderVersionChange: (String) -> Unit,
    loaderLoading: Boolean,
    loaderError: String?,
) {
    val strings = LocalStrings.current
    Panel(Modifier.weight(1f).fillMaxHeight(), padding = 14) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.t("deskA.installOptions.title"), style = MaterialTheme.typography.titleMedium)
            Text(
                "Minecraft " + selected.ifBlank { strings.t("deskA.installOptions.pickVersion") },
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoliTextButton(
                    {
                        actions.run {
                            actions.controller.platform.openUrl(
                                "https://www.minecraft.net/zh-hans/updates",
                            )
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(strings.t("deskA.installOptions.officialUpdates"), fontSize = 12.sp)
                }
            }
            CompactTextField(
                name,
                onNameChange,
                Modifier.fillMaxWidth(),
                label = strings.t("dialogs.rename.nameLabel"),
                placeholder =
                    selected.ifBlank { strings.t("deskA.installOptions.namePlaceholder") } + loaderSuffix(loader),
            )
            LoaderChoiceGrid(loader, onLoaderChange)
            LoaderVersionStatus(
                loader,
                loaderLoading,
                loaderError,
                loaders,
                loaderVersion,
                onLoaderVersionChange,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IsolationHint(state.data.settings.isolateVersions)
            Text(
                strings.t("deskA.installOptions.installNote"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 加载器选项：两行按钮组，选中项以底色与描边强调。 */
@Composable
private fun LoaderChoiceGrid(
    loader: LoaderType,
    onLoaderChange: (LoaderType) -> Unit,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            strings.t("pages.instance.install.current.loader"),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // LiteLoader 与 Cleanroom 不再出现在新建入口，只保留给已有实例识别与启动。
        listOf(
            listOf(LoaderType.Vanilla, LoaderType.Fabric, LoaderType.Quilt),
            listOf(LoaderType.Forge, LoaderType.NeoForge, LoaderType.LegacyFabric),
            listOf(LoaderType.OptiFine),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    LoaderChoice(item, loader == item, Modifier.weight(1f)) {
                        onLoaderChange(item)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 加载器版本区：加载中、加载失败、暂无可选与下拉选择四种状态。 */
@Composable
private fun LoaderVersionStatus(
    loader: LoaderType,
    loaderLoading: Boolean,
    loaderError: String?,
    loaders: List<LoaderVersion>,
    loaderVersion: String,
    onLoaderVersionChange: (String) -> Unit,
) {
    val strings = LocalStrings.current
    when {
        loaderLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        loaderError != null ->
            Text(
                loaderError.orEmpty(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        loader != LoaderType.Vanilla && loaders.isEmpty() ->
            Text(
                strings.t("deskA.installOptions.noLoaderVersions", loader.title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        loader != LoaderType.Vanilla ->
            SimpleDropdown(
                loaderVersion,
                loaders.map {
                    it.version to
                        (
                            it.version + " · " +
                                strings.t(
                                    if (it.stable) {
                                        "deskA.installOptions.loaderVersion.stable"
                                    } else {
                                        "deskA.installOptions.loaderVersion.beta"
                                    },
                                )
                        )
                },
                onLoaderVersionChange,
            )
    }
}

/** 隔离方式提示：只说明当前游戏目录的隔离状态，不在这里切换。 */
@Composable
private fun IsolationHint(isolated: Boolean) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Shield,
            null,
            Modifier.size(16.dp),
            MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (isolated) {
                strings.t("deskA.installOptions.isolationEnabled")
            } else {
                strings.t("deskA.installOptions.sharedGameDirectory")
            },
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LoaderChoice(
    loader: LoaderType,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else controlBackground(),
            tween(if (LocalAnimationsEnabled.current) 120 else 0),
            label = "loader-bg",
        )
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(background)
            .border(controlBorder(selected), shape)
            .hoverHighlight(shape, enabled = !selected, alpha = .05f)
            .pressScale()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LoaderMark(loader, Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            loader.title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
        )
        Text(
            if (selected) {
                strings.t("deskA.installOptions.selected")
            } else {
                strings.t("deskA.installOptions.selectHint")
            },
            fontSize = 10.sp,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
        )
    }
}

/** 加载器后缀：与原版一致的「 · 加载器名」，原版为空。 */
internal fun loaderSuffix(loader: LoaderType): String = if (loader != LoaderType.Vanilla) " · " + loader.title else ""
