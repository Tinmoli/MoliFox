// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把 Markdown 行内文本渲染成带样式的 AnnotatedString。

package dev.molifox.desktop

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private const val MAX_INLINE_DEPTH = 8
private const val MAX_MARKER_FAILURES = 64

internal enum class InlineKind {
    Code,
    Bold,
    BoldItalic,
    Italic,
    Strike,
    Link,
    Image,
}

internal data class InlineMatch(
    val kind: InlineKind,
    val text: String,
    val url: String?,
    /** 匹配结束位置（不含）。 */
    val end: Int,
)

internal data class InlineLink(
    val start: Int,
    val end: Int,
    val url: String,
)

internal class InlineMarkup(
    val text: AnnotatedString,
    val links: List<InlineLink>,
)

internal fun buildInlineMarkup(
    source: String,
    palette: MarkdownPalette,
): InlineMarkup = InlineRenderer(palette).render(source)

private class InlineRenderer(
    palette: MarkdownPalette,
) {
    private val builder = AnnotatedString.Builder()
    private val links = mutableListOf<InlineLink>()
    private val linkStyle =
        SpanStyle(color = palette.primary, textDecoration = TextDecoration.Underline)
    private val codeStyle =
        SpanStyle(
            background = palette.codeBackground,
            color = palette.codeText,
        )
    private val boldTextStyle = SpanStyle(fontWeight = FontWeight.SemiBold)
    private val boldItalicTextStyle =
        SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
    private val italicTextStyle = SpanStyle(fontStyle = FontStyle.Italic)
    private val strikeTextStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)

    fun render(source: String): InlineMarkup {
        scan(source, SpanStyle(), null, 0)
        return InlineMarkup(builder.toAnnotatedString(), links.toList())
    }

    private fun scan(
        source: String,
        style: SpanStyle,
        link: String?,
        depth: Int,
    ) {
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
            val marker = isInlineMarker(current)
            // 连续匹配失败时放弃该类标记，避免病态输入造成回溯风暴。
            val match =
                if (
                    marker &&
                    depth < MAX_INLINE_DEPTH &&
                    (failures[current] ?: 0) < MAX_MARKER_FAILURES
                ) {
                    inlineMatchAt(source, cursor)
                } else {
                    null
                }
            if (match != null) {
                flush()
                consumeMatch(match, style, link, depth)
                cursor = match.end
            } else {
                if (marker) failures[current] = (failures[current] ?: 0) + 1
                pending.append(current)
                cursor++
            }
        }
        flush()
    }

    /** 处理一个识别到的内联标记：先冲刷普通文本，再按类型递归着色。 */
    private fun consumeMatch(
        match: InlineMatch,
        style: SpanStyle,
        link: String?,
        depth: Int,
    ) {
        when (match.kind) {
            InlineKind.Code -> emit(match.text, style + codeStyle, link)
            InlineKind.Bold -> scan(match.text, style + boldTextStyle, link, depth + 1)
            InlineKind.BoldItalic ->
                scan(match.text, style + boldItalicTextStyle, link, depth + 1)
            InlineKind.Italic -> scan(match.text, style + italicTextStyle, link, depth + 1)
            InlineKind.Strike -> scan(match.text, style + strikeTextStyle, link, depth + 1)
            InlineKind.Link, InlineKind.Image -> consumeLinkMatch(match, style, link, depth)
        }
    }

    /** 链接与图片共用一套递归：外层已经是链接时不再叠加。 */
    private fun consumeLinkMatch(
        match: InlineMatch,
        style: SpanStyle,
        link: String?,
        depth: Int,
    ) {
        val href = safeUrl(match.url)
        val label =
            if (match.kind == InlineKind.Image) {
                match.text.ifBlank { tr("pages.markdown.image") }
            } else {
                match.text
            }
        scan(label, style, if (href != null && link == null) href else link, depth + 1)
    }

    private fun emit(
        raw: String,
        style: SpanStyle,
        link: String?,
    ) {
        if (raw.isEmpty()) return
        val start = builder.length
        builder.append(raw)
        val end = builder.length
        val effective = if (link == null) style else style + linkStyle
        if (effective != SpanStyle()) builder.addStyle(effective, start, end)
        if (link != null) links += InlineLink(start, end, link)
    }
}

/** 可能开启内联标记的字符；其余字符直接当普通文本。 */
private fun isInlineMarker(value: Char): Boolean =
    when (value) {
        '`', '*', '_', '~', '[', '!' -> true
        else -> false
    }

private fun inlineMatchAt(
    source: String,
    index: Int,
): InlineMatch? {
    if (index < 0 || index >= source.length) return null
    return when (source[index]) {
        '`' -> codeMatchAt(source, index)
        '~' -> strikeInlineMatchAt(source, index)
        '*' -> starInlineMatchAt(source, index)
        '_' -> underscoreInlineMatchAt(source, index)
        '!' -> imageInlineMatchAt(source, index)
        '[' -> linkMatchAt(source, index, linkPattern, InlineKind.Link)
        else -> null
    }
}
