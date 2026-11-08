// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Markdown 的块级解析：标题、段落、引用、列表、代码块与表格。

package dev.molifox.desktop

private val fencePattern = Regex("^(`{3,}|~{3,})[ \\t]*(.*)$")
private val headingPattern = Regex("^(#{1,6})[ \\t]+(.*)$")
private val rulePattern = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
private val listPattern = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)$")

internal sealed interface MarkdownBlock

internal data class MarkdownHeading(
    val level: Int,
    val text: String,
) : MarkdownBlock

internal data class MarkdownParagraph(
    val text: String,
) : MarkdownBlock

internal data class MarkdownQuote(
    val text: String,
) : MarkdownBlock

internal data class MarkdownListItem(
    val marker: String,
    val level: Int,
    val text: String,
)

internal data class MarkdownList(
    val items: List<MarkdownListItem>,
) : MarkdownBlock

internal data class MarkdownCode(
    val code: String,
    val language: String,
) : MarkdownBlock

internal data class MarkdownTable(
    val rows: List<List<String>>,
) : MarkdownBlock

internal data object MarkdownRule : MarkdownBlock

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val source = sanitizeMarkdown(markdown)
    if (source.isBlank()) return emptyList()
    val lines = source.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        index = readNextBlock(lines, index, blocks)
    }
    return blocks
}

/** 读取 index 处的一个块级元素，追加到 blocks 后返回下一个待处理行号。 */
private fun readNextBlock(
    lines: List<String>,
    index: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val raw = lines[index]
    val line = raw.trim()
    if (line.isEmpty()) return index + 1
    val fence = fencePattern.matchEntire(line)
    if (fence != null) return readFencedCode(lines, index, fence, blocks)
    val heading = headingPattern.matchEntire(line)
    if (heading != null) {
        blocks += MarkdownHeading(heading.groupValues[1].length, heading.groupValues[2].trim())
        return index + 1
    }
    if (rulePattern.matches(line)) {
        blocks += MarkdownRule
        return index + 1
    }
    if (line.startsWith(">")) return readQuote(lines, index, blocks)
    if (isTableRow(line)) return readTable(lines, index, blocks)
    if (listPattern.matchEntire(raw) != null) return readList(lines, index, blocks)
    return readParagraph(lines, index, blocks)
}

/** 连续以 > 开头的行合并成一个引用块。 */
private fun readQuote(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val quoted = mutableListOf<String>()
    var index = start
    while (index < lines.size && lines[index].trim().startsWith(">")) {
        quoted += lines[index].trim().removePrefix(">").trimStart()
        index++
    }
    blocks += MarkdownQuote(quoted.joinToString("\n"))
    return index
}

/** 连续的表行合并成一张表；分隔行与全空行不计入数据。 */
private fun readTable(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val rows = mutableListOf<List<String>>()
    var index = start
    while (index < lines.size && isTableRow(lines[index].trim())) {
        val cells = splitTableRow(lines[index])
        if (!isTableDelimiterRow(cells) && cells.any { it.isNotEmpty() }) rows += cells
        index++
    }
    if (rows.isNotEmpty()) blocks += MarkdownTable(rows)
    return index
}

/** 连续的列表项合并成一个列表；空列表项也要前进，避免死循环。 */
private fun readList(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val items = mutableListOf<MarkdownListItem>()
    var orderedNumber = 0
    var cursor = start
    while (cursor < lines.size) {
        val item = listPattern.matchEntire(lines[cursor]) ?: break
        val (entry, nextNumber) = listItemOf(item, orderedNumber)
        if (entry != null) items += entry
        orderedNumber = nextNumber
        cursor++
    }
    if (items.isNotEmpty()) blocks += MarkdownList(items)
    return maxOf(cursor, start + 1)
}

/** 解析单个列表项：有序列表保留作者起始序号，后续条目顺延。 */
private fun listItemOf(
    match: MatchResult,
    orderedNumber: Int,
): Pair<MarkdownListItem?, Int> {
    val indent = match.groupValues[1].replace("\t", "  ").length
    val level = (indent / 2).coerceIn(0, 2)
    val marker = match.groupValues[2]
    val text = match.groupValues[3].trim()
    if (text.isEmpty()) return null to orderedNumber
    if (!marker.first().isDigit()) return MarkdownListItem("•", level, text) to orderedNumber
    val start = if (orderedNumber == 0) marker.dropLast(1).toIntOrNull() ?: 1 else orderedNumber
    return MarkdownListItem("$start.", level, text) to (start + 1)
}

/** 普通段落：一直读到下一个块级起始行为止。 */
private fun readParagraph(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val parts = mutableListOf(lines[start].trim())
    var index = start + 1
    while (index < lines.size && !startsBlock(lines[index])) {
        parts += lines[index].trim()
        index++
    }
    blocks += MarkdownParagraph(joinParagraph(parts))
    return index
}

private fun readFencedCode(
    lines: List<String>,
    start: Int,
    fence: MatchResult,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val marker = fence.groupValues[1]
    val language = fence.groupValues[2].trim().take(32)
    val code = StringBuilder()
    var cursor = start + 1
    while (cursor < lines.size) {
        val closing = lines[cursor].trim()
        if (closing.length >= marker.length && closing.all { it == marker[0] }) {
            cursor++
            break
        }
        code.append(lines[cursor]).append('\n')
        cursor++
    }
    blocks += MarkdownCode(code.toString().trimEnd('\n'), language)
    return cursor
}

private fun startsBlock(raw: String): Boolean {
    val line = raw.trim()
    if (line.isEmpty()) return true
    if (line.startsWith("```") || line.startsWith("~~~")) return true
    if (headingPattern.matches(line)) return true
    if (rulePattern.matches(line)) return true
    if (line.startsWith(">")) return true
    if (isTableRow(line)) return true
    if (listPattern.matches(raw)) return true
    return false
}
