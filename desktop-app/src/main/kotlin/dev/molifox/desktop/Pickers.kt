// 负责应用内路径选择器与挂起请求结果桥。

package dev.molifox.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.io.path.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 选择器工作模式：文件 / 文件夹 / 保存。 */
enum class PickMode {
    File,
    Directory,
    Save,
}

/** 一次选择请求。标题只作为副标题展示，弹窗主标题由模式决定（选择文件 / 选择文件夹 / 保存位置）； [extensions] 在文件模式用于过滤列表，在保存模式用于自动补后缀。 */
data class PickRequest(
    val title: String = "",
    val mode: PickMode = PickMode.File,
    val multiple: Boolean = false,
    val extensions: List<String> = emptyList(),
    val defaultName: String = "",
    val initialDirectory: Path? = null,
)

/**
 * 请求/结果桥：挂起函数等待界面返回，界面只在 [PathPickerHost] 里渲染一次。 结果一律是绝对路径；取消返回空列表。既有 `choosePaths(...)` 调用点无需改动。
 */
object PathPicker {
    var request by mutableStateOf<PickRequest?>(null)
        private set

    /** 上次确认过的目录，作为下一次选择的起点；为空时回落到用户主目录。 */
    var lastDirectory by mutableStateOf<Path?>(null)
        private set

    private var pending: CancellableContinuation<List<Path>>? = null

    suspend fun pick(request: PickRequest): List<Path> =
        // 先在事件分发线程替换请求，保证 Compose 状态只在 UI 线程写入。
        withContext(Dispatchers.Swing) {
            pending?.let { previous ->
                pending = null
                this@PathPicker.request = null
                previous.resume(emptyList())
            }
            suspendCancellableCoroutine { continuation ->
                pending = continuation
                this@PathPicker.request = request
                // 调用方被取消（例如窗口关闭）时收起对话框，避免留下悬空状态。
                continuation.invokeOnCancellation {
                    if (pending === continuation) {
                        pending = null
                        this@PathPicker.request = null
                    }
                }
            }
        }

    fun complete(paths: List<Path>) {
        val continuation = pending ?: return
        pending = null
        request = null
        val resolved = paths.map { it.toAbsolutePath().normalize() }
        resolved.firstOrNull()?.let { first ->
            lastDirectory =
                if (runCatching { first.isDirectory() }.getOrDefault(false)) first else first.parent
        }
        continuation.resume(resolved)
    }

    fun cancel() = complete(emptyList())
}

/** 选择器宿主：挂在应用根级、主题内部渲染一次。 [quickPlaces] 由调用方补充（MoliFox 数据目录、各游戏目录），主目录/桌面/文档/下载在这里自动加入。 */
@Composable
fun PathPickerHost(quickPlaces: List<Pair<String, Path>> = emptyList()) {
    val spec = PathPicker.request ?: return
    PathPickerDialog(spec, quickPlaces)
}

@Composable
private fun PathPickerDialog(spec: PickRequest, quickPlaces: List<Pair<String, Path>>) {
    val start =
        remember(spec) {
            // 目标目录可能还没建出来（例如实例里没有 resourcepacks）：从最近的存在目录开始浏览。
            val preferred =
                spec.initialDirectory
                    ?: PathPicker.lastDirectory?.takeIf { isDirectory(it) }
                    ?: homeDirectory()
            nearestExistingDirectory(preferred)
        }
    var current by remember(spec) { mutableStateOf(start) }
    var showHidden by remember(spec) { mutableStateOf(true) }
    var listing by remember(spec) { mutableStateOf(PickerListing()) }
    var selection by remember(spec) { mutableStateOf(emptyList<Path>()) }
    var fileName by remember(spec) { mutableStateOf(spec.defaultName) }
    // 新建文件夹：只在文件夹/保存模式下出现，创建后直接进入新目录。
    var creating by remember(spec) { mutableStateOf(false) }
    var folderName by remember(spec) { mutableStateOf("新建文件夹") }
    var createError by remember(spec) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val places = remember(quickPlaces) { mergeQuickPlaces(quickPlaces) }
    // 列表高度按模式收一点：保存模式还要放文件名与新建文件夹，整体保持在 Modal 的 640 上限内。
    val listHeight = if (spec.mode == PickMode.Save) 200.dp else 236.dp

    // 换目录时清空选择并回到列表顶部；读目录放到 IO 线程，失败只显示一行说明，不抛异常。
    LaunchedEffect(current) {
        selection = emptyList()
        runCatching { listState.scrollToItem(0) }
    }
    LaunchedEffect(current, showHidden) {
        listing = withContext(Dispatchers.IO) { readDirectory(current, showHidden) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val selectedFile =
        selection.firstOrNull()?.takeIf { runCatching { it.isRegularFile() }.getOrDefault(false) }
    val selectedDirectory =
        selection.firstOrNull()?.takeIf { runCatching { it.isDirectory() }.getOrDefault(false) }
    val typedName = fileName.trim()
    val saveTarget =
        if (spec.mode == PickMode.Save && validPickName(typedName))
            current.resolve(saveNameFor(typedName, spec.extensions))
        else null
    val result: List<Path>? =
        when (spec.mode) {
            PickMode.Directory -> selectedDirectory?.let { listOf(it) } ?: listOf(current)
            PickMode.File ->
                if (spec.multiple) {
                    val files = selection.filter { path ->
                        runCatching { path.isRegularFile() }.getOrDefault(false)
                    }
                    files.ifEmpty { null }
                } else selectedFile?.let { listOf(it) }
            PickMode.Save -> saveTarget?.let { listOf(it) }
        }
    val canConfirm = result != null || (spec.mode == PickMode.File && selectedDirectory != null)
    val confirm: () -> Unit = {
        val paths = result
        when {
            paths != null -> PathPicker.complete(paths)
            // 文件模式下确认一个被单击选中的文件夹 = 进入它，不会变成死路。
            spec.mode == PickMode.File && selectedDirectory != null -> {
                current = selectedDirectory
            }
            else -> Unit
        }
    }
    val goUp: () -> Unit = { current.parent?.let { current = it } }
    val createFolder: () -> Unit = {
        val name = folderName.trim()
        when {
            !validPickName(name) -> createError = "名称不能为空，也不能包含 < > : \" / \\ | ? *"
            else -> {
                val target = runCatching { current.resolve(name) }.getOrNull()
                val failure =
                    if (target == null) IllegalStateException("无法创建文件夹")
                    else runCatching { Files.createDirectory(target) }.exceptionOrNull()
                if (failure == null) {
                    creating = false
                    createError = null
                    if (target != null) current = target
                } else {
                    createError =
                        if (target != null && runCatching { target.exists() }.getOrDefault(false))
                            "同名文件夹已存在"
                        else "无法创建文件夹，请换一个名称"
                }
            }
        }
    }
    val replaceWarning =
        saveTarget != null && runCatching { saveTarget.exists() }.getOrDefault(false)
    val summary =
        when {
            spec.mode == PickMode.Save ->
                saveTarget?.toString()
                    ?: runCatching { current.resolve(typedName).toString() }
                        .getOrDefault(current.toString())
            result != null -> result.joinToString(" · ") { it.toString() }
            selectedDirectory != null -> selectedDirectory.toString()
            else -> current.toString()
        }

    Modal(
        title =
            when (spec.mode) {
                PickMode.File -> "选择文件"
                PickMode.Directory -> "选择文件夹"
                PickMode.Save -> "保存位置"
            },
        subtitle = spec.title.ifBlank { "在 MoliFox 里浏览并确认路径" },
        width = 640.dp,
        onDismiss = { PathPicker.cancel() },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> {
                            PathPicker.cancel()
                            true
                        }
                        Key.Enter,
                        Key.NumPadEnter ->
                            if (canConfirm) {
                                confirm()
                                true
                            } else false
                        else -> false
                    }
                }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 面包屑：上一级 + 可点击的路径分段（横向滚动，长路径不挤占按钮）。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    goUp,
                    enabled = current.parent != null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上一级", fontSize = 12.sp)
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val segments = pathSegments(current)
                    segments.forEachIndexed { index, segment ->
                        if (index > 0)
                            Text(
                                "›",
                                Modifier.padding(horizontal = 3.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        val isLast = index == segments.lastIndex
                        Text(
                            text = segmentLabel(segment),
                            modifier =
                                Modifier.clip(RoundedCornerShape(Metrics.innerRadius))
                                    .hoverHighlight()
                                    .clickable { current = segment }
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            fontSize = 12.sp,
                            maxLines = 1,
                            color =
                                if (isLast) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(listHeight),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 左侧快捷位置：主目录 / 桌面 / 文档 / 下载 + 数据目录与各游戏目录。
                Column(
                    Modifier.width(136.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        "快捷位置",
                        Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    places.forEach { (label, path) ->
                        PickerPlaceRow(
                            label = label,
                            active = current == path,
                            onClick = { current = path },
                        )
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(hairline()))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (listing.error != null) {
                        PickerRow(
                            icon = Icons.Outlined.ErrorOutline,
                            label = listing.error.orEmpty(),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {},
                            onDoubleClick = {},
                        )
                        MutedText("可以点「上一级」换一个文件夹。")
                    } else {
                        val files =
                            if (spec.mode == PickMode.File)
                                listing.files.filter { matchesExtensions(it, spec.extensions) }
                            else emptyList()
                        if (listing.directories.isEmpty() && files.isEmpty()) {
                            MutedText(
                                if (spec.mode == PickMode.File && listing.files.isNotEmpty())
                                    "没有符合扩展名的文件。"
                                else "这个文件夹是空的。"
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                items(listing.directories, key = { "d:" + it.toString() }) { dir ->
                                    PickerRow(
                                        icon = Icons.Outlined.Folder,
                                        label = dir.fileName?.toString().orEmpty(),
                                        selected = selection.any { it == dir },
                                        onClick = { selection = listOf(dir) },
                                        onDoubleClick = { current = dir },
                                    )
                                }
                                items(files, key = { "f:" + it.toString() }) { file ->
                                    PickerRow(
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = file.fileName?.toString().orEmpty(),
                                        detail = fileSizeLabel(file),
                                        selected = selection.any { it == file },
                                        onClick = {
                                            selection =
                                                if (!spec.multiple) listOf(file)
                                                else if (selection.any { it == file })
                                                    selection.filterNot { it == file }
                                                // Path is Iterable<Path>; adding it directly adds
                                                // path segments.
                                                else selection + listOf(file)
                                        },
                                        onDoubleClick = {
                                            selection = listOf(file)
                                            PathPicker.complete(listOf(file))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (spec.mode == PickMode.Save)
                CompactTextField(
                    fileName,
                    { fileName = it.take(200) },
                    Modifier.fillMaxWidth(),
                    label = "文件名",
                    placeholder = "例如 MoliFox-1.0.zip",
                )
            if (creating)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        CompactTextField(
                            folderName,
                            { folderName = it.take(120) },
                            Modifier.weight(1f),
                            label = "新文件夹名称",
                        )
                        SecondaryButton({ creating = false }) { Text("取消", fontSize = 12.sp) }
                        PrimaryButton(
                            "创建",
                            enabled = folderName.isNotBlank(),
                            onClick = createFolder,
                        )
                    }
                    createError?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(showHidden, { showHidden = it }, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "显示隐藏文件",
                    Modifier.clip(RoundedCornerShape(Metrics.innerRadius))
                        .clickable { showHidden = !showHidden }
                        .padding(vertical = 2.dp),
                    fontSize = 12.sp,
                )
                if (spec.mode != PickMode.File) {
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        { creating = !creating },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建文件夹", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (replaceWarning)
                    Text(
                        "同名文件已存在，继续会替换它",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
            }
            Text(
                "将使用 · $summary",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DialogActions(
                confirmText = "选择",
                onConfirm = confirm,
                onCancel = { PathPicker.cancel() },
                enabled = canConfirm,
                hint =
                    when {
                        spec.mode == PickMode.File && spec.extensions.isNotEmpty() ->
                            "只显示 " + spec.extensions.joinToString(" / ") { it.uppercase() } + " 文件"
                        spec.mode == PickMode.File -> "单击选择文件，双击文件夹进入"
                        spec.mode == PickMode.Save && spec.extensions.isNotEmpty() ->
                            "会自动补上 ." + spec.extensions.first().trimStart('.').uppercase() + " 后缀"
                        spec.mode == PickMode.Save -> "保存到当前显示的文件夹"
                        else -> "双击文件夹可进入，单击可选中"
                    },
            )
        }
    }
}

/** 34dp 选择行：文件夹图标 + 名称，悬停高亮、选中主题色；单击选中、双击进入。 */
@Composable
private fun PickerRow(
    icon: ImageVector,
    label: String,
    detail: String = "",
    selected: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val foreground =
        when {
            tint != null -> tint
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier.fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(
                if (selected) selectedRowBackground() else Color.Transparent,
                shape,
            )
            .rowHoverHighlight(shape, enabled = !selected)
            .combinedClickable(
                interactionSource =
                    remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            Modifier.size(15.dp),
            if (tint != null) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else null,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank())
            Text(
                detail,
                fontSize = 10.sp,
                color =
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
    }
}

/** 30dp 快捷位置行：只有图标与名称，当前目录用主题色标出。 */
@Composable
private fun PickerPlaceRow(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 28.dp)
            .clip(shape)
            .background(
                if (active) selectedRowBackground() else Color.Transparent,
                shape,
            )
            .rowHoverHighlight(shape, enabled = !active)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (active) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            null,
            Modifier.size(14.dp),
            if (active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color =
                if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一次目录读取的结果：失败时只带一行说明，不向界面抛异常。 */
private data class PickerListing(
    val directories: List<Path> = emptyList(),
    val files: List<Path> = emptyList(),
    val error: String? = null,
)

/** 列出子目录与文件：读不到（权限、拔出磁盘、已删除）时返回错误行文本； 隐藏文件默认不显示，勾选后才出现。 */
private fun readDirectory(directory: Path, showHidden: Boolean): PickerListing {
    val entries =
        try {
            Files.newDirectoryStream(directory).use { stream -> stream.toList() }
        } catch (_: Exception) {
            return PickerListing(error = "无法读取此目录")
        }
    val directories = mutableListOf<Path>()
    val files = mutableListOf<Path>()
    for (entry in entries) {
        // 点开头的名字（.git、.nomedia 等）与系统隐藏属性都视为隐藏文件。
        val hidden =
            entry.fileName?.toString()?.startsWith(".") == true ||
                runCatching { entry.isHidden() }.getOrDefault(false)
        if (!showHidden && hidden) continue
        if (runCatching { entry.isDirectory() }.getOrDefault(false)) directories.add(entry)
        else files.add(entry)
    }
    val order = String.CASE_INSENSITIVE_ORDER
    return PickerListing(
        directories =
            directories.sortedWith(compareBy(order) { it.fileName?.toString().orEmpty() }),
        files = files.sortedWith(compareBy(order) { it.fileName?.toString().orEmpty() }),
    )
}

/** 扩展名过滤：`.jar` 与 `jar` 写法等价；没有扩展名限制时显示全部文件。 */
private fun matchesExtensions(path: Path, extensions: List<String>): Boolean {
    if (extensions.isEmpty()) return true
    val name = path.fileName?.toString().orEmpty()
    return extensions.any {
        val suffix = "." + it.trim().trimStart('.')
        name.endsWith(suffix, ignoreCase = true)
    }
}

/** 保存模式下自动补上调用方要求的后缀（例如 .zip / .log），与旧对话框的行为一致。 */
private fun saveNameFor(value: String, extensions: List<String>): String {
    if (extensions.isEmpty()) return value
    val suffixes = extensions.map { "." + it.trim().trimStart('.').lowercase() }
    return if (suffixes.any { value.lowercase().endsWith(it) }) value else value + suffixes.first()
}

/** 保存模式下的文件名检查：拒绝路径分隔符与 Windows 不允许的字符，避免确认后才失败。 */
private fun validPickName(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= 200 &&
        value != "." &&
        value != ".." &&
        value.none { it < ' ' || it in "<>:\"/\\|?*" }

/** 文件大小只用于列表右侧提示，读不到时留空。 */
private fun fileSizeLabel(path: Path): String = runCatching {
    val bytes = path.fileSize()
    when {
        bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}
    .getOrDefault("")

private fun homeDirectory(): Path = runCatching {
    Path.of(System.getProperty("user.home", "."))
}
    .getOrDefault(Path.of("."))
    .toAbsolutePath()
    .normalize()

private fun isDirectory(path: Path): Boolean = runCatching {
    path.isDirectory()
}
    .getOrDefault(false)

/** 从给定路径向上找到第一个存在的目录；整条路径都不存在时回落到主目录。 */
private fun nearestExistingDirectory(path: Path): Path {
    var cursor: Path? = runCatching { path.toAbsolutePath().normalize() }.getOrNull()
    while (cursor != null) {
        if (isDirectory(cursor)) return cursor
        cursor = cursor.parent
    }
    return homeDirectory()
}

/** 从盘符/根到当前目录的分段，用于面包屑。 */
private fun pathSegments(path: Path): List<Path> {
    val segments = ArrayDeque<Path>()
    var cursor: Path? = path.toAbsolutePath().normalize()
    while (cursor != null) {
        segments.addFirst(cursor)
        cursor = cursor.parent
    }
    return segments.toList()
}

private fun segmentLabel(path: Path): String =
    path.fileName?.toString()?.takeIf { it.isNotBlank() } ?: path.toString()

/** 主目录 / 桌面 / 文档 / 下载 + 所有磁盘根目录 + 调用方补充的目录；不存在的不显示，重复只留一次。 */
private fun mergeQuickPlaces(extra: List<Pair<String, Path>>): List<Pair<String, Path>> {
    val home = homeDirectory()
    val base = listOf("下载" to home.resolve("Downloads"))
    val seen = mutableSetOf<String>()
    return (base + drivePlaces() + extra).mapNotNull { (label, path) ->
        val normalized = runCatching { path.toAbsolutePath().normalize() }.getOrNull()
        if (normalized == null || !isDirectory(normalized)) return@mapNotNull null
        if (!seen.add(normalized.toString())) return@mapNotNull null
        label.ifBlank { segmentLabel(normalized) } to normalized
    }
}

/** 当前机器上的所有磁盘根（Windows 的 C:\ D:\ …，Linux 的 /），用于选择其他盘。 */
private fun drivePlaces(): List<Pair<String, Path>> = runCatching {
    File.listRoots().orEmpty().toList()
}
    .getOrDefault(emptyList())
    .map { root ->
        val path = root.path
        val label = if (path.endsWith("\\")) path.trimEnd('\\') else path
        label.ifBlank { path } to root.toPath()
    }
