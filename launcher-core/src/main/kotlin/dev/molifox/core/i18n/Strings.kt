// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责一套界面文案的加载结果、占位符格式与缺失回退。

package dev.molifox.core.i18n

/**
 * 一套已加载的界面文案。
 *
 * 查找顺序见 [I18n.catalog] 的 fallback 链：先本语言，再回退语言，最终返回 `{key}` 形式的标记， 让漏翻在测试与界面上都能被发现，而不是静默显示空串。
 */
class Strings internal constructor(
    /** 实际使用的语言标签，例如 `zh-CN` / `en-US`。 */
    val languageTag: String,
    private val fallback: Strings?,
    private val values: Map<String, String>,
) {
    /** 取一条文案并按 `{0}` / `{1}` 填入参数；没有参数时原样返回。 */
    fun t(
        key: String,
        vararg args: Any?,
    ): String {
        val template = values[key] ?: fallback?.values?.get(key) ?: return "{$key}"
        return if (args.isEmpty()) template else format(template, args)
    }

    /** 本语言或回退语言里是否存在该 key。 */
    fun has(key: String): Boolean = key in values || fallback?.has(key) == true

    /** 本语言实际写出的 key（不含回退语言），供资源校验使用。 */
    internal fun ownKeys(): Set<String> = values.keys

    internal fun ownValues(): Map<String, String> = values
}

private val PLACEHOLDER = Regex("""\{(\d+)\}""")

/**
 * 简单占位符格式：`{0}` / `{1}`。
 *
 * 不用 [java.text.MessageFormat]：英文里的单引号会被它当成转义符，翻译时很容易踩坑；界面文案也不需要复数/日期格式。
 */
internal fun format(
    template: String,
    args: Array<out Any?>,
): String =
    PLACEHOLDER.replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()
        if (index == null) {
            match.value
        } else {
            args.getOrNull(index)?.toString() ?: match.value
        }
    }
