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
import dev.molifox.desktop.MoliTextButton as TextButton
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
import com.google.gson.JsonParseException
import dev.molifox.core.GameInstance
import dev.molifox.core.LoaderType
import dev.molifox.core.LoaderVersion
import dev.molifox.core.launchOnly
import dev.molifox.core.loaderUpgradeCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun packSourceOf(instance: GameInstance): String =
    when {
        instance.packFile.isNotBlank() ->
            tr("pages.instance.install.packsource.pack", instance.packFile.substringAfterLast('/'))
        instance.externalProfile -> tr("pages.instance.install.packsource.external")
        else -> tr("pages.instance.install.packsource.launcher")
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
    // LiteLoader/Cleanroom 只识别和启动已有实例，不显示升级卡片，也不请求加载器版本。
    if (instance.loader.launchOnly) {
        CurrentInstallSection(instance, actions)
        return
    }
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
                    }.getOrElse { it.message ?: tr("pages.instance.install.externalConfigUnreadable") }
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
        } catch (failure: IOException) {
            versionError = failure.message
        } catch (failure: IllegalStateException) {
            versionError = failure.message
        } catch (failure: JsonParseException) {
            versionError = failure.message
        } finally {
            loadingVersions = false
        }
    }
    val candidates = loaderUpgradeCandidates(instance, versions)
    CurrentInstallSection(instance, actions)
    LoaderUpgradeSection(
        instance = instance,
        actions = actions,
        versions = versions,
        candidates = candidates,
        ui =
            LoaderUpgradeUi(
                loadingVersions = loadingVersions,
                versionError = versionError,
                upgradeable = upgradeable,
                externalBlocker = externalBlocker,
                onRetry = { versionRetry++ },
            ),
    )
}

/** 升级卡片用到的异步状态：读取中 / 失败原因 / 能否升级 / 外部实例阻塞原因 / 重试入口。 */
private data class LoaderUpgradeUi(
    val loadingVersions: Boolean,
    val versionError: String?,
    val upgradeable: Boolean,
    val externalBlocker: String?,
    val onRetry: () -> Unit,
)

/** 「当前安装」卡片：只展示当前组件，并提供修复入口。 */
@Composable
private fun CurrentInstallSection(
    instance: GameInstance,
    actions: UiActions,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("pages.instance.install.current.title")) {
        SettingRow(
            strings.t("pages.instance.install.current.gameVersion"),
            instance.gameVersion.ifBlank { strings.t("pages.instance.meta.versionUnknown") },
        ) {
            InstanceArtwork(instance, actions, Modifier.size(24.dp))
        }
        SettingRow(
            strings.t("pages.instance.install.current.loader"),
            if (instance.loader == LoaderType.Vanilla) {
                strings.t("pages.instance.install.current.vanillaLoader")
            } else {
                instance.loader.title + " " + instance.loaderVersion
            },
        ) {}
        Text(
            strings.t("pages.instance.install.current.versionId", instance.versionId),
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
                Text(strings.t("pages.instance.install.current.repair"), fontSize = 12.sp)
            }
        }
    }
}

/** 「升级加载器」卡片：先说明为什么不能升级，再展示候选版本与升级入口。 */
@Composable
private fun LoaderUpgradeSection(
    instance: GameInstance,
    actions: UiActions,
    versions: List<LoaderVersion>,
    candidates: List<LoaderVersion>,
    ui: LoaderUpgradeUi,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("pages.instance.install.upgrade.title"),
        strings.t("pages.instance.install.upgrade.detail"),
    ) {
        val blocker = upgradeBlocker(instance, ui)
        if (blocker != null) {
            Text(
                blocker,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SettingRow(
                strings.t("pages.instance.install.upgrade.currentLoader"),
                instance.loader.title + " " + instance.loaderVersion,
            ) {}
            LoaderCandidateStatus(instance, actions, versions, candidates, ui)
        }
    }
}

/** 不能升级的原因；顺序与界面展示优先级一致，能升级时返回 null。 */
private fun upgradeBlocker(
    instance: GameInstance,
    ui: LoaderUpgradeUi,
): String? =
    when {
        instance.broken && instance.externalProfile ->
            tr(
                "pages.instance.install.blocker.externalBroken",
                instance.brokenReason.ifBlank { tr("pages.instance.install.externalBrokenDefault") },
            )
        instance.loader == LoaderType.Vanilla -> tr("pages.instance.install.blocker.vanilla")
        instance.packFile.isNotBlank() -> tr("pages.instance.install.blocker.modpack")
        instance.externalProfile && ui.externalBlocker != null -> ui.externalBlocker.orEmpty()
        !ui.upgradeable -> tr("pages.instance.install.blocker.notInstalled")
        instance.loaderVersion.isBlank() -> tr("pages.instance.install.blocker.versionUnknown")
        else -> null
    }

/** 候选版本状态：加载中 / 读取失败 / 无候选 / 已是最新 / 可升级。 */
@Composable
private fun LoaderCandidateStatus(
    instance: GameInstance,
    actions: UiActions,
    versions: List<LoaderVersion>,
    candidates: List<LoaderVersion>,
    ui: LoaderUpgradeUi,
) {
    val strings = LocalStrings.current
    val latest = candidates.firstOrNull()
    when {
        ui.loadingVersions ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(
                    strings.t("pages.instance.install.loadingVersions", instance.loader.title),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        ui.versionError != null ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.versionError.orEmpty(),
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton({ ui.onRetry() }) { Text(strings.t("pages.instance.install.retry"), fontSize = 12.sp) }
            }
        versions.isEmpty() ->
            Text(
                strings.t("pages.instance.install.noVersions", instance.loader.title),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        latest == null ->
            Text(
                strings.t(
                    "pages.instance.install.upToDate",
                    instance.loader.title,
                    instance.loaderVersion,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else ->
            SettingRow(
                strings.t("pages.instance.install.upgradeTo"),
                if (latest.stable) {
                    latest.version
                } else {
                    strings.t("pages.instance.install.versionBeta", latest.version)
                },
            ) {
                PrimaryButton(
                    strings.t("pages.instance.install.upgrade"),
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
