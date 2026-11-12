// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.File
import java.util.Locale

@Composable
fun Modifier.packDropTarget(actions: UiActions): Modifier =
    desktopFileDropTarget(
        enabled = true,
        unreadable = { tr("pages.packs.drop.unreadable") },
        onError = { actions.controller.store.notify(it) },
    ) { raw ->
        val file = raw?.singleOrNull() as? File
        require(file != null) { tr("pages.packs.drop.oneFile") }
        require(file.extension.lowercase(Locale.ROOT) in setOf("mrpack", "zip")) {
            tr("pages.packs.drop.wrongType")
        }
        actions.previewPackFile(file.toPath())
    }
