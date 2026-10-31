// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责高级启动参数的校验、继承和合并。

package dev.molifox.core

private const val MAX_EXTRA_JVM_ARGUMENT_CHARS = 4096
private const val MAX_LAUNCH_COMMAND_TIMEOUT_SECONDS = 3600
private const val MAX_WINDOW_TITLE_CHARS = 128
private const val MAX_CUSTOM_INFO_CHARS = 64
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

internal fun validateExtraJvmArguments(value: String) {
    require(value.length <= MAX_EXTRA_JVM_ARGUMENT_CHARS) { "JVM 参数过长" }
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
        "内存请通过内存设置调整；附加参数不能覆盖游戏入口或 classpath"
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
            throw IllegalArgumentException("附加游戏参数引号未闭合")
        }
    require(
        arguments.none {
            it.startsWith('@') || it.substringBefore('=').lowercase() in managedGameFlags
        },
    ) {
        "附加游戏参数不能覆盖账户、游戏目录、资源或窗口设置，请使用对应设置"
    }
    require(arguments.none { '\u0000' in it }) { "游戏参数不能包含空字符" }
    return arguments
}

fun validateAdvancedLaunchSettings(
    settings: AdvancedLaunchSettings,
    os: String,
) {
    extraGameArguments(settings)
    require(settings.commandTimeoutSeconds in 1..MAX_LAUNCH_COMMAND_TIMEOUT_SECONDS) {
        "命令超时需为 1–3600 秒"
    }
    require(settings.launcherVisibility in setOf("", "keep", "minimize", "hide")) { "启动器显示方式无效" }
    require(settings.windowTitle.length <= MAX_WINDOW_TITLE_CHARS) {
        "游戏窗口标题最多 $MAX_WINDOW_TITLE_CHARS 字"
    }
    require(settings.customInfo.length <= MAX_CUSTOM_INFO_CHARS) {
        "自定义信息最多 $MAX_CUSTOM_INFO_CHARS 字"
    }
    for (label in listOf("游戏窗口标题", "自定义信息")) {
        val value = if (label == "游戏窗口标题") settings.windowTitle else settings.customInfo
        require('\u0000' !in value && '\r' !in value && '\n' !in value) {
            "$label 必须是单行文本"
        }
    }
    for (
    (label, command) in
    listOf("启动前" to settings.preLaunchCommand, "退出后" to settings.postExitCommand)
    ) {
        require('\u0000' !in command && '\r' !in command && '\n' !in command) {
            "${label}命令需为单行；复杂操作请调用脚本文件"
        }
        try {
            splitArguments(command)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("${label}命令引号未闭合")
        }
    }
    val names = hashSetOf<String>()
    for ((name, value) in settings.environmentVariables) {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "环境变量名称无效：$name" }
        require('\u0000' !in value && '\r' !in value && '\n' !in value) { "环境变量值必须是单行文本：$name" }
        require(names.add(if (os == "windows") name.lowercase() else name)) { "环境变量名称重复：$name" }
    }
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
            require('=' in line) { "环境变量每行使用 名称=值" }
            val name = line.substringBefore('=').trim()
            require(name !in values) { "环境变量名称重复：$name" }
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
            "[已隐藏]"
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
