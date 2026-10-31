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
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

@Composable
fun LauncherUpdatePanel(
    update: LauncherUpdateState,
    actions: UiActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow("当前版本", "MoliFox $LAUNCHER_VERSION · 正式版本通道") {
            SecondaryButton(
                { actions.controller.checkForUpdates(true) },
                enabled = !update.checking,
            ) {
                if (update.checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (update.checking) "正在检查…" else "检查更新")
            }
        }
        update.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (update.checked && update.error == null) {
            Text(
                when {
                    update.release == null -> "尚无正式发布版本"
                    update.available -> "可更新至 ${update.release?.version}"
                    else -> "已是最新版本"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        MutedText("自动测速 GitHub 原站与加速站，按版本号判断最新正式版；忽略草稿和预发布版。")
        update.release?.let { release ->
            LauncherReleaseDetails(update, actions, release)
        }
        TextButton({
            actions.run { actions.controller.platform.openUrl(MoliFoxIdentity.RELEASES) }
        }) {
            Text("查看 GitHub 发布页")
        }
    }
}

@Composable
private fun LauncherReleaseDetails(
    update: LauncherUpdateState,
    actions: UiActions,
    release: LauncherRelease,
) {
    MutedText("${release.title} · ${release.publishedAt.take(10)}")
    if (release.notes.isNotBlank()) {
        Text("更新内容", style = MaterialTheme.typography.titleSmall)
        ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
            Text(release.notes, style = MaterialTheme.typography.bodySmall)
        }
    }
    var confirmInstall by remember { mutableStateOf(false) }
    if (update.available) {
        val hasPackage = release.asset?.sha256?.isNotBlank() == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                "下载更新包",
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
                    Text("查看下载文件")
                }
                PrimaryButton("安装更新并重启") { confirmInstall = true }
            }
        }
        MutedText(
            if (hasPackage) {
                "下载后验证 SHA-256；可自动替换并重启，数据目录 MoliFox/ 保留。"
            } else {
                "该版本缺少适配此系统的包或 SHA-256 摘要，请前往发布页下载。"
            },
        )
        if (confirmInstall) {
            AlertDialog(
                onDismissRequest = { confirmInstall = false },
                title = { Text("安装更新并重启？") },
                text = {
                    Text("启动器会先退出；更新脚本等待退出后替换当前启动器文件并重新打开。MoliFox 数据目录和游戏不受影响。")
                },
                confirmButton = {
                    TextButton({
                        confirmInstall = false
                        actions.controller.applyDownloadedUpdate()
                    }) { Text("安装并重启") }
                },
                dismissButton = {
                    TextButton({ confirmInstall = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
fun LauncherAboutPanel(actions: UiActions) {
    var document by remember { mutableStateOf<String?>(null) }
    var licensesOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            FoxMark(Modifier.size(46.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("MoliFox", style = MaterialTheme.typography.titleLarge)
                MutedText("$LAUNCHER_VERSION · Minecraft Java Edition Launcher")
            }
        }
        MutedText("Kotlin · Compose Multiplatform · 支持 Windows x86_64 / Linux x86_64")
        MutedText(
            "${actions.controller.platform.osName} " +
                "${actions.controller.platform.architecture} · " +
                "Java ${System.getProperty("java.version")}",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "项目源码" to MoliFoxIdentity.REPOSITORY,
                "发布记录" to MoliFoxIdentity.RELEASES,
                "问题反馈" to MoliFoxIdentity.ISSUES,
            ).forEach { (label, url) ->
                SecondaryButton({ actions.run { actions.controller.platform.openUrl(url) } }) {
                    Text(label)
                }
            }
        }
        MutedText("Copyright (C) 2026 Tinmoli and MoliFox Contributors · GPL-3.0-only")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("许可全文" to "LICENSE", "附加条款" to "NOTICE")
                .forEach { (label, name) ->
                    SecondaryButton({ document = name }) { Text(label) }
                }
            SecondaryButton({ licensesOpen = true }) { Text("第三方许可") }
        }
        SecondaryButton({
            val summary =
                "MoliFox $LAUNCHER_VERSION\n${System.getProperty(
                    "os.name",
                )} ${actions.controller.platform.architecture}\nJava ${System.getProperty(
                    "java.version",
                )}\n${MoliFoxIdentity.REPOSITORY}"
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(summary), null)
            actions.controller.store.notify("版本与运行信息已复制")
        }) {
            Text("复制版本信息")
        }
        MutedText("感谢 Kotlin、Compose、OpenJDK 及各开源依赖。")
        MutedText("Minecraft 是 Mojang 的商标。本项目与 Mojang、Microsoft 没有关联。")
    }
    document?.let { name ->
        val text = remember(name) { BundledLegalDocuments.load(name) }
        Modal(name, "随程序分发的许可声明", 760.dp, { document = null }) {
            ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                Text(
                    "MoliFox — Minecraft Launcher",
                    style = MaterialTheme.typography.titleSmall,
                )
                MutedText("Copyright (C) 2026 Tinmoli and MoliFox Contributors")
                MutedText(
                    if (name == "LICENSE") {
                        "本程序以 GNU 通用公共许可证第 3 版（GPL-3.0-only）发布；以下为许可全文。"
                    } else {
                        "以下为本项目附加条款原文。"
                    },
                )
                Text("", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({ document = null }) { Text("关闭") }
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
            ?.use { it.readText() } ?: "未找到随包许可文件，请查看项目仓库中的 $name。"
}
