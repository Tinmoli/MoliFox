// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 下载源选择：世界固定 CurseForge，其他内容跟随设置。

package dev.molifox.desktop.content

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.SimpleDropdown
import dev.molifox.desktop.UiActions

/** 世界只有 CurseForge 提供：无论设置里选了什么内容源，都固定按 CurseForge 加载。 */
internal fun contentSourceFor(
    kind: String,
    source: String,
): String = if (kind == "world") "curseforge" else source

/** 下载源选择：普通资源跟随设置切换；世界只有 CurseForge，显示固定说明。 */
@Composable
internal fun ContentSourceControl(
    kind: String,
    source: String,
    actions: UiActions,
) {
    if (kind == "world") {
        Text(
            LocalStrings.current.t("pages.content.source.curseforgeFixed"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SimpleDropdown(
            source,
            listOf(
                "modrinth" to contentSourceName("modrinth"),
                "curseforge" to contentSourceName("curseforge"),
            ),
            { picked -> actions.run { actions.controller.setContentDownloadSource(picked) } },
            Modifier.width(150.dp),
        )
    }
}
