// 负责更新发布说明、官方下载入口和关于页中的运行与许可信息。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

@Composable
fun LauncherUpdatePanel(update: LauncherUpdateState, actions: UiActions) {
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
        update.release?.let { release ->
            MutedText("${release.title} · ${release.publishedAt.take(10)}")
            if (release.notes.isNotBlank()) {
                ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    Text(release.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
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
                    }
                }
                MutedText(
                    if (hasPackage) "下载后验证 SHA-256；退出启动器再替换原文件，保留 MoliFox 文件夹。"
                    else "该版本缺少适配此系统的包或 SHA-256 摘要，请前往发布页下载。"
                )
            }
        }
        TextButton({
            actions.run { actions.controller.platform.openUrl(MoliFoxIdentity.releases) }
        }) {
            Text("查看 GitHub 发布页")
        }
    }
}

@Composable
fun LauncherAboutPanel(actions: UiActions) {
    var document by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            FoxMark(Modifier.size(46.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("MoliFox · 茉狐", style = MaterialTheme.typography.titleLarge)
                MutedText("$LAUNCHER_VERSION · Minecraft Java Edition Launcher")
            }
        }
        MutedText("Kotlin · Compose Multiplatform · Windows / Linux")
        MutedText(
            "${actions.controller.platform.osName} ${actions.controller.platform.architecture} · Java ${System.getProperty("java.version")}"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    "项目源码" to MoliFoxIdentity.repository,
                    "发布记录" to MoliFoxIdentity.releases,
                    "问题反馈" to MoliFoxIdentity.issues,
                )
                .forEach { (label, url) ->
                    SecondaryButton({ actions.run { actions.controller.platform.openUrl(url) } }) {
                        Text(label)
                    }
                }
        }
        Text(MoliFoxIdentity.license, style = MaterialTheme.typography.titleSmall)
        MutedText("Copyright © 2026 MoliFox contributors。分发时保留署名，修改版须明确标识。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("GPLv3 全文" to "LICENSE", "附加条款" to "NOTICE", "第三方许可" to "THIRD_PARTY.md")
                .forEach { (label, name) ->
                    SecondaryButton({ document = name }) { Text(label) }
                }
        }
        SecondaryButton({
            val summary =
                "MoliFox $LAUNCHER_VERSION\n${System.getProperty("os.name")} ${actions.controller.platform.architecture}\nJava ${System.getProperty("java.version")}\n${MoliFoxIdentity.repository}"
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(summary), null)
            actions.controller.store.notify("版本与运行信息已复制")
        }) {
            Text("复制版本信息")
        }
        MutedText("感谢 Kotlin、Compose、OpenJDK 及各开源依赖。功能和操作体验参考 HMCL、PCL 等成熟启动器，独立实现。")
        MutedText("Minecraft 是 Mojang 的商标。本项目与 Mojang、Microsoft 没有关联。")
    }
    document?.let { name ->
        val text = remember(name) { BundledLegalDocuments.load(name) }
        Modal(name, "随程序分发的许可声明", 760.dp, { document = null }) {
            ScrollColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp)) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton({ document = null }) { Text("关闭") }
        }
    }
}

private object BundledLegalDocuments {
    fun load(name: String): String =
        javaClass
            .getResourceAsStream("/META-INF/molifox/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() } ?: "未找到随包许可文件，请查看项目仓库中的 $name。"
}
