// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 统一设置卡片和多行输入反馈，避免各分区产生不同的编辑行为。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 卡片流：按搜索词过滤行（标题 / 说明 / 卡片标题），整卡无命中时隐藏；全部无命中显示空态。 只做本地过滤，不改任何设置值。 */
@Composable
internal fun SettingCardFlow(
    cards: List<SettingCard>,
    query: String,
    onClearSearch: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val matched =
        cards.mapNotNull { card ->
            val cardHit = card.title.contains(query, true) || card.detail.contains(query, true)
            val blocks =
                if (query.isBlank() || cardHit) {
                    card.blocks
                } else {
                    card.blocks.filter {
                        it.title.contains(query, true) || it.detail.contains(query, true)
                    }
                }
            if (blocks.isEmpty()) null else card to blocks
        }
    if (query.isNotBlank() && matched.isNotEmpty()) {
        MutedText(
            strings.t(
                "settings.search.hit",
                query,
                matched.sumOf { it.second.size },
            ),
        )
    }
    if (matched.isEmpty()) {
        EmptyState(
            strings.t("settings.search.empty.title"),
            strings.t("settings.search.empty.detail"),
            Icons.Outlined.SearchOff,
        ) {
            SecondaryButton(onClearSearch) { Text(strings.t("settings.search.clear")) }
        }
        return
    }
    matched.forEach { (card, blocks) ->
        SettingsSection(
            card.title,
            card.detail,
            // 行间使用 1dp 细分隔线：首行之前、末行之后都不画，卡片保留内边距与 44/52 行高。
            contentGap = 0.dp,
        ) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) SettingDivider()
                block.render()
            }
        }
    }
}

/** 多行输入框：用于附加 JVM 参数；输入停手 300ms 后写入设置，非法参数就地提示原因。 */
@Composable
internal fun SettingTextArea(
    value: String,
    onCommit: (String) -> String?,
    label: String,
    hint: String? = null,
) {
    val live = rememberLiveTextDraft(value, onCommit)
    val textFocus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by
        animateColorAsState(
            controlBorderColor(focused),
            if (LocalReducedMotion.current) {
                androidx.compose.animation.core
                    .snap()
            } else {
                tween(Motion.FAST)
            },
            label = "areaBorder",
        )
    val fill by
        animateColorAsState(
            controlBackground(focused),
            if (LocalReducedMotion.current) {
                androidx.compose.animation.core
                    .snap()
            } else {
                tween(Motion.FAST)
            },
            label = "areaFill",
        )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) Text(label, color = colors.onSurfaceVariant, fontSize = 11.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(fill)
                .border(1.dp, border, RoundedCornerShape(Metrics.innerRadius))
                .clickable(interactionSource = interaction, indication = null) {
                    textFocus.requestFocus()
                }.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                live.text,
                live.onChange,
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp, max = 150.dp)
                    .focusRequester(textFocus)
                    .semantics { contentDescription = label },
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onSurface,
                        fontFamily = LocalLauncherFont.current,
                    ),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
                minLines = 1,
                maxLines = 6,
            )
        }
        val message = live.error ?: hint
        if (message != null) {
            Text(
                message,
                color = if (live.error != null) colors.error else colors.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}
