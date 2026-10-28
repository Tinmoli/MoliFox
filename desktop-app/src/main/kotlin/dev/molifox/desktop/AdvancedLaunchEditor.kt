// 负责高级启动配置的表单编辑。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.*

@Composable
fun AdvancedLaunchEditor(
    value: AdvancedLaunchSettings,
    os: String,
    onChange: (AdvancedLaunchSettings) -> Unit,
    onValidityChange: (Boolean) -> Unit,
    legacyMinimize: Boolean = false,
) {
    var timeoutText by remember { mutableStateOf(value.commandTimeoutSeconds.toString()) }
    var environmentText by remember {
        mutableStateOf(
            value.environmentVariables.entries.joinToString("\n") { "${it.key}=${it.value}" }
        )
    }
    val error = runCatching {
        require(timeoutText.toIntOrNull() in 1..3600) { "命令超时需为 1–3600 秒" }
        parseEnvironmentVariables(environmentText, os)
        validateAdvancedLaunchSettings(value, os)
    }
        .exceptionOrNull()
        ?.message
    LaunchedEffect(error) { onValidityChange(error == null) }
    OutlinedTextField(
        value.extraGameArgs,
        { onChange(value.copy(extraGameArgs = it)) },
        Modifier.fillMaxWidth(),
        label = { Text("附加游戏参数") },
        placeholder = { Text("例如 --server example.org --port 25565") },
        minLines = 2,
    )
    OutlinedTextField(
        environmentText,
        { text ->
            environmentText = text
            runCatching { parseEnvironmentVariables(text, os) }
                .onSuccess { onChange(value.copy(environmentVariables = it)) }
        },
        Modifier.fillMaxWidth(),
        label = { Text("游戏环境变量") },
        placeholder = { Text("每行一个 NAME=value") },
        minLines = 3,
    )
    MutedText("参数中含空格的值请加引号。环境变量仅传给本次游戏；等号后的空格按原样保留。")
    OutlinedTextField(
        value.preLaunchCommand,
        { onChange(value.copy(preLaunchCommand = it)) },
        Modifier.fillMaxWidth(),
        label = { Text("启动前命令") },
        singleLine = true,
    )
    OutlinedTextField(
        value.postExitCommand,
        { onChange(value.copy(postExitCommand = it)) },
        Modifier.fillMaxWidth(),
        label = { Text("游戏退出后命令") },
        singleLine = true,
    )
    OutlinedTextField(
        timeoutText,
        {
            timeoutText = it
            it.toIntOrNull()
                ?.takeIf { seconds -> seconds in 1..3600 }
                ?.let { seconds -> onChange(value.copy(commandTimeoutSeconds = seconds)) }
        },
        label = { Text("命令超时（秒）") },
        singleLine = true,
    )
    MutedText(
        "留空不执行；命令在游戏目录运行，不自动解析管道或变量。复杂操作请显式调用脚本解释器。可从环境读取 MOLIFOX_GAME_DIR、MOLIFOX_JAVA_PATH；退出后还提供 MOLIFOX_EXIT_CODE。"
    )
    SettingRow("游戏运行时的启动器") {
        val visibility =
            value.launcherVisibility.ifBlank { if (legacyMinimize) "minimize" else "keep" }
        SimpleDropdown(
            when (visibility) {
                "minimize" -> "最小化"
                "hide" -> "隐藏到托盘"
                else -> "保持显示"
            },
            listOf("keep" to "保持显示", "minimize" to "最小化", "hide" to "隐藏到托盘"),
            { onChange(value.copy(launcherVisibility = it)) },
            Modifier.widthIn(min = 180.dp, max = 230.dp),
        )
    }
    SettingRow("游戏正常退出后关闭启动器", "命令失败或游戏异常退出时保留窗口，便于查看原因") {
        Switch(
            value.closeLauncherAfterGameExit,
            { onChange(value.copy(closeLauncherAfterGameExit = it)) },
        )
    }
    MutedText("最小化或隐藏时，游戏结束后恢复窗口；系统不支持托盘时使用最小化。")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
