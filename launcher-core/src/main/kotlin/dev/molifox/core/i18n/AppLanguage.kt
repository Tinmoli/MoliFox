// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责界面语言的设置值、系统语言匹配与游戏语言映射。

package dev.molifox.core.i18n

import java.util.Locale

/**
 * 界面语言。
 *
 * [settingValue] 是写进配置文件的稳定值，不能翻译；[labelKey] 是设置下拉里的显示名。 [gameLanguage] 是「游戏语言跟随启动器」写入实例的游戏语言代码；
 * [AUTO] 没有固定值，使用时按实际解析结果决定。
 */
enum class AppLanguage(
    val settingValue: String,
    val labelKey: String,
    val gameLanguage: String,
) {
    AUTO("auto", "language.auto", ""),
    SIMPLIFIED_CHINESE("zh-CN", "language.zhCN", "zh_cn"),
    CAT_CHINESE("zh-CN-x-meow", "language.meow", "zh_cn"),
    TRADITIONAL_CHINESE("zh-TW", "language.zhTW", "zh_tw"),
    HONG_KONG_CHINESE("zh-HK", "language.zhHK", "zh_tw"),
    ENGLISH("en-US", "language.enUS", "en_us"),
    ;

    companion object {
        /** 设置下拉里的顺序：跟随系统、简体、繁体（台湾）、繁体（香港）、英文。 */
        val options: List<AppLanguage> = entries.toList()

        /** 旧配置里没有 language 字段时走 [AUTO]；无法识别的值同样按跟随系统处理，不把界面变成空白。 */
        fun fromSetting(value: String?): AppLanguage =
            entries.firstOrNull { it.settingValue.equals(value?.trim(), true) } ?: AUTO

        /** 把设置值解析成实际使用的语言标签。 */
        fun resolveTag(
            value: String?,
            systemTag: String = Locale.getDefault().toLanguageTag(),
        ): String {
            val language = fromSetting(value)
            return if (language == AUTO) matchSystem(systemTag) else language.settingValue
        }

        /**
         * 系统语言匹配：台湾 / 香港 / 澳门分别落到对应繁体，其它中文落到简体，非中文落到英文。
         *
         * 兼容 `zh_CN`、`zh-Hant-TW` 这类不同写法的 BCP 47 标签。
         */
        fun matchSystem(systemTag: String): String {
            val tag = systemTag.replace('_', '-').lowercase(Locale.ROOT)
            if (!tag.startsWith("zh")) return ENGLISH.settingValue
            return when {
                tag.contains("-tw") -> TRADITIONAL_CHINESE.settingValue
                tag.contains("-hk") || tag.contains("-mo") -> HONG_KONG_CHINESE.settingValue
                else -> SIMPLIFIED_CHINESE.settingValue
            }
        }
    }
}
