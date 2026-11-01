// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责高级启动配置的表单编辑。

package dev.molifox.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.AdvancedLaunchSettings
import dev.molifox.core.parseEnvironmentVariables
import dev.molifox.core.validateAdvancedLaunchSettings

@Composable
fun AdvancedLaunchEditor(
    value: AdvancedLaunchSettings,
    os: String,
    onChange: (AdvancedLaunchSettings) -> Unit,
    onValidityChange: (Boolean) -> Unit,
) {
    var timeoutText by remember { mutableStateOf(value.commandTimeoutSeconds.toString()) }
    var environmentText by remember {
        mutableStateOf(
            value.environmentVariables.entries.joinToString("\n") { "${it.key}=${it.value}" },
        )
    }
    val strings = LocalStrings.current
    val error =
        runCatching {
            require(timeoutText.toIntOrNull() in 1..3600) {
                strings.t("settings.game.advanced.timeout")
            }
            parseEnvironmentVariables(environmentText, os)
            validateAdvancedLaunchSettings(value, os)
        }.exceptionOrNull()
            ?.message
    LaunchedEffect(error) { onValidityChange(error == null) }
    SettingRow(
        strings.t("settings.game.advanced.verify.title"),
        strings.t("settings.game.advanced.verify.detail"),
    ) {
        CompactSwitch(value.verifyFileHashes, { onChange(value.copy(verifyFileHashes = it)) })
    }
    if (os == "windows") {
        SettingRow(
            strings.t("settings.game.advanced.javaExe.title"),
            strings.t("settings.game.advanced.javaExe.detail"),
        ) {
            CompactSwitch(
                value.useJavaConsoleExecutable,
                { onChange(value.copy(useJavaConsoleExecutable = it)) },
            )
        }
    }
    OutlinedTextField(
        value.windowTitle,
        { onChange(value.copy(windowTitle = it.take(128))) },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.windowTitle")) },
        placeholder = { Text(strings.t("settings.game.advanced.windowTitle.placeholder")) },
        singleLine = true,
    )
    MutedText(strings.t("settings.game.advanced.windowTitleHint"))
    OutlinedTextField(
        value.customInfo,
        { onChange(value.copy(customInfo = it.take(64))) },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.customInfo")) },
        placeholder = { Text(strings.t("settings.game.advanced.customInfo.placeholder")) },
        singleLine = true,
    )
    if (os == "windows") {
        SettingRow(
            strings.t("settings.game.advanced.gpu.title"),
            strings.t("settings.game.advanced.gpu.detail"),
        ) {
            CompactSwitch(
                value.highPerformanceGpu,
                { onChange(value.copy(highPerformanceGpu = it)) },
            )
        }
    }
    OutlinedTextField(
        value.extraGameArgs,
        { onChange(value.copy(extraGameArgs = it)) },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.gameArgs")) },
        placeholder = { Text(strings.t("settings.game.advanced.gameArgs.placeholder")) },
        minLines = 1,
        maxLines = 4,
    )
    OutlinedTextField(
        environmentText,
        { text ->
            environmentText = text
            runCatching { parseEnvironmentVariables(text, os) }
                .onSuccess { onChange(value.copy(environmentVariables = it)) }
        },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.env")) },
        placeholder = { Text(strings.t("settings.game.advanced.env.placeholder")) },
        minLines = 1,
        maxLines = 5,
    )
    MutedText(strings.t("settings.game.advanced.envHint"))
    OutlinedTextField(
        value.preLaunchCommand,
        { onChange(value.copy(preLaunchCommand = it)) },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.preLaunch")) },
        singleLine = true,
    )
    OutlinedTextField(
        value.postExitCommand,
        { onChange(value.copy(postExitCommand = it)) },
        Modifier.fillMaxWidth(),
        label = { Text(strings.t("settings.game.advanced.postExit")) },
        singleLine = true,
    )
    OutlinedTextField(
        timeoutText,
        {
            timeoutText = it
            it
                .toIntOrNull()
                ?.takeIf { seconds -> seconds in 1..3600 }
                ?.let { seconds -> onChange(value.copy(commandTimeoutSeconds = seconds)) }
        },
        label = { Text(strings.t("settings.game.advanced.timeoutLabel")) },
        singleLine = true,
    )
    MutedText(strings.t("settings.game.advanced.commandHint"))
    SettingRow(strings.t("settings.game.advanced.visibility.title")) {
        val visibility = value.launcherVisibility
        SimpleDropdown(
            visibility,
            listOf(
                "keep" to strings.t("settings.game.advanced.visibility.keep"),
                "minimize" to strings.t("settings.game.advanced.visibility.minimize"),
                "hide" to strings.t("settings.game.advanced.visibility.hide"),
            ),
            { onChange(value.copy(launcherVisibility = it)) },
            Modifier.widthIn(min = 180.dp, max = 230.dp),
        )
    }
    SettingRow(
        strings.t("settings.game.advanced.closeAfterExit.title"),
        strings.t("settings.game.advanced.closeAfterExit.detail"),
    ) {
        Switch(
            value.closeLauncherAfterGameExit,
            { onChange(value.copy(closeLauncherAfterGameExit = it)) },
        )
    }
    MutedText(strings.t("settings.game.advanced.visibilityHint"))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
