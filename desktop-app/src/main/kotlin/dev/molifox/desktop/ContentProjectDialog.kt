// 负责资源项目详情、版本选择和下载入口。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

/** Project browsing is available without an installation target, including old versions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentProjectDialog(
    hit: ContentHit,
    state: LauncherState,
    actions: UiActions,
    initialFilter: ContentFilter,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    var details by remember(hit.id) { mutableStateOf<ContentDetails?>(null) }
    var loading by remember(hit.id) { mutableStateOf(true) }
    var error by remember(hit.id) { mutableStateOf<String?>(null) }
    var retry by remember(hit.id) { mutableIntStateOf(0) }
    var gameVersion by remember(hit.id) { mutableStateOf(initialFilter.gameVersion) }
    var loader by remember(hit.id) { mutableStateOf(initialFilter.loader) }
    var channel by remember(hit.id) { mutableStateOf("all") }
    var versionId by remember(hit.id) { mutableStateOf("") }
    var targetId by remember(hit.id) { mutableStateOf(state.data.selectedInstanceId.orEmpty()) }
    // 用户在下载对话框里确认过的目标文件夹；为空表示沿用实例内的默认目录。
    var destination by remember(hit.id) { mutableStateOf<Path?>(null) }
    var installVersion by remember(hit.id) { mutableStateOf<ContentVersion?>(null) }
    var tab by remember(hit.id) { mutableStateOf("versions") }
    var worlds by remember(hit.id) { mutableStateOf(emptyList<SaveEntry>()) }
    var worldName by remember(hit.id) { mutableStateOf("") }
    var worldError by remember(hit.id) { mutableStateOf<String?>(null) }
    var confirmDatapack by remember(hit.id) { mutableStateOf(false) }
    var changelog by remember(hit.id) { mutableStateOf<ContentVersion?>(null) }
    var changelogError by remember(hit.id) { mutableStateOf<String?>(null) }
    var changelogLoading by remember(hit.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember(hit.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    LaunchedEffect(hit.id, retry) {
        loading = true
        error = null
        try {
            details =
                withContext(Dispatchers.IO) { actions.controller.content.catalog.browse(hit.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "项目详情暂不可用"
        } finally {
            if (isActive) loading = false
        }
    }
    val versions =
        filteredContentVersions(
                details?.versions.orEmpty(),
                ContentFilter(gameVersion, loader),
                channel,
            )
            .sortedWith(newestVersionFirst)
    val target = state.data.instances.find { it.id == targetId && it.installed }
    val recommendation = recommendedContentVersion(versions, target, hit.type)
    LaunchedEffect(hit.id, targetId, gameVersion, loader, channel, details) {
        val current = versions.find { it.id == versionId }
        if (
            current == null ||
                (target != null &&
                    hit.type != "modpack" &&
                    !compatibleContentVersion(current, target, hit.type))
        ) {
            versionId = recommendation?.id.orEmpty()
        }
    }
    val chosen = versions.find { it.id == versionId }
    LaunchedEffect(hit.id, versionId, tab) {
        changelog = null
        changelogError = null
        changelogLoading = false
        if (tab != "changelog" || versionId.isBlank()) return@LaunchedEffect
        changelogLoading = true
        try {
            changelog =
                withContext(Dispatchers.IO) {
                    actions.controller.content.catalog.browseVersion(hit.id, versionId)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            changelogError = failure.message
        } finally {
            if (isActive) changelogLoading = false
        }
    }
    val store = actions.controller.store
    val instanceRoot = target?.let { runCatching { store.instancePath(it.id) }.getOrNull() }
    // 下载目标的默认值：实例内容目录（mods / resourcepacks / shaderpacks），
    // 数据包落到所选存档的 datapacks；没有实例时由用户从数据目录里自己挑。
    val defaultDestination =
        instanceRoot
            ?.let { root ->
                runCatching {
                    if (hit.type == "datapack") contentDestination(root, hit.type, worldName)
                    else contentDestination(root, hit.type)
                }
                    .getOrNull()
            }
            ?.toAbsolutePath()
            ?.normalize()
    LaunchedEffect(targetId, hit.type) {
        worlds = emptyList()
        worldName = ""
        worldError = null
        if (hit.type != "datapack" || target == null) return@LaunchedEffect
        try {
            worlds =
                withContext(Dispatchers.IO) {
                    actions.controller.saves.listSaves(target.id).filter {
                        it.hasLevelDat && it.path.isDirectory()
                    }
                }
            worldName = worlds.firstOrNull()?.name.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            worldError = failure.message
        }
    }
    if (confirmDatapack && target != null && chosen != null) {
        Modal("安装数据包", "${target.name} / $worldName", onDismiss = { confirmDatapack = false }) {
            Text("将 ${hit.title} ${chosen.number} 安装到此存档的 datapacks 文件夹。不会覆盖同名文件。")
            Text(
                "进入世界后启用数据包或执行 /reload；请先在项目说明中确认玩法影响。",
                style = MaterialTheme.typography.bodySmall,
            )
            defaultDestination?.let { folder ->
                MutedText("下载位置 · $folder")
                if (destination != null && destination != folder)
                    MutedText("数据包只能放进存档的 datapacks，安装时会使用上面的位置。")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ confirmDatapack = false }) { Text("取消") }
                PrimaryButton(
                    "安装到此存档",
                    enabled = !actions.controller.busy,
                    onClick = {
                        actions.run {
                            actions.controller.installDatapack(hit, chosen.id, target.id, worldName)
                        }
                        onInstallStarted()
                    },
                )
            }
        }
        return
    }
    installVersion?.let { selected ->
        if (target != null)
            ContentInstallDialog(
                hit,
                target.id,
                actions,
                { installVersion = null },
                onInstallStarted,
                initialVersionId = selected.id,
                destination = destination,
            )
        return
    }
    Modal(hit.title, "项目详情 · 先选游戏与资源版本，再安装", width = 740.dp, onDismiss = onDismiss) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton({ retry++ }) { Text("重试") }
            }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                hit.description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val links =
                    details?.links
                        ?: listOf(
                            ContentLink("项目网页", contentWebsite(hit)),
                            ContentLink("MC 百科搜索", mcmodSearchUrl(hit.title)),
                        )
                links.forEach { link ->
                    TextButton(
                        { actions.run { actions.controller.platform.openUrl(link.url) } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(link.title, fontSize = 12.sp)
                    }
                }
                TextButton({
                    clipboard.setText(AnnotatedString(hit.title))
                    copied = true
                }) {
                    Text(if (copied) "名称已复制" else "复制名称", fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val gameVersions =
                    details?.versions.orEmpty().flatMap { it.gameVersions }.distinct()
                SimpleDropdown(
                    gameVersion,
                    listOf("" to "全部游戏版本") +
                        (listOf(gameVersion).filter { it.isNotBlank() } + gameVersions)
                            .distinct()
                            .map { it to it },
                    { gameVersion = it },
                    Modifier.weight(1f),
                )
                if (hit.type in setOf("mod", "modpack"))
                    SimpleDropdown(
                        loader?.name.orEmpty(),
                        listOf("" to "全部加载器") +
                            LoaderType.entries
                                .filter { it != LoaderType.Vanilla }
                                .map { it.name to it.title },
                        { loader = LoaderType.entries.find { entry -> entry.name == it } },
                        Modifier.weight(1f),
                    )
                SimpleDropdown(
                    channel,
                    listOf(
                        "all" to "全部发布类型",
                        "release" to "正式版",
                        "beta" to "测试版",
                        "alpha" to "预览版",
                    ),
                    { channel = it },
                    Modifier.width(140.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("versions" to "版本列表", "description" to "项目介绍", "changelog" to "更新日志")
                    .forEach { (key, title) ->
                        FilterChip(tab == key, { tab = key }, { Text(title, fontSize = 12.sp) })
                    }
            }
            LazyColumn(
                Modifier.fillMaxWidth().height(235.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (tab) {
                    "description" ->
                        item {
                            // Modrinth 的 body 是 Markdown（可能夹带内联 HTML），交给 MarkdownText
                            // 渲染；这里不预先剥离标记，否则 ##、-、[文字](链接) 会退化成原文。
                            val body = details?.body
                            if (body == null) {
                                Text("正在读取…", style = MaterialTheme.typography.bodySmall)
                            } else {
                                MarkdownText(body.ifBlank { "项目暂无介绍。" }, Modifier.fillMaxWidth())
                            }
                        }
                    "changelog" ->
                        item {
                            if (changelogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                changelogError
                                    ?: changelog?.changelog?.ifBlank { "作者没有提供此版本的更新日志。" }
                                    ?: if (chosen == null) "请先选择一个资源版本。" else "正在读取更新日志…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    else -> {
                        recommendation?.let { best ->
                            item {
                                Panel(Modifier.fillMaxWidth(), padding = 10) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                if (best.channel != "release") "暂无正式版 · 最新测试文件"
                                                else if (best.featured) "推荐版本 · 作者推荐的兼容正式版"
                                                else "推荐版本 · 最新兼容正式版",
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
                                        TextButton({ versionId = best.id }) { Text("选择推荐") }
                                    }
                                }
                            }
                        }
                        if (!loading && versions.isEmpty())
                            item {
                                EmptyState(
                                    "没有符合条件的版本",
                                    "切换游戏版本、加载器或发布类型后重试。",
                                    Icons.Outlined.SearchOff,
                                ) {
                                    TextButton({
                                        gameVersion = ""
                                        loader = null
                                        channel = "all"
                                    }) {
                                        Text("显示全部版本")
                                    }
                                }
                            }
                        items(versions, key = { it.id }) { version ->
                            Surface(
                                onClick = { versionId = version.id },
                                color =
                                    if (version.id == versionId)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (version.id == versionId)
                                            Icons.Outlined.RadioButtonChecked
                                        else Icons.Outlined.RadioButtonUnchecked,
                                        null,
                                        Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            version.number.ifBlank { version.name },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            version.gameVersions.joinToString(" / ") +
                                                " · " +
                                                version.loaders.joinToString(" / "),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        "${contentChannelTitle(version.channel)} · ${version.published.take(10)}",
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (hit.type != "modpack")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("安装到", fontSize = 12.sp)
                    SimpleDropdown(
                        targetId,
                        listOf("" to "选择游戏实例") +
                            state.data.instances
                                .filter { it.installed }
                                .map { it.id to "${it.name} · ${it.gameVersion}" },
                        { targetId = it },
                        Modifier.weight(1f),
                    )
                }
            if (hit.type == "datapack")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("目标存档", fontSize = 12.sp)
                    SimpleDropdown(
                        worldName,
                        if (worlds.isEmpty()) listOf("" to (worldError ?: "此实例暂无可用世界"))
                        else worlds.map { it.name to it.name },
                        { worldName = it },
                        Modifier.weight(1f),
                    )
                }
        }
        val compatible =
            chosen != null &&
                (hit.type == "modpack" ||
                    target?.let {
                        compatibleContentVersion(chosen, it, hit.type)
                    } == true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (hit.type == "modpack") "将先预览整合包，不会立即安装。"
                else if (target == null) "先选实例或点「下载」挑一个保存文件夹。"
                else if (!compatible) "所选文件不兼容此实例的游戏版本或加载器。"
                else if (hit.type == "datapack") "数据包需要安装到具体存档。" else "下载前会确认目标文件夹，并检查前置与文件冲突。",
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onDismiss) { Text("关闭") }
            PrimaryButton(
                "下载",
                enabled =
                    !loading &&
                        !actions.controller.busy &&
                        chosen != null &&
                        (target == null || compatible) &&
                        (hit.type != "datapack" || (target != null && worldName.isNotBlank())),
                onClick = {
                    val version = chosen ?: return@PrimaryButton
                    if (hit.type == "modpack") {
                        actions.run {
                            actions.controller.preparePack(hit, version.id) {
                                actions.pendingPack = it
                            }
                        }
                        onInstallStarted()
                    } else {
                        // 先让用户确认下载目标：有实例时默认就是它的内容目录，
                        // 实例路径会整段显示出来；没有实例时从数据目录开始自由选择。
                        actions.run {
                            val directory = defaultDestination
                            // 新实例可能还没有 resourcepacks / shaderpacks：先建出目录，
                            // 选择器就能停在将来真正写入的位置，而不是上层的游戏根目录。
                            directory?.let { runCatching { Files.createDirectories(it) } }
                            val picked =
                                PathPicker.pick(
                                        PickRequest(
                                            title =
                                                if (hit.type == "datapack")
                                                    "数据包会放进这个存档的 datapacks 文件夹"
                                                else "下载的资源会放进这个文件夹",
                                            mode = PickMode.Directory,
                                            initialDirectory = directory ?: store.dataDirectory,
                                        )
                                    )
                                    .firstOrNull()
                            withContext(Dispatchers.Swing) {
                                if (picked == null) return@withContext
                                if (instanceRoot == null) {
                                    store.notify("请先在「安装到」里选择游戏实例：资源会下载到实例目录")
                                    return@withContext
                                }
                                destination = picked
                                if (hit.type == "datapack") confirmDatapack = true
                                else installVersion = version
                            }
                        }
                    }
                },
            )
        }
    }
}

internal fun contentChannelTitle(channel: String) =
    when (channel) {
        "release" -> "正式版"
        "beta" -> "测试版"
        "alpha" -> "预览版"
        else -> channel
    }

/** 资源类型对应的实例内目录名；与核心安装事务接受的三个目录保持一致。 */
internal fun contentFolderForType(type: String): String =
    when (type) {
        "resourcepack" -> "resourcepacks"
        "shader" -> "shaderpacks"
        "datapack" -> "datapacks"
        else -> "mods"
    }

/** 默认下载目标：模组 / 资源包 / 光影落在实例内容目录，数据包落在所选存档的 datapacks。 显示给用户的就是最终会被写入的路径。 */
internal fun contentDestination(instanceRoot: Path, type: String, worldName: String = ""): Path =
    if (type == "datapack") instanceRoot.resolve("saves").resolve(worldName).resolve("datapacks")
    else instanceRoot.resolve(contentFolderForType(type))

/**
 * 用户确认的文件夹 → 实例内的资源子目录名（mods / resourcepacks / shaderpacks）。 不在实例内、或者不是这三个目录之一时返回
 * null：安装仍走默认目录，不写入用户没有确认的位置。
 */
internal fun contentFolderOf(instanceRoot: Path?, destination: Path?): String? {
    if (instanceRoot == null || destination == null) return null
    val root = runCatching { instanceRoot.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val target = runCatching { destination.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val relative = runCatching { root.relativize(target) }.getOrNull() ?: return null
    if (relative.nameCount != 1) return null
    return relative.toString().takeIf { it in setOf("mods", "resourcepacks", "shaderpacks") }
}

/** 版本列表排序：发布时间降序（最新文件排在最上面）；时间相同或缺失时，按版本号里的数字段降序回退， 避免字符串日期格式不一致时列表变成「最旧在上」。 */
private val newestVersionFirst: Comparator<ContentVersion> =
    compareByDescending<ContentVersion> { it.published }
        .thenComparator { left, right -> compareVersionNumbers(right.number, left.number) }

private fun compareVersionNumbers(left: String, right: String): Int {
    val a = versionNumberSegments(left)
    val b = versionNumberSegments(right)
    repeat(maxOf(a.size, b.size)) { index ->
        val diff = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return left.compareTo(right)
}

private fun versionNumberSegments(value: String): List<Int> =
    Regex("[0-9]+").findAll(value).mapNotNull { it.value.toIntOrNull() }.take(4).toList()
