// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
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

internal data class MarkdownPalette(
    val text: Color,
    val muted: Color,
    val primary: Color,
    val codeBackground: Color,
    val codeText: Color,
    val rule: Color,
    val quoteBar: Color,
    val placeholder: Color,
)

internal sealed interface ImageSegment {
    data class Text(
        val text: String,
    ) : ImageSegment

    data class Image(
        val alt: String,
        val url: String,
    ) : ImageSegment
}

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
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(palette.quoteBar, RoundedCornerShape(2.dp)),
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
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .height(1.dp)
                    .background(palette.rule),
            )
    }
}

@Composable
private fun MarkdownCodeBlock(
    block: MarkdownCode,
    palette: MarkdownPalette,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.codeBackground,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    fontSize = 10.sp,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(
                    block.code,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
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
                    if (segment.text.isNotBlank()) {
                        InlineMarkdownText(
                            source = segment.text,
                            palette = palette,
                            modifier = Modifier.fillMaxWidth(),
                            style = style,
                            maxLines = maxLines,
                            onOpenUrl = onOpenUrl,
                        )
                    }
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
        if (match.range.first > cursor) {
            segments += ImageSegment.Text(source.substring(cursor, match.range.first))
        }
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
    val label = alt.ifBlank { tr("pages.markdown.image") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.placeholder,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (href == null) Modifier else Modifier.clickable { onOpenUrl(href) })
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(tr("pages.markdown.image"), fontSize = 11.sp, color = palette.primary, maxLines = 1)
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
                if (interactive) {
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
                } else {
                    Modifier
                },
            ),
        style = style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
    )
}
