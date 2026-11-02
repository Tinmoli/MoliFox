// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 本地模组详情：保持原有分区式样，底部补一行项目页链接（有网时按指纹识别出项目）。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParseException
import dev.molifox.core.ContentLink
import dev.molifox.core.GameInstance
import dev.molifox.core.ModFile
import dev.molifox.core.modDetails
import dev.molifox.core.projectLinks
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 百科搜索入口：识别不出项目时也能查中文资料。 */
private fun encyclopediaLink(
    label: String,
    title: String,
): ContentLink = ContentLink(label, "https://www.mcmod.cn/s?key=" + title.trim().replace(" ", "+"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModDetailsDialog(
    mod: ModFile,
    links: List<ContentLink>,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Modal(mod.metadata.title.ifBlank { mod.filename }, mod.filename, onDismiss = onDismiss) {
        Column(
            modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MutedText("${mod.metadata.loader} · ${formatFileSize(mod.size)}")
            // 链接行紧跟标题信息：有官方页就列出来，识别不到项目时至少留一个百科搜索。
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { link ->
                    TextButton(
                        { onOpenUrl(link.url) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(link.title, fontSize = 12.sp)
                    }
                }
            }
            if (mod.metadata.warning.isNotBlank()) Text(mod.metadata.warning)
            mod.metadata.entries.forEach { entry ->
                Text("${entry.name} · ${entry.version}", style = MaterialTheme.typography.titleSmall)
                MutedText(strings.t("dialogs.contentUpdates.modId", entry.id))
                if (entry.authors.isNotBlank()) Text(strings.t("dialogs.contentUpdates.authors", entry.authors))
                if (entry.description.isNotBlank()) Text(entry.description)
                if (entry.dependencies.isNotEmpty()) {
                    Text(strings.t("dialogs.contentUpdates.dependencies", entry.dependencies.joinToString("\n")))
                }
            }
            MutedText(strings.t("dialogs.contentUpdates.footer"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text(strings.t("dialogs.contentUpdates.closeDetails")) }
        }
    }
}

/** 详情弹窗的数据源：先亮出声明里的项目页，联网识别到项目后再补上官方链接。 */
@Composable
internal fun ModDetailDialogHost(
    mod: ModFile,
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val title = mod.metadata.title.ifBlank { mod.filename.substringBeforeLast('.') }
    var links by remember(mod.filename) { mutableStateOf(projectLinks(mod.metadata.projectRefs)) }
    LaunchedEffect(mod.filename, instance.id) {
        val details =
            try {
                actions.controller.content.modDetails(instance.id, mod.filename, mod.metadata.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                null
            } catch (_: JsonParseException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        val discovered = details?.links.orEmpty()
        val merged =
            (discovered + projectLinks(mod.metadata.projectRefs))
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
        // 只有没解析到百科条目页时才放搜索入口，避免两个百科链接并存。
        links =
            if (merged.any { it.url.startsWith("https://www.mcmod.cn/") }) {
                merged
            } else {
                merged + encyclopediaLink(strings.t("dialogs.mod.encyclopedia"), title)
            }
    }
    ModDetailsDialog(
        mod = mod,
        links = links,
        onOpenUrl = { url -> actions.run { actions.controller.platform.openUrl(url) } },
        onDismiss = onDismiss,
    )
}
