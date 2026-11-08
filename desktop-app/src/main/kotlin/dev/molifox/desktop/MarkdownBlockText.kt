// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Markdown 预处理、表格行与段落的文本拼接。

package dev.molifox.desktop

/** 全角字符起始码位：软换行时中日韩文字之间不插空格。 */
private const val CJK_WIDE_CHAR_START_CODE = 0x2E80

private const val MAX_MARKDOWN_CHARS = 200_000

private val scriptPattern = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
private val brPattern = Regex("(?i)<br\\s*/?>")
private val blockTagPattern =
    Regex("(?i)</(?:p|div|li|ul|ol|h[1-6]|tr|td|th|table|blockquote|details|summary|section)>")
private val tagPattern = Regex("</?[A-Za-z][^<>]*>")

internal fun sanitizeMarkdown(markdown: String): String {
    if (markdown.isBlank()) return ""
    var text = markdown.take(MAX_MARKDOWN_CHARS).replace("\r\n", "\n").replace('\r', '\n')
    text = scriptPattern.replace(text, "")
    // 先换行，再去标签，保证 <br> 不会和相邻文字粘在一起。
    text = brPattern.replace(text, "\n")
    text = blockTagPattern.replace(text, "\n")
    text = tagPattern.replace(text, "")
    text = decodeEntities(text)
    text = text.replace("\u0000", "")
    return text.replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun decodeEntities(text: String): String =
    text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

internal fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length > 2 && trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
}

internal fun splitTableRow(line: String): List<String> =
    line
        .trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split('|')
        .map { it.trim() }

internal fun isTableDelimiterRow(cells: List<String>): Boolean {
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val body = cell.replace(":", "").trim()
        body.isNotEmpty() && body.all { it == '-' }
    }
}

/** CommonMark 软换行：中文之间不插空格，西文之间插一个空格。 */
internal fun joinParagraph(parts: List<String>): String {
    if (parts.size <= 1) return parts.firstOrNull().orEmpty()
    val joined = StringBuilder(parts.first())
    for (part in parts.drop(1)) {
        val previous = joined.lastOrNull() ?: ' '
        if (isWideChar(previous) || isWideChar(part.firstOrNull() ?: ' ')) {
            joined.append(part)
        } else {
            joined.append(' ').append(part)
        }
    }
    return joined.toString()
}

internal fun isWideChar(value: Char) = value.code >= CJK_WIDE_CHAR_START_CODE
