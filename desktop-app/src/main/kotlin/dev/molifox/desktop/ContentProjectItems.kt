// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的列表条目与下载动作：条目只渲染，下载交给调用方确认。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentDetails
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.desktop.platform.dialogs.PickMode
import dev.molifox.desktop.platform.dialogs.PickRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** 模组/资源包/光影/数据包/世界：先让用户确认安装位置，再把确认结果交给对话框。 */
internal fun contentDownload(
    actions: UiActions,
    hit: ContentHit,
    version: ContentVersion,
    defaultDestination: Path?,
    instanceRoot: Path?,
    onDestination: (Path) -> Unit,
    onConfirmDatapack: () -> Unit,
    onConfirmWorld: () -> Unit,
    onInstallVersion: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    if (hit.type == "modpack") {
        actions.run {
            actions.controller.preparePack(hit, version.id) { actions.pendingPack = it }
        }
        onInstallStarted()
        return
    }
    // 世界落点由包内世界目录决定，不需要用户再挑文件夹，只确认安装到哪个实例。
    if (hit.type == "world") {
        onConfirmWorld()
        return
    }
    actions.run {
        chooseContentDirectory(actions, hit, defaultDestination, instanceRoot) { picked ->
            onDestination(picked)
            if (hit.type == "datapack") onConfirmDatapack() else onInstallVersion()
        }
    }
}

/** 新实例可能还没有 resourcepacks / shaderpacks：先建出目录，选择器停在将来真正写入的位置。 */
private suspend fun chooseContentDirectory(
    actions: UiActions,
    hit: ContentHit,
    defaultDestination: Path?,
    instanceRoot: Path?,
    onPicked: (Path) -> Unit,
) {
    val directory = defaultDestination
    directory?.let { runCatching { Files.createDirectories(it) } }
    val picked =
        actions
            .choose(
                PickRequest(
                    title = contentPickTitle(hit.type),
                    mode = PickMode.Directory,
                    initialDirectory = directory ?: actions.controller.store.dataDirectory,
                ),
            ).firstOrNull()
    withContext(Dispatchers.Swing) {
        if (picked == null) return@withContext
        if (instanceRoot == null) {
            actions.controller.store.notify(tr("deskD.content.instanceRequired"))
            return@withContext
        }
        onPicked(picked)
    }
}

private fun contentPickTitle(type: String): String =
    if (type == "datapack") {
        tr("deskD.content.pickTitleDatapack")
    } else {
        tr("deskD.content.pickTitleDefault")
    }

/** 项目介绍：Modrinth 的 body 是 Markdown（可能夹带内联 HTML），交给 MarkdownText 渲染。 */
internal fun LazyListScope.contentDescriptionItem(details: ContentDetails?) {
    item {
        val strings = LocalStrings.current
        // 这里不预先剥离标记，否则 ##、-、[文字](链接) 会退化成原文。
        val body = details?.body
        if (body == null) {
            Text(strings.t("deskD.content.loading"), style = MaterialTheme.typography.bodySmall)
        } else {
            MarkdownText(body.ifBlank { strings.t("deskD.content.noDescription") }, Modifier.fillMaxWidth())
        }
    }
}

/** 更新日志：错误优先，其次作者原文，最后是「还没选版本」的占位。 */
internal fun LazyListScope.contentChangelogItem(
    changelogLoading: Boolean,
    changelog: ContentVersion?,
    changelogError: String?,
    chosen: ContentVersion?,
) {
    item {
        val strings = LocalStrings.current
        if (changelogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            changelogError
                ?: changelog?.changelog?.ifBlank { strings.t("deskD.content.noChangelog") }
                ?: if (chosen == null) {
                    strings.t("deskD.content.chooseVersion")
                } else {
                    strings.t("deskD.content.loadingChangelog")
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 推荐版本卡片：没有正式版时标题改成测试文件，按钮仍然写入当前选择。 */
internal fun LazyListScope.contentRecommendationItem(
    recommendation: ContentVersion?,
    onChoose: (ContentVersion) -> Unit,
) {
    recommendation?.let { best ->
        item {
            val strings = LocalStrings.current
            Panel(Modifier.fillMaxWidth(), padding = 10) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (best.channel != "release") {
                                strings.t("deskD.content.onlyBeta")
                            } else if (best.featured) {
                                strings.t("deskD.content.featuredRelease")
                            } else {
                                strings.t("deskD.content.latestRelease")
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                        Text(
                            best.number.ifBlank { best.name },
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton({ onChoose(best) }) { Text(strings.t("deskD.content.chooseRecommended")) }
                }
            }
        }
    }
}

/** 版本列表：空结果给出恢复默认筛选的入口，其余逐个渲染可点选行。 */
internal fun LazyListScope.contentVersionItems(
    loading: Boolean,
    versions: List<ContentVersion>,
    versionId: String,
    onSelect: (ContentVersion) -> Unit,
    onResetFilters: () -> Unit,
) {
    if (!loading && versions.isEmpty()) {
        item {
            val strings = LocalStrings.current
            EmptyState(
                strings.t("deskD.content.noVersionsTitle"),
                strings.t("deskD.content.noVersionsHint"),
                Icons.Outlined.SearchOff,
            ) {
                TextButton(onResetFilters) { Text(strings.t("deskD.content.showAllVersions")) }
            }
        }
    }
    items(versions, key = { it.id }) { version ->
        ContentProjectVersionRow(version, version.id == versionId, onSelect)
    }
}
