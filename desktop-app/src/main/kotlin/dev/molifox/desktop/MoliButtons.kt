// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** 按钮共用小圆角，保留原有交互、尺寸与颜色参数。 */
@Composable
fun MoliButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    ButtonFeedback(colors, enabled, interactionSource) { feedback, interaction ->
        MaterialButton(onClick, modifier, enabled, shape, feedback, elevation, border, contentPadding, interaction, content)
    }
}

/** 按钮共用小圆角，保留原有交互、尺寸与颜色参数。 */
@Composable
fun MoliOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    colors: ButtonColors = outlinedActionColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    ButtonFeedback(colors, enabled, interactionSource) { feedback, interaction ->
        MaterialOutlinedButton(onClick, modifier, enabled, shape, feedback, elevation, border, contentPadding, interaction, content)
    }
}

/** 按钮共用小圆角，保留原有交互、尺寸与颜色参数。 */
@Composable
fun MoliTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    ButtonFeedback(colors, enabled, interactionSource) { feedback, interaction ->
        MaterialTextButton(onClick, modifier, enabled, shape, feedback, elevation, border, contentPadding, interaction, content)
    }
}
