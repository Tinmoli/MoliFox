// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateItem

@Composable
internal fun PackDetailsDialog(
    entry: LocalPackEntry,
    folder: PackFolder,
    update: PackUpdateItem?,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val type = if (entry.isDirectory) strings.t("deskC.row.folder") else strings.t("content.details.zip")
    val modified =
        if (entry.modified > 0L) {
            formatSaveTime(java.time.Instant.ofEpochMilli(entry.modified))
        } else {
            strings.t("deskC.row.unknownTime")
        }
    Modal(strings.t("content.details.title", folder.title), entry.filename, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.t("content.details.type", type))
            Text(strings.t("content.details.size", formatFileSize(entry.size)))
            Text(strings.t("content.details.modified", modified))
            update?.let { Text(strings.t("content.details.update", it.status.title)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MoliTextButton(onDismiss) { Text(strings.t("dialogs.common.close")) }
            }
        }
    }
}
