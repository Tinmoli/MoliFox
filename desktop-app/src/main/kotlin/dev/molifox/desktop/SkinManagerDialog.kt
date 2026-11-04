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
import dev.molifox.desktop.MoliTextButton as TextButton
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
import dev.molifox.core.MinecraftCape
import dev.molifox.core.localizedName
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Shared account skin tools stay on the start page, preserving the four main navigation items. */
@Composable
fun SkinManagerDialog(
    account: Account,
    actions: UiActions,
    onUpdated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
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
    var capeEnabled by remember(account.id) { mutableStateOf(true) }
    var capes by remember(account.id) { mutableStateOf<List<MinecraftCape>>(emptyList()) }
    var capeProfileLoaded by remember(account.id) { mutableStateOf(false) }
    var capeProfileLoading by remember(account.id) { mutableStateOf(account.type == AccountType.Microsoft) }
    var confirmUpload by remember { mutableStateOf<LibrarySkin?>(null) }
    var authlibUploadSupported by remember(account.id) { mutableStateOf<Boolean?>(null) }
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
                resourceAttempt { action() }
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
        resourceAttempt { service.library.list() }
            .onSuccess { entries = it }
            .onFailure { failure -> error = failure.message }
    }
    LaunchedEffect(account.id) {
        if (account.type == AccountType.Authlib) {
            resourceAttempt { service.supportsAuthlibSkinUpload(account.id) }
                .onSuccess { authlibUploadSupported = it }
                .onFailure { error = skinErrorMessage(it) }
        }
        if (account.type == AccountType.Microsoft) {
            try {
                resourceAttempt { service.refreshProfile(account.id) }
                    .onSuccess {
                        capes = it.capes
                        capeProfileLoaded = true
                        currentVariant = it.variant
                    }
                    .onFailure { error = skinErrorMessage(it) }
            } finally {
                capeProfileLoading = false
            }
        }
    }
    val activeCape = capes.firstOrNull { it.active }
    val capeImage by produceState<BufferedImage?>(null, activeCape, capeEnabled) {
        value = null
        if (capeEnabled && activeCape != null) {
            resourceAttempt {
                val bytes = service.capeTexture(activeCape)
                withContext(Dispatchers.IO) {
                    ImageIO.read(ByteArrayInputStream(bytes))?.takeIf { it.width == 64 && it.height == 32 }
                }
            }.onSuccess { value = it }.onFailure { error = skinErrorMessage(it) }
        }
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
            resourceAttempt { loadSkinBitmap(service, account, selectedId) }
                .onSuccess { value = it }
                .onFailure { failure -> textureError = failure.message }
        }
    Modal(
        strings.t("dialogs.skin.title"),
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
                cape = capeImage,
                capeEnabled = capeEnabled,
                onCapePreviewChange = if (account.type == AccountType.Microsoft) { { capeEnabled = it } } else null,
            )
            SkinLibraryPane(
                entries = entries,
                selectedId = selectedId,
                busy = busy,
                onSelect = { selectedId = it },
                onRemove = { confirmRemove = it },
            )
        }
        if (account.type == AccountType.Microsoft) {
            SettingRow(strings.t("dialogs.skin.cape.title")) {
                if (capeProfileLoaded) {
                    SimpleDropdown(
                        activeCape?.id.orEmpty(),
                        listOf("" to strings.t("dialogs.skin.cape.none")) + capes.map { it.id to it.localizedName(strings) },
                        { id ->
                            if (id != activeCape?.id.orEmpty()) act {
                                capes = service.changeCape(account.id, id.takeIf { it.isNotBlank() }).capes
                            }
                        },
                        Modifier.width(200.dp),
                        enabled = !busy && !capeProfileLoading,
                    )
                } else {
                    MutedText(strings.t(if (capeProfileLoading) "dialogs.skin.cape.loading" else "dialogs.skin.cape.notLoaded"))
                }
            }
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
                            message = strings.t("dialogs.skin.exported")
                        }
                    }
                },
                enabled = canExport(busy, selected, image),
            ) {
                Text(strings.t("dialogs.skin.exportPng"))
            }
            if (account.type == AccountType.Microsoft) {
                MicrosoftSkinActions(
                    busy = busy || capeProfileLoading,
                    selected = selected,
                    onRefreshProfile = {
                        act(refreshAvatar = true) {
                            val profile = service.refreshProfile(account.id)
                            currentVariant = profile.variant
                            capes = profile.capes
                            capeProfileLoaded = true
                            selectedId = null
                            message = strings.t("dialogs.skin.profileRefreshed")
                        }
                    },
                    onUpload = { confirmUpload = selected },
                )
            } else if (account.type == AccountType.Authlib) {
                if (authlibUploadSupported == true) {
                    PrimaryButton(
                        strings.t("dialogs.skin.site.upload"),
                        Icons.Outlined.CloudUpload,
                        enabled = !busy && selected != null,
                        onClick = { confirmUpload = selected },
                    )
                } else if (authlibUploadSupported == false) {
                    MutedText(strings.t("dialogs.skin.site.unsupported"))
                }
            } else {
                OfflineSkinActions(
                    account = account,
                    busy = busy,
                    selected = selected,
                    onUseLocalPreview = { skin ->
                        act(refreshAvatar = true) {
                            service.useOfflinePreview(account.id, skin.id)
                            message = strings.t("dialogs.skin.localPreviewUpdated")
                        }
                    },
                    onRestoreDefault = {
                        act(refreshAvatar = true) {
                            service.useOfflinePreview(account.id, null)
                            selectedId = null
                            message = strings.t("dialogs.skin.defaultRestored")
                        }
                    },
                )
            }
        }
        SkinDialogStatus(error, message, busy, onDismiss)
    }
    confirmUpload?.let { entry ->
        Modal(
            strings.t("dialogs.skin.uploadTitle"),
            strings.t(if (account.type == AccountType.Authlib) "dialogs.skin.site.confirm" else "dialogs.skin.uploadMessage", account.name),
            onDismiss = { confirmUpload = null },
        ) {
            Text(strings.t("dialogs.skin.uploadConfirm", entry.name, entry.variant.title))
            DialogActions(
                strings.t("dialogs.skin.uploadConfirmAction"),
                onConfirm = {
                    confirmUpload = null
                    act(refreshAvatar = true) {
                        if (account.type == AccountType.Authlib) {
                            service.uploadAuthlibSkin(account.id, entry.id)
                            currentVariant = entry.variant
                            message = strings.t("dialogs.skin.site.uploaded")
                        } else {
                            val result = service.upload(account.id, entry.id)
                            selectedId = null
                            currentVariant = result.variant
                            message = uploadResultMessage(result.textureUrl)
                        }
                    }
                },
                onCancel = { confirmUpload = null },
            )
        }
    }
    confirmRemove?.let { entry ->
        Modal(strings.t("dialogs.skin.removeTitle"), onDismiss = { confirmRemove = null }) {
            Text(strings.t("dialogs.skin.removeMessage", entry.name))
            DialogActions(
                strings.t("dialogs.common.remove"),
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
    val strings = LocalStrings.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    name,
                    onNameChange,
                    Modifier.fillMaxWidth(),
                    label = strings.t("dialogs.skin.name"),
                    placeholder = strings.t("dialogs.skin.namePlaceholder"),
                    enabled = enabled,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkinVariantDropdown(variant, onVariantChange, enabled, Modifier.width(140.dp))
                    SecondaryButton(onImport, enabled = enabled) { Text(strings.t("dialogs.skin.import")) }
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
                    label = strings.t("dialogs.skin.name"),
                    placeholder = strings.t("dialogs.skin.namePlaceholder"),
                    enabled = enabled,
                )
                SkinVariantDropdown(variant, onVariantChange, enabled, Modifier.width(140.dp))
                SecondaryButton(onImport, enabled = enabled) { Text(strings.t("dialogs.skin.import")) }
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
    val strings = LocalStrings.current
    val libraryDirectory = service.library.directory
    val customSkinLoader = remember(account.id, revision) { service.customSkinLoaderDirectory() }
    MutedText(
        strings.t("dialogs.skin.offlineNote", libraryDirectory) +
            (customSkinLoader?.let { strings.t("dialogs.skin.customSkinLoaderSynced", it) } ?: ""),
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
    cape: BufferedImage?,
    capeEnabled: Boolean,
    onCapePreviewChange: ((Boolean) -> Unit)?,
) {
    val strings = LocalStrings.current
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
                cape = cape,
            )
        }
        textureError?.let {
            Text(
                strings.t("dialogs.skin.loadFailed", it),
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
        Text(strings.t("dialogs.skin.dragRotate"), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(strings.t("dialogs.skin.outerLayer"), fontSize = 11.sp)
            CompactSwitch(overlays, onOverlaysChange)
        }
        onCapePreviewChange?.let { onChange ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.t("dialogs.skin.cape.preview"), fontSize = 11.sp)
                CompactSwitch(capeEnabled, onChange)
            }
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
    val strings = LocalStrings.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings.t("dialogs.skin.library"), fontWeight = FontWeight.SemiBold)
        TextButton({ onSelect(null) }, enabled = !busy) { Text(strings.t("dialogs.skin.viewCurrent")) }
        if (entries.isEmpty()) MutedText(strings.t("dialogs.skin.empty"))
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
                        strings.t("dialogs.skin.removeLabel", entry.name),
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
    val strings = LocalStrings.current
    SecondaryButton(onRefreshProfile, enabled = !busy) { Text(strings.t("dialogs.skin.refreshProfile")) }
    PrimaryButton(
        strings.t("dialogs.skin.uploadToOfficial"),
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
    val strings = LocalStrings.current
    PrimaryButton(
        strings.t("dialogs.skin.useLocalPreview"),
        Icons.Outlined.Check,
        enabled = !busy && selected != null,
    ) {
        val selectedSkin = selected ?: return@PrimaryButton
        onUseLocalPreview(selectedSkin)
    }
    SecondaryButton(onRestoreDefault, enabled = !busy && account.localSkinId.isNotBlank()) {
        Text(strings.t("dialogs.skin.restoreDefault"))
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
    val strings = LocalStrings.current
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    message?.let { MutedText(it) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    DialogActions(strings.t("dialogs.common.done"), onConfirm = onDismiss, enabled = !busy)
}

/** 操作失败的提示：服务层通常带可读文案，缺失时给通用提示。 */
private fun skinErrorMessage(failure: Throwable): String {
    java.util.logging.Logger.getLogger("MoliFox.skin").warning("Skin operation: ${failure.javaClass.simpleName}: ${failure.message}")
    return when (failure) {
        is dev.molifox.core.ServiceHttpException -> tr("dialogs.skin.httpFailed", failure.statusCode,
            tr(when (failure.statusCode) {
                401 -> "dialogs.skin.http.auth"
                403 -> "dialogs.skin.http.forbidden"
                429 -> "dialogs.skin.http.rateLimited"
                else -> "dialogs.skin.http.service"
            }))
        is java.io.IOException -> tr("dialogs.skin.networkFailed")
        else -> failure.message ?: tr("dialogs.skin.failed")
    }
}
