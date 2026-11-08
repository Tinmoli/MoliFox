// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责高级启动配置的表单编辑。

package dev.molifox.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    // onValidityChange 是 lambda 参数，每次重组都是新实例；用 rememberUpdatedState 持有最新值，
    // 让 effect 只随 error 变化重启，回调时通知的是最新的有效性监听者。
    val currentOnValidityChange by rememberUpdatedState(onValidityChange)
    LaunchedEffect(error) { currentOnValidityChange(error == null) }
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
    CompactTextField(
        value.windowTitle,
        { onChange(value.copy(windowTitle = it.take(128))) },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.windowTitle"),
        placeholder = strings.t("settings.game.advanced.windowTitle.placeholder"),
    )
    MutedText(strings.t("settings.game.advanced.windowTitleHint"))
    CompactTextField(
        value.customInfo,
        { onChange(value.copy(customInfo = it.take(64))) },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.customInfo"),
        placeholder = strings.t("settings.game.advanced.customInfo.placeholder"),
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
    CompactTextField(
        value.extraGameArgs,
        { onChange(value.copy(extraGameArgs = it)) },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.gameArgs"),
        placeholder = strings.t("settings.game.advanced.gameArgs.placeholder"),
        maxLines = 4,
    )
    CompactTextField(
        environmentText,
        { text ->
            environmentText = text
            runCatching { parseEnvironmentVariables(text, os) }
                .onSuccess { onChange(value.copy(environmentVariables = it)) }
        },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.env"),
        placeholder = strings.t("settings.game.advanced.env.placeholder"),
        maxLines = 5,
    )
    MutedText(strings.t("settings.game.advanced.envHint"))
    CompactTextField(
        value.preLaunchCommand,
        { onChange(value.copy(preLaunchCommand = it)) },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.preLaunch"),
    )
    CompactTextField(
        value.postExitCommand,
        { onChange(value.copy(postExitCommand = it)) },
        Modifier.fillMaxWidth(),
        label = strings.t("settings.game.advanced.postExit"),
    )
    CompactTextField(
        timeoutText,
        {
            timeoutText = it
            it
                .toIntOrNull()
                ?.takeIf { seconds -> seconds in 1..3600 }
                ?.let { seconds -> onChange(value.copy(commandTimeoutSeconds = seconds)) }
        },
        label = strings.t("settings.game.advanced.timeoutLabel"),
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
