// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例模组列表、筛选与更新操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateStatus
import dev.molifox.core.domain.mods.completeModUpdateReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.job
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 模组列表：工具行（刷新/导入/下载/打开文件夹）+ 筛选芯片 + 52dp 行（版本与来源/更新/开关/删除）+ 骨架 + 空态。 */
@Composable
internal fun InstanceMods(
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    modifier: Modifier,
    onDiscover: () -> Unit,
) {
    var mods by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var updatesOpen by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModFile?>(null) }
    var deletingMod by remember { mutableStateOf<ModFile?>(null) }
    var chip by remember(instance.id) { mutableStateOf(ContentChip.All) }
    val checking = updates.modsChecking
    val updateReport = updates.mods
    val updateError = updates.modsError
    val checkScope = rememberCoroutineScope()
    // 本次页面发起的更新任务；完成/失败后刷新列表并更新报告。
    var applyingJob by remember(instance.id) { mutableStateOf<Pair<String, ModUpdateItem>?>(null) }
    var confirmingUpdate by remember { mutableStateOf<ModUpdateItem?>(null) }
    val liveState by actions.controller.state.collectAsState()
    // 检查只读网络与 JAR 声明：自动检查由页面发起，手动重查只取消它自己的请求。
    val startCheck: () -> Unit = { updates.checkMods(checkScope) }
    val startUpdate: (ModUpdateItem) -> Unit = { item ->
        updates.modsError = null
        runCatching { actions.controller.updateMod(instance.id, item) }
            .onSuccess { job -> applyingJob = job to item }
            .onFailure { failure -> updates.modsError = failure.message }
    }
    if (updatesOpen) {
        ContentUpdatesDialog(instance.id, actions) {
            updatesOpen = false
            refresh++
        }
    }
    detailMod?.let { mod -> ModDetailsDialog(mod) { detailMod = null } }
    // 名称搜索得到的匹配先确认来源项目，避免把同名模组替换掉。
    confirmingUpdate?.let { item ->
        ModUpdateConfirmation(
            item,
            onConfirm = {
                confirmingUpdate = null
                startUpdate(item)
            },
            onDismiss = { confirmingUpdate = null },
        )
    }
    // 删除模组会从 mods 目录移入回收站，先确认再执行。
    deletingMod?.let { mod ->
        DeleteModConfirmation(
            mod,
            onConfirm = {
                val filename = mod.filename
                deletingMod = null
                actions.run {
                    actions.controller.requireIdle(instance.id)
                    actions.controller.content.deleteMod(instance.id, filename)
                    withContext(Dispatchers.Swing) { refresh++ }
                }
            },
            onDismiss = { deletingMod = null },
        )
    }
    LaunchedEffect(instance.id, refresh, liveState.jobs.map { it.status }) {
        loading = true
        error = null
        try {
            mods = withContext(Dispatchers.IO) { actions.controller.content.listMods(instance.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            loading = false
        }
    }
    // 应用更新是任务中心里的后台任务；结束后刷新列表，并把旧文件对应的报告项改为已是最新。
    LaunchedEffect(applyingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val pending = applyingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == pending.first } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Completed -> {
                if (pending.second.candidate != null) {
                    updates.mods = completeModUpdateReport(updateReport, pending.second)
                }
                applyingJob = null
                refresh++
            }
            JobStatus.Failed, JobStatus.Cancelled -> {
                updates.modsError =
                    job.error
                        ?: if (job.status == JobStatus.Cancelled) "更新已取消" else "更新失败"
                applyingJob = null
                refresh++
            }
            else -> Unit
        }
    }
    val busy = actions.controller.isInstanceBusy(instance.id)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            flexible = { ToolbarSearchField(query, { query = it }, "搜索已安装模组") },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            importAction =
                ToolbarAction("导入模组", icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val files =
                            actions.choose("导入本地模组", multiple = true, extensions = listOf("jar"))
                        actions.controller.content.importMods(instance.id, files)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                },
            downloads =
                listOf(
                    ToolbarAction(
                        "下载模组",
                        icon = Icons.Outlined.Add,
                        style = ToolbarActionStyle.Filled,
                    ) { onDiscover() },
                ),
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, "mods") }
                },
        )
        val reportByFile = updateReport?.items?.associateBy { it.filename }.orEmpty()
        val activeNames = mods.map { it.filename }.toSet()
        val updateCount =
            updateReport?.items.orEmpty().count {
                it.status == ModUpdateStatus.Updatable && it.filename in activeNames
            }
        val unknownCount =
            updateReport?.items.orEmpty().count {
                it.status == ModUpdateStatus.Unknown && it.filename in activeNames
            }
        // 芯片按更新状态筛选：「未找到更新」只收「无法确认」，「已是最新」只出现在「全部」。
        // 数量与芯片一致：可更新数量、无法确认数量都不含已是最新的条目。
        val statusText =
            when {
                checking -> "正在检查更新…"
                updateReport == null -> ""
                updateCount > 0 ->
                    "$updateCount 项可更新" +
                        if (unknownCount > 0) " · $unknownCount 项无法确认" else ""
                unknownCount > 0 -> "$unknownCount 项无法确认"
                else -> "全部为最新"
            }
        // 芯片行常驻：即使检查失败或被取消，也能继续筛选并回到「版本管理」。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentChipRow(
                selected = chip,
                visible =
                    listOf(
                        ContentChip.All,
                        ContentChip.Disabled,
                        ContentChip.Updatable,
                        ContentChip.NoUpdate,
                    ),
            ) { chip = it }
            if (statusText.isNotBlank()) {
                Text(
                    statusText,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color =
                        if (updateCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(
                { updates.checkAll(checkScope, refresh = true) },
                enabled = !actions.controller.isInstanceBusy(instance.id) && !checking,
            ) {
                Text(if (checking) "正在检查…" else "检查更新", fontSize = 12.sp)
            }
            TextButton(
                { updatesOpen = true },
                enabled = !actions.controller.isInstanceBusy(instance.id),
            ) {
                Text("版本管理", fontSize = 12.sp)
            }
        }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        updateError?.let { failure ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    failure,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    {
                        updates.modsError = null
                        startCheck()
                    },
                    enabled = !actions.controller.isInstanceBusy(instance.id) && !checking,
                ) {
                    Text("重新检查", fontSize = 12.sp)
                }
            }
        }
        val filtered =
            mods
                .filter {
                    query.isBlank() ||
                        it.filename.contains(query, true) ||
                        it.metadata.title.contains(query, true) ||
                        it.metadata.entries.any { entry -> entry.id.contains(query, true) }
                }.filter { mod ->
                    when (chip) {
                        ContentChip.All -> true
                        ContentChip.Disabled -> !mod.enabled
                        ContentChip.Updatable ->
                            reportByFile[mod.filename]?.status == ModUpdateStatus.Updatable
                        // 「未找到更新」只收「无法确认」的条目；已是最新的只在「全部」里出现。
                        ContentChip.NoUpdate ->
                            reportByFile[mod.filename]?.status == ModUpdateStatus.Unknown
                    }
                }
        // 模组图标从 JAR 自带资源里取，缓存在启动器 cache/mod-icons；行内按 JAR 路径 + 修改时间复用。
        val modsDirectory =
            runCatching {
                actions.controller.store
                    .instancePath(instance.id)
                    .resolve("mods")
            }.getOrNull()
        // 首次读取还没有数据时给骨架；刷新时保留旧列表，只在顶部显示一条细进度。
        if (loading && mods.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        when {
            loading && mods.isEmpty() -> ListSkeleton()
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取模组列表", error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            mods.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "还没有安装模组",
                        "从资源库下载，或导入本地 JAR 文件。",
                        Icons.Outlined.Extension,
                    ) {
                        PrimaryButton("下载模组", Icons.Outlined.Add, onClick = onDiscover)
                    }
                }
            filtered.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        if (chip != ContentChip.All && query.isBlank()) {
                            "没有「${chip.title}」的模组"
                        } else {
                            "没有匹配的模组"
                        },
                        if (chip != ContentChip.All && query.isBlank()) {
                            "切换到「全部」查看所有模组。"
                        } else {
                            "换个关键词试试"
                        },
                        Icons.Outlined.SearchOff,
                    ) {
                        TextButton({
                            query = ""
                            chip = ContentChip.All
                        }) {
                            Text("清除筛选")
                        }
                    }
                }
            else ->
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.filename }) { mod ->
                        ModRow(
                            mod,
                            jar = modsDirectory?.resolve(mod.filename),
                            readIcon = actions.controller::modIconBytes,
                            onIconFailure = {
                                actions.controller.store.log("模组图标解码失败：${it.message}", true)
                            },
                            modifier = Modifier.animateItem(),
                            update = reportByFile[mod.filename],
                            busy =
                                actions.controller.isInstanceBusy(instance.id) ||
                                    applyingJob != null,
                            onDetail = { detailMod = mod },
                            onUpdate = {
                                reportByFile[mod.filename]?.let { item ->
                                    if (item.possibleMatch) {
                                        confirmingUpdate = item
                                    } else {
                                        startUpdate(item)
                                    }
                                }
                            },
                            onToggle = {
                                actions.run {
                                    actions.controller.requireIdle(instance.id)
                                    actions.controller.content.toggleMod(instance.id, mod.filename)
                                    withContext(Dispatchers.Swing) { refresh++ }
                                }
                            },
                            onDelete = { deletingMod = mod },
                        )
                    }
                }
        }
        if (!loading && mods.isNotEmpty()) {
            Text(
                "共 " + mods.size + " 个文件 · 已启用 " + mods.count { it.enabled },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
