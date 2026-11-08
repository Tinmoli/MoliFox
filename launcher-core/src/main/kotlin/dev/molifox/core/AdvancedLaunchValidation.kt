// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责高级启动设置的字段级校验，从模型与解析文件拆出以保持单文件职责。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 校验高级启动设置的时长、窗口与自定义信息字段。 */
fun validateAdvancedLaunchSettings(
    settings: AdvancedLaunchSettings,
    os: String,
) {
    extraGameArguments(settings)
    validateTimeoutsAndText(settings)
    validateSingleLineFields(settings)
    validateLaunchCommands(settings)
    validateEnvironmentVariables(settings, os)
}

private fun validateTimeoutsAndText(settings: AdvancedLaunchSettings) {
    require(settings.commandTimeoutSeconds in 1..MAX_LAUNCH_COMMAND_TIMEOUT_SECONDS) {
        I18n.t("core.advancedLaunchSettings.6")
    }
    require(settings.launcherVisibility in setOf("", "keep", "minimize", "hide")) {
        I18n.t("core.advancedLaunchSettings.7")
    }
    require(settings.windowTitle.length <= MAX_WINDOW_TITLE_CHARS) {
        I18n.t("core.advancedLaunchSettings.8", MAX_WINDOW_TITLE_CHARS)
    }
    require(settings.customInfo.length <= MAX_CUSTOM_INFO_CHARS) {
        I18n.t("core.advancedLaunchSettings.9", MAX_CUSTOM_INFO_CHARS)
    }
}

/** 需要单行校验的文本字段：标签 key 用于提示文案，值用于校验；判断只看 key。 */
private fun singleLineFields(settings: AdvancedLaunchSettings): List<Pair<String, String>> =
    listOf(
        "core.advancedLaunchSettings.label.windowTitle" to settings.windowTitle,
        "core.advancedLaunchSettings.label.customInfo" to settings.customInfo,
    )

private fun validateSingleLineFields(settings: AdvancedLaunchSettings) {
    for ((labelKey, value) in singleLineFields(settings)) {
        val label = I18n.t(labelKey)
        require('\u0000' !in value && '\r' !in value && '\n' !in value) {
            I18n.t("core.advancedLaunchSettings.12", label)
        }
    }
}

private fun validateLaunchCommands(settings: AdvancedLaunchSettings) {
    for (
    (labelKey, command) in
    listOf(
        "core.advancedLaunchSettings.label.preLaunch" to settings.preLaunchCommand,
        "core.advancedLaunchSettings.label.postExit" to settings.postExitCommand,
    )
    ) {
        val label = I18n.t(labelKey)
        require('\u0000' !in command && '\r' !in command && '\n' !in command) {
            I18n.t("core.advancedLaunchSettings.13", label)
        }
        try {
            splitArguments(command)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(I18n.t("core.advancedLaunchSettings.14", label))
        }
    }
}

private fun validateEnvironmentVariables(
    settings: AdvancedLaunchSettings,
    os: String,
) {
    val names = hashSetOf<String>()
    for ((name, value) in settings.environmentVariables) {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) {
            I18n.t("core.advancedLaunchSettings.envInvalid", name)
        }
        require('\u0000' !in value && '\r' !in value && '\n' !in value) {
            I18n.t("core.advancedLaunchSettings.15", name)
        }
        require(names.add(if (os == "windows") name.lowercase() else name)) {
            I18n.t("core.advancedLaunchSettings.16", name)
        }
    }
}
