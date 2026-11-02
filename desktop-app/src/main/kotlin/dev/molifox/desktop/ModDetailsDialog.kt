// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.ModFile

@Composable
fun ModDetailsDialog(
    mod: ModFile,
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
        TextButton(onDismiss) { Text(strings.t("dialogs.contentUpdates.closeDetails")) }
    }
}
