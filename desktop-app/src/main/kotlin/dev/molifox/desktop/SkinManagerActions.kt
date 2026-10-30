// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 皮肤管理的操作与取值辅助：把界面里的分支收进单一职责的小函数。

package dev.molifox.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.gson.JsonParseException
import dev.molifox.core.Account
import dev.molifox.core.AccountType
import dev.molifox.core.LibrarySkin
import dev.molifox.core.SkinService
import dev.molifox.core.SkinVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.nameWithoutExtension
import org.jetbrains.skia.Image as SkiaImage

/**
 * 皮肤操作把文件、网络与皮肤库校验的失败统一翻成界面文案。
 *
 * 只接住这几类可预期异常；取消与 Error 继续向上抛，避免把真正的缺陷吞成提示。
 */
internal suspend fun <T> skinAttempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: JsonParseException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

/** 读取选中皮肤或账户皮肤的纹理并解码成位图；没有纹理时返回 null。 */
internal suspend fun loadSkinBitmap(
    service: SkinService,
    account: Account,
    selectedId: String?,
): ImageBitmap? {
    val file =
        if (selectedId != null) {
            service.library.texture(selectedId)
        } else {
            service.texture(account)
        }
    return file?.let {
        withContext(Dispatchers.IO) {
            SkiaImage.makeFromEncoded(Files.readAllBytes(it)).toComposeImageBitmap()
        }
    }
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
        actions.choose("导入 PNG 皮肤", extensions = listOf("png")).firstOrNull() ?: return null
    val imported =
        service.library.importSkin(
            file,
            name.ifBlank { file.nameWithoutExtension },
            variant,
        )
    val message =
        if (account.type == AccountType.Offline) {
            val placement = service.useOfflinePreview(account.id, imported.id)
            "已导入 ${imported.name}" +
                (placement?.customSkinLoader?.let { "，已同步 CustomSkinLoader" } ?: "")
        } else {
            "已导入 ${imported.name}"
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
                "导出 PNG 皮肤",
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
        if (account.type == AccountType.Microsoft) " · 正版档案与本地皮肤库" else " · 离线角色本地预览"

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
    if (textureUrl.isBlank()) "皮肤已上传，请稍后刷新正版档案。" else "正版皮肤已更新"

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
