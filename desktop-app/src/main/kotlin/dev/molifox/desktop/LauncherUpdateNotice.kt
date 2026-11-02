// 发现新版本时显示发布说明；只在用户确认后下载，不强制安装。
package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.LAUNCHER_VERSION
import dev.molifox.core.LauncherUpdateState

@Composable
internal fun LauncherUpdateNotice(
    update: LauncherUpdateState,
    actions: UiActions,
) {
    var dismissed by remember { mutableStateOf<Long?>(null) }
    val release = update.release ?: return
    if (!update.available || update.checking || dismissed == update.promptSerial) return
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = { dismissed = update.promptSerial },
        title = { Text(strings.t("settings.update.available", release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.t("settings.update.versions", LAUNCHER_VERSION, release.version))
                Text(
                    strings.t(
                        if (release.prerelease) "settings.update.channel.beta" else "settings.update.channel.stable",
                    ),
                )
                ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    MarkdownText(release.notes.ifBlank { strings.t("settings.update.noNotes") })
                }
                TextButton({ actions.run { actions.controller.platform.openUrl(release.pageUrl) } }) {
                    Text(strings.t("settings.update.releases"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.run {
                        actions.controller.downloadUpdate()
                        actions.taskQueued()
                        actions.showTasks()
                        dismissed = update.promptSerial
                    }
                },
                enabled = release.asset?.sha256?.isNotBlank() == true && !actions.controller.jobs.busy,
            ) { Text(strings.t("settings.update.download")) }
        },
        dismissButton = {
            TextButton({ dismissed = update.promptSerial }) { Text(strings.t("settings.update.later")) }
        },
    )
}
