// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把 § 颜色代码切成带样式的 MOTD 片段并合并同样式相邻段。

package dev.molifox.core

/** `§0`–`§f` 与原版颜色表的顺序一致。 */
private val legacyColorCodes: List<Int> =
    listOf(
        0x000000,
        0x0000AA,
        0x00AA00,
        0x00AAAA,
        0xAA0000,
        0xAA00AA,
        0xFFAA00,
        0xAAAAAA,
        0x555555,
        0x5555FF,
        0x55FF55,
        0x55FFFF,
        0xFF5555,
        0xFF55FF,
        0xFFFF55,
        0xFFFFFF,
    )

/** 追加一段可能带 `§` 代码的文字：代码切换样式并切断当前片段，`§r` 复位， 未知代码按普通字符保留。连续同样式的小段会合并，最终得到「每个样式一段」。 */
internal fun appendStyled(
    text: String,
    style: MotdStyle,
    out: MutableList<MotdSegment>,
) {
    if (text.isEmpty()) return
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.indexOf('§') < 0) {
        appendRun(normalized, style, out)
        return
    }
    val run = StringBuilder()
    var current = style
    var index = 0
    while (index < normalized.length) {
        val char = normalized[index]
        if (char == '§' && index + 1 < normalized.length) {
            val next = codeStyle(normalized[index + 1], current)
            if (next != null) {
                appendRun(run.toString(), current, out)
                run.setLength(0)
                current = next
                index += 2
                continue
            }
        }
        run.append(char)
        index += 1
    }
    appendRun(run.toString(), current, out)
}

/** `§` 代码对应的新样式；不是已知代码时返回 null（按字面量处理）。 */
private fun codeStyle(
    code: Char,
    style: MotdStyle,
): MotdStyle? {
    val lower = code.lowercaseChar()
    val colorIndex = "0123456789abcdef".indexOf(lower)
    if (colorIndex >= 0) {
        return MotdStyle(
            color = legacyColorCodes[colorIndex],
            bold = false,
            italic = false,
            underlined = false,
            struck = false,
        )
    }
    return when (lower) {
        // §k 是乱码效果，静态界面无法呈现：吞掉代码、不改其它样式。
        'k' -> style
        'l' -> style.copy(bold = true)
        'm' -> style.copy(struck = true)
        'n' -> style.copy(underlined = true)
        'o' -> style.copy(italic = true)
        'r' -> MotdStyle.Default
        else -> null
    }
}

/** 相邻文本段样式完全一致时才合并；逐项比较顺序与原条件一致。 */
private fun sameStyle(
    last: MotdSegment,
    style: MotdStyle,
): Boolean =
    last.color == style.color &&
        last.bold == style.bold &&
        last.italic == style.italic &&
        last.underlined == style.underlined &&
        last.struck == style.struck

private fun appendRun(
    text: String,
    style: MotdStyle,
    out: MutableList<MotdSegment>,
) {
    if (text.isEmpty()) return
    val last = out.lastOrNull()
    if (last != null && sameStyle(last, style)) {
        out[out.size - 1] = last.copy(text = last.text + text)
    } else {
        out +=
            MotdSegment(
                text = text,
                color = style.color,
                bold = style.bold,
                italic = style.italic,
                underlined = style.underlined,
                struck = style.struck,
            )
    }
}
