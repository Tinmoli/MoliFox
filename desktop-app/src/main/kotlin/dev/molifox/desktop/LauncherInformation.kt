// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责更新发布说明、官方下载入口和关于页中的运行与许可信息。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import dev.molifox.core.LauncherRelease
import dev.molifox.core.LauncherUpdateState
import dev.molifox.core.MoliFoxIdentity
import dev.molifox.core.i18n.I18n
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

@Composable
fun LauncherUpdatePanel(
    update: LauncherUpdateState,
    actions: UiActions,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow(
            strings.t("settings.update.current.title"),
            strings.t("settings.update.current.detail", LAUNCHER_VERSION),
        ) {
            SecondaryButton(
                { actions.controller.checkForUpdates(true) },
                enabled = !update.checking,
            ) {
                if (update.checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (update.checking) strings.t("settings.update.checking") else strings.t("settings.update.check"))
            }
        }
        update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (update.checked && update.error == null) {
            Text(
                when {
                    update.release == null -> strings.t("settings.update.none")
                    update.available -> strings.t("settings.update.available", update.release?.version)
                    else -> strings.t("settings.update.latest")
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        MutedText(strings.t("settings.update.note"))
        update.release?.let { release ->
            LauncherReleaseDetails(update, actions, release)
        }
        TextButton({
            actions.run { actions.controller.platform.openUrl(MoliFoxIdentity.RELEASES) }
        }) {
            Text(strings.t("settings.update.releases"))
        }
    }
}

@Composable
private fun LauncherReleaseDetails(
    update: LauncherUpdateState,
    actions: UiActions,
    release: LauncherRelease,
) {
    val strings = LocalStrings.current
    MutedText("${release.title} · ${release.publishedAt.take(10)}")
    if (release.notes.isNotBlank()) {
        Text(strings.t("settings.update.notes"), style = MaterialTheme.typography.titleSmall)
        ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            MarkdownText(release.notes)
        }
    }
    var confirmInstall by remember { mutableStateOf(false) }
    if (update.available) {
        val hasPackage = release.asset?.sha256?.isNotBlank() == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                strings.t("settings.update.download"),
                Icons.Outlined.Download,
                enabled = hasPackage && !actions.controller.jobs.busy,
            ) {
                actions.run {
                    actions.controller.downloadUpdate()
                    actions.taskQueued()
                }
            }
            update.downloadedFile?.let { file ->
                SecondaryButton({
                    actions.run {
                        actions.controller.platform.openFolder(Path.of(file).parent)
                    }
                }) {
                    Text(strings.t("settings.update.viewDownload"))
                }
                PrimaryButton(strings.t("settings.update.install")) { confirmInstall = true }
            }
        }
        MutedText(
            if (hasPackage) {
                strings.t("settings.update.detail.ready")
            } else {
                strings.t("settings.update.detail.missing")
            },
        )
        if (confirmInstall) {
            AlertDialog(
                properties =
                    androidx.compose.ui.window
                        .DialogProperties(dismissOnClickOutside = false),
                onDismissRequest = { confirmInstall = false },
                title = { Text(strings.t("settings.update.confirm.title")) },
                text = {
                    Text(strings.t("settings.update.confirm.message"))
                },
                confirmButton = {
                    TextButton({
                        confirmInstall = false
                        actions.controller.applyDownloadedUpdate()
                    }) { Text(strings.t("settings.update.confirm.confirm")) }
                },
                dismissButton = {
                    TextButton({ confirmInstall = false }) { Text(strings.t("settings.common.cancel")) }
                },
            )
        }
    }
}

@Composable
fun LauncherAboutPanel(actions: UiActions) {
    var document by remember { mutableStateOf<String?>(null) }
    var licensesOpen by remember { mutableStateOf(false) }
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            FoxMark(Modifier.size(46.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(MoliFoxIdentity.NAME, style = MaterialTheme.typography.titleLarge)
                MutedText("$LAUNCHER_VERSION · Minecraft Java Edition Launcher")
            }
        }
        MutedText(strings.t("settings.about.platform"))
        MutedText(
            "${actions.controller.platform.osName} " +
                "${actions.controller.platform.architecture} · " +
                "Java ${System.getProperty("java.version")}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                strings.t("settings.about.source") to MoliFoxIdentity.REPOSITORY,
                strings.t("settings.about.releases") to MoliFoxIdentity.RELEASES,
                strings.t("settings.about.issues") to MoliFoxIdentity.ISSUES,
            ).forEach { (label, url) ->
                SecondaryButton({ actions.run { actions.controller.platform.openUrl(url) } }) {
                    Text(label)
                }
            }
        }
        MutedText("Copyright (C) 2026 Tinmoli and MoliFox Contributors · GPL-3.0-only")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                strings.t("settings.about.licenseFull") to "LICENSE",
                strings.t("settings.about.notice") to "NOTICE",
            ).forEach { (label, name) ->
                SecondaryButton({ document = name }) { Text(label) }
            }
            SecondaryButton({ licensesOpen = true }) { Text(strings.t("settings.about.thirdParty")) }
            SecondaryButton({ actions.agreementOpen = true }) { Text(strings.t("settings.about.agreement")) }
        }
        SecondaryButton({
            val summary =
                "MoliFox $LAUNCHER_VERSION\n${System.getProperty(
                    "os.name",
                )} ${actions.controller.platform.architecture}\nJava ${System.getProperty(
                    "java.version",
                )}\n${MoliFoxIdentity.REPOSITORY}"
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(summary), null)
            actions.controller.store.notify(strings.t("settings.about.copied"))
        }) {
            Text(strings.t("settings.about.copyVersion"))
        }
        MutedText(strings.t("settings.about.thanks"))
        MutedText(strings.t("settings.about.trademark"))
    }
    document?.let { name ->
        val text = remember(name) { BundledLegalDocuments.load(name) }
        Modal(name, strings.t("settings.about.legal.title"), 760.dp, { document = null }) {
            ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                Text(
                    MoliFoxIdentity.NAME,
                    style = MaterialTheme.typography.titleSmall,
                )
                MutedText("Copyright (C) 2026 Tinmoli and MoliFox Contributors")
                MutedText(
                    if (name == "LICENSE") {
                        strings.t("settings.about.legal.gpl")
                    } else {
                        strings.t("settings.about.legal.notice")
                    },
                )
                Text("", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({ document = null }) { Text(strings.t("settings.common.close")) }
        }
    }
    if (licensesOpen) {
        ThirdPartyLicensesDialog(
            onOpenUrl = { url -> actions.run { actions.controller.platform.openUrl(url) } },
            onDismiss = { licensesOpen = false },
        )
    }
}

private object BundledLegalDocuments {
    fun load(name: String): String =
        javaClass
            .getResourceAsStream("/META-INF/molifox/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() } ?: I18n.t("settings.about.legal.missing", name)
}
