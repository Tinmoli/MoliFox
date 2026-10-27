package dev.molifox.core

/** Source arguments become editable instance overrides; repair never reapplies them. */
internal data class PackLaunchOptions(val jvm: List<String>, val game: List<String>) {
    fun resolve(settings: LauncherSettings, os: String): PackLaunchDefaults {
        val jvmText =
            if (jvm.isEmpty()) null
            else
                listOf(settings.extraJvmArgs, packArgumentsText(jvm))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
        if (jvmText != null) validateExtraJvmArguments(jvmText)
        val advanced =
            if (game.isEmpty()) null
            else
                settings.advancedLaunch.copy(
                    extraGameArgs =
                        listOf(settings.advancedLaunch.extraGameArgs, packArgumentsText(game))
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                )
        if (advanced != null) validateAdvancedLaunchSettings(advanced, os)
        return PackLaunchDefaults(jvmText, advanced)
    }
}

internal data class PackLaunchDefaults(val jvm: String?, val advanced: AdvancedLaunchSettings?)

/** The existing text editor supports single or double quoting; refuse lossy conversions. */
internal fun packArgumentsText(arguments: List<String>): String {
    require(arguments.size <= 256) { "整合包启动参数过多" }
    val text =
        arguments.joinToString(" ") { value ->
            require(
                value.isNotEmpty() && value.none { it == '\u0000' || it == '\r' || it == '\n' }
            ) {
                "整合包参数不能包含空参数、空字符或换行"
            }
            when {
                '\'' !in value -> "'$value'"
                '"' !in value -> "\"$value\""
                else -> error("整合包参数同时含单双引号，暂不能无损导入")
            }
        }
    require(text.length <= 4096) { "整合包启动参数过长" }
    return text
}
