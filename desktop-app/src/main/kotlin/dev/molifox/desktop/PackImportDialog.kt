// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包选择、预览和导入确认。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.PackPreview
import java.nio.file.Path

@Composable
fun PackImportDialog(
    preview: PackPreview,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var selected by remember(preview.hash) { mutableStateOf(preview.optionalFiles.toSet()) }
    var error by remember(preview.hash) { mutableStateOf<String?>(null) }
    Modal(strings.t("dialogs.packImport.title"), preview.title, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Minecraft ${preview.gameVersion}")
                Tag(
                    preview.loader.title +
                        if (preview.loaderVersion.isBlank()) "" else " ${preview.loaderVersion}",
                )
            }
            if (preview.version.isNotBlank()) MutedText(strings.t("dialogs.packImport.version", preview.version))
            if (preview.summary.isNotBlank()) MutedText(preview.summary)
            MutedText(
                strings.t(
                    "dialogs.packImport.optionalSummary",
                    preview.requiredFiles,
                    selected.size,
                    preview.optionalFiles.size,
                ),
            )
            if (preview.optionalFiles.isNotEmpty()) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.t("dialogs.packImport.optionalTitle"),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    MoliTextButton({ selected = preview.optionalFiles.toSet() }) {
                        Text(strings.t("dialogs.packImport.selectAll"))
                    }
                    MoliTextButton({ selected = emptySet() }) { Text(strings.t("dialogs.packImport.selectNone")) }
                }
                preview.optionalFiles.forEach { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected =
                                    if (file in selected) selected - file else selected + file
                            }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            file in selected,
                            { checked ->
                                selected = if (checked) selected + file else selected - file
                            },
                        )
                        Text(file, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            MutedText(strings.t("dialogs.packImport.note"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MoliTextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                strings.t("dialogs.packImport.start"),
                onClick = {
                    try {
                        actions.controller.importPack(
                            Path.of(preview.archive),
                            selected,
                            preview.hash,
                        )
                        onDismiss()
                    } catch (failure: IllegalArgumentException) {
                        error = failure.message ?: strings.t("dialogs.packImport.failed")
                    } catch (failure: IllegalStateException) {
                        error = failure.message ?: strings.t("dialogs.packImport.failed")
                    }
                },
            )
        }
    }
}
