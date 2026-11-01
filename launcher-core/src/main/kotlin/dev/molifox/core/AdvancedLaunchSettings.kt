// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责高级启动参数的校验、继承和合并。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

private const val MAX_EXTRA_JVM_ARGUMENT_CHARS = 4096
internal const val MAX_LAUNCH_COMMAND_TIMEOUT_SECONDS = 3600
internal const val MAX_WINDOW_TITLE_CHARS = 128
internal const val MAX_CUSTOM_INFO_CHARS = 64
private const val REDACTION_LENGTH_THRESHOLD = 5

/**
 * An instance either inherits this whole group or owns an explicit copy, including empty values.
 */
data class AdvancedLaunchSettings(
    val extraGameArgs: String = "",
    val environmentVariables: Map<String, String> = emptyMap(),
    val preLaunchCommand: String = "",
    val postExitCommand: String = "",
    val commandTimeoutSeconds: Int = 60,
    val launcherVisibility: String = "keep",
    val closeLauncherAfterGameExit: Boolean = false,
    val verifyFileHashes: Boolean = true,
    val useJavaConsoleExecutable: Boolean = false,
    /** 游戏窗口标题模板；支持 `{name}`、`{user}`、`{version}`、`{date}`、`{time}`，留空不修改。 */
    val windowTitle: String = "",
    /** 显示在游戏主界面左下角与 F3 的信息，写入 `--versionType`；留空沿用版本类型。 */
    val customInfo: String = "",
    /** Windows：把本次使用的 Java 设为「高性能」显卡偏好；其他平台忽略。 */
    val highPerformanceGpu: Boolean = false,
)

/** 解析窗口标题里的替换标记；未知标记保持原样，避免用户内容被意外吞掉。 */
internal fun resolveLaunchText(
    template: String,
    instanceName: String,
    playerName: String,
    versionId: String,
    date: String,
    time: String,
): String =
    template
        .replace("{name}", instanceName)
        .replace("{user}", playerName)
        .replace("{version}", versionId)
        .replace("{date}", date)
        .replace("{time}", time)

/** 自定义信息优先于版本 JSON 的 `type`；空串表示不覆盖。 */
internal fun effectiveVersionType(
    settings: AdvancedLaunchSettings,
    fallback: String,
): String = settings.customInfo.trim().ifBlank { fallback }

fun effectiveLaunchSettings(
    settings: LauncherSettings,
    instance: GameInstance,
): AdvancedLaunchSettings = instance.advancedLaunch ?: settings.advancedLaunch

/** 需要单行校验的文本字段：标签 key 用于提示文案，值用于校验；判断只看 key。 */
internal fun validateExtraJvmArguments(value: String) {
    require(value.length <= MAX_EXTRA_JVM_ARGUMENT_CHARS) { I18n.t("core.advancedLaunchSettings.1") }
    extraJvmArguments(value)
}

/** 解析并拒绝会覆盖入口/内存的 JVM 参数；长度上限只在保存设置时检查。 */
internal fun extraJvmArguments(value: String): List<String> {
    val arguments = splitArguments(value)
    require(
        arguments.none {
            it.startsWith('@') ||
                it in listOf("-cp", "-classpath", "--class-path", "-jar") ||
                it.startsWith("-Xmx") ||
                it.startsWith("-Xms")
        },
    ) {
        I18n.t("core.advancedLaunchSettings.2")
    }
    return arguments
}

private val managedGameFlags =
    setOf(
        "--username",
        "--uuid",
        "--accesstoken",
        "--usertype",
        "--gamedir",
        "--assetsdir",
        "--assetindex",
        "--version",
        "--versiontype",
        "--clientid",
        "--xuid",
        "--width",
        "--height",
        "--fullscreen",
        "--userproperties",
    )

internal fun extraGameArguments(settings: AdvancedLaunchSettings): List<String> {
    val arguments =
        try {
            splitArguments(settings.extraGameArgs)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(I18n.t("core.advancedLaunchSettings.3"))
        }
    require(
        arguments.none {
            it.startsWith('@') || it.substringBefore('=').lowercase() in managedGameFlags
        },
    ) {
        I18n.t("core.advancedLaunchSettings.4")
    }
    require(arguments.none { '\u0000' in it }) { I18n.t("core.advancedLaunchSettings.5") }
    return arguments
}

fun parseEnvironmentVariables(
    text: String,
    os: String,
): Map<String, String> {
    val values = linkedMapOf<String, String>()
    text
        .lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            require('=' in line) { I18n.t("core.advancedLaunchSettings.17") }
            val name = line.substringBefore('=').trim()
            require(name !in values) { I18n.t("core.advancedLaunchSettings.16", name) }
            values[name] = line.substringAfter('=')
        }
    validateAdvancedLaunchSettings(AdvancedLaunchSettings(environmentVariables = values), os)
    return values
}

internal fun environmentForDiagnostics(values: Map<String, String>): Map<String, String> =
    values.mapValues { (name, value) ->
        if (
            Regex("(?i)token|secret|password|credential|api.?key|authorization")
                .containsMatchIn(name)
        ) {
            I18n.t("core.advancedLaunchSettings.18")
        } else {
            redactSecrets(value)
        }
    }

internal fun environmentSecrets(values: Map<String, String>): List<String> =
    values
        .filterKeys {
            Regex("(?i)token|secret|password|credential|api.?key|authorization").containsMatchIn(it)
        }.values
        .filter { it.length > REDACTION_LENGTH_THRESHOLD }

data class GameRunEvent(
    val id: String,
    val instanceId: String,
    val visibility: String,
    val closeAfterExit: Boolean,
    val finished: Boolean = false,
    val exitCode: Int? = null,
    val postCommandFailed: Boolean = false,
    val processId: Long? = null,
)

enum class LauncherWindowAction {
    Keep,
    Minimize,
    Hide,
    Restore,
    Exit,
}

fun launcherWindowAction(
    event: GameRunEvent,
    traySupported: Boolean,
): LauncherWindowAction =
    when {
        event.finished && event.closeAfterExit && event.exitCode == 0 && !event.postCommandFailed ->
            LauncherWindowAction.Exit
        event.finished &&
            (event.visibility != "keep" || event.postCommandFailed || event.exitCode != 0) ->
            LauncherWindowAction.Restore
        event.finished -> LauncherWindowAction.Keep
        event.visibility == "hide" && traySupported -> LauncherWindowAction.Hide
        event.visibility in setOf("hide", "minimize") -> LauncherWindowAction.Minimize
        else -> LauncherWindowAction.Keep
    }
