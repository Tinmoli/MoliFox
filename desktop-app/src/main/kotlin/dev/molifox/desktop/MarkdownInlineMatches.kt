// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责行内标记的正则匹配：代码、强调、删除线与链接图片。

package dev.molifox.desktop

private const val MAX_EMPHASIS_CHARS = 2_000

internal val codePattern = Regex("(`+)(.+?)\\1", setOf(RegexOption.DOT_MATCHES_ALL))
internal val imagePattern = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
internal val linkPattern = Regex("\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
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

internal fun strikeInlineMatchAt(
    source: String,
    index: Int,
): InlineMatch? = if (source.startsWith("~~", index)) strikeMatchAt(source, index) else null

internal fun imageInlineMatchAt(
    source: String,
    index: Int,
): InlineMatch? =
    if (source.startsWith("![", index)) {
        linkMatchAt(source, index, imagePattern, InlineKind.Image)
    } else {
        null
    }

internal fun starInlineMatchAt(
    source: String,
    index: Int,
): InlineMatch? =
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

internal fun underscoreInlineMatchAt(
    source: String,
    index: Int,
): InlineMatch? {
    val previous = if (index == 0) ' ' else source[index - 1]
    return when {
        previous.isLetterOrDigit() -> null
        source.startsWith("__", index) ->
            emphasisMatchAt(source, index, boldUnderscorePattern, InlineKind.Bold)
                ?: emphasisMatchAt(source, index, italicUnderscorePattern, InlineKind.Italic)
        else -> emphasisMatchAt(source, index, italicUnderscorePattern, InlineKind.Italic)
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

private fun strikeMatchAt(
    source: String,
    index: Int,
): InlineMatch? {
    val hit = runCatching { strikePattern.matchAt(source, index) }.getOrNull() ?: return null
    val content = hit.groupValues.getOrNull(1).orEmpty()
    if (content.isEmpty() || content.length > MAX_EMPHASIS_CHARS) return null
    return InlineMatch(InlineKind.Strike, content, null, hit.range.last + 1)
}

internal fun codeMatchAt(
    source: String,
    index: Int,
): InlineMatch? {
    val hit = runCatching { codePattern.matchAt(source, index) }.getOrNull() ?: return null
    val content = hit.groupValues.getOrNull(2).orEmpty()
    if (content.isEmpty()) return null
    return InlineMatch(InlineKind.Code, content, null, hit.range.last + 1)
}

internal fun linkMatchAt(
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
