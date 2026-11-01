// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例自定义图标与列表图形。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceIconStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun InstanceArtwork(
    instance: GameInstance,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val image by
        produceState<ImageBitmap?>(
            null,
            instance.id,
            instance.iconStyle,
            instance.iconId,
            instance.packIconId,
        ) {
            value =
                try {
                    actions.controller.icons.texture(instance)?.let { file ->
                        withContext(Dispatchers.IO) {
                            SkiaImage
                                .makeFromEncoded(Files.readAllBytes(file))
                                .toComposeImageBitmap()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
        }
    if (image != null) {
        Image(
            requireNotNull(image),
            strings.t("deskF.artwork.imageDescription", instance.name),
            modifier,
            filterQuality = FilterQuality.None,
        )
    } else {
        LoaderMark(instance.iconStyle.loader ?: instance.loader, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstanceIconDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val existing by
        produceState<java.nio.file.Path?>(null, instance.id) {
            value = actions.controller.icons.existingTexture(instance)
        }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun apply(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                error = runIconAction(action, onDismiss)
            } finally {
                busy = false
            }
        }
    }
    Modal(
        strings.t("deskF.icon.title"),
        strings.t("deskF.icon.subtitle", instance.name),
        onDismiss = { if (!busy) onDismiss() },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstanceArtwork(instance, actions, Modifier.size(48.dp))
            PrimaryButton(strings.t("deskF.icon.chooseImage"), Icons.Outlined.Image, !busy) {
                apply {
                    val chosen =
                        actions
                            .choose(
                                strings.t("deskF.icon.chooseDialogTitle"),
                                extensions = listOf("png", "jpg", "jpeg"),
                            )
                    val source = chosen.firstOrNull() ?: return@apply
                    actions.controller.icons.setCustom(instance.id, source)
                }
            }
        }
        MutedText(strings.t("deskF.icon.hint"))
        existing?.let { source ->
            SecondaryButton(
                { apply { actions.controller.icons.setCustom(instance.id, source) } },
                enabled = !busy,
            ) {
                Text(strings.t("deskF.icon.useExisting"))
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (style in InstanceIconStyle.entries.filter { it.loader != null }) {
                SecondaryButton(
                    { apply { actions.controller.icons.setPreset(instance.id, style) } },
                    enabled = !busy,
                ) {
                    LoaderMark(requireNotNull(style.loader), Modifier.size(22.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(style.title)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        DialogActions(
            strings.t("deskF.icon.restoreAuto"),
            { apply { actions.controller.icons.setPreset(instance.id, InstanceIconStyle.Auto) } },
            onCancel = onDismiss,
            enabled = !busy,
        )
    }
}

/** 执行图标操作，把可预期的失败转成提示文本；取消继续上抛，成功后关闭弹窗。 */
private suspend fun runIconAction(
    action: suspend () -> Unit,
    onDismiss: () -> Unit,
): String? =
    try {
        action()
        onDismiss()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        failure.message
    } catch (failure: IllegalArgumentException) {
        failure.message
    } catch (failure: IllegalStateException) {
        failure.message
    }
