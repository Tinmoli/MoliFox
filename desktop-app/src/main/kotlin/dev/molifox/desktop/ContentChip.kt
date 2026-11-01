// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示本地内容列表的筛选标签。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.onClick
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 内容页签共用的筛选芯片；顺序固定，页签只挑自己需要的几项显示。 */
internal enum class ContentChip(
    private val key: String,
) {
    All("pages.contentChip.all"),
    Disabled("pages.contentChip.disabled"),
    Updatable("pages.contentChip.updatable"),
    NoUpdate("pages.contentChip.noUpdate"),
    ;

    // 标题随语言变化，取用时才翻译，避免枚举初始化时把语言定死。
    val title: String
        get() = tr(key)
}

/** 同一组件、同一顺序、同一尺寸：只显示 [visible] 里给出的芯片。 */
@Composable
internal fun ContentChipRow(
    selected: ContentChip,
    visible: List<ContentChip>,
    onSelect: (ContentChip) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { chip ->
            FilterChip(
                selected = selected == chip,
                onClick = { onSelect(chip) },
                label = { Text(chip.title, fontSize = 11.sp) },
                modifier = Modifier.height(28.dp),
            )
        }
    }
}
