// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容浏览页：搜索 / 筛选表单、结果计数、检索说明与回到顶部入口。

package dev.molifox.desktop.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentCategoryOption
import dev.molifox.core.ContentFilter
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentSearch
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import dev.molifox.core.minecraftVersionTag
import dev.molifox.desktop.CompactSearchField
import dev.molifox.desktop.ContentProjectDialog
import dev.molifox.desktop.Panel
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.SimpleDropdown
import dev.molifox.desktop.SmallIconButton
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.selectedRowBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 结果排序：只在当前页内重排（后端分页接口只按相关度返回，不改业务参数）。 */
private val contentSortOptions = listOf("downloads" to "下载量", "relevance" to "相关度", "name" to "名称")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.ContentBrowser(
    state: LauncherState,
    actions: UiActions,
    kind: String,
) {
    val preferences = state.data.settings.pages.downloads
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(ContentSearch(emptyList(), 0)) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("downloads") }
    var listView by remember { mutableStateOf(true) }
    // 首次检索完成前显示骨架，之后刷新只保留旧结果 + 顶部细进度，避免列表整块闪烁。
    var loadedOnce by remember(kind) { mutableStateOf(false) }
    val selected = state.data.instances.find { it.id == state.data.selectedInstanceId }
    val source = state.data.settings.contentDownloadSource
    // 实例的版本目录名可能是整合包标识（TwilightMC-26.3v1.0），只把能识别的真实版本用于筛选。
    val instanceVersion =
        minecraftVersionTag(selected?.gameVersion.orEmpty())?.takeIf { it.isNotBlank() }
    var projectHit by remember(kind) { mutableStateOf<ContentHit?>(null) }
    // 默认筛选值直接取自当前实例，换实例时重新初始化；不存在单独的实例开关。
    var gameVersion by
        remember(kind, selected?.id) {
            mutableStateOf(instanceVersion.orEmpty())
        }
    var loader by
        remember(kind, selected?.id) {
            mutableStateOf(selected?.loader.takeIf { kind == "mod" })
        }
    // 分类：Modrinth 用内置 slug，CurseForge 用镜像自己的数字编号，换来源/类型都重新取。
    var categories by remember(kind, source) { mutableStateOf(emptySet<String>()) }
    var sourceCategories by remember(kind, source) {
        mutableStateOf(emptyList<ContentCategoryOption>())
    }
    LaunchedEffect(kind, source) {
        if (source != "curseforge") {
            sourceCategories = emptyList()
            return@LaunchedEffect
        }
        sourceCategories =
            try {
                withContext(Dispatchers.IO) {
                    actions.controller.content.contentCategories(kind)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
    }
    val categoryOptions =
        if (source == "curseforge") {
            sourceCategories.map { it.id to it.name }
        } else {
            categoriesFor(kind)
        }
    var gameVersions by remember { mutableStateOf(emptyList<String>()) }
    var versionListError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            gameVersions =
                withContext(Dispatchers.IO) {
                    actions.controller.installer
                        .versions()
                        .filter { it.type == "release" }
                        .map { it.id }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            versionListError = failure.message
        }
    }
    // 筛选只用这两个值：默认等于当前实例，改选就是显式筛选。
    val filter =
        ContentFilter(
            gameVersion,
            loader.takeIf { kind == "mod" && it != LoaderType.Vanilla },
            categories,
        )
    projectHit?.let { hit ->
        ContentProjectDialog(
            hit,
            state,
            actions,
            filter,
            { projectHit = null },
            {
                projectHit = null
                actions.taskQueued()
            },
        )
    }
    LaunchedEffect(
        kind,
        query,
        selected?.id,
        selected?.gameVersion,
        selected?.loader,
        state.data.settings.contentDownloadSource,
        filter,
    ) {
        offset = 0
    }
    LaunchedEffect(
        kind,
        query,
        selected?.id,
        selected?.gameVersion,
        selected?.loader,
        state.data.settings.contentDownloadSource,
        filter,
        offset,
        refresh,
    ) {
        loading = true
        error = null
        delay(350)
        try {
            result =
                withContext(Dispatchers.IO) {
                    actions.controller.content.search(
                        query,
                        kind,
                        if (kind == "modpack") null else state.data.selectedInstanceId,
                        offset,
                        filter,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message
        } finally {
            if (isActive) {
                loading = false
                loadedOnce = true
            }
        }
    }
    // ---- 搜索表单卡片：一行搜索、一行筛选（44dp 行）、一行来源说明 ----
    Panel(Modifier.fillMaxWidth(), padding = 12) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactSearchField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    placeholder = "搜索资源名称，例如 Sodium、Fabric API…",
                )
                PrimaryButton("搜索", Icons.Outlined.Search, onClick = { refresh++ })
                val sourceHome = contentSourceHome(state.data.settings.contentDownloadSource)
                if (sourceHome != null) {
                    SmallIconButton(
                        Icons.Outlined.OpenInNew,
                        "打开 " +
                            contentSourceName(state.data.settings.contentDownloadSource) +
                            " 主页",
                        { actions.run { actions.controller.platform.openUrl(sourceHome) } },
                    )
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 分类：类型已由左侧导航决定，只列出该类型在 Modrinth 上真实存在的分类。
                ContentCategoryPicker(
                    categories,
                    categoryOptions,
                    { categories = it },
                    Modifier.width(110.dp),
                )
                // 游戏版本：默认就是当前实例的真实版本（没有实例时为「全部游戏版本」）。
                SimpleDropdown(
                    gameVersion,
                    buildList<Pair<String, String>> {
                        instanceVersion?.let { add(it to it) }
                        add("" to "全部游戏版本")
                        gameVersions.filter { it != instanceVersion }.forEach { add(it to it) }
                        // 实例版本可能不在官方正式版列表里（例如快照），兜底补一项。
                        if (
                            gameVersion.isNotBlank() &&
                            gameVersion != instanceVersion &&
                            gameVersion !in gameVersions
                        ) {
                            add(gameVersion to gameVersion)
                        }
                    },
                    { picked -> gameVersion = picked },
                    Modifier.width(140.dp),
                )
                if (kind == "mod") {
                    val instanceLoader = selected?.loader
                    val loaderOptions =
                        buildList<Pair<String, String>> {
                            instanceLoader?.let { add(it.name to it.title) }
                            LoaderType.entries
                                .filter { it != LoaderType.Vanilla && it != instanceLoader }
                                .forEach { add(it.name to it.title) }
                            add("" to "全部加载器")
                        }
                    SimpleDropdown(
                        loader?.name.orEmpty(),
                        loaderOptions,
                        { picked ->
                            loader = LoaderType.entries.find { entry -> entry.name == picked }
                        },
                        Modifier.width(125.dp),
                    )
                }
                SimpleDropdown(sort, contentSortOptions, { sort = it }, Modifier.width(105.dp))
                // 下载源：点一下就能换。
                SimpleDropdown(
                    state.data.settings.contentDownloadSource,
                    listOf(
                        "modrinth" to contentSourceName("modrinth"),
                        "curseforge" to contentSourceName("curseforge"),
                    ),
                    { picked ->
                        actions.run { actions.controller.setContentDownloadSource(picked) }
                    },
                    Modifier.width(150.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            if (kind == "modpack") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "整合包也可以直接导入本地文件。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    SecondaryButton({ actions.importPack() }, enabled = !actions.controller.busy) {
                        Icon(Icons.Outlined.Unarchive, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入整合包", fontSize = 12.sp)
                    }
                }
            }
            val followsInstance =
                selected != null &&
                    gameVersion == instanceVersion.orEmpty() &&
                    (kind != "mod" || loader == selected.loader)
            val hint =
                when {
                    versionListError != null -> "游戏版本列表暂不可用；项目详情仍可选择作者提供的版本。"
                    followsInstance -> contentHint(kind, selected)
                    else -> "按所选游戏版本 / 加载器筛选，不会更换安装实例。"
                }
            if (hint.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info,
                        null,
                        Modifier.size(13.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        hint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ---- 结果区 ----
    ContentResultsSection(
        result = result,
        sort = sort,
        query = query,
        loading = loading,
        loadedOnce = loadedOnce,
        error = error,
        listView = listView,
        onListViewChange = { listView = it },
        showAuthors = preferences.showAuthors,
        showDescriptions = preferences.showDescriptions,
        offset = offset,
        onOffsetChange = { offset = it },
        onOpen = { projectHit = it },
        onRetry = { refresh++ },
        onClearQuery = { query = "" },
        actions = actions,
    )
}

/** 检索条件说明：告诉用户当前按什么筛选、以及为什么安装按钮是灰的。 */
private fun contentHint(
    kind: String,
    target: GameInstance?,
): String =
    when {
        kind == "modpack" -> ""
        target == null -> "先安装并选择游戏实例，即可下载兼容的资源。"
        kind == "mod" && target.loader == LoaderType.Vanilla -> "当前是原版实例，安装模组需要 Fabric、Forge 等加载器。"
        kind == "shader" -> "光影会安装至 shaderpacks；请先在实例中准备兼容的 Iris 或 OptiFine。"
        else ->
            "已按 Minecraft " +
                target.gameVersion +
                (if (kind == "mod") " / " + target.loader.title else "") +
                " 筛选，下载时会补全必需的前置资源。"
    }

@Composable
internal fun ViewToggle(
    listView: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .height(26.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        listOf(false to Icons.Outlined.GridView, true to Icons.Outlined.ViewList)
            .forEach { (isList, icon) ->
                val active = listView == isList
                Box(
                    Modifier
                        .width(30.dp)
                        .fillMaxHeight()
                        .background(if (active) selectedRowBackground() else Color.Transparent)
                        .clickable { onChange(isList) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        if (isList) "列表视图" else "卡片视图",
                        Modifier.size(14.dp),
                        if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
    }
}

@Composable
internal fun GoTopButton(onClick: () -> Unit) {
    Surface(
        onClick,
        Modifier.size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.KeyboardArrowUp,
                "回到顶部",
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
