// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp

private const val PRESSED_ALPHA = 0.08f
private const val HOVERED_ALPHA = 0.06f

internal fun buttonFeedbackAlpha(
    enabled: Boolean,
    hovered: Boolean,
    pressed: Boolean,
): Float =
    when {
        !enabled -> 0f
        pressed -> PRESSED_ALPHA
        hovered -> HOVERED_ALPHA
        else -> 0f
    }

@Composable
internal fun outlinedActionColors(): ButtonColors =
    ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.primary,
    )

/** 用单层反馈替代叠加涟漪，按下只比悬停略深，保留原有焦点与点击语义。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ButtonFeedback(
    colors: ButtonColors,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    content: @Composable (ButtonColors, MutableInteractionSource) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val alpha = buttonFeedbackAlpha(enabled, hovered || focused, pressed)
    val overlay = MaterialTheme.colorScheme.onSurface
    val container =
        if (colors.containerColor.alpha == 0f) {
            overlay.copy(alpha = alpha)
        } else {
            lerp(colors.containerColor, overlay, alpha)
        }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        content(colors.copy(containerColor = container), interaction)
    }
}
