// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责第三方许可清单的读取与关于页内弹窗。
package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 许可表每行固定为 组件 / 许可 / 用途 / 链接 四列，链接列固定在最后。 */
private const val LICENSE_TABLE_COLUMN_COUNT = 4
private const val URL_COLUMN_INDEX = 3

/** 一条第三方组件：名称、许可标识、用途与项目/许可链接。 */
internal data class ThirdPartyLicense(
    val name: String,
    val license: String,
    val purpose: String,
    val url: String,
)

/**
 * 许可清单的唯一来源是仓库的 assets/legal/THIRD_PARTY_LICENSES.md；构建时由 processResources
 * 复制到 /META-INF/molifox/，关于页与仓库文档因此永远读同一份数据。
 */
internal object ThirdPartyLicenseCatalog {
    private const val LICENSE_RESOURCE_PATH = "/META-INF/molifox/THIRD_PARTY_LICENSES.md"
    private val separator = Regex(":?-{3,}:?")

    val entries: List<ThirdPartyLicense> by lazy { parse(load()) }

    private fun load(): String =
        javaClass
            .getResourceAsStream(
                LICENSE_RESOURCE_PATH,
            )?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: ""

    private fun parse(markdown: String): List<ThirdPartyLicense> =
        markdown
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") && it.endsWith("|") && it.length > 2 }
            .map { line -> line.substring(1, line.length - 1).split('|').map { it.trim() } }
            .filter { cells ->
                cells.size == LICENSE_TABLE_COLUMN_COUNT &&
                    cells[0] != "组件" &&
                    cells.take(URL_COLUMN_INDEX).none { it.matches(separator) } &&
                    cells[URL_COLUMN_INDEX].startsWith("https://")
            }.map { cells ->
                ThirdPartyLicense(cells[0], cells[1], cells[2], cells[URL_COLUMN_INDEX])
            }.toList()
}

/** 关于页「第三方许可」：只读清单，每项一行许可与一条可打开的项目链接。 */
@Composable
internal fun ThirdPartyLicensesDialog(
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = ThirdPartyLicenseCatalog.entries
    val strings = LocalStrings.current
    Modal(
        strings.t("settings.about.thirdParty"),
        strings.t("settings.about.thirdParty.detail"),
        720.dp,
        onDismiss,
    ) {
        if (entries.isEmpty()) {
            MutedText(strings.t("settings.about.thirdParty.empty"))
        } else {
            ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), gap = 0.dp) {
                entries.forEachIndexed { index, entry ->
                    if (index >
                        0
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ThirdPartyLicenseRow(entry, onOpenUrl)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text(strings.t("settings.common.close")) }
        }
    }
}

@Composable
private fun ThirdPartyLicenseRow(
    entry: ThirdPartyLicense,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.name,
                Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.license,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            entry.purpose,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            entry.url,
            Modifier.clickable { onOpenUrl(entry.url) },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
