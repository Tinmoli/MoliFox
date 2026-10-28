// 负责原版和模组加载器的下载选择。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import kotlinx.coroutines.*

/** 下载分类：左侧二级列表的条目定义，顺序与规格 §4 一致。 */
private data class DownloadKind(val id: String, val title: String, val icon: ImageVector)

private val downloadKinds =
    listOf(
        DownloadKind("minecraft", "原版", Icons.Outlined.SportsEsports),
        DownloadKind("modpack", "整合包", Icons.Outlined.Work),
        DownloadKind("mod", "模组", Icons.Outlined.Extension),
        DownloadKind("resourcepack", "资源包", Icons.Outlined.Palette),
        DownloadKind("shader", "光影", Icons.Outlined.AutoAwesome),
        DownloadKind("datapack", "数据包", Icons.Outlined.Storage),
    )

@Composable
fun ColumnScope.DownloadCenterPage(
    state: LauncherState,
    actions: UiActions,
    kind: String,
    onKindChange: (String) -> Unit,
    navigate: (Page) -> Unit,
) {
    // 分类竖排在左侧，页面不显示“下载中心”标题，节省纵向空间。
    // 右侧内容随分类淡入切换（150ms），避免整块内容硬切。
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(158.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            downloadKinds.forEach { item ->
                DownloadKindItem(item.title, item.icon, kind == item.id) { onKindChange(item.id) }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (LocalAnimationsEnabled.current)
                Crossfade(kind, animationSpec = tween(150), label = "download-kind") { current ->
                    DownloadKindContent(current, state, actions, navigate, onKindChange) {
                        actions.taskQueued()
                    }
                }
            else
                DownloadKindContent(kind, state, actions, navigate, onKindChange) {
                    actions.taskQueued()
                }
        }
    }
}

/** 二级分类条目：高 34dp、圆角 6、图标 17、文字 13；选中态用主题底 + 主题色并做颜色过渡。 */
@Composable
private fun DownloadKindItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            tween(150),
            label = "kind-color",
        )
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(150),
            label = "kind-bg",
        )
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(background)
            .rowHoverHighlight(shape, enabled = !selected)
            .pressScale()
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Crossfade 的内容是 Box 作用域，这里补一层 Column 让子页面能使用 weight 撑满剩余高度。 */
@Composable
private fun DownloadKindContent(
    current: String,
    state: LauncherState,
    actions: UiActions,
    navigate: (Page) -> Unit,
    onKindChange: (String) -> Unit,
    onInstalled: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current == "minecraft") InstallGamePage(state, actions, onInstalled)
        else ContentBrowser(state, actions, navigate, current, onKindChange)
    }
}

@Composable
private fun ColumnScope.InstallGamePage(
    state: LauncherState,
    actions: UiActions,
    onInstalled: () -> Unit,
) {
    var versions by remember { mutableStateOf<List<ReleaseVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("release") }
    var selected by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf(LoaderType.Vanilla) }
    var loaders by remember { mutableStateOf<List<LoaderVersion>>(emptyList()) }
    var loaderVersion by remember { mutableStateOf("") }
    var loaderLoading by remember { mutableStateOf(false) }
    var loaderError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        loading = true
        error = null
        try {
            versions =
                withContext(Dispatchers.IO) { actions.controller.installer.versions(refresh > 0) }
            if (selected.isBlank())
                selected = versions.firstOrNull { it.type == "release" }?.id.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法加载版本列表"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(selected, loader) {
        val requestedVersion = selected
        val requestedLoader = loader
        loaders = emptyList()
        loaderVersion = ""
        loaderError = null
        loaderLoading = false
        if (loader == LoaderType.Vanilla || selected.isBlank()) return@LaunchedEffect
        loaderLoading = true
        try {
            loaders =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.loaderVersions(requestedLoader, requestedVersion)
                }
            loaderVersion =
                (loaders.firstOrNull { it.stable } ?: loaders.firstOrNull())?.version.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loaderError = failure.message ?: "无法加载加载器版本"
        } finally {
            if (selected == requestedVersion && loader == requestedLoader) loaderLoading = false
        }
    }
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.weight(1.15f).fillMaxHeight(), padding = 14) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Minecraft 版本", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                SmallIconButton(
                    Icons.Outlined.Refresh,
                    "刷新版本列表",
                    { refresh++ },
                    enabled = !actions.controller.busy && !loading,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (!loading && error == null) {
                versions
                    .filter { it.type == "release" }
                    .maxByOrNull { it.releaseTime }
                    ?.let { latest ->
                        Surface(
                            onClick = {
                                selected = latest.id
                                channel = "release"
                                query = ""
                            },
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "推荐 · 最新正式版",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        latest.id,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(latest.releaseTime.take(10), fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
            }
            CompactSearchField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                placeholder = "搜索版本，例如 1.21",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("release" to "正式版", "snapshot" to "快照", "all" to "全部").forEach { (id, title)
                    ->
                    FilterChip(
                        channel == id,
                        { channel = id },
                        { Text(title, fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val filtered = versions.filter {
                it.type in listOf("release", "snapshot") &&
                    (channel == "all" || it.type == channel) &&
                    it.id.contains(query, true)
            }
            when {
                loading ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            Text(
                                "正在加载版本列表…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                error != null ->
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState("版本列表暂不可用", error.orEmpty(), Icons.Outlined.CloudOff) {
                            TextButton({ refresh++ }) { Text("重新加载") }
                        }
                    }
                filtered.isEmpty() ->
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        EmptyState(
                            "没有符合条件的版本",
                            "试试搜索其他版本号，或切换到「全部」频道。",
                            Icons.Outlined.SearchOff,
                        )
                    }
                else ->
                    LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(filtered, key = { it.id }) { version ->
                            val active = version.id == selected
                            val rowShape = RoundedCornerShape(Metrics.cardRadius)
                            val rowColor by
                                animateColorAsState(
                                    if (active) selectedRowBackground() else Color.Transparent,
                                    tween(120),
                                    label = "version-bg",
                                )
                            Row(
                                Modifier.fillMaxWidth()
                                    .animateItem()
                                    .height(40.dp)
                                    .clip(rowShape)
                                    .background(rowColor)
                                    .rowHoverHighlight(rowShape, enabled = !active)
                                    .pressScale()
                                    .clickable { selected = version.id }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 游戏版本条目统一用草方块图标（原版默认图标），选中态由行底色与对勾表达。
                                GrassBlockIcon(size = 18.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        version.id,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color =
                                            if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        version.releaseTime.take(10),
                                        fontSize = 11.sp,
                                        color =
                                            if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (active) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        null,
                                        Modifier.size(15.dp),
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (version.type == "snapshot") Tag("快照") else Tag("正式版")
                            }
                        }
                    }
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight(), padding = 14) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("安装选项", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Minecraft ${selected.ifBlank { "· 请选择版本" }}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        {
                            actions.run {
                                actions.controller.platform.openUrl(
                                    "https://www.minecraft.net/zh-hans/updates"
                                )
                            }
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("官方更新", fontSize = 12.sp)
                    }
                    TextButton(
                        {
                            actions.run {
                                actions.controller.platform.openUrl(
                                    mcmodSearchUrl("Minecraft $selected")
                                )
                            }
                        },
                        enabled = selected.isNotBlank(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("MC 百科搜索", fontSize = 12.sp)
                    }
                }
                CompactTextField(
                    name,
                    { name = it.take(80) },
                    Modifier.fillMaxWidth(),
                    label = "实例名称",
                    placeholder =
                        selected.ifBlank { "选择左侧版本" } +
                            if (loader != LoaderType.Vanilla) " · " + loader.title else "",
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "模组加载器",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(
                            listOf(LoaderType.Vanilla, LoaderType.Fabric, LoaderType.Quilt),
                            listOf(LoaderType.Forge, LoaderType.NeoForge),
                        )
                        .forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { item ->
                                    LoaderChoice(item, loader == item, Modifier.weight(1f)) {
                                        loader = item
                                    }
                                }
                            }
                        }
                }
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
                            "该版本暂无可用的 " + loader.title,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    loader != LoaderType.Vanilla ->
                        SimpleDropdown(
                            loaderVersion,
                            loaders.map {
                                it.version to (it.version + if (it.stable) " · 稳定版" else " · 测试版")
                            },
                            { loaderVersion = it },
                        )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Shield,
                        null,
                        Modifier.size(16.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.data.settings.isolateVersions) "版本隔离已启用" else "当前使用共用游戏目录",
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "安装时校验游戏文件并准备缺少的依赖，进度会显示在任务列表。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val blocked =
            installBlockedReason(
                actions,
                loading,
                error,
                selected,
                loaderLoading,
                loader,
                loaderVersion,
            )
        Column(Modifier.weight(1f)) {
            Text(
                selected.ifBlank { "尚未选择版本" } + "  /  " + loader.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // 按钮灰掉时把缺项直接写在旁边，避免“点了没反应”的猜谜。
                blocked ?: "安装完成后即可从「开始」启动",
                fontSize = 11.sp,
                color =
                    if (blocked != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(
            "开始安装",
            Icons.Outlined.Download,
            !actions.controller.busy &&
                !loading &&
                error == null &&
                selected.isNotBlank() &&
                !loaderLoading &&
                (loader == LoaderType.Vanilla || loaderVersion.isNotBlank()),
        ) {
            actions.run {
                actions.controller.install(
                    InstallRequest(
                        name.ifBlank {
                            selected +
                                if (loader != LoaderType.Vanilla) " · " + loader.title else ""
                        },
                        selected,
                        loader,
                        loaderVersion,
                    ),
                    gameDirectoryId = null,
                )
                withContext(Dispatchers.Main) { onInstalled() }
            }
        }
    }
}

/** 开始安装按钮的禁用原因；返回 null 表示当前可以安装。 */
private fun installBlockedReason(
    actions: UiActions,
    loading: Boolean,
    error: String?,
    selected: String,
    loaderLoading: Boolean,
    loader: LoaderType,
    loaderVersion: String,
): String? =
    when {
        actions.controller.busy -> "有任务正在运行，稍后再试"
        loading -> "正在加载版本列表"
        error != null -> "版本列表不可用，请先重新加载"
        selected.isBlank() -> "请先在左侧选择一个版本"
        loaderLoading -> "正在加载加载器版本"
        loader != LoaderType.Vanilla && loaderVersion.isBlank() -> "请选择加载器版本"
        else -> null
    }

@Composable
private fun LoaderChoice(
    loader: LoaderType,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
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
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            if (selected) "已选择" else "点选切换",
            fontSize = 10.sp,
            color =
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 紧凑搜索框：34dp 高、圆角 4、1dp 细边、13sp 文字。 桌面端列表页的搜索都在工具栏里，用 M3 默认 56dp 输入框会把整行撑高。 */
@Composable
internal fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            controlBorderColor(focused),
            tween(120),
            label = "search-border",
        )
    val fillColor by
        animateColorAsState(
            controlBackground(focused, hovered),
            tween(120),
            label = "search-fill",
        )
    Row(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), colors.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value,
            onValueChange,
            Modifier.weight(1f),
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = colors.onSurface,
                ),
            singleLine = true,
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty())
                        Text(
                            placeholder,
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    inner()
                }
            },
        )
        if (value.isNotEmpty())
            IconButton({ onValueChange("") }, Modifier.size(20.dp)) {
                Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(13.dp), colors.onSurfaceVariant)
            }
    }
}
