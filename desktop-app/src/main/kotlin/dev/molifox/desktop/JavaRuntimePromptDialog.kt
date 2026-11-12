// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责缺少游戏 Java 时的启动提示：自动安装、选择已有或使用系统已安装的 Java。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JavaPrompt
import dev.molifox.core.JavaRuntime
import dev.molifox.core.JobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * 启动检查发现实例缺 Java 时的弹窗。四个入口：
 * 「下载并安装」→ 任务队列装 Zulu JRE（没有 JRE 构建时 JDK）；
 * 「使用系统已安装的 Java（列表）」→ 展开检测结果直接选一个；
 * 「选择已有的 Java…」→ 系统文件选择器；
 * 「取消」→ 只关闭提示。勾选「不再为这个实例提示」后不再自动出现，手动指定路径也不受影响。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JavaRuntimePromptDialog(
    prompt: JavaPrompt,
    actions: UiActions,
) {
    var rememberDismiss by remember(prompt.instanceId) { mutableStateOf(false) }
    var showList by remember(prompt.instanceId) { mutableStateOf(prompt.candidates.isEmpty()) }
    var choosing by remember(prompt.instanceId) { mutableStateOf(false) }
    val strings = LocalStrings.current
    val state by actions.controller.state.collectAsState()
    val platform = actions.controller.platform
    val installing =
        state.jobs.any {
            it.status in setOf(JobStatus.Queued, JobStatus.Running) &&
                it.resources.contains("java:${prompt.requiredMajor}")
        }

    fun dismiss() = actions.controller.dismissJavaPrompt(rememberDismiss)
    Modal(
        strings.t("settings.java.prompt.title"),
        "${prompt.instanceName} · " +
            prompt.gameVersion.ifBlank { strings.t("pages.instance.meta.versionUnknown") },
        width = 620.dp,
        onDismiss = { dismiss() },
    ) {
        Text(
            strings.t("settings.java.prompt.requirement", prompt.requiredMajor),
        )
        if (prompt.candidates.isEmpty()) MutedText(strings.t("settings.java.prompt.none"))
        if (showList) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                prompt.candidates.forEach { runtime ->
                    JavaCandidateRow(runtime, platform.architecture, enabled = !choosing) {
                        choosing = true
                        actions.controller.chooseJavaForPrompt(runtime.path)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactSwitch(rememberDismiss, { rememberDismiss = it })
            Spacer(Modifier.width(6.dp))
            Text(strings.t("settings.java.prompt.remember"), fontSize = 12.sp)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                if (installing) {
                    strings.t("settings.java.prompt.installing")
                } else {
                    strings.t("settings.java.prompt.install")
                },
                enabled = !installing,
            ) {
                actions.controller.installJavaForPrompt()
                actions.taskQueued()
            }
            SecondaryButton(
                {
                    if (choosing) return@SecondaryButton
                    choosing = true
                    actions.run {
                        try {
                            val chosen =
                                actions
                                    .choose(
                                        strings.t("settings.java.choose.file"),
                                        extensions =
                                            if (platform.osName == "windows") {
                                                listOf("exe")
                                            } else {
                                                emptyList()
                                            },
                                    ).firstOrNull()
                            if (chosen != null) {
                                withContext(Dispatchers.Swing) {
                                    actions.controller.chooseJavaForPrompt(chosen.toString())
                                }
                            }
                        } finally {
                            withContext(Dispatchers.Swing) { choosing = false }
                        }
                    }
                },
                enabled = !choosing,
            ) {
                Text(strings.t("settings.java.prompt.chooseManual"))
            }
            SecondaryButton({ showList = !showList }) {
                Text(
                    if (showList) {
                        strings.t("settings.java.prompt.hideSystem")
                    } else {
                        strings.t("settings.java.prompt.useSystem", prompt.candidates.size)
                    },
                )
            }
            SecondaryButton({ dismiss() }) { Text(strings.t("settings.common.cancel")) }
        }
    }
}

/** 一行候选：来源标签 + 版本/位数 + 厂商与版本号 + 路径；点击即写入实例 Java 路径。 */
@Composable
private fun JavaCandidateRow(
    runtime: JavaRuntime,
    architecture: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(runtime.source.title)
        Column(Modifier.weight(1f)) {
            Text(
                javaRuntimeLabel(runtime, architecture) +
                    if (runtime.version.isBlank()) "" else " · " + runtime.version,
                fontSize = 13.sp,
            )
            Text(
                runtime.path,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (runtime.imageType.isNotBlank()) {
            Text(runtime.imageType.uppercase(), fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}
