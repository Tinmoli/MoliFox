// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 皮肤管理的操作与取值辅助：把界面里的分支收进单一职责的小函数。

package dev.molifox.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.molifox.core.Account
import dev.molifox.core.AccountType
import dev.molifox.core.LibrarySkin
import dev.molifox.core.SkinService
import dev.molifox.core.SkinVariant
import dev.molifox.core.textureBytes
import kotlin.io.path.nameWithoutExtension
import org.jetbrains.skia.Image as SkiaImage

/** 读取选中皮肤或账户皮肤的纹理并解码成位图；没有纹理时返回 null。 */
internal suspend fun loadSkinBitmap(
    service: SkinService,
    account: Account,
    selectedId: String?,
): ImageBitmap? {
    val bytes = service.textureBytes(account, selectedId) ?: return null
    return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}

/** 选择 PNG 导入皮肤库并组装提示语；离线账户同时设置本地预览。返回 null 表示取消了选择。 */
internal suspend fun importSkinFromPicker(
    service: SkinService,
    account: Account,
    actions: UiActions,
    name: String,
    variant: SkinVariant,
): SkinImportResult? {
    val file =
        actions.choose(tr("dialogs.skin.importDialogTitle"), extensions = listOf("png")).firstOrNull() ?: return null
    val imported =
        service.library.importSkin(
            file,
            name.ifBlank { file.nameWithoutExtension },
            variant,
        )
    val message =
        if (account.type == AccountType.Offline) {
            val placement = service.useOfflinePreview(account.id, imported.id)
            tr("dialogs.skin.imported", imported.name) +
                (placement?.customSkinLoader?.let { tr("dialogs.skin.importedSynced", it) } ?: "")
        } else {
            tr("dialogs.skin.imported", imported.name)
        }
    return SkinImportResult(imported, message)
}

/** 选择目标文件并导出选中项或当前账户皮肤；用户取消选择时不回调。 */
internal suspend fun exportSkin(
    service: SkinService,
    account: Account,
    selected: LibrarySkin?,
    actions: UiActions,
    onExported: () -> Unit,
) {
    val file =
        actions
            .choose(
                tr("dialogs.skin.exportDialogTitle"),
                save = true,
                extensions = listOf("png"),
                defaultName = "skin.png",
            ).firstOrNull() ?: return
    if (selected != null) {
        service.library.exportSkin(selected.id, file)
    } else {
        service.exportCurrent(account.id, file)
    }
    onExported()
}

/** 弹窗副标题：正版账户说明档案与皮肤库，离线账户只做本地预览。 */
internal fun dialogSubtitle(account: Account): String =
    account.name +
        if (account.type == AccountType.Microsoft) {
            tr("dialogs.skin.accountSuffix.official")
        } else {
            tr("dialogs.skin.accountSuffix.offline")
        }

/** 忙时不关闭弹窗，避免操作中的界面被直接销毁。 */
internal fun dismissIfIdle(
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    if (!busy) onDismiss()
}

/** 预览使用的模型：选中皮肤优先，否则用账户当前模型。 */
internal fun previewVariant(
    selected: LibrarySkin?,
    current: SkinVariant,
): SkinVariant = selected?.variant ?: current

/** 有选中皮肤或已加载纹理时才能导出。 */
internal fun canExport(
    busy: Boolean,
    selected: LibrarySkin?,
    image: ImageBitmap?,
): Boolean = !busy && (selected != null || image != null)

/** 上传成功后的提示：空纹理地址表示还需要再刷新一次正版档案。 */
internal fun uploadResultMessage(textureUrl: String): String =
    if (textureUrl.isBlank()) tr("dialogs.skin.uploadedRefresh") else tr("dialogs.skin.uploaded")

/** 移除的正好是当前选中项时清空选中。 */
internal fun selectedAfterRemove(
    selectedId: String?,
    removedId: String,
): String? = if (selectedId == removedId) null else selectedId

/** 导入后的条目与要显示的提示语。 */
internal class SkinImportResult(
    val entry: LibrarySkin,
    val message: String,
)
