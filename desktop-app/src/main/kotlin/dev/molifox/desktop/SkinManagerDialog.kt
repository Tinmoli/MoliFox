// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责皮肤库、三维预览和正版上传界面。

package dev.molifox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.Account
import dev.molifox.core.AccountType
import dev.molifox.core.LibrarySkin
import dev.molifox.core.SkinService
import dev.molifox.core.SkinVariant
import kotlinx.coroutines.launch

/** Shared account skin tools stay on the start page, preserving the four main navigation items. */
@Composable
fun SkinManagerDialog(
    account: Account,
    actions: UiActions,
    onUpdated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val service = actions.controller.skins
    val scope = rememberCoroutineScope()
    var entries by remember(account.id) { mutableStateOf<List<LibrarySkin>>(emptyList()) }
    var selectedId by remember(account.id) { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var importName by remember { mutableStateOf("") }
    var importVariant by remember { mutableStateOf(SkinVariant.Classic) }
    var currentVariant by
        remember(account.id, account.skinVariant) { mutableStateOf(account.skinVariant) }
    var overlays by remember { mutableStateOf(true) }
    var confirmUpload by remember { mutableStateOf<LibrarySkin?>(null) }
    var confirmRemove by remember { mutableStateOf<LibrarySkin?>(null) }
    val selected = entries.find { it.id == selectedId }

    fun act(
        refreshAvatar: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                skinAttempt { action() }
                    .onSuccess {
                        revision++
                        if (refreshAvatar) onUpdated()
                    }.onFailure { failure -> error = skinErrorMessage(failure) }
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(account.id, revision) {
        skinAttempt { service.library.list() }
            .onSuccess { entries = it }
            .onFailure { failure -> error = failure.message }
    }
    var textureError by remember { mutableStateOf<String?>(null) }
    val image by
        produceState<ImageBitmap?>(
            null,
            account.id,
            account.skinUrl,
            account.localSkinId,
            selectedId,
            revision,
        ) {
            value = null
            textureError = null
            skinAttempt { loadSkinBitmap(service, account, selectedId) }
                .onSuccess { value = it }
                .onFailure { failure -> textureError = failure.message }
        }
    Modal(
        "皮肤管理",
        dialogSubtitle(account),
        width = 640.dp,
        onDismiss = { dismissIfIdle(busy, onDismiss) },
    ) {
        if (account.type == AccountType.Offline) {
            OfflineSkinNotice(account, revision, service)
        }
        Row(
            Modifier.fillMaxWidth().height(250.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SkinPreviewPane(
                image = image,
                variant = previewVariant(selected, currentVariant),
                overlays = overlays,
                onOverlaysChange = { overlays = it },
                textureError = textureError,
            )
            SkinLibraryPane(
                entries = entries,
                selectedId = selectedId,
                busy = busy,
                onSelect = { selectedId = it },
                onRemove = { confirmRemove = it },
            )
        }
        SkinImportRow(
            name = importName,
            onNameChange = { importName = it.take(100) },
            variant = importVariant,
            onVariantChange = { importVariant = it },
            enabled = !busy,
            onImport = {
                act(refreshAvatar = account.type == AccountType.Offline) {
                    val imported =
                        importSkinFromPicker(service, account, actions, importName, importVariant)
                            ?: return@act
                    selectedId = imported.entry.id
                    message = imported.message
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    act {
                        exportSkin(service, account, selected, actions) {
                            message = "皮肤已导出"
                        }
                    }
                },
                enabled = canExport(busy, selected, image),
            ) {
                Text("导出 PNG")
            }
            if (account.type == AccountType.Microsoft) {
                MicrosoftSkinActions(
                    busy = busy,
                    selected = selected,
                    onRefreshProfile = {
                        act(refreshAvatar = true) {
                            val profile = service.refreshProfile(account.id)
                            currentVariant = profile.variant
                            selectedId = null
                            message = "正版档案已刷新"
                        }
                    },
                    onUpload = { confirmUpload = selected },
                )
            } else {
                OfflineSkinActions(
                    account = account,
                    busy = busy,
                    selected = selected,
                    onUseLocalPreview = { skin ->
                        act(refreshAvatar = true) {
                            service.useOfflinePreview(account.id, skin.id)
                            message = "已更新本地预览"
                        }
                    },
                    onRestoreDefault = {
                        act(refreshAvatar = true) {
                            service.useOfflinePreview(account.id, null)
                            selectedId = null
                            message = "已恢复默认头像"
                        }
                    },
                )
            }
        }
        SkinDialogStatus(error, message, busy, onDismiss)
    }
    confirmUpload?.let { entry ->
        Modal(
            "上传正版皮肤",
            "将修改 ${account.name} 的 Minecraft Java 版皮肤。",
            onDismiss = { confirmUpload = null },
        ) {
            Text("上传「${entry.name}」（${entry.variant.title}）？这会更新微软账户的在线角色皮肤。")
            DialogActions(
                "确认上传",
                onConfirm = {
                    confirmUpload = null
                    act(refreshAvatar = true) {
                        val result = service.upload(account.id, entry.id)
                        selectedId = null
                        currentVariant = result.variant
                        message = uploadResultMessage(result.textureUrl)
                    }
                },
                onCancel = { confirmUpload = null },
            )
        }
    }
    confirmRemove?.let { entry ->
        Modal("移除本地皮肤", onDismiss = { confirmRemove = null }) {
            Text("从本地皮肤库移除「${entry.name}」？不会删除原始导入文件或修改正版皮肤。")
            DialogActions(
                "移除",
                onConfirm = {
                    confirmRemove = null
                    act {
                        service.library.removeSkin(entry.id)
                        selectedId = selectedAfterRemove(selectedId, entry.id)
                    }
                },
                onCancel = { confirmRemove = null },
                danger = true,
            )
        }
    }
}

/**
 * 皮肤导入行：名称输入框可伸缩，手臂型号与「导入皮肤」固定宽高。
 *
 * 常规宽度下三项同一行并按输入框底边对齐（输入框带 11sp 标签，比 34dp 控件高， 用 `Alignment.Bottom` 让三者控制区真正齐平）；只有弹窗可用宽度低于 420dp
 * 时才折成「名称一行、型号与按钮一行」，不在常规宽度换行。
 */
@Composable
private fun SkinImportRow(
    name: String,
    onNameChange: (String) -> Unit,
    variant: SkinVariant,
    onVariantChange: (SkinVariant) -> Unit,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    name,
                    onNameChange,
                    Modifier.fillMaxWidth(),
                    label = "皮肤名称",
                    placeholder = "留空使用文件名",
                    enabled = enabled,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkinVariantDropdown(variant, onVariantChange, enabled, Modifier.width(140.dp))
                    SecondaryButton(onImport, enabled = enabled) { Text("导入皮肤") }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                CompactTextField(
                    name,
                    onNameChange,
                    Modifier.weight(1f),
                    label = "皮肤名称",
                    placeholder = "留空使用文件名",
                    enabled = enabled,
                )
                SkinVariantDropdown(variant, onVariantChange, enabled, Modifier.width(140.dp))
                SecondaryButton(onImport, enabled = enabled) { Text("导入皮肤") }
            }
        }
    }
}

@Composable
private fun SkinVariantDropdown(
    variant: SkinVariant,
    onVariantChange: (SkinVariant) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    SimpleDropdown(
        variant.name,
        SkinVariant.entries.map { it.name to it.title },
        { onVariantChange(SkinVariant.valueOf(it)) },
        modifier,
        enabled = enabled,
    )
}

/** 离线账户的说明：皮肤只写本地目录，不伪造上传。 */
@Composable
private fun OfflineSkinNotice(
    account: Account,
    revision: Int,
    service: SkinService,
) {
    val libraryDirectory = service.library.directory
    val customSkinLoader = remember(account.id, revision) { service.customSkinLoaderDirectory() }
    MutedText(
        "离线皮肤不会上传，只写入本地目录（$libraryDirectory），需皮肤模组才会在游戏中显示" +
            (customSkinLoader?.let { "；CustomSkinLoader 已同步到 $it" } ?: ""),
    )
}

/** 左侧三维预览：拖动旋转、外层开关与加载失败提示。 */
@Composable
private fun SkinPreviewPane(
    image: ImageBitmap?,
    variant: SkinVariant,
    overlays: Boolean,
    onOverlaysChange: (Boolean) -> Unit,
    textureError: String?,
) {
    Column(Modifier.width(180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)),
            contentAlignment = Alignment.Center,
        ) {
            SkinPreview(
                image,
                variant,
                overlays,
                Modifier.fillMaxSize().padding(12.dp),
            )
        }
        textureError?.let {
            Text(
                "皮肤加载失败，暂用默认模型：$it",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("拖动旋转", fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            CompactSwitch(overlays, onOverlaysChange)
            Spacer(Modifier.width(4.dp))
            Text("外层", fontSize = 11.sp)
        }
    }
}

/** 右侧皮肤库列表：选中项高亮，每一项都能移除。 */
@Composable
private fun RowScope.SkinLibraryPane(
    entries: List<LibrarySkin>,
    selectedId: String?,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    onRemove: (LibrarySkin) -> Unit,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("本地皮肤库", fontWeight = FontWeight.SemiBold)
        TextButton({ onSelect(null) }, enabled = !busy) { Text("查看当前账户皮肤") }
        if (entries.isEmpty()) MutedText("还没有皮肤，使用下方按钮导入 PNG。")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (selectedId == entry.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ).clickable(enabled = !busy) { onSelect(entry.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            entry.variant.title,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SmallIconButton(
                        Icons.Outlined.DeleteOutline,
                        "移除皮肤 " + entry.name,
                        { onRemove(entry) },
                    )
                }
            }
        }
    }
}

/** 正版账户的两个操作：刷新档案与上传到正版账户。 */
@Composable
private fun MicrosoftSkinActions(
    busy: Boolean,
    selected: LibrarySkin?,
    onRefreshProfile: () -> Unit,
    onUpload: () -> Unit,
) {
    SecondaryButton(onRefreshProfile, enabled = !busy) { Text("刷新正版档案") }
    PrimaryButton(
        "上传到正版账户",
        Icons.Outlined.CloudUpload,
        enabled = !busy && selected != null,
        onClick = onUpload,
    )
}

/** 离线账户的两个操作：用于本地预览与恢复默认头像。 */
@Composable
private fun OfflineSkinActions(
    account: Account,
    busy: Boolean,
    selected: LibrarySkin?,
    onUseLocalPreview: (LibrarySkin) -> Unit,
    onRestoreDefault: () -> Unit,
) {
    PrimaryButton("用于本地预览", Icons.Outlined.Check, enabled = !busy && selected != null) {
        val selectedSkin = selected ?: return@PrimaryButton
        onUseLocalPreview(selectedSkin)
    }
    SecondaryButton(onRestoreDefault, enabled = !busy && account.localSkinId.isNotBlank()) {
        Text("恢复默认")
    }
}

/** 弹窗底部的状态文案、进度与完成按钮。 */
@Composable
private fun SkinDialogStatus(
    error: String?,
    message: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    message?.let { MutedText(it) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    DialogActions("完成", onConfirm = onDismiss, enabled = !busy)
}

/** 操作失败的提示：服务层通常带可读文案，缺失时给通用提示。 */
private fun skinErrorMessage(failure: Throwable): String = failure.message ?: "皮肤操作失败"
