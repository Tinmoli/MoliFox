// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把服务器状态 JSON 的 description 解析成带样式的片段。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 解析过程中的「当前生效样式」；与 [MotdSegment] 的区别是它没有文字。 */
internal data class MotdStyle(
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val struck: Boolean = false,
) {
    companion object {
        val Default = MotdStyle()
    }
}

private val namedColors: Map<String, Int> =
    mapOf(
        "black" to 0x000000,
        "dark_blue" to 0x0000AA,
        "dark_green" to 0x00AA00,
        "dark_aqua" to 0x00AAAA,
        "dark_red" to 0xAA0000,
        "dark_purple" to 0xAA00AA,
        "gold" to 0xFFAA00,
        "gray" to 0xAAAAAA,
        "dark_gray" to 0x555555,
        "blue" to 0x5555FF,
        "green" to 0x55FF55,
        "aqua" to 0x55FFFF,
        "red" to 0xFF5555,
        "light_purple" to 0xFF55FF,
        "yellow" to 0xFFFF55,
        "white" to 0xFFFFFF,
    )

/** `#RRGGBB` 颜色解析用的进制。 */
private const val HEX_RADIX = 16

/**
 * 把状态 JSON 的 `description` 解析成扁平的样式片段。
 *
 * 支持纯字符串（其中可以带 `§` 代码）、组件对象（`text` + `extra` + `color` +
 * `bold/italic/underlined/strikethrough`）与组件数组；子组件继承父组件样式， 显式写了 `false`
 * 的开关会覆盖父级。没有可显示文字、或结构无法识别时返回空列表； 本函数从不抛异常。
 */
fun parseMotd(description: JsonElement?): List<MotdSegment> {
    val segments = ArrayList<MotdSegment>()
    runCatching { resolveMotd(description, MotdStyle.Default, segments) }
    return segments
}

/** 解析纯文本 MOTD（旧版协议、或 `description` 本身是字符串时）；`§` 代码规则相同。 */
fun parseMotdText(text: String): List<MotdSegment> {
    val segments = ArrayList<MotdSegment>()
    runCatching { appendStyled(text, MotdStyle.Default, segments) }
    return segments
}

private fun resolveMotd(
    element: JsonElement?,
    style: MotdStyle,
    out: MutableList<MotdSegment>,
) {
    if (element == null || element.isJsonNull) return
    when {
        element.isJsonPrimitive -> {
            if (element.asJsonPrimitive.isString) appendStyled(element.asString, style, out)
        }
        element.isJsonArray -> {
            val array = element.asJsonArray
            for (index in 0 until array.size()) resolveMotd(array[index], style, out)
        }
        element.isJsonObject -> resolveComponent(element.asJsonObject, style, out)
    }
}

/** 组件对象：先把自己的样式叠到父样式上、输出 `text`，再按顺序展开 `extra`。 */
private fun resolveComponent(
    obj: JsonObject,
    base: MotdStyle,
    out: MutableList<MotdSegment>,
) {
    val own = applyStyle(base, obj)
    val text = obj.get("text")
    if (text != null && text.isJsonPrimitive && text.asJsonPrimitive.isString) {
        appendStyled(text.asString, own, out)
    }
    val extra = obj.get("extra") ?: return
    if (extra.isJsonArray) {
        val array = extra.asJsonArray
        for (index in 0 until array.size()) resolveMotd(array[index], own, out)
    } else {
        resolveMotd(extra, own, out)
    }
}

/** 合并组件自己声明的样式；无法识别的值保持继承，不抛异常。 */
private fun applyStyle(
    base: MotdStyle,
    obj: JsonObject,
): MotdStyle {
    var style = base
    val color = obj.get("color")
    if (color != null && color.isJsonPrimitive && color.asJsonPrimitive.isString) {
        val name = color.asString
        // JSON 组件里 color 只改颜色，不像 § 代码那样清掉格式。
        style =
            if (name.equals("reset", ignoreCase = true)) {
                MotdStyle.Default
            } else {
                parseColor(name)?.let { style.copy(color = it) } ?: style
            }
    }
    return style.copy(
        bold = obj.flag("bold") ?: style.bold,
        italic = obj.flag("italic") ?: style.italic,
        underlined = obj.flag("underlined") ?: style.underlined,
        struck = obj.flag("strikethrough") ?: style.struck,
    )
}

private fun JsonObject.flag(key: String): Boolean? {
    val element = get(key) ?: return null
    if (!element.isJsonPrimitive) return null
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isBoolean -> primitive.asBoolean
        primitive.isString ->
            when (primitive.asString.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        else -> null
    }
}

/** 命名颜色或 `#RRGGBB`；认不出来返回 null（调用方保持继承）。 */
private fun parseColor(value: String): Int? {
    val named = namedColors[value.lowercase()]
    if (named != null) return named
    val hex = value.takeIf { it.length == 7 && it[0] == '#' }?.substring(1)
    return hex
        ?.takeIf { candidate -> candidate.all { it.digitToIntOrNull(HEX_RADIX) != null } }
        ?.toIntOrNull(HEX_RADIX)
}
