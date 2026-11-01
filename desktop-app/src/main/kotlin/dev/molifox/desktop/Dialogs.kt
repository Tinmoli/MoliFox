// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 共享弹窗壳、操作区和列表行，使业务弹窗采用相同的交互规则。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toLong
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 弹窗显隐动画时长：0.96→1.0 缩放 + 淡入，关闭时反向；不做任何循环动画。 */
private const val DIALOG_MOTION_MILLIS = 100

/**
 * 统一弹窗：宽 460 / 560（默认 560）、圆角 10、内边距 20、标题 16sp SemiBold + 12sp 副标题。 打开时 100ms 内从 0.96 缩放到 1.0
 * 并淡入，关闭时反向播完再交给调用方销毁。 业务弹窗的入参名与回调保持不变。
 */
@Composable
fun Modal(
    title: String,
    subtitle: String = "",
    width: Dp = 560.dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val strings = LocalStrings.current
    val reduced = LocalReducedMotion.current
    val duration = if (reduced) 0 else DIALOG_MOTION_MILLIS
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { visible = true }
    // 关闭先播反向动画，再通知调用方移除弹窗；重复点击只生效一次。
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            visible = false
            if (duration == 0) {
                onDismiss()
            } else {
                scope.launch {
                    delay(duration.toLong())
                    onDismiss()
                }
            }
        }
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .96f),
            exit = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = .96f),
        ) {
            Surface(
                Modifier.width(width).heightIn(max = 640.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(Metrics.dialogRadius),
                border = BorderStroke(1.dp, hairline()),
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            if (subtitle.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    subtitle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        SmallIconButton(Icons.Outlined.Close, strings.t("dialogs.common.closeDialog"), dismiss)
                    }
                    HorizontalDivider(color = hairline())
                    // 正文统一 13sp（bodyLarge / bodyMedium 已在 Typography 收敛到 13）。
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/** 弹窗底部按钮行：右对齐，取消为次按钮、确认为主按钮，危险操作使用错误色主按钮。 只做视觉与排版，点击语义仍由调用方传入。 [busy] 为真时确认按钮禁用并显示小号进度圈。 */
@Composable
fun DialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    cancelText: String = tr("dialogs.common.cancel"),
    onCancel: (() -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    hint: String = "",
    busy: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hint.isNotBlank()) {
            Text(
                hint,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onCancel != null) SecondaryButton(onCancel, enabled = !busy) { Text(cancelText) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.pressScale().height(Metrics.controlHeight),
            enabled = enabled && !busy,
            shape = RoundedCornerShape(Metrics.innerRadius),
            colors =
                if (danger) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(confirmText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 弹窗内的列表行：44dp 高、悬停行级主题底色，选中态用主题色容器（与设置页、选择器一致）。 */
@Composable
fun DialogListRow(
    title: String,
    detail: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.rowHeight)
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .background(
                if (selected) selectedRowBackground() else Color.Transparent,
                RoundedCornerShape(Metrics.innerRadius),
            ).rowHoverHighlight(enabled = !selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else null,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}
