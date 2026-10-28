// 负责资源介绍和日志的 Markdown 文本显示。

package dev.molifox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：项目介绍来自 Modrinth 的 body，常混杂 CommonMark 与内联 HTML。
 *
 * 设计约束：
 * - 不引入任何新依赖，不使用 WebView；图片只显示占位行与域名，不下载位图，离线安全。
 * - 解析失败或语法不认识时按普通文本输出，任何畸形输入都不抛异常。
 * - 所有可点击链接只接受 http/https，点击后交给系统浏览器。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    if (blocks.isEmpty()) return
    val lines = maxLines.coerceAtLeast(1)
    val colors = MaterialTheme.colorScheme
    // 按具体颜色值记忆，避免配色对象身份变化时整段重新解析。
    val palette =
        remember(
            colors.primary,
            colors.onSurface,
            colors.onSurfaceVariant,
            colors.surfaceVariant,
            colors.outlineVariant,
            colors.surfaceContainerLow,
        ) {
            MarkdownPalette(
                text = colors.onSurface,
                muted = colors.onSurfaceVariant,
                primary = colors.primary,
                codeBackground = colors.surfaceVariant,
                codeText = colors.onSurface,
                rule = colors.outlineVariant,
                quoteBar = colors.primary.copy(alpha = 0.5f),
                placeholder = colors.surfaceContainerLow,
            )
        }
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit =
        remember(uriHandler) {
            { url: String ->
                runCatching { uriHandler.openUri(url) }
                Unit
            }
        }
    SelectionContainer(modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEachIndexed { position, block ->
                key(position, block) { MarkdownBlockView(block, lines, palette, openUrl) }
            }
        }
    }
}

private data class MarkdownPalette(
    val text: Color,
    val muted: Color,
    val primary: Color,
    val codeBackground: Color,
    val codeText: Color,
    val rule: Color,
    val quoteBar: Color,
    val placeholder: Color,
)

// ---------------------------------------------------------------- 块级模型

private sealed interface MarkdownBlock

private data class MarkdownHeading(val level: Int, val text: String) : MarkdownBlock

private data class MarkdownParagraph(val text: String) : MarkdownBlock

private data class MarkdownQuote(val text: String) : MarkdownBlock

private data class MarkdownListItem(val marker: String, val level: Int, val text: String)

private data class MarkdownList(val items: List<MarkdownListItem>) : MarkdownBlock

private data class MarkdownCode(val code: String, val language: String) : MarkdownBlock

private data class MarkdownTable(val rows: List<List<String>>) : MarkdownBlock

private data object MarkdownRule : MarkdownBlock

// ---------------------------------------------------------------- 行内模型

private enum class InlineKind {
    Code,
    Bold,
    BoldItalic,
    Italic,
    Strike,
    Link,
    Image,
}

private data class InlineMatch(
    val kind: InlineKind,
    val text: String,
    val url: String?,
    /** 匹配结束位置（不含）。 */
    val end: Int,
)

private data class InlineLink(val start: Int, val end: Int, val url: String)

private class InlineMarkup(val text: AnnotatedString, val links: List<InlineLink>)

private sealed interface ImageSegment {
    data class Text(val text: String) : ImageSegment

    data class Image(val alt: String, val url: String) : ImageSegment
}

// ---------------------------------------------------------------- 正则

private const val MAX_MARKDOWN_CHARS = 200_000
private const val MAX_INLINE_DEPTH = 8
private const val MAX_MARKER_FAILURES = 64
private const val MAX_EMPHASIS_CHARS = 2_000

private val scriptPattern = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
private val brPattern = Regex("(?i)<br\\s*/?>")
private val blockTagPattern =
    Regex("(?i)</(?:p|div|li|ul|ol|h[1-6]|tr|td|th|table|blockquote|details|summary|section)>")
private val tagPattern = Regex("</?[A-Za-z][^<>]*>")
private val fencePattern = Regex("^(`{3,}|~{3,})[ \\t]*(.*)$")
private val headingPattern = Regex("^(#{1,6})[ \\t]+(.*)$")
private val rulePattern = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
private val listPattern = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])[ \\t]+(.*)$")

private val codePattern = Regex("(`+)(.+?)\\1", setOf(RegexOption.DOT_MATCHES_ALL))
private val imagePattern = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
private val linkPattern = Regex("\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
private val boldItalicPattern =
    Regex("\\*\\*\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL))
private val boldStarPattern =
    Regex("\\*\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*\\*", setOf(RegexOption.DOT_MATCHES_ALL))
private val italicStarPattern =
    Regex("\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*", setOf(RegexOption.DOT_MATCHES_ALL))
private val boldUnderscorePattern =
    Regex("__(?=[^\\s_])(.+?)(?<=[^\\s_])__", setOf(RegexOption.DOT_MATCHES_ALL))
private val italicUnderscorePattern =
    Regex("_(?=[^\\s_])(.+?)(?<=[^\\s_])_", setOf(RegexOption.DOT_MATCHES_ALL))
private val strikePattern =
    Regex("~~(?=[^\\s~])(.+?)(?<=[^\\s~])~~", setOf(RegexOption.DOT_MATCHES_ALL))

// ---------------------------------------------------------------- 预处理

private fun sanitizeMarkdown(markdown: String): String {
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

// ---------------------------------------------------------------- 块级解析

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val source = sanitizeMarkdown(markdown)
    if (source.isBlank()) return emptyList()
    val lines = source.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trim()
        if (line.isEmpty()) {
            index++
            continue
        }
        val fence = fencePattern.matchEntire(line)
        if (fence != null) {
            index = readFencedCode(lines, index, fence, blocks)
            continue
        }
        val heading = headingPattern.matchEntire(line)
        if (heading != null) {
            blocks += MarkdownHeading(heading.groupValues[1].length, heading.groupValues[2].trim())
            index++
            continue
        }
        if (rulePattern.matches(line)) {
            blocks += MarkdownRule
            index++
            continue
        }
        if (line.startsWith(">")) {
            val quoted = mutableListOf<String>()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                quoted += lines[index].trim().removePrefix(">").trimStart()
                index++
            }
            blocks += MarkdownQuote(quoted.joinToString("\n"))
            continue
        }
        if (isTableRow(line)) {
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && isTableRow(lines[index].trim())) {
                val cells = splitTableRow(lines[index])
                if (!isTableDelimiterRow(cells) && cells.any { it.isNotEmpty() }) rows += cells
                index++
            }
            if (rows.isNotEmpty()) blocks += MarkdownTable(rows)
            continue
        }
        if (listPattern.matchEntire(raw) != null) {
            val items = mutableListOf<MarkdownListItem>()
            var orderedNumber = 0
            var cursor = index
            while (cursor < lines.size) {
                val item = listPattern.matchEntire(lines[cursor]) ?: break
                val indent = item.groupValues[1].replace("\t", "  ").length
                val level = (indent / 2).coerceIn(0, 2)
                val marker = item.groupValues[2]
                val text = item.groupValues[3].trim()
                if (text.isNotEmpty()) {
                    val label =
                        if (marker.first().isDigit()) {
                            // 保留作者起始序号，后续条目顺延。
                            if (orderedNumber == 0)
                                orderedNumber = marker.dropLast(1).toIntOrNull() ?: 1
                            "${orderedNumber++}."
                        } else "•"
                    items += MarkdownListItem(label, level, text)
                }
                cursor++
            }
            if (items.isNotEmpty()) blocks += MarkdownList(items)
            // 空列表项也要前进，避免死循环。
            index = maxOf(cursor, index + 1)
            continue
        }
        val parts = mutableListOf(line)
        index++
        while (index < lines.size && !startsBlock(lines[index])) {
            parts += lines[index].trim()
            index++
        }
        blocks += MarkdownParagraph(joinParagraph(parts))
    }
    return blocks
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

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.length > 2 && trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun isTableDelimiterRow(cells: List<String>): Boolean {
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val body = cell.replace(":", "").trim()
        body.isNotEmpty() && body.all { it == '-' }
    }
}

/** CommonMark 软换行：中文之间不插空格，西文之间插一个空格。 */
private fun joinParagraph(parts: List<String>): String {
    if (parts.size <= 1) return parts.firstOrNull().orEmpty()
    val joined = StringBuilder(parts.first())
    for (part in parts.drop(1)) {
        val previous = joined.lastOrNull() ?: ' '
        if (isWideChar(previous) || isWideChar(part.firstOrNull() ?: ' ')) joined.append(part)
        else joined.append(' ').append(part)
    }
    return joined.toString()
}

private fun isWideChar(value: Char) = value.code >= 0x2E80

// ---------------------------------------------------------------- 行内解析

private fun buildInlineMarkup(source: String, palette: MarkdownPalette): InlineMarkup =
    InlineRenderer(palette).render(source)

private class InlineRenderer(private val palette: MarkdownPalette) {
    private val builder = AnnotatedString.Builder()
    private val links = mutableListOf<InlineLink>()
    private val linkStyle =
        SpanStyle(color = palette.primary, textDecoration = TextDecoration.Underline)
    private val codeStyle =
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            background = palette.codeBackground,
            color = palette.codeText,
        )

    fun render(source: String): InlineMarkup {
        scan(source, SpanStyle(), null, 0)
        return InlineMarkup(builder.toAnnotatedString(), links.toList())
    }

    private fun scan(source: String, style: SpanStyle, link: String?, depth: Int) {
        val pending = StringBuilder()
        val failures = HashMap<Char, Int>()
        fun flush() {
            if (pending.isNotEmpty()) {
                emit(pending.toString(), style, link)
                pending.clear()
            }
        }
        var cursor = 0
        while (cursor < source.length) {
            val current = source[cursor]
            if (current == '\\' && cursor + 1 < source.length) {
                pending.append(source[cursor + 1])
                cursor += 2
                continue
            }
            val marker =
                current == '`' ||
                    current == '*' ||
                    current == '_' ||
                    current == '~' ||
                    current == '[' ||
                    current == '!'
            // 连续匹配失败时放弃该类标记，避免病态输入造成回溯风暴。
            val match =
                if (
                    marker &&
                        depth < MAX_INLINE_DEPTH &&
                        (failures[current] ?: 0) < MAX_MARKER_FAILURES
                )
                    inlineMatchAt(source, cursor)
                else null
            if (match == null) {
                if (marker) failures[current] = (failures[current] ?: 0) + 1
                pending.append(current)
                cursor++
                continue
            }
            flush()
            when (match.kind) {
                InlineKind.Code -> emit(match.text, style + codeStyle, link)
                InlineKind.Bold ->
                    scan(
                        match.text,
                        style + SpanStyle(fontWeight = FontWeight.SemiBold),
                        link,
                        depth + 1,
                    )
                InlineKind.BoldItalic ->
                    scan(
                        match.text,
                        style +
                            SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                fontStyle = FontStyle.Italic,
                            ),
                        link,
                        depth + 1,
                    )
                InlineKind.Italic ->
                    scan(
                        match.text,
                        style + SpanStyle(fontStyle = FontStyle.Italic),
                        link,
                        depth + 1,
                    )
                InlineKind.Strike ->
                    scan(
                        match.text,
                        style + SpanStyle(textDecoration = TextDecoration.LineThrough),
                        link,
                        depth + 1,
                    )
                InlineKind.Link,
                InlineKind.Image -> {
                    val href = safeUrl(match.url)
                    val label =
                        if (match.kind == InlineKind.Image) match.text.ifBlank { "图片" }
                        else match.text
                    if (href != null && link == null) scan(label, style, href, depth + 1)
                    else scan(label, style, link, depth + 1)
                }
            }
            cursor = match.end
        }
        flush()
    }

    private fun emit(raw: String, style: SpanStyle, link: String?) {
        if (raw.isEmpty()) return
        val start = builder.length
        builder.append(raw)
        val end = builder.length
        val effective = if (link == null) style else style + linkStyle
        if (effective != SpanStyle()) builder.addStyle(effective, start, end)
        if (link != null) links += InlineLink(start, end, link)
    }
}

private fun inlineMatchAt(source: String, index: Int): InlineMatch? {
    if (index < 0 || index >= source.length) return null
    val marker = source[index]
    return when (marker) {
        '`' -> codeMatchAt(source, index)
        '~' -> if (source.startsWith("~~", index)) strikeMatchAt(source, index) else null
        '*' ->
            when {
                source.startsWith("***", index) ->
                    emphasisMatchAt(source, index, boldItalicPattern, InlineKind.BoldItalic)
                        ?: emphasisMatchAt(source, index, boldStarPattern, InlineKind.Bold)
                        ?: emphasisMatchAt(source, index, italicStarPattern, InlineKind.Italic)
                source.startsWith("**", index) ->
                    emphasisMatchAt(source, index, boldStarPattern, InlineKind.Bold)
                        ?: emphasisMatchAt(source, index, italicStarPattern, InlineKind.Italic)
                else -> emphasisMatchAt(source, index, italicStarPattern, InlineKind.Italic)
            }
        '_' -> {
            val previous = if (index == 0) ' ' else source[index - 1]
            when {
                previous.isLetterOrDigit() -> null
                source.startsWith("__", index) ->
                    emphasisMatchAt(source, index, boldUnderscorePattern, InlineKind.Bold)
                        ?: emphasisMatchAt(
                            source,
                            index,
                            italicUnderscorePattern,
                            InlineKind.Italic,
                        )
                else -> emphasisMatchAt(source, index, italicUnderscorePattern, InlineKind.Italic)
            }
        }
        '!' ->
            if (source.startsWith("![", index))
                linkMatchAt(source, index, imagePattern, InlineKind.Image)
            else null
        '[' -> linkMatchAt(source, index, linkPattern, InlineKind.Link)
        else -> null
    }
}

private fun emphasisMatchAt(
    source: String,
    index: Int,
    pattern: Regex,
    kind: InlineKind,
): InlineMatch? {
    val hit = runCatching { pattern.matchAt(source, index) }.getOrNull() ?: return null
    val content = hit.groupValues.getOrNull(1).orEmpty()
    if (content.isEmpty() || content.length > MAX_EMPHASIS_CHARS) return null
    return InlineMatch(kind, content, null, hit.range.last + 1)
}

private fun strikeMatchAt(source: String, index: Int): InlineMatch? {
    val hit = runCatching { strikePattern.matchAt(source, index) }.getOrNull() ?: return null
    val content = hit.groupValues.getOrNull(1).orEmpty()
    if (content.isEmpty() || content.length > MAX_EMPHASIS_CHARS) return null
    return InlineMatch(InlineKind.Strike, content, null, hit.range.last + 1)
}

private fun codeMatchAt(source: String, index: Int): InlineMatch? {
    val hit = runCatching { codePattern.matchAt(source, index) }.getOrNull() ?: return null
    val content = hit.groupValues.getOrNull(2).orEmpty()
    if (content.isEmpty()) return null
    return InlineMatch(InlineKind.Code, content, null, hit.range.last + 1)
}

private fun linkMatchAt(
    source: String,
    index: Int,
    pattern: Regex,
    kind: InlineKind,
): InlineMatch? {
    val hit = runCatching { pattern.matchAt(source, index) }.getOrNull() ?: return null
    val text = hit.groupValues.getOrNull(1).orEmpty()
    val url = hit.groupValues.getOrNull(2).orEmpty()
    if (url.isEmpty()) return null
    return InlineMatch(kind, text, url, hit.range.last + 1)
}

/** 只放行 http/https；相对地址与伪协议按普通文字处理。 */
private fun safeUrl(raw: String?): String? {
    val url = raw?.trim()?.trim('<', '>')?.take(2_048) ?: return null
    if (url.isEmpty()) return null
    val lower = url.lowercase()
    return if (lower.startsWith("https://") || lower.startsWith("http://")) url else null
}

private fun urlHost(url: String): String {
    val rest = url.substringAfter("://", url)
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').ifBlank { "链接" }
    return host.take(48)
}

// ---------------------------------------------------------------- 渲染

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    maxLines: Int,
    palette: MarkdownPalette,
    onOpenUrl: (String) -> Unit,
) {
    val body = MaterialTheme.typography.bodySmall.copy(color = palette.text, lineHeight = 18.sp)
    when (block) {
        is MarkdownHeading -> {
            val size =
                when (block.level) {
                    1 -> 17.sp
                    2 -> 15.sp
                    else -> 14.sp
                }
            InlineMarkdownText(
                source = block.text,
                palette = palette,
                modifier =
                    Modifier.fillMaxWidth().padding(top = if (block.level <= 2) 4.dp else 2.dp),
                style =
                    body.copy(
                        fontSize = size,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = (size.value * 1.35f).sp,
                    ),
                maxLines = maxLines,
                onOpenUrl = onOpenUrl,
            )
        }
        is MarkdownParagraph ->
            ImageAwareText(
                source = block.text,
                palette = palette,
                onOpenUrl = onOpenUrl,
                modifier = Modifier.fillMaxWidth(),
                style = body,
                maxLines = maxLines,
            )
        is MarkdownQuote ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    Modifier.width(3.dp)
                        .fillMaxHeight()
                        .background(palette.quoteBar, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                ImageAwareText(
                    source = block.text,
                    palette = palette,
                    onOpenUrl = onOpenUrl,
                    modifier = Modifier.weight(1f),
                    style = body.copy(color = palette.muted),
                    maxLines = maxLines,
                )
            }
        is MarkdownList ->
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                block.items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = (item.level * 14).dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            item.marker,
                            Modifier.width(20.dp),
                            textAlign = TextAlign.End,
                            style = body.copy(color = palette.muted, fontSize = 12.sp),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        ImageAwareText(
                            source = item.text,
                            palette = palette,
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.weight(1f),
                            style = body,
                            maxLines = maxLines,
                        )
                    }
                }
            }
        is MarkdownCode -> MarkdownCodeBlock(block, palette)
        is MarkdownTable ->
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                block.rows.forEach { row ->
                    InlineMarkdownText(
                        source = row.joinToString(" | "),
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                        style =
                            body.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            ),
                        maxLines = maxLines,
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
        MarkdownRule ->
            Box(
                Modifier.fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .height(1.dp)
                    .background(palette.rule)
            )
    }
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownCode, palette: MarkdownPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.codeBackground,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (block.language.isNotBlank())
                Text(
                    block.language,
                    fontSize = 10.sp,
                    color = palette.muted,
                    maxLines = 1,
                )
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(
                    block.code,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = palette.codeText,
                        ),
                    softWrap = false,
                )
            }
        }
    }
}

/** 普通行内富文本；图片在段落级被拆出为占位行。 */
@Composable
private fun ImageAwareText(
    source: String,
    palette: MarkdownPalette,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier,
    style: TextStyle,
    maxLines: Int,
) {
    val segments = remember(source) { splitImageSegments(source) }
    if (segments.size == 1) {
        val only = segments.first()
        if (only is ImageSegment.Text) {
            InlineMarkdownText(
                source = only.text,
                palette = palette,
                modifier = modifier,
                style = style,
                maxLines = maxLines,
                onOpenUrl = onOpenUrl,
            )
            return
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is ImageSegment.Text ->
                    if (segment.text.isNotBlank())
                        InlineMarkdownText(
                            source = segment.text,
                            palette = palette,
                            modifier = Modifier.fillMaxWidth(),
                            style = style,
                            maxLines = maxLines,
                            onOpenUrl = onOpenUrl,
                        )
                is ImageSegment.Image ->
                    ImagePlaceholderRow(segment.alt, segment.url, palette, onOpenUrl)
            }
        }
    }
}

private fun splitImageSegments(source: String): List<ImageSegment> {
    val segments = mutableListOf<ImageSegment>()
    var cursor = 0
    for (match in imagePattern.findAll(source)) {
        if (match.range.first > cursor)
            segments += ImageSegment.Text(source.substring(cursor, match.range.first))
        segments += ImageSegment.Image(match.groupValues[1].trim(), match.groupValues[2].trim())
        cursor = match.range.last + 1
    }
    if (cursor < source.length) segments += ImageSegment.Text(source.substring(cursor))
    if (segments.isEmpty()) segments += ImageSegment.Text(source)
    return segments
}

@Composable
private fun ImagePlaceholderRow(
    alt: String,
    url: String,
    palette: MarkdownPalette,
    onOpenUrl: (String) -> Unit,
) {
    val href = safeUrl(url)
    val host = remember(url) { urlHost(url) }
    val label = alt.ifBlank { "图片" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.placeholder,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (href == null) Modifier else Modifier.clickable { onOpenUrl(href) })
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("图片", fontSize = 11.sp, color = palette.primary, maxLines = 1)
            Text(
                label,
                Modifier.weight(1f),
                fontSize = 11.5.sp,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                host,
                fontSize = 10.5.sp,
                color = palette.primary,
                textDecoration = if (href == null) null else TextDecoration.Underline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InlineMarkdownText(
    source: String,
    palette: MarkdownPalette,
    modifier: Modifier,
    style: TextStyle,
    maxLines: Int,
    onOpenUrl: (String) -> Unit,
) {
    val markup = remember(source, palette) { buildInlineMarkup(source, palette) }
    var layout by remember(markup) { mutableStateOf<TextLayoutResult?>(null) }
    val interactive = markup.links.isNotEmpty()
    Text(
        markup.text,
        modifier =
            modifier.then(
                if (interactive)
                    // 不消费按下事件，保留 SelectionContainer 的拖选能力；
                    // 只有指针抬起且未被选中手势取消时才算点击链接。
                    Modifier.pointerInput(markup) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val release = waitForUpOrCancellation()
                            if (release != null) {
                                val result = layout ?: return@awaitEachGesture
                                val offset = result.getOffsetForPosition(release.position)
                                markup.links
                                    .firstOrNull { offset >= it.start && offset < it.end }
                                    ?.let { link -> onOpenUrl(link.url) }
                            }
                        }
                    }
                else Modifier
            ),
        style = style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
    )
}
