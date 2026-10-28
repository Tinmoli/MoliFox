// 负责资源安装计划、兼容提示和安装确认。

package dev.molifox.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Path
import kotlinx.coroutines.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentInstallDialog(
    hit: ContentHit,
    instanceId: String,
    actions: UiActions,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
    source: ContentSource? = null,
    initialVersionId: String = "",
    relatedSources: List<ContentSource> = emptyList(),
    destination: Path? = null,
) {
    var selected by remember(hit.id, instanceId) { mutableStateOf(emptySet<String>()) }
    var plan by remember(hit.id, instanceId) { mutableStateOf<ContentInstallPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var details by remember(hit.id, instanceId) { mutableStateOf<ContentDetails?>(null) }
    var versionId by remember(hit.id, instanceId) { mutableStateOf(initialVersionId) }
    var channel by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf(false) }
    var versionMenu by remember { mutableStateOf(false) }
    var changelog by remember(hit.id, instanceId) { mutableStateOf<ContentVersion?>(null) }
    var changelogError by remember(hit.id, instanceId) { mutableStateOf<String?>(null) }
    val instanceRoot =
        remember(instanceId) {
            runCatching { actions.controller.store.instancePath(instanceId) }.getOrNull()
        }
    // 用户在下载对话框里确认过的目录 → 实例内的资源子目录；无法映射时沿用计划里的默认目录。
    val destinationFolder = contentFolderOf(instanceRoot, destination)
    LaunchedEffect(versionId) {
        changelog = null
        changelogError = null
        if (versionId.isBlank()) return@LaunchedEffect
        try {
            changelog =
                withContext(Dispatchers.IO) {
                    actions.controller.content.catalog.versionDetails(hit.id, versionId, instanceId)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            changelogError = failure.message
        }
    }
    LaunchedEffect(hit.id, instanceId, selected, retry, versionId, destinationFolder) {
        loading = true
        error = null
        plan = null
        try {
            withContext(Dispatchers.IO) {
                if (details == null)
                    details = actions.controller.content.catalog.details(hit.id, instanceId)
                if (versionId.isBlank()) {
                    val versions = requireNotNull(details).versions
                    versionId =
                        (versions.firstOrNull { it.channel == "release" } ?: versions.firstOrNull())
                            ?.id
                            .orEmpty()
                }
                require(versionId.isNotBlank()) { "没有兼容当前游戏版本与加载器的版本" }
                val planned =
                    actions.controller.content.planContent(
                        hit,
                        instanceId,
                        selected,
                        versionId,
                        (relatedSources + listOfNotNull(source)).distinctBy {
                            Triple(it.projectId, it.folder, it.filename)
                        },
                    )
                // 用户确认的目录就是实际写入的目录：只替换目录名，依赖、哈希与账本校验保持原样。
                plan =
                    if (destinationFolder == null) planned
                    else
                        planned.copy(
                            files = planned.files.map { it.copy(folder = destinationFolder) }
                        )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            if (isActive) loading = false
        }
    }
    Modal(
        hit.title,
        "${actions.controller.store.instance(instanceId).name} · 项目详情与兼容版本",
        onDismiss = onDismiss,
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton({ retry++ }) { Text("重试") }
        }
        if (destination != null) {
            if (destinationFolder == null)
                MutedText("所选文件夹不在实例内，资源会安装到实例的 " + contentFolderForType(hit.type) + " 目录。")
            else MutedText("下载位置 · " + destination)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(hit.description, style = MaterialTheme.typography.bodyMedium)
            details?.let { info ->
                MutedText(
                    "${info.providerName} · " +
                        "${info.license.ifBlank { "未声明许可" }} · " +
                        "${info.versions.size} 个兼容版本"
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.links.forEach { link ->
                        TextButton(
                            {
                                actions.run { actions.controller.platform.openUrl(link.url) }
                            },
                            enabled = !loading,
                        ) {
                            Text(link.title, fontSize = 12.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "release" to "正式版", "beta" to "测试版", "alpha" to "预览版")
                        .forEach { (key, title) ->
                            FilterChip(channel == key, { channel = key }, label = { Text(title) })
                        }
                }
                val versions = info.versions.filter { channel == "all" || it.channel == channel }
                val chosen = info.versions.find { it.id == versionId }
                Box {
                    OutlinedButton(
                        { versionMenu = true },
                        modifier = Modifier.semantics { contentDescription = "选择资源版本" },
                        enabled = !loading,
                    ) {
                        Text("选择版本：${chosen?.number?.ifBlank { chosen.name } ?: "无兼容版本"}")
                    }
                    DropdownMenu(
                        versionMenu,
                        { versionMenu = false },
                        Modifier.heightIn(max = 240.dp),
                    ) {
                        versions.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${item.number.ifBlank { item.name }} · ${item.channel} · ${item.published.take(10)}"
                                    )
                                },
                                onClick = {
                                    versionId = item.id
                                    selected = emptySet()
                                    versionMenu = false
                                },
                            )
                        }
                        if (versions.isEmpty())
                            DropdownMenuItem({ Text("此通道没有兼容版本") }, {}, enabled = false)
                    }
                }
                chosen?.let {
                    Text(
                        "${it.name} · ${it.channel} · ${it.published.take(10)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val log =
                        changelog?.takeIf { log -> log.id == it.id }?.changelog ?: it.changelog
                    if (log.isNotBlank()) {
                        Text("更新日志", style = MaterialTheme.typography.titleSmall)
                        Text(log, style = MaterialTheme.typography.bodySmall)
                    }
                    if (changelogError != null) MutedText("更新日志读取失败；不影响安装检查。")
                }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起项目介绍" else "展开项目介绍") }
                if (expanded) Text(info.body, style = MaterialTheme.typography.bodySmall)
                if (source?.locked == true) MutedText("此版本已锁定；更换版本前请先在更新管理中解锁。")
                HorizontalDivider()
            }
            plan?.let { current ->
                Text("安装内容 · ${current.files.size}", style = MaterialTheme.typography.titleSmall)
                current.files.forEach { file ->
                    SettingRow(file.title, "${file.folder}/${file.filename}") { Tag("已匹配") }
                }
                if (current.optional.isNotEmpty()) {
                    HorizontalDivider()
                    Text("可选前置", style = MaterialTheme.typography.titleSmall)
                    current.optional.forEach { dependency ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                dependency.id in selected,
                                { enabled ->
                                    selected =
                                        if (enabled) selected + dependency.id
                                        else selected - dependency.id
                                },
                                enabled = !loading,
                            )
                            Text(dependency.title)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("取消") }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                "开始安装",
                enabled = !loading && error == null && plan != null && source?.locked != true,
                onClick = {
                    scope.launch {
                        try {
                            actions.controller.installContent(requireNotNull(plan))
                            onInstalled()
                        } catch (failure: Exception) {
                            error = failure.message
                        }
                    }
                },
            )
        }
    }
}
