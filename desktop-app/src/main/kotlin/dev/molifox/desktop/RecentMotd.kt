// 负责按服务器原色呈现两行居中的公告，以及完整文本悬停展示。

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.molifox.desktop

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.MotdSegment

@Composable
internal fun RecentTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(shadowElevation = 3.dp, shape = MaterialTheme.shapes.small) {
                Text(text, Modifier.widthIn(max = 460.dp).padding(10.dp), fontSize = 12.sp)
            }
        },
        modifier = modifier,
        delayMillis = 550,
        content = content,
    )
}

@Composable
internal fun RecentMotd(
    segments: List<MotdSegment>,
    modifier: Modifier = Modifier,
) {
    val full = segments.joinToString("") { it.text }
    val text = buildAnnotatedString {
        segments.forEach { segment ->
            val original = segment.color?.let { Color(0xFF000000L or it.toLong()) } ?: Color.White
            val decoration = buildList {
                if (segment.underlined) add(TextDecoration.Underline)
                if (segment.struck) add(TextDecoration.LineThrough)
            }
                .takeIf { it.isNotEmpty() }
                ?.let(TextDecoration::combine)
            withStyle(
                SpanStyle(
                    color = original,
                    fontWeight = if (segment.bold) FontWeight.SemiBold else null,
                    fontStyle = if (segment.italic) FontStyle.Italic else null,
                    textDecoration = decoration,
                )
            ) {
                append(segment.text)
            }
        }
    }
    RecentTooltip(full.ifBlank { "服务器尚未返回公告" }, modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                var offset = 0
                val lines = text.text.split('\n')
                lines
                    .take(2)
                    .ifEmpty { listOf("") }
                    .forEachIndexed { index, line ->
                        val slice =
                            if (full.isBlank()) AnnotatedString("—")
                            else text.subSequence(offset, offset + line.length)
                        val rendered =
                            if (index == 1 && lines.size > 2) slice + AnnotatedString("…")
                            else slice
                        Text(
                            rendered,
                            Modifier.wrapContentWidth(),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                ),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        offset += line.length + 1
                    }
            }
        }
    }
}
