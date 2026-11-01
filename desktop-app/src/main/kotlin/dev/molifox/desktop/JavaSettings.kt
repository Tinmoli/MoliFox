// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将 Java 管理表单与运行时选择保持在同一职责边界。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JavaRuntime
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherSettings
import dev.molifox.core.effectiveJavaDownloadSource
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/** Java 与运行环境：当前使用项、扁平候选列表、添加入口与安装控件。 */
@Composable
internal fun javaSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
    runtimes: List<JavaRuntime>,
    detecting: Boolean,
    detectionError: String?,
    onRefresh: () -> Unit,
): List<SettingCard> {
    val state by actions.controller.state.collectAsState()
    val strings = LocalStrings.current
    var installVersion by remember { mutableStateOf("auto") }
    var installType by remember { mutableStateOf("jre") }
    var installJob by remember { mutableStateOf<String?>(null) }
    val job = installJob?.let { id -> state.jobs.find { it.id == id } }
    val active = job?.status in setOf(JobStatus.Queued, JobStatus.Running)
    return listOf(
        SettingCard(
            strings.t("settings.java.section.title"),
            "",
            listOf(
                settingField(strings.t("settings.java.gameJava")) {
                    JavaPicker(
                        actions,
                        settings.javaPath,
                        runtimes,
                        { value -> writer.commit { it.copy(javaPath = value) } },
                        knownCustomPaths = settings.customJavaPaths,
                        onAddCustom = { path ->
                            writer.commit { current ->
                                current.copy(
                                    customJavaPaths = (current.customJavaPaths + path).distinct(),
                                )
                            }
                        },
                        onRefresh = onRefresh,
                        detecting = detecting,
                        detectionError = detectionError,
                    )
                },
                settingField(
                    strings.t("settings.java.downloadSource.title"),
                    strings.t("settings.java.downloadSource.detail"),
                ) {
                    SimpleDropdown(
                        settings.effectiveJavaDownloadSource,
                        listOf(
                            "auto" to strings.t("settings.java.auto"),
                            "azul" to "Azul",
                            "adoptium" to "Adoptium",
                        ),
                        { value -> writer.commit { it.copy(javaDownloadSource = value) } },
                    )
                },
                settingField(strings.t("settings.java.install.title")) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SimpleDropdown(
                            installVersion,
                            listOf(
                                "auto" to strings.t("settings.java.auto"),
                                "8" to "Java 8",
                                "17" to "Java 17",
                                "21" to "Java 21",
                                "25" to "Java 25",
                            ),
                            { installVersion = it },
                            Modifier.weight(1f),
                            enabled = !active,
                        )
                        SimpleDropdown(
                            installType,
                            listOf("jre" to "JRE", "jdk" to "JDK"),
                            { installType = it },
                            Modifier.width(110.dp),
                            enabled = !active,
                        )
                        PrimaryButton(strings.t("settings.java.install.action"), enabled = !active) {
                            val type = if (installType == "jdk") "jdk" else "jre"
                            installJob =
                                if (installVersion == "auto") {
                                    actions.controller.installAutoJava(type)
                                } else {
                                    actions.controller.installJava(installVersion.toInt(), type)
                                }
                            actions.taskQueued()
                        }
                    }
                    if (job != null) {
                        LinearProgressIndicator(
                            progress = { job.progress.coerceIn(0f, 1f) },
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (job.status) {
                                    JobStatus.Completed -> strings.t("settings.java.install.done")
                                    JobStatus.Failed, JobStatus.Cancelled ->
                                        strings.t(
                                            "settings.java.install.failed",
                                            job.error ?: job.detail,
                                        )
                                    else -> "${(job.progress * 100).toInt()}% · ${job.detail}"
                                },
                                Modifier.weight(1f),
                                fontSize = 11.sp,
                                color =
                                    if (job.status in
                                        setOf(
                                            JobStatus.Failed,
                                            JobStatus.Cancelled,
                                        )
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            if (active) {
                                SecondaryButton(
                                    { actions.controller.jobs.cancel(job.id) },
                                    contentPadding =
                                        PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(strings.t("settings.common.cancel"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                },
            ),
        ),
    )
}
