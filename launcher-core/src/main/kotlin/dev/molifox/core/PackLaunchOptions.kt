// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包启动选项的转换与校验。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

private const val MAX_PACK_LAUNCH_ARGUMENTS = 256
private const val MAX_PACK_LAUNCH_ARGUMENT_CHARS = 4096

/**
 * Source arguments become editable instance overrides; repair never reapplies them.
 * [overrideJvm] 对应实例配置中的 `OverrideJavaArgs`：为真时实例参数替换全局，而不是追加。
 */
internal data class PackLaunchOptions(
    val jvm: List<String>,
    val game: List<String>,
    val overrideJvm: Boolean = false,
) {
    fun resolve(
        settings: LauncherSettings,
        os: String,
    ): PackLaunchDefaults {
        val jvmText =
            when {
                jvm.isEmpty() -> null
                overrideJvm -> packArgumentsText(jvm)
                else ->
                    listOf(settings.extraJvmArgs, packArgumentsText(jvm))
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
            }
        if (jvmText != null) validateExtraJvmArguments(jvmText)
        val advanced =
            if (game.isEmpty()) {
                null
            } else {
                settings.advancedLaunch.copy(
                    extraGameArgs =
                        listOf(settings.advancedLaunch.extraGameArgs, packArgumentsText(game))
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                )
            }
        if (advanced != null) validateAdvancedLaunchSettings(advanced, os)
        return PackLaunchDefaults(jvmText, advanced)
    }
}

internal data class PackLaunchDefaults(
    val jvm: String?,
    val advanced: AdvancedLaunchSettings?,
)

/** The existing text editor supports single or double quoting; refuse lossy conversions. */
internal fun packArgumentsText(arguments: List<String>): String {
    require(arguments.size <= MAX_PACK_LAUNCH_ARGUMENTS) { I18n.t("packsA2.packLaunchOptions.tooManyArguments") }
    val text =
        arguments.joinToString(" ") { value ->
            require(
                value.isNotEmpty() && value.none { it == '\u0000' || it == '\r' || it == '\n' },
            ) {
                I18n.t("packsA2.packLaunchOptions.argumentInvalid")
            }
            when {
                '\'' !in value -> "'$value'"
                '"' !in value -> "\"$value\""
                else -> error(I18n.t("packsA2.packLaunchOptions.mixedQuotes"))
            }
        }
    require(text.length <= MAX_PACK_LAUNCH_ARGUMENT_CHARS) { I18n.t("packsA2.packLaunchOptions.argumentsTooLong") }
    return text
}
