// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.molifox.core.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModeBackgroundRow(
    settings: LauncherSettings,
    actions: UiActions,
    apply: AppearanceChange,
    dark: Boolean,
) {
    val strings = LocalStrings.current
    SettingRow(strings.t(if (dark) "settings.appearance.background.dark" else "settings.appearance.background.light")) {
        SecondaryButton({
            actions.run {
                val source = actions.choose(strings.t("settings.appearance.background.choose"), extensions = listOf("png", "jpg", "jpeg")).firstOrNull()
                if (source != null) {
                    val imported = withContext(Dispatchers.IO) {
                        actions.controller.appearance.importModeBackground(source, dark)
                    }
                    apply {
                        if (dark) it.copy(darkBackgroundPath = imported.toString(), backgroundRevision = System.currentTimeMillis())
                        else it.copy(lightBackgroundPath = imported.toString(), backgroundRevision = System.currentTimeMillis())
                    }
                }
            }
        }) { Text(strings.t("settings.appearance.background.pick")) }
    }
}
