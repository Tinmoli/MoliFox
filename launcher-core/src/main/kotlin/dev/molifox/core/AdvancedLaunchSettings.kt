// 负责高级启动参数的校验、继承和合并。

package dev.molifox.core

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
)

fun effectiveLaunchSettings(
    settings: LauncherSettings,
    instance: GameInstance,
): AdvancedLaunchSettings = instance.advancedLaunch ?: settings.advancedLaunch

internal fun validateExtraJvmArguments(value: String) {
    require(value.length <= 4096) { "JVM 参数过长" }
    val arguments = splitArguments(value)
    require(
        arguments.none {
            it.startsWith('@') ||
                it in listOf("-cp", "-classpath", "--class-path", "-jar") ||
                it.startsWith("-Xmx") ||
                it.startsWith("-Xms")
        }
    ) {
        "内存请通过内存设置调整；附加参数不能覆盖游戏入口或 classpath"
    }
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
        }
    ) {
        "附加游戏参数不能覆盖账户、游戏目录、资源或窗口设置，请使用对应设置"
    }
    require(arguments.none { '\u0000' in it }) { "游戏参数不能包含空字符" }
    return arguments
}

fun validateAdvancedLaunchSettings(settings: AdvancedLaunchSettings, os: String) {
    extraGameArguments(settings)
    require(settings.commandTimeoutSeconds in 1..3600) { "命令超时需为 1–3600 秒" }
    require(settings.launcherVisibility in setOf("", "keep", "minimize", "hide")) { "启动器显示方式无效" }
    for ((label, command) in
        listOf("启动前" to settings.preLaunchCommand, "退出后" to settings.postExitCommand)) {
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

fun parseEnvironmentVariables(text: String, os: String): Map<String, String> {
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
        )
            "[已隐藏]"
        else redactSecrets(value)
    }

internal fun environmentSecrets(values: Map<String, String>): List<String> =
    values
        .filterKeys {
            Regex("(?i)token|secret|password|credential|api.?key|authorization").containsMatchIn(it)
        }
        .values
        .filter { it.length > 5 }

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

fun launcherWindowAction(event: GameRunEvent, traySupported: Boolean): LauncherWindowAction =
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
